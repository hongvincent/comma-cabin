# 쉼표 캐빈 · Comma Cabin

**픽업을 기다리는 동안 차 안에서 잠깐 쉬고, 픽업을 준비할 시간을 남겨주는 Pleos 앱입니다.** 픽업을 19:30으로 설정하면 19:28에 빗소리가 꺼집니다. 조수석 문이 먼저 열리면 남은 시간과 관계없이 바로 종료합니다.

## 심사 실행 방법

- **설치 파일:** [apk/쉼표캐빈_쉼표캐빈_release.apk](apk/쉼표캐빈_쉼표캐빈_release.apk) · 서명된 release · `app.commacabin` v0.1.0
- **검증 환경:** Pleos Connect Emulator **2.1.0**, Android Automotive OS API 34, arm64-v8a. 디스플레이 **2560 × 1440**, 160 dpi. Vehicle SDK **2.3.2**.
- **필요한 설정:** Pleos Virtual Console을 열고 `Motion → Gear Selection → P`, `Convenience → Door Position → Front Right → Close`로 맞춥니다. 앱에 P와 닫힘이 표시된 뒤 시작합니다. 기기 시계 기준 픽업까지 3분 이상 남아 있어야 합니다.

```sh
adb install -r "apk/쉼표캐빈_쉼표캐빈_release.apk"
adb shell am start -n app.commacabin/.MainActivity
adb logcat -v threadtime -s CommaCabin:I CabinVehicle:I
```

| 검증 경로 | 실행 | 기대 동작 |
| --- | --- | --- |
| 픽업 2분 전 | 픽업 19:30. 차량 신호가 표시된 뒤 시계를 19:26:30 전후로 맞추고 **휴식 시작**. 조수석 문을 닫아 둡니다. | 19:28에 빗소리가 꺼지고 **픽업 준비 시간이에요**가 표시됩니다. |
| 조수석 문 먼저 열림 | 시계를 다시 맞춘 뒤 휴식을 시작합니다. 19:28 전에 Virtual Console에서 **오른쪽 앞문**을 엽니다. | 문 열림 신호를 받자마자 빗소리가 꺼지고 **조수석 문이 열렸어요**가 표시됩니다. 문을 닫아도 다시 재생되지 않습니다. |

문 상태가 계속 **확인 중**이면 Virtual Console을 열린 상태로 유지하고, 오른쪽 앞문을 한 번 열었다가 닫아 신호를 갱신해 주세요.

시계 변경은 에뮬레이터에서만 수행합니다. 시연과 검증에서는 게스트 시스템 시각을 시작 전에 맞춘 뒤 실제 시간이 지나도록 두었습니다. 현재 시각을 유지해 재현할 때는 **픽업 시각 변경**에서 지금보다 4분 뒤로 설정하면 됩니다. [검증 기록](docs/verification.md)에 두 종료 시각과 재생 상태가 있습니다.

## 차량 데이터와 오디오

| 신호 | 연동 및 동작 |
| --- | --- |
| 조수석 문 | Pleos Vehicle SDK `Door`, `ROW_1_RIGHT`. 읽기와 구독으로 문을 감지합니다. 상태를 확인할 수 없으면 휴식을 시작하지 않습니다. |
| 기어·주행 제한 | Android Automotive `GEAR_SELECTION` 및 `CarUxRestrictionsManager`. P와 화면 사용 가능 상태에서만 시작합니다. 주행 제한이 걸리거나 화면을 떠나면 끝냅니다. |
| 앰비언트 밝기 | 지원 시 Pleos Vehicle SDK `Light`로 운전석을 우선해 밝기 값을 읽어 휴식 중 앱 창 밝기에만 반영합니다. 지원되지 않으면 시스템 밝기를 유지합니다. |
| 빗소리 | 앱에 포함된 짧은 빗소리를 기기의 미디어 채널로 반복 재생합니다. 다른 오디오가 포커스를 가져가면 종료합니다. |

픽업 시각은 앱에서 직접 설정합니다. 이 버전은 차량 조명 제어, 서라운드 채널 제어, 내비게이션 및 실제 픽업 서비스 연동을 포함하지 않습니다. 에뮬레이터에서 검증했습니다. 계정이나 네트워크 연결이 필요한 앱 기능은 없습니다.

## 소스 빌드

JDK 17, Android SDK Platform 34, Android SDK Build Tools 35.0.0을 준비합니다. SDK의 `platforms/android-34/optional/android.car.jar`를 사용합니다. Android Studio에서 프로젝트를 열거나 `ANDROID_HOME`을 설정합니다. Gradle 8.10.2 Wrapper와 AGP 8.8.2를 사용합니다. Vehicle SDK는 Pleos Maven 저장소에서 빌드 시 내려받습니다.

```sh
./checks/run.sh
./gradlew :app:assembleRelease
```

빌드 결과는 `app/build/outputs/apk/release/app-release-unsigned.apk`에 생성됩니다. 새 release APK를 설치하려면 Android SDK의 `zipalign`, `apksigner`와 별도의 서명 키를 사용합니다. 제출용으로 제공된 APK는 이미 서명되어 있습니다.

- `RestRules.java`: 픽업 2분 전 종료, 최소 휴식 1분, 문 열림 및 차량 신호 규칙
- `VehicleSignals.java`: Pleos Vehicle SDK와 Android Automotive의 읽기 연동
- `MainActivity.java` / `CabinScreen.java`: 실제 재생·정지와 차량용 화면
- `checks/run.sh`: Android 없이 실행하는 종료 규칙 검사

[Pleos 개발 환경 설정](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator) · [Pleos Virtual Console](https://document.pleos.ai/docs/pleos-only/app-test-guide/virtual-console-guide)
