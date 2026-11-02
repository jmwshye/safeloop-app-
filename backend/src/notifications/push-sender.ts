import {
  Injectable,
  Logger,
  type BeforeApplicationShutdown,
} from '@nestjs/common';
import { Worker } from 'node:worker_threads';
import type {
  PushData,
  PushJob,
  PushProgress,
  PushResult,
} from './push.types.js';

const failedResult = (): PushResult => ({ accepted: false, invalidTokens: [] });

@Injectable()
export class PushSender implements BeforeApplicationShutdown {
  private readonly logger = new Logger(PushSender.name);
  private readonly workers = new Set<Worker>();
  private stopped = false;
  private warnedUnconfigured = false;

  get configured(): boolean {
    return Boolean(
      process.env.GOOGLE_APPLICATION_CREDENTIALS?.trim() &&
      process.env.FIREBASE_PROJECT_ID?.trim(),
    );
  }

  private createWorker(job: PushJob): Worker {
    return new Worker(new URL('./push-worker.js', import.meta.url), {
      workerData: job,
    });
  }

  private timeoutMs(): number {
    return 4_000;
  }

  async send(
    tokens: string[],
    data: PushData,
    ttlMs = 60_000,
  ): Promise<PushResult> {
    if (!tokens.length || this.stopped || ttlMs <= 0) return failedResult();
    if (!this.configured) {
      if (!this.warnedUnconfigured) {
        this.warnedUnconfigured = true;
        this.logger.warn('FCM 설정이 없어 푸시를 발송하지 못했습니다.');
      }
      return failedResult();
    }
    let worker: Worker;
    try {
      worker = this.createWorker({
        credentialsPath: process.env.GOOGLE_APPLICATION_CREDENTIALS!.trim(),
        projectId: process.env.FIREBASE_PROJECT_ID!.trim(),
        tokens: [...new Set(tokens)],
        expiresAt: Date.now() + ttlMs,
        data: {
          eventId: data.eventId,
          type: data.type,
          ...(data.incidentId && { incidentId: data.incidentId }),
          ...(data.siteId && { siteId: data.siteId }),
          ...(data.entityId && { entityId: data.entityId }),
        },
      });
    } catch {
      return failedResult();
    }
    this.workers.add(worker);
    return new Promise<PushResult>((resolve) => {
      let finished = false;
      let accepted = false;
      const invalidTokens = new Set<string>();
      const finish = () => {
        if (finished) return;
        finished = true;
        clearTimeout(timer);
        // SDK 내부 재시도까지 종료해 시간 초과 후 새 발송이 이어지지 않게 한다.
        const terminated = () => {
          this.workers.delete(worker);
          resolve({ accepted, invalidTokens: [...invalidTokens] });
        };
        void worker.terminate().then(terminated, terminated);
      };
      const timer = setTimeout(finish, this.timeoutMs());
      worker.on('message', ({ done, result }: PushProgress) => {
        if (finished) return;
        accepted ||= result.accepted;
        result.invalidTokens.forEach((token) => invalidTokens.add(token));
        if (done) finish();
      });
      worker.once('error', finish);
      worker.once('exit', finish);
    });
  }

  async beforeApplicationShutdown() {
    this.stopped = true;
    await Promise.allSettled(
      [...this.workers].map((worker) => worker.terminate()),
    );
  }
}
