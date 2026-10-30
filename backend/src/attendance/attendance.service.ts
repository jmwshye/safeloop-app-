import {
  ConflictException,
  Injectable,
  NotFoundException,
} from '@nestjs/common';
import { and, eq } from 'drizzle-orm';
import { DatabaseService } from '../database.service.js';
import { attendances } from '../database/schema.js';
import type { SafetyCheckInput } from './attendance.schemas.js';

type Attendance = typeof attendances.$inferSelect;
const seoulDateFormatter = new Intl.DateTimeFormat('en', {
  timeZone: 'Asia/Seoul',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
});

function workDateAt(time: Date): string {
  const parts = Object.fromEntries(
    seoulDateFormatter
      .formatToParts(time)
      .map(({ type, value }) => [type, value]),
  );
  return `${parts.year}-${parts.month}-${parts.day}`;
}

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
    safetyCheck,
  };
}

@Injectable()
export class AttendanceService {
  constructor(private readonly database: DatabaseService) {}

  private now(): Date {
    return new Date();
  }

  async checkIn(userId: string) {
    const checkedInAt = this.now();
    const workDate = workDateAt(checkedInAt);
    const [created] = await this.database.db
      .insert(attendances)
      .values({ userId, workDate, checkedInAt })
      .onConflictDoNothing({
        target: [attendances.userId, attendances.workDate],
      })
      .returning();
    if (created) return { created: true, attendance: responseFor(created) };

    // 중복 요청이 자정을 지나 끝나더라도 처음 요청을 처리한 날짜로 조회한다.
    const [existing] = await this.database.db
      .select()
      .from(attendances)
      .where(
        and(eq(attendances.userId, userId), eq(attendances.workDate, workDate)),
      )
      .limit(1);
    if (!existing) throw new Error('출근 기록을 다시 조회할 수 없습니다.');
    return { created: false, attendance: responseFor(existing) };
  }

  async today(userId: string) {
    const workDate = workDateAt(this.now());
    const [record] = await this.database.db
      .select()
      .from(attendances)
      .where(
        and(eq(attendances.userId, userId), eq(attendances.workDate, workDate)),
      )
      .limit(1);
    return { attendance: record ? responseFor(record) : null };
  }

  async saveSafetyCheck(userId: string, id: string, input: SafetyCheckInput) {
    return this.database.db.transaction(async (tx) => {
      // 같은 기록의 동시 저장을 순서대로 처리해 중복 요청의 수정 시각도 유지한다.
      const [record] = await tx
        .select()
        .from(attendances)
        .where(and(eq(attendances.id, id), eq(attendances.userId, userId)))
        .for('update');
      if (!record) throw new NotFoundException();
      const now = this.now();
      if (record.workDate !== workDateAt(now)) throw new ConflictException();
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
      return { attendance: responseFor(updated) };
    });
  }
}
