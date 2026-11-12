# 차량 지원 진단 (STEP 4, STEP 4 보완, 읽기 전용)

- 날짜: 2026-11-12
- 목적: 쉼표 캐빈이 쓸 문·시트·공조·앰비언트 색상을 Pleos 에뮬레이터가 지원하는지, 어떤 영역·범위·현재값을 주는지 쓰기 없이 확인
- 결론: 진단한 9개 항목 모두 capability 응답이 `READ_WRITE`였고, 읽기 대상 현재값도 모두 응답이 왔다. 조수석 문은 열림·닫힘이 조회와 구독 양쪽에 반영됐다(curl 시험, 5장). 앱을 열기 전에 열어 둔 문도 첫 조회에서 바로 `1`로 나왔다. 앱은 차량에 쓰지 않았다.
- TextToSpeech: STEP 4 보완에서 SDK가 넣는 INTERNET 권한을 매니페스트 병합 규칙으로 제거한 뒤 `Mode.ON_DEVICE` 초기화를 시도했다. **초기화 성공**(2회)을 실행 확인했고, 이어서 한국어 설정을 요청했다. 실제 음성 합성과 재생은 하지 않아 검증하지 않았다(6장).
- STEP 4 보완에서 [구독 중지]가 같은 실행의 늦은 알림과 이후 재구독까지 막도록 고쳤다(2장).

## 1. 환경

STEP 3과 같은 호스트·빌드·에뮬레이터([ambient-brightness.md](ambient-brightness.md) 1장)를 썼다. 에뮬레이터는 다시 띄우거나 재부팅하지 않았다.

| 항목 | 값 |
| --- | --- |
| 기기 확인 | serial `emulator-5554`, `ro.kernel.qemu=1`(조작 명령마다 확인), AAOS 사용자 10 |
| SDK·서비스 | `ai.pleos.playground:Vehicle:2.3.2`, Pleos Vehicle Service 2.3.22 |
| 추가 권한 | `pleos.car.permission.CAR_DOORS`, `pleos.car.permission.CAR_SEATS`, `pleos.car.permission.CAR_CLIMATE`, (보완) `pleos.car.permission.TTS_SERVICE` (모두 normal, `granted=true`). 기존 `CAR_LIGHTS`·`CONTROL_CAR_LIGHTS`·`android.car.permission.CAR_POWERTRAIN`은 유지 |
| 병합 매니페스트·APK | 병합 매니페스트와 최종 APK(`aapt2 dump permissions`) 모두 `INTERNET` 없음. exported 컴포넌트는 `MainActivity`와 기존 androidx `ProfileInstallReceiver`(`android.permission.DUMP`로 보호)뿐. TTS 의존성으로 androidx `InitializationProvider`가 추가됐고 exported=false |
| Virtual Console | 이미 활성화된 브리지를 그대로 썼다. 다시 열지 않았다 |

## 2. 방법

- 앱의 [지원 진단(읽기 전용)] 탭에서 [지원 조회 시작]을 눌러야 조회가 시작된다.
- 조회 순서는 다음과 같다. 항목마다 지원 확인 후 현재값을 읽는다.
  1. 문
  2. 운전석 등받이 위치
  3. 등받이 이동(지원 확인만)
  4. 공조 전원·자동·듀얼·풍량·온도
  5. 앰비언트 색
- 조회마다 응답 상한을 5초로 둔다.
  - 화면에는 조회 중 / 응답 / 미지원 / 오류 / 응답 없음 / 건너뜀을 구분해 표시한다.
  - 응답이 없으면 성공으로 표시하지 않는다.
  - 상한이 지난 뒤 온 응답, 이전 실행의 응답, 화면이 닫힌 뒤 온 콜백은 무시한다.
  - `null`은 "값 없음"으로 표시하고 물리 상태로 보지 않는다.
- 현재값을 읽는 영역:
  - 문: `ROW_1_RIGHT`
  - 등받이: 운전석 `ROW_1_LEFT`를 포함한 영역
  - 공조·색: capability가 준 모든 영역
