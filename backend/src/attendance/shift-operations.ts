import { HttpException, NotFoundException } from '@nestjs/common';
import { and, asc, eq, isNull, ne, or } from 'drizzle-orm';
import {
  attendances,
  siteMemberships,
  sites,
  workPrompts,
} from '../database/schema.js';
import type { Tx } from '../records/records.js';
import { siteNotices } from '../database/record-schema.js';
import { enqueueNotice } from '../records/notices.js';

export function conflict(code: string, message: string): never {
  throw new HttpException({ error: { code, message } }, 409);
}

// Changes to both partners use the same site lock taken by shift creation/checkout.
export async function lockSite(tx: Tx, siteId: string) {
  const [site] = await tx
    .select()
    .from(sites)
    .where(eq(sites.id, siteId))
    .for('no key update');
  if (!site) throw new NotFoundException();
  return site;
}

export async function joinedProfile(tx: Tx, siteId: string, userId: string) {
  const [profile] = await tx
    .select()
    .from(siteMemberships)
    .where(
      and(
        eq(siteMemberships.userId, userId),
        eq(siteMemberships.siteId, siteId),
      ),
    );
  if (!profile)
    throw new HttpException(
      {
        error: {
          code: 'SITE_ACCESS_DENIED',
          message: '먼저 현장에 참여해 주세요.',
        },
      },
      403,
    );
  if (!profile.occupation || !profile.zone)
    conflict('SITE_PROFILE_REQUIRED', '직종과 작업 구역을 등록해 주세요.');
  return { ...profile, occupation: profile.occupation, zone: profile.zone };
}

export async function autoPair(
  tx: Tx,
  record: typeof attendances.$inferSelect,
  site: typeof sites.$inferSelect,
) {
  // The foreman covers any number of unpaired workers; worker-to-worker pairs are reciprocal.
  if (record.userId === site.foremanUserId) return record;
  const [partner] = await tx
    .select()
    .from(attendances)
    .where(
      and(
        eq(attendances.siteId, site.id),
        eq(attendances.workDate, record.workDate),
        eq(attendances.zone, record.zone!),
        ne(attendances.userId, record.userId),
        ne(attendances.userId, site.foremanUserId),
        isNull(attendances.checkedOutAt),
        or(
          isNull(attendances.buddyUserId),
          eq(attendances.buddyUserId, site.foremanUserId),
        ),
      ),
    )
    .orderBy(asc(attendances.checkedInAt), asc(attendances.id))
    .limit(1);
  if (partner)
    await tx
      .update(attendances)
      .set({ buddyUserId: record.userId })
      .where(eq(attendances.id, partner.id));
  const [updated] = await tx
    .update(attendances)
    .set({ buddyUserId: partner?.userId ?? site.foremanUserId })
    .where(eq(attendances.id, record.id))
    .returning();
  return updated;
}

export async function schedulePrompts(
  tx: Tx,
  site: typeof sites.$inferSelect,
  record: typeof attendances.$inferSelect,
  start = record.checkedInAt,
) {
  const times = [
    ['break_return', site.breakReturnTime],
    ['shift_change', site.shiftChangeTime],
  ] as const;
  for (const [kind, time] of times) {
    if (!time) continue;
    const dueAt = new Date(`${record.workDate}T${time}:00+09:00`);
    if (dueAt >= start) await addPrompt(tx, site.id, record, kind, dueAt);
  }
}

export async function reschedulePrompts(
  tx: Tx,
  previous: typeof sites.$inferSelect,
  site: typeof sites.$inferSelect,
) {
  const changes = [
    ['break_return', previous.breakReturnTime, site.breakReturnTime],
    ['shift_change', previous.shiftChangeTime, site.shiftChangeTime],
  ] as const;
  if (changes.every(([, before, after]) => before === after)) return;
  const now = new Date();
  const shifts = await tx
    .select()
    .from(attendances)
    .where(
      and(eq(attendances.siteId, site.id), isNull(attendances.checkedOutAt)),
    );
  for (const shift of shifts) {
    for (const [kind, before, after] of changes) {
      if (before === after) continue;
      const [existing] = await tx
        .select()
        .from(workPrompts)
        .where(
          and(
            eq(workPrompts.attendanceId, shift.id),
            eq(workPrompts.kind, kind),
          ),
        )
        .for('update');
      if (existing) {
        if (existing.answeredAt || existing.dueAt <= now) continue;
        const [notice] = await tx
          .select()
          .from(siteNotices)
          .where(
            and(
              eq(siteNotices.kind, 'work_prompt'),
              eq(siteNotices.entityId, existing.id),
            ),
          )
          .for('update');
        if (!notice || notice.status !== 'queued' || notice.attempts > 0)
          continue;
        await tx.delete(siteNotices).where(eq(siteNotices.id, notice.id));
        await tx.delete(workPrompts).where(eq(workPrompts.id, existing.id));
      }
      if (after) {
        const dueAt = new Date(shift.workDate + 'T' + after + ':00+09:00');
        if (dueAt >= now) await addPrompt(tx, site.id, shift, kind, dueAt);
      }
    }
  }
}

export async function addPrompt(
  tx: Tx,
  siteId: string,
  record: Pick<typeof attendances.$inferSelect, 'id' | 'userId'>,
  kind: typeof workPrompts.$inferSelect.kind,
  dueAt: Date,
) {
  const [prompt] = await tx
    .insert(workPrompts)
    .values({
      attendanceId: record.id,
      kind,
      dueAt,
      expiresAt: new Date(dueAt.getTime() + 15 * 60_000),
    })
    .returning();
  await enqueueNotice(tx, {
    siteId,
    recipientId: record.userId,
    kind: 'work_prompt',
    entityId: prompt.id,
    uniqueKey: `prompt:${prompt.id}:${record.userId}`,
    availableAt: dueAt,
  });
}
