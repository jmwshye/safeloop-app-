# SAFELOOP API

| 공통 설정 | 값 |
| --- | --- |
| 로컬 서버 | PC · `http://127.0.0.1:3000` / Android 에뮬레이터 · `http://10.0.2.2:3000` |
| 연결 확인 | `GET /` → `200`, 문자열 `Hello World!` |
| 서버·DB 상태 | `GET /health` → 정상 `200 {"status":"ok","database":"up"}`, DB 연결 실패 `503 {"status":"error","database":"down"}` |
| 인증 헤더 | `Authorization: Bearer example-access-token` · 예시 토큰을 로그인 응답의 `accessToken`으로 교체. 회원가입·로그인·연결·상태 확인은 제외 |
| 요청 본문 | `Content-Type: application/json`, 최대 100 KiB. 파일 전송은 해당 API의 형식 사용 |

- **실제 Android 폰** · USB 연결 → `adb reverse tcp:3000 tcp:3000` 실행 → PC 주소 사용. 로컬 HTTP는 디버그 앱에서 허용
- **관리자 웹** · API와 주소가 다르면 서버의 `ADMIN_WEB_ORIGIN`에 웹 주소 설정

- **경로** · `{id}`를 각 API에서 지정한 ID로 교체
- **재전송** · `requestId`가 필요한 작업마다 UUID 한 번 생성. 같은 작업 재전송 시 ID와 본문 유지
- **날짜·시각** · 근무일·조회 기간은 한국 날짜 기준. 응답 시각의 `Z`는 UTC

## 인증

**회원가입** · `POST /auth/signup`

| 요청 필드 | 필수 입력 |
| --- | --- |
| `name` | 이름. 앞뒤 공백을 뺀 뒤 한 글자 이상 |
| `email` | 이메일 주소. 앞뒤 공백을 제거하고 소문자로 저장 |
| `password` | 비밀번호. 입력한 그대로 12~20자 |

- **응답** `201` · `user`에 `id`, `name`, `email`
- **이메일 중복** · `409 EMAIL_ALREADY_EXISTS`

**로그인** · `POST /auth/login`

**요청 예시**

```json
{
  "email": "worker@example.com",
  "password": "samplePassword123"
}
```

**성공 응답** · `200`

```json
{
  "accessToken": "example-access-token",
  "tokenType": "Bearer",
  "expiresAt": "2026-11-08T00:00:00.000Z",
  "user": {
    "id": "11111111-1111-4111-8111-111111111111",
    "name": "김민수",
    "email": "worker@example.com"
  }
}
```

- **로그인 유지** · 로그인부터 7일
- **이메일·비밀번호 불일치** · `401 INVALID_CREDENTIALS`

**내 계정 조회** · `GET /auth/me`

- **응답** `200` · `user`. 토큰 만료 시 `401`
- **로그아웃** · 푸시 기기 해제 → 로컬 토큰 삭제

## 현장

**내 현장 목록** · `GET /sites/mine`

**응답** `sites[]`

- **현장** · `id`, `name`, `code`, 내 `role`(`worker` 또는 `foreman`), 기준 위치 `center`(`latitude`, `longitude` 또는 미설정 시 `null`)
- **반장** · 이름 `foremanName`, 전화번호 `foremanPhone`, 사용자 ID `foremanId`
- **작업 정보** · 내 직종·구역·예상 일당, 휴식 복귀·교대 시각

**현장 생성** · `POST /sites`

| 요청 필드 | 입력 조건 |
| --- | --- |
| `name` | **필수** · 현장 이름 |
| `requestId` | 생성 폼 제출용 UUID. 응답을 못 받으면 같은 값으로 재전송해 같은 현장·코드 조회 |
| `center` | 선택 · 현장 중심 좌표. `latitude`와 `longitude`를 함께 전송 |
| `foremanPhone` | 선택 · 반장 전화번호 |
| `breakReturnTime` · `shiftChangeTime` | 선택 · 휴식 복귀·교대 시각. 한국 시간 `HH:mm` |

- **응답** `201` · `site`(`id`, `name`, `code`)
- **역할** · 생성자는 현장 반장. 작업자는 발급된 `code`로 참여

**현장 코드 확인** · `POST /sites/lookup`

- **요청** `{"code":"SS-2417"}`. 영문·숫자·중간 하이픈을 포함해 최대 12자. 대소문자 구분 없음.
- **응답** `200`과 `site`(`id`, `name`, `foremanName`). 없는 코드는 `404 SITE_NOT_FOUND`.

