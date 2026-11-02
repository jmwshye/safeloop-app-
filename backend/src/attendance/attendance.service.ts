import {
  ConflictException,
  Injectable,
  NotFoundException,
} from '@nestjs/common';
import { and, desc, eq, gte, isNull, lte } from 'drizzle-orm';
import { DatabaseService } from '../database.service.js';
import {
  attendances,
  safetyReviews,
  shiftDevices,
  siteMemberships,
  sites,
} from '../database/schema.js';
import { appendEvent } from '../records/records.js';
import { enqueueNotice } from '../records/notices.js';
import { requireSiteMember } from '../sites/site-access.js';
import type {
  CheckInInput,
  Fix,
  SafetyCheckInput,
} from './attendance.schemas.js';
import {
  autoPair,
  conflict,
  joinedProfile,
  lockSite,
  schedulePrompts,
} from './shift-operations.js';
import { locationAt, workDateAt } from './work-time.js';

type Attendance = typeof attendances.$inferSelect;

function responseFor(record: Attendance) {
  const safetyCheck =
    record.safetyCheckUpdatedAt === null
      ? null
      : {
          fallHazard: record.fallHazard!,
          floorAndGuardrail: record.floorAndGuardrail!,
          protectiveEquipment: record.protectiveEquipment!,
          result:
            record.fallHazard === 'confirmed' &&
            record.floorAndGuardrail === 'confirmed' &&
            record.protectiveEquipment === 'confirmed'
              ? ('all_confirmed' as const)
              : ('needs_attention' as const),
          updatedAt: record.safetyCheckUpdatedAt.toISOString(),
        };
  return {
    id: record.id,
    workDate: record.workDate,
    checkedInAt: record.checkedInAt.toISOString(),
    checkedOutAt: record.checkedOutAt?.toISOString() ?? null,
    siteId: record.siteId,
    occupation: record.occupation,
    zone: record.zone,
    expectedWage: record.expectedWage,
    locationStatus: record.locationStatus,
    buddyUserId: record.buddyUserId,
    highRiskTask: record.highRiskTask,
    highRiskStartedAt: record.highRiskStartedAt?.toISOString() ?? null,
    safetyCheck,
  };
}

@Injectable()
export class AttendanceService {
  constructor(private readonly database: DatabaseService) {}

  private now(): Date {
    return new Date();
  }

  async checkIn(userId: string, input: CheckInInput) {
    const checkedInAt = this.now();
    const workDate = workDateAt(checkedInAt);
    return this.database.db.transaction(async (tx) => {
      let siteId = input.siteId;
      if (!siteId) {
        const memberships = await tx
          .select({ id: siteMemberships.siteId })
          .from(siteMemberships)
          .where(eq(siteMemberships.userId, userId))
          .limit(2);
        if (memberships.length !== 1)
          conflict('SITE_SELECTION_REQUIRED', '참여한 현장을 선택해 주세요.');
        siteId = memberships[0].id;
      }
      await requireSiteMember(tx, siteId, userId);
      const site = await lockSite(tx, siteId);
      const profile = await joinedProfile(tx, siteId, userId);
      const [created] = await tx
        .insert(attendances)
        .values({
          userId,
          siteId,
          workDate,
          checkedInAt,
          occupation: profile.occupation,
          zone: profile.zone,
          expectedWage: profile.expectedWage,
          locationStatus: locationAt(site, input.location, checkedInAt),
        })
        .onConflictDoNothing({
          target: [attendances.userId, attendances.workDate],
        })
        .returning();
      if (!created) {
        const [existing] = await tx
          .select()
          .from(attendances)
          .where(
            and(
              eq(attendances.userId, userId),
              eq(attendances.workDate, workDate),
            ),
          )
          .for('no key update');
        if (existing?.siteId === null) {
          if (!input.siteId || existing.checkedOutAt)
            conflict(
              'SITE_SELECTION_REQUIRED',
              '기존 출근 기록에 연결할 현장을 선택해 주세요.',
            );
          const [linked] = await tx
            .update(attendances)
            .set({
              siteId,
              occupation: profile.occupation,
              zone: profile.zone,
              expectedWage: profile.expectedWage,
              locationStatus: 'unverified',
            })
            .where(eq(attendances.id, existing.id))
            .returning();
          const paired = await autoPair(tx, linked, site);
          await schedulePrompts(tx, site, paired, checkedInAt);
          await appendEvent(tx, {
            siteId,
            entityId: linked.id,
            actorId: userId,
            subjectId: userId,
            type: 'attendance.site_linked',
            source: 'human',
          });
          if (
            [
              linked.fallHazard,
              linked.floorAndGuardrail,
              linked.protectiveEquipment,
            ].includes('unknown')
          ) {
            await tx
              .insert(safetyReviews)
              .values({ attendanceId: linked.id, requestedAt: checkedInAt });
            await enqueueNotice(tx, {
              siteId,
              recipientId: site.foremanUserId,
              kind: 'safety_review',
              entityId: linked.id,
              uniqueKey: `review:linked:${linked.id}`,
            });
          }
          return { created: false, attendance: responseFor(paired) };
        }
        if (!existing || existing.siteId !== siteId)
          conflict(
            'ATTENDANCE_ALREADY_AT_OTHER_SITE',
            '오늘은 다른 현장의 출근 기록이 있어요.',
          );
        return { created: false, attendance: responseFor(existing) };
      }
      const paired = await autoPair(tx, created, site);
      await schedulePrompts(tx, site, paired);
      await appendEvent(tx, {
        siteId,
        actorId: userId,
        subjectId: userId,
        entityId: created.id,
        type: 'attendance.check_in',
        source: 'human',
        details: { locationStatus: created.locationStatus },
      });
      return { created: true, attendance: responseFor(paired) };
    });
  }

