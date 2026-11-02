import { ForbiddenException, Injectable } from '@nestjs/common';
import {
  and,
  asc,
  desc,
  eq,
  exists,
  gte,
  inArray,
  lt,
  lte,
  or,
  sql,
} from 'drizzle-orm';
import { DatabaseService } from '../database.service.js';
import { attendances, sites, users, workPrompts } from '../database/schema.js';
import { incidents as calls } from '../database/incident-schema.js';
import {
  auditEvents,
  photos,
  recordCorrections,
} from '../database/record-schema.js';
import { IncidentService } from '../incidents/incident.service.js';
import { requireSiteForeman, requireSiteMember } from '../sites/site-access.js';
import { peerHistory, peerSummary } from '../records/peer-summary.js';
import {
  disclaimer,
  displayTime,
  displayValue,
  type ReportFile,
  type ReportSection,
  wageNote,
} from './report-formats.js';

type Reader = Pick<DatabaseService['db'], 'select'>;
type Tx = Parameters<Parameters<DatabaseService['db']['transaction']>[0]>[0];
type Period = { from: string; to: string };

async function shiftsIn(
  db: Reader,
  period: Period,
  siteId?: string,
  userId?: string,
) {
  const rows = await db
    .select({
      id: attendances.id,
      userId: attendances.userId,
      name: users.name,
      siteId: attendances.siteId,
      siteName: sites.name,
      foremanId: sites.foremanUserId,
      workDate: attendances.workDate,
      checkedInAt: attendances.checkedInAt,
      checkedOutAt: attendances.checkedOutAt,
      occupation: attendances.occupation,
      zone: attendances.zone,
      expectedWage: attendances.expectedWage,
      locationStatus: attendances.locationStatus,
      safetyCheckAt: attendances.safetyCheckUpdatedAt,
      workedMinutes: sql<
        number | null
      >`FLOOR(EXTRACT(epoch FROM (${attendances.checkedOutAt} - ${attendances.checkedInAt})) / 60)::integer`,
    })
    .from(attendances)
    .innerJoin(users, eq(users.id, attendances.userId))
    .leftJoin(sites, eq(sites.id, attendances.siteId))
    .where(
      and(
        gte(attendances.workDate, period.from),
        lte(attendances.workDate, period.to),
        siteId ? eq(attendances.siteId, siteId) : undefined,
        userId ? eq(attendances.userId, userId) : undefined,
      ),
    )
    .orderBy(asc(attendances.workDate), asc(attendances.userId));
  const checked = await peerSummary(db, rows);
  return rows.map(({ foremanId, ...row }) => {
    void foremanId;
    return { ...row, ...checked.get(row.id)! };
  });
}