**현장 참여** · `POST /sites/{siteId}/join`

- **요청** 경로에 코드 확인 응답의 `site.id`, 본문에 같은 `code` 전송
- **응답** 첫 참여 `201` / 이미 참여 `200` · 두 경우 모두 `site`

**내 작업 정보 저장** · `PUT /sites/{siteId}/profile`

- **요청** `{"occupation":"형틀 목공","zone":"2층","expectedWage":230000}`. 직종과 구역은 필수. 예상 일당(원) 생략 또는 `null` → 미입력으로 저장.
- **응답** `profile`에 저장된 세 값.

**현장 설정 변경** · `PUT /sites/{siteId}/settings`

- **권한** 현장 반장
- **요청** 생성 때와 같은 본문으로 설정 전체 교체. 생략한 선택값은 초기화
- **좌표 유지** `GET /sites/mine`의 `center`를 함께 전송
- **교대·휴식 시간 변경** 근무 중인 작업자의 발송 전 예약에 적용. 이미 도래했거나 발송한 알림은 이력에 유지
- **응답** 변경된 `site`.

## 출퇴근·안전점검

**오늘 출근 조회** · `GET /attendances/today`

**선택 쿼리** · `siteId`로 조회할 현장 지정

| `200` 응답 | 의미 |
| --- | --- |
| `{"attendance":null}` | 오늘 출근과 이전 미퇴근 기록이 모두 없음 |
| `attendance`가 있고 `safetyCheck`가 `null` | 출근 완료, 점검 전 |
| `attendance.safetyCheck`에 값이 있음 | 출근·점검 모두 저장됨 |

**출근 저장** · `POST /attendances`

**출근 조건** · 하루 한 현장. 자정을 넘긴 미퇴근 기록은 먼저 퇴근 처리. GPS 권한 거절 시에도 출근 가능

| 요청 필드 | 입력 조건 |
| --- | --- |
| `siteId` | 선택한 현장의 UUID. 참여 현장이 하나일 때만 생략 가능 |
| `location` | 선택 · 위도 `latitude`, 경도 `longitude`, 정확도(미터) `accuracyMeters`, 측정 시각 `capturedAt`. GPS를 받지 못하면 생략 |

**요청 예시 · GPS 없이 출근**

```json
{
  "siteId": "22222222-2222-4222-8222-222222222222"
}
```

- **첫 저장** · `201`과 출근 기록
- **같은 날·같은 현장 재요청** · `200`과 기존 기록
- **다음 안전점검 요청** · 출근 ID로 **`attendance.id`** 사용

**출근 응답 예시**

```json
{
  "attendance": {
    "id": "33333333-3333-4333-8333-333333333333",
    "siteId": "22222222-2222-4222-8222-222222222222",
    "workDate": "2026-11-02",
    "checkedInAt": "2026-11-01T22:51:00.000Z",
    "checkedOutAt": null,
    "occupation": "형틀 목공",
    "zone": "2층",
    "expectedWage": 230000,
    "locationStatus": "unverified",
    "buddyUserId": "44444444-4444-4444-8444-444444444444",
    "highRiskTask": null,
    "highRiskStartedAt": null,
    "safetyCheck": null
  }
}
```

| `409` 오류 코드 | 다음 동작 |
| --- | --- |
| `SITE_PROFILE_REQUIRED` | 직종·구역을 먼저 저장 |
| `SITE_SELECTION_REQUIRED` | 현장을 선택하고 `siteId`를 포함해 재요청 |
| `ATTENDANCE_ALREADY_AT_OTHER_SITE` | `siteId` 없이 오늘 기록을 조회해 이미 출근한 현장 확인 |

**안전점검 저장·수정** · `PUT /attendances/{id}/safety-check`

- **경로의 `{id}`** · 오늘 근무 중인 **내 출근 ID**
- **요청** · 세 항목 모두 필수

| 점검 항목 | 요청 필드 |
| --- | --- |
| 추락 위험 구역 | `fallHazard` |
| 바닥·개구부·안전난간 | `floorAndGuardrail` |
| 안전모·턱끈·안전대 | `protectiveEquipment` |

**요청 예시** · 항목마다 확인(`confirmed`) / 개선 필요(`needs_attention`) / 모름(`unknown`) 중 하나

```json
{
  "fallHazard": "confirmed",
  "floorAndGuardrail": "needs_attention",
  "protectiveEquipment": "unknown"
}
```

**응답** · `200`. `attendance.safetyCheck`에 세 답변, `updatedAt`, `result`.

