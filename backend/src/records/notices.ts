import { siteNotices, type noticeKinds } from '../database/record-schema.js';
import type { Tx } from './records.js';

export async function enqueueNotice(
  tx: Tx,
  notice: {
    siteId: string;
    recipientId: string;
    kind: (typeof noticeKinds)[number];
    entityId: string;
    uniqueKey: string;
    availableAt?: Date;
  },
) {
  const availableAt = notice.availableAt ?? new Date();
  await tx
    .insert(siteNotices)
    .values({ ...notice, availableAt, nextAttemptAt: availableAt })
    .onConflictDoNothing({
      target: [
        siteNotices.siteId,
        siteNotices.recipientId,
        siteNotices.uniqueKey,
      ],
    });
}