type Shift = Awaited<ReturnType<typeof shiftsIn>>[number];
async function ppeEvidenceFor(
  db: Reader,
  siteId: string,
  shiftId: string | null,
  day: string | null,
  worker: { id: string; name: string },
  buddyId: string | null,
  foremanId: string,
  incidentAt: Date,
  includePhotos: boolean,
) {
  const images: NonNullable<ReportFile['ppePhotos']> = [];
  const gaps: string[] = [];
  const history = await peerHistory(
    db,
    shiftId && day
      ? [{ id: shiftId, userId: worker.id, siteId, workDate: day, foremanId }]
      : [],
    incidentAt,
  );
  const completion = shiftId ? history.completions.get(shiftId) : undefined;
  const evidenceBuddyId =
    completion?.events.find((event) => event.attendanceId === shiftId)
      ?.checkerId ?? buddyId;
  const [buddy] = evidenceBuddyId
    ? await db
        .select({ id: users.id, name: users.name })
        .from(users)
        .where(eq(users.id, evidenceBuddyId))
    : [];
  const directions = [];
  if (shiftId && day && buddy) {
    directions.push({ shiftId, photographer: buddy, subject: worker });
    if (evidenceBuddyId !== foremanId) {
      const [peer] = await db
        .select({ id: attendances.id })
        .from(attendances)
        .where(
          and(
            eq(attendances.siteId, siteId),
            eq(attendances.userId, buddy.id),
            eq(attendances.workDate, day),
            lte(attendances.checkedInAt, incidentAt),
          ),
        );
      directions.push({
        shiftId: peer?.id ?? null,
        photographer: worker,
        subject: buddy,
      });
    }
  } else
    gaps.push(
      '사고 당시 출근·동료 연결 정보 없음. 보호구 교차 확인 증빙 미확인',
    );
  const checks = [];
  for (const direction of directions) {
    // peer_checks can be overwritten later; use immutable events before the incident.
    const [event] = direction.shiftId
      ? await db
          .select()
          .from(auditEvents)
          .where(
            and(
              eq(auditEvents.siteId, siteId),
              eq(auditEvents.entityId, direction.shiftId),
              eq(auditEvents.actorId, direction.photographer.id),
              eq(auditEvents.subjectId, direction.subject.id),
              eq(auditEvents.source, 'human'),
              completion
                ? eq(
                    auditEvents.id,
                    completion.events.find(
                      (event) => event.attendanceId === direction.shiftId,
                    )!.eventId,
                  )
                : undefined,
              inArray(auditEvents.type, [
                'peer.confirmed',
                'peer.remediated',
                'peer.mismatch',
              ]),
              lte(auditEvents.recordedAt, incidentAt),
            ),
          )
          .orderBy(desc(auditEvents.seq))
          .limit(1)
      : [];
    const candidateId =
      typeof event?.details.photoId === 'string' ? event.details.photoId : null;
    const [photo] = candidateId
      ? await db
          .select({
            id: photos.id,
            bytes: includePhotos ? photos.bytes : sql<Buffer | null>`NULL`,
          })
          .from(photos)
          .where(
            and(
              sql`${photos.id}::text=${candidateId}`,
              eq(photos.siteId, siteId),
              eq(photos.entityId, event!.entityId),
              eq(photos.ownerId, direction.photographer.id),
              eq(photos.purpose, 'ppe'),
              lte(photos.createdAt, event!.recordedAt),
            ),
          )
      : [];
    const evidence = !event
      ? 'missing_check'
      : candidateId
        ? photo
          ? 'photo'
          : 'missing_photo'
        : 'statement';
    if (evidence === 'missing_check')
      gaps.push(direction.subject.name + ' 보호구 교차 확인 기록 없음');
    if (evidence === 'missing_photo')
      gaps.push(direction.subject.name + ' 보호구 교차 확인 사진 미확인');
    if (evidence === 'statement')
      gaps.push(direction.subject.name + ' 보호구 확인은 사진 없는 진술');
    checks.push({
      eventId: event?.id ?? null,
      attendanceId: direction.shiftId,
      photographer: direction.photographer,
      subject: direction.subject,
      serverReceivedAt: event?.recordedAt ?? null,
      outcome: !event
        ? null
        : event.type === 'peer.mismatch'
          ? 'mismatch'
          : 'checked',
      evidence,
      photoId: photo?.id ?? null,
    });
    if (photo?.bytes)
      images.push({
        bytes: photo.bytes,
        caption: `촬영자 ${direction.photographer.name} · 대상자 ${direction.subject.name} · 서버 수신 ${displayTime(event!.recordedAt)} · 원본 사건 ${event!.id}`,
      });
  }
  const status = checks.some((c) => c.outcome === 'mismatch')
    ? 'mismatch'
    : !checks.length ||
        checks.some(
          (c) =>
            c.evidence === 'missing_check' || c.evidence === 'missing_photo',
        )
      ? 'incomplete'
      : checks.every((c) => c.evidence === 'photo')
        ? 'photo_confirmed'
        : 'statement_only';
  const subsequentChecks = completion
    ? history.events
        .filter(
          (event) =>
            event.seq > completion.seq &&
            (event.subjectId === worker.id || event.checkerId === worker.id),
        )
        .map((event) => ({
          eventId: event.eventId,
          attendanceId: event.attendanceId,
          checkerId: event.checkerId,
          subjectId: event.subjectId,
          type: event.type,
          serverReceivedAt: event.serverReceivedAt,
          photoId: event.photoValid ? event.photoId : null,
        }))
    : [];
  return {
    data: {
      status,
      basis: completion
        ? 'completed_before_incident'
        : 'latest_check_before_incident',
      completedAt: completion?.completedAt ?? null,
      checks,
      subsequentChecks,
    },
    images,
    gaps,
  };
}

