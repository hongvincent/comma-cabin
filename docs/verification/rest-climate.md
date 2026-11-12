# 휴식 공조(바람 줄이기·운전석 온도) 적용·복원 검증 (STEP 9)

- 날짜: 2026-11-13
- 목적: 제품 휴식 흐름에서 풍량을 한 단계 낮추고 운전석 목표 온도를 고른 값으로 맞췄다가, 끝나면 원래대로 돌리는 계약(제품 요구사항 6-1, 6-4, 6-5, 8-3)을 실제 앱으로 확인한다.
- 판정 기준: SDK 재조회값이다. `onComplete` 값은 요청 echo라 판정에 쓰지 않고, Virtual Console은 보조 증거로만 쓴다.
- 시트: 요구사항은 유지하지만 제품 자동 제어는 미승인이라 이번에 움직이지 않았다. 근거는 STEP 7의 공개 위치 불일치이고, 확인한 공개 API와 현재 버전(Vehicle SDK 2.3.2)에서는 위치 피드백 문제를 해결하지 못했다.

## 1. 구현 요약

| 파일 | 역할 |
| --- | --- |
| `climate/RestClimate.kt` | 제품 공조 컨트롤러. 그룹별 적용·종료 복원·잠금·외부 변경·P 상실 보류·[원래대로]/[기록 지우기]/[나중에]·debug 상호 배제를 맡는다. [재실행 보완] `onDebugChanged()`(화면 재계산만) |
| `climate/ClimateRecord.kt` | 그룹 기록 v1, 엄격 코덱, `FileClimateStore`(`rest-climate-records.json`, 공통 원자적 저장), 값 규칙(`same`, `onGrid`, 표시·전송 문자열 분리) |
| `climate/ClimatePort.kt` | 제품용 타입 포트. 기존 공개 SDK 어댑터 `PleosControlPort`만 감싸며 새 SDK 호출은 없다. 온도는 반올림 없이 `Float.toString`으로 보내고, 풍량은 유한한 정수 단계만 보낸다. 비정상 숫자는 값이 없는 것으로 본다 |
| `CabinProcess.kt` | 조명과 같은 프로세스 단위 owner(전용 Handler·Vehicle·주차 소스)에 공조를 더했다. debug 도구용 제품 busy는 조명+공조를 합친 값이다. [재실행 보완] `debugChanged()`: debug 상태가 바뀌면 조명·공조 화면의 차단 사유를 다시 계산한다(4절) |
| `lighting/RestLighting.kt` | [재실행 보완] `onDebugChanged()`만 더했다. 화면 상태 재계산만 하고 차량 조회·쓰기·복원·파일 변경은 없다(4절) |
| `rest/RestFlow.kt`, `audio/RestSettings.kt` | [바람 줄이기]·[운전석 온도 맞추기] 켬/끔(기본 켬)과 고른 °C(기본 22.0)를 저장한다. 첫 실행 안내는 v4다. 세션 훅은 그대로(1회, 종료는 소리 정지 뒤)다 |
| `MainActivity.kt`, `RestScreen.kt` | 실행 번호 매핑, 준비 화면 카드(실제 범위·간격을 조회 전용으로 읽은 ±), 휴식 상태줄, 종료 화면 항목별 결과, 공조 기록 카드. [재실행 보완] 밝기·제어 debug 도구의 상태 변화와 Activity 연결 직후에 `cabin.debugChanged()`를 부른다(4절) |

### 1-1. 그룹과 쓰기 규칙

