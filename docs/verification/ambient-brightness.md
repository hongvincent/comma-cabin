# 앰비언트 밝기 적용·복원 검증 (STEP 3, STEP 3 보완)

- 날짜: 2026-11-12
- 목적: 앰비언트 밝기 한 영역을 낮췄다가 원래대로 돌리는 흐름(제품 요구사항 6-1, 9장)을 Pleos 에뮬레이터에서 실행으로 확인
- 결론: 정상 차량 설정 UI로 무드 조명을 켠 뒤 **실제 변경(100→80), 사용자 변경 감지(90), 앱 원복(100)을 실행 확인**했다. 시험 후 무드 조명 OFF·밝기 70으로 되돌렸다.
- 권한: 조회에는 `CAR_LIGHTS`, 설정에는 `CONTROL_CAR_LIGHTS`가 필요하다. 문서 표기(설정도 `CAR_LIGHTS`)와 다르다.
- STEP 3 1차 실행에서는 SDK 스위치가 OFF라 쓰기를 보류했다(아래 6장).
- STEP 3 보완 2에서 콜백 경계를 고친 뒤 다시 실행했다(아래 8장). 콘솔 브리지가 비활성일 때 `onComplete`를 받고도 값이 바뀌지 않았고, 이 요청은 브리지가 활성화된 뒤 늦게 반영됐다. 앱은 이를 결과 불명으로 남겼다가 원래값으로 복원했다.

## 1. 환경

