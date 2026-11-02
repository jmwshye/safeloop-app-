import { Module } from '@nestjs/common';
import { AuthModule } from '../auth/auth.module.js';
import { DatabaseModule } from '../database.module.js';
import { RisksController } from './risks.controller.js';
import { RisksService } from './risks.service.js';

@Module({
  imports: [DatabaseModule, AuthModule],
  controllers: [RisksController],
  providers: [RisksService],
})
export class RisksModule {}
