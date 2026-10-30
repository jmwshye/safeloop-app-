import { randomBytes, scrypt, timingSafeEqual } from 'node:crypto';

const scryptOptions = {
  N: 2 ** 15,
  r: 8,
  p: 3,
  maxmem: 64 * 1024 * 1024,
};
const saltLength = 16;
const keyLength = 64;
const hashPrefix = `scrypt$v1$${scryptOptions.N}$${scryptOptions.r}$${scryptOptions.p}$`;
const encodedLength = hashPrefix.length + saltLength * 2 + 1 + keyLength * 2;

function deriveKey(password: string, salt: Buffer): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    scrypt(password, salt, keyLength, scryptOptions, (error, key) => {
      if (error) reject(error);
      else resolve(key);
    });
  });
}

export async function hashPassword(password: string): Promise<string> {
  const salt = randomBytes(saltLength);
  const key = await deriveKey(password, salt);
  return `${hashPrefix}${salt.toString('hex')}$${key.toString('hex')}`;
}

export async function verifyPassword(
  password: string,
  encoded: string,
): Promise<boolean> {
  if (
    typeof encoded !== 'string' ||
    encoded.length !== encodedLength ||
    !encoded.startsWith(hashPrefix)
  ) {
    return false;
  }

  // 저장된 문자열이 임의의 고비용 scrypt 설정을 실행하게 하지 않는다.
  const parts = /^([0-9a-f]{32})\$([0-9a-f]{128})$/.exec(
    encoded.slice(hashPrefix.length),
  );
  if (!parts) return false;

  const salt = Buffer.from(parts[1], 'hex');
  const expectedKey = Buffer.from(parts[2], 'hex');
  const actualKey = await deriveKey(password, salt);
  return timingSafeEqual(actualKey, expectedKey);
}
