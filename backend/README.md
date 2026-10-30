# SAFELOOP 백엔드

회원가입·로그인·출근·안전점검 3항목 API와 실행에 필요한 NestJS·PostgreSQL·Drizzle 구성.

## 앱에서 서버에 연결할 때

- [앱 API 명세](API.md): 회원가입·로그인·출근·안전점검 요청·응답과 오류 처리
- 개발 PC 기본 주소: `http://127.0.0.1:3000/`
- `GET /`: 연결 확인용 `200`, 문자열 `Hello World!`
- 회원가입·로그인·출근·안전점검은 **AWS 미배포**, 실제 앱 연동은 미확인. GitHub 공유와 서버 배포는 별도 작업

## 처음 실행할 때

필요한 환경: **Node.js 24·PostgreSQL 18**, 사용할 DB와 DB 계정.
아래 명령은 프로젝트의 `backend` 폴더에서 실행.

1. DB를 시작하고 `.env.example`을 같은 폴더의 `.env`로 복사
2. `.env`의 `DATABASE_URL`을 실제 DB 주소·계정·비밀번호로 변경. 실제 `.env`는 Git에 올리지 않기
3. 아래 명령 실행

```powershell
npm ci
npm run build
npm run db:migrate
npm run start:dev
```

`db:migrate`는 `0000_auth`·`0001_attendance`를 순서대로 적용해 `users`·`sessions`·`attendances` 테이블을 준비. 이미 적용한 변경은 건너뜀.
브라우저에서 `http://127.0.0.1:3000/`을 열어 `Hello World!` 확인. 서버 시작 시 DB 연결에 실패하면 실행도 중단.

## 다시 실행하거나 수정했을 때

| 상황 | 명령 |
| --- | --- |
| 평소 개발 시작 | DB 시작 후 `npm run start:dev` |
| `package-lock.json`이 바뀜 | `npm ci` |
| `drizzle/`이 바뀜 | `npm run build` → `npm run db:migrate` |
| 빌드한 결과로 실행 | `npm run build` → `npm run start:prod` |
| 서버 종료 | 실행 창에서 **Ctrl+C** |

DB 구조 변경: `src/database/schema.ts` 수정 → `npm run db:generate -- --name=변경이름` → 빌드·마이그레이션.
생성된 `drizzle/` 파일도 코드와 함께 공유.

<details>
<summary>Windows에서 DB 시작·종료</summary>

Windows 서비스로 설치했다면 해당 PostgreSQL 서비스를 시작·중지.
사용자 폴더에 직접 설치한 경우 아래 명령 사용. 설치·데이터 위치가 다르면 경로를 맞춤.

```powershell
$pgBin = "$env:LOCALAPPDATA\Programs\PostgreSQL\18\pgsql\bin"
$pgHome = "$env:LOCALAPPDATA\SAFELOOP\postgresql\18"
& "$pgBin\pg_ctl.exe" start -D "$pgHome\data" -l "$pgHome\server.log" -w
```

DB도 종료하려면:

```powershell
& "$pgBin\pg_ctl.exe" stop -D "$pgHome\data" -m fast -w
```

</details>

## 검사할 때

| 명령 | 확인 내용 |
| --- | --- |
| `npm run format:check` | 들여쓰기·코드 형식 |
| `npm run lint` | 정적 코드 검사 |
| `npm run build` | 빌드 |
| `npm test` | 비밀번호 처리 검사. 별도 테스트 DB를 지정하면 회원가입·로그인·출근·안전점검 통합검사도 실행 |

PowerShell에서 `npm.ps1` 실행이 차단되면 `npm` 대신 `npm.cmd` 사용.

### API와 DB를 함께 검사할 때

이름이 **`_test`로 끝나는 별도 DB**를 준비하고 접속 정보를 아래에 입력.
테이블은 검사 시작 시 자동 준비하고, 검사에서 만든 계정·로그인·출근·점검 정보는 종료 시 정리.

```powershell
$env:TEST_DATABASE_URL = 'postgresql://DB계정:DB비밀번호@127.0.0.1:5432/safeloop_auth_test'
npm test
Remove-Item Env:TEST_DATABASE_URL
```

`TEST_DATABASE_URL`이 없으면 DB 통합검사는 **건너뜀(skipped)**. 통과한 것으로 간주하지 않기.
