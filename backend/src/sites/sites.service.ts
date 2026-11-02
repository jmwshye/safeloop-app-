import { Injectable, NotFoundException } from '@nestjs/common';
import { and, eq } from 'drizzle-orm';
import { DatabaseService } from '../database.service.js';
import { siteMemberships, sites, users } from '../database/schema.js';

function siteByCode(
  db: Pick<DatabaseService['db'], 'select'>,
  code: string,
  siteId?: string,
) {
  return db
    .select({ id: sites.id, name: sites.name, foremanName: users.name })
    .from(sites)
    .innerJoin(users, eq(users.id, sites.foremanUserId))
    .where(and(eq(sites.code, code), siteId ? eq(sites.id, siteId) : undefined))
    .limit(1);
}

@Injectable()
export class SitesService {
  constructor(private readonly database: DatabaseService) {}

  async lookup(code: string) {
    const [site] = await siteByCode(this.database.db, code);
    if (!site) throw new NotFoundException();
    return site;
  }

  async join(siteId: string, code: string, userId: string) {
    return this.database.db.transaction(async (tx) => {
      // 참여를 저장하는 동안 현장 코드가 교체되지 않도록 한다.
      const [site] = await siteByCode(tx, code, siteId).for('share', {
        of: sites,
      });
      if (!site) throw new NotFoundException();
      const memberships = await tx
        .insert(siteMemberships)
        .values({ siteId: site.id, userId })
        .onConflictDoNothing({
          target: [siteMemberships.siteId, siteMemberships.userId],
        })
        .returning({ siteId: siteMemberships.siteId });
      return { site, created: memberships.length > 0 };
    });
  }
}
