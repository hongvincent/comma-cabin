# 조용한 휴식 흐름 (STEP 5)

- 날짜: 2026-11-12
- 목적: 첫 실행 안내 → 휴식 준비 → 휴식 중 → 종료 → 다시 쉬기를 사용자가 실제로 조작하는 흐름으로 만들고, 차량 상태 읽기·시간·화면·종료 처리를 Pleos 에뮬레이터에서 확인
- 범위: **중간 산출물**이다. 차량 상태는 읽기만 한다. 시트·조명·공조 적용과 복원, 휴식 소리, 음성 안내는 다음 단계에서 연결한다(요구사항은 [product-spec.md](../product-spec.md)에 그대로 있음). 앱의 차량 set·move·STOP 호출은 추가하지 않았고, 밝기 진단도 자동 실행하지 않는다.
- 결론: 안내, 준비, 어둡게, 직접 종료, 다시 쉬기, FR 문 열림 종료, D 전환 종료, 다른 앱 이탈과 복귀, 새 프로세스 시작, 자연스러운 정상 종료를 에뮬레이터에서 **실행 확인**했다. 문·기어 변화는 0.2초 안에 화면과 종료에 반영됐다. 시험 전후 차량 상태는 같다.

## 1. 환경

STEP 3·4와 같은 호스트와 에뮬레이터를 썼다([ambient-brightness.md](ambient-brightness.md) 1장). 에뮬레이터는 다시 띄우거나 재부팅하지 않았다.

| 항목 | 값 |
| --- | --- |
| 기기 확인 | serial `emulator-5554`, `ro.kernel.qemu=1`(조작 명령마다 확인), AAOS 사용자 10, 2560×1440 160dpi |
| 설치 | `adb -s emulator-5554 install -r`(앱 데이터 유지). 앱 데이터 삭제와 시스템 시각 변경은 하지 않았다 |
| 기기 시계 | 호스트보다 약 91ms 늦음(`adb shell date`와 호스트 시각 비교, 왕복 약 75ms). 반영 시간 계산에 보정했다 |
| 권한 | APK 권한이 STEP 4와 같다(`aapt2 dump permissions` 비교). `INTERNET` 없음. 위치·마이크·네트워크 권한을 추가하지 않았다 |
| 백업 | APK에 `allowBackup=false`, `dataExtractionRules`, `fullBackupContent`가 들어 있다(`aapt2 dump xmltree`) |

## 2. 구현 범위

| 파일 | 내용 |
| --- | --- |
| `rest/RestTime.kt` | 준비 화면의 날짜·시각 계산(기본 픽업, 휴식 종료, 남은 휴식, 날짜 차이) |
| `rest/DoorMonitor.kt` | 조수석 문 감시. 화면이 보일 때만 동작 |
| `rest/RestFlow.kt` | 화면 상태, 시작 조건, 세션, 타이머, 종료 처리 |
| `RestScreen.kt` | 안내·준비·휴식·종료 화면(Compose) |
| `MainActivity.kt` | 위 요소 연결, onStart/onStop, 창 밝기·화면 켜짐, debug 전용 [개발 진단] 보조 화면 |
| `res/xml/data_extraction_rules.xml`, `res/xml/backup_rules.xml`, `AndroidManifest.xml` | 백업·기기 이전 제외 규칙 |
| `app/build.gradle` | `buildConfig true`(debug에서만 [개발 진단] 표시) |

상태·시간 계산(`RestTime`, `RestFlow`, `DoorMonitor`)은 Android에 의존하지 않아 단위 테스트로 검증한다. SDK와 화면 연결은 `MainActivity`·`RestScreen`이 맡는다.

### 2-1. 시작 조건과 종료 이유

