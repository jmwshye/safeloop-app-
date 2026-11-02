import {
  Injectable,
  Logger,
  type OnModuleInit,
  type BeforeApplicationShutdown,
} from '@nestjs/common';
import { IncidentService } from './incident.service.js';

@Injectable()
export class IncidentWorker implements OnModuleInit, BeforeApplicationShutdown {
  private readonly logger = new Logger(IncidentWorker.name);
  private timer?: NodeJS.Timeout;
  private dueWork?: Promise<void>;
  private pushWork?: Promise<void>;

  constructor(private readonly incidents: IncidentService) {}

  onModuleInit() {
    this.timer = setInterval(() => this.tick(), 1000);
    this.timer.unref();
    this.tick();
  }

  private tick() {
    // 외부 푸시가 늦어져도 본인 응답·재알림 기한 처리는 계속 진행한다.
    this.dueWork ??= this.incidents
      .processDue()
      .catch(() => {
        this.logger.error(
          '긴급 요청 기한 처리 실패. 다음 주기에 재시도합니다.',
        );
      })
      .finally(() => {
        this.dueWork = undefined;
      });
    this.pushWork ??= this.incidents
      .dispatchPending()
      .catch(() => {
        this.logger.error('긴급 알림 처리 실패. 다음 주기에 재시도합니다.');
      })
      .finally(() => {
        this.pushWork = undefined;
      });
  }

  async beforeApplicationShutdown() {
    if (this.timer) clearInterval(this.timer);
    this.incidents.stopDispatch();
    await Promise.allSettled([this.dueWork, this.pushWork]);
  }
}
