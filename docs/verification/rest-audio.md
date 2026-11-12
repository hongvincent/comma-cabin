# 휴식 소리와 정상 종료 안내 (STEP 6)

- 날짜: 2026-11-12
- 목적: 휴식 소리(켬/조용히, 크기 3단계), 부드러운 정상 종료(60초 감쇄), 안내음과 ON_DEVICE 음성 안내를 휴식 흐름에 연결하고 에뮬레이터에서 확인
- 범위: 중간 산출물이다. 차량 쓰기(시트·조명·공조)는 추가하지 않았고 요구사항에는 그대로 있다.
- 결론:
  - 휴식음 재생·크기·켬/조용히 전환·60초 감쇄·종료 시각 정지·종료 뒤 무음을 **실행 확인**했다. 안내음 → 한국어 음성(`onStart`/`onDone`) → 포커스 반납도 **실행 확인**했다. 문 열림·D 전환·앱 이탈 즉시 정지와 설정 기억도 확인했다.
  - 확인 근거는 SDK 이벤트, AudioFlinger 트랙(게인·VolumeShaper 감쇄), CarAudioFocus 기록이다.
  - **스피커로 실제 들리는지는 미확인**이다. 에뮬레이터 오디오 출력을 켜면 호스트 CoreAudio 초기화에서 멈춰, 출력이 꺼진(`-no-audio`) 상태로만 시험했다(1장). STEP 12의 WAV 출력 시도도 WAV 파일·신호를 얻지 못하고 중단했다(8장).

## 1. 환경과 장애·복구

| 항목 | 내용 |
| --- | --- |
| 이전 종료 원인 | STEP 5까지 쓰던 에뮬레이터는 이전 세션이 백그라운드 명령으로 18:29:41에 띄웠다. 20:29:41에 "background time limit"으로 중지됐다(도구 알림 `status killed`, 에뮬레이터 로그에 정상 종료 절차). 사용자 종료가 아니다(`app/build/reports/rest-audio/logs/emulator-exit-cause.txt`) |
| 복구 방식 | 같은 AVD `Pleos_Connect_2_1_0_probe`(RAM 4096MB, GPU host, AVD 설정 변경 없음). `-no-snapshot` 콜드 부트, `-wipe-data` 없음. Python `Popen(start_new_session=True, stdin=DEVNULL)`로 세션과 분리해 실행했다 |
| 오디오 출력 시도 | PM 지시로 `-no-audio`를 빼고 `QEMU_AUDIO_IN_DRV=none`(호스트 입력을 쓰지 않으려는 설정 의도, `-allow-host-audio` 없음)으로 다시 켰다. 이 환경 변수가 실제로 적용돼 입력이 꺼졌는지는 확인하지 못했다. STEP 12에서 같은 방식으로 실행했을 때도 CoreAudio 입력 초기화 오류 로그가 남았다(8장). 첫 실행(pid 77739)은 부팅 직후 QEMU의 CoreAudio 출력 초기화(`ca_init_voice_locked` → `AudioDeviceCreateIOProcID`)가 macOS 오디오 서버 응답을 기다리며 멈췄다. 15초 뒤 에뮬레이터 hang detector가 프로세스를 종료했다(`env/crash-summary.txt`). 같은 설정의 재시도(82439)와 그 뒤 `-no-audio` 실행(87559)은 이전 크래시의 보고 동의 대화상자에 걸린 정황이 있다. 같은 새 크래시로 단정하지 않는다. 두 프로세스는 PM 승인 범위에서 해당 PID만 SIGTERM 뒤 SIGKILL로 정리했다 |
| 최종 실행 | `emulator -avd Pleos_Connect_2_1_0_probe -no-snapshot -gpu host -no-audio -crash-report-mode disabled -no-metrics`(분리 실행, 13.8초 부팅). 이전 크래시 덤프·AVD 데이터·잠금 파일·호스트 오디오 설정은 지우거나 바꾸지 않았다 |
| 기기 | `emulator-5554`, `ro.kernel.qemu=1`(조작 명령마다 확인), 사용자 10. 앱은 `install -r`로 설치(데이터 유지) |
| Virtual Console | `adb forward tcp:8088 tcp:8088` 복구 뒤 기존 공개 콘솔 페이지를 브라우저로 한 번 열어 브리지를 활성화했다(`web-activation`) |

