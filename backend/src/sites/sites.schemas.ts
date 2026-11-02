import { BadRequestException } from '@nestjs/common';
import { z } from 'zod';

export const coordinateSchema = z.strictObject({
  latitude: z.number().min(-90).max(90),
  longitude: z.number().min(-180).max(180),
});
const localTime = z.string().regex(/^([01]\d|2[0-3]):[0-5]\d$/);
export const setupSchema = z.strictObject({
  name: z.string().trim().min(1).max(100),
  center: coordinateSchema.nullish(),
  foremanPhone: z
    .string()
    .trim()
    .regex(/^\+?[\d ()-]{7,24}$/)
    .refine((phone) => /^\d{7,15}$/.test(phone.replace(/\D/g, '')))
    .nullish(),
  breakReturnTime: localTime.nullish(),
  shiftChangeTime: localTime.nullish(),
});
export const profileSchema = z.strictObject({
  occupation: z.string().trim().min(1).max(80),
  zone: z.string().trim().min(1).max(80),
  expectedWage: z.number().int().min(0).max(10_000_000).nullish(),
});
export type WorkProfile = z.infer<typeof profileSchema>;
export type SiteSetup = z.infer<typeof setupSchema>;

export function inputFor<T>(schema: z.ZodType<T>, body: unknown): T {
  const result = schema.safeParse(body);
  if (!result.success) throw new BadRequestException();
  return result.data;
}
