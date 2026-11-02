import {
  BadRequestException,
  ConflictException,
  NotFoundException,
} from '@nestjs/common';
import { createHash, randomUUID } from 'node:crypto';
import { and, eq, sql } from 'drizzle-orm';
import { z } from 'zod';
import type { DatabaseService } from '../database.service.js';
import {
  auditEvents,
  auditHeads,
  photos,
  type photoPurposes,
} from '../database/record-schema.js';
import { requireSiteMember } from '../sites/site-access.js';

export type Tx = Parameters<
  Parameters<DatabaseService['db']['transaction']>[0]
>[0];
export const uuidInput = z.uuid();
export function parseInput<T>(schema: z.ZodType<T>, value: unknown): T {
  const parsed = schema.safeParse(value);
  if (!parsed.success) throw new BadRequestException();
  return parsed.data;
}

export async function siteAccess(
  db: Pick<Tx, 'select'>,
  siteId: string,
  userId: string,
) {
  if (!uuidInput.safeParse(siteId).success) throw new NotFoundException();
  const site = await requireSiteMember(db, siteId, userId);
  return { isForeman: site.foremanUserId === userId };
}

type EventInput = {
  siteId: string;
  actorId?: string | null;
  subjectId?: string | null;
  entityId: string;
  type: string;
  source: 'human' | 'system';
  details?: Record<string, unknown>;
  correctsId?: string | null;
};

// JSONB does not preserve object key order. Verify identical facts using a stable ordering.
function canonical(value: unknown): string {
  if (Array.isArray(value)) return '[' + value.map(canonical).join(',') + ']';
  if (value !== null && typeof value === 'object') {
    const object = value as Record<string, unknown>;
    return (
      '{' +
      Object.keys(object)
        .sort()
        .map((key) => JSON.stringify(key) + ':' + canonical(object[key]))
        .join(',') +
      '}'
    );
  }
  return JSON.stringify(value);
}

type Evidence = Omit<typeof auditEvents.$inferSelect, 'hash'>;
export function eventHash(event: Evidence) {
  return createHash('sha256')
    .update(
      canonical({
        id: event.id,
        siteId: event.siteId,
        seq: event.seq,
        recordedAt: event.recordedAt.toISOString(),
        type: event.type,
        source: event.source,
        actorId: event.actorId,
        subjectId: event.subjectId,
        entityId: event.entityId,
        correctsId: event.correctsId,
        details: event.details,
        previousHash: event.previousHash,
      }),
    )
    .digest('hex');
}

export async function appendEvent(tx: Tx, input: EventInput) {
  // PostgreSQL canonicalizes UUIDs; hash and lock the same representation that is persisted.
  const siteId = input.siteId.toLowerCase();
  // Lock even when the chain is empty. Domain write and evidence must commit together.
  await tx.execute(
    sql`SELECT pg_advisory_xact_lock(hashtextextended(${siteId}::text, 0))`,
  );
  const [last] = await tx
    .select()
    .from(auditHeads)
    .where(eq(auditHeads.siteId, siteId));
  const event: Evidence = {
    id: randomUUID(),
    siteId,
    type: input.type,
    source: input.source,
    actorId: input.actorId?.toLowerCase() ?? null,
    subjectId: input.subjectId?.toLowerCase() ?? null,
    entityId: input.entityId.toLowerCase(),
    correctsId: input.correctsId?.toLowerCase() ?? null,
    details: JSON.parse(JSON.stringify(input.details ?? {})) as Record<
      string,
      unknown
    >,
    seq: (last?.seq ?? 0) + 1,
    recordedAt: new Date(),
    previousHash: last?.hash ?? null,
  };
  const hash = eventHash(event);
  await tx.insert(auditEvents).values({ ...event, hash });
  await tx
    .insert(auditHeads)
    .values({ siteId: input.siteId, seq: event.seq, hash })
    .onConflictDoUpdate({
      target: auditHeads.siteId,
      set: { seq: event.seq, hash },
    });
  return { ...event, hash };
}

export async function bindPhoto(
  tx: Tx,
  input: {
    photoId: string;
    siteId: string;
    ownerId: string;
    purpose: (typeof photoPurposes)[number];
    entityId: string;
    viewerIds?: string[];
    shareWithSite?: boolean;
    notBefore?: Date;
  },
) {
  const [photo] = await tx
    .select({ entity: photos.entityId, uploadedAt: photos.createdAt })
    .from(photos)
    .where(
      and(
        eq(photos.id, input.photoId),
        eq(photos.siteId, input.siteId),
        eq(photos.ownerId, input.ownerId),
        eq(photos.purpose, input.purpose),
      ),
    )
    .for('update');
  if (!photo)
    throw new NotFoundException({
      error: {
        code: 'PHOTO_NOT_FOUND',
        message: '이 작업에 사용할 사진을 찾을 수 없어요.',
      },
    });
  if (photo.entity && photo.entity !== input.entityId)
    throw new ConflictException();
  if (input.notBefore && photo.uploadedAt < input.notBefore)
    throw new ConflictException({
      error: {
        code: 'PHOTO_BEFORE_ARRIVAL',
        message: '현장에 도착한 뒤 올린 사진을 선택해 주세요.',
      },
    });
  if (photo.entity) return;
  await tx
    .update(photos)
    .set({
      entityId: input.entityId,
      viewerIds: [...new Set(input.viewerIds ?? [])],
      shareWithSite: input.shareWithSite ?? false,
    })
    .where(eq(photos.id, input.photoId));
}
