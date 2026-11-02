import {
  boolean,
  index,
  pgEnum,
  pgTable,
  text,
  timestamp,
  unique,
  uuid,
} from 'drizzle-orm/pg-core';
import { sites, users } from './schema.js';

export const riskCategories = [
  'fall',
  'trip',
  'collision',
  'electric',
  'other',
] as const;
export const riskStatuses = ['open', 'awaiting_recheck', 'resolved'] as const;
export const riskCategory = pgEnum('risk_category', riskCategories);
export const riskStatus = pgEnum('risk_status', riskStatuses);

export const riskReports = pgTable(
  'risk_reports',
  {
    id: uuid('id').defaultRandom().primaryKey(),
    siteId: uuid('site_id')
      .notNull()
      .references(() => sites.id, { onDelete: 'cascade' }),
    requestId: uuid('request_id').notNull(),
    reporterId: uuid('reporter_id')
      .notNull()
      .references(() => users.id),
    anonymous: boolean('anonymous').notNull(),
    title: text('title').notNull(),
    zone: text('zone').notNull(),
    category: riskCategory('category').notNull(),
    note: text('note').notNull().default(''),
    status: riskStatus('status').notNull().default('open'),
    beforePhotoId: uuid('before_photo_id'),
    afterPhotoId: uuid('after_photo_id'),
    createdAt: timestamp('created_at', { withTimezone: true })
      .notNull()
      .defaultNow(),
    requestedAt: timestamp('requested_at', { withTimezone: true }),
    assigneeId: uuid('assignee_id').references(() => users.id),
    fixerId: uuid('fixer_id').references(() => users.id),
    fixedAt: timestamp('fixed_at', { withTimezone: true }),
    verifierId: uuid('verifier_id').references(() => users.id),
    verifiedAt: timestamp('verified_at', { withTimezone: true }),
  },
  (table) => [
    unique('risk_reports_request_unique').on(
      table.siteId,
      table.reporterId,
      table.requestId,
    ),
    index('risk_reports_site_status_date_idx').on(
      table.siteId,
      table.status,
      table.createdAt,
    ),
  ],
);
