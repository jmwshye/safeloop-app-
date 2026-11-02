import {
  BadRequestException,
  Body,
  Controller,
  Get,
  Header,
  HttpCode,
  Param,
  Post,
  Put,
  Query,
  Req,
  Res,
  UseGuards,
} from '@nestjs/common';
import type { Response } from 'express';
import { z } from 'zod';
import { AuthGuard, type AuthenticatedRequest } from '../auth/auth.guard.js';
import { IncidentError } from './incident.errors.js';
import { IncidentService } from './incident.service.js';
import {
  createIncidentSchema,
  dialSchema,
  emptySchema,
  observeSchema,
  receiptSchema,
  resolutionSchema,
  workerReplySchema,
} from './incident.schemas.js';

function idValue(id: string) {
  if (!z.uuid().safeParse(id).success)
    throw new IncidentError('INCIDENT_NOT_FOUND');
  return id;
}

@Controller('sites/:siteId/dial-intents')
@UseGuards(AuthGuard)
export class SiteDialController {
  constructor(private readonly incidents: IncidentService) {}
  @Post()
  @HttpCode(200)
  @Header('Cache-Control', 'no-store')
  record(
    @Req() req: AuthenticatedRequest,
    @Param('siteId') siteId: string,
    @Body() body: unknown,
  ) {
    return this.incidents.siteDial(
      idValue(siteId),
      req.user.id,
      input(dialSchema, body),
    );
  }
}
function input<S extends z.ZodType>(schema: S, body: unknown): z.output<S> {
  const result = schema.safeParse(body);
  if (!result.success) throw new BadRequestException();
  return result.data;
}

@Controller('incidents')
@UseGuards(AuthGuard)
export class IncidentController {
  constructor(private readonly incidents: IncidentService) {}
  @Post()
  @Header('Cache-Control', 'no-store')
  async create(
    @Req() req: AuthenticatedRequest,
    @Body() body: unknown,
    @Res({ passthrough: true }) res: Response,
  ) {
    const result = await this.incidents.create(
      req.user.id,
      input(createIncidentSchema, body),
    );
    res.status(result.created ? 201 : 200);
    return { incident: result.incident };
  }
  @Get('active')
  @Header('Cache-Control', 'no-store')
  active(@Req() req: AuthenticatedRequest, @Query('siteId') siteId?: string) {
    return this.incidents.active(
      req.user.id,
      siteId === undefined ? undefined : idValue(siteId),
    );
  }
  @Get(':id')
  @Header('Cache-Control', 'no-store')
  detail(@Req() req: AuthenticatedRequest, @Param('id') id: string) {
    return this.incidents.detail(req.user.id, idValue(id));
  }
  @Put(':id/worker-response')
  @Header('Cache-Control', 'no-store')
  respond(
    @Req() req: AuthenticatedRequest,
    @Param('id') id: string,
    @Body() body: unknown,
  ) {
    return this.incidents.workerReply(
      req.user.id,
      idValue(id),
      input(workerReplySchema, body),
    );
  }
  @Put(':id/receipt')
  @Header('Cache-Control', 'no-store')
  receipt(
    @Req() req: AuthenticatedRequest,
    @Param('id') id: string,
    @Body() body: unknown,
  ) {
    return this.incidents.receipt(
      req.user.id,
      idValue(id),
      input(receiptSchema, body),
    );
  }
  @Put(':id/accept')
  @Header('Cache-Control', 'no-store')
  accept(
    @Req() req: AuthenticatedRequest,
    @Param('id') id: string,
    @Body() body: unknown,
  ) {
    input(emptySchema, body);
    return this.incidents.answerCall(req.user.id, idValue(id), true);
  }
  @Put(':id/decline')
  @Header('Cache-Control', 'no-store')
  decline(
    @Req() req: AuthenticatedRequest,
    @Param('id') id: string,
    @Body() body: unknown,
  ) {
    input(emptySchema, body);
    return this.incidents.answerCall(req.user.id, idValue(id), false);
  }
  @Put(':id/arrival')
  @Header('Cache-Control', 'no-store')
  arrive(
    @Req() req: AuthenticatedRequest,
    @Param('id') id: string,
    @Body() body: unknown,
  ) {
    input(emptySchema, body);
    return this.incidents.arrive(req.user.id, idValue(id));
  }
  @Put(':id/observation')
  @Header('Cache-Control', 'no-store')
  observe(
    @Req() req: AuthenticatedRequest,
    @Param('id') id: string,
    @Body() body: unknown,
  ) {
    return this.incidents.observe(
      req.user.id,
      idValue(id),
      input(observeSchema, body).condition,
    );
  }
  @Put(':id/dial-intent')
  @Header('Cache-Control', 'no-store')
  dial(
    @Req() req: AuthenticatedRequest,
    @Param('id') id: string,
    @Body() body: unknown,
  ) {
    return this.incidents.dial(
      req.user.id,
      idValue(id),
      input(dialSchema, body),
    );
  }
  @Put(':id/resolution')
  @Header('Cache-Control', 'no-store')
  close(
    @Req() req: AuthenticatedRequest,
    @Param('id') id: string,
    @Body() body: unknown,
  ) {
    return this.incidents.close(
      req.user.id,
      idValue(id),
      input(resolutionSchema, body),
    );
  }
  @Put(':id/retry')
  @Header('Cache-Control', 'no-store')
  retry(
    @Req() req: AuthenticatedRequest,
    @Param('id') id: string,
    @Body() body: unknown,
  ) {
    input(emptySchema, body);
    return this.incidents.retry(req.user.id, idValue(id));
  }
}
