import 'reflect-metadata';
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { NestFactory } from '@nestjs/core';
import { AppModule } from '../dist/app.module.js';
import { DatabaseService } from '../dist/database.service.js';

test('GET /health의 HTTP 응답', { timeout: 15_000 }, async (t) => {
  const previousUrl = process.env.DATABASE_URL;
  // 실제 DB에 접속하지 않고 HTTP 계약과 장애 처리만 검사한다.
  process.env.DATABASE_URL = 'postgresql://unused@127.0.0.1:1/health_test';
  let databaseDown = false;
  const probe = t.mock.method(
    DatabaseService.prototype,
    'checkConnection',
    async () => {
      if (databaseDown) {
        throw new Error('postgresql://test-user:secret@private-db/test');
      }
    },
  );
  let app;
  t.after(async () => {
    try {
      await app?.close();
    } finally {
      if (previousUrl === undefined) delete process.env.DATABASE_URL;
      else process.env.DATABASE_URL = previousUrl;
    }
  });
  app = await NestFactory.create(AppModule, { logger: false });
  await app.listen(0, '127.0.0.1');
  const baseUrl = await app.getUrl();

  async function health() {
    const response = await fetch(`${baseUrl}/health`, {
      signal: AbortSignal.timeout(5000),
    });
    assert.equal(response.headers.get('cache-control'), 'no-store');
    return { status: response.status, body: await response.json() };
  }

  await t.test('정상일 때 200, 요청마다 DB 상태 재확인', async () => {
    const before = probe.mock.callCount();
    for (let i = 0; i < 2; i++) {
      assert.deepEqual(await health(), {
        status: 200,
        body: { status: 'ok', database: 'up' },
      });
    }
    assert.equal(probe.mock.callCount() - before, 2);
  });

  await t.test(
    'DB 오류면 503, 내부 오류 정보는 응답에 포함하지 않음',
    async () => {
      databaseDown = true;
      assert.deepEqual(await health(), {
        status: 503,
        body: { status: 'error', database: 'down' },
      });
      const root = await fetch(baseUrl, { signal: AbortSignal.timeout(5000) });
      assert.equal(root.status, 200);
      assert.equal(await root.text(), 'Hello World!');
    },
  );

  await t.test('DB 복구 후 서버 재시작 없이 다시 200', async () => {
    databaseDown = false;
    assert.deepEqual(await health(), {
      status: 200,
      body: { status: 'ok', database: 'up' },
    });
  });
});