- 문과 등받이는 위치 구독도 건다.
- [구독 중지] (STEP 4 보완):
  - 누르면 다음 [지원 조회 시작] 전까지 구독을 다시 만들지 않는다. 진행 중인 현재값 조회는 끝까지 한다.
  - 구독마다 세대 번호를 두고, 중지·새 실행·화면 종료 때 번호를 바꾼다. 이전 세대의 알림과 오류는 화면을 바꾸지 않는다.
  - 등록이 바로 예외로 실패하면 "구독 · 구독 등록 실패"를 오류 행으로 표시하고, 구독 중 목록에 넣지 않는다.
  - 고치기 전 코드에서 테스트 3개가 실패하는 것으로 결함을 먼저 재현했다. ① 중지 뒤 같은 실행의 알림·오류가 화면을 바꿈 ② 문 구독 뒤 등받이 조회 대기 중 중지하면 등받이 구독이 다시 생김 ③ 등록 예외가 화면에 안 나옴. 고친 뒤 모두 통과했다.
  - 에뮬레이터에서는 조회 → [구독 중지](구독: 중지됨) → 다시 조회(구독 2개 복귀)를 확인했다.
- 앱 코드에는 set·move·STOP 호출이 없다(`PleosVehicleReadPort`). 기존 밝기 검증 버튼도 이번에는 누르지 않았다.
- 문 열기·닫기는 **curl 시험**이다. Virtual Console 공개 Door 카드의 FR 토글이 보내는 요청(`PUT /vehicle/body?door_fr=1|0`)을 curl로 그대로 보냈다. 카드를 실제 UI로 누르지 못한 이유는 두 가지다.
  - 콘솔을 띄운 브라우저와 자동화 도구(Playwright MCP)의 연결이 끊겼다. 다시 붙으려 하면 "Browser is already in use"로 실패했다(STEP 4, STEP 4 보완 모두).
  - 콘솔을 다시 열거나 브라우저를 다시 띄우면 콘솔 값이 차량에 발행될 수 있다.
- API 이름·인자·권한은 Vehicle SDK AAR의 클래스와 `@RequiresPermission` 주석(javap)으로 확인했다.

## 3. 권한 (SDK AAR 주석 기준)

| API | 권한 |
| --- | --- |
| `checkDoorPositionCapabilities`, `getCurrentDoorPosition`, `registerDoorPosition` / `unregisterDoorPosition` | `pleos.car.permission.CAR_DOORS` |
| `checkSeatBackrestAnglePositionCapabilities`, `getCurrentSeatBackrestAnglePosition`, `registerSeatBackrestAnglePosition`, `checkMoveSeatBackrestAngleCapabilities` | `pleos.car.permission.CAR_SEATS` |
| `checkHvacPower/Auto/Dual/FanSpeed/TemperatureCapabilities`, `getHvacPowerState`, `getHvacAutoState`, `getHvacDualState`, `getHvacFanSpeed`, `getHvacTemperature` | `pleos.car.permission.CAR_CLIMATE` |
| `checkAmbientLightBrightnessColorCapabilities`, `getAmbientLightBrightnessColor` | `pleos.car.permission.CAR_LIGHTS` |
| 쓰기 API(이번에 호출하지 않음) | `CONTROL_CAR_DOORS`·`CONTROL_CAR_SEATS`·`CONTROL_CAR_CLIMATE`·`CONTROL_CAR_LIGHTS` 주석이 있음 |

위 읽기 권한만으로 모든 조회가 응답했다(실행 확인). 권한을 뺐을 때 거부되는지는 시험하지 않았다.

## 4. 결과표

표의 접근·영역·범위·현재값은 모두 실제 응답 값이다(실행 확인). 현재값은 첫 진단과 문을 닫은 뒤 마지막 진단에서 같았다. 문은 예외로, 시험 중에 `1`이 됐다(5장).

