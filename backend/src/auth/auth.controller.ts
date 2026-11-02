import {
  BadRequestException,
  Body,
  Controller,
  Header,
  HttpCode,
  Post,
  UseGuards,
} from '@nestjs/common';
import { ThrottlerGuard } from '@nestjs/throttler';
import { AuthService } from './auth.service.js';
import { loginSchema, signupSchema } from './auth.schemas.js';

@Controller('auth')
@UseGuards(ThrottlerGuard)
export class AuthController {
  constructor(private readonly auth: AuthService) {}

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
