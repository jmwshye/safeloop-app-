import { sql } from 'drizzle-orm';
import {
  boolean,
  customType,
  index,
  integer,
  jsonb,
  pgTable,
  text,
  timestamp,
  unique,
  uuid,
} from 'drizzle-orm/pg-core';
import { sites, users } from './schema.js';

export const auditEvents = pgTable(
  'audit_events',
  {
    id: uuid('id').primaryKey(),
    siteId: uuid('site_id')
      .notNull()
      .references(() => sites.id, { onDelete: 'cascade' }),
    seq: integer('seq').notNull(),
    recordedAt: timestamp('recorded_at', { withTimezone: true }).notNull(),
    type: text('type').notNull(),
    source: text('source', { enum: ['human', 'system'] }).notNull(),
    actorId: uuid('actor_id'),
    subjectId: uuid('subject_id'),
    entityId: uuid('entity_id').notNull(),
    correctsId: uuid('corrects_id'),
    details: jsonb('details').$type<Record<string, unknown>>().notNull(),
    previousHash: text('previous_hash'),
    hash: text('hash').notNull(),
  },
  (t) => [
    unique().on(t.siteId, t.seq),
    index().on(t.entityId, t.seq),
    index().on(t.siteId, t.subjectId, t.seq),
  ],
);

export const photoPurposes = [
  'ppe',
  'risk_before',
  'risk_after',
  'incident_scene',
] as const;
export const auditHeads = pgTable('audit_heads', {
  siteId: uuid('site_id')
    .primaryKey()
    .references(() => sites.id, { onDelete: 'cascade' }),
  seq: integer('seq').notNull(),
  hash: text('hash').notNull(),
});
export const noticeKinds = [
  'safety_review',
  'high_risk',
  'high_risk_end',
  'work_prompt',
  'risk_action',
] as const;
export const siteNotices = pgTable(
  'site_notices',
  {
    id: uuid('id').defaultRandom().primaryKey(),
    siteId: uuid('site_id')
      .notNull()
      .references(() => sites.id, { onDelete: 'cascade' }),
    recipientId: uuid('recipient_id')
      .notNull()
      .references(() => users.id, { onDelete: 'cascade' }),
    kind: text('kind', { enum: noticeKinds }).notNull(),
    entityId: uuid('entity_id').notNull(),
    uniqueKey: text('unique_key').notNull(),
    availableAt: timestamp('available_at', { withTimezone: true }).notNull(),
    nextAttemptAt: timestamp('next_attempt_at', {
      withTimezone: true,
    }).notNull(),
    status: text('status', {
      enum: ['queued', 'sending', 'sent', 'unavailable', 'cancelled', 'failed'],
    })
      .notNull()
      .default('queued'),
    attempts: integer('attempts').notNull().default(0),
  },
  (t) => [
    unique().on(t.siteId, t.recipientId, t.uniqueKey),
    index().on(t.status, t.nextAttemptAt),
    index().on(t.recipientId, t.availableAt),
  ],
);
const bytea = customType<{ data: Buffer; driverData: Buffer }>({
  dataType: () => 'bytea',
});
export const photos = pgTable(
  'photos',
  {
    id: uuid('id').defaultRandom().primaryKey(),
    siteId: uuid('site_id')
      .notNull()
      .references(() => sites.id, { onDelete: 'cascade' }),
    ownerId: uuid('owner_id')
      .notNull()
      .references(() => users.id),
    purpose: text('purpose', { enum: photoPurposes }).notNull(),
    entityId: uuid('entity_id'),
    viewerIds: uuid('viewer_ids')
      .array()
      .notNull()
      .default(sql`'{}'::uuid[]`),
    shareWithSite: boolean('share_with_site').notNull().default(false),
    bytes: bytea('bytes').notNull(),
    // 얼굴을 가리기 전 원본. 올린 사람만 볼 수 있고 보고서와 다른 사람 화면에는 쓰지 않는다.
    originalBytes: bytea('original_bytes'),
    createdAt: timestamp('created_at', { withTimezone: true })
      .defaultNow()
      .notNull(),
  },
  (t) => [
    index().on(t.siteId, t.entityId),
    index()
      .on(t.createdAt)
      .where(sql`${t.entityId} IS NULL`),
  ],
);

export const recordCorrections = pgTable(
  'record_corrections',
  {
    id: uuid('id').defaultRandom().primaryKey(),
    siteId: uuid('site_id')
      .notNull()
      .references(() => sites.id, { onDelete: 'cascade' }),
    eventId: uuid('event_id')
      .notNull()
      .references(() => auditEvents.id, { onDelete: 'cascade' }),
    requesterId: uuid('requester_id')
      .notNull()
      .references(() => users.id),
    requestId: uuid('request_id').notNull(),
    note: text('note').notNull(),
    status: text('status', { enum: ['pending', 'accepted', 'declined'] })
      .notNull()
      .default('pending'),
    reviewerId: uuid('reviewer_id').references(() => users.id),
    reviewNote: text('review_note'),
    createdAt: timestamp('created_at', { withTimezone: true })
      .notNull()
      .defaultNow(),
    reviewedAt: timestamp('reviewed_at', { withTimezone: true }),
  },
  (t) => [
    unique().on(t.requesterId, t.requestId),
    index().on(t.siteId, t.status),
  ],
);
