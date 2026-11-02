# 안전고리 · 작업자 Android 앱

Kotlin과 Jetpack Compose로 구현한 네이티브 앱입니다. 관리자 웹은 [safeloop](https://github.com/jmwshye/safeloop) 저장소에서 관리합니다.

## Android Studio에서 열기

`File → Open`에서 **이 저장소의 최상위 폴더**를 선택합니다. `app` 하위 폴더를 선택하지 마세요.

현재 노트북에서는 한글 경로 관련 Gradle 오류를 피하도록 다음 경로를 준비했습니다.

```text
C:\Users\Public\Documents\ESTsoft\CreatorTemp\safeloop-worker-app
```

이 경로와 `캡스톤\safeloop-app-`는 같은 파일을 가리킵니다. Sync 후 실행 구성 `app`을 선택하고 실행하세요. Gradle JDK는 **17**, Android SDK는 **35**입니다. 설치된 도구를 재사용하도록 Gradle 8.9 / AGP 8.7.3 / Kotlin 2.0.21로 맞췄습니다. 기존 SDK 37·JDK 25 설정과 달라졌으므로 팀원도 동기화 시 이 버전을 사용해야 합니다.

## 화면과 동작

- 오늘: 출퇴근, 예상 일당, 작업 전 점검, 안전 동료, 최근 기록
- 안전: 3항목 점검, 동료 확인, 실제 가속도 센서 감시, 안전 확인 요청 처리
- 신고: 카메라 촬영, 위험 분류·구역·내용·익명 설정, 신고 목록
- 기록: 월별 달력, 출퇴근 기록과 예상 일당, CSV 공유, 안전 타임라인
- 내 정보: 이름·작업 구역, 알림 설정, 서버 연결, 시연 도구

119 버튼은 모든 기본 화면과 상세 창에서 전화 앱을 엽니다. 실제 전화 연결이나 신고 접수로 기록하지 않습니다.

## 체험 모드와 서버 연결

처음 실행하면 **체험 모드**입니다. 체험 표시가 항상 나타나며 데이터는 이 기기에 저장됩니다. 다른 사람에게 푸시를 전송하지 않습니다. 내 정보의 시연 도구로 본인 무응답·동료 요청을 재현할 수 있습니다.

본인 확인은 15초 동안 진행하고, 무응답이면 동료 확인 대기로 전환합니다. 동료는 수락 → 현장 도착 → 사진 촬영 → 직접 안전 확인 순서로 완료합니다. 수락만 하거나 사진만 찍어서 완료할 수 없습니다. 출퇴근은 점검 완료와 독립적입니다.

실제 연결은 내 정보 → 서버 연결에서 계정과 현장 참여 코드를 입력합니다. 같은 Firebase 프로젝트에 관리자 웹의 `functions`, Firestore·Storage 규칙과 FCM·Cloud Tasks가 배포되어 있어야 합니다. 기존 `command` callable API(`asia-northeast3`)를 사용하며, 팀원의 `safety_events` 직접 기록 API는 변경하지 않았습니다. `SafetyModels.kt`, `SensorDetector.kt` 원본도 보존했습니다.

`google-services.json`이 없으면 체험 모드로 빌드할 수 있습니다. 실제 연결 시 앱 ID `com.safeloop.app`에 맞는 설정을 사용하세요. 서비스 계정 키와 서명 키는 저장소에 넣지 마세요.

## 센서와 검증 범위

감시는 사용자가 켜는 포그라운드 서비스입니다. 충격 후 일정 시간 움직임이 적은 패턴을 위험 **추정**으로 처리합니다. 휴대폰 낙하와 사람의 사고를 완전히 구분할 수 없으며 GPS 속도 변화만으로 판정하지 않습니다. 휴대 상태·권한·배터리 제한에 따라 누락될 수 있습니다. 에뮬레이터의 시연 성공이 실제 현장 감지 성능이나 여러 기기의 알림 수신을 증명하지 않습니다.

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. 유닛 테스트는 센서 시간 조건, 출퇴근 독립성, 사진 확인 순서, 담당자 제한, CSV 수식 이스케이프를 검사합니다. 실제 FCM 수신, 잠금 화면·백그라운드 센서와 실제 기기 간 확인은 별도 검증이 필요합니다.

현재 개발 브랜치는 `feat/app-worker-experience`입니다. Android Studio에서 확인한 후 팀 규칙에 따라 PR로 검토하며 `main`에 직접 올리지 않습니다.

실행한 검사와 남은 통합 시험은 [검증 기록](docs/VALIDATION.md)을 확인하세요.

에뮬레이터 실행 화면: [오늘](docs/screens/home.png), [안전 확인 알림](docs/screens/safety-requests.png).
