import 'reflect-metadata';
import assert from 'node:assert/strict';
import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { NestFactory } from '@nestjs/core';
import { drizzle } from 'drizzle-orm/node-postgres';
import { migrate } from 'drizzle-orm/node-postgres/migrator';
import { Pool } from 'pg';
import { AppModule } from '../dist/app.module.js';
import { AuthGuard } from '../dist/auth/auth.guard.js';
import { AuthService } from '../dist/auth/auth.service.js';

const testDatabaseUrl = process.env.TEST_DATABASE_URL;

test(
  '회원가입·로그인 PostgreSQL 통합 검사',
  {
    skip: testDatabaseUrl ? false : 'TEST_DATABASE_URL 지정 시 실행',
    timeout: 120_000,
  },
  async (t) => {
    const parsedUrl = new URL(testDatabaseUrl);
    const databaseName = decodeURIComponent(parsedUrl.pathname.slice(1));
    assert.ok(
      databaseName.endsWith('_test'),
      '실제 개발 DB 대신 이름이 _test로 끝나는 별도 DB를 사용해야 함',
    );
    const previousUrl = process.env.DATABASE_URL;
    process.env.DATABASE_URL = testDatabaseUrl;
    const database = new Pool({
      connectionString: testDatabaseUrl,
      max: 2,
      connectionTimeoutMillis: 5000,
    });
    const createdEmails = new Set();
    const prefix = randomUUID();
    const password = 'samplePassword123';
    let app;
    let mainUser;
    let mainToken;
    let counter = 0;

    function emailFor(label) {
      const email = `${prefix}-${label}-${counter++}@example.com`;
      createdEmails.add(email);
      return email;
    }

    t.after(async () => {
      try {
        await app?.close();
        // 이번 실행에서 만든 계정만 정리. DB 전체 초기화·기존 계정 삭제 금지.
        if (createdEmails.size > 0) {
          await database.query(
            'DELETE FROM users WHERE email = ANY($1::text[])',
            [[...createdEmails]],
          );
        }
      } finally {
        await database.end();
        if (previousUrl === undefined) delete process.env.DATABASE_URL;
        else process.env.DATABASE_URL = previousUrl;
      }
    });

    const connectedDatabase = await database.query('SELECT current_database()');
    assert.equal(connectedDatabase.rows[0].current_database, databaseName);
    await migrate(drizzle(database), {
      migrationsFolder: fileURLToPath(new URL('../drizzle', import.meta.url)),
    });
    app = await NestFactory.create(AppModule, { logger: false });
    await app.listen(0, '127.0.0.1');
    const baseUrl = await app.getUrl();
    const auth = app.get(AuthService);
    const guard = app.get(AuthGuard);

    async function post(path, body, { raw = false } = {}) {
      const response = await fetch(`${baseUrl}/auth/${path}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: raw ? body : JSON.stringify(body),
        signal: AbortSignal.timeout(10_000),
      });
      assert.equal(response.headers.get('cache-control'), 'no-store');
      return {
        status: response.status,
        headers: response.headers,
        body: await response.json(),
      };
    }

    function expectError(response, status, code) {
      assert.equal(response.status, status);
      assert.deepEqual(Object.keys(response.body), ['error']);
      assert.deepEqual(Object.keys(response.body.error).sort(), [
        'code',
        'message',
      ]);
      assert.equal(response.body.error.code, code);
      assert.equal(typeof response.body.error.message, 'string');
      assert.ok(response.body.error.message.length > 0);
    }

    function hashToken(token) {
      return createHash('sha256').update(token).digest('hex');
    }

    async function expectUnauthorized(action) {
      await assert.rejects(action, (error) => {
        assert.equal(error.getStatus(), 401);
        return true;
      });
    }

    await t.test(
      '가입·로그인 응답과 DB의 비밀번호·토큰 저장 방식',
      async () => {
        const email = emailFor('main');
        const signup = await post('signup', {
          name: '  홍길동  ',
          email: `  ${email.toUpperCase()}  `,
          password,
        });
        assert.equal(signup.status, 201);
        assert.deepEqual(Object.keys(signup.body), ['user']);
        mainUser = signup.body.user;
        assert.deepEqual(Object.keys(mainUser).sort(), ['email', 'id', 'name']);
        assert.equal(typeof mainUser.id, 'string');
        assert.ok(mainUser.id.length > 0);
        assert.equal(mainUser.name, '홍길동');
        assert.equal(mainUser.email, email);
        const users = await database.query(
          'SELECT name, email, password_hash FROM users WHERE id = $1',
          [mainUser.id],
        );
        assert.equal(users.rowCount, 1);
        assert.equal(users.rows[0].name, '홍길동');
        assert.equal(users.rows[0].email, email);
        assert.equal(typeof users.rows[0].password_hash, 'string');
        assert.ok(users.rows[0].password_hash !== password);
        assert.ok(!users.rows[0].password_hash.includes(password));

        const beforeLogin = Date.now();
        const login = await post('login', {
          email: ` ${email.toUpperCase()} `,
          password,
        });
        const afterLogin = Date.now();
        assert.equal(login.status, 200);
        assert.deepEqual(Object.keys(login.body).sort(), [
          'accessToken',
          'expiresAt',
          'tokenType',
          'user',
        ]);
        assert.deepEqual(login.body.user, mainUser);
        assert.equal(login.body.tokenType, 'Bearer');
        assert.equal(typeof login.body.accessToken, 'string');
        assert.ok(login.body.accessToken.length >= 43);
        assert.match(login.body.expiresAt, /^\d{4}-\d{2}-\d{2}T.+Z$/);
        const expiresAt = Date.parse(login.body.expiresAt);
        const sevenDays = 7 * 24 * 60 * 60 * 1000;
        assert.ok(expiresAt >= beforeLogin + sevenDays);
        assert.ok(expiresAt <= afterLogin + sevenDays);
        mainToken = login.body.accessToken;
        const sessions = await database.query(
          'SELECT token_hash, expires_at FROM sessions WHERE user_id = $1',
          [mainUser.id],
        );
        assert.equal(sessions.rowCount, 1);
        assert.ok(sessions.rows[0].token_hash !== mainToken);
        assert.ok(sessions.rows[0].token_hash === hashToken(mainToken));
        assert.equal(sessions.rows[0].expires_at.getTime(), expiresAt);
      },
    );

    await t.test(
      '정규화한 이메일 중복 및 동시 가입을 DB에서 방지',
      async () => {
        expectError(
          await post('signup', {
            name: '중복 가입',
            email: ` ${mainUser.email.toUpperCase()} `,
            password,
          }),
          409,
          'EMAIL_ALREADY_EXISTS',
        );
        const email = emailFor('race');
        const responses = await Promise.all([
          post('signup', { name: '동시 요청', email, password }),
          post('signup', {
            name: '동시 요청',
            email: ` ${email.toUpperCase()} `,
            password,
          }),
        ]);
        assert.deepEqual(
          responses.map((response) => response.status).sort(),
          [201, 409],
        );
        expectError(
          responses.find((response) => response.status === 409),
          409,
          'EMAIL_ALREADY_EXISTS',
        );
        const users = await database.query(
          'SELECT id FROM users WHERE email = $1',
          [email],
        );
        assert.equal(users.rowCount, 1);
      },
    );

    await t.test(
      '필수 값·타입·길이·JSON 오류는 400이며 계정 생성 안 됨',
      async () => {
        const email = emailFor('invalid');
        const valid = { name: '입력 검사', email, password };
        const invalidRequests = [
          {},
          null,
          [],
          { email, password },
          { ...valid, name: ' \t ' },
          { ...valid, email: 'not-an-email' },
          { ...valid, email: 123 },
          { ...valid, password: 123 },
          { ...valid, password: 'a'.repeat(11) },
          { ...valid, password: 'a'.repeat(21) },
          { ...valid, unexpectedField: 'not-allowed' },
        ];
        for (const body of invalidRequests) {
          expectError(await post('signup', body), 400, 'VALIDATION_ERROR');
        }
        expectError(
          await post('login', { email, password: '' }),
          400,
          'VALIDATION_ERROR',
        );
        expectError(
          await post('login', '{"email":', { raw: true }),
          400,
          'VALIDATION_ERROR',
        );
        expectError(
          await post('signup', { ...valid, name: 'a'.repeat(100 * 1024) }),
          413,
          'VALIDATION_ERROR',
        );
        const users = await database.query(
          'SELECT id FROM users WHERE email = $1',
          [email],
        );
        assert.equal(users.rowCount, 0);
      },
    );

    await t.test(
      '비밀번호 12·20자 허용, 공백·대소문자는 변경 없이 비교',
      async () => {
        for (const length of [12, 20]) {
          const email = emailFor(`length${length}`);
          const boundaryPassword = 'a'.repeat(length);
          assert.equal(
            (
              await post('signup', {
                name: '비밀번호 경계',
                email,
                password: boundaryPassword,
              })
            ).status,
            201,
          );
          assert.equal(
            (await post('login', { email, password: boundaryPassword })).status,
            200,
          );
        }
        const email = emailFor('spaces');
        const spacedPassword = ' samplePassword123 ';
        assert.equal(
          (
            await post('signup', {
              name: '공백 비밀번호',
              email,
              password: spacedPassword,
            })
          ).status,
          201,
        );
        assert.equal(
          (await post('login', { email, password: spacedPassword })).status,
          200,
        );
        for (const changed of [
          spacedPassword.trim(),
          spacedPassword.toUpperCase(),
        ]) {
          expectError(
            await post('login', { email, password: changed }),
            401,
            'INVALID_CREDENTIALS',
          );
        }
      },
    );

    await t.test('없는 이메일과 틀린 비밀번호는 같은 401 응답', async () => {
      const wrongPassword = await post('login', {
        email: mainUser.email,
        password: 'anotherPassword123',
      });
      const missingAccount = await post('login', {
        email: emailFor('missing'),
        password,
      });
      expectError(wrongPassword, 401, 'INVALID_CREDENTIALS');
      expectError(missingAccount, 401, 'INVALID_CREDENTIALS');
      assert.deepEqual(wrongPassword.body, missingAccount.body);
    });

    await t.test(
      '토큰으로 사용자 확인, 인증 헤더 누락·잘못된 토큰 거부',
      async () => {
        assert.deepEqual(await auth.authenticate(mainToken), mainUser);
        const request = {
          headers: { authorization: `Bearer ${mainToken}` },
        };
        const context = {
          switchToHttp: () => ({ getRequest: () => request }),
        };
        assert.equal(await guard.canActivate(context), true);
        assert.deepEqual(request.user, mainUser);
        for (const authorization of [
          undefined,
          'Basic invalid',
          'Bearer',
          'Bearer invalid extra',
          `Bearer ${randomBytes(32).toString('base64url')}`,
        ]) {
          const invalidContext = {
            switchToHttp: () => ({
              getRequest: () => ({ headers: { authorization } }),
            }),
          };
          await expectUnauthorized(() => guard.canActivate(invalidContext));
        }
      },
    );

    await t.test(
      '토큰 사용으로 만료가 연장되지 않고 만료 후 재로그인 가능',
      async () => {
        const before = await database.query(
          'SELECT expires_at FROM sessions WHERE token_hash = $1',
          [hashToken(mainToken)],
        );
        await auth.authenticate(mainToken);
        const after = await database.query(
          'SELECT expires_at FROM sessions WHERE token_hash = $1',
          [hashToken(mainToken)],
        );
        assert.equal(
          after.rows[0].expires_at.getTime(),
          before.rows[0].expires_at.getTime(),
        );
        await database.query(
          "UPDATE sessions SET expires_at = NOW() - INTERVAL '1 second' WHERE token_hash = $1",
          [hashToken(mainToken)],
        );
        await expectUnauthorized(() => auth.authenticate(mainToken));
        const login = await post('login', {
          email: mainUser.email,
          password,
        });
        assert.equal(login.status, 200);
        assert.ok(login.body.accessToken !== mainToken);
        assert.deepEqual(
          await auth.authenticate(login.body.accessToken),
          mainUser,
        );
        await expectUnauthorized(() => auth.authenticate(mainToken));
      },
    );

    await t.test(
      'DB 오류는 내부 정보 없는 500이며 연결 복구 후 로그인 가능',
      async (subtest) => {
        const query = subtest.mock.method(Pool.prototype, 'query', async () => {
          throw new Error(
            'postgresql://private-user:secret@private-db/internal',
          );
        });
        const failedLogin = await post('login', {
          email: mainUser.email,
          password,
        });
        expectError(failedLogin, 500, 'SERVER_ERROR');
        const serialized = JSON.stringify(failedLogin.body);
        for (const privateValue of ['secret', 'private-db', 'postgresql://']) {
          assert.ok(!serialized.includes(privateValue));
        }
        query.mock.restore();
        const recovered = await post('login', {
          email: mainUser.email,
          password,
        });
        assert.equal(recovered.status, 200);
        assert.deepEqual(recovered.body.user, mainUser);
      },
    );

    await t.test(
      '로그인 반복 요청은 429와 재시도 대기 시간을 반환',
      async () => {
        let limited;
        // 잘못된 본문으로 요청해 불필요한 비밀번호 해시 연산을 피한다.
        for (let attempt = 0; attempt < 61; attempt++) {
          const response = await post('login', {});
          if (response.status === 429) {
            limited = response;
            break;
          }
          expectError(response, 400, 'VALIDATION_ERROR');
        }
        assert.ok(limited, '분당 요청 제한이 적용되어야 함');
        expectError(limited, 429, 'RATE_LIMITED');
        assert.ok(Number(limited.headers.get('retry-after')) > 0);
        expectError(
          await post('login', { email: mainUser.email, password }),
          429,
          'RATE_LIMITED',
        );
        // 로그인 제한이 별도 회원가입 요청까지 막지 않는지 확인.
        assert.equal(
          (
            await post('signup', {
              name: '별도 요청 제한',
              email: emailFor('limit'),
              password,
            })
          ).status,
          201,
        );
      },
    );
  },
);
