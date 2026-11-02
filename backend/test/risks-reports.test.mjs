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

const url = process.env.TEST_DATABASE_URL;
test(
  '위험 신고·재확인 PostgreSQL 검사',
  { skip: url ? false : 'TEST_DATABASE_URL 필요', timeout: 120_000 },
  async (t) => {
    const dbName = decodeURIComponent(new URL(url).pathname.slice(1));
    assert.ok(dbName.endsWith('_test'));
    const db = new Pool({ connectionString: url });
    const prev = process.env.DATABASE_URL;
    process.env.DATABASE_URL = url;
    const users = [];
    const siteId = randomUUID();
    const otherSiteId = randomUUID();
    let app;
    t.after(async () => {
      try {
        await app?.close();
        await db.query('DELETE FROM incidents WHERE site_id=ANY($1::uuid[])', [
          [siteId, otherSiteId],
        ]);
        await db.query(
          'DELETE FROM attendances WHERE site_id=ANY($1::uuid[])',
          [[siteId, otherSiteId]],
        );
        await db.query('DELETE FROM sites WHERE id=ANY($1::uuid[])', [
          [siteId, otherSiteId],
        ]);
        await db.query('DELETE FROM users WHERE id=ANY($1::uuid[])', [users]);
      } finally {
        await db.end();
        if (prev === undefined) delete process.env.DATABASE_URL;
        else process.env.DATABASE_URL = prev;
      }
    });
    assert.equal(
      (await db.query('SELECT current_database() AS name')).rows[0].name,
      dbName,
    );
    await migrate(drizzle(db), {
      migrationsFolder: fileURLToPath(new URL('../drizzle', import.meta.url)),
    });
    app = await NestFactory.create(AppModule, { logger: false });
    await app.listen(0, '127.0.0.1');
    const base = await app.getUrl();
    const auth = app.get(AuthService);
    async function account(name) {
      const email = randomUUID() + '@example.com',
        password = 'examplePass1234';
      const { user } = await auth.signup({ name, email, password });
      users.push(user.id);
      return {
        ...user,
        token: (await auth.login({ email, password })).accessToken,
      };
    }
    const manager = await account('검사 반장');
    const worker = await account('비공개 신고자');
    const fixer = await account('조치 작업자');
    const verifier = await account('재확인 작업자');
    const outsider = await account('다른 현장');
    await db.query(
      'INSERT INTO sites (id,name,code,foreman_user_id) VALUES ($1,$2,$3,$4)',
      [siteId, '검사 현장', randomUUID().slice(0, 8).toUpperCase(), manager.id],
    );
    for (const u of [worker, fixer, verifier])
      await db.query(
        'INSERT INTO site_memberships(site_id,user_id) VALUES ($1,$2)',
        [siteId, u.id],
      );
    await db.query(
      'INSERT INTO sites(id,name,code,foreman_user_id) VALUES($1,$2,$3,$4)',
      [
        otherSiteId,
        '보고서 제외 현장',
        randomUUID().slice(0, 8).toUpperCase(),
        outsider.id,
      ],
    );
    const jpeg = await sharp({
      create: { width: 40, height: 30, channels: 3, background: '#008800' },
    })
      .jpeg()
      .toBuffer();
    async function photo(userId, purpose, atSite = siteId) {
      return (
        await db.query(
          'INSERT INTO photos(site_id,owner_id,purpose,bytes) VALUES ($1,$2,$3,$4) RETURNING id',
          [atSite, userId, purpose, jpeg],
        )
      ).rows[0].id;
    }
    const call = (method, path, actor, body) =>
      fetch(base + path, {
        method,
        headers: {
          Authorization: 'Bearer ' + actor.token,
          'Content-Type': 'application/json',
        },
        body: body === undefined ? undefined : JSON.stringify(body),
        signal: AbortSignal.timeout(15_000),
      });
    const prefix = '/sites/' + siteId;
    const riskPath = prefix + '/risks';

    await t.test(
      '익명 신고·중복 제출·제3자 사진 거부와 독립 재확인',
      async () => {
        const input = {
          requestId: randomUUID(),
          title: '개구부 덮개',
          zone: '2층 동측',
          category: 'fall',
          anonymous: true,
          beforePhotoId: await photo(worker.id, 'risk_before'),
        };
        assert.equal(
          (await call('POST', riskPath, outsider, input)).status,
          403,
        );
        const invalid = await call('POST', riskPath, fixer, {
          ...input,
          requestId: randomUUID(),
        });
        assert.equal(invalid.status, 404);
        const submitted = await call('POST', riskPath, worker, input);
        assert.equal(submitted.status, 201);
        const { risk } = await submitted.json();
        assert.equal(risk.reporterName, null);
        assert.equal(risk.status, 'open');
        assert.equal((await call('POST', riskPath, worker, input)).status, 200);
        assert.equal(
          (
            await call('POST', riskPath, worker, {
              ...input,
              title: '다른 제목',
            })
          ).status,
          409,
        );
        const list = await (await call('GET', riskPath, manager)).json();
        assert.equal(list.risks.length, 1);
        assert.equal(list.counts.open, 1);
        for (const secret of [worker.id, worker.name, input.requestId])
          assert.ok(!JSON.stringify(list).includes(secret));
        const [reported] = (
          await db.query(
            "SELECT actor_id,subject_id FROM audit_events WHERE entity_id=$1 AND type='risk.reported'",
            [risk.id],
          )
        ).rows;
        assert.deepEqual(reported, { actor_id: null, subject_id: null });
        const item = riskPath + '/' + risk.id;
        assert.equal(
          (
            await call('PUT', item + '/request', worker, {
              assigneeId: fixer.id,
            })
          ).status,
          403,
        );
        for (let n = 0; n < 2; n++)
          assert.equal(
            (
              await call('PUT', item + '/request', manager, {
                assigneeId: fixer.id,
              })
            ).status,
            200,
          );
        assert.equal(
          (
            await db.query(
              "SELECT COUNT(*)::int AS n FROM site_notices WHERE entity_id=$1 AND kind='risk_action'",
              [risk.id],
            )
          ).rows[0].n,
          1,
        );
        const image = await photo(fixer.id, 'risk_after');
        for (let n = 0; n < 2; n++)
          assert.equal(
            (await call('PUT', item + '/fix', fixer, { afterPhotoId: image }))
              .status,
            200,
          );
        for (const person of [worker, fixer])
          assert.equal(
            (await call('PUT', item + '/recheck', person, {})).status,
            403,
          );
        for (let n = 0; n < 2; n++)
          assert.equal(
            (await call('PUT', item + '/recheck', verifier, {})).status,
            200,
          );
        const { risk: finished } = await (
          await call('GET', item, manager)
        ).json();
        assert.equal(finished.status, 'resolved');
        assert.ok(finished.fixedAt && finished.verifiedAt);
        assert.equal((await call('GET', item, outsider)).status, 403);
        const withoutPhoto = { ...input, requestId: randomUUID() };
        delete withoutPhoto.beforePhotoId;
        assert.equal(
          (await call('POST', riskPath, worker, withoutPhoto)).status,
          201,
        );
        assert.equal(
          (await call('POST', riskPath, worker, withoutPhoto)).status,
          200,
        );
        const signed = await (
          await call('POST', riskPath, worker, {
            ...withoutPhoto,
            requestId: randomUUID(),
            anonymous: false,
          })
        ).json();
        assert.equal(signed.risk.reporterName, worker.name);
        const publicList = await (await call('GET', riskPath, fixer)).json();
        assert.ok(publicList.risks.every((r) => r.reporterName === null));
      },
    );
  },
);