| 항목 | 접근 | 영역·범위 | 현재값 | 문서 기준 |
| --- | --- | --- | --- | --- |
| 조수석 문 위치 | READ_WRITE | `ROW_1_LEFT`·`ROW_1_RIGHT`·`ROW_2_LEFT`·`ROW_2_RIGHT` 각 0..1, `REAR`·`HOOD` 각 0..100 | `ROW_1_RIGHT` = 0 | 문서: "Min is typically 0 for fully closed", 최대값은 차량마다 다름 |
| 운전석 등받이 위치 | READ_WRITE | `ROW_1_LEFT`·`ROW_1_RIGHT`·`ROW_2_LEFT`·`ROW_2_RIGHT` 각 0..100 | `ROW_1_LEFT` = 0 | Console에 카드 없음. 단위는 문서에 없음 |
| 등받이 이동 지원 | READ_WRITE | 같은 4좌석(범위 없음) | 조회 대상 아님 | 문서: 방향 제어만 제공. 이동은 금지 범위라 실행하지 않음 |
| 공조 전원 | READ_WRITE | 영역 1개 = `ROW_1_LEFT+ROW_1_RIGHT+ROW_2_CENTER+ROW_2_LEFT+ROW_2_RIGHT` | false | — |
| 공조 자동 | READ_WRITE | 영역 1개 = `ROW_1_LEFT+ROW_1_RIGHT` | false | — |
| 공조 듀얼 | READ_WRITE | 영역 1개 = `ROW_1_LEFT+ROW_1_RIGHT` | false | — |
| 풍량 | READ_WRITE | `ROW_1_LEFT+ROW_1_RIGHT` 1..8 | 3 | 문서: 범위는 차량마다 다름 |
| 목표 온도(°C) | READ_WRITE | `ROW_1_LEFT` 15.0..30.0, `ROW_1_RIGHT` 15.0..30.0. 간격 `incrementCelsius` 0.5°C, 설정 배열 raw `[150, 300, 5, 580, 880, 10]` | `ROW_1_LEFT` 22.0, `ROW_1_RIGHT` 22.0 | 문서: 범위는 차량마다 다름 |
| 앰비언트 색(ARGB) | READ_WRITE | 영역 1개 = 다섯 좌석 묶음 | `0x00FFFFFF` (raw 16777215) | 7장 참고 |

운전석을 포함하는 공조 영역은 항목마다 다르다.
- 전원: 다섯 좌석 묶음 1개
- 자동·듀얼·풍량: 앞 두 좌석 묶음 1개
- 온도: 운전석 단독 영역(조수석은 별도 영역)

공조 값은 바꾸지 않았다.

## 5. 조수석 문 (curl 시험)

시각은 에뮬레이터 로그 기준이다. 아래 열기·닫기 입력은 모두 curl로 보낸 공개 Door 카드 요청이다(2장). 실제 Console UI로 Door 카드를 누르는 시험은 하지 못했다.

| 순서 | 입력(curl) | 앱 | Virtual Console |
| --- | --- | --- | --- |
| 시험 전 | — | 조회 0, 구독 첫 알림 0 | `front_right` false |
| 열기 | `door_fr=1` | 구독 알림 1 (01:55:29.825) | true |
| 닫기 | `door_fr=0` | 구독 알림 0 (01:55:35.793) | false |
| 앱 실행 전에 열기 → 앱 강제 종료 후 다시 실행. 시작 명령 9.9초 뒤 조회 | `door_fr=1` | 지원 확인 0.40초, 현재값 `1`(조회 시작 0.46초 뒤), 구독 첫 알림 1(등록 1.78초 뒤) | true |
| 같은 조건. 앱 표시 3.2초 뒤(시작 명령 5.2초 뒤) 조회 | (열린 채) | 지원 확인 0.38초, 현재값 `1`(0.50초 뒤), 구독 첫 알림 1(1.79초 뒤) | true |
| 복원: 닫기 | `door_fr=0` | 구독 알림 0 (02:00:29.066) | false |
| 마지막 진단 | — | 현재값 0, 구독 첫 알림 0 | false |

