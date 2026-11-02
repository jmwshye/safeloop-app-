import 'reflect-metadata';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { NestFactory } from '@nestjs/core';
import { drizzle } from 'drizzle-orm/node-postgres';
import { migrate } from 'drizzle-orm/node-postgres/migrator';
import { Pool } from 'pg';
import sharp from 'sharp';
import { AppModule } from '../dist/app.module.js';
import { AuthService } from '../dist/auth/auth.service.js';
import { NoticesWorker } from '../dist/records/notices.worker.js';
import { PushSender } from '../dist/notifications/push-sender.js';
import { configureHttp } from '../dist/http.js';

const testUrl = process.env.TEST_DATABASE_URL;
test(
  '작업자·반장 화면이 공유하는 기록과 권한',
  { skip: !testUrl, timeout: 120_000 },
  async (t) => {
    const dbName = decodeURIComponent(new URL(testUrl).pathname.slice(1));
    assert.ok(dbName.endsWith('_test'));
    const pool = new Pool({ connectionString: testUrl });
    const previous = process.env.DATABASE_URL;
    const users = [];
    let siteId, app;
    process.env.DATABASE_URL = testUrl;
    t.after(async () => {
      try {
        await app?.close();
        if (siteId) {
          await pool.query('DELETE FROM attendances WHERE site_id=$1', [
            siteId,
          ]);
          await pool.query('DELETE FROM sites WHERE id=$1', [siteId]);
        }
        await pool.query('DELETE FROM users WHERE id=ANY($1::uuid[])', [users]);
      } finally {
        await pool.end();
        if (previous === undefined) delete process.env.DATABASE_URL;
        else process.env.DATABASE_URL = previous;
      }
    });
    assert.equal(
      (await pool.query('SELECT current_database() AS db')).rows[0].db,
      dbName,
    );
    await migrate(drizzle(pool), {
      migrationsFolder: fileURLToPath(new URL('../drizzle', import.meta.url)),
    });
    t.mock.method(NoticesWorker.prototype, 'onApplicationBootstrap', () => {});
    app = await NestFactory.create(AppModule, { logger: false });
    const adminOrigin = 'https://admin.example.test';
    configureHttp(app, adminOrigin);
    await app.listen(0, '127.0.0.1');
    const base = await app.getUrl();
    await t.test(
      '관리자 웹에서 토큰을 보내는 브라우저 요청은 설정한 주소만 허용',
      async () => {
        for (const origin of [adminOrigin, 'https://outside.example.test']) {
          const res = await fetch(base + '/sites/mine', {
            method: 'OPTIONS',
            headers: {
              Origin: origin,
              'Access-Control-Request-Method': 'GET',
              'Access-Control-Request-Headers': 'Authorization',
            },
          });
          assert.equal(
            res.headers.get('access-control-allow-origin'),
            origin === adminOrigin ? origin : null,
          );
          if (origin === adminOrigin)
            assert.match(
              res.headers.get('access-control-allow-headers'),
              /Authorization/i,
            );
        }
      },
    );
    const auth = app.get(AuthService);
    async function actor(name) {
      const email = randomUUID() + '@example.com',
        password = 'LocalTestPassword';
      const { user } = await auth.signup({ name, email, password });
      users.push(user.id);
      const { accessToken } = await auth.login({ email, password });
      return { ...user, token: accessToken };
    }
    async function call(actor, method, path, body) {
      const headers = { Authorization: 'Bearer ' + actor.token };
      if (body !== undefined && !(body instanceof FormData))
        headers['Content-Type'] = 'application/json';
      const response = await fetch(base + path, {
        method,
        headers,
        body: body instanceof FormData ? body : JSON.stringify(body),
      });
      assert.equal(response.headers.get('cache-control'), 'no-store');
      const type = response.headers.get('content-type') ?? '';
      const data = type.includes('json')
        ? await response.json()
        : Buffer.from(await response.arrayBuffer());
      return { status: response.status, data };
    }
    const boss = await actor('테스트 반장'),
      worker = await actor('테스트 작업자'),
      peer = await actor('테스트 동료'),
      outsider = await actor('다른 현장 작업자');
    const {
      data: { site },
    } = await call(boss, 'POST', '/sites', { name: '검증 현장' });
    siteId = site.id;
    const path = '/sites/' + site.id;
    for (const who of [worker, peer]) {
      assert.equal(
        (await call(who, 'POST', path + '/join', { code: site.code })).status,
        201,
      );
      assert.equal(
        (
          await call(who, 'PUT', path + '/profile', {
            occupation: '목공',
            zone: '2층',
            expectedWage: 200000,
          })
        ).status,
        200,
      );
    }
    const arrival = await call(worker, 'POST', '/attendances', { siteId });
    assert.equal(arrival.status, 201);
    const attendance = arrival.data.attendance;
    await t.test(
      'GPS 없이 출근한 기록과 점검 요청은 해당 현장 관리자만 집계',
      async () => {
        const check = await call(
          worker,
          'PUT',
          '/attendances/' + attendance.id + '/safety-check',
          {
            fallHazard: 'unknown',
            floorAndGuardrail: 'confirmed',
            protectiveEquipment: 'confirmed',
          },
        );
        assert.equal(check.status, 200);
        const dash = await call(boss, 'GET', path + '/dashboard');
        assert.equal(dash.status, 200);
        assert.equal(dash.data.counts.attended, 1);
        assert.equal(dash.data.counts.pendingSafetyReviews, 1);
        for (const who of [outsider, worker])
          assert.equal(
            (await call(who, 'GET', path + '/dashboard')).status,
            403,
          );
        const notices = await call(boss, 'GET', path + '/notices');
        const review = notices.data.notices.find(
          (n) => n.kind === 'safety_review',
        );
        assert.equal(review.entityId, attendance.id);
        assert.ok(
          !(await call(worker, 'GET', path + '/notices')).data.notices.some(
            (n) => n.id === review.id,
          ),
        );
        await pool.query(
          'INSERT INTO push_devices(id,user_id,fcm_token,updated_at) VALUES($1,$2,$3,now())',
          [randomUUID(), boss.id, randomUUID()],
        );
        const sender = app.get(PushSender);
        t.mock.getter(sender, 'configured', () => true);
        let offline = true;
        const send = t.mock.method(sender, 'send', async () => {
          if (offline) throw new Error('Simulated network failure');
          return { accepted: true, invalidTokens: [] };
        });
        await app.get(NoticesWorker).deliver();
        assert.equal(send.mock.callCount(), 1);
        assert.equal(
          (
            await pool.query('SELECT status FROM site_notices WHERE id=$1', [
              review.id,
            ])
          ).rows[0].status,
          'queued',
        );
        offline = false;
        await pool.query(
          'UPDATE site_notices SET next_attempt_at=now() WHERE id=$1',
          [review.id],
        );
        await app.get(NoticesWorker).deliver();
        await app.get(NoticesWorker).deliver();
        assert.equal(send.mock.callCount(), 2);
        assert.equal(
          (await call(boss, 'GET', path + '/notices')).data.notices.find(
            (n) => n.id === review.id,
          ).pushStatus,
          'sent',
        );
      },
    );
    await t.test(
      '익명 신고의 작성자만 자기 타임라인에서 원본과 정정을 확인',
      async () => {
        const response = await call(
          worker,
          'POST',
          path.replace(/([0-9a-f-]{36})$/, (id) => id.toUpperCase()) + '/risks',
          {
            requestId: randomUUID(),
            title: '바닥 개구부',
            zone: '2층',
            category: 'fall',
            anonymous: true,
          },
        );
        assert.equal(response.status, 201);
        const id = response.data.risk.id;
        const eventsFor = async (who, scope) =>
          (
            await call(who, 'GET', path + '/timeline?scope=' + scope)
          ).data.events.filter((e) => e.entityId === id);
        const [original] = await eventsFor(worker, 'self');
        assert.equal(original.actorId, null);
        assert.equal(original.canRequestCorrection, true);
        assert.equal(
          (await eventsFor(boss, 'site'))[0].canRequestCorrection,
          false,
        );
        assert.equal(
          (await call(boss, 'GET', path + '/timeline/verify')).data.valid,
          true,
        );
        assert.equal(original.subjectId, null);
        assert.deepEqual(await eventsFor(peer, 'self'), []);
        const route = path + '/timeline/' + original.id + '/corrections';
        const body = {
          requestId: randomUUID(),
          note: '개구부는 기둥 옆에 있습니다.',
        };
        assert.equal((await call(peer, 'POST', route, body)).status, 404);
        const requested = await call(worker, 'POST', route, body);
        assert.equal(requested.status, 201);
        const review =
          path + '/corrections/' + requested.data.correction.id + '/review';
        const decision = { status: 'accepted', note: '위치를 확인했어요.' };
        for (let i = 0; i < 2; i++) {
          const reviewed = await call(boss, 'PUT', review, decision);
          assert.equal(reviewed.data.correction.requesterId, null);
          assert.equal(reviewed.data.correction.requestId, null);
        }
        const queue = (await call(boss, 'GET', path + '/corrections')).data
          .corrections;
        assert.equal(
          queue.find((c) => c.id === requested.data.correction.id).requesterId,
          null,
        );
        const history = await eventsFor(worker, 'self');
        assert.deepEqual(
          history.map((e) => e.type),
          ['correction.accepted', 'correction.requested', 'risk.reported'],
        );
        assert.deepEqual(await eventsFor(peer, 'self'), []);
        assert.ok(
          !JSON.stringify(await eventsFor(boss, 'site')).includes(worker.id),
        );
      },
    );
    await t.test('본인 기록 정정·반장 검토 후 원본과 해시 검증', async () => {
      const history = await call(worker, 'GET', path + '/timeline?scope=self');
      assert.equal(history.status, 200);
      const original = history.data.events.find(
        (e) => e.entityId === attendance.id,
      );
      assert.ok(original);
      assert.equal(
        (await call(outsider, 'GET', path + '/timeline')).status,
        403,
      );
      assert.equal(
        (await call(peer, 'GET', path + '/timeline?scope=site')).status,
        403,
      );
      assert.ok(
        !(await call(peer, 'GET', path + '/timeline')).data.events.some(
          (e) => e.id === original.id,
        ),
      );
      const route = path + '/timeline/' + original.id + '/corrections';
      const body = {
        requestId: randomUUID(),
        note: '작업 위치에 대한 정정 요청',
      };
      assert.equal((await call(peer, 'POST', route, body)).status, 404);
      const req = await call(worker, 'POST', route, body);
      assert.equal(req.status, 201);
      const id = req.data.correction.id;
      assert.equal(
        (await call(worker, 'POST', route, body)).data.correction.id,
        id,
      );
      const reviewPath = path + '/corrections/' + id + '/review',
        decision = { status: 'accepted', note: '작업자 확인' };
      assert.equal((await call(peer, 'PUT', reviewPath, decision)).status, 403);
      assert.equal(
        (await call(boss, 'PUT', reviewPath, decision)).data.correction.status,
        'accepted',
      );
      assert.equal((await call(boss, 'PUT', reviewPath, decision)).status, 200);
      const corrected = (
        await call(worker, 'GET', path + '/timeline?scope=self')
      ).data.events;
      assert.ok(
        corrected.some(
          (e) =>
            e.correctsId === original.id && e.type === 'correction.accepted',
        ),
      );
      assert.deepEqual(
        corrected.find((e) => e.id === original.id),
        original,
      );
      assert.equal(
        (await call(boss, 'GET', path + '/timeline/verify')).data.valid,
        true,
      );
      assert.equal(
        (await call(worker, 'GET', path + '/timeline/verify')).status,
        403,
      );
      await pool.query('UPDATE audit_events SET type=$1 WHERE id=$2', [
        'test.tampered',
        original.id,
      ]);
      const broken = (await call(boss, 'GET', path + '/timeline/verify')).data;
      assert.equal(broken.valid, false);
      assert.equal(broken.firstInvalidSeq, original.seq);
      await pool.query('UPDATE audit_events SET type=$1 WHERE id=$2', [
        original.type,
        original.id,
      ]);
      const extra = randomUUID();
      await pool.query(
        'INSERT INTO audit_events(id,site_id,seq,recorded_at,type,source,entity_id,details,hash) SELECT $1,site_id,0,recorded_at,type,source,entity_id,details,hash FROM audit_events WHERE id=$2',
        [extra, original.id],
      );
      assert.equal(
        (await call(boss, 'GET', path + '/timeline/verify')).data.valid,
        false,
      );
      await pool.query('DELETE FROM audit_events WHERE id=$1', [extra]);
      assert.equal(
        (await call(boss, 'GET', path + '/timeline/verify')).data.valid,
        true,
      );
    });
    await t.test(
      '메타데이터 없는 사진·초안 접근제어·잘못된 파일 거절',
      async () => {
        const img = await sharp({
          create: { width: 3, height: 3, channels: 3, background: 'blue' },
        })
          .jpeg()
          .withMetadata({ orientation: 3 })
          .toBuffer();
        function form(bytes) {
          const data = new FormData();
          data.set('purpose', 'ppe');
          data.set(
            'file',
            new Blob([bytes], { type: 'image/jpeg' }),
            'private.jpg',
          );
          return data;
        }
        assert.equal(
          (await call(outsider, 'POST', path + '/photos', form(img))).status,
          403,
        );
        assert.equal(
          (
            await call(
              worker,
              'POST',
              path + '/photos',
              form(Buffer.from('not an image')),
            )
          ).status,
          400,
        );
        const posted = await call(worker, 'POST', path + '/photos', form(img));
        assert.equal(posted.status, 201);
        const route = path + '/photos/' + posted.data.photo.id;
        for (const who of [boss, peer])
          assert.equal((await call(who, 'GET', route)).status, 404);
        const image = await call(worker, 'GET', route);
        assert.equal(image.status, 200);
        const metadata = await sharp(image.data).metadata();
        assert.equal(metadata.format, 'jpeg');
        assert.equal(metadata.exif, undefined);
        assert.equal((await call(worker, 'DELETE', route)).status, 204);
        assert.equal((await call(worker, 'GET', route)).status, 404);
      },
    );
    await t.test(
      '푸시가 대기 중에 동료나 조치 담당자가 바뀌면 이전 사람에게 보내지 않음',
      async () => {
        const task = await call(
          worker,
          'PUT',
          '/attendances/' + attendance.id + '/high-risk',
          { task: 'ladder' },
        );
        assert.equal(task.status, 200);
        assert.equal(
          (await call(peer, 'POST', '/attendances', { siteId })).status,
          201,
        );
        const {
          data: { risk },
        } = await call(worker, 'POST', path + '/risks', {
          requestId: randomUUID(),
          title: '자재 정리 필요',
          zone: '2층',
          category: 'trip',
          anonymous: false,
        });
        const assign = path + '/risks/' + risk.id + '/request';
        for (const who of [worker, peer])
          assert.equal(
            (await call(boss, 'PUT', assign, { assigneeId: who.id })).status,
            200,
          );
        await app.get(NoticesWorker).deliver();
        for (const [kind, recipient, entity] of [
          ['high_risk', boss, attendance.id],
          ['risk_action', worker, risk.id],
        ]) {
          const {
            rows: [notice],
          } = await pool.query(
            'SELECT status FROM site_notices WHERE kind=$1 AND recipient_id=$2 AND entity_id=$3',
            [kind, recipient.id, entity],
          );
          assert.equal(notice.status, 'cancelled');
        }
      },
    );
  },
);