새 부팅 baseline과 콘솔 활성화의 영향(SDK 읽기 기준)은 다음과 같다.

| 항목 | 활성화 전 | 활성화 후 |
| --- | --- | --- |
| 무드 조명 | OFF, 70 | OFF, 70 |
| 공조 전원 | false | **true** (하단 바 "22.0° A/C") |
| 목표 온도 | `PropertyNotAvailableException` | 22.0 / 22.0 |
| 자동·듀얼·풍량 | false·false·3 | 같음 |
| 문·등받이·색 | 0·0·`0x00FFFFFF` | 같음 |
| 콘솔 JSON | 조명 On·`#00e5ff`·100%, 공조 power_on true, 브리지 false | 조명 On·`#ffffff`·70%, 공조 power_on **false**(SDK와 다름), 기어 Park |

## 2. 구현

| 파일 | 내용 |
| --- | --- |
| `audio/RestAudio.kt` | 휴식음·포커스·종료 시각 정지·정상 종료 안내(안내음 → 음성)·TTS 단일 소유 |
| `audio/AudioPorts.kt` | 재생기·포커스 포트, `Envelope`/`endEnvelope`, `TtsSlot` |
| `audio/RestSettings.kt` | 저장 값과 잘못된 값의 기본값 처리, 안내 버전 |
| `sdk/AndroidAudio.kt` | `AudioFocusRequest`(USAGE_MEDIA), 정적 `AudioTrack`(반복 지점) + `VolumeShaper` |
| `sdk/PleosTtsEngine.kt` | `ON_DEVICE`만. 이벤트 리스너 1개, `speak`·`stop`, 해제 전 리스너 제거. 이벤트를 받은 시점의 핸들러를 고정해 전달 |
| `rest/RestFlow.kt` | 소리 선택·크기·`AUDIO_LOST`·소리 실패, 오디오 준비 뒤 최종 재검사, `listenTo`(Activity가 그대로 사용) |
| `RestScreen.kt`, `SupportScreen.kt`, `MainActivity.kt` | 준비·휴식 중 소리 선택, 종료 안내 결과 문구, 안내 v2, 진단 [한 문장 말하기], onStop 정리 |
| `rest/DoorMonitor.kt` | 첫 지원 확인 상한 5초, 현재값 조회 2초·주기 1초 유지 |
| `res/raw/rest_loop.wav`, `res/raw/announce_chime.wav`, `tools/gen_rest_audio.py` | 직접 생성한 음원과 생성기 |

### 2-1. 음원

`tools/gen_rest_audio.py`가 파이썬 표준 라이브러리와 고정 seed로 만든다. 녹음·다운로드는 없다. 두 번 생성해 해시가 같은 것을 확인했다.

| 파일 | 형식 | 내용·점검 |
| --- | --- | --- |
| `rest_loop.wav` | 20초, 22050Hz, 모노, 16-bit PCM, 882KB | 브라운·핑크 노이즈(30Hz 고역 통과, 2.5kHz 저역 통과)에 10초 주기의 완만한 부풀기. 마지막 1초를 처음에 겹쳐 반복 지점을 이었다. 피크 −9dBFS, RMS −22.3dBFS. 반복 경계 샘플 차이 0.0185로, 일반 샘플 간 차이의 99.9 백분위(0.0238)보다 작다 |
| `announce_chime.wav` | 1.8초, 44100Hz, 모노, 16-bit PCM, 159KB | G5→E5 두 음의 부드러운 종소리(15ms 상승, 지수 감쇠). 0에서 시작해 0으로 끝난다. 피크 −9dBFS |

### 2-2. 계약

- **용도와 포커스**: 휴식음·안내음은 `USAGE_MEDIA`(Pleos 매트릭스 MUSIC)를 쓴다. 지연 포커스는 받지 않고, 요청은 동기적으로 허용 또는 거부로 끝난다. 요청 중 예외는 거부로 처리한다.
- **시작**:
  - 켬이면 `AUDIOFOCUS_GAIN`이 필요하다. 거부·준비 실패·재생 실패면 시작하지 않고 이유를 보여 준다.
  - 조용히는 휴식음용 포커스를 요청하지 않는다.
  - 오디오 준비 뒤 차량·시간을 다시 확인한다. 바뀌었으면 오디오를 정리하고 시작하지 않는다.
