import { Module } from '@nestjs/common';
import { AuthModule } from '../auth/auth.module.js';
import { DatabaseModule } from '../database.module.js';
import { NotificationsModule } from '../notifications/notifications.module.js';
import { DashboardService } from './dashboard.service.js';
import { NoticesWorker } from './notices.worker.js';
import { PhotosService } from './photos.service.js';
import { RecordsController } from './records.controller.js';
import { RecordsService } from './records.service.js';

@Module({
  imports: [AuthModule, DatabaseModule, NotificationsModule],
  controllers: [RecordsController],
  providers: [DashboardService, NoticesWorker, PhotosService, RecordsService],
})
export class RecordsModule {}
