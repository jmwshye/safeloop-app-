import { ForbiddenException, Injectable } from '@nestjs/common';
import { and, asc, desc, eq, isNull, ne, sql } from 'drizzle-orm';
import { DatabaseService } from '../database.service.js';
import { auditEvents } from '../database/record-schema.js';
import { incidents } from '../database/incident-schema.js';
import { riskReports } from '../database/risk-schema.js';
import { attendances, sites, users } from '../database/schema.js';
import { siteAccess } from './records.js';
import { peerSummary } from './peer-summary.js';

@Injectable()
export class DashboardService {
  constructor(private readonly database: DatabaseService) {}
  async read(siteId: string, userId: string) {
    return this.database.db.transaction(
      async (tx) => {
        if (!(await siteAccess(tx, siteId, userId)).isForeman)
          throw new ForbiddenException();
        const [site] = await tx
          .select({
            id: sites.id,
            name: sites.name,
            code: sites.code,
            foremanName: users.name,
          })
          .from(sites)
          .innerJoin(users, eq(users.id, sites.foremanUserId))
          .where(eq(sites.id, siteId));
        const counts = await tx.execute<{
          work_date: string;
          members: number;
          attended: number;
          open_risks: number;
          unverified: number;
          pending_reviews: number;
        }>(sql`
        WITH today AS (
          SELECT * FROM attendances WHERE site_id=${siteId}
          AND work_date=(now() AT TIME ZONE 'Asia/Seoul')::date
        ), unverified AS (
          SELECT worker_id FROM incidents WHERE site_id=${siteId} AND status IN ('calling','unanswered','responding')
          UNION
          SELECT a.user_id FROM today a
          CROSS JOIN LATERAL (SELECT answered_at,expires_at FROM work_prompts
            WHERE attendance_id=a.id AND due_at<=now() ORDER BY due_at DESC LIMIT 1) p
          WHERE a.checked_out_at IS NULL AND p.answered_at IS NULL AND p.expires_at<=now()
        )
        SELECT ((now() AT TIME ZONE 'Asia/Seoul')::date)::text AS work_date,
          (SELECT count(*) FROM site_memberships WHERE site_id=${siteId} AND user_id<>${userId})::int AS members,
          (SELECT count(*) FROM today WHERE user_id<>${userId})::int AS attended,
          (SELECT count(*) FROM risk_reports WHERE site_id=${siteId} AND status='open')::int AS open_risks,
          (SELECT count(*) FROM unverified)::int AS unverified,
          (SELECT count(*) FROM safety_reviews r JOIN attendances a ON a.id=r.attendance_id WHERE a.site_id=${siteId} AND r.resolved_at IS NULL AND r.acknowledged_at IS NULL)::int AS pending_reviews
      `);
        const totals = counts.rows[0];
        const today = await tx
          .select({
            id: attendances.id,
            userId: attendances.userId,
            siteId: attendances.siteId,
            workDate: attendances.workDate,
          })
          .from(attendances)
          .where(
            and(
              eq(attendances.siteId, siteId),
              eq(attendances.workDate, totals.work_date),
              ne(attendances.userId, userId),
            ),
          );
        // Daily completion stays counted after checkout or a new buddy assignment.
        const confirmations = [
          ...(
            await peerSummary(
              tx,
              today.map((shift) => ({ ...shift, foremanId: userId })),
            )
          ).values(),
        ];
        const activeCalls = await tx
          .select({
            id: incidents.id,
            workerId: incidents.workerId,
            workerName: users.name,
            zone: incidents.zone,
            status: incidents.status,
            responderId: incidents.responderId,
            startedAt: incidents.createdAt,
            acceptedAt: incidents.acceptedAt,
          })
          .from(incidents)
          .innerJoin(users, eq(users.id, incidents.workerId))
          .where(and(eq(incidents.siteId, siteId), isNull(incidents.closedAt)))
          .orderBy(asc(incidents.createdAt));
        const oldestRisks = await tx
          .select({
            id: riskReports.id,
            title: riskReports.title,
            zone: riskReports.zone,
            category: riskReports.category,
            createdAt: riskReports.createdAt,
          })
          .from(riskReports)
          .where(
            and(eq(riskReports.siteId, siteId), eq(riskReports.status, 'open')),
          )
          .orderBy(asc(riskReports.createdAt))
          .limit(5);
        const timeline = await tx
          .select({
            id: auditEvents.id,
            type: auditEvents.type,
            source: auditEvents.source,
            recordedAt: auditEvents.recordedAt,
            entityId: auditEvents.entityId,
          })
          .from(auditEvents)
          .where(
            and(
              eq(auditEvents.siteId, siteId),
              sql`${auditEvents.recordedAt} >= date_trunc('day',now() AT TIME ZONE 'Asia/Seoul') AT TIME ZONE 'Asia/Seoul'`,
            ),
          )
          .orderBy(desc(auditEvents.seq))
          .limit(10);
        return {
          site,
          workDate: totals.work_date,
          counts: {
            members: totals.members,
            attended: totals.attended,
            crossChecked: confirmations.filter((row) => row.mutuallyChecked)
              .length,
            photoConfirmed: confirmations.filter((row) => row.photoConfirmed)
              .length,
            openRisks: totals.open_risks,
            unverified: totals.unverified,
            pendingSafetyReviews: totals.pending_reviews,
          },
          activeCalls,
          oldestRisks,
          timeline,
        };
      },
      { isolationLevel: 'repeatable read', accessMode: 'read only' },
    );
  }
}
