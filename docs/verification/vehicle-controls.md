# 차량 제어 적용·복원 검증 (STEP 7)

- 날짜: 2026-11-12
- 목적: 운전석 등받이, 따뜻한 무드등 색, 풍량, 사용자가 고른 목표 온도의 실제 쓰기·원복 계약(제품 요구사항 6-1~6-5)을 Pleos 에뮬레이터에서 작은 실행으로 확인
- 방법: debug 전용 [제어 검증] 탭(개발 진단 > 제어 검증)에서 버튼을 한 번씩 눌러 실행했다. 시험 조건(무드 조명 스위치, 공조 전원·Auto·Sync)은 정상 차량 UI로만 만들고 되돌렸다. 판정에는 SDK 재조회값을 쓰고, `onComplete` 값(요청값 echo)과 Console JSON은 대조용으로만 쓴다.
- 결론

| 속성 | 적용 | 원복 | 판정 |
| --- | --- | --- | --- |
| 운전석 등받이 | 내부 actuator는 움직였지만(Console recline 0→45) SDK 위치는 계속 0 | 공개 SDK 위치로 확인할 수 없음. 외부 시험 환경은 PM 승인 시험 복구로 0 | **미확인(환경 불일치)**: 공개 SDK 위치로 이동량·원복을 판정할 수 없음 |
| 무드등 색 | 요청 `0xFFFF9A40` → 재조회 `0x00FF9A40`(RGB 같음, alpha 0x00) | `0x00FFFFFF` → 재조회 `0x00FFFFFF` | 원복 **실행 확인**. 적용은 raw 값이 달라 **결과 불명**(alpha 비교 규칙은 PM 결정 필요) |
| 풍량 | 3→2, 재조회 2 (Auto false, Auto true 각 1회) | 2→3, 재조회 3 | **실행 확인**. 풍량을 바꿔도 Auto는 바뀌지 않음 |
| 운전석 목표 온도 | 22.0→22.5, 재조회 22.5 | 22.5→22.0, 재조회 22.0 | **실행 확인**. Sync 꺼짐(SDK dual false): 조수석 그대로. Sync 켜짐(SDK dual true): 조수석도 같이 22.5로 바뀌었다가 원복 때 22.0으로 돌아옴 |
| OFF 차단 | 스위치 OFF·공조 전원 OFF에서 색·풍량·온도 쓰기 요청 | — | **실행 확인**: 기록 저장 전에 거부, 명령 0 |

## 1. 환경

| 항목 | 값 |
| --- | --- |
| 기기 | `emulator-5554`, `ro.kernel.qemu=1`, API 34, AAOS 사용자 10. AVD `Pleos_Connect_2_1_0_probe`(STEP 3부터 같은 AVD, `-no-snapshot -gpu host -no-audio -crash-report-mode disabled -no-metrics`) |
| SDK | `ai.pleos.playground:Vehicle:2.3.2`(버전 변경 없음) |
| Virtual Console | `127.0.0.1:8088`(adb forward). 브리지는 이미 활성 상태였고 다시 활성화하지 않았다 |
| 권한 (설치 후 `dumpsys package`) | 기존 `CAR_LIGHTS`, `CONTROL_CAR_LIGHTS`, `CAR_POWERTRAIN`, `CAR_DOORS`, `CAR_SEATS`, `CAR_CLIMATE`, `TTS_SERVICE`에 더해 `pleos.car.permission.CONTROL_CAR_SEATS`, `pleos.car.permission.CONTROL_CAR_CLIMATE`를 추가. 모두 `granted=true`(`adb pm grant` 쓰지 않음) |

## 2. 구현한 검증 경로

| 파일 | 역할 |
| --- | --- |
| `control/ControlPort.kt`, `sdk/PleosControlPort.kt` | 공개 SDK 어댑터. capability, 현재값 조회, set/move, 리스너(`onFailed` 포함). 콜백은 main으로 넘긴다. 앱이 쓰는 값은 색·풍량·온도·등받이 이동과 복원용 `setHvacAuto`뿐이다. 공조 전원·듀얼, 조명 스위치 setter는 없다 |
| `control/ControlRecord.kt` | 새 복원 기록 형식과 원자적 저장 |
| `control/ControlBench.kt` | 단계 진행: 쓰기 직전 확인, 기록 저장, 명령, 콜백 뒤 재조회, 잠금, 원복, 시트 STOP |
| `ControlScreen.kt` | debug [제어 검증] 탭. 버튼을 누를 때만 쓴다. 제품 첫 화면·휴식 흐름과 연결하지 않았다 |

### 2-1. 복원 기록

- 파일: 앱 `filesDir/vehicle-control-records.json`. 기존 밝기 전용 기록(`ambient-probe-recovery.json`)은 읽거나 바꾸지 않는다. 밝기 기록이 남아 있으면 색 쓰기를 막는다.
- 형식: `{"formatVersion":1,"records":[…]}`. 레코드 필드: `jobId`, `prop`(`SEAT_BACKREST`/`AMBIENT_COLOR`/`HVAC_FAN_SPEED`/`HVAC_TEMPERATURE`), `area`, `original`, `target`(시트는 `BACKWARD`/`FORWARD`), `related`(명령 전에 읽은 공유 상태: 색은 스위치, 공조는 전원·Auto·Dual, 온도는 다른 영역 온도까지), `stage`, `requestId`, `responseReceived`, `priorRequestUnresolved`, `lastEcho`, `lastRead`, `note`.
- 단계: `APPLY_REQUESTED` → `APPLY_CONFIRMED`/`RESULT_UNKNOWN`, `RESTORE_REQUESTED` → 삭제(복원 확인) 또는 `RESTORE_FAILED`, `USER_CHANGED`.
- 저장: 임시 파일 쓰기 → fsync → `ATOMIC_MOVE` → 디렉터리 fsync. 이 중 하나라도 실패하면 저장 실패이고 명령을 보내지 않는다. 이후 쓰기·삭제도 막는다. Android에서 디렉터리 fsync가 동작해 기록이 남는 것을 실행으로 확인했다.
- 읽기: 형식 버전, 속성·단계 이름, 영역, 값 형식(색은 `0x` + 16진수 8자리, 정수, 유한 실수), 시트 목표, 중복, `related` 키·값을 엄격히 검사한다. 하나라도 틀리면 기록 읽기 실패로 표시하고 쓰기를 막는다. 기본값으로 바꾸지 않는다.
- 소유자 1명: 프로세스 안에서 하나의 bench만 기록을 쓴다(`RecordLease`).

### 2-2. 쓰기 규칙

