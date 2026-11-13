# 시트 프리셋 공개 SDK 검증 (STEP 14–15, debug 전용)

- 날짜: 2026-11-13
- 환경: Pleos Connect 에뮬레이터(AVD 이미지 2.1.0, `emulator-5554`, user 10), Vehicle SDK 2.3.2
- 범위:
  - 운전석 프리셋 선택 `selectSeatMemory`가 실제로 무엇을 하는지 확인한다.
  - 앱이 프리셋으로 "평소 자세 1번 / 휴식 자세 2번"을 쓸 수 있는지 확인한다.
- 제품 상태: **제품의 시트 기능(프리셋 포함)은 아직 구현하지 않았다.**
  - 시트 요구사항(6-2, F11)은 유지한다.
  - 제품 화면은 지금도 시트 미지원 안내를 보여 준다.

## 1. 결론

| 항목 | 결과 |
| --- | --- |
| 지원 조회(STEP 14) | `checkSeatMemoryCapabilities`: 접근 WRITE, 운전석(`ROW_1_LEFT`) 1~2. 서비스 원문 `SEAT_MEMORY_SELECT`, `propertyId 356518784` |
| SDK 선택 호출(STEP 15, 2회) | 2번·1번 요청이 SDK → 차량 서비스 → VHAL(`seat memory select of area: Row1Left, value: 2/1`)까지 **전달됐다**. 시트는 25초 동안 **어느 영역·축도 움직이지 않았다**. onFailed 없음 |
| 처리 응답 | SDK 내부 로그 `setProperty onResult … result = true`. 이 응답은 공개 API로 받을 수 없고(완료 콜백 없음, onFailed만 있음), **자세가 바뀌었다는 뜻도 아니다** |
| 네이티브 저장 | 차량 설정 앱의 저장 카드가 실제로 떴다. "시트 위치 변경됨 / 운전석 **0**에 저장할까요?" [취소]/[저장]. 대상이 0이어서 누르지 않았다 |
| 1·2번 저장과 이동 | **미검증.** 이번 실행에서 '선택된 프리셋' 값은 0으로 유지됐고, 1·2번에 저장하지 못했다 |

- 이동이 없었던 원인은 정하지 않는다. 이번 증거로는 다음을 가르지 못했다: 저장된 프리셋이 없음, 에뮬레이터 동작, 지원 실패.

## 2. debug 검증 도구 (release에서는 개발 진단 화면에 진입할 수 없다)

- 위치: 개발 진단 > [시트 프리셋(검증)]. release 빌드는 개발 진단에 들어갈 수 없다(`openDevTools = null`).
- 파일: `control/SeatPresetBench.kt`, `sdk/PleosSeatPresetPort.kt`, `SeatPresetScreen.kt`, `MainActivity.kt` 연결
- 호출 조건:
  - 공개 SDK만 쓴다(지원 조회, 5축 위치 조회, `selectSeatMemory`, 5축 STOP).
  - 요청 전에 진단 기록 `seat-preset-probe.json`을 저장한다. 저장에 실패하면 호출하지 않는다. 기록은 자동으로 지우지 않는다.
  - 화면 표시, P, 조수석 문 닫힘, 지원 번호 안, 다른 작업 없음일 때만 보낸다.
- 단계는 `REQUESTED`(요청함 · 결과 미확인) → `CLOSED`(관측 종료 · 결과 미확인 · 잠금 유지) → `CONFIRMED`(운영자 확인 · 자세 결과 미확인)다. 어떤 단계도 "완료/성공"이라고 쓰지 않는다.
- **잠금**: `CONFIRMED`가 아닌 기록이 있으면 새 선택 요청을 막는다.
  - 관측 종료, timeout, 호출 반환, onFailed는 잠금을 풀지 않는다.
  - 앱을 다시 실행해도 기록을 읽어 잠금을 유지한다.
- **운영자 확인 조건**: 다음 두 가지를 근거 문자열로 적고 [운영자 확인]을 눌러야 한다.
  - 외부 로그에서 해당 요청의 처리 응답을 확인했다.
  - 전 영역 moves 0 · actuator 0을 확인했다.
- 늦은 오류는 파일을 다시 읽어 자기 작업 ID의 기록만 고친다. 닫힌 화면은 갱신하지 않고, 새 차량 명령도 보내지 않는다.
- 공유 guard:
  - 프리셋 요청은 휴식 중이거나 다른 개발 진단 작업·기록이 있거나 제품 공조·무드등이 작업 중이면 막는다.
  - 프리셋 기록이 정리되지 않았으면 등받이 탐색·복원과 휴식 시작을 막는다.
  - 비상 STOP과 제품 원복은 막지 않는다.

## 3. 실행 기록

