import { sql } from 'drizzle-orm';
import {
  boolean,
  doublePrecision,
  index,
  integer,
  jsonb,
  pgEnum,
  pgTable,
  primaryKey,
  text,
  timestamp,
  unique,
  uniqueIndex,
  uuid,
} from 'drizzle-orm/pg-core';
import { attendances, sites, users } from './schema.js';

const time = (name: string) => timestamp(name, { withTimezone: true });
export const incidentStatus = pgEnum('incident_status', [
  'awaiting_worker',
  'calling',
  'responding',
  'arrived',
  'unanswered',
  'closed',
]);
export const incidentSource = pgEnum('incident_source', [
  'abnormal_movement',
  'manual_help',
]);
export type EmergencyLocation = {
  latitude: number;
  longitude: number;
  accuracyMeters: number;
};
export type SensorSample = { offsetMs: number; accelerationG: number };

export const incidents = pgTable(
  'incidents',
  {
    id: uuid('id').defaultRandom().primaryKey(),
    workerId: uuid('worker_id')
      .notNull()
      .references(() => users.id),
    siteId: uuid('site_id')
      .notNull()
      .references(() => sites.id, { onDelete: 'cascade' }),
    attendanceId: uuid('attendance_id').references(() => attendances.id),
    buddyId: uuid('buddy_id').references(() => users.id),
    foremanId: uuid('foreman_id')
      .notNull()
      .references(() => users.id),
    zone: text('zone').notNull(),
    source: incidentSource('source').notNull(),
    status: incidentStatus('status').notNull(),
    location: jsonb('location').$type<EmergencyLocation>(),
    peakG: doublePrecision('peak_g'),
    sensorWindow: jsonb('sensor_window').$type<SensorSample[]>(),
    createdAt: time('created_at').notNull(),
    workerDueAt: time('worker_due_at'),
    workerResponse: text('worker_response', { enum: ['okay', 'help_needed'] }),
    workerRespondedAt: time('worker_responded_at'),
    escalatedAt: time('escalated_at'),
    nextCallAt: time('next_call_at'),
    responderId: uuid('responder_id').references(() => users.id),
    acceptedAt: time('accepted_at'),
    arrivedAt: time('arrived_at'),
    observation: text('observation', {
      enum: ['unconscious', 'immobile', 'conscious', 'appears_safe'],
    }),
    observedAt: time('observed_at'),
    photoId: uuid('photo_id'),
    resolution: text('resolution', {
      enum: [
        'worker_confirmed_okay',
        'no_help_needed',
        'onsite_action_completed',
        'handoff_confirmed',
      ],
    }),
    closedAt: time('closed_at'),
  },
  (t) => [
    uniqueIndex('incidents_open_worker_idx')
      .on(t.workerId)
      .where(sql`${t.closedAt} IS NULL`),
    uniqueIndex('incidents_open_responder_idx')
      .on(t.responderId)
      .where(sql`${t.closedAt} IS NULL AND ${t.responderId} IS NOT NULL`),
    index('incidents_deadline_idx')
      .on(t.workerDueAt, t.nextCallAt)
      .where(sql`${t.closedAt} IS NULL`),
    index('incidents_site_idx').on(t.siteId, t.createdAt),
  ],
);

export const incidentRequests = pgTable(
  'incident_requests',
  {
    workerId: uuid('worker_id')
      .notNull()
      .references(() => users.id),
    requestId: uuid('request_id').notNull(),
    incidentId: uuid('incident_id')
      .notNull()
      .references(() => incidents.id, { onDelete: 'cascade' }),
    fingerprint: text('fingerprint').notNull(),
  },
  (t) => [primaryKey({ columns: [t.workerId, t.requestId] })],
);

export const incidentRecipients = pgTable(
  'incident_recipients',
  {
    id: uuid('id').defaultRandom().primaryKey(),
    incidentId: uuid('incident_id')
      .notNull()
      .references(() => incidents.id, { onDelete: 'cascade' }),
    userId: uuid('user_id')
      .notNull()
      .references(() => users.id),
    role: text('role', { enum: ['buddy', 'foreman', 'zone'] }).notNull(),
    requestedAt: time('requested_at').notNull(),
    sentAt: time('sent_at'),
    receivedAt: time('received_at'),
    openedAt: time('opened_at'),
    acceptedAt: time('accepted_at'),
    declinedAt: time('declined_at'),
  },
  (t) => [
    unique('incident_recipient_unique').on(t.incidentId, t.userId),
    index('incident_recipient_user_idx').on(t.userId),
  ],
);

export const pushDevices = pgTable(
  'push_devices',
  {
    id: uuid('id').primaryKey(),
    userId: uuid('user_id')
      .notNull()
      .references(() => users.id, { onDelete: 'cascade' }),
    fcmToken: text('fcm_token').notNull().unique(),
    updatedAt: time('updated_at').notNull(),
  },
  (t) => [index('push_devices_user_idx').on(t.userId)],
);
export const notificationPreferences = pgTable('notification_preferences', {
  userId: uuid('user_id')
    .primaryKey()
    .references(() => users.id, { onDelete: 'cascade' }),
  emergencyPush: boolean('emergency_push').notNull().default(true),
  sitePush: boolean('site_push').notNull().default(true),
});
export const incidentPushes = pgTable(
  'incident_pushes',
  {
    id: uuid('id').defaultRandom().primaryKey(),
    incidentId: uuid('incident_id')
      .notNull()
      .references(() => incidents.id, { onDelete: 'cascade' }),
    userId: uuid('user_id')
      .notNull()
      .references(() => users.id),
    type: text('type', {
      enum: [
        'alert',
        'observation',
        'worker_safe',
        'closed',
        'verification_overdue',
      ],
    }).notNull(),
    status: text('status', {
      enum: ['queued', 'sent', 'unavailable', 'failed', 'cancelled'],
    })
      .notNull()
      .default('queued'),
    attempts: integer('attempts').notNull().default(0),
    requestedAt: time('requested_at').notNull(),
    sentAt: time('sent_at'),
    nextAttemptAt: time('next_attempt_at').notNull(),
  },
  (t) => [
    unique('incident_push_event_unique').on(t.incidentId, t.userId, t.type),
    index('incident_push_pending_idx')
      .on(t.nextAttemptAt)
      .where(sql`${t.status}='queued'`),
  ],
);
export const emergencyDialIntents = pgTable(
  'emergency_dial_intents',
  {
    id: uuid('id').defaultRandom().primaryKey(),
    incidentId: uuid('incident_id').references(() => incidents.id, {
      onDelete: 'cascade',
    }),
    siteId: uuid('site_id')
      .notNull()
      .references(() => sites.id, { onDelete: 'cascade' }),
    userId: uuid('user_id')
      .notNull()
      .references(() => users.id),
    requestId: uuid('request_id').notNull(),
    openedAt: time('opened_at').notNull(),
  },
  (t) => [
    unique('dial_intent_request_unique').on(t.siteId, t.userId, t.requestId),
  ],
);
