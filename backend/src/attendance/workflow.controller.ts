import {
  Body,
  Controller,
  Get,
  HttpCode,
  Param,
  Post,
  Put,
  Req,
  UseGuards,
} from '@nestjs/common';
import { z } from 'zod';
import { AuthGuard, type AuthenticatedRequest } from '../auth/auth.guard.js';
import { inputFor } from '../sites/sites.schemas.js';
import {
  attendanceIdSchema,
  checkPeerSchema,
  deviceSchema,
  emptySchema,
  highRiskSchema,
  locationSchema,
  pairSchema,
  replySchema,
} from './attendance.schemas.js';
import { AttendanceService } from './attendance.service.js';
import { WorkflowService } from './workflow.service.js';

const id = (value: string) => inputFor(attendanceIdSchema, value);
const locationInput = z.strictObject({ location: locationSchema.nullish() });

@Controller('attendances')
@UseGuards(AuthGuard)
export class WorkActionsController {
  constructor(private readonly work: WorkflowService) {}
  @Get(':id/context')
  context(@Req() r: AuthenticatedRequest, @Param('id') value: string) {
    return this.work.context(r.user.id, id(value));
  }
  @Put(':id/device-status')
  device(
    @Req() r: AuthenticatedRequest,
    @Param('id') value: string,
    @Body() body: unknown,
  ) {
    return this.work.device(r.user.id, id(value), inputFor(deviceSchema, body));
  }
  @Put(':id/high-risk')
  highRisk(
    @Req() r: AuthenticatedRequest,
    @Param('id') value: string,
    @Body() body: unknown,
  ) {
    return this.work.highRisk(
      r.user.id,
      id(value),
      inputFor(highRiskSchema, body).task,
    );
  }
  @Put(':id/peer-check')
  peer(
    @Req() r: AuthenticatedRequest,
    @Param('id') value: string,
    @Body() body: unknown,
  ) {
    return this.work.checkPeer(
      r.user.id,
      id(value),
      inputFor(checkPeerSchema, body),
    );
  }
  @Put(':id/check-ins/:promptId')
  reply(
    @Req() r: AuthenticatedRequest,
    @Param('id') value: string,
    @Param('promptId') prompt: string,
    @Body() body: unknown,
  ) {
    return this.work.answerPrompt(
      r.user.id,
      id(value),
      id(prompt),
      inputFor(replySchema, body).response,
    );
  }
}

@Controller('sites/:siteId')
@UseGuards(AuthGuard)
export class SiteTeamController {
  constructor(
    private readonly attendance: AttendanceService,
    private readonly work: WorkflowService,
  ) {}
  @Post('check-location')
  @HttpCode(200)
  verify(
    @Req() r: AuthenticatedRequest,
    @Param('siteId') site: string,
    @Body() body: unknown,
  ) {
    return this.attendance.verifyLocation(
      r.user.id,
      id(site),
      inputFor(locationInput, body).location,
    );
  }
  @Get('workers')
  workers(@Req() r: AuthenticatedRequest, @Param('siteId') site: string) {
    return this.work.workers(id(site), r.user.id);
  }
  @Put('buddy-assignment')
  pair(
    @Req() r: AuthenticatedRequest,
    @Param('siteId') site: string,
    @Body() body: unknown,
  ) {
    const pair = inputFor(pairSchema, body);
    return this.work.reassign(id(site), r.user.id, pair.workerId, pair.buddyId);
  }
  @Get('safety-requests')
  requests(@Req() r: AuthenticatedRequest, @Param('siteId') site: string) {
    return this.work.reviews(id(site), r.user.id);
  }
  @Put('safety-requests/:id/acknowledgement')
  ack(
    @Req() r: AuthenticatedRequest,
    @Param('siteId') site: string,
    @Param('id') value: string,
    @Body() body: unknown,
  ) {
    inputFor(emptySchema, body);
    return this.work.acknowledge(id(site), r.user.id, id(value));
  }
}
