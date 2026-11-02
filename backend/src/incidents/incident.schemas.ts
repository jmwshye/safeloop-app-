import { z } from 'zod';

export const createIncidentSchema = z.strictObject({
  siteId: z.uuid(),
  requestId: z.uuid(),
  source: z.enum(['abnormal_movement', 'manual_help']),
  location: z
    .strictObject({
      latitude: z.number().min(-90).max(90),
      longitude: z.number().min(-180).max(180),
      accuracyMeters: z.number().min(0).max(100000),
    })
    .optional(),
  detectedAt: z.iso.datetime({ offset: true }).optional(),
  attendanceId: z.uuid().optional(),
  peakG: z.number().nonnegative().max(1000).optional(),
  sensorWindow: z
    .array(
      z.strictObject({
        offsetMs: z.number().int().min(-15000).max(15000),
        accelerationG: z.number().nonnegative().max(1000),
      }),
    )
    .max(300)
    .optional(),
});
export const workerReplySchema = z.strictObject({
  requestId: z.uuid(),
  response: z.enum(['okay', 'help_needed']),
});
export type WorkerReplyInput = z.infer<typeof workerReplySchema>;
export const receiptSchema = z.strictObject({
  stage: z.enum(['received', 'opened']),
  occurredAt: z.iso.datetime({ offset: true }).optional(),
});
export const observeSchema = z.strictObject({
  condition: z.enum(['unconscious', 'immobile', 'conscious', 'appears_safe']),
});
export const resolutionSchema = z.strictObject({
  reason: z.enum([
    'no_help_needed',
    'onsite_action_completed',
    'handoff_confirmed',
  ]),
  photoId: z.uuid().optional(),
});
export const dialSchema = z.strictObject({
  requestId: z.uuid(),
  openedAt: z.iso.datetime({ offset: true }).optional(),
});
export const emptySchema = z.strictObject({}).default({});
export type CreateIncidentInput = z.infer<typeof createIncidentSchema>;
export type ResolutionInput = z.infer<typeof resolutionSchema>;