- **재생 직전 확인**: 새 재생과 재개 모두 같은 순서다.
  1. 그 순간의 단조 시각을 세션·종료 시각과 다시 비교하고, 늦었으면 재생 없이 해제한다.
  2. 음량(크기 × 덕)을 설정한다.
  3. 그 시각 기준의 종료 envelope를 설정한다.
  4. `play()`한다.
- **감쇄**: 네이티브 `VolumeShaper` 하나로 한다. 종료 60초 전까지 1이고, 종료 시각에 0이 되며, 끝값 0이 유지된다. 음량에는 시간 감쇄를 곱하지 않는다. 앱의 종료 시각 정지 타이머가 트랙을 따로 해제한다.
  - 해제 순서는 pause → stop → shaper close → release이고, 각 단계는 예외에 안전하다. `AudioTrack.setVolume` 반환값이 SUCCESS가 아니면 실패로 처리한다.
- **포커스 변화**:
  - LOSS_TRANSIENT는 일시정지한다. CAN_DUCK은 크기 × 0.3이다.
  - GAIN에서는 같은 세션, 종료 전 단조 시각, 주차·주행 제한 없음·문 닫힘일 때만 재개한다. 거부되면 계속 "일시정지"로 표시한다.
  - LOSS는 휴식을 즉시 중단한다(`AUDIO_LOST`).
- **켬↔조용히**:
  - 켬→조용히는 즉시 정지하고 포커스를 반납한다.
  - 조용히→켬에서 포커스·재생에 실패하면 조용히 그대로 두고 이유를 표시한다. 나중에 저절로 켜지지 않는다.
  - 재생 중 플레이어 오류가 나면 조용히로 바꾸고 이유를 표시한다. 저장된 선택은 실제로 적용된 선택일 때만 바뀐다.
- **정상 종료 안내** (정상 종료에서만):
  1. 휴식음을 정지한다.
  2. 포커스를 정한다. 켬이면 보유 포커스를 유지하고, 조용히이거나 포커스를 잠시 잃은 상태면 `GAIN_TRANSIENT_MAY_DUCK`을 요청한다.
  3. 안내음 1회를 재생한다.
  4. 포커스를 아직 가진 경우에만 음성 "픽업 2분 전이에요" 1회를 말한다.
  5. 포커스를 반납하고 음성 객체를 해제한다.
  - 대체 동작: 포커스 거부나 안내음 실패는 화면만, 음성 초기화·합성 오류·응답 없음(15초)은 안내음만이다.
  - 안내음 중 포커스를 잃으면 안내를 취소하고 화면만 쓴다. 음성 중 LOSS·LOSS_TRANSIENT이면 `stop()`한다.
  - 중단·직접 종료에는 소리가 없다.
- **세대와 취소**: 포커스 요청·재생기·음성 객체·안내 작업마다 번호를 둔다. 늦은 콜백은 무시한다. 새 휴식, onStop, onDestroy, 영구 상실은 진행 중인 안내까지 모두 정리한다. 백그라운드 재생·서비스·알람은 없다.
- **TTS 단일 소유**: Speech Service가 앱 단위로 콜백을 등록하므로, `TtsSlot`으로 진단과 제품 중 하나만 객체를 갖는다. 제품은 휴식마다 새 객체를 만들고, 한 번 말한 뒤 리스너 제거 후 해제한다. 진단 발화는 공개 이벤트에 발화 ID가 없으므로 한 문장이 끝나면(정지·상한·이탈 포함) 객체를 해제하고 다음은 초기화부터 다시 한다.
- **저장**: SharedPreferences `comma_cabin_rest`에 `onboarding_version`(2), `rest_sound`, `rest_level`만 저장한다. STEP 5의 `onboarding_seen`은 지운다. 백업 제외 규칙은 그대로다.

## 3. 단위 테스트와 빌드

