import 'reflect-metadata';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { NestFactory } from '@nestjs/core';
import { sql } from 'drizzle-orm';
import { drizzle } from 'drizzle-orm/node-postgres';
import { migrate } from 'drizzle-orm/node-postgres/migrator';
import { Pool } from 'pg';
import sharp from 'sharp';
import { AppModule } from '../dist/app.module.js';
import { AuthService } from '../dist/auth/auth.service.js';
import { DatabaseService } from '../dist/database.service.js';
import { IncidentService } from '../dist/incidents/incident.service.js';
import { appendEvent } from '../dist/records/records.js';

const url = process.env.TEST_DATABASE_URL;
test(
  '위험 신고·재확인과 근로·사고 보고서 PostgreSQL 검사',
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

    const shift = randomUUID();
    await db.query(
      `INSERT INTO attendances(id,site_id,user_id,work_date,checked_in_at,checked_out_at,zone,expected_wage) VALUES
    ($1,$2,$3,'2026-11-01','2026-10-31T15:02Z','2026-11-01T00:02Z','=A1',123000),
    (gen_random_uuid(),$2,$3,'2026-10-31','2026-10-30T17:00Z',NULL,'전날',99999),
    (gen_random_uuid(),$2,$4,'2026-11-02','2026-11-01T17:00Z',NULL,NULL,NULL)`,
      [shift, siteId, worker.id, fixer.id],
    );
    await db.query('UPDATE attendances SET buddy_user_id=$2 WHERE id=$1', [
      shift,
      manager.id,
    ]);
    await db.query(
      "INSERT INTO peer_checks(attendance_id,checker_user_id,outcome,checked_at) VALUES ($1,$2,'checked',now())",
      [shift, manager.id],
    );
    const database = app.get(DatabaseService);
    await database.db.transaction((tx) =>
      appendEvent(tx, {
        siteId,
        entityId: shift,
        actorId: worker.id,
        subjectId: worker.id,
        type: 'attendance.check_in',
        source: 'human',
      }),
    );
    await database.db.transaction((tx) =>
      appendEvent(tx, {
        siteId,
        entityId: shift,
        actorId: manager.id,
        subjectId: worker.id,
        type: 'peer.confirmed',
        source: 'human',
        details: { photoId: null },
      }),
    );
    const promptId = randomUUID();
    await db.query(
      "insert into work_prompts(id,attendance_id,kind,due_at,expires_at) values ($1,$2,'high_risk_start',now(),now()+interval '15 minutes')",
      [promptId, shift],
    );
    const promptEvent = await database.db.transaction((tx) =>
      appendEvent(tx, {
        siteId,
        entityId: promptId,
        actorId: worker.id,
        subjectId: worker.id,
        type: 'work.check_in_answered',
        source: 'human',
      }),
    );
    await db.query(
      'insert into record_corrections(site_id,event_id,requester_id,request_id,note) values ($1,$2,$3,$4,$5)',
      [siteId, promptEvent.id, worker.id, randomUUID(), '작업 중 응답 정정'],
    );
    const original = (
      await db.query(
        "SELECT id FROM audit_events WHERE entity_id=$1 AND type='attendance.check_in'",
        [shift],
      )
    ).rows[0].id;
    await db.query(
      'INSERT INTO record_corrections(site_id,event_id,requester_id,request_id,note) VALUES($1,$2,$3,$4,$5)',
      [siteId, original, worker.id, randomUUID(), '근무 시각 확인 요청'],
    );

    await t.test(
      '한국 근로일 기준의 월별 집계·선택 기간·정정 이력과 접근 분리',
      async () => {
        const q = prefix + '/reports/monthly?month=2026-11';
        assert.equal((await call('GET', q, outsider)).status, 403);
        assert.equal((await call('GET', q, worker)).status, 403);
        const month = await (await call('GET', q, manager)).json();
        assert.deepEqual(month.period, {
          from: '2026-11-01',
          to: '2026-11-30',
        });
        assert.equal(month.summary.totalWorkers, 2);
        assert.equal(month.dailyRecords.length, 2);
        assert.equal(month.summary.workerDays, 2);
        assert.equal(month.summary.expectedWageTotal, 123000);
        assert.equal(month.summary.workedMinutes, 540);
        assert.equal(month.summary.mutualChecks, 1);
        assert.equal(month.summary.photoConfirmedChecks, 0);
        assert.equal(month.summary.statementOnlyChecks, 1);
        assert.equal(month.summary.incompleteShifts, 1);
        assert.equal(month.summary.missingWages, 1);
        assert.equal(month.summary.pendingCorrections, 2);
        assert.equal(
          month.workers.find((w) => w.userId === worker.id).pendingCorrections,
          2,
        );
        assert.deepEqual(
          new Set(month.corrections.map((c) => c.eventId)),
          new Set([original, promptEvent.id]),
        );
        const workerPath =
          prefix + '/reports/work?month=2026-11&userId=' + worker.id;
        assert.equal((await call('GET', workerPath, fixer)).status, 403);
        const mine = await (await call('GET', workerPath, worker)).json();
        const managers = await (await call('GET', workerPath, manager)).json();
        assert.deepEqual(mine.shifts, managers.shifts);
        assert.ok(mine.corrections.some((c) => c.eventId === promptEvent.id));
        assert.deepEqual(
          mine.shifts.map((s) => s.id),
          [shift],
        );
        assert.equal(
          (await call('GET', '/reports/work?userId=' + worker.id, outsider))
            .status,
          403,
        );
        assert.equal(
          (
            await call(
              'GET',
              '/reports/work?from=2026-02-30&to=2026-03-01',
              worker,
            )
          ).status,
          400,
        );
        assert.equal(
          (
            await call(
              'GET',
              '/reports/work?from=2026-10-01&to=2026-09-30',
              worker,
            )
          ).status,
          400,
        );
        const empty = await (
          await call('GET', '/reports/work?month=2026-01', worker)
        ).json();
        assert.equal(empty.summary.expectedWageTotal, null);
      },
    );

    await t.test(
      '반장만 명시적으로 전체 작업자 상세 근로 기록을 JSON·CSV·PDF로 내보냄',
      async () => {
        await db.query(
          `INSERT INTO attendances(site_id,user_id,work_date,zone,checked_in_at) VALUES($1,$2,'2026-11-01','외부 비밀 구역',now())`,
          [otherSiteId, outsider.id],
        );
        const route = prefix + '/reports/work?month=2026-11&scope=all';
        for (const viewer of [worker, fixer, outsider])
          assert.equal((await call('GET', route, viewer)).status, 403);
        assert.equal(
          (await call('GET', '/reports/work?scope=all', manager)).status,
          403,
        );
        assert.equal(
          (await call('GET', route + '&userId=' + worker.id, manager)).status,
          400,
        );
        const all = await (await call('GET', route, manager)).json();
        assert.equal(all.scope, 'all');
        assert.equal(all.userId, null);
        assert.equal(all.shifts.length, 2);
        assert.deepEqual(
          new Set(all.shifts.map((s) => s.userId)),
          new Set([worker.id, fixer.id]),
        );
        assert.equal(
          all.shifts.find((s) => s.id === shift).expectedWage,
          123000,
        );
        assert.ok(all.shifts.every((s) => s.siteId === siteId));
        const own = await (
          await call('GET', prefix + '/reports/work?month=2026-11', manager)
        ).json();
        assert.equal(own.userId, manager.id);
        assert.equal(own.shifts.length, 0);
        const csv = await (
          await call('GET', route + '&format=csv', manager)
        ).text();
        assert.ok(csv.includes(worker.name) && csv.includes(fixer.name));
        assert.ok(csv.includes('123000') && !csv.includes('외부 비밀 구역'));
        const pdf = await call('GET', route + '&format=pdf', manager);
        assert.equal(pdf.status, 200);
        assert.equal(
          Buffer.from(await pdf.arrayBuffer())
            .subarray(0, 5)
            .toString(),
          '%PDF-',
        );
      },
    );

    await t.test(
      'CSV 수식 보호 및 실제 한글 폰트가 포함된 PDF 파일 생성',
      async () => {
        const route = '/reports/work?month=2026-11&format=';
        const csv = await call('GET', route + 'csv', worker);
        assert.equal(csv.status, 200);
        assert.match(csv.headers.get('content-type'), /text\/csv/);
        const text = await csv.text();
        assert.match(text, /"'=A1"/);
        assert.match(text, /2026-11-01 00:02:00/);
        assert.ok(!text.includes('전날'));
        assert.ok(text.includes(original));
        assert.ok(text.includes('근무 시각 확인 요청'));
        const response = await call('GET', route + 'pdf', worker);
        assert.equal(response.status, 200);
        assert.match(response.headers.get('content-type'), /application\/pdf/);
        const bytes = Buffer.from(await response.arrayBuffer());
        assert.equal(bytes.subarray(0, 5).toString(), '%PDF-');
        assert.ok(bytes.includes(Buffer.from('/ToUnicode')));
        assert.ok(bytes.includes(Buffer.from('%%EOF')));
      },
    );

    await t.test(
      '사고 PDF·JSON의 열람 권한, 선택 사진, 증빙·위치정보 분리',
      async () => {
        const id = randomUUID();
        const photoId = await photo(worker.id, 'incident_scene');
        await db.query('UPDATE photos SET entity_id=$1 WHERE id=$2', [
          id,
          photoId,
        ]);
        await db.query(
          `INSERT INTO incidents(id,worker_id,site_id,attendance_id,foreman_id,zone,source,status,created_at,closed_at,peak_g,photo_id,location)
       VALUES($1,$2,$3,$4,$5,'2층','abnormal_movement','closed',now(),now(),3.4,$6,'{"latitude":37.99,"longitude":127.99,"accuracyMeters":20}')`,
          [id, worker.id, siteId, shift, manager.id, photoId],
        );
        await database.db.transaction((tx) =>
          appendEvent(tx, {
            siteId,
            entityId: id,
            subjectId: worker.id,
            type: 'incident.signal_detected',
            source: 'system',
          }),
        );
        const route = prefix + '/reports/incidents/' + id;
        const discover = prefix + '/reports/incidents';
        for (const viewer of [worker, manager]) {
          const history = await (await call('GET', discover, viewer)).json();
          assert.ok(history.incidents.some((i) => i.id === id));
        }
        for (const viewer of [fixer, outsider]) {
          const history = await (await call('GET', discover, viewer)).json();
          assert.equal(history.incidents.length, 0);
        }
        assert.equal((await call('GET', route, outsider)).status, 404);
        assert.equal((await call('GET', route, fixer)).status, 404);
        const response = await call('GET', route, worker);
        assert.equal(response.status, 200);
        const content = await response.json();
        assert.equal(content.incident.peakG, 3.4);
        assert.equal(content.incident.scenePhotoId, photoId);
        assert.ok(
          content.events.some(
            (e) => e.entityId === id && e.source === 'system',
          ),
        );
        assert.ok(content.events.some((e) => e.id === original));
        assert.ok(!JSON.stringify(content).includes('latitude'));
        assert.ok(!JSON.stringify(content).includes('sensorWindow'));
        assert.ok(!JSON.stringify(content).includes('expectedWage'));
        const pdf = await call('GET', route + '?format=pdf', manager);
        assert.equal(pdf.status, 200);
        assert.ok(
          Buffer.from(await pdf.arrayBuffer()).includes(
            Buffer.from('/Subtype /Image'),
          ),
        );
      },
    );

    await t.test(
      '사고 이전 양방향 보호구 사진·인물·서버시각 보존과 후속 변경·타 현장 사진 차단',
      async () => {
        const workerShift = randomUUID(),
          peerShift = randomUUID();
        await db.query(
          `INSERT INTO attendances(id,site_id,user_id,work_date,buddy_user_id,checked_in_at) VALUES
        ($1,$3,$4,'2026-10-01',$5,now()-interval '1 hour'),
        ($2,$3,$5,'2026-10-01',$4,now()-interval '1 hour')`,
          [workerShift, peerShift, siteId, worker.id, fixer.id],
        );
        const addCheck = async (
          attendanceId,
          photographer,
          subject,
          imageId,
          type = 'peer.confirmed',
        ) => {
          if (imageId)
            await db.query(
              "UPDATE photos SET entity_id=$1,created_at=now()-interval '1 minute' WHERE id=$2",
              [attendanceId, imageId],
            );
          return database.db.transaction((tx) =>
            appendEvent(tx, {
              siteId,
              entityId: attendanceId,
              actorId: photographer.id,
              subjectId: subject.id,
              type,
              source: 'human',
              details: { photoId: imageId },
            }),
          );
        };
        const left = await photo(fixer.id, 'ppe'),
          right = await photo(worker.id, 'ppe');
        const firstEvent = await addCheck(workerShift, fixer, worker, left);
        const secondEvent = await addCheck(peerShift, worker, fixer, right);
        for (const [attendanceId, checkerId, imageId] of [
          [workerShift, fixer.id, left],
          [peerShift, worker.id, right],
        ])
          await db.query(
            "INSERT INTO peer_checks(attendance_id,checker_user_id,outcome,photo_id,checked_at) VALUES($1,$2,'checked',$3,now())",
            [attendanceId, checkerId, imageId],
          );
        const makeIncident = async (buddyId = fixer.id) => {
          const id = randomUUID();
          await db.query(
            `INSERT INTO incidents(id,site_id,worker_id,attendance_id,foreman_id,buddy_id,zone,source,status,created_at,closed_at)
          VALUES($1,$2,$3,$4,$5,$6,'A','manual_help','closed',now(),now())`,
            [id, siteId, worker.id, workerShift, manager.id, buddyId],
          );
          return prefix + '/reports/incidents/' + id;
        };
        const route = await makeIncident();
        await new Promise((resolve) => setTimeout(resolve, 5));
        // Both mutable current check and later events change; the old incident must retain its original pair.
        await addCheck(workerShift, fixer, worker, null);
        await db.query(
          'UPDATE peer_checks SET photo_id=NULL WHERE attendance_id=$1',
          [workerShift],
        );
        const content = await (await call('GET', route, worker)).json();
        const ppe = content.incident.ppeEvidence;
        assert.equal(ppe.status, 'photo_confirmed');
        assert.deepEqual(
          ppe.checks.map((c) => c.photoId),
          [left, right],
        );
        assert.deepEqual(
          ppe.checks.map((c) => c.eventId),
          [firstEvent.id, secondEvent.id],
        );
        assert.equal(ppe.checks[0].photographer.id, fixer.id);
        assert.equal(ppe.checks[0].subject.id, worker.id);
        assert.ok(
          ppe.checks.every((c) => c.serverReceivedAt && c.evidence === 'photo'),
        );
        assert.ok(!JSON.stringify(content).includes('"bytes"'));
        for (const viewer of [fixer, outsider])
          assert.equal(
            (await call('GET', route + '?format=pdf', viewer)).status,
            404,
          );
        const pdf = Buffer.from(
          await (
            await call('GET', route + '?format=pdf', manager)
          ).arrayBuffer(),
        );
        assert.equal(
          (pdf.toString('latin1').match(/\/Subtype \/Image/g) ?? []).length,
          2,
        );
        const monthly = await (
          await call('GET', prefix + '/reports/monthly?month=2026-10', manager)
        ).json();
        assert.equal(monthly.summary.photoConfirmedChecks, 2);
        assert.equal(monthly.summary.statementOnlyChecks, 0);
        await db.query(
          'UPDATE peer_checks SET photo_id=$2 WHERE attendance_id=$1',
          [workerShift, left],
        );
        const withBoth = await (
          await call('GET', prefix + '/reports/monthly?month=2026-10', manager)
        ).json();
        assert.equal(withBoth.summary.photoConfirmedChecks, 2);
        assert.equal(withBoth.summary.statementOnlyChecks, 0);
        // Checkout reassigns the remaining worker; neither mutation erases a completed day.
        await db.query(
          'UPDATE attendances SET checked_out_at=now(),buddy_user_id=$2 WHERE id=$1',
          [peerShift, manager.id],
        );
        await db.query('UPDATE attendances SET buddy_user_id=$2 WHERE id=$1', [
          workerShift,
          verifier.id,
        ]);
        const reassigned = await (
          await call('GET', prefix + '/reports/monthly?month=2026-10', manager)
        ).json();
        assert.equal(reassigned.summary.photoConfirmedChecks, 2);
        assert.equal(reassigned.summary.mutualChecks, 2);
        const historical = await (await call('GET', route, worker)).json();
        assert.deepEqual(
          historical.incident.ppeEvidence.checks.map((c) => c.photoId),
          [left, right],
        );
        const statementRoute = await makeIncident();
        const statement = await (
          await call('GET', statementRoute, manager)
        ).json();
        assert.equal(statement.incident.ppeEvidence.status, 'statement_only');
        assert.equal(statement.incident.ppeEvidence.checks[0].photoId, null);
        assert.ok(
          statement.dataGaps.some((gap) => gap.includes('사진 없는 진술')),
        );
        await addCheck(workerShift, fixer, worker, left, 'peer.remediated');
        await addCheck(workerShift, verifier, worker, null, 'peer.mismatch');
        const newBuddyRoute = await makeIncident(verifier.id);
        const newBuddyReport = await (
          await call('GET', newBuddyRoute, manager)
        ).json();
        assert.equal(
          newBuddyReport.incident.ppeEvidence.basis,
          'completed_before_incident',
        );
        assert.ok(newBuddyReport.incident.ppeEvidence.completedAt);
        assert.deepEqual(
          newBuddyReport.incident.ppeEvidence.checks.map(
            (check) => check.photoId,
          ),
          [left, right],
        );
        assert.ok(
          newBuddyReport.incident.ppeEvidence.subsequentChecks.some(
            (check) =>
              check.checkerId === verifier.id && check.type === 'peer.mismatch',
          ),
        );
        const newBuddyPdf = Buffer.from(
          await (
            await call('GET', newBuddyRoute + '?format=pdf', manager)
          ).arrayBuffer(),
        );
        assert.equal(
          (newBuddyPdf.toString('latin1').match(/\/Subtype \/Image/g) ?? [])
            .length,
          2,
        );
        const foreign = await photo(fixer.id, 'ppe', otherSiteId);
        await addCheck(workerShift, fixer, worker, foreign);
        const forbiddenRoute = await makeIncident();
        const guarded = await (
          await call('GET', forbiddenRoute, manager)
        ).json();
        assert.equal(guarded.incident.ppeEvidence.status, 'incomplete');
        assert.equal(
          guarded.incident.ppeEvidence.checks[0].evidence,
          'missing_photo',
        );
        assert.ok(
          !JSON.stringify(guarded.incident.ppeEvidence).includes(foreign),
        );
        const guardedPdf = Buffer.from(
          await (
            await call('GET', forbiddenRoute + '?format=pdf', manager)
          ).arrayBuffer(),
        );
        assert.equal(
          (guardedPdf.toString('latin1').match(/\/Subtype \/Image/g) ?? [])
            .length,
          1,
        );
        for (const invalidKind of ['owner', 'purpose', 'entity', 'future']) {
          const invalidPhoto = await photo(
            invalidKind === 'owner' ? verifier.id : fixer.id,
            invalidKind === 'purpose' ? 'incident_scene' : 'ppe',
          );
          await addCheck(workerShift, fixer, worker, invalidPhoto);
          if (invalidKind === 'entity')
            await db.query('UPDATE photos SET entity_id=$2 WHERE id=$1', [
              invalidPhoto,
              peerShift,
            ]);
          if (invalidKind === 'future')
            await db.query(
              "UPDATE photos SET created_at=now()+interval '1 hour' WHERE id=$1",
              [invalidPhoto],
            );
          const invalidRoute = await makeIncident();
          const invalidReport = await (
            await call('GET', invalidRoute, manager)
          ).json();
          assert.equal(
            invalidReport.incident.ppeEvidence.checks[0].evidence,
            'missing_photo',
            invalidKind,
          );
          assert.equal(
            invalidReport.incident.ppeEvidence.checks[0].photoId,
            null,
            invalidKind,
          );
        }
      },
    );

    await t.test(
      '보고서 조회 중 수락이 저장되어도 개요·원본 사건은 같은 읽기 시점으로 고정',
      async () => {
        const id = randomUUID();
        await db.query(
          `INSERT INTO incidents(id,site_id,worker_id,attendance_id,foreman_id,zone,source,status,created_at)
        VALUES($1,$2,$3,$4,$5,'A','manual_help','calling',now())`,
          [id, siteId, worker.id, shift, manager.id],
        );
        const service = app.get(IncidentService);
        const originalRead = service.reportDetail;
        let ready, resume;
        const reached = new Promise((resolve) => {
          ready = resolve;
        });
        const gate = new Promise((resolve) => {
          resume = resolve;
        });
        service.reportDetail = async function (...args) {
          const result = await originalRead.apply(this, args);
          if (args[1] === id) {
            ready();
            await gate;
          }
          return result;
        };
        const route = prefix + '/reports/incidents/' + id;
        const pending = call('GET', route, manager);
        try {
          await Promise.race([
            reached,
            pending.then(() => {
              throw new Error('보고서 조회 동기화 실패');
            }),
          ]);
          await database.db.transaction(async (tx) => {
            await tx.execute(
              sql`UPDATE incidents SET status='responding',responder_id=${fixer.id},accepted_at=now() WHERE id=${id}`,
            );
            await appendEvent(tx, {
              siteId,
              entityId: id,
              actorId: fixer.id,
              subjectId: worker.id,
              type: 'incident.accepted',
              source: 'human',
            });
          });
        } finally {
          resume();
          service.reportDetail = originalRead;
        }
        const frozen = await (await pending).json();
        assert.equal(frozen.incident.status, 'calling');
        assert.equal(frozen.incident.acceptedAt, null);
        assert.ok(
          !frozen.events.some((event) => event.type === 'incident.accepted'),
        );
        const updated = await (await call('GET', route, manager)).json();
        assert.equal(updated.incident.status, 'responding');
        assert.ok(updated.incident.acceptedAt);
        assert.ok(
          updated.events.some((event) => event.type === 'incident.accepted'),
        );
      },
    );

    await t.test(
      '서로 다른 시점의 확인·불일치를 합쳐 허위 완료로 집계하지 않음',
      async () => {
        const one = randomUUID(),
          two = randomUUID();
        await db.query(
          `INSERT INTO attendances(id,site_id,user_id,work_date,checked_in_at) VALUES
        ($1,$3,$4,'2026-09-01',now()),($2,$3,$5,'2026-09-01',now())`,
          [one, two, siteId, worker.id, fixer.id],
        );
        const event = (id, actor, subject, type) =>
          database.db.transaction((tx) =>
            appendEvent(tx, {
              siteId,
              entityId: id,
              actorId: actor.id,
              subjectId: subject.id,
              type,
              source: 'human',
              details: { photoId: null },
            }),
          );
        await event(one, fixer, worker, 'peer.confirmed');
        await event(one, fixer, worker, 'peer.mismatch');
        await event(two, worker, fixer, 'peer.confirmed');
        const route = prefix + '/reports/monthly?month=2026-09';
        const incomplete = await (await call('GET', route, manager)).json();
        assert.equal(incomplete.summary.mutualChecks, 0);
        await event(one, fixer, worker, 'peer.remediated');
        const completed = await (await call('GET', route, manager)).json();
        assert.equal(completed.summary.mutualChecks, 2);
        assert.equal(completed.summary.statementOnlyChecks, 2);
        await event(two, worker, fixer, 'peer.mismatch');
        const preserved = await (await call('GET', route, manager)).json();
        assert.equal(preserved.summary.mutualChecks, 2);
      },
    );
    await t.test(
      '본인 확인 종료 보고서와 도착 후 관찰 전 관리자 미확인 인원',
      async () => {
        const route = '/sites/' + otherSiteId;
        await db.query(
          'insert into site_memberships(site_id,user_id) values($1,$2) on conflict do nothing',
          [otherSiteId, verifier.id],
        );
        await db.query(
          "insert into attendances(site_id,user_id,work_date,checked_in_at) values ($1,$2,(now() at time zone 'Asia/Seoul')::date,now()-interval '10 minutes')",
          [otherSiteId, verifier.id],
        );
        const api = async (method, path, actor, body) => {
          const response = await call(method, path, actor, body);
          assert.ok(response.ok, method + ' ' + path + ' ' + response.status);
          return response.json();
        };
        const self = (
          await api('POST', '/incidents', verifier, {
            siteId: otherSiteId,
            source: 'abnormal_movement',
            requestId: randomUUID(),
          })
        ).incident;
        await api(
          'PUT',
          '/incidents/' + self.id + '/worker-response',
          verifier,
          { response: 'okay', requestId: randomUUID() },
        );
        const report = await api(
          'GET',
          route + '/reports/incidents/' + self.id,
          verifier,
        );
        assert.equal(report.incident.resolution, 'worker_confirmed_okay');
        assert.ok(!report.dataGaps.includes('현장 도착 미확인'));
        const incident = (
          await api('POST', '/incidents', verifier, {
            siteId: otherSiteId,
            source: 'manual_help',
            requestId: randomUUID(),
          })
        ).incident;
        const path = '/incidents/' + incident.id;
        await api('PUT', path + '/accept', outsider, {});
        await api('PUT', path + '/arrival', outsider, {});
        const count = async () =>
          (await api('GET', route + '/dashboard', outsider)).counts.unverified;
        assert.equal(await count(), 1);
        await api('PUT', path + '/observation', outsider, {
          condition: 'conscious',
        });
        assert.equal(await count(), 0);
      },
    );
  },
);
