import {
  Controller,
  Get,
  Header,
  Param,
  ParseUUIDPipe,
  Query,
  Req,
  Res,
  UseGuards,
} from '@nestjs/common';
import type { Response } from 'express';
import { parseInput as parse } from '../records/records.js';
import { AuthGuard, type AuthenticatedRequest } from '../auth/auth.guard.js';
import {
  workPeriod,
  incidentQuery,
  incidentListQuery,
  workQuery,
  monthlyQuery,
  makeCsv,
  makePdf,
  type ReportFile,
} from './report-formats.js';
import { ReportsService } from './reports.service.js';

async function respond(
  res: Response,
  format: 'json' | 'csv' | 'pdf',
  name: string,
  result: { data: unknown; file: ReportFile },
) {
  res.setHeader('Cache-Control', 'private, no-store');
  if (format === 'json') {
    res.json(result.data);
    return;
  }
  const bytes =
    format === 'pdf' ? await makePdf(result.file) : makeCsv(result.file);
  res
    .type(format === 'pdf' ? 'application/pdf' : 'text/csv; charset=utf-8')
    .attachment('safeloop-' + name + '.' + format)
    .send(bytes);
}

@Controller()
@UseGuards(AuthGuard)
export class ReportsController {
  constructor(private readonly service: ReportsService) {}

  @Get('reports/work')
  async myWork(
    @Req() r: AuthenticatedRequest,
    @Query() input: unknown,
    @Res() res: Response,
  ) {
    const q = parse(workQuery, input);
    const report = await this.service.work(
      r.user.id,
      workPeriod(q),
      undefined,
      q.userId,
      q.scope,
    );
    await respond(res, q.format, 'work', report);
  }
  @Get('sites/:siteId/reports/work')
  async worker(
    @Req() r: AuthenticatedRequest,
    @Param('siteId', ParseUUIDPipe) siteId: string,
    @Query() input: unknown,
    @Res() res: Response,
  ) {
    const q = parse(workQuery, input);
    const report = await this.service.work(
      r.user.id,
      workPeriod(q),
      siteId,
      q.userId,
      q.scope,
    );
    await respond(res, q.format, 'work', report);
  }
  @Get('sites/:siteId/reports/monthly')
  async monthly(
    @Req() r: AuthenticatedRequest,
    @Param('siteId', ParseUUIDPipe) siteId: string,
    @Query() input: unknown,
    @Res() res: Response,
  ) {
    const q = parse(monthlyQuery, input);
    const report = await this.service.monthly(r.user.id, siteId, workPeriod(q));
    await respond(res, q.format, 'monthly', report);
  }
  @Get('sites/:siteId/reports/incidents')
  @Header('Cache-Control', 'private, no-store')
  listIncidents(
    @Req() r: AuthenticatedRequest,
    @Param('siteId', ParseUUIDPipe) siteId: string,
    @Query() input: unknown,
  ) {
    const q = parse(incidentListQuery, input);
    return this.service.incidentList(
      r.user.id,
      siteId,
      workPeriod(q),
      q.limit,
      q.offset,
    );
  }
  @Get('sites/:siteId/reports/incidents/:id')
  async incident(
    @Req() r: AuthenticatedRequest,
    @Param('siteId', ParseUUIDPipe) siteId: string,
    @Param('id', ParseUUIDPipe) id: string,
    @Query() input: unknown,
    @Res() res: Response,
  ) {
    const q = parse(incidentQuery, input);
    const report = await this.service.incident(
      r.user.id,
      siteId,
      id,
      q.format === 'pdf',
    );
    await respond(res, q.format, 'incident', report);
  }
}
