import { ConflictException, Injectable } from '@nestjs/common';
import { and, eq, or, sql } from 'drizzle-orm';
import { DatabaseService } from '../database.service.js';
import {
  pushDevices,
  notificationPreferences,
} from '../database/incident-schema.js';

@Injectable()
export class PushDevicesService {
  constructor(private readonly database: DatabaseService) {}

  async preferences(userId: string) {
    const [row] = await this.database.db
      .select()
      .from(notificationPreferences)
      .where(eq(notificationPreferences.userId, userId));
    return {
      emergencyPush: row?.emergencyPush ?? true,
      sitePush: row?.sitePush ?? true,
    };
  }

  async setPreferences(
    userId: string,
    preferences: { emergencyPush?: boolean; sitePush?: boolean },
  ) {
    const [row] = await this.database.db
      .insert(notificationPreferences)
      .values({ userId, ...preferences })
      .onConflictDoUpdate({
        target: notificationPreferences.userId,
        set: preferences,
      })
      .returning({
        emergencyPush: notificationPreferences.emergencyPush,
        sitePush: notificationPreferences.sitePush,
      });
    return row;
  }

  async register(userId: string, id: string, fcmToken: string) {
    await this.database.db.transaction(async (tx) => {
      // 기기·토큰의 계정 전환을 한 번에 처리해 서로 다른 소유자로 갈라지지 않게 한다.
      await tx.execute(
        sql`SELECT pg_advisory_xact_lock(hashtextextended('safeloop-push-devices', 0))`,
      );
      const [current] = await tx
        .select()
        .from(pushDevices)
        .where(eq(pushDevices.id, id));
      if (current?.userId === userId && current.fcmToken === fcmToken) return;
      if (
        current &&
        current.userId !== userId &&
        current.fcmToken !== fcmToken
      ) {
        throw new ConflictException();
      }
      await tx
        .delete(pushDevices)
        .where(or(eq(pushDevices.id, id), eq(pushDevices.fcmToken, fcmToken)));
      await tx
        .insert(pushDevices)
        .values({ id, userId, fcmToken, updatedAt: new Date() });
    });
    return { device: { id } };
  }

  async remove(userId: string, id: string) {
    await this.database.db.transaction(async (tx) => {
      await tx.execute(
        sql`SELECT pg_advisory_xact_lock(hashtextextended('safeloop-push-devices', 0))`,
      );
      await tx
        .delete(pushDevices)
        .where(and(eq(pushDevices.id, id), eq(pushDevices.userId, userId)));
    });
    return { removed: true };
  }
}
