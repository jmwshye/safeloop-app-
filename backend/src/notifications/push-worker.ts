import { parentPort, workerData } from 'node:worker_threads';
import { cert, deleteApp, initializeApp } from 'firebase-admin/app';
import { getMessaging } from 'firebase-admin/messaging';
import type { PushJob, PushProgress, PushResult } from './push.types.js';
import { pushMessage } from './push-message.js';

const job = workerData as PushJob;
const invalidTokenCodes = new Set([
  'messaging/invalid-registration-token',
  'messaging/registration-token-not-registered',
]);

async function send(): Promise<PushResult> {
  const app = initializeApp({
    credential: cert(job.credentialsPath),
    projectId: job.projectId,
  });
  try {
    let accepted = false;
    const invalidTokens: string[] = [];
    for (let offset = 0; offset < job.tokens.length; offset += 500) {
      const tokens = job.tokens.slice(offset, offset + 500);
      const ttl = job.expiresAt - Date.now();
      if (ttl <= 0) break;
      const response = await getMessaging(app).sendEachForMulticast(
        pushMessage(tokens, job.data, ttl),
      );
      accepted ||= response.successCount > 0;
      response.responses.forEach((result, index) => {
        if (result.error && invalidTokenCodes.has(result.error.code)) {
          invalidTokens.push(tokens[index]);
        }
      });
      parentPort?.postMessage({
        done: false,
        result: { accepted, invalidTokens },
      } satisfies PushProgress);
    }
    return { accepted, invalidTokens };
  } finally {
    await deleteApp(app);
  }
}

try {
  parentPort?.postMessage({
    done: true,
    result: await send(),
  } satisfies PushProgress);
} catch {
  // SDK 예외에는 토큰·인증 정보가 들어갈 수 있으므로 작업 결과만 전달한다.
  parentPort?.postMessage({
    done: true,
    result: { accepted: false, invalidTokens: [] },
  } satisfies PushProgress);
}
