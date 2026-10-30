# SAFELOOP 앱 API

> 회원가입·로그인·출근·안전점검 5개 API 구현·로컬 검사 완료 / AWS 미배포. 실제 앱 연동은 미확인.

## 공통

- **서버 주소:** [백엔드 README](README.md#앱에서-서버에-연결할-때)의 기본 주소에 API 경로를 연결.
- **인증:** 회원가입·로그인은 로그인 토큰 없이 호출. 출근·안전점검 API에는 아래 헤더 필수. 토큰·ID는 문자열 그대로 사용하며 내부 형식을 해석하지 않음.
- **본문:** JSON을 보낼 때 `Content-Type: application/json` 지정. 명세의 필드만 전송하며 최대 **100KiB**. `GET`에는 본문 없음.
- **응답:** JSON, 캐시하지 않음. 성공 응답의 필드는 생략하지 않으며, `null`이면 내부 필드를 읽지 않기. 비밀번호는 반환하지 않음.
- **시각:** 서버가 정한 ISO 8601 UTC 문자열. `Z`는 UTC이며 앱에서는 한국 시각으로 표시.
- 예시의 이름·이메일·ID·토큰은 가짜 값. `string`은 문자열, `object`는 객체.

```http
Authorization: Bearer example-access-token
```

### 공통 오류

모든 오류는 같은 구조이며 `error.code`·`error.message`는 문자열. **코드로 동작을 구분**하고 메시지는 안내에 사용. 메시지 문구로 분기하지 않기.

```json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "입력값을 확인해 주세요."
  }
}
```

| 상태 · 코드 | 적용 조건 | 앱 처리 |
| --- | --- | --- |
| `400 VALIDATION_ERROR` | 입력 누락·형식·타입·값·추가 필드 오류 | 입력 확인 |
| `401 UNAUTHORIZED` | 로그인 필요 API의 토큰 없음·만료·유효하지 않음 | 다시 로그인 |
| `413 VALIDATION_ERROR` | JSON 본문 100KiB 초과 | 요청 크기 확인 |
| `429 RATE_LIMITED` | 회원가입·로그인 각각 IP당 1분 60회 초과 | `Retry-After` 헤더의 초만큼 기다린 뒤 재시도 |
| `5xx`·JSON이 아닌 응답·알 수 없는 코드 | 서버·통신 오류 | 저장 성공이나 로그인 실패로 단정하지 않기 |

각 API의 **오류**에는 위 공통 오류 외에 필요한 항목만 표시.
응답 없음·시간 초과는 성공 여부 미확인 상태. 완료 표시는 서버 성공 응답 후에만 변경.

## 인증

회원가입 → 로그인 → 응답의 토큰을 안전하게 보관.

### 회원가입 · `POST /auth/signup`

**요청** — 인증 불필요. 세 필드 모두 필수.

| 필드 | 타입 | 입력 기준 |
| --- | --- | --- |
| `name` | string | 앞뒤 공백 제거 후 빈 값 불가 |
| `email` | string | 이메일 형식. 앞뒤 공백 제거·소문자 처리, 대소문자 구분 없이 중복 검사 |
| `password` | string | 12~20자. 문자 조합 강제 없음. 공백·대소문자 변경 없이 전달 |

```json
{
  "name": "홍길동",
  "email": "worker@example.com",
  "password": "samplePassword123"
}
```

**성공 응답** — `201`. `user`의 `id`·`name`·`email`은 문자열이며 이름·이메일은 위 기준으로 정리된 값.

```json
{
  "user": {
    "id": "user_123",
    "name": "홍길동",
    "email": "worker@example.com"
  }
}
```

**오류** — `409 EMAIL_ALREADY_EXISTS`: 이미 가입된 이메일. 로그인 안내.

**주의사항**

- 가입 성공은 로그인 완료가 아님. 로그인 토큰은 로그인 API에서 발급.
- 비밀번호 확인은 앱에서 검사하고 서버에는 `password` 하나만 전송.
- 응답이 없으면 가입 여부가 미확인 상태이므로 무조건 반복 가입 요청하지 않기.

### 로그인 · `POST /auth/login`

**요청** — 인증 불필요. 두 필드 모두 필수.

| 필드 | 타입 | 입력 기준 |
| --- | --- | --- |
| `email` | string | 이메일 형식·앞뒤 공백 제거·소문자 처리 |
| `password` | string | 빈 값 불가. 입력값 그대로 전달 |

```json
{
  "email": "worker@example.com",
  "password": "samplePassword123"
}
```

**성공 응답** — `200`.

```json
{
  "accessToken": "example-access-token",
  "tokenType": "Bearer",
  "expiresAt": "2026-11-06T03:00:00Z",
  "user": {
    "id": "user_123",
    "name": "홍길동",
    "email": "worker@example.com"
  }
}
```

| 필드 | 타입 | 의미 |
| --- | --- | --- |
| `accessToken` | string | 로그인한 사용자임을 증명하는 토큰 |
| `tokenType` | string | `Bearer` 고정 |
| `expiresAt` | string | 토큰 만료 시각 |
| `user` | object | 회원가입 응답과 같은 사용자 정보 |

**오류** — `401 INVALID_CREDENTIALS`: 없는 이메일·틀린 비밀번호 모두 같은 응답. 재입력 안내.

**주의사항**

- 로그인 성공부터 **7일 고정**. 앱 사용으로 연장하지 않으며 서버가 만료 여부 판단.
- 토큰은 안전하게 보관하고 로그인 유지를 위해 비밀번호를 저장하지 않기.

## 출근·점검

로그인·앱 재실행 → 오늘 상태 조회 → 미출근이면 출근 저장 → 점검 저장.
출근 날짜는 **한국 시간(Asia/Seoul) 00:00~24:00**, 사용자별 하루 한 건.

### 출근 저장 · `POST /attendances`

**요청** — 빈 JSON 객체 `{}`. 사용자 ID·날짜·시각은 서버가 결정.

**성공 응답** — 첫 출근 `201`, 이미 출근했다면 `200`으로 기존 기록 반환.

```json
{
  "attendance": {
    "id": "attendance_123",
    "workDate": "2026-10-30",
    "checkedInAt": "2026-10-30T00:00:00Z",
    "safetyCheck": null
  }
}
```

**오류** — 공통 오류 적용. 빈 객체가 아닌 본문은 `400`.

**주의사항**

- 점검 전에도 출근 가능. 중복 클릭·재전송은 ID·최초 출근 시각·저장된 점검을 바꾸지 않음.
- 응답이 없으면 오늘 상태 조회로 저장 여부 확인.

### 오늘 출근·점검 조회 · `GET /attendances/today`

**요청** — 본문·조회 조건 없음.

**성공 응답** — `200`. 미출근이면 아래 응답.

```json
{
  "attendance": null
}
```

<details>
<summary>출근·점검이 저장된 응답 예시</summary>

```json
{
  "attendance": {
    "id": "attendance_123",
    "workDate": "2026-10-30",
    "checkedInAt": "2026-10-30T00:00:00Z",
    "safetyCheck": {
      "fallHazard": "confirmed",
      "floorAndGuardrail": "needs_attention",
      "protectiveEquipment": "unknown",
      "result": "needs_attention",
      "updatedAt": "2026-10-30T00:05:00Z"
    }
  }
}
```

</details>

| 상태 | 앱 표시 |
| --- | --- |
| `attendance: null` | 미출근 |
| 기록은 있고 `safetyCheck: null` | 출근 완료 · 점검 미작성 |
| `safetyCheck.result: all_confirmed` | 전 항목 확인 |
| `safetyCheck.result: needs_attention` | 확인 필요 |

**오류** — 공통 오류 적용. 미출근은 `404`가 아님.

**주의사항** — 로그인한 본인의 오늘 기록만 반환.

### 안전점검 저장·수정 · `PUT /attendances/{id}/safety-check`

**요청** — `{id}`는 출근 응답의 `attendance.id`. 세 필드 모두 필수 문자열.

| 필드 | 앱 점검 항목 |
| --- | --- |
| `fallHazard` | 작업 구역 추락·개구부 위험 위치 확인 |
| `floorAndGuardrail` | 바닥 자재 정리 및 안전난간 상태 확인 |
| `protectiveEquipment` | 본인 안전모·턱끈 및 보호구 착용 완료 |

답변: `confirmed` = 확인, `needs_attention` = 개선 필요, `unknown` = 모름.

```json
{
  "fallHazard": "confirmed",
  "floorAndGuardrail": "needs_attention",
  "protectiveEquipment": "unknown"
}
```

**성공 응답** — 최초 저장·수정 모두 `200`.

```json
{
  "attendance": {
    "id": "attendance_123",
    "workDate": "2026-10-30",
    "checkedInAt": "2026-10-30T00:00:00Z",
    "safetyCheck": {
      "fallHazard": "confirmed",
      "floorAndGuardrail": "needs_attention",
      "protectiveEquipment": "unknown",
      "result": "needs_attention",
      "updatedAt": "2026-10-30T00:05:00Z"
    }
  }
}
```

**오류**

| 상태 · 코드 | 의미·앱 처리 |
| --- | --- |
| `404 ATTENDANCE_NOT_FOUND` | 없거나 다른 사용자의 기록. 오늘 상태 재조회 |
| `409 ATTENDANCE_NOT_TODAY` | 지난 날짜 기록은 수정 불가. 오늘 상태 재조회 |

**주의사항**

- 본인의 **오늘 기록만** 수정. `unknown`도 답변으로 인정하며 누락·`null`·잘못된 값은 `400`.
- 수정할 때도 세 답변 전체 전송. 출근 시각은 유지하고, 같은 답변 재전송은 수정 시각도 유지.
- 세 답변 모두 `confirmed`이면 `all_confirmed`, 하나라도 다르면 `needs_attention`. 저장 성공과 전 항목 확인은 구분.
- 응답이 없으면 오늘 상태 조회로 저장 여부 확인.

### 출근·점검 응답 필드

| 필드 | 타입 | 의미 |
| --- | --- | --- |
| `attendance` | object 또는 null | 출근 기록. 오늘 조회에서 미출근이면 `null` |
| `attendance.id` | string | 출근 기록 ID |
| `attendance.workDate` | string | 한국 기준 날짜, `YYYY-MM-DD` |
| `attendance.checkedInAt` | string | 최초 출근 시각 |
| `attendance.safetyCheck` | object 또는 null | 점검 결과. 미작성은 `null` |
| `safetyCheck.fallHazard`·`floorAndGuardrail`·`protectiveEquipment` | string | 저장한 세 답변 |
| `safetyCheck.result` | string | `all_confirmed` 또는 `needs_attention` |
| `safetyCheck.updatedAt` | string | 점검 최초 저장·마지막 수정 시각 |

`safetyCheck.*`는 `attendance.safetyCheck` 내부 필드.
