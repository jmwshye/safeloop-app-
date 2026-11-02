import assert from 'node:assert/strict';
import { test } from 'node:test';
import { hashPassword, verifyPassword } from '../dist/auth/password-hasher.js';

test('비밀번호는 평문 없이 저장하고 일치 여부를 확인', async () => {
  const password = 'samplePassword123';
  const encoded = await hashPassword(password);

  assert.ok(!encoded.includes(password));
  assert.equal(await verifyPassword(password, encoded), true);
  assert.equal(await verifyPassword('differentPassword123', encoded), false);
});

test('같은 비밀번호도 매번 다른 salt와 해시로 저장', async () => {
  const password = 'samplePassword123';
  const first = await hashPassword(password);
  const second = await hashPassword(password);

  assert.notEqual(first.split('$')[5], second.split('$')[5]);
  assert.notEqual(first, second);
  assert.equal(await verifyPassword(password, first), true);
  assert.equal(await verifyPassword(password, second), true);
});

test('비밀번호의 공백과 대소문자를 그대로 구분', async () => {
  const password = '  SamplePassword123  ';
  const encoded = await hashPassword(password);

  assert.equal(await verifyPassword(password, encoded), true);
  assert.equal(await verifyPassword(password.trim(), encoded), false);
  assert.equal(await verifyPassword(password.toLowerCase(), encoded), false);
});

test('손상된 형식과 허용하지 않는 파라미터는 거부', async () => {
  const salt = 'ab'.repeat(16);
  const key = 'cd'.repeat(64);
  const validShape = `scrypt$v1$32768$8$3$${salt}$${key}`;
  const malformed = [
    '',
    'samplePassword123',
    validShape.replace('scrypt', 'other'),
    validShape.replace('$v1$', '$v2$'),
    validShape.replace('$32768$', '$16384$'),
    validShape.replace('$32768$', '$1073741824$'),
    validShape.replace('$8$', '$9$'),
    validShape.replace('$3$', '$9$'),
    validShape.replace(salt, 'zz'.repeat(16)),
    validShape.replace(key, 'zz'.repeat(64)),
    validShape.replace(salt, salt.slice(2)),
    validShape.replace(key, key.slice(2)),
    `${validShape}$extra`,
    `${validShape}\n`,
    null,
    undefined,
  ];

  for (const encoded of malformed) {
    assert.equal(await verifyPassword('samplePassword123', encoded), false);
  }
});
