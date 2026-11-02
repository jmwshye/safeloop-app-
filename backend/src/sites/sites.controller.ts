import {
  BadRequestException,
  Body,
  Controller,
  Get,
  Header,
  HttpCode,
  NotFoundException,
  Param,
  Post,
  Put,
  Req,
  Res,
  UseGuards,
} from '@nestjs/common';
import { ThrottlerGuard } from '@nestjs/throttler';
import type { Response } from 'express';
import { z } from 'zod';
import { AuthGuard, type AuthenticatedRequest } from '../auth/auth.guard.js';
import { SitesService } from './sites.service.js';
import { inputFor, profileSchema, setupSchema } from './sites.schemas.js';

const codeInput = z.strictObject({
  code: z
    .string()
    .trim()
    .max(12)
    .regex(/^[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*$/)
    .toUpperCase(),
});
const siteIdSchema = z.uuid();

function readCode(body: unknown): string {
  const input = codeInput.safeParse(body);
  if (!input.success) throw new BadRequestException();
  return input.data.code;
}

@Controller('sites')
@UseGuards(AuthGuard, ThrottlerGuard)
export class SitesController {
  constructor(private readonly sites: SitesService) {}

  @Post()
  @Header('Cache-Control', 'no-store')
  create(@Req() request: AuthenticatedRequest, @Body() body: unknown) {
    return this.sites.create(request.user.id, inputFor(setupSchema, body));
  }

  @Get('mine')
  @Header('Cache-Control', 'no-store')
  mine(@Req() request: AuthenticatedRequest) {
    return this.sites.mine(request.user.id);
  }

  @Put(':siteId/settings')
  @Header('Cache-Control', 'no-store')
  settings(
    @Param('siteId') siteId: string,
    @Req() request: AuthenticatedRequest,
    @Body() body: unknown,
  ) {
    return this.sites.configure(
      request.user.id,
      inputFor(siteIdSchema, siteId),
      inputFor(setupSchema, body),
    );
  }

  @Put(':siteId/profile')
  @Header('Cache-Control', 'no-store')
  profile(
    @Param('siteId') siteId: string,
    @Req() request: AuthenticatedRequest,
    @Body() body: unknown,
  ) {
    return this.sites.setProfile(
      inputFor(siteIdSchema, siteId),
      request.user.id,
      inputFor(profileSchema, body),
    );
  }

  @Post('lookup')
  @HttpCode(200)
  @Header('Cache-Control', 'no-store')
  async lookup(@Body() body: unknown) {
    const site = await this.sites.lookup(readCode(body));
    return { site };
  }

  @Post(':siteId/join')
  @Header('Cache-Control', 'no-store')
  async join(
    @Param('siteId') siteId: string,
    @Req() request: AuthenticatedRequest,
    @Body() body: unknown,
    @Res({ passthrough: true }) response: Response,
  ) {
    if (!siteIdSchema.safeParse(siteId).success) throw new NotFoundException();
    const result = await this.sites.join(
      siteId,
      readCode(body),
      request.user.id,
    );
    response.status(result.created ? 201 : 200);
    return { site: result.site };
  }
}
