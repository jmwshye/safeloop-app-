import {
  BadRequestException,
  Body,
  Controller,
  Delete,
  Get,
  Header,
  Param,
  Put,
  Req,
  UseGuards,
} from '@nestjs/common';
import { z } from 'zod';
import { AuthGuard, type AuthenticatedRequest } from '../auth/auth.guard.js';
import { PushDevicesService } from './push-devices.service.js';

const deviceIdSchema = z.uuid();
const registrationSchema = z
  .object({
    fcmToken: z
      .string()
      .min(1)
      .max(4096)
      .regex(/^[!-~]+$/),
  })
  .strict();

@Controller()
@UseGuards(AuthGuard)
export class NotificationsController {
  constructor(private readonly devices: PushDevicesService) {}

  @Get('me/notification-preferences')
  @Header('Cache-Control', 'no-store')
  preferences(@Req() request: AuthenticatedRequest) {
    return this.devices.preferences(request.user.id);
  }

  @Put('me/notification-preferences')
  @Header('Cache-Control', 'no-store')
  updatePreferences(
    @Req() request: AuthenticatedRequest,
    @Body() body: unknown,
  ) {
    const input = z
      .strictObject({
        emergencyPush: z.boolean().optional(),
        sitePush: z.boolean().optional(),
      })
      .refine((x) => Object.keys(x).length > 0)
      .safeParse(body);
    if (!input.success) throw new BadRequestException();
    return this.devices.setPreferences(request.user.id, input.data);
  }

  @Put('push-devices/:deviceId')
  @Header('Cache-Control', 'no-store')
  register(
    @Req() request: AuthenticatedRequest,
    @Param('deviceId') deviceId: string,
    @Body() body: unknown,
  ) {
    if (!deviceIdSchema.safeParse(deviceId).success)
      throw new BadRequestException();
    const input = registrationSchema.safeParse(body);
    if (!input.success) throw new BadRequestException();
    return this.devices.register(
      request.user.id,
      deviceId,
      input.data.fcmToken,
    );
  }

  @Delete('push-devices/:deviceId')
  @Header('Cache-Control', 'no-store')
  remove(
    @Req() request: AuthenticatedRequest,
    @Param('deviceId') deviceId: string,
  ) {
    if (!deviceIdSchema.safeParse(deviceId).success)
      throw new BadRequestException();
    return this.devices.remove(request.user.id, deviceId);
  }
}