| 조건 | 시작 불가 이유(화면 문구) | 휴식 중이면 |
| --- | --- | --- |
| 기어 UNKNOWN(표준 Car 재연결 포함) | 기어 상태를 확인할 수 없어요 | 즉시 중단: 차량 상태 확인 불가 |
| 기어 P 아님 | 기어를 P(주차)로 바꿔 주세요 | 즉시 중단: P 이탈 |
| 주행 제한 응답 없음 | 주행 제한 상태를 확인할 수 없어요 | 즉시 중단: 차량 상태 확인 불가 |
| 주행 제한 있음(P여도) | 주행 제한 중이라 쉴 수 없어요 | 즉시 중단: 주행 제한 |
| 문 확인 중 / 미지원 / 오류 | 각각 별도 문구 | 즉시 중단: 문 상태 확인 불가 |
| 문 열림 | 조수석 문이 열려 있어요 | 즉시 중단: 문 열림 |
| 휴식 종료 시각이 지남 / 3분 미만 | 각각 별도 문구 | — |

- 시작 버튼은 조건이 모두 맞을 때만 눌린다. 누르는 순간에도 기어·주행 제한·문·시각을 다시 읽어 확인한다.
- 직접 끝내기, 시각 도달(정상 종료), 앱 이탈(onStop)은 각각 다른 종료 이유다. 종료 화면은 제목(끝났어요 / 끝냈어요 / 멈췄어요), 이유, 종료 시각, [다시 쉬기]를 보여 준다.
- 종료는 세션마다 한 번만 처리한다. 종료 뒤 늦은 타이머·차량 콜백은 상태를 바꾸지 않고, 다시 P·문 닫힘이 돼도 자동으로 시작하지 않는다.

### 2-2. 시간 계약

- 준비 화면: 벽시계(`System.currentTimeMillis`)와 기기 시간대로 계산한다. 기본 픽업 = 현재 + 20분을 현지 시각 5분 단위로 올림. −5분/+5분은 날짜를 포함한 시각을 그대로 옮긴다. 과거로 줄여도 다음 날로 넘기지 않는다. 오늘이 아니면 "내일"·"어제"·"M월 d일"을 붙인다.
- 휴식 종료 = 픽업 − 2분. 시작 가능 = 남은 휴식 3분 이상.
- 휴식 중: 시작 순간에 `종료 기준 = elapsedRealtime + 남은 휴식`을 정한다. 250ms마다 `종료 기준 − 현재 elapsedRealtime`으로 남은 시간을 다시 계산한다(1씩 빼지 않음). 시스템 시계가 바뀌어도 시작한 휴식은 길어지거나 짧아지지 않는다. 표시용 종료 시각만 시작 때의 벽시계 값이다.
- 마지막 60초는 "곧 끝나요"로 표시한다. 소리가 없으므로 소리 줄이기는 표시하지 않는다.
- 10초 동안 터치가 없으면 이 앱 창의 밝기만 0.02(`LayoutParams.screenBrightness`)로 낮추고 남은 시간만 흐리게 남긴다. 어두울 때 첫 터치는 화면만 깨우고 조작을 실행하지 않는다.
- 휴식 중에만 `FLAG_KEEP_SCREEN_ON`을 켠다. 종료·onStop·onDestroy에서 창 밝기와 화면 켜짐을 원래대로 돌린다. 시스템 밝기와 차량 조명은 건드리지 않는다.

### 2-3. 문 조회 계약 (`DoorMonitor`)

