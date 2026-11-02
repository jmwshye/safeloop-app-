import {
  Module,
  type MiddlewareConsumer,
  type NestModule,
} from '@nestjs/common';
import type { Request, Response, NextFunction } from 'express';
import { APP_FILTER } from '@nestjs/core';
import { AppController } from './app.controller.js';
import { AppService } from './app.service.js';
import { DatabaseModule } from './database.module.js';
import { AuthModule } from './auth/auth.module.js';
import { ApiExceptionFilter } from './api-exception.filter.js';
import { AttendanceModule } from './attendance/attendance.module.js';
import { SitesModule } from './sites/sites.module.js';
import { RecordsModule } from './records/records.module.js';
import { IncidentModule } from './incidents/incident.module.js';
import { RisksModule } from './risks/risks.module.js';
import { HealthController } from './health.controller.js';

@Module({
  imports: [
    DatabaseModule,
    AuthModule,
    AttendanceModule,
    SitesModule,
    RecordsModule,
    IncidentModule,
    RisksModule,
  ],
  controllers: [AppController, HealthController],
  providers: [
    AppService,
    { provide: APP_FILTER, useClass: ApiExceptionFilter },
  ],
})
export class AppModule implements NestModule {
  configure(consumer: MiddlewareConsumer) {
    consumer
      .apply((_req: Request, res: Response, next: NextFunction) => {
        res.setHeader('Cache-Control', 'no-store');
        res.setHeader('X-Content-Type-Options', 'nosniff');
        next();
      })
      .forRoutes('{*path}');
  }
}
