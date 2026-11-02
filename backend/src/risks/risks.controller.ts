import {
  Body,
  Controller,
  Get,
  Header,
  Param,
  ParseUUIDPipe,
  Post,
  Put,
  Query,
  Req,
  Res,
  UseGuards,
} from '@nestjs/common';
import type { Response } from 'express';
import { parseInput as parse } from '../records/records.js';
import { AuthGuard, type AuthenticatedRequest } from '../auth/auth.guard.js';
import {
  emptyInput,
  fixInput,
  requestInput,
  riskInput,
  riskQuery,
} from './risk.schemas.js';
import { RisksService } from './risks.service.js';

@Controller('sites/:siteId/risks')
@UseGuards(AuthGuard)
export class RisksController {
  constructor(private readonly service: RisksService) {}

  @Get()
  @Header('Cache-Control', 'private, no-store')
  list(
    @Req() r: AuthenticatedRequest,
    @Param('siteId', ParseUUIDPipe) siteId: string,
    @Query() query: unknown,
  ) {
    return this.service.list(r.user.id, siteId, parse(riskQuery, query));
  }
  @Post()
  async create(
    @Req() r: AuthenticatedRequest,
    @Param('siteId', ParseUUIDPipe) siteId: string,
    @Body() body: unknown,
    @Res({ passthrough: true }) res: Response,
  ) {
    const result = await this.service.create(
      r.user.id,
      siteId,
      parse(riskInput, body),
    );
    res.status(result.created ? 201 : 200);
    return { risk: result.risk };
  }
  @Get(':id')
  @Header('Cache-Control', 'private, no-store')
  detail(
    @Req() r: AuthenticatedRequest,
    @Param('siteId', ParseUUIDPipe) siteId: string,
    @Param('id', ParseUUIDPipe) id: string,
  ) {
    return this.service.detail(r.user.id, siteId, id);
  }
  @Put(':id/request')
  requestFix(
    @Req() r: AuthenticatedRequest,
    @Param('siteId', ParseUUIDPipe) siteId: string,
    @Param('id', ParseUUIDPipe) id: string,
    @Body() body: unknown,
  ) {
    return this.service.requestFix(
      r.user.id,
      siteId,
      id,
      parse(requestInput, body).assigneeId,
    );
  }
  @Put(':id/fix')
  fix(
    @Req() r: AuthenticatedRequest,
    @Param('siteId', ParseUUIDPipe) siteId: string,
    @Param('id', ParseUUIDPipe) id: string,
    @Body() body: unknown,
  ) {
    return this.service.fix(
      r.user.id,
      siteId,
      id,
      parse(fixInput, body).afterPhotoId,
    );
  }
  @Put(':id/recheck')
  recheck(
    @Req() r: AuthenticatedRequest,
    @Param('siteId', ParseUUIDPipe) siteId: string,
    @Param('id', ParseUUIDPipe) id: string,
    @Body() body: unknown,
  ) {
    parse(emptyInput, body);
    return this.service.recheck(r.user.id, siteId, id);
  }
}
