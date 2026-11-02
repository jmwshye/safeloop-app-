import {
  ConflictException,
  Injectable,
  UnauthorizedException,
  type OnModuleInit,
} from '@nestjs/common';
import { createHash, randomBytes } from 'node:crypto';
import { and, eq, gt, lte } from 'drizzle-orm';
import { DatabaseService } from '../database.service.js';
import { sessions, users } from '../database/schema.js';
import { hashPassword, verifyPassword } from './password-hasher.js';
import type { LoginInput, SignupInput } from './auth.schemas.js';

const publicUserFields = { id: users.id, name: users.name, email: users.email };
const sessionDurationMs = 7 * 24 * 60 * 60 * 1000;
export type PublicUser = Pick<
  typeof users.$inferSelect,
  'id' | 'name' | 'email'
>;

@Injectable()
export class AuthService implements OnModuleInit {
  private dummyPasswordHash!: string;

  constructor(private readonly database: DatabaseService) {}

  async onModuleInit() {
    // 없는 이메일도 같은 비용으로 비밀번호를 검사한다.
    this.dummyPasswordHash = await hashPassword(
      randomBytes(32).toString('base64url'),
    );
  }

  async signup(input: SignupInput) {
    const passwordHash = await hashPassword(input.password);
    const [user] = await this.database.db
      .insert(users)
      .values({
        name: input.name,
        email: input.email,
        passwordHash,
      })
      .onConflictDoNothing({ target: users.email })
      .returning(publicUserFields);
    if (!user) throw new ConflictException();
    return { user };
  }

  async login(input: LoginInput) {
    const [user] = await this.database.db
      .select()
      .from(users)
      .where(eq(users.email, input.email))
      .limit(1);
    const valid = await verifyPassword(
      input.password,
      user?.passwordHash ?? this.dummyPasswordHash,
    );
    if (!user || !valid) throw new UnauthorizedException();

    const accessToken = randomBytes(32).toString('base64url');
    const tokenHash = createHash('sha256').update(accessToken).digest('hex');
    const now = new Date();
    const expiresAt = new Date(now.getTime() + sessionDurationMs);
    await this.database.db.transaction(async (tx) => {
      await tx
        .delete(sessions)
        .where(and(eq(sessions.userId, user.id), lte(sessions.expiresAt, now)));
      await tx
        .insert(sessions)
        .values({ tokenHash, userId: user.id, expiresAt });
    });
    return {
      accessToken,
      tokenType: 'Bearer' as const,
      expiresAt: expiresAt.toISOString(),
      user: { id: user.id, name: user.name, email: user.email },
    };
  }

  async authenticate(token: string): Promise<PublicUser> {
    const unauthorized = () =>
      new UnauthorizedException({
        error: { code: 'UNAUTHORIZED', message: '다시 로그인해 주세요.' },
      });
    if (!/^[A-Za-z0-9_-]{43}$/.test(token)) throw unauthorized();
    const tokenHash = createHash('sha256').update(token).digest('hex');
    const [user] = await this.database.db
      .select(publicUserFields)
      .from(sessions)
      .innerJoin(users, eq(sessions.userId, users.id))
      .where(
        and(
          eq(sessions.tokenHash, tokenHash),
          gt(sessions.expiresAt, new Date()),
        ),
      )
      .limit(1);
    if (!user) throw unauthorized();
    return user;
  }
}
