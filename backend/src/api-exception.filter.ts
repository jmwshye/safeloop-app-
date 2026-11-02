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
  403: { code: 'FORBIDDEN', message: '이 작업에 접근할 수 없어요.' },
  404: { code: 'NOT_FOUND', message: '요청한 주소를 찾을 수 없어요.' },
  409: {
    code: 'STATE_CONFLICT',
    message: '상태가 바뀌었어요. 다시 확인해 주세요.',
  },
  413: { code: 'VALIDATION_ERROR', message: '요청 데이터가 너무 커요.' },
  429: {
    code: 'RATE_LIMITED',
    message: '요청이 너무 많아요. 잠시 후 다시 시도해 주세요.',
  },
};

const loginRequired = {
  code: 'UNAUTHORIZED',
  message: '다시 로그인해 주세요.',
};

const attendanceErrors: Record<number, { code: string; message: string }> = {
  401: loginRequired,
  404: { code: 'ATTENDANCE_NOT_FOUND', message: '출근 기록을 찾을 수 없어요.' },
  409: {
    code: 'ATTENDANCE_NOT_TODAY',
    message: '오늘의 출근 기록만 수정할 수 있어요.',
  },
};

const siteErrors: Record<number, { code: string; message: string }> = {
  401: loginRequired,
  404: { code: 'SITE_NOT_FOUND', message: '현장 코드를 다시 확인해 주세요.' },
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
    const isAttendance =
      path === '/attendances' || path.startsWith('/attendances/');
    const isSite = path === '/sites' || path.startsWith('/sites/');
    if (path === '/health' || path === '/health/' || path === '/') {
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
    const details =
      exception instanceof HttpException ? exception.getResponse() : null;
    const domainError =
      details && typeof details === 'object' && 'error' in details
        ? details.error
        : null;
    const explicit =
      status < 500 &&
      domainError &&
      typeof domainError === 'object' &&
      'code' in domainError &&
      typeof domainError.code === 'string' &&
      'message' in domainError &&
      typeof domainError.message === 'string'
        ? { code: domainError.code, message: domainError.message }
        : null;
    const error = explicit ||
      (status === 409 &&
        path === '/auth/signup' && {
          code: 'EMAIL_ALREADY_EXISTS',
          message: '이미 가입된 이메일이에요.',
        }) ||
      (status === 401 && path !== '/auth/login' && loginRequired) ||
      (isSite &&
        (path === '/sites/lookup' || path.endsWith('/join')) &&
        siteErrors[status]) ||
      (isAttendance && attendanceErrors[status]) ||
      errors[status] || {
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