1. **그룹**: 풍량·온도 영역을 좌석 포함 관계로 자동 모드·전원(온도는 연동도) 영역 하나씩에 매핑한다. 같은 자동 영역을 쓰면 한 그룹이다. 매핑이 애매하면 그 항목은 쓰지 않는다. 같은 자동 영역이어도 전원·연동 영역이 다른 항목은 기존 그룹에 합치지 않고 명령 전에 이유를 남기고 건너뛴다. 그래서 앱이 쓴 기록은 항상 코덱으로 다시 읽을 수 있다.
2. **원래 자동 모드**: 그룹의 첫 변경 바로 전에 한 번만 저장하고, 뒤의 항목이 덮지 않는다.
3. **적용 순서**(그룹마다 풍량 → 온도, 한 번에 하나):
   1. 지원(READ_WRITE·영역·범위·간격) 확인
   2. 현재값 확인. 원래값과 목표값이 모두 나중에 그대로 다시 쓸 수 있는 값이어야 한다(풍량 정수·범위, 온도 격자)
   3. 공유 상태(전원·자동·연동)를 마지막으로 조회
   4. 기록 저장. 실패하면 명령 0
   5. 최종 확인: 같은 실행, P + 주행 제한 없음, debug 없음, 저장 오류 없음, 같은 자원을 쓰는 그룹에 열린 요청 없음
   6. 명령 → 종료 콜백 → 0.5초 뒤 값과 공유 상태를 다시 읽어 판정
4. **목표**:
   - 풍량: 현재 −1이고 max(지원 최소, 1) 이상이어야 한다. 끔(0)은 쓰지 않는다.
   - 온도: 고른 값. 운전석 단독 영역(`ROW_1_LEFT`)만 바꾸고, 범위·간격(0.5든 0.25든)에 맞아야 한다. 맞지 않으면 바꾸지 않고 다시 고르라고 표시한다.
   - 전원이 꺼져 있으면 아무것도 쓰지 않는다. 연동(Sync, SDK Dual)이 켜져 있거나 확인되지 않으면 온도는 쓰지 않는다. 앱은 전원·Sync를 켜거나 끄지 않는다.
5. **공유 자원 잠금**: 종료 콜백을 받지 못한 요청(이전 실행 미응답 포함)이 있거나 작업이 진행 중인 그룹, 또는 그 그룹과 자동·전원 영역이 겹치는 그룹에는 새 적용·복원·Auto 쓰기를 보내지 않는다.
6. **변화의 출처**:
   - 우리 명령 전후로 공유 상태가 바뀌었거나 읽히지 않으면 출처를 알 수 없다고 보고 그룹을 `RESULT_UNKNOWN`으로 둔다.
   - 우리 명령 사이에 바뀐 것(리스너 불일치 → 별도 재조회로 확인, 또는 종료 직전 조회)은 차량 쪽 변경으로 보고 `EXTERNAL_CHANGE`로 둔다.
   - 두 경우 모두 그룹의 추가 적용과 자동 복원을 멈춘다. 리스너의 첫 값만으로는 정하지 않는다.
7. **자동 복원**(정상 종료·앱 이탈 등):
   - 종료 직전에 모든 항목과 공유 상태를 읽어, 앱의 마지막 값과 같을 때만 시작한다. 이때 시작 기준(base)을 저장한다.
   - 항목은 온도 → 풍량 순서로, 현재값이 원래값이면 쓰지 않는다. 각 명령 직전에 기준이 그대로인지 확인한다.
   - Auto는 자동으로 쓰지 않는다.
   - 마지막에 모든 항목·자동·전원·연동을 다시 읽어, 원래값이고 기준·Sync 조건에 맞을 때만 기록을 지운다. 아니면 항목별 실제값을 표시하고 기록(최종 재조회값 포함)을 남기며, 다시 시도하지 않는다.
8. **[원래대로]**:
   - 사용자가 요청한 원래값으로 되돌린다. 차량에서 바뀐 값도 포함한다.
   - 전원이 꺼져 있으면 켜지 않고 보류한다. Sync가 켜져 있으면 온도를 보류하고 "차량에서 연동을 끈 뒤" 다시 하라고 안내한다.
   - 항목을 되돌린 뒤 Auto가 원래와 다르면 `setHvacAuto`를 최대 1회 보낸다(직전에 READ_WRITE·영역·전원·Sync·기준을 다시 확인).
   - 작업 도중 공유 상태가 바뀌면 멈춘다.
