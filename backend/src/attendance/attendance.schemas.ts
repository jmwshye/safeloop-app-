import { z } from 'zod';

export const checkInSchema = z.strictObject({});
export const attendanceIdSchema = z.uuid();
const answer = z.enum(['confirmed', 'needs_attention', 'unknown']);

export const safetyCheckSchema = z.strictObject({
  fallHazard: answer,
  floorAndGuardrail: answer,
  protectiveEquipment: answer,
});

export type SafetyCheckInput = z.infer<typeof safetyCheckSchema>;