1. 쓰기 직전 확인: 제어 검증 화면이 실제로 보임(아래 7), 기록 읽기·저장 오류 없음, 진행 중 단계 없음, 같은 속성 기록 없음, 풍량·온도는 서로의 기록·요청 없음(Auto 공유), 색은 밝기 기록 없음, 최신 P + 주행 제한 없음. capability(영역·accessType·범위), 전제 조건(스위치 ON / 전원 true), 원래값을 읽는다. 원래값과 목표값이 둘 다 현재 capability 범위·형식 안이어야 한다(예: 풍량 9(최대 8), 온도 30.5(최대 30), 형식이 틀린 색이면 기록 없이 이유만 남기고 끝낸다). 그다음 기록을 저장하고, 저장 뒤 다시 주차·작업 유효성·저장 오류 없음을 확인한 다음에만 명령을 보낸다. 저장 뒤 확인에서 막히면 보내지 않은 기록이라 지운다.
2. 판정: 종료 콜백을 받은 뒤 0.5초 기다렸다가 다시 조회한다. 적용은 `onComplete` + 재조회 = 목표일 때만 `APPLY_CONFIRMED`이고, 아니면 `RESULT_UNKNOWN`이다. 복원은 `onComplete` + 재조회 = 원래값 + 공유 상태가 원래대로일 때만 기록을 지운다.
3. 타임아웃(5초)은 요청을 끝내지 않는다. 응답 없는 요청이 있는 기록은 잠그고, 새 쓰기·원복·[기록 지우기]를 막는다. 앱을 다시 시작해도 잠금은 유지된다(`priorRequestUnresolved`). 늦은 응답은 기록에만 반영한다.
4. Auto 복원: 풍량·온도 복원 뒤 Auto만 바뀌어 있으면 `setHvacAuto(원래값)`를 한 번만 보낸다. 보내기 전에 Auto capability가 **READ_WRITE**인지(쓴 뒤 다시 읽어 확인해야 하므로 WRITE만으로는 안 됨), 영역, 주차, 화면 표시, 저장 오류 없음을 다시 확인한다.
5. 사용자 변경: 적용 확인 뒤 리스너 값이 목표와 다르면 `USER_CHANGED`로 바꾸고 원복하지 않는다.
6. 시트: 위치 capability(범위)와 이동 capability(쓰기)를 확인하고, 현재 위치가 범위 안이고, 위치 리스너 등록에 성공하고, 문이 닫혀 있을 때만 움직인다. 이동 중에는 리스너와 0.2초 직접 조회를 함께 쓴다. 첫 위치 변화, 값 없음·범위 밖·조회 실패·리스너 오류, 주차 상실, 문 열림, 화면 이탈이면 즉시 STOP한다. 1초 watchdog은 탐색의 안전 상한이다. 위치 도달로 보지 않는다. MOVE와 STOP 두 종료 콜백을 모두 받아야 `responseReceived=true`가 된다.
7. 화면 표시 경계: `MainActivity`가 "Activity가 시작된 상태(onStart~onStop)이고 `ControlScreen` Composable이 표시 중"일 때만 bench를 보임으로 둔다. bench는 기본값이 숨김이다. 다른 탭, [← 휴식 화면], 뒤로 가기(BackHandler), 휴식 시작으로 Composable이 빠지거나(`DisposableEffect` 해제) Activity가 onStop되면 숨김으로 바뀐다. 숨김이 되면 준비 중인 단계를 취소하고(늦은 응답은 무시하고 명령도 보내지 않음), 움직이는 시트는 STOP한다. 다시 보여도 아무 작업도 저절로 재개하지 않고 버튼으로만 시작한다. 이미 보낸 요청의 응답은 계속 작업 ID로 기록한다.
8. 소유자: 닫힌 bench나 lease를 잃은 bench는 기록 파일을 바꾸지 않고, 새 SDK 명령도 보내지 않는다. 중앙 저장 함수와 공유 상태 재조회의 각 단계·완료에서 확인한다. 이미 보낸 요청의 결과는 현재 소유자가 다시 읽은 기록으로 처리한다.
9. 저장 오류 뒤: 차량 새 쓰기·복원·Auto 복원과 기록 추가·삭제를 모두 막는다. 진행 중이던 요청의 응답은 기록 갱신만 시도한다. 복원이 확인돼도 기록은 지우지 않고 "복원 확인, 저장 오류로 기록 유지"로 남긴다.
10. 기록 읽기에서는 같은 속성·영역, 같은 `jobId`, 같은 `requestId`가 둘 이상인 파일을 손상된 기록으로 보고 쓰기를 막는다.

## 3. 시작 상태 (baseline, 실제 조회)

| 항목 | SDK | Console |
| --- | --- | --- |
| 기어·문 | P, 조수석 문 닫힘 | Park, 문 4개 닫힘 |
| 무드등 | 스위치 OFF, 밝기 70, 색 `0x00FFFFFF` | `ambient_light` mode On(차량 스위치와 무관한 표기, STEP 3), `#ffffff`, 70% |
| 공조 | 전원 false, Auto false, Dual false, 풍량 3, 운전석·조수석 22.0 | power/auto/dual/sync false, ac true, fan 3, 22.0/22.0 |
| 운전석 등받이 | 위치 0 | `seat.positions.recline.driver` 0 |
| 기록 | 제어 기록 없음, 밝기 기록 없음 | — |

## 4. 결과

### 4-1. 운전석 등받이

| 실행 | 명령·콜백 | SDK 위치(알림·직접 조회·재조회) | 내부 VHAL 로그 / Console | 기록 |
| --- | --- | --- | --- | --- |
| BACKWARD 탐색(위치 0에서) | MOVE `onComplete(BACKWARD)` +195ms, watchdog STOP `onComplete(STOP)` +125ms | 0, 0, 0, 0 → 재조회 0 | `Recline type to Negative`, 이동 상태 −1 → 약 0.1초 뒤 0(위치 로그 없음, 하한으로 보임), STOP 뒤 `Stopped`. Console recline 0 | `RESULT_UNKNOWN`. Console 0을 확인한 뒤 debug [기록 지우기] |
| FORWARD 탐색 | MOVE `onComplete(FORWARD)` +293ms, watchdog STOP `onComplete(STOP)` +70ms | 0 → 재조회 0 | `Positive`, 이동 상태 1, `Recline is 0.05 … 0.45`(약 0.1초마다 0.05씩), 매번 `revised position: 0`. STOP 직후 이동 상태 0, 0.45 뒤 추가 변화 없음. Console recline **45** | `RESULT_UNKNOWN`, 원래 0, 재조회 0. 지우지 않고 유지 |
| 시험 복구(PM 승인, 1회) | 기록 유지, 새 요청 ID와 응답 없음을 먼저 저장한 뒤 BACKWARD. MOVE `onComplete(BACKWARD)` +327ms, watchdog STOP `onComplete(STOP)` +85ms | 0 → 재조회 0 (위치로 멈추거나 성공 판정하지 않음) | `Negative`, `Recline is 0.4 … 0.05, 0`(약 0.1초마다), 0에서 이동 상태 0, STOP 뒤 `Stopped`. Console recline 45 → **0**, moves 0, `actuator.active_count` 0 | `RESULT_UNKNOWN` 유지(`responseReceived=true`, `priorRequestUnresolved=false`). PM이 증거를 대조한 뒤 승인한 debug [기록 지우기]로 정리 |