| 실행 | 결과 |
| --- | --- |
| 본인: 최종 소스로 `./gradlew :app:testDebugUnitTest --console=plain`(실제 실행, `build/test-6.txt`) | 종료 코드 0. **127개**(기존 82 + 새 45), 실패 0, 오류 0, 건너뜀 0 |
| 본인: 최종 소스로 `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain` (`build/verify-final-*`) | 종료 코드 0. 입력이 같아 모든 작업이 UP-TO-DATE. 린트 오류 0, 경고 5 |
| Codex 독립 실행 `:app:testDebugUnitTest --rerun :app:lintDebug :app:assembleDebug` (`build/codex-review-gradle.txt`, 테스트 XML 21:09) | 종료 코드 0, 127개 실패 0·오류 0·건너뜀 0, 린트 오류 0·경고 5 |

새 테스트:
- `RestAudioTest` 28개:
  - 포커스: 거부, 조용히 시작, 일시정지·덕·재개 조건, 재개 거부 시 "재생 중" 아님, 영구 상실, 이전 요청 콜백 무시, 포커스 예외
  - 재생 시점: 재생 전 음량·envelope, 마지막 1분 재개 envelope, 종료 시각 정지와 이후 무재생, 준비 중 종료 시각이 지난 소리 해제
  - 실패: 재생 실패 해제, 음량 실패 → 흐름 통지
  - 안내: 켬/조용히 순서, 실패별 대체, 안내음 중 일시 상실 → 시간 경과 → GAIN에도 음성 없음, 음성 중 상실 → stop, 새 휴식·stopAll 취소, TTS 단일 소유
- `RestFlowTest` +7개: 오디오는 차량·시간 검사 뒤에만, 소리 전환 거부, 설정 기억, `AUDIO_LOST` 1회, 현재 세션 소리 실패만 반영, `listenTo` 연결, 준비 중 조건 변경 → 시작 취소·오디오 정리
- `TtsDiagnosticsTest` +5개: 끝 이벤트까지만 포커스 + 객체 해제, 슬롯 회수 시 stop, 포커스 예외, LOSS·이탈·상한 → stop·해제·늦은 이벤트 무시, 재시도 시 stop
- `RestSettingsTest` 4개, `DoorMonitorTest` +1개(첫 지원 확인 5초와 현재값 2초)

## 4. 에뮬레이터 결과

시간은 기기 epoch 초(로그) 또는 기기 시각이다. 차량 입력은 공개 FR 문 토글·Gear 카드와 같은 curl 요청만 썼다.

| # | 시나리오 | 결과 | 증거 |
| --- | --- | --- | --- |
| 1 | 진단 [한 문장 말하기](선행 단일 발화) | 앱 `GAIN_TRANSIENT_MAY_DUCK` 허용 → `speak` → `onReady`·`onStart` → 서비스가 AudioTrack(USAGE_MEDIA, 22050Hz)을 만들어 재생하고 seoyoon/ko_KR로 합성(약 1.4초 분량) → `onDone`(RMS 3회) → 앱 포커스 반납. 첫(콜드) 발화는 `onStart`부터 실제 음성 데이터 쓰기까지 약 3초가 걸렸다 | `logs/tts-probe-1-summary.txt`, `logs/tts-probe-1-logcat.txt` |
| 2 | TTS 포커스 주체 | 발화 중 CarAudioFocus의 "Current Focus Holders"는 앱(USAGE_MEDIA) 하나뿐이었다. 네 번의 발화 모두 "Focus Events"에 앱 요청·반납만 있었다. 서비스 포커스 요청과 발화 중 앱 포커스 변화는 없었다. `Receives Duck Events: false`. (첫 dumpsys 샘플은 zsh 변수 문제로 실패해 근거에서 뺐다) | `logs/tts-focus-owner-summary.txt`, `logs/car-audio-during-speak-2.txt` |
| 3 | 안내 v2 | 예전 무음 안내를 본 데이터(`onboarding_seen=true`)에서도 새 안내가 한 번 나왔다. 닫으면 `onboarding_version=2`, 조용히·보통 저장, 옛 키 삭제 | `screens/a01`, `logs/prefs-after-onboarding-v2.txt` |
| 4 | 휴식 소리 켬 시작 | 포커스 GAIN 허용 → 앱 정적 트랙(USAGE_MEDIA/MUSIC, 게인 −5.2dB = 0.55, VolumeShaper 적용, underrun 0), 음성 준비 완료 | `logs/s1-start.txt`, `logs/af-s1-playing.txt` |
| 5 | 휴식 중 크기·켬/조용히 | 크게 −1.4dB, 작게 −10dB, 보통 −5.2dB가 바로 반영됐다. 조용히: 트랙·포커스 없음. 다시 켬: 새 트랙과 앱 포커스 | `logs/s1-controls.txt` |
| 6 | **정상 종료(켬)** | 아래 6-1 | `logs/s1-fade.txt`, `logs/s1-end-logcat.txt`, `screens/a04` |
| 7 | 문 열림 즉시 정지 | 1초 주기 조회가 먼저 열림을 잡았다(`read 1`, curl 뒤 약 0.32초). 같은 순간 `DOOR_OPENED`, 휴식음 트랙 해제, 포커스 반납. 안내음·음성 없음. 문을 닫아도 재시작 없음 | `logs/s2-door.txt` |
| 8 | D 전환 즉시 정지 | 기어 변경 로그와 같은 시각에 `LEFT_PARK`, 8ms 안에 트랙 해제·포커스 반납. P 복귀에도 재시작·안내 없음 | `logs/s3-drive.txt` |
| 9 | 다른 앱 이탈 | 내비게이션으로 이동 → onStop에서 `APP_LEFT`, 휴식음·포커스·문 감시 정지. 돌아오면 종료 화면 | `logs/s4-leave.txt`, `screens/a07` |
| 10 | 설정 기억 + 문 열린 채 새 프로세스 | 휴식 소리 켬·작게가 새 프로세스에서도 선택돼 있었다. 첫 문 상태 전환은 `CHECKING -> OPEN`(지원 확인 약 1.9초, 오류 표시 없음). 닫힘 표시·시작 허용 없음, 시작 탭에도 세션 없음. 닫으면 닫힘 | `logs/s5-*`, `screens/a08`, `a09` |
| 11 | **정상 종료(조용히)** | 아래 6-2 | `logs/s6-*` |

