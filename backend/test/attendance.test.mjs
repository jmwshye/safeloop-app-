import 'reflect-metadata';
import assert from 'node:assert/strict';
import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { test } from 'node:test';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';
import { NestFactory } from '@nestjs/core';
import { drizzle } from 'drizzle-orm/node-postgres';
import { migrate } from 'drizzle-orm/node-postgres/migrator';
import { Client, Pool } from 'pg';
import { AppModule } from '../dist/app.module.js';
import { AttendanceService } from '../dist/attendance/attendance.service.js';

const testDatabaseUrl = process.env.TEST_DATABASE_URL;
const confirmed = {
  fallHazard: 'confirmed',
  floorAndGuardrail: 'confirmed',
  protectiveEquipment: 'confirmed',
};
const mixed = {
  fallHazard: 'confirmed',
  floorAndGuardrail: 'needs_attention',
  protectiveEquipment: 'unknown',
};

test(
  '출근·안전점검 PostgreSQL 통합 검사',
  {
    skip: testDatabaseUrl ? false : 'TEST_DATABASE_URL 지정 시 실행',
    timeout: 120_000,
  },
  async (t) => {
    const databaseName = decodeURIComponent(
      new URL(testDatabaseUrl).pathname.slice(1),
    );
    assert.ok(
      databaseName.endsWith('_test'),
      '이름이 _test로 끝나는 별도 DB만 사용',
    );
    const previousUrl = process.env.DATABASE_URL;
    process.env.DATABASE_URL = testDatabaseUrl;
    const database = new Pool({
      connectionString: testDatabaseUrl,
      max: 3,
      connectionTimeoutMillis: 5000,
    });
    const prefix = randomUUID();
    const emails = [
      `${prefix}-owner@example.com`,
      `${prefix}-other@example.com`,
    ];
    const password = 'samplePassword123';
    let app;
    let baseUrl;
    let owner;
    let other;
    let original;
    let otherAttendance;
    let currentAttendance;
    let serverTime = Date.parse('2026-10-30T00:00:00.000Z');
    let advancePerRead = 0;

    t.mock.method(AttendanceService.prototype, 'now', () => {
      const now = new Date(serverTime);
      serverTime += advancePerRead;
      return now;
    });

    t.after(async () => {
      try {
        await app?.close();
        // 이번 실행의 계정과 연결된 출근·점검만 정리한다.
        await database.query(
          'DELETE FROM users WHERE email = ANY($1::text[])',
          [emails],
        );
      } finally {
        await database.end();
        if (previousUrl === undefined) delete process.env.DATABASE_URL;
        else process.env.DATABASE_URL = previousUrl;
      }
    });

    const connected = await database.query('SELECT current_database()');
    assert.equal(connected.rows[0].current_database, databaseName);
    // npm test는 테스트 파일을 직렬 실행해 마이그레이션 경쟁을 피한다.
    await migrate(drizzle(database), {
      migrationsFolder: fileURLToPath(new URL('../drizzle', import.meta.url)),
    });
    app = await NestFactory.create(AppModule, { logger: false });
    await app.listen(0, '127.0.0.1');
    baseUrl = await app.getUrl();

    async function request(method, path, { token, body, raw = false } = {}) {
      const headers = {};
      if (token !== undefined) headers.Authorization = `Bearer ${token}`;
      if (body !== undefined) headers['Content-Type'] = 'application/json';
      const response = await fetch(`${baseUrl}${path}`, {
        method,
        headers,
        body:
          body === undefined ? undefined : raw ? body : JSON.stringify(body),
        signal: AbortSignal.timeout(10_000),
      });
      assert.equal(response.headers.get('cache-control'), 'no-store');
      return { status: response.status, body: await response.json() };
    }

    const today = (token = owner.token) =>
      request('GET', '/attendances/today', { token });
    const checkIn = (token = owner.token, body = {}) =>
      request('POST', '/attendances', { token, body });
    const save = (id, body, token = owner.token) =>
      request('PUT', `/attendances/${id}/safety-check`, { token, body });

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

    function expectRecord(record) {
      assert.deepEqual(Object.keys(record).sort(), [
        'checkedInAt',
        'id',
        'safetyCheck',
        'workDate',
      ]);
      assert.equal(typeof record.id, 'string');
      assert.match(record.workDate, /^\d{4}-\d{2}-\d{2}$/);
      assert.equal(
        new Date(record.checkedInAt).toISOString(),
        record.checkedInAt,
      );
      if (record.safetyCheck !== null) {
        assert.deepEqual(Object.keys(record.safetyCheck).sort(), [
          'fallHazard',
          'floorAndGuardrail',
          'protectiveEquipment',
          'result',
          'updatedAt',
        ]);
        assert.equal(
          new Date(record.safetyCheck.updatedAt).toISOString(),
          record.safetyCheck.updatedAt,
        );
      }
    }

    async function register(email) {
      const signup = await request('POST', '/auth/signup', {
        body: { name: '출근 검사', email, password },
      });
      assert.equal(signup.status, 201);
      const login = await request('POST', '/auth/login', {
        body: { email, password },
      });
      assert.equal(login.status, 200);
      return { user: signup.body.user, token: login.body.accessToken };
    }

    owner = await register(emails[0]);
    other = await register(emails[1]);

    await t.test(
      '미출근은 null, 출근 최초 저장은 201과 서버 시각',
      async () => {
        assert.deepEqual(await today(), {
          status: 200,
          body: { attendance: null },
        });
        const response = await checkIn();
        assert.equal(response.status, 201);
        assert.deepEqual(Object.keys(response.body), ['attendance']);
        original = response.body.attendance;
        expectRecord(original);
        assert.equal(original.workDate, '2026-10-30');
        assert.equal(original.checkedInAt, '2026-10-30T00:00:00.000Z');
        assert.equal(original.safetyCheck, null);
        assert.deepEqual((await today()).body, response.body);
      },
    );

    await t.test(
      '같은 날 재요청은 기존 기록 유지, 다른 사용자 조회는 분리',
      async () => {
        serverTime += 300_000;
        const again = await checkIn();
        assert.equal(again.status, 200);
        assert.deepEqual(again.body.attendance, original);
        assert.deepEqual(await today(other.token), {
          status: 200,
          body: { attendance: null },
        });
      },
    );

    await t.test(
      '동시 출근 요청도 한 건만 만들고 나머지는 기존 기록 반환',
      async () => {
        const responses = await Promise.all(
          Array.from({ length: 6 }, () => checkIn(other.token)),
        );
        assert.equal(
          responses.filter((response) => response.status === 201).length,
          1,
        );
        assert.equal(
          responses.filter((response) => response.status === 200).length,
          5,
        );
        otherAttendance = responses[0].body.attendance;
        for (const response of responses)
          assert.deepEqual(response.body.attendance, otherAttendance);
        const rows = await database.query(
          'SELECT count(*)::int AS count FROM attendances WHERE user_id = $1 AND work_date = $2',
          [other.user.id, '2026-10-30'],
        );
        assert.equal(rows.rows[0].count, 1);
      },
    );

    await t.test(
      '세 답변을 함께 저장하고 모름도 저장된 답변으로 반환',
      async () => {
        const response = await save(original.id, mixed);
        assert.equal(response.status, 200);
        expectRecord(response.body.attendance);
        assert.deepEqual(response.body.attendance.safetyCheck, {
          ...mixed,
          result: 'needs_attention',
          updatedAt: new Date(serverTime).toISOString(),
        });
        assert.equal(
          response.body.attendance.checkedInAt,
          original.checkedInAt,
        );
        assert.deepEqual((await today()).body, response.body);
      },
    );

    await t.test(
      '당일 수정·전 항목 확인 계산, 동일 답변 재전송은 수정 시각 유지',
      async () => {
        serverTime += 60_000;
        const allConfirmed = await save(original.id, confirmed);
        assert.equal(allConfirmed.status, 200);
        assert.equal(
          allConfirmed.body.attendance.safetyCheck.result,
          'all_confirmed',
        );
        assert.equal(
          allConfirmed.body.attendance.safetyCheck.updatedAt,
          new Date(serverTime).toISOString(),
        );
        serverTime += 60_000;
        assert.deepEqual(await save(original.id, confirmed), allConfirmed);
        const checkInAgain = await checkIn();
        assert.equal(checkInAgain.status, 200);
        assert.deepEqual(checkInAgain.body, allConfirmed.body);
        const unknown = await save(original.id, {
          ...confirmed,
          protectiveEquipment: 'unknown',
        });
        assert.equal(unknown.status, 200);
        assert.equal(
          unknown.body.attendance.safetyCheck.result,
          'needs_attention',
        );
        assert.equal(unknown.body.attendance.checkedInAt, original.checkedInAt);
      },
    );

    await t.test(
      '입력 누락·타입·추가 필드·JSON 오류는 400이고 기존 기록 보존',
      async () => {
        const before = await today();
        for (const body of [
          null,
          [],
          { userId: other.user.id },
          { checkedInAt: original.checkedInAt },
        ]) {
          expectError(
            await checkIn(owner.token, body),
            400,
            'VALIDATION_ERROR',
          );
        }
        const invalidChecks = [
          {},
          null,
          [],
          { fallHazard: 'confirmed', floorAndGuardrail: 'confirmed' },
          { ...confirmed, fallHazard: null },
          { ...confirmed, floorAndGuardrail: true },
          { ...confirmed, protectiveEquipment: 'safe' },
          { ...confirmed, result: 'all_confirmed' },
        ];
        for (const body of invalidChecks)
          expectError(await save(original.id, body), 400, 'VALIDATION_ERROR');
        expectError(
          await request('PUT', `/attendances/${original.id}/safety-check`, {
            token: owner.token,
            body: '{',
            raw: true,
          }),
          400,
          'VALIDATION_ERROR',
        );
        assert.deepEqual(await today(), before);
      },
    );

    await t.test(
      '없는 기록·잘못된 ID·다른 사람 기록은 동일한 404',
      async () => {
        const missing = await save(randomUUID(), confirmed);
        expectError(missing, 404, 'ATTENDANCE_NOT_FOUND');
        for (const id of ['not-a-uuid', otherAttendance.id]) {
          const response = await save(id, confirmed);
          expectError(response, 404, 'ATTENDANCE_NOT_FOUND');
          assert.deepEqual(response.body, missing.body);
        }
        assert.equal(
          (await today(other.token)).body.attendance.safetyCheck,
          null,
        );
      },
    );

    await t.test('세 API 모두 토큰 누락·변조·만료 시 401', async () => {
      const login = await request('POST', '/auth/login', {
        body: { email: emails[1], password },
      });
      assert.equal(login.status, 200);
      const expiredToken = login.body.accessToken;
      await database.query(
        "UPDATE sessions SET expires_at = NOW() - INTERVAL '1 second' WHERE token_hash = $1",
        [createHash('sha256').update(expiredToken).digest('hex')],
      );
      for (const token of [
        undefined,
        randomBytes(32).toString('base64url'),
        expiredToken,
      ]) {
        for (const [method, path, body] of [
          ['POST', '/attendances', {}],
          ['GET', '/attendances/today', undefined],
          ['PUT', `/attendances/${original.id}/safety-check`, confirmed],
        ])
          expectError(
            await request(method, path, { token, body }),
            401,
            'UNAUTHORIZED',
          );
      }
    });

    await t.test(
      '한국 자정에 오늘 기록이 바뀌고 지난 날짜 점검은 수정 불가',
      async () => {
        serverTime = Date.parse('2026-10-30T14:59:59.999Z');
        assert.equal((await today()).body.attendance.id, original.id);
        const beforeMidnight = await save(original.id, confirmed);
        assert.equal(beforeMidnight.status, 200);
        assert.equal(
          beforeMidnight.body.attendance.safetyCheck.updatedAt,
          '2026-10-30T14:59:59.999Z',
        );
        // 중복 요청 안에서 시각을 다시 읽으면 다음 날짜가 되는 경계.
        serverTime = Date.parse('2026-10-30T14:59:59.999Z');
        advancePerRead = 1;
        try {
          const duplicate = await checkIn();
          assert.equal(duplicate.status, 200);
          assert.deepEqual(duplicate.body, beforeMidnight.body);
        } finally {
          advancePerRead = 0;
        }
        serverTime = Date.parse('2026-10-30T15:00:00.000Z');
        assert.deepEqual(await today(), {
          status: 200,
          body: { attendance: null },
        });
        expectError(
          await save(original.id, confirmed),
          409,
          'ATTENDANCE_NOT_TODAY',
        );
        const response = await checkIn();
        assert.equal(response.status, 201);
        currentAttendance = response.body.attendance;
        assert.equal(currentAttendance.workDate, '2026-10-31');
        assert.equal(currentAttendance.checkedInAt, '2026-10-30T15:00:00.000Z');
        assert.notEqual(currentAttendance.id, original.id);
        assert.equal(currentAttendance.safetyCheck, null);
      },
    );

    await t.test(
      '같은 점검을 동시에 처음 저장해도 수정 시각과 결과가 하나로 유지',
      async () => {
        advancePerRead = 1;
        try {
          const responses = await Promise.all(
            Array.from({ length: 6 }, () => save(currentAttendance.id, mixed)),
          );
          for (const response of responses) {
            assert.equal(response.status, 200);
            assert.deepEqual(response.body, responses[0].body);
          }
          currentAttendance = responses[0].body.attendance;
          assert.deepEqual((await today()).body.attendance, currentAttendance);
        } finally {
          advancePerRead = 0;
        }
      },
    );

    await t.test('DB도 중복 출근과 일부만 채운 점검을 거부', async () => {
      await assert.rejects(
        database.query(
          'INSERT INTO attendances (user_id, work_date, checked_in_at) VALUES ($1, $2, $3)',
          [
            owner.user.id,
            currentAttendance.workDate,
            currentAttendance.checkedInAt,
          ],
        ),
        (error) => error.code === '23505',
      );
      await assert.rejects(
        database.query(
          'UPDATE attendances SET protective_equipment = NULL WHERE id = $1',
          [currentAttendance.id],
        ),
        (error) => error.code === '23514',
      );
      assert.deepEqual((await today()).body.attendance, currentAttendance);
    });

    await t.test(
      '점검 저장이 DB 잠금을 기다리다가 날짜를 넘기면 409',
      async () => {
        serverTime = Date.parse('2026-10-31T14:59:59.999Z');
        const blocker = await database.connect();
        let pending;
        try {
          await blocker.query('BEGIN');
          await blocker.query(
            'SELECT id FROM attendances WHERE id = $1 FOR UPDATE',
            [currentAttendance.id],
          );
          const pid = (await blocker.query('SELECT pg_backend_pid() AS pid'))
            .rows[0].pid;
          pending = save(currentAttendance.id, confirmed);
          let waiting = false;
          const deadline = Date.now() + 5000;
          while (Date.now() < deadline) {
            const blocked = await database.query(
              'SELECT 1 FROM pg_stat_activity WHERE $1 = ANY(pg_blocking_pids(pid))',
              [pid],
            );
            if (blocked.rowCount > 0) {
              waiting = true;
              break;
            }
            await delay(10);
          }
          assert.ok(waiting, '점검 요청이 실제 DB 행 잠금에서 대기해야 함');
          serverTime = Date.parse('2026-10-31T15:00:00.000Z');
          await blocker.query('COMMIT');
          expectError(await pending, 409, 'ATTENDANCE_NOT_TODAY');
          const row = (
            await database.query(
              'SELECT fall_hazard, floor_and_guardrail, protective_equipment, safety_check_updated_at FROM attendances WHERE id = $1',
              [currentAttendance.id],
            )
          ).rows[0];
          assert.equal(row.fall_hazard, mixed.fallHazard);
          assert.equal(row.floor_and_guardrail, mixed.floorAndGuardrail);
          assert.equal(row.protective_equipment, mixed.protectiveEquipment);
          assert.equal(
            row.safety_check_updated_at.toISOString(),
            currentAttendance.safetyCheck.updatedAt,
          );
        } finally {
          await blocker.query('ROLLBACK');
          blocker.release();
          if (pending) await pending.catch(() => {});
        }
      },
    );

    await t.test(
      '출근 저장 중 DB 오류는 500, 부분 저장·내부 정보 노출 없이 복구',
      async (subtest) => {
        const originalQuery = Client.prototype.query;
        const query = subtest.mock.method(
          Client.prototype,
          'query',
          function (config, ...args) {
            const sql = typeof config === 'string' ? config : config.text;
            if (/insert\s+into\s+"?attendances"?/i.test(sql)) {
              throw new Error(
                'postgresql://private-user:secret@private-db/internal',
              );
            }
            return originalQuery.call(this, config, ...args);
          },
        );
        const failed = await checkIn();
        expectError(failed, 500, 'SERVER_ERROR');
        const serialized = JSON.stringify(failed.body);
        for (const secret of ['secret', 'private-db', 'postgresql://'])
          assert.ok(!serialized.includes(secret));
        query.mock.restore();
        assert.deepEqual(await today(), {
          status: 200,
          body: { attendance: null },
        });
        const recovered = await checkIn();
        assert.equal(recovered.status, 201);
        currentAttendance = recovered.body.attendance;
      },
    );

    await t.test(
      '점검 UPDATE 오류도 기존 답변을 보존하고 잠금을 해제',
      async (subtest) => {
        const saved = await save(currentAttendance.id, mixed);
        assert.equal(saved.status, 200);
        const before = await today();
        serverTime += 60_000;
        const originalQuery = Client.prototype.query;
        let failedUpdates = 0;
        const query = subtest.mock.method(
          Client.prototype,
          'query',
          function (config, ...args) {
            const sql = typeof config === 'string' ? config : config.text;
            if (/update\s+"?attendances"?/i.test(sql)) {
              failedUpdates++;
              throw new Error(
                'postgresql://private-user:secret@private-db/internal',
              );
            }
            return originalQuery.call(this, config, ...args);
          },
        );
        const failed = await save(currentAttendance.id, confirmed);
        expectError(failed, 500, 'SERVER_ERROR');
        assert.equal(failedUpdates, 1);
        for (const secret of ['secret', 'private-db', 'postgresql://'])
          assert.ok(!JSON.stringify(failed.body).includes(secret));
        query.mock.restore();
        assert.deepEqual(await today(), before);
        const recovered = await save(currentAttendance.id, confirmed);
        assert.equal(recovered.status, 200);
        assert.deepEqual(recovered.body.attendance.safetyCheck, {
          ...confirmed,
          result: 'all_confirmed',
          updatedAt: new Date(serverTime).toISOString(),
        });
      },
    );
  },
);
