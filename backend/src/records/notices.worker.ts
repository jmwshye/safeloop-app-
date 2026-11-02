import {
  Injectable,
  Logger,
  type BeforeApplicationShutdown,
  type OnApplicationBootstrap,
} from '@nestjs/common';
import { and, asc, eq, inArray, isNull, sql } from 'drizzle-orm';
import { DatabaseService } from '../database.service.js';
import { siteNotices } from '../database/record-schema.js';
import { riskReports } from '../database/risk-schema.js';
import {
  attendances,
  safetyReviews,
  sites,
  siteMemberships,
  workPrompts,
} from '../database/schema.js';
import {
  pushDevices,
  notificationPreferences,
} from '../database/incident-schema.js';
import { PushSender } from '../notifications/push-sender.js';

@Injectable()
export class NoticesWorker
  implements OnApplicationBootstrap, BeforeApplicationShutdown
{
  private timer?: NodeJS.Timeout;
  private work?: Promise<void>;
  private readonly logger = new Logger(NoticesWorker.name);
  constructor(
    private readonly database: DatabaseService,
    private readonly sender: PushSender,
  ) {}

  onApplicationBootstrap() {
    this.timer = setInterval(() => {
      this.work ??= this.deliver()
        .catch(() => {
          this.logger.error('현장 알림 처리 실패. 다음 주기에 재시도합니다.');
        })
        .finally(() => {
          this.work = undefined;
        });
    }, 2000);
    this.timer.unref();
  }
  async beforeApplicationShutdown() {
    clearInterval(this.timer);
    await this.work;
  }

  async deliver() {
    const db = this.database.db;
    // A lease permits another process to retry after a crash, without holding a transaction during FCM I/O.
    for (let i = 0; i < 20; i++) {
      const notice = await db.transaction(async (tx) => {
        const [item] = await tx
          .select()
          .from(siteNotices)
          .where(
            and(
              inArray(siteNotices.status, ['queued', 'sending']),
              sql`${siteNotices.nextAttemptAt} <= now()`,
            ),
          )
          .orderBy(asc(siteNotices.nextAttemptAt))
          .limit(1)
          .for('update', { skipLocked: true });
        if (!item) return;
        const [claimed] = await tx
          .update(siteNotices)
          .set({
            status: 'sending',
            attempts: item.attempts + 1,
            nextAttemptAt: sql`now() + interval '60 seconds'`,
          })
          .where(eq(siteNotices.id, item.id))
          .returning();
        return claimed;
      });
      if (!notice) return;
      const status = await this.send(notice).catch(() => {
        this.logger.warn('현장 푸시를 전송하지 못했어요.');
        return this.retryStatus(notice.attempts);
      });
      await db
        .update(siteNotices)
        .set({
          status,
          nextAttemptAt: sql`now() + ${Math.min(300, 2 ** Math.min(notice.attempts, 9))} * interval '1 second'`,
        })
        .where(
          and(
            eq(siteNotices.id, notice.id),
            eq(siteNotices.status, 'sending'),
            eq(siteNotices.attempts, notice.attempts),
          ),
        );
    }
  }
  private retryStatus(attempts: number) {
    return attempts >= 5 ? 'failed' : 'queued';
  }

  private async send(
    item: typeof siteNotices.$inferSelect,
  ): Promise<typeof item.status> {
    const db = this.database.db;
    const [member] = await db
      .select({ id: sites.id })
      .from(sites)
      .leftJoin(
        siteMemberships,
        and(
          eq(siteMemberships.siteId, sites.id),
          eq(siteMemberships.userId, item.recipientId),
        ),
      )
      .where(
        and(
          eq(sites.id, item.siteId),
          sql`(${sites.foremanUserId}=${item.recipientId} OR ${siteMemberships.userId} IS NOT NULL)`,
        ),
      );
    if (!member) return 'cancelled';
    if (item.kind === 'work_prompt') {
      const [due] = await db
        .select({ id: workPrompts.id })
        .from(workPrompts)
        .innerJoin(attendances, eq(attendances.id, workPrompts.attendanceId))
        .where(
          and(
            eq(workPrompts.id, item.entityId),
            eq(attendances.userId, item.recipientId),
            isNull(attendances.checkedOutAt),
            isNull(workPrompts.answeredAt),
            sql`${workPrompts.expiresAt}>now()`,
          ),
        );
      if (!due) return 'cancelled';
    } else if (item.kind === 'safety_review') {
      const [review] = await db
        .select({ id: safetyReviews.attendanceId })
        .from(safetyReviews)
        .where(
          and(
            eq(safetyReviews.attendanceId, item.entityId),
            isNull(safetyReviews.resolvedAt),
            isNull(safetyReviews.acknowledgedAt),
          ),
        );
      if (!review) return 'cancelled';
    } else if (item.kind === 'high_risk' || item.kind === 'high_risk_end') {
      const [shift] = await db
        .select({ id: attendances.id })
        .from(attendances)
        .where(
          and(
            eq(attendances.id, item.entityId),
            eq(attendances.siteId, item.siteId),
            eq(attendances.buddyUserId, item.recipientId),
            isNull(attendances.checkedOutAt),
            item.kind === 'high_risk'
              ? sql`${attendances.highRiskTask} IS NOT NULL`
              : isNull(attendances.highRiskTask),
          ),
        );
      if (!shift) return 'cancelled';
    } else if (item.kind === 'risk_action') {
      const [risk] = await db
        .select({ id: riskReports.id })
        .from(riskReports)
        .where(
          and(
            eq(riskReports.id, item.entityId),
            eq(riskReports.siteId, item.siteId),
            eq(riskReports.assigneeId, item.recipientId),
            eq(riskReports.status, 'open'),
          ),
        );
      if (!risk) return 'cancelled';
    }
    const [prefs] = await db
      .select()
      .from(notificationPreferences)
      .where(eq(notificationPreferences.userId, item.recipientId));
    if (prefs && !prefs.sitePush) return 'unavailable';
    const devices = await db
      .select({ token: pushDevices.fcmToken })
      .from(pushDevices)
      .where(eq(pushDevices.userId, item.recipientId));
    if (!devices.length || !this.sender.configured) return 'unavailable';
    const result = await this.sender.send(
      devices.map((d) => d.token),
      {
        eventId: item.id,
        recipientId: item.recipientId,
        type: item.kind,
        siteId: item.siteId,
        entityId: item.entityId,
      },
    );
    if (result.invalidTokens.length)
      await db
        .delete(pushDevices)
        .where(
          and(
            eq(pushDevices.userId, item.recipientId),
            inArray(pushDevices.fcmToken, result.invalidTokens),
          ),
        );
    if (result.accepted) return 'sent';
    return this.retryStatus(item.attempts);
  }
}