1. onStart에서 새 세대로 시작한다. `checkDoorPositionCapabilities`로 `ROW_1_RIGHT` 영역, 읽기 권한, min/max를 확인한다. 닫힘은 capability의 min과 같은 값, 그보다 큰 범위 안 값은 열림이다(실제 범위 0..1).
2. 구독(`registerDoorPosition`)을 건 뒤 현재값을 직접 조회한다. 응답이 오면 **1초 뒤** 다시 조회한다(조회는 한 번에 하나). 첫 구독 알림은 기다리지 않는다.
3. 응답 상한은 **2초**다. 조회·구독 오류나 상한 초과는 오류로 표시하고, 1초 뒤 새 세대로 처음부터 다시 연결한다. 그 사이 상태는 오류로 남는다.
4. null, 영역 없음, 범위 밖 값, 오류, 상한 초과는 닫힘으로 보지 않는다. 미지원은 미지원으로 표시한다.
5. 순서: 조회를 보낼 때와 이벤트를 받을 때마다 번호를 붙인다. 조회 응답은 보낸 뒤에 더 새로운 조회·이벤트가 반영됐으면 버린다.
6. onStop에서는 휴식을 먼저 끝낸 뒤(앱 이탈) 세대를 바꾼다. 예약된 조회와 진행 중 응답을 무효로 하고, 구독을 해제하고, 상태를 확인 중으로 되돌린다. 백그라운드에서는 조회하지 않는다. 다시 보이면 새 세대가 현재값을 확인한 뒤에야 시작을 허용한다.

기어·주행 제한은 STEP 3에서 검증한 `CarParkingSource`(연결 세대, 재연결 때 UNKNOWN)를 그대로 쓴다.

### 2-4. 저장