### 6-1. 정상 종료(켬) 실측

종료 예정은 04:13:00.000(기기)이고, 크기는 보통(0.55)이었다.

| 시점 | 앱 휴식음 트랙 VolumeShaper 감쇄 | 이론값 |
| --- | --- | --- |
| 종료 59초 전 | −0.14dB | 59/60 → −0.15dB |
| 45초 전 | −2.5dB | 0.75 → −2.5dB |
| 30초 전 | −6dB | 0.5 → −6.0dB |
| 15초 전 | −12dB | 0.25 → −12.0dB |
| 5초 전 | −22dB | 0.083 → −21.6dB |
| 1초 전 | −36dB | 0.017 → −35.6dB |
| 종료 1초 뒤부터 | 휴식음 트랙 없음 | — |

- 04:13:00.000: 앱 정지 타이머가 휴식음을 해제했다(화면 tick과 무관).
- 00.065: `TIME_UP`. 00.068: 안내가 휴식 포커스를 그대로 유지했다(새 요청 없음).
- 00.174: 안내음 트랙.
- 02.181: `speak`. 02.213: `onStart`, 서비스 트랙 재생.
- 04.135: `onDone`. 04.147: 포커스 반납. 결과는 VOICE.
- 화면에는 "휴식이 끝났어요 / 안내음과 음성으로 알렸어요"가 보였고, 남은 포커스 보유자는 없었다.

### 6-2. 정상 종료(조용히) 실측

크기는 작게, 종료 예정은 04:23:00.000(기기)이었다. 시작할 때는 포커스 요청과 휴식음 트랙이 없었고, 음성만 준비했다.

- 04:23:00.000: 앱 정지 타이머(휴식음 없음).
- 00.033: `TIME_UP`. 00.046: 이때 처음 `GAIN_TRANSIENT_MAY_DUCK`을 요청했고 허용됐다(CarAudioFocus).
- 00.134: 안내음.
- 02.141: `speak`. 02.163: `onStart`.
- 04.028: `onDone`. 04.032: 포커스 반납. 결과는 VOICE.
- 화면에는 "안내음과 음성으로 알렸어요"가 보였고, 끝난 뒤 앱 오디오 트랙은 0개였다.

증거: `logs/s6-quiet-start.txt`, `logs/s6-quiet-end-logcat.txt`, `logs/s6-quiet-ended-ui.txt`, `screens/a10`

