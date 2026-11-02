import type { MulticastMessage } from 'firebase-admin/messaging';
import type { PushData } from './push.types.js';

export function pushMessage(
  tokens: string[],
  data: PushData,
  ttl: number,
): MulticastMessage {
  // The Android receiver checks the signed-in recipient before showing any notice.
  return { tokens, data, android: { priority: 'high', ttl } };
}
