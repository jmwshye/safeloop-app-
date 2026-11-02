CREATE TYPE "public"."incident_source" AS ENUM('abnormal_movement', 'manual_help');--> statement-breakpoint
CREATE TYPE "public"."incident_status" AS ENUM('awaiting_worker', 'calling', 'responding', 'arrived', 'unanswered', 'closed');--> statement-breakpoint
CREATE TYPE "public"."risk_category" AS ENUM('fall', 'trip', 'collision', 'electric', 'other');--> statement-breakpoint
CREATE TYPE "public"."risk_status" AS ENUM('open', 'awaiting_recheck', 'resolved');--> statement-breakpoint
CREATE TABLE "peer_checks" (
	"attendance_id" uuid NOT NULL,
	"checker_user_id" uuid NOT NULL,
	"outcome" text NOT NULL,
	"photo_id" uuid,
	"checked_at" timestamp with time zone NOT NULL,
	CONSTRAINT "peer_checks_attendance_id_checker_user_id_pk" PRIMARY KEY("attendance_id","checker_user_id"),
	CONSTRAINT "peer_checks_outcome" CHECK ("peer_checks"."outcome" = 'checked' OR ("peer_checks"."outcome" = 'mismatch' AND "peer_checks"."photo_id" IS NULL))
);
--> statement-breakpoint
CREATE TABLE "safety_reviews" (
	"attendance_id" uuid PRIMARY KEY NOT NULL,
	"requested_at" timestamp with time zone NOT NULL,
	"acknowledged_at" timestamp with time zone,
	"resolved_at" timestamp with time zone
);
--> statement-breakpoint
CREATE TABLE "shift_devices" (
	"attendance_id" uuid PRIMARY KEY NOT NULL,
	"state" text NOT NULL,
	"observed_at" timestamp with time zone NOT NULL,
	"received_at" timestamp with time zone NOT NULL,
	CONSTRAINT "shift_devices_state" CHECK ("shift_devices"."state" IN ('carried','not_carried'))
);
--> statement-breakpoint
CREATE TABLE "work_prompts" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"attendance_id" uuid NOT NULL,
	"kind" text NOT NULL,
	"due_at" timestamp with time zone NOT NULL,
	"expires_at" timestamp with time zone NOT NULL,
	"response" text,
	"answered_at" timestamp with time zone,
	CONSTRAINT "work_prompt_kind_valid" CHECK ("work_prompts"."kind" IN ('break_return','shift_change','high_risk_start','high_risk_end')),
	CONSTRAINT "work_prompt_response" CHECK (("work_prompts"."response" IS NULL AND "work_prompts"."answered_at" IS NULL) OR
    ("work_prompts"."response" IS NOT NULL AND "work_prompts"."response" IN ('okay','help_needed') AND "work_prompts"."answered_at" IS NOT NULL))
);
--> statement-breakpoint
CREATE TABLE "audit_events" (
	"id" uuid PRIMARY KEY NOT NULL,
	"site_id" uuid NOT NULL,
	"seq" integer NOT NULL,
	"recorded_at" timestamp with time zone NOT NULL,
	"type" text NOT NULL,
	"source" text NOT NULL,
	"actor_id" uuid,
	"subject_id" uuid,
	"entity_id" uuid NOT NULL,
	"corrects_id" uuid,
	"details" jsonb NOT NULL,
	"previous_hash" text,
	"hash" text NOT NULL,
	CONSTRAINT "audit_events_site_id_seq_unique" UNIQUE("site_id","seq")
);
--> statement-breakpoint
CREATE TABLE "audit_heads" (
	"site_id" uuid PRIMARY KEY NOT NULL,
	"seq" integer NOT NULL,
	"hash" text NOT NULL
);
--> statement-breakpoint
CREATE TABLE "photos" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"site_id" uuid NOT NULL,
	"owner_id" uuid NOT NULL,
	"purpose" text NOT NULL,
	"entity_id" uuid,
	"viewer_ids" uuid[] DEFAULT '{}'::uuid[] NOT NULL,
	"share_with_site" boolean DEFAULT false NOT NULL,
	"bytes" "bytea" NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL
);
--> statement-breakpoint
CREATE TABLE "record_corrections" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"site_id" uuid NOT NULL,
	"event_id" uuid NOT NULL,
	"requester_id" uuid NOT NULL,
	"request_id" uuid NOT NULL,
	"note" text NOT NULL,
	"status" text DEFAULT 'pending' NOT NULL,
	"reviewer_id" uuid,
	"review_note" text,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"reviewed_at" timestamp with time zone,
	CONSTRAINT "record_corrections_requester_id_request_id_unique" UNIQUE("requester_id","request_id")
);
--> statement-breakpoint
CREATE TABLE "site_notices" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"site_id" uuid NOT NULL,
	"recipient_id" uuid NOT NULL,
	"kind" text NOT NULL,
	"entity_id" uuid NOT NULL,
	"unique_key" text NOT NULL,
	"available_at" timestamp with time zone NOT NULL,
	"next_attempt_at" timestamp with time zone NOT NULL,
	"status" text DEFAULT 'queued' NOT NULL,
	"attempts" integer DEFAULT 0 NOT NULL,
	CONSTRAINT "site_notices_site_id_recipient_id_unique_key_unique" UNIQUE("site_id","recipient_id","unique_key")
);
--> statement-breakpoint
CREATE TABLE "emergency_dial_intents" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"incident_id" uuid,
	"site_id" uuid NOT NULL,
	"user_id" uuid NOT NULL,
	"request_id" uuid NOT NULL,
	"opened_at" timestamp with time zone NOT NULL,
	CONSTRAINT "dial_intent_request_unique" UNIQUE("site_id","user_id","request_id")
);
--> statement-breakpoint
CREATE TABLE "incident_pushes" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"incident_id" uuid NOT NULL,
	"user_id" uuid NOT NULL,
	"type" text NOT NULL,
	"status" text DEFAULT 'queued' NOT NULL,
	"attempts" integer DEFAULT 0 NOT NULL,
	"requested_at" timestamp with time zone NOT NULL,
	"sent_at" timestamp with time zone,
	"next_attempt_at" timestamp with time zone NOT NULL,
	CONSTRAINT "incident_push_event_unique" UNIQUE("incident_id","user_id","type")
);
--> statement-breakpoint
CREATE TABLE "incident_recipients" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"incident_id" uuid NOT NULL,
	"user_id" uuid NOT NULL,
	"role" text NOT NULL,
	"requested_at" timestamp with time zone NOT NULL,
	"sent_at" timestamp with time zone,
	"received_at" timestamp with time zone,
	"opened_at" timestamp with time zone,
	"accepted_at" timestamp with time zone,
	"declined_at" timestamp with time zone,
	CONSTRAINT "incident_recipient_unique" UNIQUE("incident_id","user_id")
);
--> statement-breakpoint
CREATE TABLE "incident_requests" (
	"worker_id" uuid NOT NULL,
	"request_id" uuid NOT NULL,
	"incident_id" uuid NOT NULL,
	"fingerprint" text NOT NULL,
	CONSTRAINT "incident_requests_worker_id_request_id_pk" PRIMARY KEY("worker_id","request_id")
);
--> statement-breakpoint
CREATE TABLE "incidents" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"worker_id" uuid NOT NULL,
	"site_id" uuid NOT NULL,
	"attendance_id" uuid,
	"buddy_id" uuid,
	"foreman_id" uuid NOT NULL,
	"zone" text NOT NULL,
	"source" "incident_source" NOT NULL,
	"status" "incident_status" NOT NULL,
	"location" jsonb,
	"peak_g" double precision,
	"sensor_window" jsonb,
	"created_at" timestamp with time zone NOT NULL,
	"worker_due_at" timestamp with time zone,
	"worker_response" text,
	"worker_responded_at" timestamp with time zone,
	"escalated_at" timestamp with time zone,
	"next_call_at" timestamp with time zone,
	"responder_id" uuid,
	"accepted_at" timestamp with time zone,
	"arrived_at" timestamp with time zone,
	"observation" text,
	"observed_at" timestamp with time zone,
	"photo_id" uuid,
	"resolution" text,
	"closed_at" timestamp with time zone
);
--> statement-breakpoint
CREATE TABLE "notification_preferences" (
	"user_id" uuid PRIMARY KEY NOT NULL,
	"emergency_push" boolean DEFAULT true NOT NULL,
	"site_push" boolean DEFAULT true NOT NULL
);
--> statement-breakpoint
CREATE TABLE "push_devices" (
	"id" uuid PRIMARY KEY NOT NULL,
	"user_id" uuid NOT NULL,
	"fcm_token" text NOT NULL,
	"updated_at" timestamp with time zone NOT NULL,
	CONSTRAINT "push_devices_fcm_token_unique" UNIQUE("fcm_token")
);
--> statement-breakpoint
CREATE TABLE "risk_reports" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"site_id" uuid NOT NULL,
	"request_id" uuid NOT NULL,
	"reporter_id" uuid NOT NULL,
	"anonymous" boolean NOT NULL,
	"title" text NOT NULL,
	"zone" text NOT NULL,
	"category" "risk_category" NOT NULL,
	"note" text DEFAULT '' NOT NULL,
	"status" "risk_status" DEFAULT 'open' NOT NULL,
	"before_photo_id" uuid,
	"after_photo_id" uuid,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"requested_at" timestamp with time zone,
	"assignee_id" uuid,
	"fixer_id" uuid,
	"fixed_at" timestamp with time zone,
	"verifier_id" uuid,
	"verified_at" timestamp with time zone,
	CONSTRAINT "risk_reports_request_unique" UNIQUE("site_id","reporter_id","request_id")
);
--> statement-breakpoint
ALTER TABLE "attendances" ADD COLUMN "site_id" uuid;--> statement-breakpoint
ALTER TABLE "attendances" ADD COLUMN "checked_out_at" timestamp with time zone;--> statement-breakpoint
ALTER TABLE "attendances" ADD COLUMN "occupation" text;--> statement-breakpoint
ALTER TABLE "attendances" ADD COLUMN "zone" text;--> statement-breakpoint
ALTER TABLE "attendances" ADD COLUMN "expected_wage" integer;--> statement-breakpoint
ALTER TABLE "attendances" ADD COLUMN "location_status" text DEFAULT 'unverified' NOT NULL;--> statement-breakpoint
ALTER TABLE "attendances" ADD COLUMN "buddy_user_id" uuid;--> statement-breakpoint
ALTER TABLE "attendances" ADD COLUMN "high_risk_task" text;--> statement-breakpoint
ALTER TABLE "attendances" ADD COLUMN "high_risk_started_at" timestamp with time zone;--> statement-breakpoint
ALTER TABLE "site_memberships" ADD COLUMN "occupation" text;--> statement-breakpoint
ALTER TABLE "site_memberships" ADD COLUMN "zone" text;--> statement-breakpoint
ALTER TABLE "site_memberships" ADD COLUMN "expected_wage" integer;--> statement-breakpoint
ALTER TABLE "sites" ADD COLUMN "latitude" double precision;--> statement-breakpoint
ALTER TABLE "sites" ADD COLUMN "longitude" double precision;--> statement-breakpoint
ALTER TABLE "sites" ADD COLUMN "foreman_phone" text;--> statement-breakpoint
ALTER TABLE "sites" ADD COLUMN "break_return_time" text;--> statement-breakpoint
ALTER TABLE "sites" ADD COLUMN "shift_change_time" text;--> statement-breakpoint
ALTER TABLE "peer_checks" ADD CONSTRAINT "peer_checks_attendance_id_attendances_id_fk" FOREIGN KEY ("attendance_id") REFERENCES "public"."attendances"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "peer_checks" ADD CONSTRAINT "peer_checks_checker_user_id_users_id_fk" FOREIGN KEY ("checker_user_id") REFERENCES "public"."users"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "safety_reviews" ADD CONSTRAINT "safety_reviews_attendance_id_attendances_id_fk" FOREIGN KEY ("attendance_id") REFERENCES "public"."attendances"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "shift_devices" ADD CONSTRAINT "shift_devices_attendance_id_attendances_id_fk" FOREIGN KEY ("attendance_id") REFERENCES "public"."attendances"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "work_prompts" ADD CONSTRAINT "work_prompts_attendance_id_attendances_id_fk" FOREIGN KEY ("attendance_id") REFERENCES "public"."attendances"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "audit_events" ADD CONSTRAINT "audit_events_site_id_sites_id_fk" FOREIGN KEY ("site_id") REFERENCES "public"."sites"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "audit_heads" ADD CONSTRAINT "audit_heads_site_id_sites_id_fk" FOREIGN KEY ("site_id") REFERENCES "public"."sites"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "photos" ADD CONSTRAINT "photos_site_id_sites_id_fk" FOREIGN KEY ("site_id") REFERENCES "public"."sites"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "photos" ADD CONSTRAINT "photos_owner_id_users_id_fk" FOREIGN KEY ("owner_id") REFERENCES "public"."users"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "record_corrections" ADD CONSTRAINT "record_corrections_site_id_sites_id_fk" FOREIGN KEY ("site_id") REFERENCES "public"."sites"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "record_corrections" ADD CONSTRAINT "record_corrections_event_id_audit_events_id_fk" FOREIGN KEY ("event_id") REFERENCES "public"."audit_events"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "record_corrections" ADD CONSTRAINT "record_corrections_requester_id_users_id_fk" FOREIGN KEY ("requester_id") REFERENCES "public"."users"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "record_corrections" ADD CONSTRAINT "record_corrections_reviewer_id_users_id_fk" FOREIGN KEY ("reviewer_id") REFERENCES "public"."users"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "site_notices" ADD CONSTRAINT "site_notices_site_id_sites_id_fk" FOREIGN KEY ("site_id") REFERENCES "public"."sites"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "site_notices" ADD CONSTRAINT "site_notices_recipient_id_users_id_fk" FOREIGN KEY ("recipient_id") REFERENCES "public"."users"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "emergency_dial_intents" ADD CONSTRAINT "emergency_dial_intents_incident_id_incidents_id_fk" FOREIGN KEY ("incident_id") REFERENCES "public"."incidents"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "emergency_dial_intents" ADD CONSTRAINT "emergency_dial_intents_site_id_sites_id_fk" FOREIGN KEY ("site_id") REFERENCES "public"."sites"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "emergency_dial_intents" ADD CONSTRAINT "emergency_dial_intents_user_id_users_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."users"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "incident_pushes" ADD CONSTRAINT "incident_pushes_incident_id_incidents_id_fk" FOREIGN KEY ("incident_id") REFERENCES "public"."incidents"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "incident_pushes" ADD CONSTRAINT "incident_pushes_user_id_users_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."users"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "incident_recipients" ADD CONSTRAINT "incident_recipients_incident_id_incidents_id_fk" FOREIGN KEY ("incident_id") REFERENCES "public"."incidents"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "incident_recipients" ADD CONSTRAINT "incident_recipients_user_id_users_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."users"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "incident_requests" ADD CONSTRAINT "incident_requests_worker_id_users_id_fk" FOREIGN KEY ("worker_id") REFERENCES "public"."users"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "incident_requests" ADD CONSTRAINT "incident_requests_incident_id_incidents_id_fk" FOREIGN KEY ("incident_id") REFERENCES "public"."incidents"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "incidents" ADD CONSTRAINT "incidents_worker_id_users_id_fk" FOREIGN KEY ("worker_id") REFERENCES "public"."users"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "incidents" ADD CONSTRAINT "incidents_site_id_sites_id_fk" FOREIGN KEY ("site_id") REFERENCES "public"."sites"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "incidents" ADD CONSTRAINT "incidents_attendance_id_attendances_id_fk" FOREIGN KEY ("attendance_id") REFERENCES "public"."attendances"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "incidents" ADD CONSTRAINT "incidents_buddy_id_users_id_fk" FOREIGN KEY ("buddy_id") REFERENCES "public"."users"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "incidents" ADD CONSTRAINT "incidents_foreman_id_users_id_fk" FOREIGN KEY ("foreman_id") REFERENCES "public"."users"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "incidents" ADD CONSTRAINT "incidents_responder_id_users_id_fk" FOREIGN KEY ("responder_id") REFERENCES "public"."users"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "notification_preferences" ADD CONSTRAINT "notification_preferences_user_id_users_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."users"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "push_devices" ADD CONSTRAINT "push_devices_user_id_users_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."users"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "risk_reports" ADD CONSTRAINT "risk_reports_site_id_sites_id_fk" FOREIGN KEY ("site_id") REFERENCES "public"."sites"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "risk_reports" ADD CONSTRAINT "risk_reports_reporter_id_users_id_fk" FOREIGN KEY ("reporter_id") REFERENCES "public"."users"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "risk_reports" ADD CONSTRAINT "risk_reports_assignee_id_users_id_fk" FOREIGN KEY ("assignee_id") REFERENCES "public"."users"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "risk_reports" ADD CONSTRAINT "risk_reports_fixer_id_users_id_fk" FOREIGN KEY ("fixer_id") REFERENCES "public"."users"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "risk_reports" ADD CONSTRAINT "risk_reports_verifier_id_users_id_fk" FOREIGN KEY ("verifier_id") REFERENCES "public"."users"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
CREATE UNIQUE INDEX "work_prompts_daily_kind_idx" ON "work_prompts" USING btree ("attendance_id","kind") WHERE "work_prompts"."kind" IN ('break_return','shift_change');--> statement-breakpoint
CREATE INDEX "audit_events_entity_id_seq_index" ON "audit_events" USING btree ("entity_id","seq");--> statement-breakpoint
CREATE INDEX "audit_events_site_id_subject_id_seq_index" ON "audit_events" USING btree ("site_id","subject_id","seq");--> statement-breakpoint
CREATE INDEX "photos_site_id_entity_id_index" ON "photos" USING btree ("site_id","entity_id");--> statement-breakpoint
CREATE INDEX "photos_created_at_index" ON "photos" USING btree ("created_at") WHERE "photos"."entity_id" IS NULL;--> statement-breakpoint
CREATE INDEX "record_corrections_site_id_status_index" ON "record_corrections" USING btree ("site_id","status");--> statement-breakpoint
CREATE INDEX "site_notices_status_next_attempt_at_index" ON "site_notices" USING btree ("status","next_attempt_at");--> statement-breakpoint
CREATE INDEX "site_notices_recipient_id_available_at_index" ON "site_notices" USING btree ("recipient_id","available_at");--> statement-breakpoint
CREATE INDEX "incident_push_pending_idx" ON "incident_pushes" USING btree ("next_attempt_at") WHERE "incident_pushes"."status"='queued';--> statement-breakpoint
CREATE INDEX "incident_recipient_user_idx" ON "incident_recipients" USING btree ("user_id");--> statement-breakpoint
CREATE UNIQUE INDEX "incidents_open_worker_idx" ON "incidents" USING btree ("worker_id") WHERE "incidents"."closed_at" IS NULL;--> statement-breakpoint
CREATE UNIQUE INDEX "incidents_open_responder_idx" ON "incidents" USING btree ("responder_id") WHERE "incidents"."closed_at" IS NULL AND "incidents"."responder_id" IS NOT NULL;--> statement-breakpoint
CREATE INDEX "incidents_deadline_idx" ON "incidents" USING btree ("worker_due_at","next_call_at") WHERE "incidents"."closed_at" IS NULL;--> statement-breakpoint
CREATE INDEX "incidents_site_idx" ON "incidents" USING btree ("site_id","created_at");--> statement-breakpoint
CREATE INDEX "push_devices_user_idx" ON "push_devices" USING btree ("user_id");--> statement-breakpoint
CREATE INDEX "risk_reports_site_status_date_idx" ON "risk_reports" USING btree ("site_id","status","created_at");--> statement-breakpoint
ALTER TABLE "attendances" ADD CONSTRAINT "attendances_site_id_sites_id_fk" FOREIGN KEY ("site_id") REFERENCES "public"."sites"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "attendances" ADD CONSTRAINT "attendances_buddy_user_id_users_id_fk" FOREIGN KEY ("buddy_user_id") REFERENCES "public"."users"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
CREATE INDEX "attendances_site_date_idx" ON "attendances" USING btree ("site_id","work_date");--> statement-breakpoint
ALTER TABLE "attendances" ADD CONSTRAINT "attendances_location_status" CHECK ("attendances"."location_status" IN ('inside','outside','unverified'));--> statement-breakpoint
ALTER TABLE "attendances" ADD CONSTRAINT "attendances_shift_end" CHECK ("attendances"."checked_out_at" >= "attendances"."checked_in_at");--> statement-breakpoint
ALTER TABLE "site_memberships" ADD CONSTRAINT "site_membership_wage" CHECK ("site_memberships"."expected_wage" BETWEEN 0 AND 10000000);--> statement-breakpoint
ALTER TABLE "sites" ADD CONSTRAINT "sites_center" CHECK (("sites"."latitude" IS NULL AND "sites"."longitude" IS NULL) OR
      ("sites"."latitude" IS NOT NULL AND "sites"."longitude" IS NOT NULL AND
      "sites"."latitude" BETWEEN -90 AND 90 AND "sites"."longitude" BETWEEN -180 AND 180));