## 5. 확인하지 못한 것

- 스피커·이어폰으로 실제 들리는지(물리 청음): 출력이 꺼진 에뮬레이터라 확인하지 못했다(1장). 트랙 재생·감쇄·SDK 이벤트까지만 확인했다. STEP 12의 WAV 출력 시도에서도 출력 신호를 얻지 못했다(8장).
- 다른 앱과의 실제 포커스 경합(LOSS_TRANSIENT·CAN_DUCK·LOSS): 에뮬레이터에서 재현하지 못했다. 단위 테스트로만 확인했다. 이 환경은 앱에 duck 이벤트를 보내지 않았다.
- VolumeShaper 시간 기준이 일시정지 중에 흐르는지는 문서에 없다. 재개마다 그 시각 기준 envelope를 새로 설정하므로 결과는 같다. 공식 문서대로 출력 장치가 바뀌면 곡선 위치를 잃고 끝(0)으로 갈 수 있다(조용해지는 쪽).
- 주행 제한 단독 변경, 시동 꺼짐: 이전과 같이 미확인이다.

## 6. 최종 환경

콘솔 활성화로 켜진 공조 전원은 시험 뒤 **정상 차량 UI**로 껐다. 하단 바 공조 표시 → 공조 패널의 "ON"을 한 번 눌러 "OFF 공조 꺼짐"이 됐다(`screens/a13`, `a14`). 첫 시도는 패널이 자동으로 닫힌 뒤 눌러 왼쪽 주행 화면의 빈 곳에 닿았고(차량 변화 없음), 두 번째에 꺼졌다. SDK 쓰기 API나 숨김 콘솔 API는 쓰지 않았다.

| 항목 | 새 부팅 baseline(활성화 전) | 최종(SDK 실제 조회) |
| --- | --- | --- |
| 기어 | 콘솔 브리지 비활성 | P (콘솔 Park, 앱 조건 ✓) |
| 문 | 콘솔 모두 닫힘, SDK `ROW_1_RIGHT` 0 | 콘솔 모두 닫힘, SDK 0 |
| 무드 조명 | OFF, 70 | OFF, 70 |
| 공조 전원·자동·듀얼·풍량 | false·false·false·3 | false·false·false·3 |
| 목표 온도 | `PropertyNotAvailableException` | 22.0 / 22.0 (활성화 뒤부터 조회됨, 앱은 쓰지 않음) |
| 등받이·색 | 0, `0x00FFFFFF` | 0, `0x00FFFFFF` |
| 복원 기록 | 없음 | 없음 |
| 남은 오디오 포커스·앱 트랙 | 없음 | 없음(CarAudioFocus 보유자 없음, 앱 트랙 0) |
| 앱 설정 | `onboarding_seen=true`(STEP 5) | `onboarding_version=2`, `rest_sound=QUIET`, `rest_level=LOW`(시험에서 바꾼 마지막 선택) |

에뮬레이터는 `-no-audio -crash-report-mode disabled -no-metrics`로 세션과 분리돼 실행 중이다. 콘솔 페이지와 8088 포워딩도 열어 둔다. Playwright가 저장소 루트에 만든 `.playwright-mcp/`는 `app/build/reports/rest-audio/playwright/`로 옮겼다.

## 7. 원본 자료

`app/build/reports/rest-audio/`

| 경로 | 내용 |
| --- | --- |
| `env/` | 에뮬레이터 실행 기록·로그, 크래시 요약 |
| `logs/logcat-full.txt` | 시험 중 전체 logcat(epoch) |
| `logs/tts-*`, `logs/car-audio-*` | TTS 선행 시험, 포커스 주체 |
| `logs/s1-*` ~ `logs/s6-*` | 시나리오별 조작·트랙·포커스·화면 텍스트 |
| `logs/vc-state-*`, `logs/state-*`, `logs/sdk-*` | 콘솔·SDK 상태(부팅 baseline, 활성화 전후, 최종) |
| `build/` | 테스트·린트·빌드 출력, APK 권한·백업 속성 |
| `assets/gen-1.txt` | 음원 생성 점검값과 해시 |
| `docs/` | TTS·Pleos 오디오 포커스 공식 문서 사본, TTS 3.3.1 공개 API |
| `playwright/` | 콘솔 활성화 때 Playwright가 만든 파일 |
| `screens/` | 화면 |

