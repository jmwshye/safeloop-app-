import { HttpException } from '@nestjs/common';

const errors = {
  SHIFT_REQUIRED: [409, '출근 중인 현장을 확인해 주세요.'],
  SITE_NOT_FOUND: [404, '현장을 찾을 수 없어요.'],
  REQUEST_ID_CONFLICT: [409, '같은 요청 ID에 다른 내용을 보낼 수 없어요.'],
  INCIDENT_NOT_FOUND: [404, '요청 기록을 찾을 수 없어요.'],
  INCIDENT_ACTION_FORBIDDEN: [403, '이 동작을 처리할 권한이 없어요.'],
  INCIDENT_STATE_CONFLICT: [409, '요청 상태가 바뀌었어요. 다시 확인해 주세요.'],
} as const;

export class IncidentError extends HttpException {
  readonly error: { code: string; message: string };

  constructor(code: keyof typeof errors) {
    const [status, message] = errors[code];
    super({ error: { code, message } }, status);
    this.error = { code, message };
  }
}