- 판정: 공개 SDK `getCurrentSeatBackrestAnglePosition`/`registerSeatBackrestAnglePosition`(Int)은 내부 actuator가 0.45까지 움직이는 동안에도 0만 돌려줬다. 공개 위치로는 이동량도 원복도 판정할 수 없다. 외부 시험 환경의 원위치 복구는 Console·내부 로그로 확인했지만, **공개 SDK만으로 위치 제어를 확인한 것은 아니다.**
- 관찰 범위(내부 raw 값 기준): FORWARD는 값을 키우고 BACKWARD는 줄였다. 갱신은 약 0.1초마다 0.05였고, 1초 상한 STOP 뒤 추가 이동은 보이지 않았다. SDK 위치 기준 방향 부호·단위·허용 오차는 미확인이다. 이 값을 각도로 부르지 않는다.
- 다른 공개 조회 경로: Seat 공개 API의 등받이 읽기는 위 Int 위치 두 개뿐이다. `checkSeatBackrestAngleCapabilities`는 deprecated("use checkSeatBackrestAnglePositionCapabilities instead")이고, 공식 문서에는 위치의 단위·정밀도 설명이 없다.
- 부수 효과: 이동 명령마다 차량 홈 화면에 시스템 좌석 위치 미니 앱(`SEAT_POSITION`)이 떴다(관찰).
- 시험 복구 코드는 이번 1회만을 위한 경로라 실행 뒤 제거했다(최종 소스에 없음). 재현 절차: 기록 유지 → 새 요청 ID·응답 없음 저장 → BACKWARD → 1초 watchdog 또는 안전 조건에서만 STOP(위치 0으로 멈추지 않음) → 두 종료 콜백 뒤 `responseReceived=true`, 단계 유지 → SDK 재조회 + Console `seat.positions.recline.driver`·`moves`·`actuator.active_count` + VHAL `Recline is`/`Stopped` 로그 대조.

#### 4-1-1. STEP 13 재검증: 가상 위치 설정과 SDK 조회 (2026-11-13, 이동 명령 없음)

위 STEP 7 기록은 그대로 두고, 같은 AVD(이미지 2.1.0)에서 위치를 직접 설정해 다시 확인한 결과만 덧붙인다.

- **설정 경로**:
  - Console catalog의 `seat-backrest-position` 항목이 정확한 계약을 갖고 있다. 이 항목은 `visible:false`다. 계약 내용: "Publish normalized seat position", `%`, 0~100 step 1, `PUT /vehicle/seat`, `kind=position`, `control=recline`, `value`, `area=driver`, `SEAT_BACKREST_ANGLE_1_POS`.
  - 이 계약 그대로 운전석 위치를 emulator 전용 fixture로 설정했다. Console 브리지가 꺼져 있으면 `503 emulator_inactive`가 나오므로, 먼저 Console 프런트엔드에 정의된 로컬 세션 API로 활성화했다.
  - 차량 설정 앱 > 시트 > 시트 조정에는 부위별 방향 조정(이동형), 운전자 프리셋 1·2, 릴랙션 컴포트가 있다. 숫자 위치 입력은 없다. 누르지 않았다.
  - CarService 셸 조회·주입은 user 빌드라 쓸 수 없었다. 에뮬레이터 Extended Controls > Car data는 확인하지 않았다.
- **결과**:

| Console 입력 | Console 표시 | VHAL 로그(`sdv_vhal_rust`) | 공개 SDK getter | 공개 SDK 리스너 |
| --- | --- | --- | --- | --- |
| 원래값 | 0 | — | 0 | 등록 직후 0 |
| 25 | 25 | `Recline is 0.25, revised position: 0` | 0 | 이벤트 없음 |
| 50 | 50 | `Recline is 0.5, revised position: 0` | 0 | 이벤트 없음 |
| 100 | 100 | `Recline is 1, revised position: 0.01` | 0 | 이벤트 없음 |
| 0(원복) | 0 | `Recline is 0, revised position: 0` | 0 | 이벤트 없음 |

- **판단**:
  - 가상 위치는 Console에서 설정되고 DDS로 게시됐다. 시트 위치가 가상값이라는 점과, 공개 SDK 읽기가 0이라는 점은 별개다.
  - 관측된 흐름은 100% → raw 1 → revised 0.01 → SDK 0이다. 그래서 Console/DDS → VHAL 사이의 단위 환산과 정수화 단계에 문제가 있다고 **강하게 의심된다**. 정확한 변환 구현(수식)은 읽지 않았으므로 **미확인**이다.
  - 시험한 0·25·50·100%에서 SDK 값은 모두 0이었다. 최대 입력에서도 0이었으므로, 이동 시간만 늘리면 해결된다고 볼 근거는 없다.
  - SDK 라이브러리 자체의 결함으로 단정하지 않는다. 로그로 확인한 범위는 다음뿐이다: VHAL의 revised 값이 0.01 이하였고, SDK getter·서비스 로그의 값이 0이었다.
- **대안 후보(미검증)**:
  - 설치된 SDK에는 `selectSeatMemory(SeatArea, Int, onFailed)`와 `checkSeatMemoryCapabilities`가 있다. 차량 설정 앱에는 운전자 프리셋 1·2가 있다.
  - STEP 14(2026-11-13)에서 capability만 조회했다. `checkSeatMemoryCapabilities` 결과: 접근 **WRITE**, 운전석(`ROW_1_LEFT`) **1~2**.
    - 서비스 원문: `SEAT_MEMORY_SELECT`, `access 2`, `propertyId 356518784`, `supportedAreas {"1": min 1, max 2}`.
    - 프리셋 선택·저장·이동은 호출하지 않았다. 차량 쓰기는 0건이다.
    - 공개 SDK 타입에는 프리셋 저장 메서드와 현재 프리셋 번호 조회가 보이지 않는다(접근도 WRITE뿐이다).
  - 선택 결과 확인, 저장, 원래 자세로 되돌리는 계약은 아직 확인하지 않았다. 이 경로가 구현 불가하다고 정하지 않는다.
  - (위 STEP 14 내용은 당시 결과로 그대로 둔다.) **STEP 15(2026-11-13) 최신 결과**:
    - debug 도구로 `selectSeatMemory` 2번·1번을 1회씩 호출했다. 요청은 SDK → 서비스 → VHAL까지 전달됐지만 시트는 어느 영역·축도 움직이지 않았다.
    - 차량 설정 앱의 저장 카드는 떴지만 대상이 "운전석 0"이었다. 1·2번 저장과 프리셋 이동은 미검증이다.
    - 제품 시트 기능은 아직 없다. 자세한 내용: [seat-presets.md](seat-presets.md).
- 증거: `app/build/reports/seat-recheck/`(`logs/f0~f10`, `docs/official-docs-summary.md`), 보고 `app/build/reports/pm-live/step13-report.md`.

#### 4-1-2. STEP 21–23 원인 분리 (2026-11-13, 이동 명령·SDK 쓰기 없음)

