import { and, asc, eq, inArray, or, sql } from 'drizzle-orm';
import { DatabaseService } from '../database.service.js';
import { attendances } from '../database/schema.js';
import { auditEvents } from '../database/record-schema.js';

type Shift = {
  id: string;
  userId: string;
  siteId: string | null;
  workDate: string;
  foremanId: string | null;
};
type Summary = { mutuallyChecked: boolean; photoConfirmed: boolean };

// Daily completion is historical; current buddy/check readiness belongs to the live worker view.
export async function peerSummary(
  db: Pick<DatabaseService['db'], 'select'>,
  shifts: Shift[],
) {
  return (await peerHistory(db, shifts)).summaries;
}

export async function peerHistory(
  db: Pick<DatabaseService['db'], 'select'>,
  shifts: Shift[],
  before?: Date,
) {
  const result = new Map<string, Summary>(
    shifts.map((shift) => [
      shift.id,
      { mutuallyChecked: false, photoConfirmed: false },
    ]),
  );
  const groups = new Map<string, Shift>();
  for (const shift of shifts)
    if (shift.siteId) groups.set(shift.siteId + ':' + shift.workDate, shift);
  const events = await db
    .select({
      eventId: auditEvents.id,
      seq: auditEvents.seq,
      serverReceivedAt: auditEvents.recordedAt,
      siteId: auditEvents.siteId,
      attendanceId: attendances.id,
      subjectId: attendances.userId,
      workDate: attendances.workDate,
      checkerId: auditEvents.actorId,
      type: auditEvents.type,
      photoId: sql<string | null>`${auditEvents.details}->>'photoId'`,
      photoValid: sql<boolean>`EXISTS(SELECT 1 FROM photos p
      WHERE p.id::text=${auditEvents.details}->>'photoId' AND p.purpose='ppe'
        AND p.site_id=${auditEvents.siteId} AND p.entity_id=${attendances.id}
        AND p.owner_id=${auditEvents.actorId} AND p.created_at<=${auditEvents.recordedAt})`,
    })
    .from(auditEvents)
    .innerJoin(
      attendances,
      and(
        groups.size ? undefined : sql`false`,
        before ? sql`${auditEvents.recordedAt}<=${before}` : undefined,
        eq(attendances.id, auditEvents.entityId),
        eq(attendances.siteId, auditEvents.siteId),
        eq(attendances.userId, auditEvents.subjectId),
      ),
    )
    .where(
      and(
        eq(auditEvents.source, 'human'),
        inArray(auditEvents.type, [
          'peer.confirmed',
          'peer.remediated',
          'peer.mismatch',
        ]),
        or(
          ...Array.from(groups.values(), (shift) =>
            and(
              eq(attendances.siteId, shift.siteId!),
              eq(attendances.workDate, shift.workDate),
            ),
          ),
        ),
      ),
    )
    .orderBy(asc(auditEvents.siteId), asc(auditEvents.seq));
  const latest = new Map<
    string,
    {
      attendanceId: string;
      checked: boolean;
      photo: boolean;
      event: (typeof events)[number];
    }
  >();
  const completions = new Map<
    string,
    { events: typeof events; completedAt: Date; seq: number }
  >();
  function complete(id: string, photo: boolean, evidence: typeof events) {
    const summary = result.get(id);
    if (summary) {
      summary.mutuallyChecked = true;
      summary.photoConfirmed ||= photo;
      const last = evidence[evidence.length - 1];
      completions.set(id, {
        events: evidence,
        completedAt: last.serverReceivedAt,
        seq: last.seq,
      });
    }
  }
  for (const event of events) {
    if (!event.checkerId || event.checkerId === event.subjectId) continue;
    const group = event.siteId + ':' + event.workDate;
    const state = {
      attendanceId: event.attendanceId,
      checked: event.type !== 'peer.mismatch',
      photo: event.photoValid,
      event,
    };
    latest.set(group + ':' + event.subjectId + ':' + event.checkerId, state);
    if (!state.checked) continue;
    if (event.checkerId === groups.get(group)?.foremanId) {
      complete(event.attendanceId, state.photo, [event]);
      continue;
    }
    const reverse = latest.get(
      group + ':' + event.checkerId + ':' + event.subjectId,
    );
    if (!reverse?.checked) continue;
    complete(event.attendanceId, state.photo && reverse.photo, [
      reverse.event,
      event,
    ]);
    complete(reverse.attendanceId, state.photo && reverse.photo, [
      reverse.event,
      event,
    ]);
  }
  return { summaries: result, completions, events };
}