## 8. STEP 12 오디오 출력 경로 재시도 (중단)

- 날짜: 2026-11-13
- 목적: `-no-audio` 대신 WAV 출력(시험 A)과 CoreAudio 출력만(시험 B)으로 실제 출력 신호를 확인하려 했다. 앱 코드와 SDK는 바꾸지 않았다.

**시험 A(WAV, 1회)**:
- 실행: 같은 AVD를 `adb emu kill`로 정상 종료한 뒤 원래 명령에서 `-no-audio`만 뺐다(`-no-snapshot -gpu host -crash-report-mode disabled -no-metrics`). 일회성 프로세스 환경으로 `QEMU_AUDIO_OUT_DRV=wav`, `QEMU_AUDIO_IN_DRV=none`, `QEMU_WAV_PATH=<새 WAV 경로>`를 줬다. `-allow-host-audio`는 쓰지 않았고, 전역 환경·AVD 설정에는 저장하지 않았다.
- 결과:
  - 부팅은 됐다(14.2초).
  - **WAV 파일은 끝까지 생기지 않았다.**
  - 에뮬레이터 stderr에 `coreaudio(input, ???): Could not resume the device`, `coreaudio(input, unknown property): Could not get device period size`가 모두 9줄 남았다. 이 설치본에서 `QEMU_AUDIO_OUT_DRV=wav`·`QEMU_AUDIO_IN_DRV=none`이 적용됐다는 증거는 없고, 입력 쪽 CoreAudio 초기화 흔적이 있다.
  - 부팅 직후 게스트 load average는 약 298이었다.
- 중단: 입력을 끈다는 전제를 입증하지 못했다. 그래서 Codex 지시로 음성·휴식 시험을 진행하지 않고 정상 종료했다. 이 실행 동안 수집한 앱 로그는 0줄이었다. 일부 게스트 조회는 시간 초과돼 상태 확인에 한계가 있었다.

**시험 B(CoreAudio 출력만)**: 같은 환경 변수 방식이라 안전 전제가 충족되지 않아 **실행하지 않았다**.

**복귀**:
- 원래 명령(`-no-audio`)으로 같은 AVD를 다시 시작했다(12.1초 부팅). 부팅 완료 직후 stderr는 0줄이었다. 이후 그래픽 경고(`[h264 …] no frame!` 4줄, `gldCopyBufferSubData: NEEDS IMPLEMENTATION` 1줄)가 남았고, CoreAudio 메시지는 없다. `adb forward tcp:8088 tcp:8088`을 다시 연결했다.
- SDK로 다시 읽어 보니 공조 전원만 true였다(Console은 false). 정상 공조 패널로 끄고 SDK로 false를 확인했다.
- 나머지는 SDK 재조회로 확인했다: P, 문 닫힘, 무드등 OFF·70·`0x00FFFFFF`, Auto·Dual false, 풍량 3, 22.0/22.0, 등받이 0.
- 기록 파일 3개는 비어 있고, 밝기 v1 파일은 없으며, 포커스 보유자도 없다. 설정(버전 5, QUIET, LOW, 22.0)과 userdata는 보존됐다. AVD `config.ini` 해시는 시험 전과 같다.

**호스트·마이크**:
- 호스트 출력 장치·볼륨·마이크 권한·전역 설정은 바꾸지 않았다. 앱에는 마이크 권한(RECORD_AUDIO)이 없다.
- 입력 초기화 오류 로그 외에, 호스트 입력이 실제로 녹음되거나 전달됐는지 판단할 증거는 없다. 그래서 단정하지 않는다.

**결론**:
- 출력 신호(WAV)와 스피커 청취는 계속 **미확인**이다. 사람의 청취 확인도 하지 않았다.
- 출력 경로를 다시 시험하려면, 입력을 확실히 끄는 방법(이 설치본에서 지원되는 공식 옵션)을 먼저 확인하는 결정이 필요하다.
- 원본: `app/build/reports/audio-output/`(`logs/a-emulator.err`, `logs/a*-*.txt`, `logs/r*-*.txt`, `logs/f1-final-baseline.txt`)