- **공식 샘플**: [pleos-sample-vehicle-app](https://github.com/Pleos-Playground/pleos-sample-vehicle-app) 커밋 `959981d960ec71144accb27af5ae5d341e72a644`.
  - 원본은 빌드할 수 없었다. 의존성 선언 Vehicle `2.3.1`이 공식 Maven 저장소에 없다(pom 404, 공개 버전은 2.0.3·2.3.2). README에는 2.3.2로 적혀 있다.
  - 의존성 선언 한 줄만 2.3.2로 바꾼 비교본은 빌드·설치·실행됐다. 원본 그대로는 아니며, 코드·콜백·스레드는 바꾸지 않았다.
  - 샘플은 받은 값을 가공 없이 표시하므로, 우리 앱 어댑터를 거치지 않은 비교다.
- **방법**:
  - 운전석(`ROW_1_LEFT`)만 시험했다. 샘플은 capability·get·listener만 썼다(Apply 없음).
  - 값은 Console catalog의 position fixture(0..100 %, step 1)로 넣었다. 단계마다 전체 moves 0과 actuator 0을 확인했다.
  - 끝난 뒤 원래값 0으로 돌리고 Console 세션을 닫았다.
- **결과**: 세 축 모두 capability는 READ_WRITE, 범위 0..100이었다.

| 축 | Console 입력 | VHAL 로그(`sdv_vhal_rust`) | 서비스·샘플 get | 샘플 listener |
| --- | --- | --- | --- | --- |
| 등받이 | 25 | `Recline is 0.25, revised position: 0` | Int 0 | 변화 없음 |
| 등받이 | 100 | `Recline is 1, revised position: 0.01` | Int 0 | 변화 없음 |
| 높이 | 50 | `Height is 0.5, revised position: 0` | Int 0 | 변화 없음 |
| 높이 | 100 | `Height is 1, revised position: 0.01` | Int 0 | 변화 없음 |
| 기울기 | 50 | `Tilt is 0.5, revised position: 0` | Int 0 | 변화 없음 |
| 기울기 | 100 | `Tilt is 1, revised position: 0.01` | Int 0 | 변화 없음 |

- **사실과 미확인**:
  - 사실: VHAL 로그에서 0/0.01까지 작아진 값을 관측했고, 서비스·SDK에서 Int 0을 확인했다. 우리 앱 어댑터만의 문제가 아니다.
  - 미확인: 분수가 정수로 바뀌는 정확한 지점과 구현, 실제 차량, 다른 이미지. 앞뒤·요추 축은 시험하지 않았다.
- **공개 진단 경로**(값 주입은 하지 않음):
  - 개발자 옵션: 기본 설정의 빌드 번호 절차로 켜서 읽었다(`development_settings_enabled` null → 1, 직접 바꾼 설정은 이것 하나). CarData·VHAL·차량 속성 입력 메뉴가 없다.
  - `cmd car_service help`: user 빌드라 거부된다(`SecurityException: The command 'help' requires non-user build`).
  - VHAL `dumpsys android.hardware.automotive.vehicle.IVehicle/default --help`: 서비스는 있다. 하지만 shell → `hal_vehicle_default` binder 호출이 SELinux로 거부돼 `FAILED_TRANSACTION`이 난다.
  - 기기 안 `Virtual console` 앱은 같은 Console(`localhost:8088`)을 띄우는 WebView다. 새 입력 경로가 아니다(정적 분석, 열지 않음).
- **현재 한계**:
  - 업체 답변 없이 쓸 수 있는 위치 피드백 해결 수단을 찾지 못했다.
  - 이 결과를 SDK 전체나 모든 실제 차량이 안 된다는 뜻으로 보지 않는다.
  - F11은 미충족이다. 위치 피드백 없이 시간으로 이동·원복을 간주하는 방식은 쓰지 않는다.
- 증거(빌드 산출물 폴더, 커밋하지 않음): `app/build/reports/seat-independent/`(STEP 21), `seat-axes/`(STEP 22), `seat-vhal-dump/`(STEP 23)와 각 폴더의 보고서.

#### 4-1-3. STEP 25 시트 목표 이동 코어 (2026-11-13, 컴포넌트·단위 검증만)

- **범위**: 새 코어(`app/src/main/java/app/commacabin/seat/`의 `SeatMove.kt`, `SeatMoveCore.kt`)와 포트·기록(journal) 인터페이스만 만들었다.
  - 앱에 연결하지 않았다. 실제 SDK 어댑터와 기록 파일 구현은 없다.
  - 이 코어로 실제 시트나 에뮬레이터 시트를 움직인 적은 없다.
- **연결 전 조건**: 위치 피드백을 믿을 수 있는 환경과, 그 환경에서 실측한 증가 방향·단위·허용 오차·시간 값이 필요하다. 현재 에뮬레이터(4-1-2, 위치 Int 0)에서는 제품에 연결하지 않는다.
- **동작 계약**:
  - 이동 전: 리스너 등록 확인, 현재 위치 = 시작값, 기록 저장 성공이 필요하다. SDK 진입 직전에 다시 확인하고 저장한다. 하나라도 실패하면 MOVE를 보내지 않는다.
  - 이동 중: 기존 SdkLane은 이동 예약 중에는 STOP만 실행하고 조회를 보류한다. 그래서 코어는 이동 중에 조회하지 않고, 리스너 값과 무변화·상한 제한으로 STOP을 정한다.
  - 판정: 모든 MOVE·STOP 요청이 끝나고, MOVE 종료 뒤에 보낸 가장 최근 STOP이 onComplete일 때만 이동 예약을 푼다. 그 뒤 새로 읽은 값이 정해진 횟수 모두 범위 안·허용 오차 안이어야 확인이다.
  - echo, 이동 중 값, 이전 조회는 판정에 쓰지 않는다. SDK 콜백 순서가 실제 적용 순서를 보장한다고 보지 않는다.
  - 목표 도달이 아닌 STOP(안전·화면·수동 STOP, 리스너 실패, 잘못된 값, 제한, MOVE 실패)은 HELD로 고정한다. 나중 값이 목표 안이어도 확인하지 않는다.
  - 타임아웃은 요청을 끝내지 않는다. 응답 없는 요청은 기록에 남아 새 MOVE와 기록 삭제를 막는다. 이전 실행의 기록이 끝나지 않았거나 앞뒤가 맞지 않으면 잠근다.
  - 안전조건 상실·화면 이탈 때는 앞선 STOP이 실패했거나 응답이 없어도, 저장이 실패해도 새 안전 STOP을 보낸다. P 복귀로 자동 재개하지 않는다.
- **경계(후속 단계)**:
  - STOP 요청 ID는 호출자가 주는 고유 ID가 전제다. 후속 호출자는 중앙에서 발급한 ID로 이를 지킨다.
  - 실제 lane이 "같은 STOP이 이미 대기 중"으로 거부하면 실패로 기록하고 보류한다.
  - 이전 실행 불명 작업의 전역 비상 STOP, 기록 파일 코덱·정리, SDK 어댑터, 제품 연결은 하지 않았다.
- **검증**(JDK 17, `./gradlew --max-workers=1 :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`, exit 0):
  - JUnit 331개 통과(기존 297 + 코어 32 + 실제 SdkLane·가짜 SDK 2). 실패·오류·skip 0.
  - lint 오류 0, 기존 경고 5. debug 빌드 성공(설치하지 않음).
  - 경위: 리뷰에서 지적된 5개 문제는 정적 검토로 찾았고, 수정 전 red 실행은 하지 않았다. 마지막 안전 STOP 보완은 수정 전 임시 테스트 2개가 실제로 실패(STOP 2개 기대, 실제 1개)한 것을 확인한 뒤 고쳐 통과시켰다.
  - 원문 로그(빌드 산출물, 커밋하지 않음): `app/build/reports/step25-seat-core/`의 `fix1-red-interrupt.log`, `fix1-seat-tests.log`, `fix1-verify-full.log`.
- F11은 계속 미충족이다. 실기와 제품 연결은 확인하지 않았다.

#### 4-1-4. STEP 26 시트 복원 기록 파일 (2026-11-13, 단위 검증만)

- **범위**: 4-1-3 코어의 기록 인터페이스에 실제 파일 구현(`FileSeatJournal`)과 엄격한 JSON 코덱(formatVersion 1)을 더했다(`seat/SeatJournalFile.kt`).
  - 파일 이름, 형식, 쓰기 경로, 실패 계약은 [../seat-recovery-format.md](../seat-recovery-format.md)에 정리했다.
  - 앱에서 만들거나 연결하지 않았다. 테스트는 JVM 임시 디렉터리의 파일만 썼다. 실제 Android 파일, SDK, 기기 호출은 0이다.
- **검증**(JDK 17, `./gradlew --max-workers=1 :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`, exit 0):
  - JUnit 347개 통과(기존 331 + 기록 파일 16). 실패·오류·skip 0. lint 오류 0, 기존 경고 5. debug 빌드 성공(설치하지 않음).
  - 테스트 범위: 모든 필드의 왕복, 표준 JSON만 받는 엄격한 거부와 원본 바이트 보존, 256 KiB 상한, 존재 판정 불가·접근 오류, 형식에 안 맞는 기록 쓰기 거부, 덮어쓰기·요청 ID 의미 변경 금지, 원자적 교체 미지원·디렉터리 fsync 실패, 코어와 함께 쓸 때 MOVE 0·STOP 허용·재실행 잠금.
  - 원문 로그(빌드 산출물, 커밋하지 않음): `app/build/reports/step26-seat-journal/fix1-verify-full.log`.
- 앱 연결(`filesDir`·lease·owner), 기록 정리, 자동 복원, 전역 비상 STOP은 후속 단계다. F11은 계속 미충족이다.

#### 4-1-5. STEP 27 시트 코어와 기존 공개 SDK 포트 사이 변환층 (2026-11-13, 단위 검증만)

- **범위**: 코어의 `SeatAxisPort`를 기존 `ControlPort`(앱에서는 SdkLane 위의 `PleosControlPort`)에 잇는 얇은 변환층(`seat/ControlSeatAxisPort.kt`)을 만들었다.
  - 새 SDK 호출, 스레드, 대기열, gate, 타이머는 없다.
  - 앱에서 만들거나 연결하지 않았다. 실제 SDK·기기 호출은 0이다.
- **대상**: 운전석(`ROW_1_LEFT`) 등받이(`ControlProp.SEAT_BACKREST`)만 지원한다. 다른 축은 조회·리스너·STOP을 오류로, MOVE를 미전송으로 거부하며, 하위 포트를 호출하지 않는다. 생성자는 아무것도 호출하지 않는다.
- **위치 변환**: `ControlPort`의 문자열이 정규 Int 형식일 때만 값으로 넘긴다(`"0"`은 0, null은 값 없음). 소수, 공백, 범위 밖, 앞자리 0 같은 형식은 조회와 리스너 모두 `InvalidSeatPosition` 오류다. 0으로 고치거나 잘라 쓰지 않는다.
- **리스너**: handle을 받았다고 등록된 것으로 보지 않는다. 하위의 등록 결과와 리스너 오류를 그대로 전달하고, 닫기도 하위 handle에 넘긴다. 늦은 콜백을 걸러 내지 않는다(판정은 코어의 몫).
- **MOVE**: 호출자의 `Dispatch`를 그대로 하위에 넘긴다(gate·onStart를 미리 실행하지 않는다). 하위에 넘기지 않은 것이 확실한 경우(다른 축, STOP을 MOVE로 요청, 빈 예약 키)만 onNotSent로 알린다. 하위 오류는 그대로 오류로 전달한다.
- **STOP**: 기존 `ControlPort`의 STOP 경로(맨 앞, gate 없음)를 쓴다. 소스로 확인한 결과 `PleosControlPort`는 STOP에서 예약 키와 dispatch를 쓰지 않아, 빈 키와 `Dispatch.NONE`을 넘긴다. `StopAlreadyQueued` 같은 거부는 실패로 전달한다. 변환층은 요청받은 등받이 STOP을 그대로 전달할 수 있다. 다만 시트의 전역 소유권과 이전 실행의 이동을 복구하는 정책은 정하지도 구현하지도 않았다.
- **이동 예약 해제**: `endMotion`은 코어가 준 정확한 키를 그대로 넘길 때만 호출한다. 콜백이나 시간 경과로 스스로 해제하지 않는다.
- **검증**(JDK 17, `./gradlew --max-workers=1 :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`, exit 0):
  - JUnit 355개 통과(기존 347 + 변환층 8, 가짜 `ControlPort` 사용). 실패·오류·skip 0.
  - lint 오류 0, 기존 경고 5. debug 빌드 성공(설치하지 않음).
  - 원문 로그(빌드 산출물, 커밋하지 않음): `app/build/reports/step27-seat-adapter/verify-full.log`.
- 지원 조회·환경 신뢰·실측 제한값 검증, 실제 owner 생성, 기록 파일 연결, 전역 비상 STOP은 후속 단계다. 현재 이미지의 위치 Int 0 문제는 그대로이고, F11은 계속 미충족이다.

#### 4-1-6. STEP 28 이전 실행 미완료 시트 요청의 비상 STOP 관리자 (2026-11-13, 단위 검증만)

- **범위**: 새 프로세스가 시작될 때 이전 실행이 남긴 시트 기록을 한 번 읽고, 이동 중일 수 있는 요청에 새 비상 STOP을 보내는 관리자(`seat/SeatEmergencyStop.kt`)를 만들었다.
  - 앱에서 만들거나 연결하지 않았다. 실제 SDK·기기 호출은 0이다.
  - 프로세스당 하나, main 스레드, 프로세스 수명이 계약이다. 코어와 같은 기록을 동시에 쓰지 않게 하는 일은 미래의 프로세스 owner가 맡는다.
- **판정**(`start()` 1회):
  - 호출 0: 기록 없음, 읽을 수 없는 기록, 대상을 믿을 수 없는 기록(BACKREST가 아니거나 요청 ID가 비었거나 겹침). 뒤의 둘은 잠그고 수동 확인을 안내한다. 기록은 그대로 둔다.
  - 호출 0·잠금 없음: 결과와 모든 요청이 정지 완료로 맞는 BACKREST 기록뿐이다. 모든 요청이 끝났고, `afterMove`가 MOVE의 응답 여부와 맞아야 한다.
    - NOT_SENT: MOVE가 NOT_SENT이고 STOP은 모두 그 전에 보낸 것이며 끝남.
    - CONFIRMED·UNCONFIRMED·HELD: MOVE의 종료 응답이 있고, 그 뒤 가장 최근 STOP이 완료됨.
  - 비상 STOP 1회와 해제되지 않는 잠금: 그 밖의 모든 BACKREST 기록이다. 결과 미정(SAVED 포함), STOP_FAILED, 결과 표기와 요청 상태가 맞지 않는 기록이 해당한다.
- **STOP**: 새 내부 UUID를 쓰고 사용자 입력 ID는 없다. 빈 ID나 이미 쓴 ID는 보내지 않는다.
  - 기록에 새 STOP을 덧붙여 저장한 뒤 보낸다. 저장이 실패해도 STOP은 막지 않고 오류를 기록한다.
  - 이전 요청은 바꾸지 않는다. 응답은 자기 STOP만 한 번 갱신하고, 타임아웃은 결과 불명 표시만 한다.
  - "STOP 응답 받음"은 실제 정지나 복원 확인이 아니다. 명시적 `emergencyStop()`은 새 ID로 다시 보낸다.
  - MOVE·복원·조회·삭제·`endMotion`은 하지 않는다.
- **검증**(JDK 17, `./gradlew --max-workers=1 :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`):
  - 첫 전체 실행은 exit 1이었다. 기존 실제 SdkLane 테스트 1개가 실패했다.
    - 원인: 테스트의 대기 조건이 이동 예약만 확인하고, 작업 스레드가 가짜 SDK MOVE 호출에 들어가기 전에 다음 확인으로 넘어갔다. SdkLane은 예약을 잡은 뒤 SDK 호출에 들어간다.
    - 조치: 대기 조건에 실제 MOVE 진입을 넣었다. 제품 코드는 바꾸지 않았다.
  - 보정 뒤 전체 실행은 exit 0이다. JUnit 364개 통과(기존 355 + 관리자 9). 실패·오류·skip 0. lint 오류 0, 기존 경고 5. debug 빌드 성공(설치하지 않음).
  - 원문 로그(빌드 산출물, 커밋하지 않음): `app/build/reports/step28-seat-emergency-stop/`.
- 실제 앱 연결(프로세스 owner·기록 파일·변환층 생성), 수동 조정 화면, 기록 정리는 후속 단계다. F11은 계속 미충족이다.

#### 4-1-7. STEP 29 확인된 시트 기록만 정리 (2026-11-13, 단위 검증만)

- **범위**: 코어에 명시적 정리 메서드 `clearRecord()`, 기록 인터페이스에 `clear(expected)`, `FileSeatJournal`에 기록 파일 한 개 삭제와 디렉터리 fsync를 더했다.
  - 앱에서 만들거나 연결하지 않았다. 실제 Android 파일·SDK·기기 호출은 0이다.
  - 정리 계약 전체는 [../seat-recovery-format.md](../seat-recovery-format.md) 6절에 있다.
- **허용**: 이 실행의 코어가 직접 끝낸 시도만 정리할 수 있다. 어느 경우든 정합성 판정을 통과하고, 진행 중인 시도·잠금·저장 오류가 없어야 한다.
  - RESTORE CONFIRMED(목표 = 원래값).
  - 앞선 기록이 없던 새 APPLY의 확실한 미전송(원래값 = 시작값, 모든 저장 성공).
  - 증거는 한 번만 쓰고, 다음 시작이 오면 사라진다. 기록이 증거 이후 바뀌면 거부한다.
- **거부**: APPLY 확인·HELD·UNCONFIRMED·STOP 실패, MOVE·STOP 미응답, 재시작한 코어(파일의 결과만으로는 증거가 아님), 현재값 재조회, 디스크 기록 변경·손상·다른 버전, 이미 쓴 증거, 첫 저장 실패로 SAVED가 남은 경우, RESTORE 준비 거절로 앞선 APPLY 기록이 남은 경우.
  - 파일 계층도 정합하지 않거나 정리 대상 형태가 아닌 기록은 기대 snapshot과 같아도 지우지 않는다.
- **좁은 보정**
  - 공통 정합성 판정의 조건을 강화했다: STOP의 `afterMove`가 MOVE와 맞을 것, MOVE 자신의 `afterMove` = false, RESTORE 목표 = 원래값.
  - 코어가 불러온 기록을 판정할 때 이 공통 판정을 쓴다.
  - 코어의 새 STOP `afterMove`는 MOVE의 SDK 종료 응답이 있을 때만 true다.
- **실패**: 정리가 실패하면 코어가 저장 오류로 고정해 이후 이동을 막고 다시 시도하지 않는다. 삭제 뒤 디렉터리 fsync가 실패하면 파일은 이미 없을 수 있다.
- **검증**(JDK 17, `./gradlew --max-workers=1 :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`, exit 0):
  - JUnit 372개 통과(기존 364 + 정리 8). 실패·오류·skip 0. lint 오류 0, 기존 경고 5. debug 빌드 성공(설치하지 않음).
  - 원문 로그(빌드 산출물, 커밋하지 않음): `app/build/reports/step29-seat-record-clear/verify-full.log`.
- 정리를 부르는 화면·owner 연결은 후속 단계다. F11은 계속 미충족이다.

#### 4-1-8. STEP 33 휴식 시트 owner (2026-11-13, 단위 검증만)

- **범위**: 프로세스당 하나인 owner `seat/RestSeat.kt`를 만들었다. 코어에는 구조화 결과(`SeatAttemptResult`)와 `holdRecord`를 더했다.
  - 앱(`CabinProcess`/`MainActivity`)에는 만들거나 연결하지 않았다. 실제 SDK·기기 호출은 0이다.
  - `VERIFIED_SEAT_ENVIRONMENT = null`이라 제품 MOVE는 0이다. 방향·이동량·오차·시간 값은 테스트 fixture에만 있다.
- **시작**: 비상 STOP 관리자가 먼저 돈다. 환경 값이 없어도 미완료 기록에는 안전 STOP을 보내고, 늦은 응답도 owner 상태에 반영한다.
  - 기록이 남아 있으면 시트는 그 실행 동안 건드리지 않는다.
  - 기록이 없고 환경이 검증됐을 때만 코어를 만든다. 기록은 한쪽만 쓴다.
- **감시**: 적용 확인 뒤 코어가 리스너를 닫으면 owner가 감시한다.
  - 등록 확인과 첫 새 읽기가 확인 값과 같아야 "감시 준비"다.
  - 다른 값은 사용자 변경, null·범위 밖·등록·읽기 실패·리스너 오류는 감시 실패로 고정한다. 둘 다 기록을 HELD로 남기고, 값이 돌아와도 풀지 않는다.
  - 정상 종료 뒤에도 원복 확인 동안 감시를 유지하고, 코어의 새 리스너 직전에 닫는다.
- **휴식 토큰**: `onRestStart()`는 owner가 발급한 프로세스 고유 토큰을 돌려주고, `onRestEnd(token, 종료 사유)`는 그 토큰으로만 짝을 맞춘다.
  - 앱 쪽(Activity마다 1부터 다시 세는 세션 번호)은 무드등·공조처럼 세션→토큰을 직접 매핑해야 한다. 다른 번호나 늦은 옛 종료는 무시한다.
- **코어 오류와 진행 중 문제**: 코어 결과를 처리하기 전에 코어의 잠금·저장 오류를 먼저 보고 보류·수동 안내로 바꾼다. STOP 실패와 응답 없음은 진행 중인 시도를 억지로 끝내지 않고 `STOP_FAILED`/`NO_ANSWER`로 알린다. 기어 이탈 때 새 STOP은 계속 보낸다.
- **종료와 원복**: 종료와 보류 결정을 먼저 고정한 뒤 코어를 중단한다. 보류는 풀리지 않는다.
  - 주차형 종료(LEFT_PARK·RESTRICTED·VEHICLE_UNKNOWN)는 보류하고, 나머지 6개 종료는 원복을 시도할 수 있다(무드등·공조와 같은 합의).
  - 원복은 한 번만, 다음을 모두 통과할 때만 한다: 모든 응답, MOVE 뒤 STOP 완료, 첫 원인 뒤 신뢰 실패 없음, 정지 뒤 읽기(또는 감시 기준값), 새 capability, P/UX, 그 값과 같은 새 읽기, 코어의 새 관찰·preflight.
  - 확인된 원복만 코어의 현재 실행 증거로 정리한다. 그 밖에는 보류하고 재시도하지 않는다.
- **검증**(JDK 17, `./gradlew --max-workers=1 :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`, exit 0):
  - JUnit 405개 통과(기존 372 + owner 33). 실패·오류·skip 0. lint 오류 0, 기존 경고 5. debug 빌드 성공(설치하지 않음).
  - 원문 로그(빌드 산출물, 커밋하지 않음): `app/build/reports/step33-rest-seat/fix3-verify-full.log`.
- 앱 연결과 release QA는 다음 단계다. 위치 Int 0 문제로 환경을 검증할 수 없어 F11은 계속 미충족이다.

### 4-2. 무드등 색

1. 정상 차량 설정 앱 라이트 > 무드 조명 [켜기] → SDK 스위치 ON.
2. [따뜻한 색 적용]: 원래 raw `0x00FFFFFF`, 요청 payload `0xFFFF9A40`(문서상 불투명 ARGB). `onComplete(0xFFFF9A40)`(echo). 리스너 `0x00FFFFFF` → `0x00FF9A40`. 재조회 **`0x00FF9A40`**. VHAL `set ambient lights color: r,g,b: (255, 154, 64)`, Console `#ff9a40`. 공유 상태(스위치) 변화 없음. raw 값이 요청과 달라 `RESULT_UNKNOWN`으로 남겼다(alpha를 무시해 성공 처리하지 않음).
3. [원래 색]: payload `0x00FFFFFF`(읽은 원래 raw 그대로). `onComplete(0x00FFFFFF)`, 리스너 `0x00FFFFFF`, 재조회 `0x00FFFFFF`, VHAL `(255, 255, 255)` → 복원 확인, 기록 삭제.
4. 정상 UI [끄기] → SDK 스위치 OFF. 이 상태에서 [따뜻한 색 적용]은 "무드등 스위치가 켜져 있지 않아 쓰지 않아요"로 기록 저장 전에 거부됐다.

- 관찰한 변환: 차량은 RGB만 저장하고, 조회·리스너는 alpha를 항상 `0x00`으로 돌려줬다(요청 alpha `0xFF`, `0x00` 모두). **제안(결정 아님)**: 색 적용 확인은 하위 24비트(RGB)가 같고 요청 alpha가 `0xFF`일 때로 하고, 원래값 복원은 읽은 raw 값을 그대로 보내 raw로 비교한다. 근거는 위 두 번의 실행뿐이다.

### 4-3. 풍량

| 조건 (정상 UI) | 적용 | 원복 |
| --- | --- | --- |
| 전원 ON, Auto false, Dual false | 기록(원래 3, 목표 2, 전원 true·Auto false·Dual false) 저장 → payload 2, `onComplete(2)`, 리스너 3 → 2, 재조회 **2**. VHAL `set blower level: 2`, Console fan 2. 공유 상태 변화 없음 → `APPLY_CONFIRMED` | payload 3, `onComplete(3)`, 리스너 3, 재조회 **3**, 공유 상태 원래대로 → 복원 확인, 기록 삭제 |
| 전원 ON, **Auto true**(공조 패널 Auto) | 원래 3, 목표 2. `onComplete(2)`, 재조회 **2**. Auto는 **true 그대로**(SDK·Console) → `APPLY_CONFIRMED` | `onComplete(3)`, 재조회 **3**, Auto true 그대로 → 복원 확인, 기록 삭제 |

- 이 에뮬레이터에서는 풍량 변경이 Auto를 끄지 않았다. 그래서 `setHvacAuto` 복원 경로는 실기에서 실행되지 않았다(단위 테스트로만 확인). 실제 차량에서 Auto가 꺼지는지는 미확인이다.
- 최솟값: capability 범위 1~8에서 원래값 −1(최소 1 이상)만 쓴다.

### 4-4. 운전석 목표 온도

| 조건 (정상 UI) | 적용 (한 간격 0.5°C) | 원복 |
| --- | --- | --- |
| 전원 ON, Auto false, **Sync 꺼짐**(SDK dual false) | 기록(원래 22.0, 목표 22.5, 전원·Auto·Dual, 조수석 22.0) 저장 → `onComplete(22.5)`, 재조회 운전석 **22.5**, 조수석 **22.0**. VHAL `set temperature area: Row1Left value: 22.5`. Console 22.5/22.0 → `APPLY_CONFIRMED`, 공유 상태 변화 없음 | `onComplete(22.0)`, 재조회 22.0, 조수석 22.0 → 복원 확인, 기록 삭제 |
| 전원 ON, Auto false, **Sync 켜짐** | 기록(Dual **true**, 조수석 22.0 포함) 저장 → `onComplete(22.5)`, 재조회 운전석 **22.5**, 조수석 **22.5**. VHAL `temperature of area Row1Right is 22.5`. Console 22.5/22.5. Dual·Auto·전원 변화 없음 → `APPLY_CONFIRMED`, note "공유 상태 변화: HVAC_TEMPERATURE ROW_1_RIGHT 22.0→22.5" | 운전석에 22.0 → 조수석도 22.0으로 따라옴. 재조회 22.0/22.0, 공유 상태 원래대로 → 복원 확인, 기록 삭제 |

- SDK Dual과 UI Sync의 대응(실측): 공조 패널 **Sync** 버튼 → VHAL `set sync: … ON` → DDS `dual=true` → SDK `getHvacDualState` **true**, Console `dual_on`·`sync_on` true. Sync를 끄면 모두 false.
- 한 영역 변경의 영향: Dual(Sync) true면 운전석 쓰기가 조수석을 같은 값으로 바꾼다. false면 바꾸지 않는다. 제품 계약 6-5의 "다른 영역은 바꾸지 않는다"와 Dual true 조건이 충돌하므로 처리 방식은 PM 결정이 필요하다(보고서 참고).
- 온도 변경은 Auto·Dual·전원을 바꾸지 않았다(Auto false 조건에서만 확인).

### 4-5. OFF 차단

- 공조 전원 OFF(SDK false)에서 [한 단계 낮추기]·[한 간격 바꾸기]: "공조 전원이 켜져 있지 않아 쓰지 않아요". 기록 파일 변화 없음, VHAL 쓰기 로그 없음.
- 무드등 스위치 OFF에서 [따뜻한 색 적용]: 4-2의 4번.

### 4-6. 화면 표시 경계 (Main 연결, 최종 APK 실기)

| 조작 | bench 로그 |
| --- | --- |
| 개발 진단 > [제어 검증] 탭 | "제어 검증 화면 보임: 새 작업은 버튼으로만 시작" |
| [밝기 검증] 탭 | "제어 검증 화면 숨김" |
| [제어 검증] 탭 → [← 휴식 화면] | 보임 → 숨김 |
| [개발 진단] 다시 열기(제어 검증 탭 유지) → 뒤로 가기 키 | 보임 → 숨김 |
| [개발 진단] 다시 열기 | 보임 |
| 하단 바 차량 앱으로 화면을 가림(Activity onStop) → 앱 다시 실행 | 숨김 → 보임 |
| [한 단계 낮추기]를 누르고 약 0.15초 뒤 [밝기 검증] 탭(공조 전원 OFF 상태) | "풍량 적용 준비" → "제어 검증 화면 숨김" → "화면 이탈: 준비 중이던 작업을 취소했어요(명령 보내지 않음)" → "ignored late read: 풍량 지원 확인". 기록 0건, 차량 쓰기 0 |
| [제어 검증] 탭으로 돌아옴 | "보임"만 남고 이어서 진행되는 작업 없음. 이후 차량 쓰기 로그(`setPropertyAsyncValue` HVAC·AMBIENT·SEAT) 0건 |

- 이 AAOS 화면에서는 HOME 키를 눌러도 앱이 resumed 상태로 남아 onStop이 오지 않았다. 그래서 Activity 이탈은 다른 앱으로 화면을 가리는 방식으로 확인했다.
- 화면 이탈 시 시트 STOP은 실기에서 다시 움직여 보지 않았다(PM 승인 없는 시트 이동을 하지 않음). 같은 `onHidden` 경로를 단위 테스트로 확인했다.

## 5. 최종 상태 (실제 조회)

| 항목 | SDK | Console |
| --- | --- | --- |
| 기어·문 | P, 닫힘 | Park, 문 4개 닫힘 |
| 무드등 | 스위치 OFF, 밝기 70(밝기 검증 탭 조회), 색 `0x00FFFFFF` | mode On(표기), `#ffffff`, 70% |
| 공조 | 전원·Auto·Dual false, 풍량 3, 22.0/22.0 | power/auto/dual/sync false, ac true, fan 3, 22.0/22.0 |
| 등받이 | 0 | recline driver 0, moves 0, active_count 0 |
| 기록 | `vehicle-control-records.json` = 기록 없음. 밝기 기록 없음, 미완료 요청·잠금 없음 | — |
| 오디오 | 오디오 포커스 스택 비어 있음 | — |

시험 조건(무드 조명 ON, 공조 전원·Auto·Sync ON)은 모두 정상 UI로 원래 OFF/false로 되돌렸고 SDK 조회로 확인했다. 앱은 공조 전원·듀얼·조명 스위치를 쓰지 않았다.

## 6. 단위 테스트 (`ControlBenchTest`, 30개)

- 기본 계약: 기록 저장 전 명령 없음, echo 불신, 저장 실패(디렉터리 fsync 실패 포함) → 명령 0, 타임아웃 잠금과 늦은 응답, `onFailed`, 재시작 뒤 잠금
- 차단: OFF·주차 아님·읽기 실패, 밝기 기록이 있으면 색, 풍량·온도 동시 작업
- 원복: 공유 상태가 바뀐 원복 실패, Auto 복원 1회, 사용자 변경
- 화면 이탈: 준비 취소(늦은 읽기 뒤 명령 없음)
- 시트: 첫 변화 STOP, MOVE/STOP 응답 순서(MOVE만·STOP만·역순·무응답)와 재시작 잠금, 안전 STOP(주차 상실·문 열림·값 없음·범위 밖·조회 실패·반대 방향), 위치 없이·리스너 실패·문 미확인이면 이동 안 함, 시트 원복
- 기록 형식: 엄격 검사
- 추가(통합 보완):
  - 한 번도 보이지 않은 bench는 아무것도 시작하지 않고, 숨김을 두 번 불러도 문제없음
  - Auto capability가 READ이거나 WRITE만이면 Auto 복원을 보내지 않음
  - 공유 상태 재조회를 기다리는 동안 close되거나 lease를 잃은 뒤 늦은 응답이 와도 파일 변경 0, 새 SDK 명령 0
  - 저장 오류 뒤 다른 작업의 복원이 확인돼도 기록을 지우지 않고 응답만 갱신, 이후 삭제·복원 거부
  - 범위 밖·형식 오류인 현재값(풍량 9, 온도 30.5, 6자리 색)이면 기록·명령 0
  - `jobId`·`requestId`가 중복된 기록 파일은 읽기 거부

## 7. 미확인과 한계

1. 등받이: 공개 SDK 위치가 actuator 이동을 반영하지 않아 이동량·방향 부호(SDK 기준)·허용 오차·원복을 공개 경로로 확인하지 못했다. 이 에뮬레이터 환경의 불일치인지, 실제 차량도 같은지는 미확인이다.
2. 색: 적용 확인 기준(alpha)은 PM 결정 전까지 결과 불명으로 남는다.
3. Auto true에서 풍량·온도 변경이 Auto를 끄는 차량: 이 에뮬레이터에서는 재현되지 않아 `setHvacAuto` 실기 경로는 미실행이다. Auto true 조건의 온도 변경은 시험하지 않았다.
4. 타임아웃·늦은 응답·`onFailed`: 이번 실기에서는 모든 요청이 0.07~0.33초 안에 `onComplete`로 끝나 실기 재현이 없다(단위 테스트로만 확인).
5. 화면 이탈 중 복원 완료, 주행 전환 중 시트 STOP의 실기 실행은 하지 않았다.

## 8. 증거

`app/build/reports/vehicle-controls/`(빌드 산출물 폴더, 커밋하지 않음)

- `logs/logcat-full.txt`(실행 전체 logcat, epoch 초), `logs/baseline*.txt`, `logs/seat-1.txt`·`seat-2*.txt`·`seat-3*.txt`, `logs/seat-record-after-*.json`, `logs/color-1~3.txt`, `logs/hvac-0~6.txt`, `logs/hvac-ui-*.txt`, `logs/sync-*.txt`, `logs/hvac-vhal.txt`, `logs/*-record-after-*.json`, `logs/records-after-*.json`, `logs/vc-state-*.json`, `logs/final-*.txt`, `logs/visibility-*.txt`, `logs/logcat-final-tail.txt`, `logs/granted-permissions*.txt`, `logs/install-*.txt`
- `screens/c01~c20*.png`
- `build/final-gradle-2.txt`, `build/final-test-rerun.txt`, `build/apk-manifest-final.txt`