9. **P 상실·잠금·저장 오류·손상 기록·수명·debug 상호 배제**: 무드등과 같다([rest-lighting.md](rest-lighting.md) 1-1).
   - P 상실이면 보류하고, P로 돌아와도 [원래대로] 전까지 쓰지 않는다.
   - 미응답·이전 실행 미응답 그룹은 잠근다.
   - 저장 오류 뒤에는 추가·삭제를 하지 않는다.
   - 손상 파일은 보존하고 공조를 막는다.
   - 프로세스 단위 owner가 실행 번호로 세션을 구분한다.
   - debug 기록·작업과 서로 차단한다. 무드등과 공조는 서로 막지 않는다.
10. **화면의 차단 사유**: [원래대로]·[기록 지우기]의 켜짐/꺼짐과 이유는 화면 상태를 만들 때 계산한다. debug 작업·기록·오류 상태가 바뀌거나 새 Activity가 연결되면 다시 계산한다(재실행 보완, 4절). 이 재계산은 차량을 읽거나 쓰지 않고 복원도 시작하지 않는다. 실제 차단은 버튼을 누를 때와 명령 직전에 다시 확인한다.

### 1-2. 기록 형식 `rest-climate-records.json` v1

`{"formatVersion":1,"groups":[{groupId, autoArea, powerArea, dualArea, autoOriginal, expected{POWER,AUTO,DUAL}, stage, autoRequestId, autoResponseReceived, priorRequestUnresolved, members:[{prop(FAN|TEMP), area, original, target, stage, requestId, payload, responseReceived, lastEcho, lastRead}]}]}`

- 풍량은 JSON 정수, 온도는 유한한 숫자(°C, Float로 바꾼 뒤에도 유한)여야 한다.
- 온도 항목은 운전석 단독 영역만 받고, 연동 영역이 운전석을 포함해야 한다. 항목 영역은 그룹의 자동·전원 영역 안이어야 한다.
- 다음 중복은 손상으로 본다: groupId, 자동 영역, 항목·영역, requestId.

## 2. 환경과 시작 상태

| 항목 | 값 |
| --- | --- |
| 기기 | `emulator-5554`, `ro.kernel.qemu=1`, 같은 AVD `Pleos_Connect_2_1_0_probe`. SDK·권한 변경 없음(설치 후 9개 `granted=true`) |
| 시작 상태(실제 조회) | P, 문 닫힘. 무드등 SDK OFF·70·`0x00FFFFFF`. 공조 전원·Auto·Sync false, 풍량 3, 22.0/22.0. 등받이 0. 기록 비어 있음 |
| 설정(설치 전) | `onboarding_version=3`, `rest_sound=QUIET`, `rest_level=LOW`, `rest_lighting=true` |
| 시험 APK | APK-1(설치 00:46, PID 14929): #1. APK-2(설치 00:48, PID 28793, 공조 제어 로직 최종): #2~#13. APK-3(설치 01:08, 문구 3건만 수정 — "휴식 전 자동 모드: 켜짐/꺼짐", "자동 모드가 다시 조절했어요(지금 N)", "원래대로 돌리지 못했어요(지금 N)", Codex 소스 확인값 `579d61d0…`): #14~#15. APK-4(설치 01:19, 재실행 버튼 갱신 보완, Codex 소스 확인값 `fbc1d52f…`, APK sha256 `a07aba61…`): #16~#17. APK-2 이후 제어 로직(쓰기·복원·판정)은 바뀌지 않았다 |
| 시험 조건 | 공조 전원·Auto·Sync·외부 풍량 변경은 차량 기본 UI 공조 패널(하단 바에서 열리는 오버레이, 앱을 멈추지 않음)로만 만들었다. 무드등 스위치는 차량 설정 앱 무드 조명 섹션의 버튼 위치를 특정해 눌렀다. 기어는 Console Gear 카드와 같은 요청이다. Console 공조 요청 fixture는 쓰지 않았다 |

## 3. 실기 결과

