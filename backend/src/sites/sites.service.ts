import {
  ConflictException,
  Injectable,
  NotFoundException,
} from '@nestjs/common';
import { createHash, randomBytes } from 'node:crypto';
import { and, asc, eq, or, sql } from 'drizzle-orm';
import { DatabaseService } from '../database.service.js';
import { siteMemberships, sites, users } from '../database/schema.js';
import { requireSiteForeman, requireSiteMember } from './site-access.js';
import { lockSite, reschedulePrompts } from '../attendance/shift-operations.js';
import type { SiteCreation, SiteSetup, WorkProfile } from './sites.schemas.js';

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

  async create(userId: string, input: SiteCreation) {
    return this.database.db.transaction(async (tx) => {
      const settings = siteSettings(input);
      const fingerprint = createHash('sha256')
        .update(JSON.stringify(settings))
        .digest('hex');
      if (input.requestId) {
        await tx.execute(
          sql`SELECT pg_advisory_xact_lock(hashtextextended(${userId + ':' + input.requestId.toLowerCase()}::text, 1))`,
        );
        const [prior] = await tx
          .select()
          .from(sites)
          .where(
            and(
              eq(sites.foremanUserId, userId),
              eq(sites.creationRequestId, input.requestId),
            ),
          );
        if (prior) {
          if (prior.creationFingerprint !== fingerprint)
            throw new ConflictException('같은 요청의 현장 설정이 달라요.');
          return { site: { id: prior.id, name: prior.name, code: prior.code } };
        }
      }
      let site;
      while (!site) {
        [site] = await tx
          .insert(sites)
          .values({
            ...settings,
            creationRequestId: input.requestId,
            creationFingerprint: input.requestId ? fingerprint : null,
            foremanUserId: userId,
            code: randomBytes(6).toString('hex').toUpperCase(),
          })
          .onConflictDoNothing({ target: sites.code })
          .returning({ id: sites.id, name: sites.name, code: sites.code });
      }
      await tx.insert(siteMemberships).values({ siteId: site.id, userId });
      return { site };
    });
  }

  async configure(userId: string, siteId: string, input: SiteSetup) {
    return this.database.db.transaction(async (tx) => {
      await requireSiteForeman(tx, siteId, userId);
      const previous = await lockSite(tx, siteId);
      const settings = siteSettings(input);
      const [site] = await tx
        .update(sites)
        .set(settings)
        .where(eq(sites.id, previous.id))
        .returning();
      await reschedulePrompts(tx, previous, site);
      return {
        site: {
          id: site.id,
          code: site.code,
          foremanUserId: site.foremanUserId,
          ...settings,
        },
      };
    });
  }

  async mine(userId: string) {
    const rows = await this.database.db
      .select({
        id: sites.id,
        name: sites.name,
        code: sites.code,
        latitude: sites.latitude,
        longitude: sites.longitude,
        foremanName: users.name,
        foremanId: sites.foremanUserId,
        foremanPhone: sites.foremanPhone,
        occupation: siteMemberships.occupation,
        zone: siteMemberships.zone,
        expectedWage: siteMemberships.expectedWage,
        breakReturnTime: sites.breakReturnTime,
        shiftChangeTime: sites.shiftChangeTime,
      })
      .from(sites)
      .innerJoin(users, eq(users.id, sites.foremanUserId))
      .leftJoin(
        siteMemberships,
        and(
          eq(siteMemberships.siteId, sites.id),
          eq(siteMemberships.userId, userId),
        ),
      )
      .where(
        or(eq(siteMemberships.userId, userId), eq(sites.foremanUserId, userId)),
      )
      .orderBy(asc(sites.name), asc(sites.id));
    return {
      sites: rows.map(({ latitude, longitude, ...row }) => ({
        ...row,
        center:
          latitude !== null && longitude !== null
            ? { latitude, longitude }
            : null,
        role: row.foremanId === userId ? 'foreman' : 'worker',
      })),
    };
  }

  async setProfile(siteId: string, userId: string, profile: WorkProfile) {
    return this.database.db.transaction(async (tx) => {
      await requireSiteMember(tx, siteId, userId);
      const fields = {
        occupation: profile.occupation,
        zone: profile.zone,
        expectedWage: profile.expectedWage ?? null,
      };
      await tx
        .insert(siteMemberships)
        .values({ siteId, userId, ...fields })
        .onConflictDoUpdate({
          target: [siteMemberships.siteId, siteMemberships.userId],
          set: fields,
        });
      return { profile: fields };
    });
  }

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

function siteSettings(input: SiteSetup) {
  return {
    name: input.name,
    latitude: input.center?.latitude ?? null,
    longitude: input.center?.longitude ?? null,
    foremanPhone: input.foremanPhone ?? null,
    breakReturnTime: input.breakReturnTime ?? null,
    shiftChangeTime: input.shiftChangeTime ?? null,
  };
}
