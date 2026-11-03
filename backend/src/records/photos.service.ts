import {
  BadRequestException,
  ForbiddenException,
  Injectable,
  NotFoundException,
} from '@nestjs/common';
import { and, eq, isNotNull, isNull, lt, sql } from 'drizzle-orm';
import sharp from 'sharp';
import { DatabaseService } from '../database.service.js';
import { photos, type photoPurposes } from '../database/record-schema.js';
import { siteAccess } from './records.js';

@Injectable()
export class PhotosService {
  constructor(private readonly database: DatabaseService) {}

  async upload(
    siteId: string,
    ownerId: string,
    purpose: (typeof photoPurposes)[number],
    bytes: Buffer,
  ) {
    await siteAccess(this.database.db, siteId, ownerId);
    const sanitized = await this.sanitize(bytes);
    return this.database.db.transaction(async (tx) => {
      // Unattached photos represent abandoned camera submissions, not evidence.
      await tx
        .delete(photos)
        .where(
          and(
            isNull(photos.entityId),
            lt(photos.createdAt, new Date(Date.now() - 86_400_000)),
          ),
        );
      const [photo] = await tx
        .insert(photos)
        .values({ siteId, ownerId, purpose, bytes: sanitized })
        .returning({ id: photos.id, purpose: photos.purpose });
      return photo;
    });
  }

  /** The app masks faces before upload; the unmasked copy stays with the uploader only. */
  async attachOriginal(
    siteId: string,
    ownerId: string,
    id: string,
    bytes: Buffer,
  ) {
    await siteAccess(this.database.db, siteId, ownerId);
    const sanitized = await this.sanitize(bytes);
    const [updated] = await this.database.db
      .update(photos)
      .set({ originalBytes: sanitized })
      .where(
        and(
          eq(photos.id, id),
          eq(photos.siteId, siteId),
          eq(photos.ownerId, ownerId),
        ),
      )
      .returning({ id: photos.id });
    if (!updated) throw new ForbiddenException();
  }

  async readOriginal(siteId: string, userId: string, id: string) {
    await siteAccess(this.database.db, siteId, userId);
    const [photo] = await this.database.db
      .select({ bytes: photos.originalBytes })
      .from(photos)
      .where(
        and(
          eq(photos.siteId, siteId),
          eq(photos.id, id),
          eq(photos.ownerId, userId),
          isNotNull(photos.originalBytes),
        ),
      );
    if (!photo?.bytes) throw new NotFoundException();
    return photo.bytes;
  }

  private async sanitize(bytes: Buffer) {
    try {
      const input = sharp(bytes, {
        limitInputPixels: 32_000_000,
        failOn: 'error',
      });
      const { format, pages } = await input.metadata();
      if (!['jpeg', 'png', 'webp'].includes(format) || (pages ?? 1) > 1)
        throw new Error();
      // Re-encoding strips EXIF (including GPS), file names and embedded thumbnails.
      return await input
        .rotate()
        .resize({
          width: 1920,
          height: 1920,
          fit: 'inside',
          withoutEnlargement: true,
        })
        .flatten({ background: 'white' })
        .jpeg({ quality: 82 })
        .toBuffer();
    } catch {
      throw new BadRequestException({
        error: {
          code: 'INVALID_PHOTO',
          message: '지원하는 사진 파일을 선택해 주세요.',
        },
      });
    }
  }

  async read(siteId: string, userId: string, id: string) {
    const { isForeman } = await siteAccess(this.database.db, siteId, userId);
    // Authorize in the same query that reads the bytes, including private drafts.
    const [photo] = await this.database.db
      .select({ bytes: photos.bytes })
      .from(photos)
      .where(
        and(
          eq(photos.siteId, siteId),
          eq(photos.id, id),
          sql`(${photos.ownerId}=${userId} OR (${photos.entityId} IS NOT NULL AND (${photos.shareWithSite} OR ${isForeman} OR ${userId}::uuid = ANY(${photos.viewerIds}))))`,
        ),
      );
    if (!photo) throw new NotFoundException();
    return photo.bytes;
  }

  async discard(siteId: string, userId: string, id: string) {
    await siteAccess(this.database.db, siteId, userId);
    const [removed] = await this.database.db
      .delete(photos)
      .where(
        and(
          eq(photos.id, id),
          eq(photos.siteId, siteId),
          eq(photos.ownerId, userId),
          isNull(photos.entityId),
        ),
      )
      .returning({ id: photos.id });
    if (!removed) throw new ForbiddenException();
  }
}
