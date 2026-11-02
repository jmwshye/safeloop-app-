import {
  BadRequestException,
  Body,
  Controller,
  Get,
  Header,
  HttpCode,
  Post,
  Req,
  UseGuards,
} from '@nestjs/common';
import { ThrottlerGuard } from '@nestjs/throttler';
import { AuthService } from './auth.service.js';
import { loginSchema, signupSchema } from './auth.schemas.js';
import { AuthGuard, type AuthenticatedRequest } from './auth.guard.js';

@Controller('auth')
@UseGuards(ThrottlerGuard)
export class AuthController {
  constructor(private readonly auth: AuthService) {}

  @Get('me')
  @UseGuards(AuthGuard)
  me(@Req() request: AuthenticatedRequest) {
    return { user: request.user };
  }

  @Post('signup')
  @Header('Cache-Control', 'no-store')
  signup(@Body() body: unknown) {
    const input = signupSchema.safeParse(body);
    if (!input.success) throw new BadRequestException();
    return this.auth.signup(input.data);
  }

  @Post('login')
  @HttpCode(200)
  @Header('Cache-Control', 'no-store')
  login(@Body() body: unknown) {
    const input = loginSchema.safeParse(body);
    if (!input.success) throw new BadRequestException();
    return this.auth.login(input.data);
  }
}
