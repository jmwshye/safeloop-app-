import {
  BadRequestException,
  Body,
  Controller,
  Header,
  HttpCode,
  NotFoundException,
  Param,
  Post,
  Req,
  Res,
  UseGuards,
} from '@nestjs/common';
import { ThrottlerGuard } from '@nestjs/throttler';
import type { Response } from 'express';
import { z } from 'zod';
import { AuthGuard, type AuthenticatedRequest } from '../auth/auth.guard.js';
import { SitesService } from './sites.service.js';

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
