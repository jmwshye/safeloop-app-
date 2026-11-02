CREATE TYPE "public"."safety_answer" AS ENUM('confirmed', 'needs_attention', 'unknown');--> statement-breakpoint
CREATE TABLE "attendances" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"user_id" uuid NOT NULL,
	"work_date" date NOT NULL,
	"checked_in_at" timestamp with time zone NOT NULL,
	"fall_hazard" "safety_answer",
	"floor_and_guardrail" "safety_answer",
	"protective_equipment" "safety_answer",
	"safety_check_updated_at" timestamp with time zone,
	CONSTRAINT "attendances_user_date_unique" UNIQUE("user_id","work_date"),
	CONSTRAINT "attendances_complete_safety_check" CHECK (
      ("attendances"."fall_hazard" IS NULL AND "attendances"."floor_and_guardrail" IS NULL
        AND "attendances"."protective_equipment" IS NULL AND "attendances"."safety_check_updated_at" IS NULL)
      OR
      ("attendances"."fall_hazard" IS NOT NULL AND "attendances"."floor_and_guardrail" IS NOT NULL
        AND "attendances"."protective_equipment" IS NOT NULL AND "attendances"."safety_check_updated_at" IS NOT NULL)
    )
);
--> statement-breakpoint
ALTER TABLE "attendances" ADD CONSTRAINT "attendances_user_id_users_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."users"("id") ON DELETE cascade ON UPDATE no action;