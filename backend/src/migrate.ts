import { existsSync } from 'node:fs';
import { loadEnvFile } from 'node:process';
import { fileURLToPath } from 'node:url';
import { drizzle } from 'drizzle-orm/node-postgres';
import { migrate } from 'drizzle-orm/node-postgres/migrator';
import { Pool } from 'pg';

const envPath = fileURLToPath(new URL('../.env', import.meta.url));
if (existsSync(envPath)) loadEnvFile(envPath);
if (!process.env.DATABASE_URL)
  throw new Error('DATABASE_URL이 없습니다. DB 접속 설정을 확인하세요.');

const pool = new Pool({
  connectionString: process.env.DATABASE_URL,
  connectionTimeoutMillis: 5000,
});
try {
  await migrate(drizzle(pool), {
    migrationsFolder: fileURLToPath(new URL('../drizzle', import.meta.url)),
  });
  console.log('DB 구조 반영 완료');
} catch {
  console.error('DB 구조 반영 실패. DB 접속 정보·권한·변경 파일을 확인하세요.');
  process.exitCode = 1;
} finally {
  await pool.end();
}