- 모두 확인이면 `all_confirmed`. 개선 필요나 모름이 하나라도 있으면 `needs_attention`.
- 새로 ‘모름’으로 저장한 항목 → 반장에게 확인 요청

**출근 전 위치 확인** · `POST /sites/{siteId}/check-location`

- **요청** 출근과 같은 `location` 객체. GPS가 없으면 `{}`
- **처리** 위치만 확인. 출근 저장은 별도 요청

- **응답** `locationStatus` · `inside`(현장 안) / `outside`(현장 밖) / `unverified`(위치 미확인)
- **판정 기준** 현장 반경 300m와 측정 오차
- **저장 범위** 출근 기록에 위치 판정만 저장. 원본 좌표 제외

**퇴근 저장** · `PUT /attendances/{id}/checkout`

- **요청** 내 출근 ID와 `{}`.
- **응답** `attendance.checkedOutAt`이 퇴근 시각.

**내 출퇴근 이력** · `GET /attendances/records`

- **요청** 조회 기간의 시작일과 종료일. 예시 `?from=2026-11-01&to=2026-11-30`.
- **응답** `attendances[]`.

## 동료·작업 상태

**내 근무 상태 조회** · `GET /attendances/{id}/context`

**경로의 `{id}`** · 내 출근 ID

| 응답 필드 | 의미 |
| --- | --- |
| `buddy` | 배정된 동료. `userId`는 사용자 ID, `attendanceId`는 동료 점검에 쓸 출근 ID, `zone`은 근무 구역(미확인 시 `null`) |
| `device` | 내 기기 휴대 상태 |
| `peerChecks[]` | `checkerUserId`가 `attendanceId`의 작업자를 확인한 기록. 결과는 `outcome`, 사진은 `photoId`, 시각은 `checkedAt` |
| `prompts[]` | 응답할 안전 확인 알림. `id`, `kind`, `state`, 시작 `dueAt`, 만료 `expiresAt` |

**동료 점검 기록** · `PUT /attendances/{id}/peer-check`

- **경로의 `{id}`** · **동료의 출근 ID**(`buddy.attendanceId`)
- **확인 담당** · 짝이 있으면 서로 확인 / 짝이 없으면 반장이 작업자 확인

| 결과 | 요청 본문 |
| --- | --- |
| 확인 | `{"outcome":"checked"}`. 촬영한 보호구 사진이 있으면 `photoId` 추가 |
| 불일치 | `{"outcome":"mismatch"}`. 사진 없이 전송. 조치 후 `checked`로 다시 저장 |

**응답** `check`에 저장된 점검 기록.

**동료 재배정** · `PUT /sites/{siteId}/buddy-assignment`

- **요청** 현장 반장이 작업자의 **사용자 ID**를 `workerId`, 동료의 **사용자 ID**를 `buddyId`로 전송
- **응답** 배정된 `workerId`와 `buddyId`.

**기기 휴대 상태 기록** · `PUT /attendances/{id}/device-status`

- **요청** `state`는 `carried`(휴대) 또는 `not_carried`(미휴대). `observedAt`은 측정 시각.
- **응답** `device.state`와 `device.lastSeenAt`. 5분간 새 신호가 없으면 `unverified`.

**고위험 작업 시작·종료** · `PUT /attendances/{id}/high-risk`

- **시작** `task` · `scaffold`(비계) / `ladder`(사다리) / `opening`(개구부) / `aerial_lift`(고소작업대)
- **종료** `{"task":null}`
- **응답** `highRiskTask`와 `highRiskStartedAt`.

**안전 확인 응답** · `PUT /attendances/{id}/check-ins/{promptId}`

- **요청** 내 `prompts[]` 중 `state=pending`인 알림에 `{"response":"okay"}` 또는 `{"response":"help_needed"}`.
- **응답** `prompt`. 도움 요청 후 진행 중 사건 조회
- **15분 미응답** · `unverified`로 처리. 긴급 호출 없음
- **알림 종류** `break_return`(휴식 복귀), `shift_change`(교대), `high_risk_start`·`high_risk_end`(고위험 작업 시작·종료).

## 긴급 호출·119

**사건 생성** · `POST /incidents`

필수 항목은 현장 `siteId`, 요청 `requestId`, 호출 사유 `source`. 근무 중 충격을 감지하면 `abnormal_movement`, 현장 구성원이 직접 도움을 요청하면 `manual_help`.

