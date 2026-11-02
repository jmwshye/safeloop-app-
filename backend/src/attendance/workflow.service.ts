import { Injectable, NotFoundException } from '@nestjs/common';
import { and, asc, desc, eq, isNull, lte, ne, or } from 'drizzle-orm';
import { DatabaseService } from '../database.service.js';
import {
  attendances,
  peerChecks,
  safetyReviews,
  shiftDevices,
  siteMemberships,
  users,
  workPrompts,
} from '../database/schema.js';
import { IncidentService } from '../incidents/incident.service.js';
import { appendEvent, bindPhoto } from '../records/records.js';
import { enqueueNotice } from '../records/notices.js';
import { denySite, requireSiteForeman } from '../sites/site-access.js';
import type { DeviceInput, PeerInput } from './attendance.schemas.js';
import { addPrompt, conflict, lockSite } from './shift-operations.js';
import { workDateAt } from './work-time.js';

const freshnessMs = 5 * 60_000;
function deviceStatus(row: typeof shiftDevices.$inferSelect | null, now: Date) {
  const fresh =
    row &&
    now.valueOf() -
      Math.min(row.observedAt.valueOf(), row.receivedAt.valueOf()) <
      freshnessMs;
  return {
    state: fresh ? row.state : 'unverified',
    lastSeenAt: row?.receivedAt ?? null,
  };
}

function promptState(
  p: typeof workPrompts.$inferSelect,
  now: Date,
  end: Date | null,
) {
  if (p.response) return p.response;
  if (end && end < p.dueAt) return 'cancelled';
  if (now >= p.expiresAt || end) return 'unverified';
  return now < p.dueAt ? 'scheduled' : 'pending';
}

function ensureActive(record: typeof attendances.$inferSelect) {
  if (!record.siteId || record.checkedOutAt) {
    conflict('SHIFT_NOT_ACTIVE', '오늘 근무 중인 기록에서만 처리할 수 있어요.');
  }
  return record.siteId;
}

@Injectable()
export class WorkflowService {
  constructor(
    private readonly database: DatabaseService,
    private readonly incidents: IncidentService,
  ) {}

  async device(userId: string, id: string, input: DeviceInput) {
    return this.database.db.transaction(async (tx) => {
      const [shift] = await tx
        .select()
        .from(attendances)
        .where(and(eq(attendances.id, id), eq(attendances.userId, userId)))
        .for('no key update');
      if (!shift) throw new NotFoundException();
      const now = new Date();
      ensureActive(shift);
      const observedAt = new Date(input.observedAt);
      const [prior] = await tx
        .select()
        .from(shiftDevices)
        .where(eq(shiftDevices.attendanceId, id));
      if (prior && observedAt <= prior.observedAt)
        return { device: deviceStatus(prior, now) };
      const age = now.valueOf() - observedAt.valueOf();
      if (age < -10_000 || age >= freshnessMs)
        conflict('DEVICE_READING_STALE', '최신 기기 상태를 다시 보내 주세요.');
      const values = { state: input.state, observedAt, receivedAt: now };
      const [current] = await tx
        .insert(shiftDevices)
        .values({ attendanceId: id, ...values })
        .onConflictDoUpdate({ target: shiftDevices.attendanceId, set: values })
        .returning();
      return { device: deviceStatus(current, now) };
    });
  }

  async context(userId: string, id: string) {
    const db = this.database.db;
    const [own] = await db
      .select({ shift: attendances, device: shiftDevices })
      .from(attendances)
      .leftJoin(shiftDevices, eq(shiftDevices.attendanceId, attendances.id))
      .where(and(eq(attendances.id, id), eq(attendances.userId, userId)));
    if (!own) throw new NotFoundException();
    const { shift } = own;
    const now = new Date();
    const active = !shift.checkedOutAt;
    const prompts = await db
      .select()
      .from(workPrompts)
      .where(eq(workPrompts.attendanceId, id))
      .orderBy(asc(workPrompts.dueAt));
    let buddy: null | {
      userId: string;
      name: string;
      attendanceId: string | null;
      zone: string | null;
      highRiskTask: string | null;
      device: ReturnType<typeof deviceStatus> | null;
    } = null;
    if (shift.siteId && shift.buddyUserId) {
      const [row] = await db
        .select({
          userId: users.id,
          name: users.name,
          attendanceId: attendances.id,
          zone: attendances.zone,
          highRiskTask: attendances.highRiskTask,
          deviceRow: shiftDevices,
          ended: attendances.checkedOutAt,
        })
        .from(users)
        .leftJoin(
          attendances,
          and(
            eq(attendances.userId, users.id),
            eq(attendances.siteId, shift.siteId),
            eq(attendances.workDate, shift.workDate),
          ),
        )
        .leftJoin(shiftDevices, eq(shiftDevices.attendanceId, attendances.id))
        .where(eq(users.id, shift.buddyUserId));
      if (row) {
        const { deviceRow, ended, ...identity } = row;
        buddy = {
          ...identity,
          highRiskTask: active && !ended ? identity.highRiskTask : null,
          device:
            active && !ended && row.attendanceId
              ? deviceStatus(deviceRow, now)
              : null,
        };
      }
    }
    const pairs = await db
      .select()
      .from(peerChecks)
      .where(
        or(
          and(
            eq(peerChecks.attendanceId, id),
            buddy ? eq(peerChecks.checkerUserId, buddy.userId) : undefined,
          ),
          buddy?.attendanceId
            ? and(
                eq(peerChecks.attendanceId, buddy.attendanceId),
                eq(peerChecks.checkerUserId, userId),
              )
            : undefined,
        ),
      );
    return {
      buddy,
      device: active ? deviceStatus(own.device, now) : null,
      peerChecks: pairs,
      prompts: prompts.map((p) => ({
        ...p,
        state: promptState(p, now, shift.checkedOutAt),
      })),
    };
  }