function totals(shifts: Shift[]) {
  const wages = shifts.flatMap((s) =>
    s.expectedWage === null ? [] : [s.expectedWage],
  );
  return {
    workerDays: shifts.length,
    workedMinutes: shifts.reduce((sum, s) => sum + (s.workedMinutes ?? 0), 0),
    incompleteShifts: shifts.filter((s) => s.checkedOutAt === null).length,
    expectedWageTotal: wages.length
      ? wages.reduce((sum, n) => sum + n, 0)
      : null,
    missingWages: shifts.length - wages.length,
    selfChecks: shifts.filter((s) => s.safetyCheckAt !== null).length,
    mutualChecks: shifts.filter((s) => s.mutuallyChecked).length,
    photoConfirmedChecks: shifts.filter((s) => s.photoConfirmed).length,
    statementOnlyChecks: shifts.filter(
      (s) => s.mutuallyChecked && !s.photoConfirmed,
    ).length,
  };
}
async function evidenceFor(
  db: Reader,
  records: { id: string; siteId: string | null }[],
  exactEventIds: string[] = [],
) {
  const siteRecords = new Map<string, string[]>();
  for (const record of records) {
    if (!record.siteId) continue;
    const list = siteRecords.get(record.siteId);
    if (list) list.push(record.id);
    else siteRecords.set(record.siteId, [record.id]);
  }
  const promptOwners = new Map<string, string>();
  for (const [siteId, ids] of siteRecords) {
    const prompts = await db
      .select({ id: workPrompts.id, shiftId: workPrompts.attendanceId })
      .from(workPrompts)
      .innerJoin(attendances, eq(attendances.id, workPrompts.attendanceId))
      .where(
        and(
          eq(attendances.siteId, siteId),
          inArray(workPrompts.attendanceId, ids),
        ),
      );
    for (const prompt of prompts) {
      promptOwners.set(prompt.id, prompt.shiftId);
      ids.push(prompt.id);
    }
  }
  const filters = Array.from(siteRecords, ([siteId, ids]) =>
    and(eq(auditEvents.siteId, siteId), inArray(auditEvents.entityId, ids)),
  );
  if (exactEventIds.length)
    filters.push(inArray(auditEvents.id, exactEventIds));
  const events = filters.length
    ? await db
        .select({
          id: auditEvents.id,
          siteId: auditEvents.siteId,
          entityId: auditEvents.entityId,
          type: auditEvents.type,
          source: auditEvents.source,
          serverReceivedAt: auditEvents.recordedAt,
          correctsId: auditEvents.correctsId,
          hash: auditEvents.hash,
        })
        .from(auditEvents)
        .where(or(...filters))
        .orderBy(asc(auditEvents.recordedAt), asc(auditEvents.seq))
    : [];
  const eventIds = events.map((e) => e.id);
  const corrections = eventIds.length
    ? await db
        .select({
          id: recordCorrections.id,
          eventId: recordCorrections.eventId,
          entityId: auditEvents.entityId,
          status: recordCorrections.status,
          note: recordCorrections.note,
          createdAt: recordCorrections.createdAt,
          reviewNote: recordCorrections.reviewNote,
          reviewedAt: recordCorrections.reviewedAt,
        })
        .from(recordCorrections)
        .innerJoin(
          auditEvents,
          and(
            eq(auditEvents.id, recordCorrections.eventId),
            eq(auditEvents.siteId, recordCorrections.siteId),
          ),
        )
        .where(inArray(recordCorrections.eventId, eventIds))
        .orderBy(asc(recordCorrections.createdAt))
    : [];
  return { events, corrections, promptOwners };
}
type Evidence = Awaited<ReturnType<typeof evidenceFor>>;

