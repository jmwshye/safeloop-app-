import { Module } from '@nestjs/common';
import { AuthModule } from '../auth/auth.module.js';
import { DatabaseModule } from '../database.module.js';
import { AttendanceController } from './attendance.controller.js';
import { AttendanceService } from './attendance.service.js';
import { IncidentModule } from '../incidents/incident.module.js';
import {
  WorkActionsController,
  SiteTeamController,
} from './workflow.controller.js';
import { WorkflowService } from './workflow.service.js';

@Module({
  imports: [AuthModule, DatabaseModule, IncidentModule],
  controllers: [
    AttendanceController,
    WorkActionsController,
    SiteTeamController,
  ],
  providers: [AttendanceService, WorkflowService],
})
export class AttendanceModule {}