```json
{
  "siteId": "22222222-2222-4222-8222-222222222222",
  "requestId": "55555555-5555-4555-8555-555555555555",
  "source": "abnormal_movement"
}
```

| 선택 필드 | 보낼 값 |
| --- | --- |
| `location` | 사건 당시 `latitude`, `longitude`, `accuracyMeters`. 출근용 `capturedAt`은 제외 |
| `detectedAt` · `attendanceId` | 충격을 감지한 원래 시각(ISO 8601)과 당시 출근 ID. 오프라인 재시도 시 같은 값 사용 |
| `peakG` | 최대 가속도(g) |
| `sensorWindow` | 충격 전후 15초의 `offsetMs`·`accelerationG` 표본, 최대 300개 |

- **새 사건** · `201`과 `incident`
- **같은 사용자의 같은 현장에 진행 중 사건 있음** · 새 `requestId`여도 `200`과 기존 사건
- **다른 현장에 진행 중 사건 있음** · `409 INCIDENT_STATE_CONFLICT`

**목록** · `GET /incidents/active`

- **선택 쿼리** · `siteId`
- **응답** `incidents[]` · 본인이 당사자이거나 초대받은 사건, 반장이 담당하는 현장의 사건
- **조회 조건** · 미출근 상태에서도 가능

**상세** · `GET /incidents/{id}`

- **응답** `incident` · 당사자 `worker`, 구역 `zone`, 수락자 `responderId`, 수신자 `recipients[]`
- **위치** 생성 시 기록한 `location`. 미제공·권한 밖·사건 종료 후에는 `null`

| `incident.status` | 의미 |
| --- | --- |
| `awaiting_worker` | `workerDueAt`까지 15초간 당사자 응답 대기. 직접 도움 요청은 이 단계를 건너뜀 |
| `calling` | 근무 중인 지정 동료와 반장 호출. 30초 무응답 시 같은 구역 동료 한 명씩 확대(고위험 작업·다른 사건 대응 중인 대체 동료 제외) |
| `unanswered` | 수락자가 없고 추가로 호출할 동료가 없음. 기존 초대는 수락 가능 |
| `responding` · `arrived` | 수락자가 이동 중 · 현장 도착 |
| `closed` | 사건 종료. `resolution`과 `closedAt`에 종료 사유와 시각 |

**당사자 응답** · `PUT /incidents/{id}/worker-response`

**요청** `requestId`와 `response`

| `response` | 처리 |
| --- | --- |
| `help_needed` | 도움 요청 |
| `okay` | 본인 확인 15초 안이면 오탐 종료. 동료 호출을 시작한 뒤에는 응답만 기록하고 현장 확인 계속 |

**초대받은 동료·반장** · 사건 변경 응답은 모두 `incident`.

- 수신·열람 · `PUT /incidents/{id}/receipt`. 기기가 받으면 `{"stage":"received"}`, 사용자가 열면 `{"stage":"opened"}` 전송. 늦게 동기화할 때는 원래 시각 `occurredAt`도 전달.
- 수락 · `PUT /incidents/{id}/accept`에 `{}`. 수락자 1명만 허용. `409`면 사건 상세 재조회.
- 거절 · `PUT /incidents/{id}/decline`에 `{}`.

| `recipients[]` 필드 | 시각의 의미 |
| --- | --- |
| `requestedAt` | 서버 호출 요청 |
| `sentAt` | FCM 접수 |
| `receivedAt` | 단말 수신 확인 |
| `openedAt` | 사용자 열람 확인 |
| `acceptedAt` / `declinedAt` | 수락 / 거절 |

`null` · 해당 시각의 기록 없음

**수락자의 현장 대응**

1. **도착** · `PUT /incidents/{id}/arrival`에 `{}`. `arrivedAt` 기록.
2. **관찰** · `PUT /incidents/{id}/observation`에 `condition` 전송. 값은 `unconscious`, `immobile`, `conscious`, `appears_safe`. `observation`과 `observedAt`에 기록되며 반장에게 보내는 상태 알림은 `notices[]`에서 확인.
3. **완료** · `PUT /incidents/{id}/resolution`에 `reason` 필수. 도움 불필요 `no_help_needed`, 현장 조치 완료 `onsite_action_completed`, 인계 확인 `handoff_confirmed` 중 하나. 현장 사진 `photoId`는 선택. 도착 전 업로드한 사진은 `409 PHOTO_BEFORE_ARRIVAL`.

**현장 확인 지연** · 수락 후 120초 동안 상태 관찰 기록이 없으면 반장에게 한 번 알림. 수락자는 계속 대응 가능.

