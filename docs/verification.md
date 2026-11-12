# Pleos 에뮬레이터 검증

2026년 11월 12일, Pleos Connect Emulator 2.1.0(API 34, arm64-v8a, 2560 × 1440)에서 `app.commacabin` v0.1.0 release를 설치해 확인했습니다. 설치된 APK를 다시 읽어 제출용 APK와 바이트 단위로 일치함을 확인했습니다.

**APK SHA-256:** `c3c44409524b2719f56862f70b4a2bf2a39317a2924f2a147ad513653cd34c19`


## 실측 결과

| 경로 | 게스트 시스템 시각 | 앱 및 재생 상태 |
| --- | --- | --- |
| 19:30 픽업, 조수석 문 닫힘 | 종료 판단 **19:28:00.012** | `DEADLINE` · **픽업 준비 시간이에요** · 재생 중 → 꺼짐 |
| 19:28 전에 오른쪽 앞문 열림 | SDK 이벤트 **19:26:58.593** | `PASSENGER_DOOR` · **조수석 문이 열렸어요** · 재생 중 → 꺼짐 |
| P 기어에서 D로 전환 | 종료 판단 **19:26:47.548** | `DRIVE_RESTRICTION` · 재생 중 → 꺼짐 |

문을 닫거나 기어를 다시 P에 놓았을 때 자동 재생되지 않았습니다. Door 경로는 같은 이벤트 처리에서 플레이어를 멈췄고, 재생이 꺼졌다는 로그는 이벤트 약 43ms 뒤에 찍혔습니다. 이 간격은 에뮬레이터 앱 내부 로그 기준이며, 실차의 물리 동작이나 스피커 출력 지연을 측정한 값은 아닙니다.

```text
11-12 19:26:55.247  5771  5771 I CommaCabin: started time=19:26:55.246 pickup=19:30 deadline=19:28:00.000 isPlaying=true
11-12 19:26:58.593  5771  5771 I CommaCabin: signals time=19:26:58.593 parked=true uxAllows=true door=OPEN
11-12 19:26:58.636  5771  5771 I CommaCabin: stopped time=19:26:58.593 reason=PASSENGER_DOOR deadline=19:28:00.000 wasPlaying=true isPlaying=false
11-12 19:26:47.655  5771  5771 I CommaCabin: started time=19:26:47.655 pickup=19:30 deadline=19:28:00.000 isPlaying=true
11-12 19:28:00.375  5771  5771 I CommaCabin: stopped time=19:28:00.012 reason=DEADLINE deadline=19:28:00.000 wasPlaying=true isPlaying=false
11-12 19:26:47.736  5771  5771 I CommaCabin: stopped time=19:26:47.548 reason=DRIVE_RESTRICTION deadline=19:28:00.000 wasPlaying=true isPlaying=false
```

## 같은 조건에서 실행하기

가장 빠른 재현 방법은 앱에서 픽업 시각을 지금보다 4분 뒤로 설정하는 것입니다. 정확히 19:30/19:28을 재현하려면 에뮬레이터에서만 다음을 실행합니다. 시계 변경 후에는 시간이 흐르도록 두고, P 기어와 조수석 문 닫힘이 보일 때 **휴식 시작**을 누릅니다.

```sh
# 에뮬레이터가 하나만 연결된 상태에서 adb -e로 실행합니다.
adb -e shell settings put global auto_time 0
adb -e shell cmd alarm set-time "$(python3 -c 'from datetime import datetime; t = datetime.now().astimezone().tzinfo; print(int(datetime.now(t).replace(hour=19, minute=26, second=30, microsecond=0).timestamp() * 1000))')"
adb -e shell date
```

위 예시는 호스트와 에뮬레이터의 시간대가 같을 때 사용합니다. 테스트 후 에뮬레이터의 자동 시간 설정을 복원합니다. Virtual Console을 열린 상태로 두면 오른쪽 앞문 변경이 앱까지 전달됩니다.

시연 영상은 위 release APK의 에뮬레이터 화면 캡처를 편집했습니다. 빗소리는 앱에 포함된 같은 음원으로 재현했습니다. 앱의 재생과 정지는 Android MediaPlayer 상태를 기록해 확인했습니다.
