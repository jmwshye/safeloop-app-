import {
  BadRequestException,
  Body,
  Controller,
  Delete,
  Get,
  Header,
  HttpCode,
  Param,
  Post,
  Put,
  Query,
  Req,
  Res,
  UploadedFile,
  UseGuards,
  UseInterceptors,
} from '@nestjs/common';
import { FileInterceptor } from '@nestjs/platform-express';
import type { Response } from 'express';
import { and, desc, eq, lte, ne } from 'drizzle-orm';
import { z } from 'zod';
import { AuthGuard, type AuthenticatedRequest } from '../auth/auth.guard.js';
import { DatabaseService } from '../database.service.js';
import { photoPurposes, siteNotices } from '../database/record-schema.js';
import { parseInput, siteAccess, uuidInput } from './records.js';
import { RecordsService } from './records.service.js';
import { PhotosService } from './photos.service.js';
import { DashboardService } from './dashboard.service.js';

const timelineQuery = z.strictObject({
  day: z.iso.date().optional(),
  before: z.coerce.number().int().positive().optional(),
  limit: z.coerce.number().int().min(1).max(100).default(50),
  scope: z.enum(['self', 'site']).optional(),
});
const note = z.string().trim().min(1).max(1000);

@Controller('sites/:siteId')
@UseGuards(AuthGuard)
export class RecordsController {
  constructor(
    private readonly records: RecordsService,
    private readonly media: PhotosService,
    private readonly dashboard: DashboardService,
    private readonly db: DatabaseService,
  ) {}
  @Get('dashboard')
  overview(@Param('siteId') site: string, @Req() req: AuthenticatedRequest) {
    return this.dashboard.read(site, req.user.id);
  }
  @Get('timeline')
  timeline(
    @Param('siteId') site: string,
    @Req() req: AuthenticatedRequest,
    @Query() query: unknown,
  ) {
    return this.records.timeline(
      site,
      req.user.id,
      parseInput(timelineQuery, query),
    );
  }
  @Get('timeline/verify')
  verify(@Param('siteId') site: string, @Req() req: AuthenticatedRequest) {
    return this.records.verify(site, req.user.id);
  }
  @Post('timeline/:eventId/corrections')
  async correct(
    @Param('siteId') site: string,
    @Param('eventId') id: string,
    @Body() body: unknown,
    @Req() req: AuthenticatedRequest,
  ) {
    const input = parseInput(
      z.strictObject({ requestId: z.uuid(), note }),
      body,
    );
    return {
      correction: await this.records.requestCorrection(
        site,
        req.user.id,
        parseInput(uuidInput, id),
        input.requestId,
        input.note,
      ),
    };
  }
  @Get('corrections')
  async listCorrections(
    @Param('siteId') site: string,
    @Req() req: AuthenticatedRequest,
  ) {
    return { corrections: await this.records.corrections(site, req.user.id) };
  }
  @Put('corrections/:id/review')
  async review(
    @Param('siteId') site: string,
    @Param('id') id: string,
    @Body() body: unknown,
    @Req() req: AuthenticatedRequest,
  ) {
    const input = parseInput(
      z.strictObject({ status: z.enum(['accepted', 'declined']), note }),
      body,
    );
    return {
      correction: await this.records.review(
        site,
        req.user.id,
        parseInput(uuidInput, id),
        input.status,
        input.note,
      ),
    };
  }
  @Post('photos')
  @UseInterceptors(
    FileInterceptor('file', {
      limits: {
        files: 1,
        fileSize: 5 * 1024 * 1024,
        fields: 1,
        fieldSize: 50,
        parts: 3,
      },
    }),
  )
  async upload(
    @Param('siteId') site: string,
    @Req() req: AuthenticatedRequest,
    @Body() body: unknown,
    @UploadedFile() file?: { buffer: Buffer },
  ) {
    const input = parseInput(
      z.strictObject({ purpose: z.enum(photoPurposes) }),
      body,
    );
    if (!file?.buffer?.length) throw new BadRequestException();
    return {
      photo: await this.media.upload(
        site,
        req.user.id,
        input.purpose,
        file.buffer,
      ),
    };
  }
  @Get('photos/:id')
  @Header('X-Content-Type-Options', 'nosniff')
  async readPhoto(
    @Param('siteId') site: string,
    @Param('id') id: string,
    @Req() req: AuthenticatedRequest,
    @Res() response: Response,
  ) {
    const bytes = await this.media.read(
      site,
      req.user.id,
      parseInput(uuidInput, id),
    );
    response.type('image/jpeg').send(bytes);
  }
  @Delete('photos/:id')
  @HttpCode(204)
  discard(
    @Param('siteId') site: string,
    @Param('id') id: string,
    @Req() req: AuthenticatedRequest,
  ) {
    return this.media.discard(site, req.user.id, parseInput(uuidInput, id));
  }
  @Get('notices')
  async notices(
    @Param('siteId') site: string,
    @Req() req: AuthenticatedRequest,
  ) {
    await siteAccess(this.db.db, site, req.user.id);
    const items = await this.db.db
      .select({
        id: siteNotices.id,
        kind: siteNotices.kind,
        entityId: siteNotices.entityId,
        availableAt: siteNotices.availableAt,
        pushStatus: siteNotices.status,
      })
      .from(siteNotices)
      .where(
        and(
          eq(siteNotices.siteId, site),
          eq(siteNotices.recipientId, req.user.id),
          lte(siteNotices.availableAt, new Date()),
          ne(siteNotices.status, 'cancelled'),
        ),
      )
      .orderBy(desc(siteNotices.availableAt))
      .limit(100);
    return { notices: items };
  }
}