  async checkPeer(userId: string, id: string, input: PeerInput) {
    return this.database.db.transaction(async (tx) => {
      const [target] = await tx
        .select()
        .from(attendances)
        .where(eq(attendances.id, id))
        .for('no key update');
      if (!target || target.buddyUserId !== userId || target.userId === userId)
        throw new NotFoundException();
      const now = new Date();
      const siteId = ensureActive(target);
      const [previous] = await tx
        .select()
        .from(peerChecks)
        .where(
          and(
            eq(peerChecks.attendanceId, id),
            eq(peerChecks.checkerUserId, userId),
          ),
        );
      const photoId = input.photoId ?? null;
      if (previous?.outcome === input.outcome && previous.photoId === photoId)
        return { check: previous };
      if (photoId)
        await bindPhoto(tx, {
          photoId,
          siteId,
          ownerId: userId,
          purpose: 'ppe',
          entityId: id,
          viewerIds: [target.userId],
        });
      const fields = { outcome: input.outcome, photoId, checkedAt: now };
      const [check] = await tx
        .insert(peerChecks)
        .values({ attendanceId: id, checkerUserId: userId, ...fields })
        .onConflictDoUpdate({
          target: [peerChecks.attendanceId, peerChecks.checkerUserId],
          set: fields,
        })
        .returning();
      const type =
        input.outcome === 'mismatch'
          ? 'peer.mismatch'
          : previous?.outcome === 'mismatch'
            ? 'peer.remediated'
            : 'peer.confirmed';
      await appendEvent(tx, {
        siteId,
        entityId: id,
        actorId: userId,
        subjectId: target.userId,
        type,
        source: 'human',
        details: { photoId },
      });
      return { check };
    });
  }

  async highRisk(userId: string, id: string, task: string | null) {
    return this.database.db.transaction(async (tx) => {
      const [record] = await tx
        .select()
        .from(attendances)
        .where(and(eq(attendances.id, id), eq(attendances.userId, userId)))
        .for('no key update');
      if (!record) throw new NotFoundException();
      const now = new Date();
      const siteId = ensureActive(record);
      const highRiskStartedAt = task ? (record.highRiskStartedAt ?? now) : null;
      if (record.highRiskTask !== task) {
        if (record.highRiskTask && task)
          conflict(
            'HIGH_RISK_ACTIVE',
            '진행 중인 고위험 작업을 먼저 종료해 주세요.',
          );
        await tx
          .update(attendances)
          .set({ highRiskTask: task, highRiskStartedAt })
          .where(eq(attendances.id, id));
        const event = await appendEvent(tx, {
          siteId,
          entityId: id,
          actorId: userId,
          subjectId: userId,
          type: task ? 'work.high_risk_started' : 'work.high_risk_ended',
          source: 'human',
          details: { task },
        });
        await addPrompt(
          tx,
          siteId,
          record,
          task ? 'high_risk_start' : 'high_risk_end',
          now,
        );
        if (record.buddyUserId)
          await enqueueNotice(tx, {
            siteId,
            recipientId: record.buddyUserId,
            kind: task ? 'high_risk' : 'high_risk_end',
            entityId: id,
            uniqueKey: `high-risk:${event.id}:${record.buddyUserId}`,
          });
      }
      return { highRiskTask: task, highRiskStartedAt };
    });
  }

