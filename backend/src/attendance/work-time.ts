import type { sites } from '../database/schema.js';
import type { Fix } from './attendance.schemas.js';

const seoulDay = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Seoul',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
});
export function workDateAt(time: Date): string {
  const fields = Object.fromEntries(
    seoulDay.formatToParts(time).map((part) => [part.type, part.value]),
  );
  return `${fields.year}-${fields.month}-${fields.day}`;
}

/** Keep only the result. A stale or imprecise device fix cannot verify presence. */
export function locationAt(
  site: Pick<typeof sites.$inferSelect, 'latitude' | 'longitude'>,
  fix: Fix | null | undefined,
  now: Date,
) {
  if (
    !fix ||
    site.latitude === null ||
    site.longitude === null ||
    fix.accuracyMeters > 200
  )
    return 'unverified';
  const age = now.valueOf() - Date.parse(fix.capturedAt);
  if (age < -10_000 || age > 120_000) return 'unverified';
  const rad = Math.PI / 180;
  const latDiff = (site.latitude - fix.latitude) * rad;
  const lonDiff = (site.longitude - fix.longitude) * rad;
  const a =
    Math.sin(latDiff / 2) ** 2 +
    Math.cos(site.latitude * rad) *
      Math.cos(fix.latitude * rad) *
      Math.sin(lonDiff / 2) ** 2;
  const distance = 2 * 6371000 * Math.asin(Math.sqrt(Math.min(1, a)));
  if (distance + fix.accuracyMeters <= 300) return 'inside';
  if (distance - fix.accuracyMeters > 300) return 'outside';
  return 'unverified';
}
