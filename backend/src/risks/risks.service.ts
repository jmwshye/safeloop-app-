import {
  ConflictException,
  ForbiddenException,
  Injectable,
  NotFoundException,
} from '@nestjs/common';
import { and, asc, eq, sql } from 'drizzle-orm';
import { alias } from 'drizzle-orm/pg-core';
import { DatabaseService } from '../database.service.js';
import { users } from '../database/schema.js';
import { riskReports as risks } from '../database/risk-schema.js';
import { appendEvent, bindPhoto } from '../records/records.js';
import { enqueueNotice } from '../records/notices.js';
import { requireSiteForeman, requireSiteMember } from '../sites/site-access.js';
import type { RiskInput, RiskQuery } from './risk.schemas.js';

type Reader = Pick<DatabaseService['db'], 'select'>;

const assignee = alias(users, 'risk_assignee');
const fixer = alias(users, 'risk_fixer');
const verifier = alias(users, 'risk_verifier');

const publicFields = {
  id: risks.id,
  title: risks.title,
  zone: risks.zone,
  category: risks.category,
  note: risks.note,
  status: risks.status,
  anonymous: risks.anonymous,
  beforePhotoId: risks.beforePhotoId,
  afterPhotoId: risks.afterPhotoId,
  createdAt: risks.createdAt,
  requestedAt: risks.requestedAt,
  assigneeId: risks.assigneeId,
  assigneeName: assignee.name,
  fixerId: risks.fixerId,
  fixerName: fixer.name,
  fixedAt: risks.fixedAt,
  verifierId: risks.verifierId,
  verifierName: verifier.name,
  verifiedAt: risks.verifiedAt,
  reporterName: sql<
    string | null
  >`CASE WHEN ${risks.anonymous} THEN NULL ELSE ${users.name} END`,
};

function readPublic(db: Reader, viewerId: string, hideReporter = false) {
  return db
    .select({
      ...publicFields,
      canRecheck: sql<boolean>`${risks.status} = 'awaiting_recheck'
        AND ${risks.reporterId} <> ${viewerId}
        AND ${risks.fixerId} IS DISTINCT FROM ${viewerId}::uuid`,
      reporterName: hideReporter ? sql<null>`NULL` : publicFields.reporterName,
    })
    .from(risks)
    .innerJoin(users, eq(users.id, risks.reporterId))
    .leftJoin(assignee, eq(assignee.id, risks.assigneeId))
    .leftJoin(fixer, eq(fixer.id, risks.fixerId))
    .leftJoin(verifier, eq(verifier.id, risks.verifierId));
}
function atSite(siteId: string, id: string) {
  return and(eq(risks.siteId, siteId), eq(risks.id, id));
}
async function riskView(
  db: Reader,
  siteId: string,
  id: string,
  viewerId: string,
) {
  const [risk] = await readPublic(db, viewerId).where(atSite(siteId, id));
  if (!risk) throw new NotFoundException();
  return { risk };
}
async function lockRisk(db: Reader, siteId: string, id: string) {
  const [risk] = await db
    .select()
    .from(risks)
    .where(atSite(siteId, id))
    .for('update');
  if (!risk) throw new NotFoundException();
  return risk;
}

@Injectable()
export class RisksService {
  constructor(private readonly database: DatabaseService) {}

  async list(userId: string, siteId: string, query: RiskQuery) {
    const db = this.database.db;
    await requireSiteMember(db, siteId, userId);
    const conditions = and(
      eq(risks.siteId, siteId),
      query.status ? eq(risks.status, query.status) : undefined,
    );
    // The site-wide list never displays a reporter, even when the report was signed.
    const rows = await readPublic(db, userId, true)
      .where(conditions)
      .orderBy(asc(risks.status), asc(risks.createdAt), asc(risks.id))
      .limit(query.limit + 1)
      .offset(query.offset);
    const groups = await db
      .select({ status: risks.status, count: sql<number>`count(*)::integer` })
      .from(risks)
      .where(eq(risks.siteId, siteId))
      .groupBy(risks.status);
    const counts = { open: 0, awaiting_recheck: 0, resolved: 0 };
    for (const row of groups) counts[row.status] = row.count;
    return {
      risks: rows.slice(0, query.limit),
      counts,
      nextOffset: rows.length > query.limit ? query.offset + query.limit : null,
    };
  }

  async detail(userId: string, siteId: string, id: string) {
    await requireSiteMember(this.database.db, siteId, userId);
    return riskView(this.database.db, siteId, id, userId);
  }

