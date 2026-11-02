import {
  ConflictException,
  ForbiddenException,
  Injectable,
  NotFoundException,
} from '@nestjs/common';
import {
  and,
  asc,
  desc,
  eq,
  gt,
  gte,
  getTableColumns,
  lt,
  or,
  sql,
} from 'drizzle-orm';
import { DatabaseService } from '../database.service.js';
import {
  auditEvents,
  auditHeads,
  recordCorrections,
} from '../database/record-schema.js';
import { appendEvent, eventHash, siteAccess } from './records.js';
import { riskReports } from '../database/risk-schema.js';

// The private owner link lives in the report, never in the shared timeline.
function personalEvent(userId: string) {
  return or(
    eq(auditEvents.actorId, userId),
    eq(auditEvents.subjectId, userId),
    sql`((${auditEvents.type} = 'risk.reported' OR ${auditEvents.type} LIKE 'correction.%')
      AND EXISTS (SELECT 1 FROM ${riskReports} WHERE ${riskReports.siteId} = ${auditEvents.siteId}
      AND ${riskReports.id} = ${auditEvents.entityId} AND ${riskReports.anonymous}
      AND ${riskReports.reporterId} = ${userId}))`,
  );
}

function anonymousReport(event: typeof auditEvents.$inferSelect) {
  return event.type === 'risk.reported' && event.actorId === null;
}

function reviewedCorrection(
  row: typeof recordCorrections.$inferSelect,
  anonymous: boolean,
) {
  return anonymous ? { ...row, requesterId: null, requestId: null } : row;
}

@Injectable()
export class RecordsService {
  constructor(private readonly database: DatabaseService) {}

  async timeline(
    siteId: string,
    userId: string,
    query: {
      day?: string;
      before?: number;
      limit: number;
      scope?: 'self' | 'site';
    },
  ) {
    const { isForeman } = await siteAccess(this.database.db, siteId, userId);
    if (query.scope === 'site' && !isForeman) throw new ForbiddenException();
    const own = !isForeman || query.scope === 'self';
    const start = query.day
      ? new Date(query.day + 'T00:00:00+09:00')
      : undefined;
    const items = await this.database.db
      .select({
        ...getTableColumns(auditEvents),
        canRequestCorrection: sql<boolean>`coalesce(
          ${auditEvents.actorId} = ${userId} OR ${auditEvents.subjectId} = ${userId}
          OR (${auditEvents.type} = 'risk.reported' AND ${personalEvent(userId)}), false)`,
      })
      .from(auditEvents)
      .where(
        and(
          eq(auditEvents.siteId, siteId),
          own ? personalEvent(userId) : undefined,
          query.before ? lt(auditEvents.seq, query.before) : undefined,
          start ? gte(auditEvents.recordedAt, start) : undefined,
          start
            ? lt(auditEvents.recordedAt, new Date(start.getTime() + 86_400_000))
            : undefined,
        ),
      )
      .orderBy(desc(auditEvents.seq))
      .limit(query.limit + 1);
    const events = items.slice(0, query.limit);
    return {
      events,
      nextBefore: items.length > query.limit ? events.at(-1)!.seq : null,
    };
  }

  async verify(siteId: string, userId: string) {
    return this.database.db.transaction(
      async (tx) => {
        if (!(await siteAccess(tx, siteId, userId)).isForeman)
          throw new ForbiddenException();
        let seq = 0;
        let hash: string | null = null;
        for (;;) {
          const batch = await tx
            .select()
            .from(auditEvents)
            .where(
              and(
                eq(auditEvents.siteId, siteId),
                seq > 0 ? gt(auditEvents.seq, seq) : undefined,
              ),
            )
            .orderBy(asc(auditEvents.seq))
            .limit(500);
          for (const item of batch) {
            if (
              item.seq !== seq + 1 ||
              item.previousHash !== hash ||
              item.hash !== eventHash(item)
            )
              return { valid: false, checked: seq, firstInvalidSeq: seq + 1 };
            seq = item.seq;
            hash = item.hash;
          }
          if (batch.length < 500) break;
        }
        const [head] = await tx
          .select()
          .from(auditHeads)
          .where(eq(auditHeads.siteId, siteId));
        const valid = (head?.seq ?? 0) === seq && (head?.hash ?? null) === hash;
        return { valid, checked: seq, firstInvalidSeq: valid ? null : seq + 1 };
      },
      { isolationLevel: 'repeatable read', accessMode: 'read only' },
    );
  }

