# SDK 호출 분리와 ANR 보완 (STEP 17)

- 날짜: 2026-11-13
- 환경: Pleos Connect 에뮬레이터(`emulator-5554`, user 10), Vehicle SDK 2.3.2, JDK 17
- 범위: 앱의 Vehicle SDK 호출을 main 스레드에서 떼어 낸다. 쓰기·복원 계약은 그대로 유지한다.
- 제품 상태: 제품의 시트 기능은 아직 구현하지 않았다(시트 명령은 debug 도구에만 있다).

## 1. 실제 원인

- 기기 시각(PST) 2026-11-12(호스트 KST 2026-11-13)에 debug 빌드에서 `Input dispatching timed out` ANR이 났다.
  - main 스레드가 debug [제어 검증] 새로고침의 등받이 capability 조회 안에서 12.3초 동안 실행 중(Runnable)이었다.
  - 맨 위 frame은 Kotlin reflection이었다.
- SDK 기능 API(capability·get·set·listener 등록/해제·select·STOP)의 동작은 로컬 SDK 바이트코드로 확인했다.
  - 호출한 스레드에서 권한 확인(구현 클래스 멤버 reflection)을 먼저 한 뒤에 서비스로 요청을 보낸다.
  - 첫 호출에서 긴 지연을 관측했으며, 지연 변동의 세부 원인은 특정하지 않았다.
- 제품 경로도 같은 구조였다. 공조·조명·문 첫 capability가 main에서 1~1.5초 걸린 것이 관측됐다.

## 2. 바뀐 계약

- **단일 작업 스레드**: Vehicle 기능 API는 모두 `SdkLane`(`sdk/SdkLane.kt`)의 스레드 `cabin-sdk` 하나에서 차례대로 실행한다.
  - 대기열 lock은 대기열 조작에만 쓰고, SDK 호출 중에는 잡지 않는다.
- **main에 남은 것**: `Vehicle(context)` 생성과 `initialize()`(프로세스당 1회, `CabinProcess`), 포트를 만들 때 부르는 `getLight()` getter.
  - 위 권한 확인은 기능 API 진입점에서 일어나며, 이 둘은 해당하지 않는다(바이트코드 확인 기준).
  - 따라서 "SDK 호출이 main에서 0건"은 아니다. 정확히는 "권한 확인이 있는 기능 API가 main에서 0건"이다.
- **쓰기 순서**: 기록 저장 → 레인 대기 → 차례가 오면 main에서 gate → main에서 onStart → SDK 진입.
  - gate는 그 시점의 최신 상태로 판정한다: P·주행 제한, 정책상 필요한 문 상태, run/job/requestId, 기록이 그대로인지, 저장 오류, 리스너 등록 확인과 사전 조회 뒤 값 변화 없음.
  - onStart는 timeout과 추적을 준비한다. "진입 시작"을 뜻할 뿐 차량 전달이나 성공이 아니다. worker는 onStart가 끝난 뒤에만 SDK에 들어간다.
- **미전송(local NotSent)**: SDK에 들어가지 않은 요청은 정확히 1번 미전송으로 끝난다.
  - 해당 경우: gate 거절, gate 2초 초과, 화면 닫힘, STOP, 대기열 가득.
  - gate나 onStart가 실행 중일 때 포기되면, 그것이 끝난 뒤 1번 알린다. 늦게 온 main 메시지로 되살아나지 않는다.
  - 진입한 뒤의 예외나 무응답은 결과 불명으로 남긴다(기록·timeout 유지).
- **STOP**: 가장 먼저 실행하고 gate가 없다.
  - 같은 owner의 아직 진입하지 않은 이동(MOVE/select)을 원자적으로 미전송 처리한다. 이미 진입했으면 그 호출이 끝난 뒤 바로 STOP을 보낸다.
  - 조명·공조의 일반 쓰기는 취소하지 않는다.
  - 같은 STOP(owner+축)은 대기열에 한 번만 들어간다. owner는 응답 전 재요청을 큐에 넣지 않고 화면에 알린다.
- **이동 예약**: 이동 요청마다 다른 key를 쓴다.
  - 예약 중에는 STOP만 SDK에 들어간다. 이미 등록된 콜백은 계속 받는다.
  - 그 key의 STOP 응답이 OK여야 해제한다(프리셋은 5축 모두). 실패·무응답·watchdog·화면 닫기로는 해제하지 않고 새 이동을 막는다.
  - 예약 중에는 휴식 시작도 거부한다.
- **리스너**: 핸들을 받았다고 등록된 것이 아니다. 등록 호출이 정상 반환된 것만 유효하다.
  - 등록이 실패하거나 이후 onFailed가 오면 무효로 보고, 대기 중인 쓰기를 거부한다.
  - 제품 조명·공조는 항목 리스너를 사전 조회보다 먼저 등록한다. 대기 중 값이 바뀌면 그 항목은 적용하지 않거나 복원을 보류한다.
