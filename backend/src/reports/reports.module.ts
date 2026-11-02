import { Module } from '@nestjs/common';
import { AuthModule } from '../auth/auth.module.js';
import { DatabaseModule } from '../database.module.js';
import { IncidentModule } from '../incidents/incident.module.js';
import { ReportsController } from './reports.controller.js';
import { ReportsService } from './reports.service.js';

@Module({
  imports: [DatabaseModule, AuthModule, IncidentModule],
  controllers: [ReportsController],
  providers: [ReportsService],
})
export class ReportsModule {}
