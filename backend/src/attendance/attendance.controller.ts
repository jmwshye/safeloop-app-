import {
  BadRequestException,
  Body,
  Controller,
  Get,
  Header,
  NotFoundException,
  Param,
  Post,
  Put,
  Query,
  Req,
  Res,
  UseGuards,
} from '@nestjs/common';
import type { Response } from 'express';
import { AuthGuard, type AuthenticatedRequest } from '../auth/auth.guard.js';
import { AttendanceService } from './attendance.service.js';
import {
  attendanceIdSchema,
  checkInSchema,
  safetyCheckSchema,
  rangeSchema,
  emptySchema,
} from './attendance.schemas.js';
import { inputFor } from '../sites/sites.schemas.js';

@Controller('attendances')
@UseGuards(AuthGuard)
export class AttendanceController {
  constructor(private readonly attendance: AttendanceService) {}

  @Post()
  @Header('Cache-Control', 'no-store')
  async checkIn(
    @Req() request: AuthenticatedRequest,
    @Body() body: unknown,
    @Res({ passthrough: true }) response: Response,
  ) {
    const result = await this.attendance.checkIn(
      request.user.id,
      inputFor(checkInSchema, body),
    );
    response.status(result.created ? 201 : 200);
    return { attendance: result.attendance };
  }

  @Get('today')
  @Header('Cache-Control', 'no-store')
  today(
    @Req() request: AuthenticatedRequest,
    @Query('siteId') siteId?: string,
  ) {
    return this.attendance.today(
      request.user.id,
      siteId === undefined ? undefined : inputFor(attendanceIdSchema, siteId),
    );
  }

  @Get('records')
  @Header('Cache-Control', 'no-store')
  records(@Req() request: AuthenticatedRequest, @Query() query: unknown) {
    const { from, to } = inputFor(rangeSchema, query);
    return this.attendance.records(request.user.id, from, to);
  }

  @Put(':id/checkout')
  @Header('Cache-Control', 'no-store')
  checkout(
    @Req() request: AuthenticatedRequest,
    @Param('id') id: string,
    @Body() body: unknown,
  ) {
    inputFor(emptySchema, body);
    return this.attendance.checkout(
      request.user.id,
      inputFor(attendanceIdSchema, id),
    );
  }

  @Put(':id/safety-check')
  @Header('Cache-Control', 'no-store')
  saveSafetyCheck(
    @Req() request: AuthenticatedRequest,
    @Param('id') id: string,
    @Body() body: unknown,
  ) {
    if (!attendanceIdSchema.safeParse(id).success)
      throw new NotFoundException();
    const input = safetyCheckSchema.safeParse(body);
    if (!input.success) throw new BadRequestException();
    return this.attendance.saveSafetyCheck(request.user.id, id, input.data);
  }
}