판정 값은 앱 로그의 SDK 재조회값이다. Console 값은 보조로 함께 적었다. 화면 증거는 `screens/c01~`에 있다. 각 행의 APK는 2절 "시험 APK"에 있다.

| # | 시험 | 실제 관찰 | 판정 |
| --- | --- | --- | --- |
| 1 | 설정 이전(버전 3) → 첫 실행 | 안내 v4(공조 문장 포함)가 나왔다. 확인 뒤 `onboarding_version=4`, `rest_fan=true`, `rest_temp=true`, `rest_temp_c=22.0`이 저장됐고, `rest_sound=QUIET`·`rest_level=LOW`·`rest_lighting=true`는 그대로였다 | 실행 확인 |
| 2 | 준비 화면 온도 범위 | 조회 전용 capability로 운전석 15.0~30.0°C, 0.5 간격을 읽었다. [+]로 22.5를 저장했고, 차량 공조 쓰기는 0건이었다 | 실행 확인 |
| 3 | 공조 전원 OFF에서 휴식 | 바람·온도 모두 "공조가 꺼져 있어 건드리지 않았어요". `HVAC` 쓰기 0건 | 실행 확인 |
| 4 | 기본 순환(전원 ON, Auto·Sync 꺼짐, 22.5) | 적용: 풍량 요청 2 → 재조회 2, 운전석 요청 22.5 → 재조회 22.5, 조수석 22.0 그대로. 그룹 기록(자동 원래 false, 두 항목 `APPLIED`, 응답 받음). 사용자 종료: 온도 22.0 → 풍량 3 복원 → 최종 재조회 {3, 22.0, 전원 true·Auto false·Sync false} = 원래값 → 기록 삭제. 종료 화면 항목별 "원래대로 돌렸어요", "자동 모드는 원래대로(꺼짐)예요" | 실행 확인 |
| 5 | [바람 줄이기] 끔 | 온도만 22.0→22.5→22.0. 풍량 쓰기 0건, 기록 정리 | 실행 확인 |
| 6 | [운전석 온도 맞추기] 끔 | 풍량만 3→2→3. 온도 쓰기 0건, 기록 정리 | 실행 확인 |
| 7 | Sync 켜짐(차량 패널) | 온도는 "공조 연동(Sync)이 켜져 있어 운전석 온도는 바꾸지 않았어요"(온도 쓰기 0건, 현재값 조회만), 바람은 3→2→3 원복 → 기록 정리 | 실행 확인 |
| 8 | Auto 켜짐(차량 패널) | 풍량 2·온도 22.5 적용 동안 Auto는 true 그대로였다(이 에뮬레이터는 바꾸지 않음). 종료 뒤 원복, 최종 재조회 {3, 22.0, Auto true} → 기록 정리 | 실행 확인 |
| 9 | Auto 원래 true → 휴식 중 차량 패널로 Auto 끔 → 사용자 종료 → [원래대로] | 종료 직전 조회가 Auto false ≠ 앱의 마지막 값 true를 잡아 `EXTERNAL_CHANGE`로 두었고 종료 뒤 쓰기는 0건이었다("차량에서 바뀐 공조 설정은 유지했어요"). [원래대로]: 온도 22.0 → 풍량 3 → `setHvacAuto(true)`(서비스 로그 `HVAC_AUTO_ON value=true`) → 최종 재조회 {3, 22.0, Auto true} → 기록 삭제. 자동 복원이 아니라 사용자의 명시적 복원에서만 Auto를 썼다 | 실행 확인(`setHvacAuto` 실제 경로) |
| 10 | 휴식 중 차량 패널 풍량 ∧(2→3) | 리스너 3 ≠ 앱 값 2 → 재조회 3 → 외부 변경 확인 → 그룹 `EXTERNAL_CHANGE`. 종료 뒤 쓰기 0건. [원래대로]는 현재값으로 판단해 풍량(이미 3)은 쓰지 않고 온도만 22.0으로 되돌린 뒤 최종 재조회 → 기록 삭제 | 실행 확인 |
| 11 | 휴식 중 Console 기어 Drive → Park | `LEFT_PARK` 종료 → "주차 상태가 아니어서 되돌리지 않았어요"(`HELD`). Park로 돌아온 뒤 10초 동안 쓰기 0건(Console 2 / 22.5 유지). [원래대로] → 22.0·3 복원, 최종 재조회 → 기록 삭제 | 실행 확인 |
| 12 | 휴식 중 차량 앱으로 화면을 가림 | onStop(`wm_on_stop_called`) → `APP_LEFT` → 소리 종료 → 차량 앱이 앞에 있는 동안 온도 → 풍량 원복과 최종 재조회 → 기록 삭제 | 실행 확인 |
| 13 | 정상 종료(TIME_UP, 483초), 무드등 켬, 휴식 중 [휴식 소리 켬] | 무드등 스위치는 차량 설정 앱 무드 조명 섹션으로 켜고(`t12`), 휴식 중 [휴식 소리 켬]을 실제로 눌러 포커스 GAIN #25 허용, 0.3으로 재생했다. 종료 순서: 종료 시각에 휴식음 정지 → `TIME_UP` → 오디오 종료(안내가 포커스 #25 유지) → 안내음 → 무드등·공조 복원이 함께 진행(무드등 70·`0x00FFFFFF`, 공조 온도 22.0 → 풍량 3, 각각 재조회 뒤 기록 삭제, 공조는 최종 재조회 {3, 22.0, 전원 true·Auto false·Sync false}) → 음성 Start/Done → 포커스 반납 → `VOICE`. 끝난 뒤 무드등 스위치는 같은 섹션의 [끄기]로 되돌렸다(`t14`). 마지막 60초 감쇄는 STEP 6의 VolumeShaper라 별도 로그가 없고 물리 소리는 관찰할 수 없다 | 실행 확인 |
| 14 | 적용 기록이 남은 상태에서 강제 종료 | 휴식 시작(PID 13172) → 풍량 3→2, 운전석 22.0→22.5 적용 확인. 기록이 안전한지 먼저 확인했다(그룹 `ACTIVE`, 두 항목 `APPLIED`·응답 받음, Auto 응답 받음, 이전 미응답 false). `am force-stop --user 10 app.commacabin` → 프로세스 없음. 기록 파일은 그대로였고 Console 2 / 22.5 | 실행 확인 |
| 15 | 다음 실행(APK-3, PID 24181) | "공조 기록 불러옴: … ACTIVE … 잠금 false"를 남겼고, 준비 화면이었으며(휴식 재시작 없음), `HVAC` 쓰기는 0건이었다. 카드에 원래 3, 원래 22.0°C, 원래 자동 꺼짐과 [원래대로]/[기록 지우기]/[나중에]가 나왔다. **결함**: [원래대로]가 꺼진 채 "개발 진단 작업이나 기록이 남아 있어 되돌릴 수 없어요"를 계속 보였다(debug 기록 파일은 비어 있음). 꺼진 버튼을 눌러도 변화가 없었다(`t16-records-after-restore.json`은 계획한 파일명이고, 내용은 `ACTIVE` 그대로다). 원인을 확인하려고 화면 표시만 바꾸는 [나중에] → [남은 공조 기록 보기]를 눌렀더니 버튼이 켜졌다(`HVAC` 쓰기 0). 우회일 뿐 고친 것이 아니다. 원인과 수정은 4절에 있다 | 결함 발견 |
| 16 | 보완 APK-4 설치(앱 강제 종료 포함) → 새 실행(PID 32242) → [원래대로] | 설치 전 기록이 안전한지 확인했다. 설치 전·후 기록 파일은 바이트 단위로 같았다. 불러옴 `ACTIVE`·잠금 false, 준비 화면, 쓰기 0. 초기 debug 조회가 끝난 뒤(밝기 70 응답 `…392.615`) 화면을 아래로 내리기만 하고 다른 버튼은 누르지 않은 상태에서 [원래대로]·[기록 지우기]·[나중에]가 모두 켜져 있었고, 원래값도 표시됐다. 누르기 전까지 `HVAC` 쓰기는 0건이었다. [원래대로]: 운전석 22.0 요청 → 재조회 22.0 → 풍량 3 요청 → 재조회 3 → 최종 재조회 {3, 22.0, 전원 true·Auto false·Sync false} = 원래값 → 기록 삭제(`{"groups":[]}`). 서비스 로그의 `HVAC` set은 이 2건뿐이었고, Auto는 원래값과 같아 쓰지 않았다. Console 3 / 22.0 / 22.0 | 실행 확인 |
| 17 | 최종 정리 | 공조 전원은 차량 패널로 껐다. 휴식 소리는 조용히, 온도 선택은 22.0으로 되돌렸다(앱 화면, 차량 쓰기 0). SDK로 다시 읽으려고 debug 제어 검증의 [새로고침(조회만)]을 썼다: 전원 false, Auto false, Dual false, 풍량 3, 22.0/22.0, 무드등 스위치 OFF, 색 `0x00FFFFFF`, 등받이 0(조회 중 `HVAC` set 0건). 밝기 70은 같은 실행의 시작 조회값이다. 기록 파일: 공조 `{"groups":[]}`, 무드등·제어 `{"records":[]}`, 밝기 v1 파일 없음. 오디오 포커스 스택은 비어 있다. 설정: `rest_sound=QUIET`, `rest_level=LOW`, `rest_lighting`·`rest_fan`·`rest_temp`=true, `rest_temp_c=22.0`, `onboarding_version=4` | 실행 확인 |