- 열린 문 첫 조회의 실제 시간: 조회 버튼을 누른 뒤 0.46초, 0.50초 만에 현재값 `1`이 왔다(성공 2회).
- 실행되지 않은 시도 2회:
  - 시작 약 1초 뒤 탭: 화면이 준비되기 전이라 조회가 실행되지 않았다.
  - 3초 뒤 탭 두 번을 간격 없이 보냄: 탭 전환만 되고 조회는 시작되지 않았다.
- 구독 첫 알림:
  - 새 프로세스에서 처음 건 문 구독은 등록 뒤 1.6~1.8초가 지나야 첫 알림이 왔다(3회).
  - 같은 프로세스에서 다시 등록하면 0.14초 만에 왔다(1회).
  - STEP 4 보완 시험에서는 같은 프로세스에서 화면을 새로 만든 뒤 첫 조회 때 문 구독 첫 알림이 **아예 오지 않았다**. 현재값 조회는 0으로 바로 왔고, 구독 중지까지 12.7초를 기다렸다. 다시 조회하면 등록 0.08초 뒤에 왔다.
- 결론:
  - 직접 조회(`getCurrentDoorPosition`)는 매번 맞는 값을 바로 줬다.
  - 구독 첫 알림만 기다리면 늦거나 오지 않아 "확인 중"에 머물 수 있다. 시작 조건은 직접 조회값으로 정하고, 구독은 변화 감지에만 써야 한다.
  - 이전 프로토처럼 문을 여닫아야 풀리는 멈춤은, 직접 조회를 쓰는 이 진단에서는 재현되지 않았다.
- 앱 표시 직후 3초 안의 조회 시간은 측정하지 못했다.
- 앱은 문에 쓰지 않았다. 시험 뒤 FR 문을 닫아 시험 전 상태(모두 닫힘)로 되돌렸다.

## 6. TextToSpeech 3.3.1 (ON_DEVICE 초기화)

- 문서: `pleos.car.permission.TTS_SERVICE`를 선언해야 한다. [Introduction](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/intro)은 `Mode.ON_DEVICE`가 네트워크와 앱 등록이 필요 없다고 설명한다.
- `release()` 이후 다시 쓰려면 새 객체를 만들어 초기화해야 한다(초기화·해제 문서).
- AAR(아티팩트 확인):
  - API: `TextToSpeech(Context, Mode)`, `initialize(OnInitializeListener)` → `onSuccess()` / `onFailure(ErrorType)`, `setLocale(Locale)`, `release()`. 모두 `TTS_SERVICE` 주석이 있다.
  - TextToSpeech AAR과 그 의존성 `ai.pleos.playground.security:universal-access:0.6.0`의 매니페스트가 모두 `android.permission.INTERNET`을 선언한다. TextToSpeech 쪽 주석은 "CCG Validation 체크를 위한 권한"이다.
  - `ai.pleos.playground.caas.intent.action.SPEECH_START`에 대한 `queries`가 있다. CAAS 최소 버전은 4.1.29다.
  - 함께 들어오는 의존성은 universal-access(네이티브 `libuacore.so` 포함), gson, appcompat이다. 디버그 APK가 약 47MB가 됐다.
- 앱 설정(STEP 4 보완):
  - `implementation "ai.pleos.playground:TextToSpeech:3.3.1"`
  - `<uses-permission android:name="pleos.car.permission.TTS_SERVICE" />`
  - `<uses-permission android:name="android.permission.INTERNET" tools:node="remove" />`: 정식 매니페스트 병합 규칙이다. SDK 바이너리와 보안 검사는 건드리지 않았다.
- 결과: 병합 매니페스트와 APK에 INTERNET이 없다.
- 진단 동작:
  - 사용자가 [TTS 초기화 시도]를 누르면 `Mode.ON_DEVICE` 객체를 새로 만들어 초기화한다. 응답 상한은 10초다.
  - 성공하면 `setLocale(Locale.KOREA)`를 요청한다. 이 API는 결과를 돌려주지 않으므로 화면에는 "한국어 설정 요청 완료, 실제 합성 미검증"으로만 표시한다.
  - 실패·응답 없음·새 시도·화면 종료 때는 객체를 해제한다. 그 뒤 온 콜백은 무시하고, 해제한 객체는 다시 쓰지 않는다.
  - 테스트 6개: 성공 / 실패 / 응답 없음 + 늦은 응답 / 새 시도 / 종료 후 / 초기화 중 예외

