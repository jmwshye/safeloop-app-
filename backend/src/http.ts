import type { NestExpressApplication } from '@nestjs/platform-express';

export function configureHttp(app: NestExpressApplication, webOrigin?: string) {
  app.set('trust proxy', 'loopback');
  if (!webOrigin?.trim()) return;
  const origin = new URL(webOrigin.trim());
  if (
    !['https:', 'http:'].includes(origin.protocol) ||
    origin.username ||
    origin.password ||
    origin.pathname !== '/' ||
    origin.search ||
    origin.hash
  ) {
    throw new Error('ADMIN_WEB_ORIGIN에는 관리자 웹의 주소만 입력해 주세요.');
  }
  app.enableCors({
    origin: [origin.origin],
    allowedHeaders: ['Authorization', 'Content-Type'],
    exposedHeaders: ['Content-Disposition'],
  });
}