function evidenceSections({ events, corrections }: Evidence): ReportSection[] {
  return [
    {
      title: '원본 사건',
      columns: [
        '원본 사건 ID',
        '대상 기록 ID',
        '사건',
        '근거',
        '서버 수신 시각(KST)',
        '정정 대상 사건 ID',
        '기록 해시',
      ],
      rows: events.map((e) => [
        e.id,
        e.entityId,
        e.type,
        e.source === 'human' ? '사람이 확인' : '시스템 기록·추정',
        displayTime(e.serverReceivedAt),
        e.correctsId,
        e.hash,
      ]),
    },
    {
      title: '정정 이력',
      columns: [
        '원본 사건 ID',
        '정정 요청 ID',
        '처리 상태',
        '요청 내용',
        '검토 내용',
        '요청 시각(KST)',
        '검토 시각(KST)',
      ],
      rows: corrections.map((c) => [
        c.eventId,
        c.id,
        displayValue(c.status),
        c.note,
        c.reviewNote,
        displayTime(c.createdAt),
        displayTime(c.reviewedAt),
      ]),
    },
  ];
}
function gapsFor(rows: Shift[], evidence: Evidence) {
  const gaps: string[] = [];
  if (!rows.length) gaps.push('선택 기간에 출근 기록 없음');
  if (rows.some((r) => r.checkedOutAt === null))
    gaps.push('퇴근 미기록. 해당 근무시간은 합산에서 제외');
  if (rows.some((r) => r.expectedWage === null))
    gaps.push('예상 일당 미입력. 해당 일당은 합산에서 제외');
  if (rows.some((r) => !r.mutuallyChecked))
    gaps.push('상호 확인 완료 증빙이 없는 근로 기록 포함');
  const ids = new Set(evidence.events.map((e) => e.entityId));
  if (rows.some((r) => !ids.has(r.id)))
    gaps.push('연결된 원본 사건이 없는 근로 기록 포함');
  return gaps;
}
function notesSection(
  period: Period | undefined,
  gaps: string[],
  notes: string[],
): ReportSection {
  return {
    title: '보고서 정보',
    columns: ['항목', '값'],
    rows: [
      ...(period
        ? [
            ['기간 시작', period.from],
            ['기간 종료', period.to],
          ]
        : []),
      [
        '누락 기록',
        gaps.length ? gaps.join(' / ') : '조회 범위에서 확인된 누락 없음',
      ],
      ...notes.map((note) => ['안내', note]),
    ],
  };
}

@Injectable()
export class ReportsService {
  constructor(
    private readonly database: DatabaseService,
    private readonly incidents: IncidentService,
  ) {}