| 시도 | 결과 | 원본 로그 |
| --- | --- | --- |
| 1 | 버튼 후 0.18초에 `onSuccess`. 화면 "[초기화 성공] 시도 1 초기화 성공. 한국어 설정 요청 완료, 실제 합성 미검증" | 앱: `initialize - ON_DEVICE with listener` → `connectService` → `onServiceConnected` → `registerCallback` → `updateLocale ko_KR` → `setLocale ko_KR app.commacabin`. CAAS: `registerTtsCallback applicationId = app.commacabin:ondevice`, `setLocale(app.commacabin) ko-KR`, `saveTtsLocale(app.commacabin) {… app.commacabin=ko_KR …}` |
| 2 (새 시도) | 이전 객체 `release` → `disconnectService` → `unregisterCallback` 뒤 새 객체로 0.18초에 성공, 한국어 설정 요청 | CAAS: `unregisterTtsCallback` → `registerTtsCallback` → `setLocale(app.commacabin) ko-KR` |
| 화면 종료 | `onDestroy`에서 `release` → `disconnectService` → `unregisterCallback` | CAAS: `unregisterTtsCallback` |

- 오류·SecurityException·네트워크 관련 오류는 없었다.
- 에뮬레이터 서비스(읽기만 확인):
  - `ai.pleos.playground.caas` 4.2.10-coral-aosp(versionCode 4002010)가 설치돼 있다. 최소 요구 버전 이상이다.
  - `pleos.car.permission.TTS_SERVICE`는 이 패키지가 정의한 normal 권한이고, 설치 후 `granted=true`였다.
- 한계:
  - 한국어 설정은 서비스가 요청을 받아 저장한 로그까지만 확인했다.
  - 실제 한국어 합성·재생, 오디오 포커스 처리는 하지 않았다. speak·registerApp·HYBRID·SERVER·등록 키는 쓰지 않았다.
  - 뒤로 가기로는 화면이 끝나지 않아(Android 12+ 루트 화면) 해제 로그가 없었다. 화면을 실제로 끝낸 경우에만 해제를 확인했다.

## 7. 관찰

- **앰비언트 색 alpha**: 현재값 alpha가 `0x00`이었다(`0x00FFFFFF`).
  - STEP 2 조사 때 인용한 `setAmbientLightBrightnessColor` 문서는 "The value is a 32-bit ARGB color. Alpha is fixed to 0xFF (fully opaque)."라고 적는다. 이번 단계에서 문서를 다시 받지는 않았다.
  - 조회값을 그대로 원래값으로 돌려 쓸 수 있는지는 색 쓰기 검증 때 확인한다. 색은 바꾸지 않았다.
- **등받이 구독 첫 알림 중복**: STEP 4의 4번 등록 중 2번은 같은 값 0이 1ms 간격으로 두 번 왔다(보완 시험에서도 1번). 같은 값이 다시 오는 것을 값 변화로 보면 안 된다.
- **문 구독 첫 알림 누락**: 5장 참고. 구독 첫 알림을 현재값 대신 쓰면 안 된다.
- **Console 카드 표기와 SDK 범위 차이**: 제품 요구사항에 적은 Console 카드 범위(풍량 0~8, 목표 온도 16~30°C)와 SDK capability(풍량 1..8, 15.0..30.0°C)가 다르다. 앱은 capability 범위를 쓴다.
- **등받이**: Console에 카드는 없지만 SDK는 위치(0..100)와 이동 지원을 응답했다. 콘솔 상태 json에는 `seat.positions.recline`(4좌석 모두 0)이 있다. 이동을 하지 않아 위치가 바뀌는 것은 관찰하지 못했다.
- **앰비언트 모드**: 콘솔 숨김 항목 `ambient_light.mode`는 시험 전후 모두 On이었다. SDK 스위치는 OFF였다. STEP 3에서 관찰한 차이와 같다.

