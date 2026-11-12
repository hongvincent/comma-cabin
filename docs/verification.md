# Pleos 에뮬레이터 검증

2026년 11월 12일, Pleos Connect Emulator 2.1.0(Android Automotive OS API 34, arm64-v8a, 2560 × 1440, 160 dpi)의 분할 화면에서 `app.commacabin` v0.2.0 release를 설치해 확인했습니다.

서명된 APK의 SHA-256은 `ec4355aad8de365d4280b30627aa53ae100541254af0da94c0be84cc1c5b18af`입니다.

## 실측 결과

| 경로 | 게스트 시스템 시각 | 앱 및 재생 상태 |
| --- | --- | --- |
| 19:30 픽업, 조수석 문 닫힘 | 종료 판단 **19:28:00.052** | `DEADLINE` · **픽업 준비할 시간이에요** · 재생 중 → 꺼짐 |
| 19:28 전에 오른쪽 앞문 열림 | SDK 이벤트 **19:26:54.874** | `PASSENGER_DOOR` · **조수석 문이 열렸어요** · 재생 중 → 꺼짐 |
| P 기어에서 D로 전환 | 종료 판단 **19:26:47.375** | `DRIVE_RESTRICTION` · 재생 중 → 꺼짐 |

문을 닫거나 기어를 다시 P에 놓았을 때 자동 재생되지 않았습니다. 문 열림 신호와 `MediaPlayer` 정지 상태가 로그에 기록된 시각의 차이는 **45ms**였습니다. 실차의 센서 입력부터 스피커 출력이 멈출 때까지 걸리는 시간은 별도 검증 범위입니다.

```text
11-12 19:26:50.721 23932 23932 I CommaCabin: started time=19:26:50.721 pickup=19:30 deadline=19:28:00.000 isPlaying=true
11-12 19:26:54.874 23932 23932 I CommaCabin: signals time=19:26:54.874 parked=true uxAllows=true door=OPEN
11-12 19:26:54.919 23932 23932 I CommaCabin: stopped time=19:26:54.874 reason=PASSENGER_DOOR deadline=19:28:00.000 wasPlaying=true isPlaying=false
11-12 19:26:45.397 13721 13721 I CommaCabin: started time=19:26:45.397 pickup=19:30 deadline=19:28:00.000 isPlaying=true
11-12 19:28:00.087 13721 13721 I CommaCabin: stopped time=19:28:00.052 reason=DEADLINE deadline=19:28:00.000 wasPlaying=true isPlaying=false
11-12 19:26:47.396 13721 13721 I CommaCabin: stopped time=19:26:47.375 reason=DRIVE_RESTRICTION deadline=19:28:00.000 wasPlaying=true isPlaying=false
```

[시작 전](images/pleos-ready-full.png) · [재생 중](images/pleos-playing-full.png) · [19:28 예정 종료](images/pleos-deadline-full.png) · [조수석 문 열림 종료](images/pleos-door-full.png)

## 터치 영역

160 dpi 분할 화면에서 UI Automator로 활성화된 **휴식 시작** 버튼의 경계 `[1342,1192][1688,1272]`를 확인했습니다. 너비 **346 px**, 높이 **80 px**이며 이 밀도에서 **346 × 80 dp**입니다. 시작·종료와 시각 변경 버튼의 구현상 최소 높이는 80 dp입니다.

## 같은 조건에서 실행하기

현재 에뮬레이터 시각보다 픽업을 4분 이상 뒤로 설정하면 두 종료 경로를 재현할 수 있습니다. 정확히 19:30과 19:28을 맞출 때는 에뮬레이터에서만 다음 명령을 실행합니다. 시각 설정 뒤에는 시간이 흐르도록 두고, P 기어와 조수석 문 닫힘을 확인한 뒤 **19:27:00 전에** 휴식을 시작합니다.

```sh
# 에뮬레이터가 하나만 연결된 상태에서 adb -e로 실행합니다.
adb -e shell settings put global auto_time 0
adb -e shell cmd alarm set-time "$(python3 -c 'from datetime import datetime; t = datetime.now().astimezone().tzinfo; print(int(datetime.now(t).replace(hour=19, minute=26, second=30, microsecond=0).timestamp() * 1000))')"
adb -e shell date
```

호스트와 에뮬레이터의 시간대를 같게 맞춥니다. 테스트 후 에뮬레이터의 자동 시간 설정을 복원합니다. Virtual Console을 열린 상태로 두고 오른쪽 앞문을 조작합니다.

시연 영상은 release APK의 에뮬레이터 화면 캡처를 편집하고, 빗소리는 앱과 같은 [녹음 음원](audio.md)으로 재현했습니다. 앱의 재생과 정지는 Android `MediaPlayer` 상태 로그로 확인했습니다.