  async answerPrompt(
    userId: string,
    id: string,
    promptId: string,
    answer: 'okay' | 'help_needed',
  ) {
    return this.database.db.transaction(async (tx) => {
      const [row] = await tx
        .select({ prompt: workPrompts, shift: attendances })
        .from(workPrompts)
        .innerJoin(attendances, eq(attendances.id, workPrompts.attendanceId))
        .where(
          and(
            eq(workPrompts.id, promptId),
            eq(attendances.id, id),
            eq(attendances.userId, userId),
          ),
        )
        .for('no key update');
      if (!row || !row.shift.siteId) throw new NotFoundException();
      const { prompt, shift } = row;
      if (prompt.response === answer) return { prompt };
      const now = new Date();
      const siteId = ensureActive(shift);
      if (promptState(prompt, now, shift.checkedOutAt) !== 'pending')
        conflict('CHECK_IN_NOT_PENDING', '지금 응답할 확인 요청이 없어요.');
      if (answer === 'help_needed')
        await this.incidents.open(tx, userId, {
          siteId,
          requestId: promptId,
          source: 'manual_help',
        });
      const [saved] = await tx
        .update(workPrompts)
        .set({ response: answer, answeredAt: now })
        .where(eq(workPrompts.id, promptId))
        .returning();
      await appendEvent(tx, {
        siteId,
        entityId: promptId,
        actorId: userId,
        subjectId: userId,
        type: 'work.check_in_answered',
        source: 'human',
        details: { response: answer, kind: prompt.kind },
      });
      return { prompt: saved };
    });
  }

  async reassign(
    siteId: string,
    userId: string,
    worker: string,
    buddy: string,
  ) {
    return this.database.db.transaction(async (tx) => {
      const site = await lockSite(tx, siteId);
      if (site.foremanUserId !== userId) denySite();
      if (worker === buddy)
        conflict('BUDDY_SELF', '다른 동료를 선택해 주세요.');
      const active = await tx
        .select()
        .from(attendances)
        .where(
          and(eq(attendances.siteId, siteId), isNull(attendances.checkedOutAt)),
        );
      const first = active.find((r) => r.userId === worker);
      const second = active.find((r) => r.userId === buddy);
      if (!first || (!second && buddy !== site.foremanUserId))
        throw new NotFoundException();
      if (worker === site.foremanUserId)
        conflict(
          'BUDDY_FOREMAN',
          '작업자를 먼저 선택하고 반장을 확인 담당으로 지정해 주세요.',
        );
      if (
        first.buddyUserId === buddy &&
        (buddy === site.foremanUserId || second?.buddyUserId === worker)
      )
        return { workerId: worker, buddyId: buddy };
      for (const rec of active) {
        if (
          rec.userId === worker ||
          rec.userId === site.foremanUserId ||
          rec.userId === buddy
        )
          continue;
        if (
          rec.buddyUserId === worker ||
          (buddy !== site.foremanUserId && rec.buddyUserId === buddy)
        ) {
          await tx
            .update(attendances)
            .set({ buddyUserId: site.foremanUserId })
            .where(eq(attendances.id, rec.id));
        }
      }
      await tx
        .update(attendances)
        .set({ buddyUserId: buddy })
        .where(eq(attendances.id, first.id));
      if (second && buddy !== site.foremanUserId)
        await tx
          .update(attendances)
          .set({ buddyUserId: worker })
          .where(eq(attendances.id, second.id));
      await appendEvent(tx, {
        siteId,
        entityId: first.id,
        actorId: userId,
        subjectId: worker,
        type: 'peer.assigned',
        source: 'human',
        details: { buddyId: buddy },
      });
      return { workerId: worker, buddyId: buddy };
    });
  }

