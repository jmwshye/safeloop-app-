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
} from './attendance.schemas.js';

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
    if (!checkInSchema.safeParse(body).success) throw new BadRequestException();
    const result = await this.attendance.checkIn(request.user.id);
    response.status(result.created ? 201 : 200);
    return { attendance: result.attendance };
  }

  @Get('today')
  @Header('Cache-Control', 'no-store')
  today(@Req() request: AuthenticatedRequest) {
    return this.attendance.today(request.user.id);
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