| 항목 | 값 |
| --- | --- |
| 호스트 | macOS 26.5.2, arm64, JDK 17.0.19 |
| 빌드 | Gradle 8.10.2, AGP 8.8.2, Kotlin 2.2.21, Compose Compiler 플러그인 2.2.21, Compose BOM 2024.12.01, `ai.pleos.playground:Vehicle:2.3.2`. compileSdk 34, targetSdk 34, minSdk 28 |
| 호환 근거 | [Kotlin 호환표](https://kotlinlang.org/docs/gradle-configure-project.html): KGP 2.2.20–2.2.21 = Gradle 7.6.3–8.14, AGP 7.3.1–8.11.1 (기존 2.0.0은 Gradle 8.5·AGP 8.3.1까지). [Compose 설정](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler): Compose Compiler 플러그인은 "your Kotlin version"과 같은 버전 |
| 에뮬레이터 | Android Emulator 37.2.12, AVD `Pleos_Connect_2_1_0_probe`(cold boot, RAM 4096MB, GPU host, 2560×1440, 160dpi) |
| 시스템 이미지 | Pleos 공식 저장소 `system-images;android-34;pleos_car_emulator;arm64-v8a`("Pleos Connect v2.1.0 APIs", SHA-1 `24d28eb2f18eba5e87c861a7eb2bdccebb049419` 일치). 사용자가 42dot SDK 라이선스 동의를 확인한 뒤 설치 |
| 기기 확인 | serial `emulator-5554`, `ro.kernel.qemu=1`, API 34, AAOS 사용자 10 |
| Pleos Vehicle Service | `ai.pleos.playground.service.vehicle` 2.3.22 |
| 최종 권한 | `pleos.car.permission.CAR_LIGHTS`, `pleos.car.permission.CONTROL_CAR_LIGHTS`, `android.car.permission.CAR_POWERTRAIN` (모두 normal, `granted=true`) |

## 2. 시험 전 상태와 준비

| 항목 | 값 | 확인 방법 |
| --- | --- | --- |
| 무드 조명 | 끄기 | 차량 설정 > 라이트 > 무드 조명 화면 |
| SDK 스위치 | OFF | `getAmbientLightSwitch` |
| SDK 밝기 | 70 | `getAmbientLightBrightness`, 설정 화면 막대 약 70% |
| 기어 | P | `GEAR_SELECTION` |

1. 정상 차량 설정 앱(`ai.umos.vehiclecontrol`)의 **라이트 > 무드 조명 [켜기]**를 눌렀다(`adb -s emulator-5554 shell input tap 1893 730`). SDK 스위치가 **ON**으로 조회됐다.
2. 그 뒤 Virtual Console을 브라우저로 처음 열자, 앱도 콘솔 카드도 건드리지 않았는데 SDK 밝기가 **70 → 100**으로 바뀌었다. 공조 표시도 "꺼짐"에서 "22.0° A/C"로 바뀌었다. 콘솔이 활성화될 때(`availability.source: web-activation`) 자기 값을 차량에 발행한 것으로 본다. 공조는 이번 범위가 아니라 되돌리지 않았다.
3. 숨김 콘솔 항목 발행, 직접 VHAL 쓰기, 권한 우회는 하지 않았다. 기어 변경은 콘솔에 보이는 Gear 카드와 같은 엔드포인트(`/vehicle/gear`)로 했다.

## 3. 확인표

| # | 입력 | 기대 | 실제 | 판정 |
| --- | --- | --- | --- | --- |
| 1 | 권한 `CAR_LIGHTS`만, [밝기 낮추기 검증 시작] | 쓰기 결과 확인 | `onFailed(SecurityException: setAmbientLightBrightness need Permission)`, 재조회 100, 콘솔 100%. 기록 `RESULT_UNKNOWN` 유지 | 실행 확인(권한 부족) |
| 2 | `CONTROL_CAR_LIGHTS` 추가 후 재설치(`install -r`, 데이터 유지)·재실행 | 남은 기록 표시, 새 실행 차단 | `결과 불명` 기록 불러옴, 시작을 눌러도 실행 없음 | 실행 확인(기록이 남은 재실행) |
| 3 | [원래대로] | 지원 확인 → 기록 갱신 → 원복 → 재조회 | 지원 확인 READ_WRITE·0~100 → `onComplete(value=100)` → 재조회 100 → 기록 삭제(값이 이미 100이라 변화 증거는 아님) | 실행 확인 |
| 4 | [밝기 낮추기 검증 시작] | 100 → 80 | 기록 저장(`APPLY_REQUESTED`) → `onComplete(value=80)` → 재조회 80 → `APPLY_CONFIRMED`. 콘솔 80%, 설정 화면 막대 약 80% | 실행 확인 |
| 5 | 설정 화면 무드 조명 밝기 [>] (사용자 직접 변경) | 감지, 자동으로 덮어쓰지 않음 | 리스너 알림 90 → `USER_CHANGED`, 앱 쓰기 없음. 콘솔 90% | 실행 확인 |
| 6 | 콘솔 기어 Drive | 쓰기 차단 | 시스템이 앱 화면을 "주행 중에는 사용할 수 없습니다."로 막아 탭이 앱에 닿지 않음. 앱 상태 "P 아님, 주행 제한 있음, 쓰기 조건 미충족". 원복 요청 0건, 콘솔 90% 유지 | 실행 확인 |
| 7 | 기어 Park 복귀 | 자동 재실행 없음 | 상태만 갱신, 원복 요청 0건, 기록 `USER_CHANGED` 유지 | 실행 확인 |
| 8 | [원래대로] (P) | 90 → 100 | 지원 확인 → `onComplete(value=100)` → 재조회 100 → 기록 삭제. 콘솔 100% | 실행 확인 |
| 9 | 권한 `CONTROL_CAR_LIGHTS`만 | 조회 가능 여부 | `checkAmbientLightBrightnessCapabilities`가 `SecurityException(… need Permission)` | 실행 확인 → 두 권한 모두 필요 |
| 10 | 주행 제한만 따로 켜기 | 정상 UI·공식 문서 경로 탐색 | 콘솔 카탈로그에 주행 제한·주차 브레이크·Auto Hold 항목 없음. 문서의 `cmd hmg_car_service` 명령은 이 이미지에 없음(1차, exit 20, 반복하지 않음) | 미확인 |
| 11 | 정리: 설정 화면 밝기 [<] ×3, 무드 조명 [끄기] | 시험 전 상태 | 콘솔 90→80→70%. SDK 스위치 OFF, 밝기 70, 기록 없음. 설정 화면 [끄기]·약 70% | 실행 확인 |

관찰 구분: `onComplete`가 돌려주는 값은 **요청 값**이다(실제 조회값이 아님). 성공 판정은 정상 응답과 별도의 재조회로 했다. 리스너 알림과 Virtual Console 값은 대조용으로만 기록했다.

기어 D로 바꿀 때 `기어=NOT_PARK, 주행 제한=false`가 잠깐 먼저 기록됐다. 주행 제한이 풀린 채 P가 아닐 때도 앱은 쓰기 조건을 미충족으로 본다. 이것은 기어 변경 중의 관찰이며, 주행 제한 단독 재현은 아니다.

## 4. 실측값

| 시점 | 콜백 | 재조회(SDK) | 리스너 | Virtual Console |
| --- | --- | --- | --- | --- |
| 시험 전(콘솔 열기 전) | — | 70 | — | — |
| 콘솔 연 뒤, 변경 전 | — | 100 | — | 100% |
| 변경 후 | `onComplete(80)` | 80 | — | 80% |
| 사용자 변경 후 | — | — | 90 | 90% |
| 앱 원복 후 | `onComplete(100)` | 100 | — | 100% |
| 정리 후(최종) | — | 70, 스위치 OFF | — | 70% |

최종 남은 복원 기록: 없음.

## 5. 단위 테스트 (가짜 Light·가짜 저장소, 실제 SDK 아님)

`./gradlew :app:testDebugUnitTest`: `AmbientProbeTest` 26개, `FileRecoveryStoreTest` 6개, `ParkingTrackerTest` 4개, 모두 통과(보완 2 기준 36개). 실제 에뮬레이터로 재현하지 못한 경우는 단위 테스트로만 확인했다.
- 재실행 때 응답 없는 기록·이전 결과 불명 기록이 있으면 원복·기록 삭제·새 쓰기 모두 0회
- 조회 응답 없음 → 조회 타임아웃으로 끝냄 → 재시도 가능, 앞 조회의 늦은 응답은 무시
- 쓰기 뒤 재조회 응답 없음 → 결과 불명·기록 유지
- close 이후, 또는 더 새로운 관리자가 기록을 맡은 뒤 늦은 콜백이 기록·상태를 바꾸지 않음
- 응답 수신 저장 실패·기록 삭제 실패 → 저장 오류 상태, 이후 쓰기·삭제 0회
- 원복 전 지원 영역 없음·읽기 전용·범위 밖·지원 조회 실패·지원 조회 응답 없음·기록 저장 실패 → 원복 쓰기 0회
- 쓰기 타임아웃 → 원래값 조회 → 늦은 적용 응답(기록 유지, 작업 ID로 처리)
- 기록 디코더: 빈 작업 ID, 알 수 없는 영역·중복 좌석·단계, 문자열·소수 값, 음수, 목표 ≥ 원래, 형식이 다른 참/거짓·버전 → 읽을 수 없음으로 보존, 쓰기·삭제 차단
- (보완 2) 쓰기 타임아웃 진단 조회가 원복·다음 검증 뒤에 도착해도 화면·기록·쓰기 횟수 불변
- (보완 2) 해제된 이전 구독의 리스너 알림이 다음 작업을 사용자 변경으로 바꾸지 않음
- (보완 2) 응답 수신 표시가 없는 기록은 단계(결과 불명·적용 확인·사용자 변경·복원 실패·복원 보류)와 관계없이 잠김: 원복·삭제·새 쓰기 0회
- (보완 2) 주차 상태: 이전 연결의 늦은 이벤트 무시, 연결 해제 시 불명, close 뒤 이벤트·재등록 무시, 같은 연결 안 이전 시각 이벤트 무시

## 6. STEP 3 1차 실행 기록 (요약)

SDK `getAmbientLightSwitch`가 OFF여서 밝기를 쓰지 않았다. Virtual Console의 숨김 항목 `ambient_light.mode`는 On이었지만 차량 스위치와 다른 값이었다. 같은 실행에서 조회·스위치 OFF 차단·기어 Drive/Park 차단·재실행을 확인했고, 쓰기 요청은 0건이었다.

## 7. 원본 자료 위치 (Git에 넣지 않는 `app/build/reports/ambient-probe/`)

- `followup/logcat-s1-apply.txt` ~ `logcat-s8-final.txt`: 시나리오별 앱 로그(기기 버퍼 덤프. s7 전에 버퍼를 비움)
- `followup/ui-switch-on.txt`, `ui-user-change.txt`, `ui-restore-initial.txt`: 설정 화면 조작 명령과 종료 코드
- `followup/install.txt`, `assemble-*.txt`, `compile-kotlin-2.2.21.txt`, `test-run-1.txt`: 설치·빌드 출력
- `followup/AndroidManifest.both-lights.xml`: 권한 비교 때 되돌린 Manifest
- `followup/vc-state-after-open.json`, `vc-state-final.json`, `vc-catalog.json`: 콘솔 상태·카탈로그
- `followup/kotlin-gradle-configure-project.html`, `android-compose-setup.html`: 호환 근거 원문
- `screens/f01-*.png` ~ `f20-*.png`: 앱·설정 화면
- 1차 실행 자료: `logs/`, `screens/01~05`, `vc/`, `env/`
- 단위 테스트 결과 `app/build/test-results/testDebugUnitTest/`, 린트 결과 `app/build/reports/lint-results-debug.*`

## 8. STEP 3 보완 2 (콜백 경계·기록 잠금)

**코드 변경**
- 작업이 바뀔 때(새로고침·검증 시작·원복·쓰기 응답) 진행 중인 이전 조회를 무효화한다. 쓰기 타임아웃 진단 조회는 같은 작업이 아직 응답 대기일 때만 화면에 반영한다.
- 리스너는 구독 세대와 작업 ID를 함께 기억하고, 둘 다 같을 때만 반영한다.
- `responseReceived=false`인 기록은 단계 이름과 관계없이 잠근다.
- 주차 상태는 `ParkingTracker`가 연결 세대로 관리한다. close 뒤 콜백·재등록과 이전 연결의 늦은 이벤트를 무시하고, 연결 해제·불명은 쓰기 조건 미충족으로 둔다.

**실행(재부팅 후, serial emulator-5554)**
- 시험 전: SDK 스위치 OFF, 밝기 70, 기어 P, 기록 없음, 설정 화면 무드 조명 [끄기]·약 70%, 공조 양쪽 "꺼짐"(1차 실행 때 바뀐 22.0° A/C는 재부팅으로 OFF가 돼 있어 공조 조작은 하지 않음)
- 정상 UI로 무드 조명 [켜기] → SDK 스위치 ON

| 순서 | 콜백 | 재조회 | 콘솔·UI | 판정 |
| --- | --- | --- | --- | --- |
| 콘솔을 열기 전 변경 70 → 50 | `onComplete(50)` | 70 (8초 뒤에도 70) | 설정 막대 약 70% | 결과 불명, 기록 유지 |
| 콘솔을 처음 연 직후 | — | 50 | 콘솔 50% | 앞 요청이 늦게 반영됨(기록에는 원래 70) |
| [원래대로] 50 → 70 | `onComplete(70)` | 70 | 콘솔 70% | 복원 확인, 기록 삭제 |
| 변경 70 → 50 | `onComplete(50)` | 50 | 콘솔 50% | 적용 확인 |
| [원래대로] 50 → 70 | `onComplete(70)` | 70 | 콘솔 70% | 복원 확인, 기록 삭제. 원복 중 도착한 이전 작업의 리스너 알림 70은 "이전 구독 알림 무시"로 걸러짐 |

- 정리: 정상 UI로 무드 조명 [끄기]. 최종 SDK 스위치 OFF, 밝기 70, 기어 P, 주행 제한 없음, 기록 없음, 저장 오류 없음. 설정 화면 [끄기]·약 70%, 공조 양쪽 "꺼짐", 콘솔 70%
- 이번 실행에서는 콘솔을 연 뒤에도 공조가 바뀌지 않았다. 콘솔 활성화 때 무엇이 발행되는지는 실행마다 달라 범위를 확정하지 못했다.

**원본 자료**: `app/build/reports/ambient-probe/followup-02/` (로그 `logcat-r1`~`r3`, UI 명령 `ui-commands.txt`, 빌드·테스트 출력, `test-xml/`, 린트 결과, 콘솔 상태 json), 화면 `screens/g01`~`g13`.
