# 안전고리 SafeLoop

> 소규모 건설 현장 작업자의 근로 기록과 동료 안전 확인을 한 앱으로 잇는 캡스톤 프로젝트

휴대폰이 충격과 이후의 움직임 감소를 감지하면 본인에게 먼저 묻고, 응답이 없으면 같은 구역 동료가 현장을 확인합니다. 출퇴근 기록은 안전점검 완료 여부와 관계없이 작업자 본인의 기록으로 남습니다.

사고를 확정하거나 119에 자동으로 신고하지 않습니다. 센서 판단은 언제나 **추정**으로 표시합니다.

## 지금 상태

**`main`은 최신 상태가 아닙니다.** 최신 앱과 백엔드는 아직 합쳐지지 않은 브랜치에 있습니다.

| 영역 | 위치 | 상태 |
| --- | --- | --- |
| 작업자 앱 초안 | `main` | 단일 화면 시제품. 이름·위치가 코드에 고정 |
| 작업자 앱 최신 | `feat/app-worker-experience` | 5개 탭, 체험 모드, 안전 확인 흐름. PR 없음 |
| 백엔드 | `taegyu/*` (PR #2~#5) | NestJS·PostgreSQL. 인증·출근·점검·상태 확인 |
| 관리자 웹·Firebase 함수 | [jmwshye/safeloop](https://github.com/jmwshye/safeloop) | 모의 데이터 시연. 실제 Firebase 미배포 |

실기기 센서 정확도, 기기 간 알림, 실제 서버 통합은 아직 검증하지 않았습니다. 현황과 결정할 내용은 [docs/STATUS.md](docs/STATUS.md)를 보세요.

## 문서

| 문서 | 내용 |
| --- | --- |
| [docs/STATUS.md](docs/STATUS.md) | **먼저 읽기.** 현황, 팀 결정 사항, 다음 작업 |
| [프로젝트 헌장](https://claude.ai/artifact/QadKtQskLYTUSGinpBuT5c) | 제품의 최종 기준. 현재 비공개 |
| [docs/기획서.md](docs/기획서.md) | 헌장 요약: 문제, 사용자, 범위, 안전 흐름, 제품 규칙 |
| [docs/wireframe.md](docs/wireframe.md) | 화면 기준 문서. 검토용 초안 |
| [CONTRIBUTING.md](CONTRIBUTING.md) | 브랜치·커밋·PR·병합 규칙 |
| [CLAUDE.md](CLAUDE.md) | Claude Code 작업 기준 |

API 명세는 백엔드 PR이 합쳐지면 `backend/API.md`가 기준입니다.

## 구조

```text
safeloop-app-/
├─ app/                 # Kotlin·Jetpack Compose 작업자 앱
├─ docs/                # 기획서·현황·와이어프레임
├─ .github/             # PR 양식
└─ CONTRIBUTING.md      # 팀 규칙
```

브랜치가 합쳐지면 `backend/`(NestJS)와 `.github/workflows/`(자동 검사)가 추가됩니다.

## 실행

### 앱

Android Studio에서 **저장소 최상위 폴더**를 엽니다. `app` 폴더만 열지 마세요.

```powershell
.\gradlew.bat assembleDebug
```

`main`은 `app/google-services.json`을 사용합니다. 최신 앱은 이 파일이 없으면 체험 모드로 빌드되며 JDK 17, Android SDK 35, Gradle 8.9를 씁니다. `feat/app-worker-experience`의 README와 [검증 기록](https://github.com/jmwshye/safeloop-app-/blob/feat/app-worker-experience/docs/VALIDATION.md)을 함께 확인하세요.

한글이 포함된 경로에서 Gradle 오류가 나면 영문 경로에서 빌드합니다.

### 백엔드

Node.js 24와 PostgreSQL 18이 필요합니다. 코드는 PR #2~#5의 `taegyu/ci/backend-checks` 브랜치에 있습니다.

```powershell
cd backend
npm ci
npm run build
npm run db:migrate
npm run start:dev
```

`.env.example`을 `.env`로 복사해 `DATABASE_URL`을 넣은 뒤 실행합니다. 확인 주소는 `http://127.0.0.1:3000/health`입니다.

## 팀

| 이름 | 역할 |
| --- | --- |
| 민철 | 와이어프레임·디자인 |
| 태규 | 백엔드 (브랜치 기준 추정, 확인 필요) |
| 그 외 | 팀에서 확인 후 작성 |