## 8. 최종 환경

STEP 4와 STEP 4 보완 모두 같은 값으로 끝났다. 보완 시험 전후 콘솔 상태(`followup/vc-state-before.json`, `followup/vc-state-after.json`)도 같다. 보완의 최종 SDK 조명 조회는 02:25:03 기준 OFF·70이다.

| 항목 | 시험 전 | 최종 |
| --- | --- | --- |
| 기어 | P | P |
| 문(콘솔) | 모두 닫힘 | 모두 닫힘 |
| 무드 조명(SDK) | 스위치 OFF, 밝기 70 (STEP 3 종료 기록) | 스위치 OFF, 밝기 70 |
| 콘솔 `ambient_light` | mode On, `#ffffff`, 70% | 같음 |
| 공조(콘솔·SDK) | 전원 off, 자동 off, 듀얼 off, 풍량 3, 22.0/22.0°C | 같음 |
| 등받이 | 콘솔 recline 4좌석 0, SDK `ROW_1_LEFT` 0 | 같음 |
| 복원 기록 | 없음 | 없음(앱 기록 파일 없음, 앱 시작 로그 "남은 복원 기록 없음") |

## 9. 빌드·테스트·린트와 Gradle 메모리

기본 검증 명령(JDK 17, `-D` 옵션 없음):

```
./gradlew :app:testDebugUnitTest --rerun :app:lintDebug :app:assembleDebug --console=plain
```

- 원인: 프로젝트에 `org.gradle.jvmargs`가 없어 Gradle daemon이 기본 heap 512MiB(`-Xmx512m`)로 떴다. 메모리를 많이 쓰는 `:app:lintAnalyzeDebug`·`:app:mergeExtDexDebug`가 `java.lang.OutOfMemoryError: Java heap space`로 실패했다.
- 조치(STEP 4 최종 정리): `gradle.properties`에 `org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8`을 추가했다. 전역 `~/.gradle`·셸·JDK 설정은 바꾸지 않았다.

프로젝트 설정 전 (`followup/`):

| 실행 | 옵션 | 결과 |
| --- | --- | --- |
| `gradle-verify-1.txt` | 기본 | 실패(exit 1). `lintAnalyzeDebug`·`mergeExtDexDebug` OOM |
| `gradle-verify-2.txt` | 기본 | 실패(exit 1). `mergeExtDexDebug` OOM(D8). `lintAnalyzeDebug`는 이번에 통과 |
| `gradle-verify-3-nodaemon.txt` | 단일 사용 daemon(로그 기준) | 실패(exit 1). `mergeExtDexDebug` OOM |
| `gradle-verify-4-xmx2g.txt` | 예외 옵션 `-Dorg.gradle.jvmargs=-Xmx2g` | 성공(exit 0). `mergeExtDexDebug` 실행, `lintAnalyzeDebug`는 UP-TO-DATE |

`gradle-verify-5-final-exact.txt`, `gradle-test-rerun.txt`도 설정 전 실행이다. 무거운 작업이 모두 UP-TO-DATE라 메모리 문제 해결의 근거로 보지 않는다.

프로젝트 설정 후 (`final/`, `-D` 옵션 없음):

| 실행 | 옵션 | 결과 |
| --- | --- | --- |
| `verify-default-*` | 기본 명령 그대로 | 성공(exit 0). 새 daemon이 `-Xmx2048m -Dfile.encoding=UTF-8`로 시작됨(`daemons-after-default.txt`). 테스트는 다시 실행, `lintAnalyzeDebug`·`mergeExtDexDebug`는 UP-TO-DATE |
| `verify-rerun-tasks-*` | 기본 명령 + `--rerun-tasks`(메모리 확인용) | 성공(exit 0). 51개 작업 모두 실행, `lintAnalyzeDebug`·`mergeExtDexDebug` 포함. OOM 없음 |
| `verify-default-2-*` | 기본 명령 그대로(개수 기록용) | 성공(exit 0) |

