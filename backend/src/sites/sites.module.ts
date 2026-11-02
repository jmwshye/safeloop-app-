import { Module } from '@nestjs/common';
import { AuthModule } from '../auth/auth.module.js';
import { DatabaseModule } from '../database.module.js';
import { SitesController } from './sites.controller.js';
import { SitesService } from './sites.service.js';

@Module({
  imports: [AuthModule, DatabaseModule],
  controllers: [SitesController],
  providers: [SitesService],
})
export class SitesModule {}
