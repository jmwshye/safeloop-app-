import 'reflect-metadata';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { NestFactory } from '@nestjs/core';
import { drizzle } from 'drizzle-orm/node-postgres';
import { migrate } from 'drizzle-orm/node-postgres/migrator';
import { Pool } from 'pg';
import { AppModule } from '../dist/app.module.js';
import { AuthService } from '../dist/auth/auth.service.js';

const testDatabaseUrl = process.env.TEST_DATABASE_URL;

test(
  '현장 확인·참여 PostgreSQL 통합 검사',
  {
    skip: testDatabaseUrl ? false : 'TEST_DATABASE_URL 지정 시 실행',
    timeout: 120_000,
  },
  async (t) => {
    const dbName = decodeURIComponent(
      new URL(testDatabaseUrl).pathname.slice(1),
    );
    assert.ok(dbName.endsWith('_test'), '이름이 _test로 끝나는 별도 DB만 사용');
    const db = new Pool({
      connectionString: testDatabaseUrl,
      connectionTimeoutMillis: 5000,
    });
    const previousUrl = process.env.DATABASE_URL;
    process.env.DATABASE_URL = testDatabaseUrl;
    const userIds = [];
    const siteIds = [];
    let app;

    t.after(async () => {
      try {
        await app?.close();
        if (siteIds.length)
          await db.query('DELETE FROM sites WHERE id = ANY($1::uuid[])', [
            siteIds,
          ]);
        if (userIds.length)
          await db.query('DELETE FROM users WHERE id = ANY($1::uuid[])', [
            userIds,
          ]);
      } finally {
        await db.end();
        if (previousUrl === undefined) delete process.env.DATABASE_URL;
        else process.env.DATABASE_URL = previousUrl;
      }
    });

    const connected = await db.query('SELECT current_database() AS name');
    assert.equal(connected.rows[0].name, dbName);
    await migrate(drizzle(db), {
      migrationsFolder: fileURLToPath(new URL('../drizzle', import.meta.url)),
    });
    app = await NestFactory.create(AppModule, { logger: false });
    await app.listen(0, '127.0.0.1');
    const baseUrl = await app.getUrl();
    const auth = app.get(AuthService);
    async function account(name) {
      const email = `${randomUUID()}@example.com`;
      const password = 'sitesTestPassword';
      const { user } = await auth.signup({ name, email, password });
      userIds.push(user.id);
      const { accessToken } = await auth.login({ email, password });
      return { user, token: accessToken };
    }
    const northForeman = await account('첫 번째 현장 반장');
    const southForeman = await account('두 번째 현장 반장');
    const first = await account('첫 번째 검사 작업자');
    const second = await account('두 번째 검사 작업자');
    async function site(name, code, foreman) {
      const { rows } = await db.query(
        'INSERT INTO sites (name, code, foreman_user_id) VALUES ($1, $2, $3) RETURNING id',
        [name, code, foreman.user.id],
      );
      const id = rows[0].id;
      siteIds.push(id);
      return { id, name, foremanName: foreman.user.name };
    }
    const code = `SS-${randomUUID().slice(0, 8).toUpperCase()}`;
    const legacyCode = randomUUID().slice(0, 8).toUpperCase();
    const unknownCode = randomUUID().slice(-12).toUpperCase();
    const north = await site('첫 번째 검사 현장', code, northForeman);
    const south = await site('두 번째 검사 현장', legacyCode, southForeman);
    const lookup = '/sites/lookup';
    const join = `/sites/${north.id}/join`;
    async function request(method, path, body, token) {
      const headers = { 'Content-Type': 'application/json' };
      if (token !== undefined) headers.Authorization = `Bearer ${token}`;
      const response = await fetch(`${baseUrl}${path}`, {
        method,
        headers,
        body: JSON.stringify(body),
        signal: AbortSignal.timeout(10_000),
      });
      assert.equal(response.headers.get('cache-control'), 'no-store');
      return { status: response.status, body: await response.json() };
    }
    const post = (path, body, token) => request('POST', path, body, token);
    async function members(siteId) {
      const { rows } = await db.query(
        'SELECT user_id FROM site_memberships WHERE site_id = $1 ORDER BY user_id',
        [siteId],
      );
      return rows.map((row) => row.user_id);
    }
    function expectError(result, status, errorCode) {
      assert.equal(result.status, status);
      assert.equal(result.body.error.code, errorCode);
      assert.equal(typeof result.body.error.message, 'string');
    }

    await t.test(
      '조회는 현장 ID·이름·반장 이름만 공개하고 소속을 만들지 않음',
      async () => {
        assert.deepEqual(
          await post(lookup, { code: ` ${code.toLowerCase()} ` }, first.token),
          { status: 200, body: { site: north } },
        );
        assert.deepEqual(
          await post(lookup, { code: legacyCode }, first.token),
          { status: 200, body: { site: south } },
        );
        assert.deepEqual(await members(north.id), []);
        assert.deepEqual(await members(south.id), []);
      },
    );

    await t.test(
      '인증·입력·코드와 현장 일치 확인에 실패하면 소속이 생기지 않음',
      async () => {
        for (const path of [lookup, join]) {
          for (const token of [undefined, 'invalid']) {
            expectError(await post(path, { code }, token), 401, 'UNAUTHORIZED');
          }
          for (const body of [
            {},
            { code: '  ' },
            { code: '한글' },
            { code: 'A'.repeat(13) },
            { code, role: 'foreman' },
            { code, userId: second.user.id },
          ]) {
            expectError(
              await post(path, body, first.token),
              400,
              'VALIDATION_ERROR',
            );
          }
          expectError(
            await post(path, { code: unknownCode }, first.token),
            404,
            'SITE_NOT_FOUND',
          );
        }
        expectError(
          await post(join, { code: legacyCode }, first.token),
          404,
          'SITE_NOT_FOUND',
        );
        expectError(
          await post('/sites/invalid/join', { code }, first.token),
          404,
          'SITE_NOT_FOUND',
        );
        assert.deepEqual(await members(north.id), []);
        assert.deepEqual(await members(south.id), []);
      },
    );

    await t.test(
      '같은 사람의 동시 요청과 재전송은 소속 한 건만 저장',
      async () => {
        const responses = await Promise.all(
          Array.from({ length: 4 }, () =>
            post(join, { code: code.toLowerCase() }, first.token),
          ),
        );
        assert.deepEqual(
          responses.map((r) => r.status).sort(),
          [200, 200, 200, 201],
        );
        for (const response of responses)
          assert.deepEqual(response.body, { site: north });
        assert.deepEqual(await post(join, { code }, first.token), {
          status: 200,
          body: { site: north },
        });
        assert.deepEqual(await members(north.id), [first.user.id]);
        assert.deepEqual(await members(south.id), []);
      },
    );

    await t.test(
      '다른 작업자의 참여는 로그인한 본인의 소속만 추가',
      async () => {
        assert.deepEqual(await post(join, { code }, second.token), {
          status: 201,
          body: { site: north },
        });
        assert.deepEqual(
          await members(north.id),
          [first.user.id, second.user.id].sort(),
        );
        assert.deepEqual(await members(south.id), []);
      },
    );
  },
);