  async work(
    viewerId: string,
    period: Period,
    siteId?: string,
    targetId?: string,
    scope: 'self' | 'all' = 'self',
  ) {
    const userId = scope === 'all' ? undefined : (targetId ?? viewerId);
    if (!siteId && viewerId !== userId) throw new ForbiddenException();
    return this.database.db.transaction(
      async (db) => {
        if (siteId && viewerId !== userId)
          await requireSiteForeman(db, siteId, viewerId);
        const rows = await shiftsIn(db, period, siteId, userId);
        if (siteId && userId && !rows.length)
          await requireSiteMember(db, siteId, userId);
        const evidence = await evidenceFor(db, rows);
        const missing = gapsFor(rows, evidence);
        const generatedAt = new Date();
        const summary = totals(rows);
        const data = {
          kind: 'work',
          generatedAt,
          period,
          userId: userId ?? null,
          scope,
          amountBasis: 'expected',
          summary,
          shifts: rows,
          events: evidence.events,
          corrections: evidence.corrections,
          dataGaps: missing,
        };
        const file: ReportFile = {
          title: '작업자 근로 기록',
          generatedAt,
          sections: [
            {
              title: '선택 기간 합계',
              columns: [
                '근로일수',
                '근무 분',
                '예상 일당 합계(원)',
                '퇴근 미기록',
                '일당 미입력',
              ],
              rows: [
                [
                  summary.workerDays,
                  summary.workedMinutes,
                  summary.expectedWageTotal,
                  summary.incompleteShifts,
                  summary.missingWages,
                ],
              ],
            },
            {
              title: '근로 기록',
              columns: [
                '근로일',
                '작업자',
                '현장',
                '직종',
                '구역',
                '출근(KST)',
                '퇴근(KST)',
                '근무 분',
                '예상 일당(원)',
                '출근 기록 ID',
              ],
              rows: rows.map((s) => [
                s.workDate,
                s.name,
                s.siteName,
                s.occupation,
                s.zone,
                displayTime(s.checkedInAt),
                displayTime(s.checkedOutAt),
                s.workedMinutes,
                s.expectedWage,
                s.id,
              ]),
            },
            ...evidenceSections(evidence),
            notesSection(period, missing, [wageNote]),
          ],
        };
        return { data, file };
      },
      { isolationLevel: 'repeatable read', accessMode: 'read only' },
    );
  }
  async monthly(viewerId: string, siteId: string, period: Period) {
    return this.database.db.transaction(
      async (db) => {
        const site = await requireSiteForeman(db, siteId, viewerId);
        const rows = await shiftsIn(db, period, siteId);
        const evidence = await evidenceFor(db, rows);
        const missing = gapsFor(rows, evidence);
        const grouped = new Map<string, Shift[]>();
        for (const shift of rows) {
          const worker = grouped.get(shift.userId);
          if (worker) worker.push(shift);
          else grouped.set(shift.userId, [shift]);
        }
        const pending = evidence.corrections.filter(
          (c) => c.status === 'pending',
        );
        const pendingCounts = new Map<string, number>();
        for (const c of pending)
          pendingCounts.set(
            evidence.promptOwners.get(c.entityId) ?? c.entityId,
            (pendingCounts.get(
              evidence.promptOwners.get(c.entityId) ?? c.entityId,
            ) ?? 0) + 1,
          );
        const workers = Array.from(grouped, ([userId, shifts]) => ({
          userId,
          name: shifts[0].name,
          ...totals(shifts),
          pendingCorrections: shifts.reduce(
            (sum, shift) => sum + (pendingCounts.get(shift.id) ?? 0),
            0,
          ),
        }));
        const generatedAt = new Date();
        const summary = {
          ...totals(rows),
          totalWorkers: workers.length,
          pendingCorrections: pending.length,
        };
        const dailyRecords = rows.map((s) => ({
          id: s.id,
          userId: s.userId,
          name: s.name,
          workDate: s.workDate,
          checkedInAt: s.checkedInAt,
          checkedOutAt: s.checkedOutAt,
        }));
        const data = {
          kind: 'monthly',
          generatedAt,
          disclaimer,
          site: { id: siteId, name: site.name },
          period,
          amountBasis: 'expected',
          summary,
          workers,
          dailyRecords,
          events: evidence.events,
          corrections: evidence.corrections,
          dataGaps: missing,
        };
        const columns = [
          '작업자',
          '근로일수',
          '근무 분',
          '퇴근 미기록',
          '예상 일당 합계(원)',
          '일당 미입력',
          '본인 점검 저장',
          '동료 상호 확인',
          '서로 찍기 확인',
          '사진 없는 상호 확인',
          '정정 대기',
        ];
        const file: ReportFile = {
          title: '월간 근로일·안전확인 집계',
          generatedAt,
          sections: [
            {
              title: '현장 집계',
              columns: ['현장', '작업자 수', ...columns.slice(1)],
              rows: [
                [
                  site.name,
                  summary.totalWorkers,
                  summary.workerDays,
                  summary.workedMinutes,
                  summary.incompleteShifts,
                  summary.expectedWageTotal,
                  summary.missingWages,
                  summary.selfChecks,
                  summary.mutualChecks,
                  summary.photoConfirmedChecks,
                  summary.statementOnlyChecks,
                  summary.pendingCorrections,
                ],
              ],
            },
            {
              title: '작업자 집계',
              columns,
              rows: workers.map((w) => [
                w.name,
                w.workerDays,
                w.workedMinutes,
                w.incompleteShifts,
                w.expectedWageTotal,
                w.missingWages,
                w.selfChecks,
                w.mutualChecks,
                w.photoConfirmedChecks,
                w.statementOnlyChecks,
                w.pendingCorrections,
              ]),
            },
            {
              title: '일별 출퇴근',
              columns: [
                '근로일',
                '작업자',
                '출근(KST)',
                '퇴근(KST)',
                '출근 기록 ID',
              ],
              rows: dailyRecords.map((s) => [
                s.workDate,
                s.name,
                displayTime(s.checkedInAt),
                displayTime(s.checkedOutAt),
                s.id,
              ]),
            },
            ...evidenceSections(evidence),
            notesSection(period, missing, [wageNote, disclaimer]),
          ],
        };
        return { data, file };
      },
      { isolationLevel: 'repeatable read', accessMode: 'read only' },
    );
  }

  async incidentList(
    viewerId: string,
    siteId: string,
    period: Period,
    limit: number,
    offset: number,
  ) {
    const db = this.database.db;
    // Former workers retain access to their own reports. Only the current foreman sees the site list.
    const manager = db
      .select({ id: sites.id })
      .from(sites)
      .where(and(eq(sites.id, siteId), eq(sites.foremanUserId, viewerId)));
    const start = new Date(period.from + 'T00:00:00+09:00');
    const end = new Date(
      Date.parse(period.to + 'T00:00:00+09:00') + 86_400_000,
    );
    const entries = await db
      .select({
        id: calls.id,
        workerName: users.name,
        zone: calls.zone,
        source: calls.source,
        status: calls.status,
        createdAt: calls.createdAt,
        closedAt: calls.closedAt,
      })
      .from(calls)
      .innerJoin(users, eq(users.id, calls.workerId))
      .where(
        and(
          eq(calls.siteId, siteId),
          or(eq(calls.workerId, viewerId), exists(manager)),
          gte(calls.createdAt, start),
          lt(calls.createdAt, end),
        ),
      )
      .orderBy(desc(calls.createdAt), desc(calls.id))
      .offset(offset)
      .limit(limit + 1);
    return {
      period,
      incidents: entries.slice(0, limit),
      nextOffset: entries.length > limit ? offset + limit : null,
    };
  }

