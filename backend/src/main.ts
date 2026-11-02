import { existsSync } from 'node:fs';
import { loadEnvFile } from 'node:process';
import { fileURLToPath } from 'node:url';
import { NestFactory } from '@nestjs/core';
import type { NestExpressApplication } from '@nestjs/platform-express';
import { AppModule } from './app.module.js';
import { configureHttp } from './http.js';

const envPath = fileURLToPath(new URL('../.env', import.meta.url));
if (existsSync(envPath)) {
  loadEnvFile(envPath);
}

async function bootstrap() {
  const app = await NestFactory.create<NestExpressApplication>(AppModule);
  configureHttp(app, process.env.ADMIN_WEB_ORIGIN);
  app.enableShutdownHooks();
  try {
    await app.listen(process.env.PORT ?? 3000, process.env.HOST ?? '127.0.0.1');
  } catch (error) {
    await app.close();
    throw error;
  }
}
await bootstrap();
