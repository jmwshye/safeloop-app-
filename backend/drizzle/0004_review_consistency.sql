ALTER TABLE "sites" ADD COLUMN "creation_request_id" uuid;--> statement-breakpoint
ALTER TABLE "sites" ADD COLUMN "creation_fingerprint" text;--> statement-breakpoint
ALTER TABLE "sites" ADD CONSTRAINT "sites_foreman_user_id_creation_request_id_unique" UNIQUE("foreman_user_id","creation_request_id");