import type { MulticastMessage } from 'firebase-admin/messaging';
import type { PushData } from './push.types.js';

export function pushMessage(
  tokens: string[],
  data: PushData,
  ttl: number,
): MulticastMessage {
  const android = { priority: 'high', ttl } as const;
  // The Kotlin service displays the alert and acknowledges reception. A notification
  // payload would send background calls directly to Android's system tray instead.
  if (data.incidentId) return { tokens, data, android };
  return {
    tokens,
    data,
    notification: {
      title: 'SAFELOOP 안전 알림',
      body: '앱에서 요청 상태를 확인해 주세요.',
    },
    android: { ...android, notification: { tag: data.eventId } },
  };
}
