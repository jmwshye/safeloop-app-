# SAFELOOP 백엔드

## 앱·관리자 웹에서 연결할 때

- [API 명세](API.md)에서 요청·응답·오류 처리 확인
- [기능 흐름과 서버 구조](FLOW.md)에서 전체 동작 확인
- PC: `http://127.0.0.1:3000` · Android 에뮬레이터: `http://10.0.2.2:3000`
- 실제 폰: USB 연결 → `adb reverse tcp:3000 tcp:3000` 실행 → PC 주소 사용
- 로컬 HTTP: 디버그 앱에서 허용

## 처음 실행할 때

- 준비: Node.js **24.15 이상인 24.x**·PostgreSQL 18·사용할 DB·DB 계정
- Node.js 버전 확인: `node --version`
- 명령 실행 위치: `backend` 폴더

1. PostgreSQL 시작
2. `.env.example`을 같은 폴더의 `.env`로 복사
3. `.env`의 `DATABASE_URL`에 DB 주소·계정·비밀번호 입력
4. 의존성 설치 → 빌드 → DB 구조 반영 → 서버 실행

```powershell
npm ci
npm run build
npm run db:migrate
npm run start:dev
```

- `db:migrate`: `drizzle/`의 변경 파일을 순서대로 적용
- 현재 변경 파일: `0000_auth` ~ `0003_site_workflows`
- 이미 적용한 변경: 건너뛰기

상태 확인 주소: `http://127.0.0.1:3000/health`

| 상황 | 결과 |
| --- | --- |
| 정상 | `200 {"status":"ok","database":"up"}` |
| 실행 중 DB 연결 끊김 | `503 {"status":"error","database":"down"}` |
| DB 연결 복구 | 다음 요청부터 `200` |
| 시작할 때 DB 연결 실패 | 서버 실행 중단 |

## 연결 설정을 바꿀 때

- `.env` 수정 후 서버 재시작
- 실제 `.env`와 인증 키 파일은 Git에 올리지 않기

| 설정 | 입력 |
| --- | --- |
| `DATABASE_URL` | 사용할 PostgreSQL의 주소·DB 이름·계정·비밀번호 |
| `HOST` · `PORT` | 기본 `127.0.0.1` · `3000` |
| `ADMIN_WEB_ORIGIN` | 관리자 웹을 다른 주소에서 실행할 때 웹 주소 하나. 예 `http://localhost:5173`. 화면 경로 제외 |
| `FIREBASE_PROJECT_ID` | 앱과 같은 Firebase 프로젝트 ID. 실제 푸시를 보낼 때 설정 |
| `GOOGLE_APPLICATION_CREDENTIALS` | Firebase 서비스 계정 JSON의 절대 경로. 파일은 저장소 밖에 보관 |

FCM 설정 두 값 모두 미입력: API 실행 가능 · 실제 푸시 발송 없음

## 다시 실행하거나 수정했을 때

| 상황 | 실행 |
| --- | --- |
| 평소 개발 시작 | DB 시작 후 `npm run start:dev` |
| 설치 버전 파일이 바뀜 | `npm ci` |
| DB 변경 파일이 추가됨 | `npm run build` → `npm run db:migrate` |
| 빌드 결과로 실행 | `npm run build` → `npm run start:prod` |
| 서버 종료 | 실행 창에서 **Ctrl+C** |

DB 구조 변경 순서:

1. `src/database/`에서 DB 구조 수정
2. 새 스키마 파일 추가 시 `all-schema.ts`에서도 내보내기
3. `npm run db:generate -- --name=변경이름`으로 변경 파일 생성
4. 생성된 `drizzle/` 파일도 함께 공유

실행 파일을 다른 환경으로 옮길 때:

| 함께 옮길 파일 | 용도 |
| --- | --- |
| `dist/` | 빌드된 서버 실행 파일 |
| `drizzle/` | DB 변경 반영 |
| `assets/fonts/` | 한글 PDF 생성 |
| `package.json` · `package-lock.json` | 의존성 설치 |

대상 환경에서 의존성 설치 후 `.env` 설정

## 검사할 때

| 명령 | 확인 내용 |
| --- | --- |
| `npm run format:check` | 코드 형식 |
| `npm run lint` | 정적 코드 검사 |
| `npm run build` | 빌드 |
| `npm test` | 빌드 후 상태 확인·인증·현장·근무·긴급 대응·위험 신고·기록·보고서 검사 |

- 검사용 계정이 소유한 **별도 DB** 준비
- DB 이름: `_test`로 끝나게 지정
- 테스트에서 테이블 자동 준비 및 테스트 데이터 정리

```powershell
$env:TEST_DATABASE_URL = 'postgresql://DB계정:DB비밀번호@127.0.0.1:5432/safeloop_test'
npm test
Remove-Item Env:TEST_DATABASE_URL
```

- `TEST_DATABASE_URL` 미설정: DB 통합검사 건너뛰기
- 전체 검사 완료 기준: `fail 0` · `skipped 0`
- 푸시: 모의 발송 검사. 실제 폰 수신은 별도 확인

| GitHub CI | 내용 |
| --- | --- |
| 실행 시점 | PR 생성·갱신, `main` 푸시 |
| 검사 환경 | Node.js 24·임시 PostgreSQL 18 |
| 검사 순서 | 설치 → 코드 형식 → 정적 검사 → 빌드·전체 테스트 |
| 결과 확인 | PR의 **Checks → Backend checks** |

PowerShell에서 `npm.ps1` 실행 차단 시 `npm` 대신 `npm.cmd` 사용

## Windows에서 DB를 켜거나 끌 때

- Windows 서비스로 설치: 해당 PostgreSQL 서비스 시작·중지
- 사용자 폴더에 설치: 실제 설치·데이터 위치에 맞춰 아래 명령 실행

```powershell
$pgBin = "$env:LOCALAPPDATA\Programs\PostgreSQL\18\pgsql\bin"
$pgData = "$env:LOCALAPPDATA\SAFELOOP\postgresql\18\data"
& "$pgBin\pg_ctl.exe" start -D $pgData -l "$pgData\server.log" -w
```

DB 종료: `& "$pgBin\pg_ctl.exe" stop -D $pgData -m fast -w`
