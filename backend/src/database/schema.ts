import { sql } from 'drizzle-orm';
import {
  check,
  date,
  doublePrecision,
  index,
  integer,
  pgEnum,
  pgTable,
  primaryKey,
  text,
  timestamp,
  unique,
  uniqueIndex,
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

export const sites = pgTable(
  'sites',
  {
    id: uuid('id').defaultRandom().primaryKey(),
    code: text('code').notNull().unique(),
    name: text('name').notNull(),
    foremanUserId: uuid('foreman_user_id')
      .notNull()
      .references(() => users.id),
    latitude: doublePrecision('latitude'),
    longitude: doublePrecision('longitude'),
    foremanPhone: text('foreman_phone'),
    creationRequestId: uuid('creation_request_id'),
    creationFingerprint: text('creation_fingerprint'),
    breakReturnTime: text('break_return_time'),
    shiftChangeTime: text('shift_change_time'),
  },
  (table) => [
    check(
      'sites_code_format',
      sql`length(${table.code}) <= 12 AND ${table.code} ~ '^[A-Z0-9]+(-[A-Z0-9]+)*$'`,
    ),
    unique().on(table.foremanUserId, table.creationRequestId),
    check(
      'sites_center',
      sql`(${table.latitude} IS NULL AND ${table.longitude} IS NULL) OR
      (${table.latitude} IS NOT NULL AND ${table.longitude} IS NOT NULL AND
      ${table.latitude} BETWEEN -90 AND 90 AND ${table.longitude} BETWEEN -180 AND 180)`,
    ),
  ],
);

export const siteMemberships = pgTable(
  'site_memberships',
  {
    siteId: uuid('site_id')
      .notNull()
      .references(() => sites.id, { onDelete: 'cascade' }),
    userId: uuid('user_id')
      .notNull()
      .references(() => users.id, { onDelete: 'cascade' }),
    occupation: text('occupation'),
    zone: text('zone'),
    expectedWage: integer('expected_wage'),
  },
  (table) => [
    primaryKey({ columns: [table.siteId, table.userId] }),
    check(
      'site_membership_wage',
      sql`${table.expectedWage} BETWEEN 0 AND 10000000`,
    ),
  ],
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
    siteId: uuid('site_id').references(() => sites.id),
    checkedOutAt: timestamp('checked_out_at', { withTimezone: true }),
    occupation: text('occupation'),
    zone: text('zone'),
    expectedWage: integer('expected_wage'),
    locationStatus: text('location_status', {
      enum: ['inside', 'outside', 'unverified'],
    })
      .default('unverified')
      .notNull(),
    buddyUserId: uuid('buddy_user_id').references(() => users.id),
    highRiskTask: text('high_risk_task'),
    highRiskStartedAt: timestamp('high_risk_started_at', {
      withTimezone: true,
    }),
    fallHazard: safetyAnswer('fall_hazard'),
    floorAndGuardrail: safetyAnswer('floor_and_guardrail'),
    protectiveEquipment: safetyAnswer('protective_equipment'),
    safetyCheckUpdatedAt: timestamp('safety_check_updated_at', {
      withTimezone: true,
    }),
  },
  (table) => [
    unique('attendances_user_date_unique').on(table.userId, table.workDate),
    index('attendances_site_date_idx').on(table.siteId, table.workDate),
    check(
      'attendances_location_status',
      sql`${table.locationStatus} IN ('inside','outside','unverified')`,
    ),
    check(
      'attendances_shift_end',
      sql`${table.checkedOutAt} >= ${table.checkedInAt}`,
    ),
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

export const shiftDevices = pgTable(
  'shift_devices',
  {
    attendanceId: uuid('attendance_id')
      .primaryKey()
      .references(() => attendances.id, { onDelete: 'cascade' }),
    state: text('state', { enum: ['carried', 'not_carried'] }).notNull(),
    observedAt: timestamp('observed_at', { withTimezone: true }).notNull(),
    receivedAt: timestamp('received_at', { withTimezone: true }).notNull(),
  },
  (t) => [
    check('shift_devices_state', sql`${t.state} IN ('carried','not_carried')`),
  ],
);

export const safetyReviews = pgTable('safety_reviews', {
  attendanceId: uuid('attendance_id')
    .primaryKey()
    .references(() => attendances.id, { onDelete: 'cascade' }),
  requestedAt: timestamp('requested_at', { withTimezone: true }).notNull(),
  acknowledgedAt: timestamp('acknowledged_at', { withTimezone: true }),
  resolvedAt: timestamp('resolved_at', { withTimezone: true }),
});

export const peerChecks = pgTable(
  'peer_checks',
  {
    attendanceId: uuid('attendance_id')
      .notNull()
      .references(() => attendances.id, { onDelete: 'cascade' }),
    checkerUserId: uuid('checker_user_id')
      .notNull()
      .references(() => users.id),
    outcome: text('outcome', { enum: ['checked', 'mismatch'] }).notNull(),
    photoId: uuid('photo_id'),
    checkedAt: timestamp('checked_at', { withTimezone: true }).notNull(),
  },
  (table) => [
    primaryKey({ columns: [table.attendanceId, table.checkerUserId] }),
    check(
      'peer_checks_outcome',
      sql`${table.outcome} = 'checked' OR (${table.outcome} = 'mismatch' AND ${table.photoId} IS NULL)`,
    ),
  ],
);

export const workPrompts = pgTable(
  'work_prompts',
  {
    id: uuid('id').defaultRandom().primaryKey(),
    attendanceId: uuid('attendance_id')
      .notNull()
      .references(() => attendances.id, { onDelete: 'cascade' }),
    kind: text('kind', {
      enum: [
        'break_return',
        'shift_change',
        'high_risk_start',
        'high_risk_end',
      ],
    }).notNull(),
    dueAt: timestamp('due_at', { withTimezone: true }).notNull(),
    expiresAt: timestamp('expires_at', { withTimezone: true }).notNull(),
    response: text('response', { enum: ['okay', 'help_needed'] }),
    answeredAt: timestamp('answered_at', { withTimezone: true }),
  },
  (table) => [
    uniqueIndex('work_prompts_daily_kind_idx')
      .on(table.attendanceId, table.kind)
      .where(sql`${table.kind} IN ('break_return','shift_change')`),
    check(
      'work_prompt_kind_valid',
      sql`${table.kind} IN ('break_return','shift_change','high_risk_start','high_risk_end')`,
    ),
    check(
      'work_prompt_response',
      sql`(${table.response} IS NULL AND ${table.answeredAt} IS NULL) OR
    (${table.response} IS NOT NULL AND ${table.response} IN ('okay','help_needed') AND ${table.answeredAt} IS NOT NULL)`,
    ),
  ],
);