  async today(userId: string, siteId?: string) {
    if (siteId) await requireSiteMember(this.database.db, siteId, userId);
    const workDate = workDateAt(this.now());
    const [record] = await this.database.db
      .select()
      .from(attendances)
      .where(
        and(
          eq(attendances.userId, userId),
          eq(attendances.workDate, workDate),
          siteId ? eq(attendances.siteId, siteId) : undefined,
        ),
      )
      .limit(1);
    return { attendance: record ? responseFor(record) : null };
  }

  async verifyLocation(
    userId: string,
    siteId: string,
    location: Fix | null | undefined,
  ) {
    const site = await requireSiteMember(this.database.db, siteId, userId);
    return { locationStatus: locationAt(site, location, this.now()) };
  }

  async records(userId: string, from: string, to: string) {
    const rows = await this.database.db
      .select()
      .from(attendances)
      .where(
        and(
          eq(attendances.userId, userId),
          gte(attendances.workDate, from),
          lte(attendances.workDate, to),
        ),
      )
      .orderBy(desc(attendances.workDate));
    return { attendances: rows.map(responseFor) };
  }

  async checkout(userId: string, id: string) {
    return this.database.db.transaction(async (tx) => {
      const owned = and(eq(attendances.userId, userId), eq(attendances.id, id));
      const [summary] = await tx
        .select({ siteId: attendances.siteId })
        .from(attendances)
        .where(owned);
      if (!summary) throw new NotFoundException();
      const site = summary.siteId ? await lockSite(tx, summary.siteId) : null;
      const [record] = await tx
        .select()
        .from(attendances)
        .where(owned)
        .for('no key update');
      if (!record) throw new NotFoundException();
      if (record.checkedOutAt) return { attendance: responseFor(record) };
      const now = this.now();
      const [updated] = await tx
        .update(attendances)
        .set({ checkedOutAt: now, highRiskTask: null, highRiskStartedAt: null })
        .where(owned)
        .returning();
      await tx.delete(shiftDevices).where(eq(shiftDevices.attendanceId, id));
      if (site) {
        // Reassign an active peer; retain the departing worker's historical buddy.
        const [remaining] = await tx
          .select()
          .from(attendances)
          .where(
            and(
              eq(attendances.siteId, site.id),
              eq(attendances.workDate, record.workDate),
              eq(attendances.buddyUserId, userId),
              isNull(attendances.checkedOutAt),
            ),
          );
        if (remaining && userId !== site.foremanUserId)
          await autoPair(tx, remaining, site);
        await appendEvent(tx, {
          siteId: site.id,
          actorId: userId,
          subjectId: userId,
          entityId: id,
          type: 'attendance.check_out',
          source: 'human',
        });
      }
      return { attendance: responseFor(updated) };
    });
  }

  async saveSafetyCheck(userId: string, id: string, input: SafetyCheckInput) {
    return this.database.db.transaction(async (tx) => {
      // 같은 기록의 동시 저장을 순서대로 처리해 중복 요청의 수정 시각도 유지한다.
      const [record] = await tx
        .select()
        .from(attendances)
        .where(and(eq(attendances.id, id), eq(attendances.userId, userId)))
        .for('no key update');
      if (!record) throw new NotFoundException();
      const now = this.now();
      if (record.workDate !== workDateAt(now)) throw new ConflictException();
      if (record.checkedOutAt)
        conflict('SHIFT_ENDED', '이미 퇴근한 기록이에요.');
      if (!record.siteId)
        conflict(
          'SITE_SELECTION_REQUIRED',
          '먼저 기존 출근 기록에 현장을 연결해 주세요.',
        );
      if (
        record.fallHazard === input.fallHazard &&
        record.floorAndGuardrail === input.floorAndGuardrail &&
        record.protectiveEquipment === input.protectiveEquipment
      ) {
        return { attendance: responseFor(record) };
      }
      const [updated] = await tx
        .update(attendances)
        .set({ ...input, safetyCheckUpdatedAt: now })
        .where(eq(attendances.id, record.id))
        .returning();
      const hadUnknown = [
        record.fallHazard,
        record.floorAndGuardrail,
        record.protectiveEquipment,
      ].includes('unknown');
      const hasUnknown = Object.values(input).includes('unknown');
      const newUnknown = (
        Object.keys(input) as (keyof SafetyCheckInput)[]
      ).some((key) => input[key] === 'unknown' && record[key] !== 'unknown');
      const event = await appendEvent(tx, {
        siteId: record.siteId,
        actorId: userId,
        subjectId: userId,
        entityId: id,
        type: record.safetyCheckUpdatedAt
          ? 'safety_check.corrected'
          : 'safety_check.saved',
        source: 'human',
        details: { ...input },
      });
      if (newUnknown) {
        await tx
          .insert(safetyReviews)
          .values({ attendanceId: id, requestedAt: now })
          .onConflictDoUpdate({
            target: safetyReviews.attendanceId,
            set: { requestedAt: now, acknowledgedAt: null, resolvedAt: null },
          });
        const [site] = await tx
          .select({ foreman: sites.foremanUserId })
          .from(sites)
          .where(eq(sites.id, record.siteId));
        await enqueueNotice(tx, {
          siteId: record.siteId,
          recipientId: site.foreman,
          entityId: id,
          kind: 'safety_review',
          uniqueKey: `review:${event.id}:${site.foreman}`,
        });
      } else if (hadUnknown && !hasUnknown) {
        await tx
          .update(safetyReviews)
          .set({ resolvedAt: now })
          .where(eq(safetyReviews.attendanceId, id));
      }
      return { attendance: responseFor(updated) };
    });
  }
}
