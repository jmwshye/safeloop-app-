import { z } from 'zod';
import { coordinateSchema } from '../sites/sites.schemas.js';

export const locationSchema = coordinateSchema.extend({
  accuracyMeters: z.number().min(0).max(100_000),
  capturedAt: z.iso.datetime({ offset: true }),
});
export const checkInSchema = z.strictObject({
  siteId: z.uuid().optional(),
  location: locationSchema.nullish(),
});
export type CheckInInput = z.infer<typeof checkInSchema>;
export type Fix = z.infer<typeof locationSchema>;
export const attendanceIdSchema = z.uuid();
const answer = z.enum(['confirmed', 'needs_attention', 'unknown']);

export const safetyCheckSchema = z.strictObject({
  fallHazard: answer,
  floorAndGuardrail: answer,
  protectiveEquipment: answer,
});

export type SafetyCheckInput = z.infer<typeof safetyCheckSchema>;
export const highRiskSchema = z.strictObject({
  task: z.enum(['scaffold', 'ladder', 'opening', 'aerial_lift']).nullable(),
});
export const checkPeerSchema = z
  .strictObject({
    outcome: z.enum(['checked', 'mismatch']),
    photoId: z.uuid().nullish(),
  })
  .refine((x) => x.outcome === 'checked' || !x.photoId);
export type PeerInput = z.infer<typeof checkPeerSchema>;
export const pairSchema = z.strictObject({
  workerId: z.uuid(),
  buddyId: z.uuid(),
});
export const replySchema = z.strictObject({
  response: z.enum(['okay', 'help_needed']),
});
export const deviceSchema = z.strictObject({
  state: z.enum(['carried', 'not_carried']),
  observedAt: z.iso.datetime({ offset: true }),
});
export type DeviceInput = z.infer<typeof deviceSchema>;
export const emptySchema = z.strictObject({});
export const rangeSchema = z
  .strictObject({ from: z.iso.date(), to: z.iso.date() })
  .refine(
    ({ from, to }) =>
      from <= to && Date.parse(to) - Date.parse(from) <= 366 * 86400000,
  );
