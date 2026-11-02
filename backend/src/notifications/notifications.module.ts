import { Module } from '@nestjs/common';
import { AuthModule } from '../auth/auth.module.js';
import { DatabaseModule } from '../database.module.js';
import { NotificationsController } from './notifications.controller.js';
import { PushDevicesService } from './push-devices.service.js';
import { PushSender } from './push-sender.js';

@Module({
  imports: [DatabaseModule, AuthModule],
  controllers: [NotificationsController],
  providers: [PushDevicesService, PushSender],
  exports: [PushDevicesService, PushSender],
})
export class NotificationsModule {}
