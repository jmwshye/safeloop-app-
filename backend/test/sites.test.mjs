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
        if (siteIds.length) {
          await db.query(
            'DELETE FROM incidents WHERE site_id=ANY($1::uuid[])',
            [siteIds],
          );
          await db.query(
            'DELETE FROM attendances WHERE site_id=ANY($1::uuid[])',
            [siteIds],
          );
        }
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
    const put = (path, body, token) => request('PUT', path, body, token);
    const get = (path, token) => request('GET', path, undefined, token);
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

    const sitePath = `/sites/${north.id}`;
    const profile = { occupation: '철근', zone: '2층', expectedWage: 150000 };
    let firstShift;
    let secondShift;
    await t.test(
      '현장 생성·소속 조회, 직종과 구역 설정, GPS 권한 거절 시에도 출근 저장',
      async () => {
        const created = await post(
          '/sites',
          { name: '별도 검사 현장' },
          southForeman.token,
        );
        assert.equal(created.status, 201);
        siteIds.push(created.body.site.id);
        const settings = {
          name: north.name,
          center: { latitude: 37.53, longitude: 127.02 },
          foremanPhone: '02-0000-0000',
          breakReturnTime: '12:00',
          shiftChangeTime: '18:00',
        };
        assert.equal(
          (await put(sitePath + '/settings', settings, second.token)).status,
          403,
        );
        assert.equal(
          (await put(sitePath + '/settings', settings, northForeman.token))
            .status,
          200,
        );
        const configured = (await get('/sites/mine', first.token)).body
          .sites[0];
        assert.equal(configured.foremanPhone, settings.foremanPhone);
        assert.deepEqual(configured.center, settings.center);
        const createdSites = (await get('/sites/mine', southForeman.token)).body
          .sites;
        assert.equal(
          createdSites.find((site) => site.id === created.body.site.id).center,
          null,
        );
        assert.equal(
          (await post('/attendances', { siteId: north.id }, southForeman.token))
            .status,
          403,
        );
        assert.equal(
          (await post('/attendances', { siteId: north.id }, first.token))
            .status,
          409,
        );
        assert.equal(
          (await put(sitePath + '/profile', profile, first.token)).status,
          200,
        );
        assert.equal(
          (
            await put(
              sitePath + '/profile',
              { ...profile, expectedWage: null },
              second.token,
            )
          ).status,
          200,
        );
        const fix = {
          ...settings.center,
          accuracyMeters: 10,
          capturedAt: new Date().toISOString(),
        };
        for (const [location, expected] of [
          [fix, 'inside'],
          [{ ...fix, latitude: 37.5327, accuracyMeters: 80 }, 'unverified'],
          [{ ...fix, latitude: 37.6 }, 'outside'],
          [{ ...fix, accuracyMeters: 999 }, 'unverified'],
          [{ ...fix, capturedAt: '2000-01-01T00:00:00Z' }, 'unverified'],
        ]) {
          assert.deepEqual(
            (
              await post(
                sitePath + '/check-location',
                { location },
                first.token,
              )
            ).body,
            { locationStatus: expected },
          );
        }
        const saved = await post(
          '/attendances',
          { siteId: north.id, location: null },
          first.token,
        );
        assert.equal(saved.status, 201);
        firstShift = saved.body.attendance;
        assert.equal(firstShift.locationStatus, 'unverified');
        assert.equal(firstShift.buddyUserId, northForeman.user.id);
        assert.equal(firstShift.expectedWage, profile.expectedWage);
        secondShift = (
          await post(
            '/attendances',
            { siteId: north.id, location: fix },
            second.token,
          )
        ).body.attendance;
        assert.equal(secondShift.locationStatus, 'inside');
        assert.equal(secondShift.buddyUserId, first.user.id);
        assert.equal(
          (await get('/attendances/today', first.token)).body.attendance
            .buddyUserId,
          second.user.id,
        );
        const context = (
          await get(`/attendances/${firstShift.id}/context`, first.token)
        ).body;
        assert.equal(context.buddy.name, second.user.name);
        assert.equal(context.buddy.attendanceId, secondShift.id);
        assert.equal(context.buddy.zone, profile.zone);
        assert.deepEqual(context.device, {
          state: 'unverified',
          lastSeenAt: null,
        });
        const devicePath = `/attendances/${firstShift.id}/device-status`;
        const deviceInput = {
          state: 'not_carried',
          observedAt: new Date().toISOString(),
        };
        assert.equal(
          (await put(devicePath, deviceInput, second.token)).status,
          404,
        );
        assert.equal(
          (await put(devicePath, { ...deviceInput, latitude: 37 }, first.token))
            .status,
          400,
        );
        const report = await put(devicePath, deviceInput, first.token);
        assert.equal(report.status, 200);
        assert.equal(report.body.device.state, 'not_carried');
        assert.ok(report.body.device.lastSeenAt);
        assert.deepEqual(
          await put(devicePath, deviceInput, first.token),
          report,
        );
        const peerDevice = async () =>
          (await get(`/attendances/${secondShift.id}/context`, second.token))
            .body.buddy.device;
        assert.deepEqual(await peerDevice(), report.body.device);
        await db.query(
          "UPDATE shift_devices SET observed_at=now()-interval '6 minutes', received_at=now()-interval '6 minutes' WHERE attendance_id=$1",
          [firstShift.id],
        );
        assert.equal((await peerDevice()).state, 'unverified');
        assert.equal(
          (
            await db.query(
              'SELECT count(*)::int AS n FROM incidents WHERE worker_id=$1',
              [first.user.id],
            )
          ).rows[0].n,
          0,
        );
        assert.equal(
          (await get(sitePath + '/workers', southForeman.token)).status,
          403,
        );
        assert.equal(
          JSON.stringify(
            (await get(sitePath + '/workers', northForeman.token)).body,
          ).includes('latitude'),
          false,
        );
      },
    );

    await t.test(
      '모름 재전송은 반장 요청 하나, 정정 전 답변은 이력에 남고 다른 현장에는 비공개',
      async () => {
        const path = `/attendances/${firstShift.id}/safety-check`;
        const input = {
          fallHazard: 'unknown',
          floorAndGuardrail: 'confirmed',
          protectiveEquipment: 'confirmed',
        };
        assert.equal((await put(path, input, first.token)).status, 200);
        assert.equal((await put(path, input, first.token)).status, 200);
        assert.equal(
          (await get(sitePath + '/safety-requests', southForeman.token)).status,
          403,
        );
        const pending = await get(
          sitePath + '/safety-requests',
          northForeman.token,
        );
        assert.equal(pending.body.requests.length, 1);
        assert.equal(pending.body.requests[0].fallHazard, 'unknown');
        const ack =
          sitePath + `/safety-requests/${firstShift.id}/acknowledgement`;
        const firstAck = await put(ack, {}, northForeman.token);
        assert.equal(firstAck.status, 200);
        assert.deepEqual(await put(ack, {}, northForeman.token), firstAck);
        assert.equal(
          (await put(path, { ...input, fallHazard: 'confirmed' }, first.token))
            .status,
          200,
        );
        assert.equal(
          (await get(sitePath + '/safety-requests', northForeman.token)).body
            .requests.length,
          0,
        );
        const events = await db.query(
          "SELECT type,details->>'fallHazard' AS answer FROM audit_events WHERE entity_id=$1 AND type IN ('safety_check.saved','safety_check.corrected') ORDER BY seq",
          [firstShift.id],
        );
        assert.deepEqual(
          events.rows.map((r) => r.answer),
          ['unknown', 'confirmed'],
        );
        assert.equal(
          (
            await db.query(
              "SELECT COUNT(*)::int AS n FROM site_notices WHERE entity_id=$1 AND kind='safety_review'",
              [firstShift.id],
            )
          ).rows[0].n,
          1,
        );
      },
    );

    await t.test(
      '교차 확인은 동료 양방향·불일치 재확인·사진 선택을 구분하고 고위험 상태를 공유',
      async () => {
        const peer = `/attendances/${firstShift.id}/peer-check`;
        assert.equal(
          (await put(peer, { outcome: 'checked' }, first.token)).status,
          404,
        );
        assert.equal(
          (
            await put(
              peer,
              { outcome: 'mismatch', photoId: randomUUID() },
              second.token,
            )
          ).status,
          400,
        );
        assert.equal(
          (await put(peer, { outcome: 'mismatch' }, second.token)).status,
          200,
        );
        const status = async () =>
          (
            await get(sitePath + '/workers', northForeman.token)
          ).body.workers.find((r) => r.userId === first.user.id).peerStatus;
        assert.equal(await status(), 'mismatch');
        assert.equal(
          (await put(peer, { outcome: 'checked' }, second.token)).status,
          200,
        );
        assert.equal(await status(), 'pending');
        assert.equal(
          (
            await put(
              `/attendances/${secondShift.id}/peer-check`,
              { outcome: 'checked' },
              first.token,
            )
          ).status,
          200,
        );
        assert.equal(await status(), 'statement_only');
        const photos = await Promise.all(
          [first, second].map(async (person) => {
            const { rows } = await db.query(
              "INSERT INTO photos(site_id,owner_id,purpose,bytes) VALUES ($1,$2,'ppe',$3) RETURNING id",
              [north.id, person.user.id, Buffer.from('test bytes')],
            );
            return rows[0].id;
          }),
        );
        assert.equal(
          (
            await put(
              peer,
              { outcome: 'checked', photoId: photos[0] },
              second.token,
            )
          ).status,
          404,
        );
        assert.equal(
          (
            await put(
              peer,
              { outcome: 'checked', photoId: photos[1] },
              second.token,
            )
          ).status,
          200,
        );
        assert.equal(await status(), 'statement_only');
        assert.equal(
          (
            await put(
              `/attendances/${secondShift.id}/peer-check`,
              { outcome: 'checked', photoId: photos[0] },
              first.token,
            )
          ).status,
          200,
        );
        assert.equal(await status(), 'photo_confirmed');
        const risk = `/attendances/${firstShift.id}/high-risk`;
        const started = await put(risk, { task: 'scaffold' }, first.token);
        assert.equal(started.status, 200);
        assert.ok(started.body.highRiskStartedAt);
        assert.deepEqual(
          await put(risk, { task: 'scaffold' }, first.token),
          started,
        );
        assert.equal(
          (await get(`/attendances/${secondShift.id}/context`, second.token))
            .body.buddy.highRiskTask,
          'scaffold',
        );
        assert.equal(
          (await put(risk, { task: null }, first.token)).body.highRiskTask,
          null,
        );
        await put(risk, { task: 'ladder' }, first.token);
        await put(risk, { task: null }, first.token);
        const highRiskPrompts = (
          await get(`/attendances/${firstShift.id}/context`, first.token)
        ).body.prompts;
        assert.equal(
          highRiskPrompts.filter((p) => p.kind === 'high_risk_start').length,
          2,
        );
        assert.equal(
          highRiskPrompts.filter((p) => p.kind === 'high_risk_end').length,
          2,
        );
        const assign = sitePath + '/buddy-assignment';
        assert.equal(
          (
            await put(
              assign,
              { workerId: first.user.id, buddyId: northForeman.user.id },
              second.token,
            )
          ).status,
          403,
        );
        assert.equal(
          (
            await put(
              assign,
              { workerId: first.user.id, buddyId: northForeman.user.id },
              northForeman.token,
            )
          ).status,
          200,
        );
        assert.equal(
          (await get('/attendances/today', second.token)).body.attendance
            .buddyUserId,
          northForeman.user.id,
        );
      },
    );

    await t.test(
      '예약 확인의 무응답은 안전 미확인, 도움 응답은 실제 사건 하나를 생성하고 퇴근 후 재전송도 안전',
      async () => {
        const [due, expired] = await Promise.all(
          ['break_return', 'shift_change'].map(async (kind, index) => {
            const { rows } = await db.query(
              `INSERT INTO work_prompts(attendance_id,kind,due_at,expires_at)
          VALUES ($1,$2,now()-interval '5 minutes',now()+$3::int*interval '1 minute')
              ON CONFLICT(attendance_id,kind) WHERE kind IN ('break_return','shift_change') DO UPDATE SET due_at=excluded.due_at,expires_at=excluded.expires_at RETURNING id`,
              [firstShift.id, kind, index === 0 ? 5 : -1],
            );
            return rows[0].id;
          }),
        );
        const context = (
          await get(`/attendances/${firstShift.id}/context`, first.token)
        ).body;
        assert.equal(
          context.prompts.find((p) => p.id === expired).state,
          'unverified',
        );
        const reply = `/attendances/${firstShift.id}/check-ins/${due}`;
        assert.equal(
          (await put(reply, { response: 'help_needed' }, second.token)).status,
          404,
        );
        assert.equal(
          (await put(reply, { response: 'help_needed' }, first.token)).status,
          200,
        );
        assert.equal(
          (await put(reply, { response: 'help_needed' }, first.token)).status,
          200,
        );
        assert.equal(
          (
            await db.query(
              "SELECT count(*)::int AS n FROM incidents WHERE worker_id=$1 AND source='manual_help'",
              [first.user.id],
            )
          ).rows[0].n,
          1,
        );
        const end = `/attendances/${firstShift.id}/checkout`;
        const saved = await put(end, {}, first.token);
        assert.equal(saved.status, 200);
        assert.ok(saved.body.attendance.checkedOutAt);
        assert.equal(
          (
            await db.query(
              'SELECT count(*)::int AS n FROM shift_devices WHERE attendance_id=$1',
              [firstShift.id],
            )
          ).rows[0].n,
          0,
        );
        assert.deepEqual(await put(end, {}, first.token), saved);
        await put(
          sitePath + '/profile',
          { ...profile, zone: '다음 구역', expectedWage: 999 },
          first.token,
        );
        const date = firstShift.workDate;
        const history = await get(
          `/attendances/records?from=${date}&to=${date}`,
          first.token,
        );
        assert.equal(
          history.body.attendances[0].expectedWage,
          profile.expectedWage,
        );
        assert.equal(history.body.attendances[0].zone, profile.zone);
      },
    );
    await t.test(
      '마이그레이션 이전 출근은 본인이 명시한 현장에 기존 시각·점검을 보존해 연결',
      async () => {
        const worker = await account('이전 버전 작업자');
        await post(join, { code }, worker.token);
        await put(
          sitePath + '/profile',
          { occupation: '철근', zone: '별도 구역' },
          worker.token,
        );
        const {
          rows: [old],
        } = await db.query(
          `INSERT INTO attendances
        (user_id,work_date,checked_in_at,fall_hazard,floor_and_guardrail,protective_equipment,safety_check_updated_at)
        VALUES ($1,(now() AT TIME ZONE 'Asia/Seoul')::date,now()-interval '1 hour','unknown','confirmed','confirmed',now()) RETURNING id,checked_in_at`,
          [worker.user.id],
        );
        assert.equal(
          (await post('/attendances', {}, worker.token)).status,
          409,
        );
        const linked = await post(
          '/attendances',
          { siteId: north.id },
          worker.token,
        );
        assert.equal(linked.status, 200);
        assert.equal(linked.body.attendance.id, old.id);
        assert.equal(
          linked.body.attendance.checkedInAt,
          old.checked_in_at.toISOString(),
        );
        assert.equal(linked.body.attendance.locationStatus, 'unverified');
        assert.equal(linked.body.attendance.safetyCheck.fallHazard, 'unknown');
        assert.deepEqual(
          await post('/attendances', { siteId: north.id }, worker.token),
          linked,
        );
        assert.equal(
          (
            await get(sitePath + '/safety-requests', northForeman.token)
          ).body.requests.filter((r) => r.attendanceId === old.id).length,
          1,
        );
      },
    );
  },
);
