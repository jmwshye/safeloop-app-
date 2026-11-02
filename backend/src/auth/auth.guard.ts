import {
  Injectable,
  UnauthorizedException,
  type CanActivate,
  type ExecutionContext,
} from '@nestjs/common';
import type { Request } from 'express';
import { AuthService, type PublicUser } from './auth.service.js';

export type AuthenticatedRequest = Request & { user: PublicUser };

@Injectable()
export class AuthGuard implements CanActivate {
  constructor(private readonly auth: AuthService) {}

  async canActivate(context: ExecutionContext): Promise<boolean> {
    const request = context.switchToHttp().getRequest<AuthenticatedRequest>();
    const token = request.headers.authorization?.match(/^Bearer +(\S+)$/i)?.[1];
    if (!token)
      throw new UnauthorizedException({
        error: { code: 'UNAUTHORIZED', message: '다시 로그인해 주세요.' },
      });
    request.user = await this.auth.authenticate(token);
    return true;
  }
}