**재호출** · `PUT /incidents/{id}/retry`

`unanswered`일 때 당사자나 현장 반장이 `{}` 전송. `409 NO_RESPONDER_AVAILABLE`이면 직접 연락 안내.

**119 전화 기록**

- **앱** · 오프라인에서도 `ACTION_DIAL`로 `tel:119` 먼저 열기
- **서버 기록** · 두 경로 모두 `openedAt`에 전화 화면을 연 원래 시각을 전송. 사건 종료 전에 연 기록은 종료 후에도 동기화 가능
- 진행 중 사건 안에서 · `PUT /incidents/{id}/dial-intent`에 `requestId` → `incident`.
- 사건 밖에서 · `POST /sites/{siteId}/dial-intents`에 `requestId` → `200`, `id`와 `openedAt`.

## 위험 신고

**신고 등록** · `POST /sites/{siteId}/risks`

- **권한** · 현장 구성원
- **필수** · `requestId`, `category`, `zone`(1~100자), `anonymous`
- **선택** · `title`(1~100자), `note`(최대 1,000자), `beforePhotoId`
- **제목 생략** · 종류와 위치로 자동 생성
- **신고 사진** · `beforePhotoId`에 본인이 같은 현장에 올린 `risk_before` 사진 ID

```json
{
  "requestId": "77777777-7777-4777-8777-777777777777",
  "category": "fall",
  "zone": "2층 동측 개구부",
  "anonymous": true
}
```

- **분류** `fall`(추락), `trip`(걸려 넘어짐), `collision`(충돌), `electric`(전기), `other`(기타).
- **응답** 신규 신고는 `201`과 `risk.status=open`. 같은 `requestId`와 같은 본문을 다시 보내면 `200`과 기존 `risk`. 본문이 달라지면 `409`.

**목록** · `GET /sites/{siteId}/risks`

- **선택 쿼리** · `status`, `limit`(기본 40, 최대 100), `offset`
- **응답** `risks[]`, 현장 전체 상태별 `counts`, 다음 페이지의 `nextOffset`
- **다음 페이지** · `nextOffset`을 `offset`으로 전송. `null`이면 마지막 페이지

**상세** · `GET /sites/{siteId}/risks/{id}`

| `risk` 필드 | 의미 |
| --- | --- |
| `beforePhotoId` / `afterPhotoId` | 조치 전 / 후 사진 |
| `createdAt` / `fixedAt` / `verifiedAt` | 신고 / 조치 / 재확인 시각 |
| `assigneeId` / `assigneeName` | 배정된 담당자 ID / 이름 |
| `fixerId` / `fixerName` | 실제 조치자 ID / 이름 |
| `verifierId` / `verifierName` | 재확인자 ID / 이름 |
| `canRecheck` | 현재 사용자가 재확인할 수 있으면 `true` |

- **담당자 미정** · 해당 ID·이름은 `null`
- **신고자 이름** · 목록의 `reporterName`은 항상 `null`. 익명 신고는 상세에서도 반장에게 이름 비공개

**조치와 재확인** · 아래 요청의 응답은 갱신된 `risk`.

1. **배정** · `PUT /sites/{siteId}/risks/{id}/request`. 반장이 `assigneeId`에 현장 구성원의 사용자 ID 전송. 상태는 `open`(미조치).
2. **조치** · `PUT /sites/{siteId}/risks/{id}/fix`. 현장 구성원이 **본인이 같은 현장에 `risk_after`로 올린 사진**의 ID를 `afterPhotoId`로 전송. 상태는 `awaiting_recheck`(재확인 대기).
3. **재확인** · `PUT /sites/{siteId}/risks/{id}/recheck`. 다른 현장 구성원이 `{}`를 보내면 `resolved`(완료). 신고자나 실제 조치자가 재확인하면 `403`.

## 현장 운영

**권한** · 해당 현장 반장

**대시보드** · `GET /sites/{siteId}/dashboard`

- **응답** `site`, 기준일 `workDate`, 진행 중인 `activeCalls`, 오래된 미조치 위험 5건(`oldestRisks`), 오늘 최신 기록 10건(`timeline`).
- 진행 중 호출은 주기적으로 다시 조회.

| 응답의 `counts` 필드 | 뜻 |
| --- | --- |
| `members` / `attended` | 현장 작업자 수 / 오늘 출근 인원 |
| `crossChecked` | 오늘 교차 확인을 완료한 인원. 사진 없는 확인 포함 |
| `photoConfirmed` | 오늘 사진으로 교차 확인을 완료한 인원. ‘서로 찍기 완료’에 사용 |
| `unverified` | 상태 미확인 인원 |
| `openRisks` / `pendingSafetyReviews` | 미조치 위험 건수 / 반장 확인 대기 건수 |