  async requestCorrection(
    siteId: string,
    userId: string,
    eventId: string,
    requestId: string,
    note: string,
  ) {
    return this.database.db.transaction(async (tx) => {
      await siteAccess(tx, siteId, userId);
      const [event] = await tx
        .select()
        .from(auditEvents)
        .where(
          and(
            eq(auditEvents.id, eventId),
            eq(auditEvents.siteId, siteId),
            personalEvent(userId),
          ),
        );
      if (
        !event ||
        (event.subjectId !== userId &&
          event.actorId !== userId &&
          !anonymousReport(event))
      )
        throw new NotFoundException();
      const [created] = await tx
        .insert(recordCorrections)
        .values({ siteId, eventId, requestId, note, requesterId: userId })
        .onConflictDoNothing({
          target: [recordCorrections.requesterId, recordCorrections.requestId],
        })
        .returning();
      if (!created) {
        const [prior] = await tx
          .select()
          .from(recordCorrections)
          .where(
            and(
              eq(recordCorrections.requesterId, userId),
              eq(recordCorrections.requestId, requestId),
            ),
          );
        if (!prior || prior.eventId !== eventId || prior.note !== note)
          throw new ConflictException();
        return prior;
      }
      await appendEvent(tx, {
        siteId,
        actorId: anonymousReport(event) ? null : userId,
        subjectId: anonymousReport(event) ? null : (event.subjectId ?? userId),
        entityId: event.entityId,
        correctsId: eventId,
        type: 'correction.requested',
        source: 'human',
        details: { correctionId: created.id, note },
      });
      return created;
    });
  }

  async corrections(siteId: string, userId: string) {
    const { isForeman } = await siteAccess(this.database.db, siteId, userId);
    const rows = await this.database.db
      .select({ correction: recordCorrections, original: auditEvents })
      .from(recordCorrections)
      .innerJoin(auditEvents, eq(auditEvents.id, recordCorrections.eventId))
      .where(
        and(
          eq(recordCorrections.siteId, siteId),
          isForeman ? undefined : eq(recordCorrections.requesterId, userId),
        ),
      )
      .orderBy(desc(recordCorrections.createdAt));
    return rows.map(({ correction, original }) =>
      reviewedCorrection(
        correction,
        anonymousReport(original) && correction.requesterId !== userId,
      ),
    );
  }

  async review(
    siteId: string,
    userId: string,
    id: string,
    status: 'accepted' | 'declined',
    note: string,
  ) {
    return this.database.db.transaction(async (tx) => {
      if (!(await siteAccess(tx, siteId, userId)).isForeman)
        throw new ForbiddenException();
      const [item] = await tx
        .select()
        .from(recordCorrections)
        .where(
          and(
            eq(recordCorrections.siteId, siteId),
            eq(recordCorrections.id, id),
          ),
        )
        .for('update');
      if (!item) throw new NotFoundException();
      const [event] = await tx
        .select()
        .from(auditEvents)
        .where(eq(auditEvents.id, item.eventId));
      const anonymous = anonymousReport(event);
      if (item.status !== 'pending') {
        if (item.status === status && item.reviewNote === note)
          return reviewedCorrection(item, anonymous);
        throw new ConflictException();
      }
      const [updated] = await tx
        .update(recordCorrections)
        .set({
          status,
          reviewerId: userId,
          reviewNote: note,
          reviewedAt: new Date(),
        })
        .where(eq(recordCorrections.id, id))
        .returning();
      await appendEvent(tx, {
        siteId,
        actorId: userId,
        subjectId: anonymous ? null : item.requesterId,
        entityId: event.entityId,
        correctsId: event.id,
        type: 'correction.' + status,
        source: 'human',
        details: { correctionId: id, note },
      });
      return reviewedCorrection(updated, anonymous);
    });
  }
}
