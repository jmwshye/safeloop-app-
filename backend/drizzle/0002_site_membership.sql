CREATE TABLE "site_memberships" (
	"site_id" uuid NOT NULL,
	"user_id" uuid NOT NULL,
	CONSTRAINT "site_memberships_site_id_user_id_pk" PRIMARY KEY("site_id","user_id")
);
--> statement-breakpoint
CREATE TABLE "sites" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"code" text NOT NULL,
	"name" text NOT NULL,
	"foreman_user_id" uuid NOT NULL,
	CONSTRAINT "sites_code_unique" UNIQUE("code"),
	CONSTRAINT "sites_code_format" CHECK (length("sites"."code") <= 12 AND "sites"."code" ~ '^[A-Z0-9]+(-[A-Z0-9]+)*$')
);
--> statement-breakpoint
ALTER TABLE "site_memberships" ADD CONSTRAINT "site_memberships_site_id_sites_id_fk" FOREIGN KEY ("site_id") REFERENCES "public"."sites"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "site_memberships" ADD CONSTRAINT "site_memberships_user_id_users_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."users"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "sites" ADD CONSTRAINT "sites_foreman_user_id_users_id_fk" FOREIGN KEY ("foreman_user_id") REFERENCES "public"."users"("id") ON DELETE no action ON UPDATE no action;