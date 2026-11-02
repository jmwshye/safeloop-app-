import { z } from 'zod';
import { riskCategories, riskStatuses } from '../database/risk-schema.js';

const uuid = z.uuid().toLowerCase();
const categoryLabels: Record<(typeof riskCategories)[number], string> = {
  fall: '추락',
  trip: '넘어짐',
  collision: '충돌',
  electric: '감전',
  other: '기타',
};
export const riskInput = z
  .strictObject({
    requestId: uuid,
    title: z.string().trim().min(1).max(100).optional(),
    zone: z.string().trim().min(1).max(100),
    category: z.enum(riskCategories),
    note: z.string().trim().max(1000).default(''),
    anonymous: z.boolean(),
    beforePhotoId: uuid.nullish().transform((value) => value ?? null),
  })
  .transform((input) => ({
    ...input,
    title:
      input.title ??
      `${categoryLabels[input.category]} 위험 · ${input.zone}`.slice(0, 100),
  }));
export type RiskInput = z.infer<typeof riskInput>;
export const fixInput = z.strictObject({ afterPhotoId: uuid });
export const requestInput = z.strictObject({ assigneeId: uuid });
export const emptyInput = z.strictObject({});
export const riskQuery = z.strictObject({
  status: z.enum(riskStatuses).optional(),
  offset: z.coerce.number().int().min(0).max(1_000_000).default(0),
  limit: z.coerce.number().int().min(1).max(100).default(40),
});
export type RiskQuery = z.infer<typeof riskQuery>;
