import { HttpException } from '@nestjs/common';
import { and, eq } from 'drizzle-orm';
import type { DatabaseService } from '../database.service.js';
import { sites, siteMemberships } from '../database/schema.js';

type Reader = Pick<DatabaseService['db'], 'select'>;

export function denySite(): never {
  throw new HttpException(
    {
      error: {
        code: 'SITE_ACCESS_DENIED',
        message: '해당 현장에 접근할 수 없어요.',
      },
    },
    403,
  );
}

export async function requireSiteMember(
  db: Reader,
  siteId: string,
  userId: string,
) {
  const [row] = await db
    .select({ site: sites, member: siteMemberships.userId })
    .from(sites)
    .leftJoin(
      siteMemberships,
      and(
        eq(siteMemberships.siteId, sites.id),
        eq(siteMemberships.userId, userId),
      ),
    )
    .where(eq(sites.id, siteId));
  if (!row || (row.member === null && row.site.foremanUserId !== userId))
    denySite();
  return row.site;
}

export async function requireSiteForeman(
  db: Reader,
  siteId: string,
  userId: string,
) {
  const [site] = await db
    .select()
    .from(sites)
    .where(and(eq(sites.id, siteId), eq(sites.foremanUserId, userId)));
  if (!site) denySite();
  return site;
}