- 단위 테스트: 50개, 실패 0, 오류 0, 건너뜀 0(`AmbientProbeTest` 26, `SupportDiagnosticsTest` 8, `TtsDiagnosticsTest` 6, `FileRecoveryStoreTest` 6, `ParkingTrackerTest` 4). `--rerun-tasks` 실행과 마지막 기본 실행이 같다.
- 린트: 오류 0, 경고 6(`GradleDependency` 2, `AndroidGradlePluginVersion`·`OldTargetApi`·`DataExtractionRules`·`MissingApplicationIcon` 각 1). 보고서는 `--rerun-tasks` 실행에서 만들어졌다.
- 한계: 2048MiB로 충분한지는 이 호스트에서 전체 작업을 다시 실행한 1회로 확인했다.

## 10. 원본 자료

`app/build/reports/vehicle-support/`

| 경로 | 내용 |
| --- | --- |
| `sdk/` | Door·Seat·HVAC·Light javap 덤프(`*-verbose.txt`에 권한 주석), TTS 클래스와 AAR 매니페스트 |
| `docs/` | TTS 공식 문서 사본 |
| `tts-3.3.1-aar-AndroidManifest.xml`, `tts-deps.txt`, `compile-tts-dep.txt` | TTS AAR 매니페스트, 의존성 트리, 시험 컴파일 |
| `merged-AndroidManifest.xml`, `granted-permissions.txt` | 병합 매니페스트, 설치 후 권한 |
| `vc-state-before.json`, `vc-state-after-door-restore.json`, `vc-catalog.json` | 콘솔 상태 |
| `logs/logcat-run1.txt`, `logs/ui-run1.txt` | 첫 진단 |
| `logs/door-commands.txt` | 문 열기·닫기 요청과 앱·콘솔 결과 |
| `logs/door-first-read.txt`, `logs/logcat-door-first-read.txt`, `logs/logcat-door-early-full.txt` | 앱 실행 전 열린 문 첫 조회 |
| `logs/logcat-final.txt`, `logs/ui-final-run.txt` | 복원과 마지막 진단 |
| `logs/tts-service-check.txt` | TTS 서비스·권한 읽기 확인 |
| `screens/s01`~`s05` | 화면 |
| `followup/repro-stop-tests.txt`, `followup/repro-SupportDiagnosticsTest.xml`, `followup/fixed-stop-tests.txt` | 보완: 중지 결함 재현(실패 3)과 수정 후 통과 |
| `followup/logcat-tts-attempt1-app.txt`, `followup/logcat-tts-attempt1-full.txt`, `followup/logcat-tts-attempt2-and-close-full.txt`, `followup/logcat-tts-close-full.txt`, `followup/tts-ui-attempt*.txt`, `followup/t01`·`t02` | 보완: TTS 초기화·한국어 설정 요청·해제 |
| `followup/stop-ui.txt`, `followup/logcat-stop-device.txt`, `followup/t03-stop-observing.png` | 보완: 기기에서 구독 중지·재조회 |
| `followup/merged-AndroidManifest.xml`, `followup/apk-permissions.txt`, `followup/granted-permissions.txt`, `universal-access-0.6.0-AndroidManifest.xml` | 보완: 병합·APK 권한 |
| `followup/gradle-verify-*.txt`, `followup/lint-results-debug.*`, `followup/heapdumps/` | 보완: 빌드·테스트·린트(프로젝트 메모리 설정 전, OOM heap dump 포함. 9장) |
| `final/` | 최종 정리: 프로젝트 메모리 설정 후 기본 명령 출력(stdout·stderr·exit), 테스트 XML, 린트 결과, daemon 옵션(9장) |
| `followup/vc-state-before.json`, `followup/vc-state-after.json`, `followup/logcat-final-light.txt`, `followup/final-record.txt` | 보완: 최종 환경 |
