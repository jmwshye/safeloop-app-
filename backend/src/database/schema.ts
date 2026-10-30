import { sql } from 'drizzle-orm';
import {
  check,
  date,
  index,
  pgEnum,
  pgTable,
  text,
  timestamp,
  unique,
  uuid,
} from 'drizzle-orm/pg-core';

export const users = pgTable(
  'users',
  {
    id: uuid('id').defaultRandom().primaryKey(),
    name: text('name').notNull(),
    email: text('email').notNull().unique('users_email_unique'),
    passwordHash: text('password_hash').notNull(),
    createdAt: timestamp('created_at', { withTimezone: true })
      .defaultNow()
      .notNull(),
  },
  (table) => [
    check(
      'users_email_normalized',
      sql`${table.email} = lower(btrim(${table.email}))`,
    ),
  ],
);

export const sessions = pgTable(
  'sessions',
  {
    tokenHash: text('token_hash').primaryKey(),
    userId: uuid('user_id')
      .notNull()
      .references(() => users.id, { onDelete: 'cascade' }),
    expiresAt: timestamp('expires_at', { withTimezone: true }).notNull(),
    createdAt: timestamp('created_at', { withTimezone: true })
      .defaultNow()
      .notNull(),
  },
  (table) => [index('sessions_user_id_idx').on(table.userId)],
);

export const safetyAnswer = pgEnum('safety_answer', [
  'confirmed',
  'needs_attention',
  'unknown',
]);

export const attendances = pgTable(
  'attendances',
  {
    id: uuid('id').defaultRandom().primaryKey(),
    userId: uuid('user_id')
      .notNull()
      .references(() => users.id, { onDelete: 'cascade' }),
    workDate: date('work_date', { mode: 'string' }).notNull(),
    checkedInAt: timestamp('checked_in_at', { withTimezone: true }).notNull(),
    fallHazard: safetyAnswer('fall_hazard'),
    floorAndGuardrail: safetyAnswer('floor_and_guardrail'),
    protectiveEquipment: safetyAnswer('protective_equipment'),
    safetyCheckUpdatedAt: timestamp('safety_check_updated_at', {
      withTimezone: true,
    }),
  },
  (table) => [
    unique('attendances_user_date_unique').on(table.userId, table.workDate),
    check(
      'attendances_complete_safety_check',
      sql`
      (${table.fallHazard} IS NULL AND ${table.floorAndGuardrail} IS NULL
        AND ${table.protectiveEquipment} IS NULL AND ${table.safetyCheckUpdatedAt} IS NULL)
      OR
      (${table.fallHazard} IS NOT NULL AND ${table.floorAndGuardrail} IS NOT NULL
        AND ${table.protectiveEquipment} IS NOT NULL AND ${table.safetyCheckUpdatedAt} IS NOT NULL)
    `,
    ),
  ],
);