  async incident(
    viewerId: string,
    siteId: string,
    id: string,
    includePhoto: boolean,
  ) {
    return this.database.db.transaction(
      (db) => this.incidentSnapshot(db, viewerId, siteId, id, includePhoto),
      { isolationLevel: 'repeatable read', accessMode: 'read only' },
    );
  }
  private async incidentSnapshot(
    db: Tx,
    viewerId: string,
    siteId: string,
    id: string,
    includePhoto: boolean,
  ) {
    const { incident } = await this.incidents.reportDetail(
      siteId,
      id,
      viewerId,
      db,
    );
    // Whitelist the fields for a portable report; live location and raw sensor samples stay out.
    const {
      id: incidentId,
      attendanceId,
      buddyId,
      foremanId,
      worker,
      zone,
      source,
      status,
      createdAt,
      workerResponse,
      workerRespondedAt,
      acceptedAt,
      arrivedAt,
      observation,
      observedAt,
      closedAt,
      resolution,
      escalatedAt,
      peakG,
      recipients,
      dialIntents,
      photoId,
    } = incident;
    const generatedAt = new Date();
    const { day, siteName, occupation, evidence, scenePhoto, ppe } =
      await (async () => {
        const [record] = await db
          .select({
            day: attendances.workDate,
            siteName: sites.name,
            occupation: attendances.occupation,
          })
          .from(sites)
          .leftJoin(
            attendances,
            and(
              attendanceId ? eq(attendances.id, attendanceId) : sql`false`,
              eq(attendances.siteId, sites.id),
              eq(attendances.userId, worker.id),
            ),
          )
          .where(eq(sites.id, siteId));
        const ppe = await ppeEvidenceFor(
          db,
          siteId,
          record?.day ? attendanceId : null,
          record?.day ?? null,
          worker,
          buddyId,
          foremanId,
          new Date(createdAt),
          includePhoto,
        );
        const combined = await evidenceFor(
          db,
          [
            { id, siteId },
            ...(record?.day && attendanceId
              ? [{ id: attendanceId, siteId }]
              : []),
          ],
          [
            ...ppe.data.checks.flatMap((check) =>
              check.eventId ? [check.eventId] : [],
            ),
            ...ppe.data.subsequentChecks.map((check) => check.eventId),
          ],
        );
        // Filter context to what was recorded before/during this case; later unrelated shift events stay private.
        const cutoff = closedAt ? new Date(closedAt) : generatedAt;
        combined.events = combined.events.filter(
          (e) => e.entityId === id || e.serverReceivedAt <= cutoff,
        );
        const allowed = new Set(combined.events.map((e) => e.id));
        combined.corrections = combined.corrections.filter((c) =>
          allowed.has(c.eventId),
        );
        const [photo] =
          includePhoto && photoId
            ? await db
                .select({ bytes: photos.bytes })
                .from(photos)
                .where(
                  and(
                    eq(photos.id, photoId),
                    eq(photos.entityId, id),
                    eq(photos.siteId, siteId),
                    eq(photos.purpose, 'incident_scene'),
                  ),
                )
            : [];
        return {
          day: record?.day ?? null,
          siteName: record?.siteName ?? null,
          occupation: record?.occupation ?? null,
          evidence: combined,
          scenePhoto: photo?.bytes,
          ppe,
        };
      })();
    const missing: string[] = [...ppe.gaps];
    if (!day) missing.push('연결된 근로 기록 없음');
    if (source === 'abnormal_movement' && peakG === null)
      missing.push('충격 세기 미기록');
    if (!evidence.events.some((e) => e.entityId === id))
      missing.push('호출의 원본 사건 미기록');
    if (!arrivedAt && resolution !== 'worker_confirmed_okay')
      missing.push('현장 도착 미확인');
    if (!closedAt) missing.push('대응 종료 미확인');
    const call = {
      id: incidentId,
      worker,
      siteId,
      siteName,
      occupation,
      zone,
      workDate: day,
      source,
      status,
      createdAt,
      peakG,
      workerResponse,
      workerRespondedAt,
      acceptedAt,
      arrivedAt,
      observation,
      observedAt,
      closedAt,
      resolution,
      escalatedAt,
      recipients: recipients.map((r) => ({
        name: r.name,
        role: r.role,
        requestedAt: r.requestedAt,
        sentAt: r.sentAt,
        receivedAt: r.receivedAt,
        openedAt: r.openedAt,
        acceptedAt: r.acceptedAt,
        declinedAt: r.declinedAt,
      })),
      dialerOpenedAt: dialIntents.map((d) => d.openedAt),
      scenePhotoId: photoId,
      ppeEvidence: ppe.data,
    };
    const data = {
      kind: 'incident',
      generatedAt,
      incident: call,
      events: evidence.events,
      corrections: evidence.corrections,
      dataGaps: missing,
      disclaimer,
    };
    const notes = [
      disclaimer,
      '119 전화 화면을 연 시각만 기록합니다. 실제 통화·신고 접수 여부는 별도로 확인해야 합니다.',
    ];
    const file: ReportFile = {
      title: '사고 경위 보고서',
      generatedAt,
      scenePhoto,
      ppePhotos: ppe.images,
      sections: [
        {
          title: ppe.data.completedAt
            ? '사고 전 상호 확인 완료 증빙'
            : '사고 전 보호구 확인 상태',
          columns: [
            '원본 사건 ID',
            '촬영·확인자',
            '대상자',
            '서버 수신 시각(KST)',
            '확인 결과',
            '증빙',
            '사진 ID',
          ],
          rows: ppe.data.checks.map((c) => [
            c.eventId,
            c.photographer.name,
            c.subject.name,
            displayTime(c.serverReceivedAt),
            c.outcome === 'checked'
              ? '확인'
              : c.outcome === 'mismatch'
                ? '불일치'
                : '미확인',
            c.evidence === 'photo'
              ? '사진'
              : c.evidence === 'statement'
                ? '사진 없는 진술'
                : c.evidence === 'missing_photo'
                  ? '사진 미확인'
                  : '기록 없음',
            c.photoId,
          ]),
        },
        {
          title: '완료 증빙 이후 확인 변화',
          columns: ['원본 사건 ID', '사건', '서버 수신 시각(KST)', '사진 ID'],
          rows: ppe.data.subsequentChecks.map((c) => [
            c.eventId,
            c.type,
            displayTime(c.serverReceivedAt),
            c.photoId,
          ]),
        },
        {
          title: '사건 개요',
          columns: [
            '호출 ID',
            '작업자',
            '현장',
            '직종',
            '구역',
            '감지·요청(KST)',
            '신호 출처',
            '최대 충격(G)',
            '응답',
            '응답(KST)',
            '호출 전환(KST)',
            '수락(KST)',
            '도착(KST)',
            '상태 확인',
            '상태 확인(KST)',
            '종료 사유',
            '종료(KST)',
          ],
          rows: [
            [
              id,
              worker.name,
              siteName,
              occupation,
              zone,
              displayTime(createdAt),
              displayValue(source),
              peakG,
              displayValue(workerResponse),
              displayTime(workerRespondedAt),
              displayTime(escalatedAt),
              displayTime(acceptedAt),
              displayTime(arrivedAt),
              displayValue(observation),
              displayTime(observedAt),
              displayValue(resolution),
              displayTime(closedAt),
            ],
          ],
        },
        {
          title: '호출 전달',
          columns: [
            '받는 사람',
            '역할',
            '요청(KST)',
            '발송(KST)',
            '수신(KST)',
            '열람(KST)',
            '수락(KST)',
            '거절(KST)',
          ],
          rows: call.recipients.map((r) => [
            r.name,
            displayValue(r.role),
            displayTime(r.requestedAt),
            displayTime(r.sentAt),
            displayTime(r.receivedAt),
            displayTime(r.openedAt),
            displayTime(r.acceptedAt),
            displayTime(r.declinedAt),
          ]),
        },
        {
          title: '119 전화 화면',
          columns: ['전화 화면 열림(KST)'],
          rows: call.dialerOpenedAt.map((time) => [displayTime(time)]),
        },
        ...evidenceSections(evidence),
        notesSection(undefined, missing, notes),
      ],
    };
    return { data, file };
  }
}