## 4. 재실행 복원 버튼 결함과 보완

- **증상**(3절 #15): 응답을 모두 받은 기록이 남은 채 앱이 다시 실행되면, 기록이 정상으로 로드되고 debug 기록도 없는데 [원래대로]가 꺼진 채 "개발 진단 작업이나 기록이 남아 있어 되돌릴 수 없어요"가 남았다.
- **원인**(코드와 로그로 확정):
  - 제품 무드등·공조의 `restoreBlock`은 화면 상태를 만들 때(`publish()`) 한 번 계산된다. 계산에는 `debugBlock()`(현재 Activity의 debug 도구 busy, 또는 debug 기록 파일)이 들어간다.
  - 새 실행은 `onCreate` 1.5초 뒤 `AmbientProbe.refresh()`(조회 전용)를 시작한다. 이 동안 probe가 `busy`라 debug busy로 계산된다.
  - APK-3 로그에서 공조의 마지막 화면 갱신(준비 화면 온도 범위, `1794499838.264`)이 probe 새로고침 응답(`.267` 지원 확인 ~ `.893` 밝기 70) 도중이었다.
  - `probe.onState`·`bench.onState`는 debug 화면 상태만 바꾸고 제품 쪽을 다시 계산하지 않았다. 그래서 조회가 끝난 뒤에도 차단 사유가 남았다.
  - 새 Activity는 연결할 때 `cabin.*.state`를 그대로 복사하므로, 이전 Activity(또는 없음)의 debug 상태로 계산된 값이 남을 수도 있었다.
- **수정**(Main·CabinProcess·controller 화면 갱신 API와 테스트만):
  - `RestClimate.onDebugChanged()`, `RestLighting.onDebugChanged()`: `alive`일 때 `publish()`만 한다. 차량 조회·쓰기, 자동 복원, 기록 파일 변경이 없다.
  - `CabinProcess.debugChanged()`: 두 owner에 전달한다.
  - `MainActivity`: `probe.onState`·`bench.onState`(작업·기록·저장 오류·손상 기록·미응답 변화가 모두 이 경로로 온다)에서 `cabin.debugChanged()`를 부른다. `onCreate`에서 `cabin.debugBusy`와 화면 리스너를 연결한 직후에도 한 번 부른다.
- **유지한 차단**:
  - [원래대로]를 누르면 `restoreBlock()`을 다시 확인한다. 명령 직전에는 `restoreHold()`, 적용 때는 `commandBlock()`이 debug 상태를 그때그때 확인한다.
  - debug 쓰기·미응답·기록이 있으면 계속 막는다. 버튼을 강제로 켜거나 debug 차단을 약하게 바꾸지 않았다.
- **반대 방향(제품 busy → debug 화면)**:
  - debug 밝기·제어 화면의 버튼은 자기 상태(`canStart` 등)로만 켜지고, 제품 busy를 화면 상태에 저장하지 않는다. 그래서 오래된 값이 남지 않는다.
  - 제품 busy(`cabin.productBusy()`)는 누를 때(`startChange`·`restore`)와 매 쓰기 직전(`vehicleBlock`·`stillValid`·Auto 복원 확인)에 매번 새로 확인한다. 제품이 작업 중이거나 기록이 있으면 이유와 함께 거절한다.
  - 거절 문구가 공조 때문일 때도 "제품 휴식 무드등 작업"으로 시작한다(뒤에 실제 이유가 붙음). 허용 파일 밖(`AmbientProbe`·`ControlBench`)이라 바꾸지 않고 후속 항목으로 남긴다.
- **Activity 교체 순서**:
  - 새 Activity가 `debugBusy` → 리스너 → `debugChanged()` 순서로 연결한다.
  - 이전 Activity의 `onDestroy`는 자기 것일 때만 해제한다. 늦게 오는 이전 bench·probe 상태 변화는 재계산만 일으킨다.
  - 같은 프로세스에서 Activity를 교체하는 실기는 이번 보완에서 다시 하지 않았다(6절).
- **회귀 테스트**(2개):
  - `RestClimateTest`·`RestLightingTest` "a debug change only redraws the restore block …"
  - 이전 실행의 적용 기록을 debug 조회 중에 불러오면 꺼짐이다. 조회가 끝나 `onDebugChanged()`를 부르면 켜진다. debug 기록이 생기면 다시 꺼지고, 그때 누르면 거절된다. 갱신을 몇 번 해도 차량 호출·저장은 0이고 기록 단계는 그대로다. 그 뒤 명시 복원은 성공하고 기록이 정리된다.
  - `onDebugChanged()`를 빈 함수로 바꾸면 두 테스트가 실패하는 것도 확인했다(53개 중 2개 실패, `build/gradle-fix-mutation.txt`). 확인 뒤 원래대로 되돌렸다.
- **실기**: 3절 #16.

## 5. 단위 테스트

- 전체: `./gradlew --offline testDebugUnitTest --rerun lintDebug assembleDebug` → exit 0, 219개(실패·오류·건너뜀 0), lint 오류 0·경고 5(이전과 같음), assemble 성공(`build/gradle-fix-final.txt`). Codex도 같은 소스(`fbc1d52f…`)로 219개 통과를 따로 확인했다.
- `RestClimateTest` 29개:
  - both choices off reads nothing, power off writes nothing and never turns it on
  - a rest lowers the fan and sets the driver only, then restores both with the auto original saved once
  - an auto change across the fan command has no knowable source - no temperature, no automatic auto restore, only an explicit restore
  - a failed auto restore is not retried and keeps the record
  - an explicit auto restore checks read-write support and the area again
  - the record goes only after a full re-read - auto restored but the fan at 4 keeps it with the actual value shown
  - a failed last read keeps the record
  - an explicit restore decides by the current value, not by an old RESTORED stage
  - groups sharing a power area never overlap - an open request in one blocks the other until it answers
  - an original temperature off the vehicle grid is never changed
  - a mode change during a restore stops the further writes
  - every fan area is handled per group, and one group's failure does not stop the other
  - the fan never goes below max of min and 1, and never to off
  - temperature - driver-only area, exact grid value sent unrounded, off-grid or Sync on skipped while the fan goes on
  - the SDK adapter sends 22_25 as 22_25 and a fan only as a whole step
  - a failing save sends nothing and then blocks adding records
  - a timeout locks the group, the late answer is read, and the group is restored after the end
  - losing parking holds - a late answer after P is back sends nothing until an explicit restore
  - a stale first listener value is checked by reads, a real change stops the group and only an explicit restore writes
  - a shared state changed from the vehicle side keeps the group as it is at the end
  - an auto change that could not be attributed is left for a manual check, not reverted
  - an explicit restore with Sync on keeps the temperature, with power off writes nothing, and auto goes back last
  - an end from an older session changes nothing and a replaced owner never writes
  - a debug change only redraws the restore block - on after the first debug read of the next run, off with debug work or records, no vehicle call
  - a group that never answered in an earlier run stays locked, and debug work blocks apply and restore
  - onFailed after the value changed is not applied, and a late check read after the end overwrites nothing
  - the codec rejects a non-driver temperature, a Sync area without the driver, non-finite numbers and duplicates
  - one auto area with front and rear power areas - the rear fan is not merged and the written record reads back
  - Sync turned on after the last restore write keeps the record with the final readings
- `RestLightingTest`: 재실행 버튼 갱신 회귀 1개 추가(전체 24개).
- `RestSettingsTest` +1(STEP 9 설정 기본값·이전), `RestFlowTest` +1(공조 선택은 준비 화면에서만 저장, 시작 때 상태로 전달).

## 6. 실행하지 않은 것과 한계

- 같은 프로세스에서 Activity만 교체되는 경우의 공조 실기(STEP 8에서는 무드등으로 Codex가 확인). 이번 보완의 연결 순서는 코드와 설계로만 확인했다.
- 무드등의 재실행 버튼 갱신은 단위 테스트로만 확인했다(실기 무드등 기록 없음).
- 실기에서 버튼이 꺼졌다가 켜지는 순간 자체는 캡처하지 못했다. #16의 첫 화면 덤프는 스크롤 전이라 카드 버튼이 화면 밖이었다. 조회가 끝난 뒤 켜진 상태와 쓰기 0은 확인했다.
- 단위 테스트로만 확인한 경로: 타임아웃·늦은 응답, 저장 오류, 손상 파일, `onFailed`, 전원 영역을 공유하는 그룹 잠금, 복원 중 모드 변화, 0.25 간격, [원래대로] 때 전원 꺼짐·Sync 켜짐, Auto 복원 실패, 출처를 알 수 없는 Auto 변화.
- 마지막 60초 감쇄의 별도 로그와 물리 소리(STEP 6 한계와 같음).
- 후속 UI 정리: 준비 화면이 길어 [쉬기 시작]이 스크롤 아래로 가려진다. debug 거절 문구의 "무드등" 표기.
- 정상 8분 종료는 APK-2(제어 로직 동일)로 확인했고, 보완 뒤 다시 하지 않았다(Codex 지시: 제어 로직이 바뀌지 않으면 불필요).

## 7. 증거

`app/build/reports/rest-climate/` 아래(빌드 산출물이라 저장소에는 없음):

- `logs/logcat-full.txt`: 전체 로그. 시험 시작부터 최종 정리까지 수집하고 멈췄다.
- `logs/t1~t19-*.txt`, `logs/*records*.json`: 시험별 화면 문구·로그·기록 파일. 재실행·보완 관련은 `t15`(강제 종료), `t16`(APK-3 결함), `t16b`(표시만 갱신 확인), `t17`(APK-4 설치·불러옴·버튼·복원), `t18`(설정 복귀), `t19`(최종 SDK 조회·기록·포커스)이다.
- `logs/install-1~4.txt`, `logs/ui-power-off-final.txt`(차량 패널로 전원 끔).
- `screens/c01~c29`: c19~c22는 APK-3 다음 실행과 결함, c23은 표시 갱신 뒤, c24~c26은 APK-4 다음 실행·버튼 켜짐·복원 뒤, c27~c29는 최종 정리.
- `build/gradle-fix-targeted.txt`, `build/gradle-fix-mutation.txt`, `build/gradle-fix-final.txt`(+`-exit.txt`)