- **완료 인원** · 당일 원본 확인 기록 기준. 퇴근·짝 변경 후에도 유지
- **현재 동료와의 확인 상태** · 아래 `peerStatus`로 확인

**작업자 목록** · `GET /sites/{siteId}/workers`

- **응답** `workers[]`. 호출 중인 작업자는 `userId`와 대시보드의 `activeCalls[].workerId`로 연결.

| 작업자 필드 | 뜻 |
| --- | --- |
| `userId` / `buddyUserId` | 작업자 ID / 배정된 동료 ID |
| `name`, `occupation`, `zone` | 이름, 직종, 작업 구역 |
| `attendanceId`, `checkedInAt`, `checkedOutAt` | 오늘의 출근 ID, 출근 시각, 퇴근 시각 |
| `locationStatus`, `device`, `checkInStatus` | 출근 위치 판정, 기기 상태, 최근 안전 확인 상태 |
| `peerStatus` | `pending` 대기 · `mismatch` 불일치 · `statement_only` 진술 확인 · `photo_confirmed` 사진 확인 |

**점검 확인 요청 목록** · `GET /sites/{siteId}/safety-requests`

- **응답** `requests[]`에 `attendanceId`, 세 점검 답변, `requestedAt`(요청 시각), `acknowledgedAt`(확인 시각).

**점검 요청 확인** · `PUT /sites/{siteId}/safety-requests/{id}/acknowledgement`

- **요청** `{id}`는 목록의 `attendanceId`. `expectedRequestedAt`에 화면에 표시한 요청의 `requestedAt` 전송. `409 SAFETY_REVIEW_CHANGED`면 새 항목을 다시 확인.
- **응답** `request.acknowledgedAt`에 확인 시각 기록. 작업자의 원래 답변은 유지.

## 푸시·알림

**기기 등록** · `PUT /push-devices/{deviceId}`

- **경로** · `deviceId`는 앱에서 계정별로 보관하는 설치 UUID. 같은 폰에서 다른 계정으로 로그인하면 계정에 맞는 ID 사용
- **요청** 로그인 또는 FCM 토큰 변경 시 `fcmToken`에 기기가 받은 새 토큰 전송
- **응답** `device.id`.

**기기 해제** · `DELETE /push-devices/{deviceId}`

- 로그아웃 전 현재 토큰으로 호출. **응답** `{"removed":true}`.

**내 알림 설정 조회** · `GET /me/notification-preferences`

- **응답** 긴급 알림 `emergencyPush`와 현장 알림 `sitePush`. 기본값은 둘 다 `true`.

**내 알림 설정 변경** · `PUT /me/notification-preferences`

- **요청** 두 설정 중 바꿀 값만 전송. 예시 `{"sitePush":false}`.
- **응답** 변경 후 `emergencyPush`와 `sitePush`.

**현장 알림함** · `GET /sites/{siteId}/notices`

- **응답** `notices[]`에 내 최근 알림 최대 100건. 항목은 `id`, 종류 `kind`, 관련 기록 `entityId`, 알림 시각 `availableAt`, 전송 상태 `pushStatus`.

**FCM 수신 시 처리**

- **공통 데이터** · `type`, `eventId`, `siteId`, `recipientId`. 앱은 현재 계정·현장과 일치할 때 알림 표시
- **이동할 기록** · 긴급 푸시는 `incidentId`, 현장 푸시는 `entityId`

| 메시지 `type` | 앱 동작 |
| --- | --- |
| `incident_alert` | 잠금 화면 알림 표시. 단말 수신·사용자 열람은 각각 서버에 기록. 같은 `eventId`는 기존 알림 갱신 |
| `incident_observation`, `incident_worker_safe`, `incident_closed`, `incident_verification_overdue` | `incidentId`로 사건 상세 갱신 |
| `work_prompt` / `high_risk` / `high_risk_end` | 내 근무 확인 / 동료 고위험 작업 시작 / 종료 |
| `safety_review` / `risk_action` | 반장 점검 요청 목록 / 위험 신고 상세 갱신 |

## 사진

**사진 업로드** · `POST /sites/{siteId}/photos`

