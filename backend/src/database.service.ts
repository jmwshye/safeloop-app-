import {
  Injectable,
  Logger,
  type OnApplicationShutdown,
  type OnModuleInit,
} from '@nestjs/common';
import { sql } from 'drizzle-orm';
import { drizzle, type NodePgDatabase } from 'drizzle-orm/node-postgres';
import { Pool } from 'pg';
import * as schema from './database/schema.js';

@Injectable()
export class DatabaseService implements OnModuleInit, OnApplicationShutdown {
  private readonly logger = new Logger(DatabaseService.name);
  private readonly pool: Pool;
  private closePromise?: Promise<void>;
  readonly db: NodePgDatabase<typeof schema>;

  constructor() {
    const connectionString = process.env.DATABASE_URL;
    if (!connectionString) {
      throw new Error(
        'DATABASE_URL이 없습니다. backend/.env 설정을 확인하세요.',
      );
    }

    this.pool = new Pool({
      connectionString,
      connectionTimeoutMillis: 5000,
      query_timeout: 5000,
    });
    this.pool.on('error', () => {
      this.logger.error(
        'PostgreSQL 연결이 끊겼습니다. DB 실행 상태를 확인하세요.',
      );
    });
    this.db = drizzle({ client: this.pool, schema });
  }

  async onModuleInit(): Promise<void> {
    try {
      await this.checkConnection();
      this.logger.log('PostgreSQL 연결 확인 완료 (Drizzle SELECT 1)');
    } catch {
      await this.close();
      throw new Error(
        'PostgreSQL 연결 실패. DB 실행 상태와 backend/.env의 DATABASE_URL을 확인하세요.',
      );
    }
  }

  async checkConnection(): Promise<void> {
    await this.db.execute(sql`SELECT 1 AS connected`);
  }

  async onApplicationShutdown(): Promise<void> {
    await this.close();
  }

  private close(): Promise<void> {
    return (this.closePromise ??= this.pool.end());
  }
}