  async create(userId: string, siteId: string, input: RiskInput) {
    return this.database.db.transaction(async (tx) => {
      await requireSiteMember(tx, siteId, userId);
      const [inserted] = await tx
        .insert(risks)
        .values({ ...input, siteId, reporterId: userId })
        .onConflictDoNothing({
          target: [risks.siteId, risks.reporterId, risks.requestId],
        })
        .returning({ id: risks.id });
      if (!inserted) {
        const [existing] = await tx
          .select()
          .from(risks)
          .where(
            and(
              eq(risks.siteId, siteId),
              eq(risks.reporterId, userId),
              eq(risks.requestId, input.requestId),
            ),
          );
        if (
          !existing ||
          existing.anonymous !== input.anonymous ||
          existing.title !== input.title ||
          existing.zone !== input.zone ||
          existing.category !== input.category ||
          existing.note !== input.note ||
          existing.beforePhotoId !== input.beforePhotoId
        )
          throw new ConflictException();
        return {
          created: false,
          ...(await riskView(tx, siteId, existing.id, userId)),
        };
      }
      if (input.beforePhotoId)
        await bindPhoto(tx, {
          photoId: input.beforePhotoId,
          ownerId: userId,
          siteId,
          entityId: inserted.id,
          purpose: 'risk_before',
          shareWithSite: true,
        });
      await appendEvent(tx, {
        siteId,
        entityId: inserted.id,
        actorId: input.anonymous ? null : userId,
        source: 'human',
        type: 'risk.reported',
        details: { category: input.category, zone: input.zone },
      });
      return {
        created: true,
        ...(await riskView(tx, siteId, inserted.id, userId)),
      };
    });
  }

  async requestFix(
    userId: string,
    siteId: string,
    id: string,
    assigneeId: string,
  ) {
    return this.database.db.transaction(async (tx) => {
      await requireSiteForeman(tx, siteId, userId);
      const risk = await lockRisk(tx, siteId, id);
      if (risk.status !== 'open') throw new ConflictException();
      await requireSiteMember(tx, siteId, assigneeId);
      if (!risk.requestedAt || risk.assigneeId !== assigneeId) {
        const requestedAt = new Date();
        await tx
          .update(risks)
          .set({ requestedAt, assigneeId })
          .where(eq(risks.id, id));
        await appendEvent(tx, {
          siteId,
          entityId: id,
          actorId: userId,
          subjectId: assigneeId,
          source: 'human',
          type: 'risk.fix_requested',
        });
        await enqueueNotice(tx, {
          siteId,
          recipientId: assigneeId,
          kind: 'risk_action',
          entityId: id,
          uniqueKey: `risk:${id}:${assigneeId}:${requestedAt.toISOString()}`,
        });
      }
      return riskView(tx, siteId, id, userId);
    });
  }

  async fix(userId: string, siteId: string, id: string, photoId: string) {
    return this.database.db.transaction(async (tx) => {
      await requireSiteMember(tx, siteId, userId);
      const risk = await lockRisk(tx, siteId, id);
      if (risk.fixerId === userId && risk.afterPhotoId === photoId)
        return riskView(tx, siteId, id, userId);
      if (risk.status !== 'open') throw new ConflictException();
      await bindPhoto(tx, {
        photoId,
        ownerId: userId,
        siteId,
        entityId: id,
        purpose: 'risk_after',
        shareWithSite: true,
      });
      await tx
        .update(risks)
        .set({
          status: 'awaiting_recheck',
          fixedAt: new Date(),
          fixerId: userId,
          afterPhotoId: photoId,
        })
        .where(eq(risks.id, id));
      await appendEvent(tx, {
        siteId,
        entityId: id,
        actorId: userId,
        source: 'human',
        type: 'risk.fixed',
      });
      return riskView(tx, siteId, id, userId);
    });
  }

  async recheck(userId: string, siteId: string, id: string) {
    return this.database.db.transaction(async (tx) => {
      await requireSiteMember(tx, siteId, userId);
      const risk = await lockRisk(tx, siteId, id);
      if (risk.status === 'resolved' && risk.verifierId === userId)
        return riskView(tx, siteId, id, userId);
      if (risk.status !== 'awaiting_recheck') throw new ConflictException();
      if (risk.reporterId === userId || risk.fixerId === userId)
        throw new ForbiddenException();
      await tx
        .update(risks)
        .set({ status: 'resolved', verifierId: userId, verifiedAt: new Date() })
        .where(eq(risks.id, id));
      await appendEvent(tx, {
        siteId,
        entityId: id,
        actorId: userId,
        source: 'human',
        type: 'risk.rechecked',
      });
      return riskView(tx, siteId, id, userId);
    });
  }
}
