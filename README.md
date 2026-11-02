# 안전고리 · 작업자 Android 앱

Kotlin · Jetpack Compose. 관리자 웹: [safeloop](https://github.com/jmwshye/safeloop).

## Android Studio에서 열기

`File → Open` → **저장소 최상위 폴더** 선택.

JDK **17**, Android SDK **35**, Gradle **8.9**. Sync 후 실행 구성 `app` 선택.

## 화면과 동작

- 오늘: 출퇴근, 예상 일당, 작업 전 점검, 안전 동료, 최근 기록
- 안전: 3항목 점검, 동료 확인, 실제 가속도 센서 감시, 안전 확인 요청 처리
- 신고: 카메라 촬영, 위험 분류·구역·내용·익명 설정, 신고 목록
- 기록: 월별 달력, 출퇴근 기록과 예상 일당, CSV 공유, 안전 타임라인
- 내 정보: 이름·작업 구역, 알림 설정, 서버 연결, 시연 도구

119 버튼: 전화 앱 열기. 실제 통화 연결·신고 접수 여부는 확인 불가.

## 체험 모드와 서버 연결

첫 실행: **체험 모드**. 기기 안에만 저장, 실제 푸시 전송 없음. 내 정보 → 시연 도구에서 본인 무응답·동료 요청 재현.

실제 호출은 NestJS 서버가 본인 응답 15초·동료 호출 확대 30초를 관리. 동료는 수락 → 도착 → 상태 관찰 → 완료 사유 선택. 현장 사진은 선택. 출퇴근은 점검 완료와 독립적.

실제 연결: 내 정보 → 서버 연결 → 계정·현장 코드 입력 → 현장 확인 후 참여. 계정·출근·점검·신고·사진·보고서는 NestJS API와 PostgreSQL 사용.

- **API 주소** · 기본 디버그 주소 `http://10.0.2.2:3000`(에뮬레이터). 빌드 시 `-PSAFELOOP_API_URL=주소`로 변경.
- **실제 폰의 로컬 연결** · USB 연결 후 `adb reverse tcp:3000 tcp:3000`, API 주소는 `http://127.0.0.1:3000`.
- **릴리스** · HTTPS API 주소 필수. 로컬 HTTP 허용은 디버그 앱에만 적용.
- **푸시만 FCM** · `app/google-services.json`과 서버 FCM 설정 필요. 설정 파일이 없어도 NestJS 로그인·API 연동 가능. Firebase Auth·Firestore·Functions·Storage 서버는 사용하지 않음.
- **인증** · 로그인 토큰은 Android Keystore로 암호화. 만료 또는 `401`이면 다시 로그인. 서비스 계정 키·서명 키는 Git에 넣지 않기.

## 센서와 검증 범위

- 감시: 사용자가 켜는 포그라운드 서비스
- 감지: 충격 후 움직임 감소를 위험 **추정**으로 기록. 휴대폰 낙하와 실제 사고 구분 불가
- 기기 확인: 휴대 상태·권한·배터리 제한, 잠금 화면·실제 FCM 수신은 실물 폰에서 검사

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug -PSAFELOOP_API_URL=http://10.0.2.2:3000
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. 유닛 테스트: 센서 조건, 화면 흐름, API 전송·인증 헤더, 재시도·계정/현장 격리. 실제 FCM 수신, 잠금 화면·백그라운드 센서, 기기 간 대응은 실제 폰에서 별도 확인.

이전 시안의 검사 내역: [검증 기록](docs/VALIDATION.md).

에뮬레이터 실행 화면: [오늘](docs/screens/home.png), [안전 확인 알림](docs/screens/safety-requests.png).
