import {
  Catch,
  HttpException,
  Injectable,
  Logger,
  type ArgumentsHost,
  type ExceptionFilter,
} from '@nestjs/common';
import { BaseExceptionFilter, HttpAdapterHost } from '@nestjs/core';
import type { Request, Response } from 'express';

const errors: Record<number, { code: string; message: string }> = {
  400: { code: 'VALIDATION_ERROR', message: '입력값을 확인해 주세요.' },
  401: {
    code: 'INVALID_CREDENTIALS',
    message: '이메일 또는 비밀번호를 확인해 주세요.',
  },
  404: { code: 'NOT_FOUND', message: '요청한 주소를 찾을 수 없어요.' },
  409: { code: 'EMAIL_ALREADY_EXISTS', message: '이미 가입된 이메일이에요.' },
  413: { code: 'VALIDATION_ERROR', message: '요청 데이터가 너무 커요.' },
  429: {
    code: 'RATE_LIMITED',
    message: '요청이 너무 많아요. 잠시 후 다시 시도해 주세요.',
  },
};

@Injectable()
@Catch()
export class ApiExceptionFilter implements ExceptionFilter {
  private readonly logger = new Logger(ApiExceptionFilter.name);
  constructor(private readonly adapterHost: HttpAdapterHost) {}

  catch(exception: unknown, host: ArgumentsHost) {
    const context = host.switchToHttp();
    const request = context.getRequest<Request>();
    const path = request.path.toLowerCase();
    if (!path.startsWith('/auth/')) {
      new BaseExceptionFilter(this.adapterHost.httpAdapter).catch(
        exception,
        host,
      );
      return;
    }
    const status =
      exception instanceof HttpException ? exception.getStatus() : 500;
    if (status >= 500) this.logger.error('API 요청 처리 중 서버 오류 발생');
    // SQL·비밀번호·토큰이 포함될 수 있는 내부 예외는 응답이나 로그에 출력하지 않는다.
    const error = errors[status] ?? {
      code: 'SERVER_ERROR',
      message: '서버 오류가 발생했어요. 잠시 후 다시 시도해 주세요.',
    };
    context
      .getResponse<Response>()
      .setHeader('Cache-Control', 'no-store')
      .status(status)
      .json({ error });
  }
}
