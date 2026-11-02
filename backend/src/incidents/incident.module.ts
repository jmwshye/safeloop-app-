import { Module } from '@nestjs/common';
import { AuthModule } from '../auth/auth.module.js';
import { DatabaseModule } from '../database.module.js';
import { NotificationsModule } from '../notifications/notifications.module.js';
import {
  IncidentController,
  SiteDialController,
} from './incident.controller.js';
import { IncidentService } from './incident.service.js';
import { IncidentWorker } from './incident.worker.js';

@Module({
  imports: [DatabaseModule, AuthModule, NotificationsModule],
  controllers: [IncidentController, SiteDialController],
  providers: [IncidentService, IncidentWorker],
  exports: [IncidentService],
})
export class IncidentModule {}
