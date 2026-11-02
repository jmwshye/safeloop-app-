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
import { IncidentService } from '../dist/incidents/incident.service.js';
import { IncidentWorker } from '../dist/incidents/incident.worker.js';
import { PushSender } from '../dist/notifications/push-sender.js';
import { pushMessage } from '../dist/notifications/push-message.js';

test('긴급·현장 FCM 모두 계정 확인 뒤 단말에서 표시할 데이터 메시지', () => {
  const tokens = ['local-test-device'];
  const data = {
    siteId: randomUUID(),
    incidentId: randomUUID(),
    eventId: randomUUID(),
    type: 'incident_alert',
  };
  const urgent = pushMessage(tokens, data, 23000);
  assert.deepEqual(urgent, {
    tokens,
    data,
    android: { priority: 'high', ttl: 23000 },
  });
  const site = pushMessage(
    tokens,
    { siteId: data.siteId, eventId: randomUUID(), type: 'site_notice' },
    60000,
  );
  assert.equal(site.notification, undefined);
  assert.equal(site.android.priority, 'high');
});

const url = process.env.TEST_DATABASE_URL;
test(
  '긴급 대응 PostgreSQL API 및 모의 기기 알림',
  { skip: url ? false : 'TEST_DATABASE_URL 지정 시 실행', timeout: 120000 },
  async (t) => {
    const dbName = decodeURIComponent(new URL(url).pathname.slice(1));
    assert.ok(dbName.endsWith('_test'));
    const db = new Pool({
      connectionString: url,
      connectionTimeoutMillis: 5000,
    });
    const previous = process.env.DATABASE_URL;
    process.env.DATABASE_URL = url;
    const ids = [],
      siteId = randomUUID();
    let app;
    t.after(async () => {
      try {
        await app?.close();
        await db.query('DELETE FROM incidents WHERE site_id=$1', [siteId]);
        await db.query('DELETE FROM attendances WHERE site_id=$1', [siteId]);
        await db.query('DELETE FROM sites WHERE id=$1', [siteId]);
        await db.query('DELETE FROM users WHERE id=ANY($1::uuid[])', [ids]);
      } finally {
        await db.end();
        if (previous === undefined) delete process.env.DATABASE_URL;
        else process.env.DATABASE_URL = previous;
      }
    });
    assert.equal(
      (await db.query('select current_database() as db')).rows[0].db,
      dbName,
    );
    await migrate(drizzle(db), {
      migrationsFolder: fileURLToPath(new URL('../drizzle', import.meta.url)),
    });
    let clock = Date.parse('2026-10-30T01:00:00Z');
    t.mock.method(IncidentWorker.prototype, 'onModuleInit', () => {});
    t.mock.method(IncidentService.prototype, 'now', () => new Date(clock));
    const sent = [];
    let mockDelivery = async () => ({ accepted: true, invalidTokens: [] });
    t.mock.method(
      PushSender.prototype,
      'send',
      async (tokens, payload, ttl) => {
        sent.push({ tokens, payload, ttl });
        return mockDelivery(tokens, payload);
      },
    );
    app = await NestFactory.create(AppModule, { logger: false });
    await app.listen(0, '127.0.0.1');
    const base = await app.getUrl(),
      service = app.get(IncidentService);
    async function user(name) {
      const id = randomUUID(),
        token = randomBytes(32).toString('base64url');
      ids.push(id);
      await db.query(
        'INSERT INTO users(id,name,email,password_hash) values ($1,$2,$3,$4)',
        [id, name, id + '@example.com', 'unused'],
      );
      await db.query(
        "INSERT INTO sessions(token_hash,user_id,expires_at) values ($1,$2,now()+interval '1 day')",
        [createHash('sha256').update(token).digest('hex'), id],
      );
      return { id, token };
    }
    const worker = await user('검사 작업자'),
      manager = await user('검사 반장'),
      buddy = await user('검사 동료'),
      zone = await user('검사 다음 동료'),
      other = await user('다른 현장 작업자');
    await db.query(
      'insert into sites(id,name,code,foreman_user_id) values($1,$2,$3,$4)',
      [siteId, '검사 현장', siteId.slice(0, 8).toUpperCase(), manager.id],
    );
    for (const who of [worker, buddy, zone]) {
      await db.query(
        "insert into site_memberships(site_id,user_id,occupation,zone) values ($1,$2,'목공','2층')",
        [siteId, who.id],
      );
      await db.query(
        "insert into attendances(user_id,site_id,work_date,checked_in_at,zone,buddy_user_id) values($1,$2,'2026-10-30',$3,'2층',$4)",
        [who.id, siteId, new Date(clock), who === worker ? buddy.id : null],
      );
    }
    async function req(person, path, method = 'GET', body) {
      const response = await fetch(base + path, {
        method,
        headers: {
          ...(person ? { Authorization: 'Bearer ' + person.token } : {}),
          ...(body ? { 'Content-Type': 'application/json' } : {}),
        },
        body: body && JSON.stringify(body),
        signal: AbortSignal.timeout(15000),
      });
      return { status: response.status, data: await response.json() };
    }
    async function ok(person, path, method = 'GET', body) {
      const { status, data } = await req(person, path, method, body);
      assert.ok(
        status >= 200 && status < 300,
        method + ' ' + path + ' ' + status + ' ' + JSON.stringify(data),
      );
      return data;
    }
    const startBody = () => ({
      siteId,
      requestId: randomUUID(),
      source: 'abnormal_movement',
      peakG: 3.4,
      location: { latitude: 37, longitude: 127, accuracyMeters: 9 },
      sensorWindow: [{ offsetMs: -100, accelerationG: 3.4 }],
    });
    const start = async (input = startBody()) =>
      (await ok(worker, '/incidents', 'POST', input)).incident;
    let caseId;
    const path = (tail) => '/incidents/' + caseId + tail;
    async function confirmAtSite(who) {
      await ok(who, path('/accept'), 'PUT', {});
      await ok(who, path('/arrival'), 'PUT', {});
      await ok(who, path('/observation'), 'PUT', { condition: 'appears_safe' });
      await ok(who, path('/resolution'), 'PUT', { reason: 'no_help_needed' });
    }
    await t.test(
      '인증/현장 범위, 기기 설정을 따로 저장, 독립적인 119 전화 화면 기록 재시도',
      async () => {
        assert.equal(
          (await req(null, '/incidents', 'POST', startBody())).status,
          401,
        );
        assert.equal(
          (await req(other, '/incidents', 'POST', startBody())).status,
          404,
        );
        assert.deepEqual(
          await ok(buddy, '/me/notification-preferences', 'PUT', {
            sitePush: false,
          }),
          { emergencyPush: true, sitePush: false },
        );
        assert.deepEqual(
          await ok(buddy, '/me/notification-preferences', 'PUT', {
            emergencyPush: false,
          }),
          { emergencyPush: false, sitePush: false },
        );
        await ok(buddy, '/me/notification-preferences', 'PUT', {
          emergencyPush: true,
        });
        for (const p of [manager, buddy, zone])
          await ok(p, '/push-devices/' + randomUUID(), 'PUT', {
            fcmToken: 'test-' + p.id,
          });
        const dial = { requestId: randomUUID() },
          route = '/sites/' + siteId + '/dial-intents';
        const first = await ok(worker, route, 'POST', dial);
        assert.deepEqual(first, await ok(worker, route, 'POST', dial));
        assert.ok(!('connected' in first));
        assert.equal((await req(other, route, 'POST', dial)).status, 403);
      },
    );
    await t.test(
      '15초 내 본인 응답은 오탐 종료. 같은 생성 재시도는 같은 기록',
      async () => {
        const input = startBody();
        const [c, replay, concurrentSignal] = await Promise.all([
          start(input),
          start(input),
          start({ ...input, requestId: randomUUID() }),
        ]);
        assert.equal(c.id, replay.id);
        assert.equal(c.id, concurrentSignal.id);
        caseId = c.id;
        assert.equal((await ok(manager, path(''))).incident.location, null);
        assert.equal(
          (await ok(worker, path(''))).incident.location.latitude,
          37,
        );
        clock += 14999;
        assert.equal(
          (await ok(worker, path(''))).incident.status,
          'awaiting_worker',
        );
        assert.equal((await req(buddy, path(''))).status, 404);
        assert.equal(
          (
            await ok(worker, path('/worker-response'), 'PUT', {
              requestId: randomUUID(),
              response: 'okay',
            })
          ).incident.resolution,
          'worker_confirmed_okay',
        );
        assert.equal((await start(input)).id, caseId);
        assert.equal(
          (await req(worker, '/incidents', 'POST', { ...input, peakG: 4 }))
            .status,
          409,
        );
        await service.processDue();
        await service.dispatchPending();
        assert.equal(sent.length, 0);
      },
    );
    await t.test(
      '본인 15초 경과 후 동료·반장 동시 요청, FCM 접수/수신/열람은 독립 증거',
      async () => {
        caseId = (await start()).id;
        clock += 15000;
        await service.processDue();
        const info = (await ok(manager, path(''))).incident;
        assert.equal(info.status, 'calling');
        const late = (
          await ok(worker, path('/worker-response'), 'PUT', {
            requestId: randomUUID(),
            response: 'okay',
          })
        ).incident;
        assert.equal(late.workerResponse, 'okay');
        assert.equal(late.status, 'calling');
        assert.equal(late.closedAt, null);
        assert.deepEqual(
          info.recipients.map((r) => r.userId).sort(),
          [buddy.id, manager.id].sort(),
        );
        assert.equal(
          info.recipients[0].requestedAt,
          info.recipients[1].requestedAt,
        );
        assert.equal(info.sensorWindow, null);
        for (const who of [zone, other]) {
          assert.equal((await req(who, path(''))).status, 404);
          assert.equal((await req(who, path('/accept'), 'PUT')).status, 404);
          assert.deepEqual(
            (await ok(who, '/incidents/active?siteId=' + siteId)).incidents,
            [],
          );
        }
        const timeline = '/sites/' + siteId + '/timeline';
        const audit = (
          await ok(manager, timeline + '?scope=site')
        ).events.filter((e) => e.entityId === caseId);
        assert.ok(audit.some((e) => e.type === 'incident.signal_detected'));
        assert.equal(
          audit.filter((e) => e.type === 'incident.worker_unanswered').length,
          1,
        );
        assert.ok(!audit.some((e) => e.type === 'incident.response_delayed'));
        assert.doesNotMatch(
          JSON.stringify(audit),
          /latitude|longitude|accuracyMeters|sensorWindow|fcmToken|@example\.com|검사 작업자/,
        );
        assert.ok(
          !(await ok(zone, timeline)).events.some((e) => e.entityId === caseId),
        );
        assert.equal((await req(other, timeline)).status, 403);
        await service.dispatchPending();
        const after = (await ok(buddy, path(''))).incident;
        assert.equal(after.location.accuracyMeters, 9);
        const r = after.recipients.find((x) => x.userId === buddy.id);
        assert.ok(r.sentAt);
        assert.equal(r.receivedAt, null);
        assert.equal(r.openedAt, null);
        const initialAlerts = sent.filter(
          (job) => job.payload.type === 'incident_alert',
        );
        assert.equal(initialAlerts.length, 2);
        for (const job of initialAlerts) {
          assert.equal(job.ttl, 30000);
          assert.deepEqual(job.payload, {
            eventId: job.payload.eventId,
            recipientId: job.tokens.includes('test-' + buddy.id)
              ? buddy.id
              : manager.id,
            siteId,
            incidentId: caseId,
            type: 'incident_alert',
          });
          assert.match(job.payload.eventId, /^[a-f0-9-]{36}$/);
        }
        await ok(buddy, path('/receipt'), 'PUT', { stage: 'received' });
        const opened = (
          await ok(buddy, path('/receipt'), 'PUT', { stage: 'opened' })
        ).incident;
        assert.ok(
          opened.recipients.find((x) => x.userId === buddy.id).openedAt,
        );
        assert.equal(
          (await req(other, path('/receipt'), 'PUT', { stage: 'received' }))
            .status,
          404,
        );
        assert.equal((await req(zone, path(''))).status, 404);
        clock += 29999;
        await service.processDue();
        assert.equal((await req(zone, path(''))).status, 404);
        clock += 1;
        await service.processDue();
        assert.equal((await ok(zone, path(''))).incident.recipients.length, 3);
      },
    );
    let accepted;
    await t.test(
      '동시 수락은 한 명만 성공, 도착 이전 종료 거절, 119는 통화 기록이 아님',
      async () => {
        const a = await Promise.all(
          [buddy, zone].map((p) => req(p, path('/accept'), 'PUT', {})),
        );
        assert.deepEqual(a.map((x) => x.status).sort(), [200, 409]);
        accepted = a[0].status === 200 ? buddy : zone;
        const inactiveCandidate = a[0].status === 200 ? zone : buddy;
        assert.equal(
          (await ok(inactiveCandidate, path(''))).incident.location,
          null,
        );
        assert.equal(
          (await ok(manager, path(''))).incident.location.latitude,
          37,
        );
        const again = await ok(accepted, path('/accept'), 'PUT', {});
        assert.equal(again.incident.responderId, accepted.id);
        const events = (
          await ok(manager, '/sites/' + siteId + '/timeline?scope=site')
        ).events.filter((e) => e.entityId === caseId);
        assert.equal(
          events.filter((e) => e.type === 'incident.response_delayed').length,
          1,
        );
        const acceptedEvents = events.filter(
          (e) => e.type === 'incident.accepted',
        );
        assert.equal(acceptedEvents.length, 1);
        assert.deepEqual(acceptedEvents[0].details, {
          role: accepted === buddy ? 'buddy' : 'zone',
          responseSeconds: accepted === buddy ? 30 : 0,
        });
        const overdue = async () =>
          (await ok(manager, path(''))).incident.notices.filter(
            (n) => n.type === 'verification_overdue',
          );
        clock += 119999;
        await service.processDue();
        assert.deepEqual(await overdue(), []);
        clock += 1;
        await Promise.all([service.processDue(), service.processDue()]);
        let reminders = await overdue();
        assert.equal(reminders.length, 1);
        assert.equal(reminders[0].userId, manager.id);
        assert.equal(
          (await ok(manager, path(''))).incident.status,
          'responding',
        );
        await service.dispatchPending();
        await service.processDue();
        reminders = await overdue();
        assert.equal(reminders.length, 1);
        assert.equal(reminders[0].status, 'sent');
        assert.equal(
          sent.filter(
            (job) =>
              job.payload.incidentId === caseId &&
              job.payload.type === 'incident_verification_overdue',
          ).length,
          1,
        );
        const incidentEvents = (
          await ok(manager, '/sites/' + siteId + '/timeline?scope=site')
        ).events;
        assert.equal(
          incidentEvents.filter(
            (e) =>
              e.entityId === caseId &&
              e.type === 'incident.verification_overdue',
          ).length,
          1,
        );
        const report = await ok(
          worker,
          '/sites/' + siteId + '/reports/incidents/' + caseId,
        );
        assert.equal(
          report.events.filter(
            (e) => e.type === 'incident.verification_overdue',
          ).length,
          1,
        );
        assert.equal(
          (
            await req(accepted, path('/resolution'), 'PUT', {
              reason: 'onsite_action_completed',
            })
          ).status,
          409,
        );
        const arrived = (await ok(accepted, path('/arrival'), 'PUT')).incident;
        assert.ok(arrived.arrivedAt);
        const intent = { requestId: randomUUID() };
        await ok(accepted, path('/dial-intent'), 'PUT', intent);
        const repeated = (
          await ok(accepted, path('/dial-intent'), 'PUT', intent)
        ).incident;
        assert.equal(repeated.dialIntents.length, 1);
        assert.equal(repeated.dialIntents[0].userId, accepted.id);
        const observed = (
          await ok(accepted, path('/observation'), 'PUT', {
            condition: 'conscious',
          })
        ).incident;
        assert.equal(
          observed.notices.filter(
            (n) => n.type === 'observation' && n.userId === manager.id,
          ).length,
          1,
        );
        const firstAnswer = { requestId: randomUUID(), response: 'okay' };
        await ok(worker, path('/worker-response'), 'PUT', firstAnswer);
        await ok(worker, path('/worker-response'), 'PUT', {
          requestId: randomUUID(),
          response: 'help_needed',
        });
        const replay = await ok(
          worker,
          path('/worker-response'),
          'PUT',
          firstAnswer,
        );
        assert.equal(replay.incident.workerResponse, 'help_needed');
        assert.equal(
          (
            await req(worker, path('/worker-response'), 'PUT', {
              ...firstAnswer,
              response: 'help_needed',
            })
          ).status,
          409,
        );
        const done = (
          await ok(accepted, path('/resolution'), 'PUT', {
            reason: 'onsite_action_completed',
          })
        ).incident;
        assert.equal(done.status, 'closed');
        assert.equal(done.photoId, null);
        assert.equal(done.location, null);
        assert.equal(
          (await service.reportDetail(siteId, caseId, manager.id)).incident
            .location,
          null,
        );
        await assert.rejects(service.reportDetail(siteId, caseId, other.id));
        assert.deepEqual(
          (await ok(manager, '/incidents/active?siteId=' + siteId)).incidents,
          [],
        );
      },
    );
    await t.test(
      '직접 구조 요청, 수락자 없음·재시도·늦은 본인 응답, 다음 동료 중복 발송 방지',
      async () => {
        const c = await start({
          siteId,
          requestId: randomUUID(),
          source: 'manual_help',
        });
        caseId = c.id;
        assert.equal(c.status, 'calling');
        await ok(buddy, path('/decline'), 'PUT', {});
        assert.equal(
          (await ok(zone, path('/decline'), 'PUT', {})).incident.status,
          'unanswered',
        );
        await ok(worker, path('/retry'), 'PUT', {});
        assert.equal(
          (
            await ok(worker, path('/worker-response'), 'PUT', {
              requestId: randomUUID(),
              response: 'okay',
            })
          ).incident.status,
          'calling',
        );
        assert.equal(
          (await req(buddy, path('/accept'), 'PUT', {})).status,
          409,
        );
        assert.equal(
          (await ok(worker, path(''))).incident.recipients.length,
          3,
        );
        await confirmAtSite(manager);
      },
    );
    await t.test(
      '기기 토큰 무효·푸시 장애·수동 복구 후에도 수신 증거는 단말이 보낼 때만 기록',
      async () => {
        // Drain previous closures so each assertion observes only the new incident.
        await service.dispatchPending();
        caseId = (await start()).id;
        clock += 15000;
        await service.processDue();
        const buddyToken = 'test-' + buddy.id;
        const managerToken = 'test-' + manager.id;
        mockDelivery = async (tokens) => {
          if (tokens.includes(buddyToken))
            return { accepted: false, invalidTokens: [buddyToken] };
          if (tokens.includes(managerToken))
            throw new Error('simulated transport failure');
          return { accepted: true, invalidTokens: [] };
        };
        const recipient = async (who) =>
          (await ok(manager, path(''))).incident.recipients.find(
            (row) => row.userId === who.id,
          );
        await service.dispatchPending();
        assert.equal((await recipient(manager)).delivery, 'queued');
        assert.equal(
          (
            await db.query('select id from push_devices where user_id=$1', [
              buddy.id,
            ])
          ).rowCount,
          0,
        );
        clock += 2000;
        await service.dispatchPending();
        assert.equal((await recipient(buddy)).delivery, 'unavailable');
        clock += 4000;
        await service.dispatchPending();
        assert.equal((await recipient(manager)).delivery, 'failed');
        const attempts = sent.filter(
          (job) =>
            job.payload.incidentId === caseId &&
            job.tokens.includes(managerToken),
        );
        assert.deepEqual(
          attempts.map((job) => job.ttl),
          [30000, 30000, 30000],
        );
        assert.equal((await recipient(manager)).receivedAt, null);
        assert.equal((await recipient(manager)).sentAt, null);
        clock += 24000;
        await service.processDue();
        await ok(zone, path('/decline'), 'PUT', {});
        assert.equal(
          (await ok(worker, path(''))).incident.status,
          'unanswered',
        );
        mockDelivery = async () => ({ accepted: true, invalidTokens: [] });
        await ok(buddy, '/push-devices/' + randomUUID(), 'PUT', {
          fcmToken: 'new-' + buddy.id,
        });
        await ok(worker, path('/retry'), 'PUT', {});
        await service.dispatchPending();
        assert.equal((await recipient(buddy)).delivery, 'sent');
        assert.equal((await recipient(manager)).delivery, 'sent');
        assert.equal((await recipient(manager)).receivedAt, null);
        await ok(worker, path('/worker-response'), 'PUT', {
          requestId: randomUUID(),
          response: 'okay',
        });
        await confirmAtSite(buddy);
      },
    );
    await t.test(
      '도착·상태 기록 후에만 대응 마무리, 현장 사진은 당사자·수락자·해당 현장 반장만 열람',
      async () => {
        caseId = (
          await start({
            siteId,
            requestId: randomUUID(),
            source: 'manual_help',
          })
        ).id;
        await ok(buddy, path('/accept'), 'PUT');
        await ok(buddy, path('/arrival'), 'PUT');
        const photoId = randomUUID(),
          wrongPhotoId = randomUUID();
        await db.query(
          "INSERT INTO photos(id,site_id,owner_id,purpose,bytes) VALUES($1,$2,$3,'incident_scene',$4),($5,$2,$6,'incident_scene',$4)",
          [
            photoId,
            siteId,
            buddy.id,
            Buffer.from([255, 216, 255, 217]),
            wrongPhotoId,
            zone.id,
          ],
        );
        const resolution = { reason: 'handoff_confirmed', photoId };
        assert.equal(
          (await req(buddy, path('/resolution'), 'PUT', resolution)).status,
          409,
        );
        await ok(buddy, path('/observation'), 'PUT', { condition: 'immobile' });
        await db.query('UPDATE photos SET created_at=$1 WHERE id=$2', [
          new Date(clock - 1),
          photoId,
        ]);
        const stale = await req(buddy, path('/resolution'), 'PUT', resolution);
        assert.equal(stale.status, 409);
        assert.equal(stale.data.error.code, 'PHOTO_BEFORE_ARRIVAL');
        await db.query('UPDATE photos SET created_at=$1 WHERE id=$2', [
          new Date(clock),
          photoId,
        ]);
        clock += 120000;
        await service.processDue();
        assert.ok(
          !(await ok(manager, path(''))).incident.notices.some(
            (n) => n.type === 'verification_overdue',
          ),
        );
        assert.equal(
          (
            await req(buddy, path('/resolution'), 'PUT', {
              ...resolution,
              photoId: wrongPhotoId,
            })
          ).status,
          404,
        );
        assert.equal(
          (await ok(buddy, path('/resolution'), 'PUT', resolution)).incident
            .photoId,
          photoId,
        );
        assert.equal(
          (await ok(buddy, path('/resolution'), 'PUT', resolution)).incident
            .photoId,
          photoId,
        );
        const photoStatus = async (person) => {
          const response = await fetch(
            base + '/sites/' + siteId + '/photos/' + photoId,
            { headers: { Authorization: 'Bearer ' + person.token } },
          );
          await response.arrayBuffer();
          return response.status;
        };
        assert.deepEqual(
          await Promise.all(
            [worker, buddy, manager, zone, other].map(photoStatus),
          ),
          [200, 200, 200, 404, 403],
        );
      },
    );
    await t.test(
      '고위험 작업 중인 대체 동료 제외 후에도 반장·119 경로 유지',
      async () => {
        await db.query(
          "UPDATE attendances SET high_risk_task='비계', high_risk_started_at=$3 WHERE site_id=$1 AND user_id=$2",
          [siteId, zone.id, new Date(clock)],
        );
        caseId = (await start()).id;
        clock += 15000;
        await service.processDue();
        clock += 30000;
        await service.processDue();
        const pending = (await ok(worker, path(''))).incident;
        assert.equal(pending.status, 'unanswered');
        assert.ok(pending.recipients.some((r) => r.userId === manager.id));
        assert.ok(!pending.recipients.some((r) => r.userId === zone.id));
        assert.equal((await req(zone, path('/accept'), 'PUT', {})).status, 404);
        const dial = (
          await ok(worker, path('/dial-intent'), 'PUT', {
            requestId: randomUUID(),
          })
        ).incident;
        assert.equal(dial.dialIntents.length, 1);
        assert.equal(dial.status, 'unanswered');
        await confirmAtSite(manager);
      },
    );
    await t.test(
      '오프라인 충격 시각·119 복원, 만료 이후에도 유효한 호출 발송, 반대 응답 후 안전 알림 취소',
      async () => {
        const detectedAt = new Date(clock - 130000).toISOString();
        const call = await start({ ...startBody(), detectedAt });
        caseId = call.id;
        assert.equal(call.createdAt, detectedAt);
        assert.equal(call.status, 'calling');
        assert.equal(call.location, null);
        clock += 31000;
        await ok(worker, path('/worker-response'), 'PUT', {
          requestId: randomUUID(),
          response: 'okay',
        });
        await ok(worker, path('/worker-response'), 'PUT', {
          requestId: randomUUID(),
          response: 'help_needed',
        });
        // The production dispatcher consumes at most eight jobs per tick.
        while (
          (
            await db.query(
              "select 1 from incident_pushes where status='queued' and next_attempt_at <= $1 limit 1",
              [new Date(clock)],
            )
          ).rowCount
        ) {
          await service.dispatchPending();
        }
        const notices = (await ok(worker, path(''))).incident;
        assert.ok(notices.recipients.some((r) => r.delivery === 'sent'));
        assert.ok(
          notices.notices
            .filter((n) => n.type === 'worker_safe')
            .every((n) => n.status === 'cancelled'),
        );
        const openedAt = new Date(clock).toISOString();
        clock += 1000;
        await confirmAtSite(manager);
        clock += 30000;
        const late = await ok(worker, path('/dial-intent'), 'PUT', {
          requestId: randomUUID(),
          openedAt,
        });
        assert.equal(late.incident.dialIntents[0].openedAt, openedAt);
        assert.equal(
          (
            await req(worker, path('/dial-intent'), 'PUT', {
              requestId: randomUUID(),
              openedAt: new Date(clock).toISOString(),
            })
          ).status,
          409,
        );
      },
    );
    await t.test(
      '수락 동료 퇴근 또는 직접 구조 요청 시 반장이 원 사건을 이어받음',
      async () => {
        for (const why of ['checkout', 'distress']) {
          const original = await start({
            siteId,
            source: 'manual_help',
            requestId: randomUUID(),
          });
          caseId = original.id;
          await ok(buddy, path('/accept'), 'PUT', {});
          let second;
          if (why === 'checkout')
            await db.query(
              'UPDATE attendances SET checked_out_at=$3 WHERE site_id=$1 AND user_id=$2',
              [siteId, buddy.id, new Date(clock)],
            );
          else
            second = (
              await ok(buddy, '/incidents', 'POST', {
                siteId,
                source: 'manual_help',
                requestId: randomUUID(),
              })
            ).incident;
          await service.processDue();
          const active = (await ok(manager, path(''))).incident;
          assert.equal(active.responderId, null);
          assert.ok(
            active.recipients.find((r) => r.userId === buddy.id)?.declinedAt,
          );
          await confirmAtSite(manager);
          if (second) {
            caseId = second.id;
            await confirmAtSite(manager);
          }
          await db.query(
            'UPDATE attendances SET checked_out_at=NULL WHERE site_id=$1 AND user_id=$2',
            [siteId, buddy.id],
          );
        }
      },
    );
    await t.test(
      '공용 단말 계정·토큰이 함께 바뀌어도 계정별 기기 등록과 도용 거부',
      async () => {
        const deviceA = randomUUID(),
          deviceB = randomUUID();
        await ok(worker, '/push-devices/' + deviceA, 'PUT', {
          fcmToken: 'offline-A-' + deviceA,
        });
        assert.equal(
          (
            await req(zone, '/push-devices/' + deviceA, 'PUT', {
              fcmToken: 'rotated-B-' + deviceB,
            })
          ).status,
          409,
        );
        await ok(zone, '/push-devices/' + deviceB, 'PUT', {
          fcmToken: 'rotated-B-' + deviceB,
        });
        await ok(worker, '/push-devices/' + deviceB, 'DELETE');
        const { rows } = await db.query(
          'select user_id, fcm_token from push_devices where id=$1',
          [deviceB],
        );
        assert.deepEqual(rows, [
          { user_id: zone.id, fcm_token: 'rotated-B-' + deviceB },
        ]);
      },
    );
  },
);