- 새 SharedPreferences `comma_cabin_rest`에 키 `onboarding_seen`(안내를 닫았는지) 하나만 저장한다. 픽업 시각, 이용 기록, 세션은 저장하지 않는다. 그래서 새 프로세스는 휴식을 재개하지 않는다.
- 기존 복원 기록 파일(`ambient-probe-recovery.json`)과 그 잠금 계약은 바꾸지 않았다.
- `allowBackup="false"`는 유지했다. Android 공개 문서([Auto Backup](https://developer.android.com/identity/data/autobackup), [`<application>`](https://developer.android.com/guide/topics/manifest/application-element#allowbackup))에 따르면, Android 12 이상을 대상으로 하는 앱은 일부 기기에서 이 값만으로 기기 간 이전이 막히지 않는다. 그래서 `dataExtractionRules`의 `cloud-backup`·`device-transfer` 모두에서 `root`·`file`·`database`·`sharedpref`·`external`을 제외했다. Android 11 이하용 `fullBackupContent`에도 같은 제외 규칙을 넣었다. 전역 설정은 바꾸지 않았다.

## 3. 단위 테스트

`./gradlew :app:testDebugUnitTest --rerun :app:lintDebug :app:assembleDebug --console=plain` → **82개, 실패 0, 오류 0, 건너뜀 0**, 종료 코드 0. 기존 50개(`AmbientProbeTest` 26, `SupportDiagnosticsTest` 8, `TtsDiagnosticsTest` 6, `FileRecoveryStoreTest` 6, `ParkingTrackerTest` 4)는 모두 그대로 통과했다.

| 클래스 | 수 | 확인하는 것 |
| --- | --- | --- |
| `RestTimeTest` | 4 | 5분 올림(정각·1초·1ms), 자정 넘김(다음 날짜, 실제 길이), 픽업은 내일이고 종료는 오늘인 경우, 3분 경계·지난 종료 |
| `RestFlowTest` | 15 | 안내 한 번, 시작 순간 재확인, P+주행 제한 조합·재연결 UNKNOWN, 문 확인 중·미지원·오류·열림, 3분 미만, 과거로 줄여도 날짜 유지, 정상 종료(마지막 60초 포함), 늦은 tick, 시계 뒤로·앞으로, 중단 원인 8가지, 중복 종료·종료 뒤 콜백, 이전 세션 시간, 어둡게·첫 터치, 다시 쉬기(유지·새 기본값), 이탈 후 복귀 |
| `DoorMonitorTest` | 13 | 직접 조회로 닫힘, 문 이벤트 뒤 늦은 조회 무시, 이벤트 뒤 보낸 조회는 반영, 알림 없이 주기 조회로 열림 감지, null·범위 밖, 영역 없음·쓰기 전용·NOT_SUPPORTED, capability 범위 사용(0..100), 조회 오류 → 새 세대 복구, 상한 초과 뒤 늦은 응답 무시, 구독 등록 실패·구독 오류, stop 뒤 조회·구독·예약 정지, 재시작 뒤 이전 세대 무시, stop 뒤 오류 응답 무시 |

처음 실행에서는 `RestFlowTest` 1개가 실패했다. 테스트가 60초를 기다린 뒤 바로 `휴식 끝내기`를 불렀는데, 그사이 화면이 어두워져 첫 조작이 의도대로 무시된 것이다. 테스트에서 먼저 화면을 깨운 뒤 끝내도록 고쳤다. 같은 이유로 우연히 통과하던 다시 쉬기 테스트도 바로잡았다. 앱 코드는 바꾸지 않았다.

## 4. 빌드·린트

| 명령 | 종료 코드 | 결과 |
| --- | --- | --- |
| `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain` | 0 | BUILD SUCCESSFUL. 린트 오류 0, 경고 5(`GradleDependency` 2, `AndroidGradlePluginVersion`·`OldTargetApi`·`MissingApplicationIcon` 각 1) |
| 같은 명령, 테스트만 `--rerun` | 0 | 테스트 82개 실제 재실행 |

STEP 4의 `DataExtractionRules` 경고는 백업 규칙을 넣어 없어졌다. `gradle clean`이나 build 폴더 삭제는 하지 않았다.

## 5. 에뮬레이터 시나리오

차량 입력은 공개 Virtual Console 카드와 같은 요청만 **curl로** 보냈다: FR 문 `PUT /vehicle/body?door_fr=1|0`(STEP 4와 같음), 기어 `GET /vehicle/gear?gear=Drive|Park`(STEP 3와 같음). 콘솔과 브라우저는 다시 열지 않았다. 숨김 API, 비공개 VHAL, `driving_regulation` 명령은 쓰지 않았다.

| # | 입력 | 기대 | 실제 | 증거 | 판정 |
| --- | --- | --- | --- | --- | --- |
| 1 | 설치 후 첫 실행 | 안내 1회 | 안내가 나왔다(현재 하는 일만: 픽업 2분 전 종료, 문·기어·이탈 종료, 화면 어둡게, 소리로 깨우지 않음). [확인했어요] 뒤 준비 화면. 설정 파일에는 `onboarding_seen=true` 하나만 있다. 이후 새 프로세스에서는 안내가 나오지 않았다 | `r01`, `r02`, `prefs-after-onboarding.txt` | 실행 확인 |
| 2 | 준비 화면 | 기본 픽업, 조건 3개 | 02:56경(기기 시각) 픽업 03:20, 휴식 종료 03:18, 21분, ✓ 3개 | `r02`, `ui-02-ready.txt` | 실행 확인 |
| 3 | +5분, −5분 ×7 | 시각·남은 휴식이 바뀜. 과거는 오늘 날짜 그대로 | 03:25 → 02:50 "이미 지난 시각이에요", 종료 시각이 지났다는 이유, 시작 비활성. 다음 날로 넘어가지 않았다 | `r03`, `ui-03-adjust.txt` | 실행 확인 |
| 4 | 준비 중 FR 열기·닫기 | 문 조건만 바뀜 | "조수석 문이 열려 있어요" ↔ "조수석 문 닫힘" | `ready-door.txt` | 실행 확인 |
| 5 | 준비 중 기어 D·P | 주차 조건 바뀜 | "기어를 P(주차)로 바꿔 주세요" ↔ ✓. D에서도 앱 화면이 보였다 | `ready-gear.txt`, `r04` | 실행 확인 |
| 6 | [조용히 쉬기 시작] → 10초 | 어둡게 | 10.06초 뒤 어두워졌다. 창 `sbrt=0.02`, `KEEP_SCREEN_ON`. 화면에는 흐린 남은 시간만 남았다 | `r05`, `r06`, `rest-a-dim.txt` | 실행 확인 |
| 7 | 어두운 화면 터치 | 깨우기만 | 깨어났다. 조작은 실행되지 않았다. 확인 작업이 10초를 넘겨 다시 어두워진 상태에서 [휴식 끝내기] 자리를 누른 2회도 깨우기만 했다(의도한 동작) | `r07`, `rest-a-end.txt` | 실행 확인 |
| 8 | 깨운 뒤 1.1초 안에 [휴식 끝내기] | 직접 종료 | "휴식을 끝냈어요 / 휴식 끝내기를 눌렀어요. / 종료 시각 02:59". 창 밝기 기본값, `KEEP_SCREEN_ON` 해제 | `r08`, `rest-a-end.txt` | 실행 확인 |
| 9 | [다시 쉬기](픽업 03:25가 아직 안 지남) | 픽업 유지, 새 세션 | 03:25 유지, 22분. 이후 시작마다 세션 번호가 늘었다(1→7) | `r09`, `rest-again-1.txt`, logcat | 실행 확인 |
| 10 | 휴식 중 FR 열기 → 닫기 | 즉시 중단, 재시작 없음 | "조수석 문이 열려 휴식을 멈췄어요." 문을 닫아도 종료 화면 유지 | `r10`, `rest-b-door.txt` | 실행 확인 |
| 11 | 휴식 중 기어 D → P | 즉시 중단, 재시작 없음 | "기어가 P에서 바뀌어 휴식을 멈췄어요." P로 돌아와도 종료 화면 유지. 기어 변경(16ms)이 주행 제한(약 150ms)보다 먼저 와서 P 이탈로 기록됐다 | `r11`, `rest-c-gear.txt` | 실행 확인 |
| 12 | 휴식 중 Home 키 | — | Pleos 화면에서는 Home 키 뒤에도 이 앱이 맨 앞(onStop 없음)이었다. 이어서 연 FR 문으로 문 열림 종료가 됐다. 이탈 시험은 13번으로 했다 | `rest-d-home.txt` | 관찰 |
| 13 | 휴식 중(어두운 상태) 하단 바 내비게이션 아이콘 → 다른 앱 | 앱 이탈 중단, 창 복원, 백그라운드 조회 없음, 복귀 때 종료 화면 | onStop에서 `APP_LEFT` 종료, 같은 시점에 문 감시 정지(`CLOSED -> CHECKING (stopped)`). 창 `sbrt`·`KEEP_SCREEN_ON` 해제. 앱이 안 보이는 동안 FR 문을 열고 닫았지만 앱 로그가 없었다. 돌아오자 "앱 화면을 벗어나 휴식을 멈췄어요."가 보였고, 새 세대가 복귀 명령 약 0.36초 뒤 닫힘을 다시 확인했다 | `r13`, `r14`, `rest-d-leave.txt`, logcat | 실행 확인 |
| 14 | 픽업 03:10으로 줄여 시작(276.9초) → 기다림 | 정상 종료 | 마지막 60초에 "곧 끝나요"(어두운 상태·깨운 상태 모두). 03:08:00.157(기기 시각)에 `TIME_UP`. "휴식이 끝났어요 / 정한 휴식 시간이 끝났어요. 픽업 2분 전이에요. / 종료 시각 03:08". 창 밝기·화면 켜짐 복원 | `r15`~`r18`, `rest-e-normal.txt` | 실행 확인 |
| 15 | 픽업 03:10이 지난 03:10:05에 [다시 쉬기] | 새 기본값 | 03:35(03:10:05 + 20분을 5분 단위로 올림) | `r19`, `rest-again-2.txt` | 실행 확인 |
| 16 | [개발 진단] → [← 휴식 화면] | 보조 화면 | 기존 밝기 검증·지원 진단 탭이 열리고 돌아왔다. 진단 버튼은 누르지 않았다. 휴식 중 화면에는 [개발 진단]이 없다(6·8·13번 UI 덤프) | `r20`, `devtools.txt` | 실행 확인 |
| 17 | 휴식 중 앱 강제 종료(`am force-stop --user 10`) 후 실행 | 휴식을 재개하지 않음 | 준비 화면으로 시작했다(새 프로세스의 기본 픽업 03:35, 안내 다시 안 나옴). 복원 기록 없음 그대로 | `r21`, `rest-f-new-process.txt` | 실행 확인 |
| 18 | (PM 보충) FR 문을 먼저 열고 앱 강제 종료 후 새 프로세스 실행 | 닫힘 표시·시작 허용이 한 번도 없어야 함 | 새 프로세스의 첫 `RestDoor` 로그는 `CHECKING -> ERROR (capability timeout)`(실행 명령 약 6.4초 뒤)였다. 1초 뒤 재시도에서 `capability ok`, 이어서 `ERROR -> OPEN (event 1)`. `CLOSED`는 한 번도 없었다. 화면 샘플 3회 모두 "조수석 문이 열려 있어요"였다. 시작 버튼 자리를 눌러도 세션이 생기지 않았다. FR을 닫자 8ms 뒤 닫힘으로 바뀌었다 | `r22-*`, `r23`, `rest-g-open-first.txt`, logcat | 실행 확인 |

## 6. 반영 시간 실측

curl 직전 호스트 시각과 앱 로그(기기 시각 + 91ms 보정)의 차이다. 모두 구독·Car 콜백으로 반영됐다.

| 입력 | 반영까지 | 앱 로그 |
| --- | --- | --- |
| 준비 중 FR 열기 / 닫기 | 약 22ms / 13ms | `RestDoor CLOSED -> OPEN` / `OPEN -> CLOSED` |
| 준비 중 기어 D | 기어 약 15ms, 주행 제한 약 127ms | `기어=NOT_PARK`, `주행 제한=true` |
| 준비 중 기어 P | 기어 약 8ms, 주행 제한 해제 약 70ms | `기어=PARK`, `주행 제한=false` |
| 휴식 중 FR 열기 → 종료 | 약 17ms | `session 2 end: DOOR_OPENED` |
| 휴식 중 기어 D → 종료 | 약 16ms | `session 3 end: LEFT_PARK` |
| 새 프로세스 뒤 FR 닫기 | 약 8ms | `RestDoor OPEN -> CLOSED` |

- 2초 목표는 모든 측정에서 만족했다. 오차는 시계 비교의 왕복 시간(약 ±40ms) 정도다.
- 조회 간격 1초, 응답 상한 2초, 실패 뒤 재연결 1초. 구독 알림이 빠지면 주기 조회가 최대 약 1초 + 응답 시간 안에 잡는다. 이번 시험에서는 알림이 늘 먼저 와서, 주기 조회가 먼저 잡는 경로는 기기에서 관찰되지 않았다(단위 테스트로만 확인). 조회는 화면이 보이는 동안 1초에 약 1회였다(첫 프로세스에서 약 430초 동안 조회·이벤트 번호가 #421까지 증가).
- 콜드 스타트 때 문 확인: 실행 명령부터 첫 문 상태까지 약 4.7초, 6.5초, 7.4초(3회)가 걸렸다. 그동안은 확인 중이거나 오류로 표시되고 시작할 수 없다.

## 7. 관찰과 한계

- **콜드 스타트의 오류 문구**: 18번에서 새 프로세스의 첫 capability 응답이 2초 상한을 넘었다. 그래서 "조수석 문 신호에 문제가 있어요"가 약 1초 보인 뒤 재시도로 정상 확인됐다. onCreate 로그(기기 875.35) 직후 문 감시가 시작됐다면 타임아웃 예정은 약 877.4였는데 실제 처리는 880.20이어서, 메인 스레드가 첫 화면 준비로 바빴던 것으로 보인다(추정). 안전 쪽(닫힘 아님, 시작 불가)으로는 맞지만 사용자에게 오류가 잠깐 보인다. 바꾸지 않았다. 다음 지시에서 고칠지 정한다(예: 첫 capability만 상한을 늘리기).
- **Home 키**: 이 Pleos 화면에서는 Home 키가 앱을 화면에서 내리지 않았다(12번). 앱 이탈은 다른 앱을 연 13번으로 확인했다.
- **미확인**: 주행 제한 단독 변경(공개 카드 없음, 비공개 명령은 쓰지 않음), 시동 꺼짐(Suspend), 자정을 넘는 픽업의 화면 표시(시스템 시각을 바꾸지 않아 단위 테스트로만 확인), 구독 알림이 빠졌을 때 주기 조회가 먼저 잡는 경로(위 6장).
- **Activity만 다시 만들어지는 경우**: 저장하는 세션이 없으므로 종료 화면 대신 준비 화면이 보인다(휴식은 재개하지 않음). 이번 시험에서는 Activity가 프로세스와 별도로 다시 만들어진 경우가 없었다.
- 스크린샷에는 창 백라이트 밝기가 반영되지 않는다. 어둡게는 `dumpsys window`의 `sbrt=0.02`로 확인했다.
- 휴식 중 터치는 화면 아무 곳이나 활동으로 본다. 그래서 [휴식 끝내기]는 어두운 화면에서 한 번 깨운 뒤 10초 안에 눌러야 한다.
- 시험 중 왼쪽 홈 패널의 위젯 페이지가 음악 위젯에서 다른 페이지로 바뀌어 보였다. 원인 조작은 특정하지 못했다(Home 키 또는 하단 바 조작으로 추정). 차량 상태와는 무관하며 되돌리지 않았다.

## 8. 최종 환경

| 항목 | 시험 전 | 최종 |
| --- | --- | --- |
| 기어 | P | P |
| 문(콘솔) | 모두 닫힘 | 모두 닫힘 |
| 무드 조명(SDK, 앱 시작 때 읽기) | 스위치 OFF, 밝기 70 | 스위치 OFF, 밝기 70 |
| 콘솔 `ambient_light` | mode On, `#ffffff`, 70% | 같음 |
| 공조(콘솔) | 전원 off, 자동 off, A/C on, 듀얼 off, 풍량 3, 22.0/22.0°C | 같음 |
| 등받이(콘솔 recline) | 4좌석 0 | 같음 |
| 복원 기록 | 없음 | 없음 |
| 앱 설정 | 없음 | `comma_cabin_rest.xml`에 `onboarding_seen=true`만 |

앱은 준비 화면(휴식 아님)으로 열려 있다.

## 9. 원본 자료

`app/build/reports/rest-flow/`

| 경로 | 내용 |
| --- | --- |
| `step4-commit-push.txt` | STEP 4 커밋·푸시 확인 |
| `build/verify-*-stdout.txt`, `-stderr.txt`, `-exit.txt`, `build/test-xml/`, `build/lint-results-debug.*`, `build/test-1.txt`, `build/test-2.txt` | 테스트·린트·빌드(첫 실패 포함) |
| `build/merged-AndroidManifest.xml`, `build/apk-permissions.txt`, `build/apk-backup-attrs.txt` | 병합 매니페스트, APK 권한, 백업 속성 |
| `logs/logcat-app.txt` | 앱 로그(`RestFlow`, `RestDoor`, `AmbientProbe` 등, epoch 시각) |
| `logs/ui-*.txt`, `logs/rest-*.txt`, `logs/ready-*.txt`, `logs/devtools.txt` | 시나리오별 조작·UI 텍스트·창 상태 |
| `logs/latency.txt` | 반영 시간 계산 |
| `logs/vc-state-before.json`, `logs/vc-state-after.json`, `logs/final-compare.txt`, `logs/seat-before.txt`, `logs/install.txt`, `logs/prefs-after-onboarding.txt` | 시험 전후 상태 |
| `screens/r01`~`r23` | 화면 |
| `tools/rf.sh` | 시험 보조 스크립트(emulator-5554·`ro.kernel.qemu=1` 확인 뒤에만 조작) |