- **수명**: Vehicle과 레인은 프로세스 수명 동안 유지하고 release하지 않는다(`CabinProcess`).
  - Activity가 끝나면 도구를 닫은 뒤 그 Activity 몫만 닫는다. 대기 쓰기는 기록용 미전송(화면 갱신 없음), 대기 조회·등록은 취소, 등록된 리스너는 해제한다. STOP과 정리 작업은 계속 실행된다.
  - 제품 복원은 Activity와 무관하게 계속된다. 콜백은 프로세스 Handler로 오므로 Activity의 메시지 정리에 지워지지 않는다.
- **상한**: 조회·쓰기·구독 합계 64개. 조회는 대기 8초를 넘으면 건너뛴다(조회별 취소 핸들은 없다).
- **Ready 온도 범위**: 응답이 없을 때만 읽기 전용으로 1회 다시 조회한다. 오류나 미지원 응답은 그대로 보여 준다.

## 3. 기록·권한

- 기록 형식 v1은 그대로이고 새 필드는 없다.
- 미전송은 그 requestId의 기록만 지우거나 되돌린다. 실패하면 기록을 남기고 잠근다.
- 대기 중에 프로세스가 죽으면 기존처럼 결과 불명으로 보수적으로 해석한다.
- 권한·Manifest·의존성·SDK 버전 변경은 없다.

## 4. 검증 결과

- `./gradlew --max-workers=1 :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:lintRelease :app:assembleRelease` → 성공.
- JUnit 297개 통과, 실패 0. 기존 235개에 새 62개를 더한 수다.
  - 새 테스트: SdkLane 32, RestLighting 10, RestClimate 8, ControlBench 8, SeatPresetBench 3, AmbientProbe 1.
  - 주요 회귀: onStart 중 STOP(MOVE 0, STOP 1, 미전송 1, 예약 없음), gate 시간 초과·거절·예외, 닫기 경합, 등록 실패·이후 onFailed, 예전 STOP 응답의 새 예약 해제 방지, 예전 휴식의 미전송이 새 휴식 화면을 바꾸지 않음, 늦은 capability 성공과 확정 실패 구분.
- lint는 debug·release 각각 오류 0, 경고 5(빌드 설정·리소스, 이번 변경 밖). Kotlin 경고 0. `git diff --check` 통과.
- STOP 순서 재현은 Root가 같은 방식으로 다시 확인했다.

## 5. 기기 증거 (APK별, 조회만)

- `9ac58bf2`(시작 차단·온도 재시도 반영 전 debug):
  - cold 실행에서 capability 조회가 모두 `cabin-sdk`에서 실행됐다. main에서는 0건이었다.
  - Root UI 입력: SDK 공조 capability 조회(3.836초)가 진행되는 동안 +5 입력이 171.6ms에 처리됐다. 문 timeout 상태도 main에서 갱신됐다.
  - 같은 빌드에서 첫 cold의 온도 범위가 시간 초과로 막히는 회귀를 찾았다. 그래서 4절의 재시도를 넣었다.
- `19c5495f`(최종 debug):
  - cold 실행: 문 capability 1885ms, 이어서 공조 capability 2411ms. 요청 후 약 4.5초에 응답이 왔다.
  - 범위 15–30/0.5가 표시되고 버튼을 쓸 수 있었다.
  - 시간 초과가 나지 않아 **재시도 경로는 기기에서 재현하지 못했다**.

## 6. 제약과 미실행

- SDK 내부 동시성(SDK 자체 코루틴과 캐시)은 다루지 않았다. 앱 쪽 진입 스레드만 하나다.
- 콜백 최대 시간은 보장하지 않는다. 실행 중인 SDK 호출은 끊을 수 없다(STOP도 현재 호출이 끝나야 실행된다).
- 첫 프레임에서 약 130프레임이 건너뛰어졌다. 원인은 특정하지 않았다.
- cold start에서 문 상태가 약 1.3초 오류로 보인 뒤 기존 재시도로 회복된다.
  - 원인은 레인 대기다. owner의 조회 timeout은 대기 시간을 포함해 센다.
  - debug 밝기 프로브의 cold 자동 조회는 STEP 18에서 미룬다.
- STOP 실패 뒤 성공 응답이 끝내 오지 않으면, 프로세스가 사는 동안 예약이 유지된다(debug 시트 도구만 해당).
- 진입 직전의 아주 짧은 구간은 원자 상태로만 막는다. 결정적으로 재현하는 테스트는 없다.
- 실행하지 않은 것:
  - 실제 차량 SDK 쓰기(조명·공조·시트) 회귀
  - 실제 앱 전환으로 확인하는 앱 이탈(APP_LEFT)과 복원. OEM Home·Back·앱 그리드로는 onStop을 확인하지 못했다.
  - release 설치·실행
  - 부하 재현
- "ANR 재발 없음"은 관측 범위로만 말한다.