| 요청 필드 | `multipart/form-data`에 보낼 값 |
| --- | --- |
| `file` | JPG·PNG·WebP 정지 사진 1장. 최대 5 MiB, 3,200만 화소 |
| `purpose` | `ppe` 동료 보호구 · `risk_before` 위험 조치 전 · `risk_after` 위험 조치 후 · `incident_scene` 사고 현장 |

- **응답** `201` · `photo.id`, `photo.purpose`
- **첨부 조건** 같은 현장에서 본인이 해당 용도로 올린 `photo.id` 사용

**사진 열람** · `GET /sites/{siteId}/photos/{id}`

- **응답** 인증 헤더로 요청하면 `200 image/jpeg` 바이트.
- **권한** 미첨부 사진은 업로더만, 위험 사진은 현장 구성원, 동료·사고 사진은 해당 당사자와 반장.

**미첨부 사진 삭제** · `DELETE /sites/{siteId}/photos/{id}`

- 본인이 올린 미첨부 사진만 삭제 가능. **응답** `204`, 본문 없음.

## 타임라인·정정

**기록 조회** · `GET /sites/{siteId}/timeline`

- **요청** `?scope=self&day=2026-11-02`. 반장은 `scope=site`로 현장 전체를 조회. `limit` 기본 50건, 최대 100건.
- **응답** 최신순 `events[]`. 다음 페이지는 `nextBefore`를 `before`로 전송. `null`이면 마지막 페이지.

| 기록 필드 | 뜻 |
| --- | --- |
| `type` / `source` | 이벤트 종류 / 출처. `human`은 사람이 확인한 기록, `system`은 시스템 기록 |
| `recordedAt` | 서버가 기록한 시각 |
| `entityId` | 관련 출근·사건·위험 신고 ID |
| `details.photoId` | 동료 점검에 첨부한 사진 ID |
| `incident.worker_unanswered` / `incident.response_delayed` | 본인 확인 시간 만료 / 대응자 수락 시간 만료 |
| `incident.accepted` | `details.role`은 수락자 역할, `details.responseSeconds`는 해당 수락자 호출부터 수락까지 걸린 초 |
| `incident.responder_unavailable` | 수락자가 대응할 수 없어 다른 동료에게 다시 요청 |
| `incident.verification_overdue` | 수락 후 120초간 현장 상태 미확인으로 반장에게 확인 요청 |

**원본 검사** · `GET /sites/{siteId}/timeline/verify`

- 반장 전용. **응답** `valid`(검사 결과), `checked`(검사 건수), `firstInvalidSeq`(첫 오류 순번).

**정정 요청** · `POST /sites/{siteId}/timeline/{eventId}/corrections`

- **요청** `{eventId}`에 본인 기록의 원본 이벤트 ID. 본문에 `requestId`와 `note`(1~1,000자).
- **응답** `201`, `correction`.

**정정 목록** · `GET /sites/{siteId}/corrections`

- **응답** `corrections[]`. 작업자는 본인 요청, 반장은 현장 전체 요청. 익명 신고의 원 신고자 ID는 반장에게도 비공개.

**정정 검토** · `PUT /sites/{siteId}/corrections/{id}/review`

- **요청** 반장이 `status`(`accepted` 승인 / `declined` 반려)와 `note`(1~1,000자) 전송.
- **응답** `correction`. 검토 결과를 새 이벤트로 추가. 원본 연결은 `correctsId`.

## 보고서

- **기간** `?month=2026-11`. 월을 생략하면 한국 기준 이번 달. 근로 보고서는 월 대신 `from`과 `to`를 함께 전송 가능(양 끝 포함 최대 366일).
- **다운로드** 근로·월간·개별 사고 보고서는 기본 JSON. `format=csv` / `format=pdf` → 파일 응답. 파일명은 `Content-Disposition` 헤더에서 확인

**내 근로 보고서** · `GET /reports/work`

- **범위** 내가 일한 모든 현장.
- **응답** `period`, `shifts`(출퇴근 기록), `summary`(합계), `events`, `corrections`, `dataGaps`.

| `summary` 필드 | 뜻 |
| --- | --- |
| `workerDays` | 근로일 수 |
| `workedMinutes` | 퇴근까지 기록된 근무 시간 합계(분) |
| `expectedWageTotal` | 예상 일당 합계(원). 모두 미입력이면 `null` |

**현장별 근로 보고서** · `GET /sites/{siteId}/reports/work`

- **조회 대상** 기본은 본인. 반장은 `userId`로 한 명 / `scope=all`로 전체 작업자 선택. 두 조건 동시 전송 시 `400`.
- **응답** 내 근로 보고서와 같은 형식.
- **전체 다운로드 예** `GET /sites/{siteId}/reports/work?month=2026-11&scope=all&format=csv`

