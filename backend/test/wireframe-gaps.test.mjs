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
import { IncidentWorker } from '../dist/incidents/incident.worker.js';
import { NoticesWorker } from '../dist/records/notices.worker.js';
import { configureHttp } from '../dist/http.js';

const testUrl = process.env.TEST_DATABASE_URL;
test(
  '와이어프레임 위험 처리자와 사진 교차확인 집계',
  { skip: !testUrl, timeout: 120_000 },
  async (t) => {
    const dbName = decodeURIComponent(new URL(testUrl).pathname.slice(1));
    assert.ok(dbName.endsWith('_test'));
    const pool = new Pool({ connectionString: testUrl });
    const previous = process.env.DATABASE_URL;
    const userIds = [],
      siteIds = [];
    let app;
    process.env.DATABASE_URL = testUrl;
    t.after(async () => {
      try {
        await app?.close();
        await pool.query(
          'DELETE FROM attendances WHERE site_id=ANY($1::uuid[])',
          [siteIds],
        );
        await pool.query('DELETE FROM sites WHERE id=ANY($1::uuid[])', [
          siteIds,
        ]);
        await pool.query('DELETE FROM users WHERE id=ANY($1::uuid[])', [
          userIds,
        ]);
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
    t.mock.method(IncidentWorker.prototype, 'onModuleInit', () => {});
    t.mock.method(NoticesWorker.prototype, 'onApplicationBootstrap', () => {});
    app = await NestFactory.create(AppModule, { logger: false });
    configureHttp(app);
    await app.listen(0, '127.0.0.1');
    const base = await app.getUrl(),
      auth = app.get(AuthService);
    async function actor(name) {
      const email = randomUUID() + '@example.com',
        password = 'LocalTestPassword';
      const { user } = await auth.signup({ name, email, password });
      userIds.push(user.id);
      const { accessToken } = await auth.login({ email, password });
      return { ...user, token: accessToken };
    }
    async function call(who, method, path, body) {
      const headers = { Authorization: 'Bearer ' + who.token };
      if (body !== undefined && !(body instanceof FormData))
        headers['Content-Type'] = 'application/json';
      const response = await fetch(base + path, {
        method,
        headers,
        body: body instanceof FormData ? body : JSON.stringify(body),
      });
      return { status: response.status, data: await response.json() };
    }
    const boss = await actor('검사 반장'),
      worker = await actor('신고 작업자'),
      peer = await actor('조치 작업자'),
      observer = await actor('재확인 작업자'),
      outsider = await actor('다른 현장 반장');
    async function siteFor(who) {
      const response = await call(who, 'POST', '/sites', {
        name: '시안 검증 현장',
      });
      assert.equal(response.status, 201);
      siteIds.push(response.data.site.id);
      return response.data.site;
    }
    const site = await siteFor(boss),
      otherSite = await siteFor(outsider);
    const path = '/sites/' + site.id;
    for (const who of [worker, peer, observer]) {
      assert.equal(
        (await call(who, 'POST', path + '/join', { code: site.code })).status,
        201,
      );
      assert.equal(
        (
          await call(who, 'PUT', path + '/profile', {
            occupation: '목공',
            zone: who === observer ? '3층' : '2층',
            expectedWage: 200000,
          })
        ).status,
        200,
      );
    }
    const jpeg = await sharp({
      create: { width: 8, height: 8, channels: 3, background: '#445566' },
    })
      .jpeg()
      .toBuffer();
    async function photo(who, purpose) {
      const form = new FormData();
      form.set('purpose', purpose);
      form.set('file', new Blob([jpeg], { type: 'image/jpeg' }), 'proof.jpg');
      const response = await call(who, 'POST', path + '/photos', form);
      assert.equal(response.status, 201);
      return response.data.photo.id;
    }

    await t.test(
      '시안의 위치·유형·익명 여부만으로 신고하고 같은 요청을 재시도',
      async () => {
        const input = {
          requestId: randomUUID(),
          zone: '2층 동측 개구부',
          category: 'fall',
          anonymous: true,
        };
        const first = await call(worker, 'POST', path + '/risks', input);
        assert.equal(first.status, 201);
        assert.equal(first.data.risk.title, '추락 위험 · 2층 동측 개구부');
        const repeat = await call(worker, 'POST', path + '/risks', input);
        assert.equal(repeat.status, 200);
        assert.equal(repeat.data.risk.id, first.data.risk.id);
        assert.equal(
          (
            await call(worker, 'POST', path + '/risks', {
              ...input,
              title: first.data.risk.title,
            })
          ).status,
          200,
        );
        assert.equal(
          (
            await call(worker, 'POST', path + '/risks', {
              ...input,
              zone: '3층',
            })
          ).status,
          409,
        );
        assert.equal(
          (
            await call(worker, 'POST', path + '/risks', {
              ...input,
              category: 'trip',
            })
          ).status,
          409,
        );
        const longZone = await call(worker, 'POST', path + '/risks', {
          ...input,
          requestId: randomUUID(),
          zone: '가'.repeat(100),
        });
        assert.equal(longZone.status, 201);
        assert.equal(longZone.data.risk.title.length, 100);
      },
    );

    await t.test(
      '일반 작업자에게 조치자·재확인자 이름을 제공하고 익명 신고자와 다른 현장은 보호',
      async () => {
        const reported = await call(worker, 'POST', path + '/risks', {
          requestId: randomUUID(),
          title: '개구부 덮개',
          zone: '2층',
          category: 'fall',
          anonymous: true,
        });
        assert.equal(reported.status, 201);
        const route = path + '/risks/' + reported.data.risk.id;
        assert.equal(reported.data.risk.fixerName, null);
        const requested = await call(boss, 'PUT', route + '/request', {
          assigneeId: peer.id,
        });
        assert.equal(requested.status, 200);
        assert.equal(requested.data.risk.assigneeName, peer.name);
        const fixed = await call(peer, 'PUT', route + '/fix', {
          afterPhotoId: await photo(peer, 'risk_after'),
        });
        assert.equal(fixed.status, 200);
        assert.equal(fixed.data.risk.fixerName, peer.name);
        for (const who of [worker, peer, observer]) {
          const detail = (await call(who, 'GET', route)).data.risk;
          assert.equal(detail.canRecheck, who === observer);
          assert.equal(detail.reporterName, null);
          assert.equal(Object.hasOwn(detail, 'reporterId'), false);
        }
        assert.equal(
          (await call(observer, 'GET', path + '/workers')).status,
          403,
        );
        for (const who of [worker, peer])
          assert.equal(
            (await call(who, 'PUT', route + '/recheck', {})).status,
            403,
          );
        const verified = await call(observer, 'PUT', route + '/recheck', {});
        assert.equal(verified.status, 200);
        assert.equal(verified.data.risk.verifierName, observer.name);
        assert.equal(verified.data.risk.canRecheck, false);
        for (const who of [boss, peer, observer]) {
          const detail = await call(who, 'GET', route);
          assert.equal(detail.status, 200);
          const list = await call(who, 'GET', path + '/risks');
          assert.equal(list.status, 200);
          for (const row of [
            detail.data.risk,
            list.data.risks.find((r) => r.id === reported.data.risk.id),
          ]) {
            assert.equal(row.assigneeName, peer.name);
            assert.equal(row.fixerName, peer.name);
            assert.equal(row.verifierName, observer.name);
            assert.equal(row.reporterName, null);
            assert.ok(!('reporterId' in row));
            assert.ok(!JSON.stringify(row).includes(worker.id));
            assert.ok(!JSON.stringify(row).includes(worker.name));
          }
        }
        assert.equal((await call(outsider, 'GET', route)).status, 403);
        assert.equal(
          (await call(outsider, 'GET', path + '/risks')).status,
          403,
        );
        assert.equal(
          (
            await call(
              outsider,
              'GET',
              '/sites/' + otherSite.id + '/risks/' + reported.data.risk.id,
            )
          ).status,
          404,
        );
        const signed = await call(worker, 'POST', path + '/risks', {
          requestId: randomUUID(),
          zone: '2층',
          category: 'trip',
          anonymous: false,
        });
        assert.equal(signed.status, 201);
        assert.equal(
          (await call(observer, 'GET', path + '/risks/' + signed.data.risk.id))
            .data.risk.reporterName,
          worker.name,
        );
        assert.equal(
          (await call(observer, 'GET', path + '/risks')).data.risks.find(
            (r) => r.id === signed.data.risk.id,
          ).reporterName,
          null,
        );
      },
    );

    const shifts = new Map();
    for (const who of [worker, peer, observer]) {
      const response = await call(who, 'POST', '/attendances', {
        siteId: site.id,
      });
      assert.equal(response.status, 201);
      shifts.set(who.id, response.data.attendance.id);
    }
    async function check(checker, target, photoId, outcome = 'checked') {
      const response = await call(
        checker,
        'PUT',
        '/attendances/' + shifts.get(target.id) + '/peer-check',
        { outcome, ...(photoId ? { photoId } : {}) },
      );
      assert.equal(response.status, 200);
    }
    async function counts(expectedChecked, expectedPhotos) {
      const response = await call(boss, 'GET', path + '/dashboard');
      assert.equal(response.status, 200);
      assert.equal(response.data.counts.crossChecked, expectedChecked);
      assert.equal(response.data.counts.photoConfirmed, expectedPhotos);
    }
    let incomingPhoto;
    await t.test(
      '무사진·한 장·쌍방 사진을 구분하고 완료 이력과 현재 불일치를 분리',
      async () => {
        await counts(0, 0);
        await check(peer, worker);
        await counts(0, 0);
        await check(worker, peer);
        await counts(2, 0);
        incomingPhoto = await photo(peer, 'ppe');
        await check(peer, worker, incomingPhoto);
        await counts(2, 0);
        const outgoingPhoto = await photo(worker, 'ppe');
        await check(worker, peer, outgoingPhoto);
        await counts(2, 2);
        await check(worker, peer, null, 'mismatch');
        await counts(2, 2);
        const current = await call(boss, 'GET', path + '/workers');
        assert.equal(
          current.data.workers.find((row) => row.userId === worker.id)
            .peerStatus,
          'mismatch',
        );
        await check(worker, peer, outgoingPhoto);
        await counts(2, 2);
      },
    );
    await t.test(
      '반장 대행은 반장 사진 한 장으로 완료하며 말로 확인은 별도 유지',
      async () => {
        const context = await call(
          observer,
          'GET',
          '/attendances/' + shifts.get(observer.id) + '/context',
        );
        assert.equal(context.data.buddy.userId, boss.id);
        await check(boss, observer);
        await counts(3, 2);
        await check(boss, observer, await photo(boss, 'ppe'));
        await counts(3, 3);
        for (const who of [worker, outsider])
          assert.equal(
            (await call(who, 'GET', path + '/dashboard')).status,
            403,
          );
      },
    );
    await t.test(
      '다른 현장이나 용도의 사진을 가리키는 기록은 사진 완료로 세지 않음',
      async () => {
        await pool.query('UPDATE photos SET site_id=$1 WHERE id=$2', [
          otherSite.id,
          incomingPhoto,
        ]);
        await counts(3, 1);
        await pool.query(
          "UPDATE photos SET site_id=$1,purpose='risk_before' WHERE id=$2",
          [site.id, incomingPhoto],
        );
        await counts(3, 1);
        await pool.query("UPDATE photos SET purpose='ppe' WHERE id=$1", [
          incomingPhoto,
        ]);
        await counts(3, 3);
      },
    );
    await t.test(
      '동료가 퇴근하거나 다시 배정돼도 오늘의 완료 이력은 유지',
      async () => {
        assert.equal(
          (
            await call(
              peer,
              'PUT',
              '/attendances/' + shifts.get(peer.id) + '/checkout',
              {},
            )
          ).status,
          200,
        );
        await counts(3, 3);
        assert.equal(
          (
            await call(boss, 'PUT', path + '/buddy-assignment', {
              workerId: worker.id,
              buddyId: observer.id,
            })
          ).status,
          200,
        );
        await counts(3, 3);
        const current = await call(boss, 'GET', path + '/workers');
        assert.equal(
          current.data.workers.find((row) => row.userId === worker.id)
            .peerStatus,
          'pending',
        );
      },
    );
  },
);