| 작업 | 요청 | 전달·응답(logcat) | 관측 | 운영자 확인 |
| --- | --- | --- | --- | --- |
| `#9e4b46bd` | 2번 | VHAL `value: 2` 1794526517.538, SDK 내부 `result = true` .546 | 시작 자세(fixture 앞뒤 30·등받이 40)에서 변화 없음(0.2초 간격 124 샘플) | `CONFIRMED`(Codex 승인 범위의 근거) |
| `#797aeeb8` | 1번 | VHAL `value: 1` 1794527257.537, SDK 내부 `result = true` .542 | 같은 시작 자세에서 변화 없음(124 샘플) | `CONFIRMED`(같은 기준의 근거) |

- SDK 위치 조회는 요청 전후 모두 5축 0이었다. Console이 30/40일 때도 0이었다. 단위 변환 의심은 [vehicle-controls.md](vehicle-controls.md) 4-1-1과 같다.
- 차량 설정 앱에서 길게 누르기 → [변경]은 **선택**(`SEAT_MEMORY_SELECT`)이다. 저장 증거가 아니다.
- 저장 카드 시험(Codex 승인, 등받이 탐색 최대 2회 중 1회 사용):
  - ControlBench 등받이 FORWARD에 이동 `onComplete`·STOP `onComplete`가 왔고, Console 등받이가 0 → 50으로 움직였다.
  - 그 직후 홈 화면에 저장 카드가 약 4초 동안 표시됐다.
  - 카드 번호와 저장 대상은 차량 설정 앱이 읽는 '선택된 프리셋' 값이다. 이번 실행에서 그 값은 네이티브 1번 선택 뒤에도 0이었고 변경 이벤트가 없었다.
  - 저장 로그(`Save driver position preset`)는 0건이다.

## 4. 정리와 보존

- 시트 위치는 Console 공식 position fixture로만 원래 snapshot(모든 영역·축 0)에 맞췄다. 프리셋으로 돌아온 것이 아니다. 매번 전 영역 moves 0, actuator 0을 확인했다.
- 등받이 탐색 기록은 다음을 모두 확인한 뒤 debug [기록 지우기]로 정리했다. 원본은 호스트 증거로 보존했다.
  - MOVE·STOP 종료 응답 수신
  - 위치 원복과 전체 정지
- 외부 수동 입력으로 바뀐 동승석 열선(2/Heating)은 보존했다. 우리 시험의 결과로 보지 않는다.
- 진단 기록 2건은 `CONFIRMED`로 남아 있다(삭제하지 않음). 제품 기록 3개는 비어 있다.

## 5. 테스트와 증거

- `./gradlew --max-workers=1 :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:lintRelease :app:assembleRelease`(JDK 17): exit 0, 테스트 235개 통과, lint 오류 0·경고 5(기존과 같음)
  - `SeatPresetBenchTest` 5개: 조건, 저장 후 호출, 관측 종료 뒤 잠금 유지, 운영자 확인 뒤 허용, 이전 실행 기록의 잠금, 늦은 오류의 자기 작업만 갱신, 예외 시 잠금 유지, STOP 비차단
  - `ControlBenchTest` +1(프리셋 미정리 시 등받이 이동 차단, STOP은 나감)
  - `RestFlowTest` +1(프리셋 미정리 시 휴식 시작 거절)
- 증거(빌드 산출물 폴더, 커밋하지 않음):
  - `app/build/reports/seat-preset-call/`(STEP 15 호출·원복)
  - `app/build/reports/seat-preset-save/`(저장 카드 시험)
  - `app/build/reports/seat-memory-support/`(STEP 14)
  - 보고: `app/build/reports/pm-live/step14-report.md`, `step15-report.md`

## 6. 공식 기술 문의

- [Pleos-Playground/pleos-playground-api Discussions #36](https://github.com/Pleos-Playground/pleos-playground-api/discussions/36) (Q&A Technical, 2026-11-13 04:28 UTC 게시).
- 주제: 에뮬레이터 2.1.0에서 가상 시트를 움직인 뒤에도 등받이 위치 getter와 listener가 0을 반환하는 현상([vehicle-controls.md](vehicle-controls.md) 4-1-1과 같은 문제).
- 사용자 위임에 따라 PM이 게시했고, 게시 내용을 다시 읽어 확인했다. 문의 관리는 PM이 한다(중복 문의·댓글·메일 없음).
- 2026-11-13 04:44 UTC 기준 답변·댓글이 없다. 문의는 보조 기록으로 둔다. 해결 계획은 공식 샘플·정상 설정으로 원인을 직접 분리하고 대안을 검증하는 것이다. 그때까지 제품 시트 기능(F11)은 미완료다.

## 7. 미확인

1. 1·2번 프리셋 저장과, 저장 뒤 `selectSeatMemory`로 시트가 움직이고 돌아오는지
2. 프리셋 이동 중 STOP의 효과
3. 공개 API로 선택 결과를 확인하는 방법(현재 문서와 SDK에서 찾지 못함)