**현장 월간 집계** · `GET /sites/{siteId}/reports/monthly`

- **요청** 반장 전용. `month` 선택.
- **응답** `summary`(현장 합계), `workers`(작업자별 합계), `dailyRecords`(일별 기록), `period`, `events`, `corrections`, `dataGaps`.

| 안전 확인 집계 필드 | 근로일 수 기준 · 현장·작업자 합계 공통 |
| --- | --- |
| `mutualChecks` | 사진 없는 확인을 포함한 교차 확인 완료 |
| `photoConfirmedChecks` | 사진으로 교차 확인 완료 |
| `statementOnlyChecks` | 교차 확인 완료 중 사진 확인까지 완료하지 않은 경우 |

**사고 보고서 목록** · `GET /sites/{siteId}/reports/incidents`

- **요청** `month`, `offset`, `limit` 선택. 기본 30건, 최대 100건.
- **응답** `period`, `incidents[]`, `nextOffset`. 종료된 사건 포함. 당사자는 본인 사건, 반장은 현장 전체 사건.

**개별 사고 보고서** · `GET /sites/{siteId}/reports/incidents/{id}`

- **권한** 사건 당사자·현장 반장.
- **응답** `incident`와 `generatedAt`, `events`, `corrections`, `dataGaps`, `disclaimer`. 원시 센서 표본과 GPS 좌표는 제외.

| `incident` 필드 | 뜻 |
| --- | --- |
| `recipients` | 호출 대상과 수신·응답 기록 |
| `dialerOpenedAt` | 119 전화 화면을 연 시각 목록 |
| `scenePhotoId` | 첨부한 현장 사진 ID. 있으면 PDF에 사진 포함 |
| `ppeEvidence` | 사고 전 보호구 교차 확인 증빙. 사진은 PDF에도 포함 |

| `ppeEvidence` 필드 | 의미 |
| --- | --- |
| `status` | `photo_confirmed` 사진 확인 · `statement_only` 사진 없는 확인 포함 · `mismatch` 불일치 · `incomplete` 증빙 부족 |
| `checks[]` | 확인자 `photographer`, 대상자 `subject`, 원본 사건 `eventId`, 서버 수신 시각 `serverReceivedAt`, 결과 `outcome`, 증빙 `evidence`, 사진 `photoId` |
| `checks[].evidence` | `photo` 사진 · `statement` 진술 · `missing_check` 확인 기록 없음 · `missing_photo` 사진 증빙 없음 |

- `basis=completed_before_incident` · 사고 전 가장 최근 교차 확인 완료 증빙. `status`는 완료 당시 상태, `completedAt`은 완료 시각. 짝 변경 후에도 보존. 완료 후부터 사고 전까지의 확인·불일치는 `subsequentChecks[]`로 구분
- `basis=latest_check_before_incident` · 완료 증빙 없음 → 사고 당시 동료와의 마지막 확인 상태 표시. `completedAt=null`
- 사고 이후 확인은 제외. 부족한 증빙은 `dataGaps`에도 표시.

## 공통 오류

```json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "입력값을 확인해 주세요."
  }
}
```

| 상태 · 코드 | 처리 |
| --- | --- |
| `400` / `413` | 입력값·필수 필드 / 본문·파일 크기 확인 |
| `401 INVALID_CREDENTIALS` | 로그인 이메일·비밀번호 확인 |
| `401 UNAUTHORIZED` | 다시 로그인 |
| `403` / `404` | 현장·역할·기록 ID 확인. 볼 권한이 없는 사건이나 사진도 `404` 가능 |
| `409 SHIFT_REQUIRED` | 해당 현장에 출근했는지 확인 |
| `409 SHIFT_ENDED` / `ATTENDANCE_NOT_TODAY` | 오늘 근무 중인 출근 기록인지 확인 |
| `409 INCIDENT_STATE_CONFLICT` / `CHECK_IN_NOT_PENDING` | 사건 상세 / 현재 안전 확인 상태 재조회 |
| `409 REQUEST_ID_CONFLICT` | 같은 `requestId`로 보낸 이전 요청 본문과 대조 |
| `429` | `Retry-After`가 있으면 해당 초만큼 기다린 후 재시도 |
| 시간 초과 / `5xx` | 저장 여부 미확인. 출근은 오늘 기록, 호출은 진행 중 사건을 먼저 조회 |