  async workers(siteId: string, userId: string) {
    const db = this.database.db;
    const site = await requireSiteForeman(db, siteId, userId);
    const now = new Date();
    const day = workDateAt(now);
    const rows = await db
      .select({
        userId: users.id,
        name: users.name,
        profileOccupation: siteMemberships.occupation,
        profileZone: siteMemberships.zone,
        shift: attendances,
        device: shiftDevices,
        peer: peerChecks,
      })
      .from(siteMemberships)
      .innerJoin(users, eq(users.id, siteMemberships.userId))
      .leftJoin(
        attendances,
        and(
          eq(attendances.userId, users.id),
          eq(attendances.siteId, siteId),
          or(eq(attendances.workDate, day), isNull(attendances.checkedOutAt)),
        ),
      )
      .leftJoin(
        peerChecks,
        and(
          eq(peerChecks.attendanceId, attendances.id),
          eq(peerChecks.checkerUserId, attendances.buddyUserId),
        ),
      )
      .leftJoin(shiftDevices, eq(shiftDevices.attendanceId, attendances.id))
      .where(
        and(
          eq(siteMemberships.siteId, siteId),
          ne(siteMemberships.userId, site.foremanUserId),
        ),
      )
      .orderBy(asc(users.name), asc(users.id));
    const prompts = await db
      .select({ prompt: workPrompts, worker: attendances.userId })
      .from(workPrompts)
      .innerJoin(attendances, eq(attendances.id, workPrompts.attendanceId))
      .where(
        and(
          eq(attendances.siteId, siteId),
          or(eq(attendances.workDate, day), isNull(attendances.checkedOutAt)),
          lte(workPrompts.dueAt, now),
        ),
      )
      .orderBy(desc(workPrompts.dueAt));
    const latest = new Map<string, typeof workPrompts.$inferSelect>();
    for (const row of prompts)
      if (!latest.has(row.worker)) latest.set(row.worker, row.prompt);
    const incoming = new Map(rows.map((row) => [row.userId, row.peer]));
    return {
      workers: rows.map(
        ({
          shift,
          device,
          peer,
          userId: worker,
          name,
          profileOccupation,
          profileZone,
        }) => {
          const prompt = latest.get(worker);
          const reverse = incoming.get(shift?.buddyUserId ?? '');
          const evidence =
            shift?.buddyUserId === site.foremanUserId
              ? [peer]
              : [peer, reverse?.checkerUserId === worker ? reverse : null];
          return {
            userId: worker,
            name,
            occupation: shift?.occupation ?? profileOccupation,
            zone: shift?.zone ?? profileZone,
            attendanceId: shift?.id ?? null,
            checkedInAt: shift?.checkedInAt ?? null,
            checkedOutAt: shift?.checkedOutAt ?? null,
            buddyUserId: shift?.buddyUserId ?? null,
            highRiskTask: shift?.highRiskTask ?? null,
            device:
              shift && !shift.checkedOutAt ? deviceStatus(device, now) : null,
            locationStatus: shift?.locationStatus ?? null,
            peerStatus: evidence.some((check) => check?.outcome === 'mismatch')
              ? 'mismatch'
              : evidence.some((check) => !check)
                ? 'pending'
                : evidence.every((check) => check?.photoId)
                  ? 'photo_confirmed'
                  : 'statement_only',
            checkInStatus: prompt
              ? promptState(prompt, now, shift?.checkedOutAt ?? null)
              : null,
          };
        },
      ),
    };
  }

  async reviews(siteId: string, userId: string) {
    const db = this.database.db;
    await requireSiteForeman(db, siteId, userId);
    return {
      requests: await db
        .select({
          attendanceId: safetyReviews.attendanceId,
          userId: attendances.userId,
          name: users.name,
          workDate: attendances.workDate,
          zone: attendances.zone,
          fallHazard: attendances.fallHazard,
          floorAndGuardrail: attendances.floorAndGuardrail,
          protectiveEquipment: attendances.protectiveEquipment,
          requestedAt: safetyReviews.requestedAt,
          acknowledgedAt: safetyReviews.acknowledgedAt,
        })
        .from(safetyReviews)
        .innerJoin(attendances, eq(attendances.id, safetyReviews.attendanceId))
        .innerJoin(users, eq(users.id, attendances.userId))
        .where(
          and(eq(attendances.siteId, siteId), isNull(safetyReviews.resolvedAt)),
        )
        .orderBy(desc(safetyReviews.requestedAt)),
    };
  }

  async acknowledge(
    siteId: string,
    userId: string,
    id: string,
    expectedRequestedAt: Date,
  ) {
    return this.database.db.transaction(async (tx) => {
      await requireSiteForeman(tx, siteId, userId);
      // Take the same first lock as a worker editing this checklist.
      await tx
        .select({ id: attendances.id })
        .from(attendances)
        .where(and(eq(attendances.id, id), eq(attendances.siteId, siteId)))
        .for('no key update');
      const [row] = await tx
        .select({ review: safetyReviews, worker: attendances.userId })
        .from(safetyReviews)
        .innerJoin(attendances, eq(attendances.id, safetyReviews.attendanceId))
        .where(
          and(
            eq(attendances.siteId, siteId),
            eq(attendances.id, id),
            isNull(safetyReviews.resolvedAt),
          ),
        )
        .for('no key update');
      if (!row) throw new NotFoundException();
      if (row.review.requestedAt.valueOf() !== expectedRequestedAt.valueOf())
        conflict(
          'SAFETY_REVIEW_CHANGED',
          '점검 내용이 바뀌었어요. 새 항목을 확인해 주세요.',
        );
      if (row.review.acknowledgedAt) return { request: row.review };
      const [request] = await tx
        .update(safetyReviews)
        .set({ acknowledgedAt: new Date() })
        .where(eq(safetyReviews.attendanceId, id))
        .returning();
      await appendEvent(tx, {
        siteId,
        entityId: id,
        actorId: userId,
        subjectId: row.worker,
        type: 'safety_check.reviewed',
        source: 'human',
      });
      return { request };
    });
  }
}
