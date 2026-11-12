# Comma Cabin

현대자동차그룹 차량용 앱 개발 공모전을 위한 Android 프로젝트입니다.

`jeonyul` 브랜치에서 새로 개발합니다. 앱 첫 화면은 주차 중 조용히 쉬는 흐름입니다: 첫 실행 안내 → 휴식 준비(픽업 시각, 주차·조수석 문·시간 조건) → 휴식 중(남은 시간, 10초 뒤 화면 어둡게) → 종료(이유·시각) → 다시 쉬기. 이 단계는 중간 산출물입니다. 차량 상태는 읽기만 하고, 시트·조명·공조 적용과 복원, 휴식 소리, 음성 안내는 아직 연결하지 않았습니다(요구사항: docs/product-spec.md). 검증 결과는 docs/verification/rest-flow.md에 기록합니다.

앰비언트 밝기 적용·복원 검증(docs/verification/ambient-brightness.md)과 차량 지원 진단(docs/verification/vehicle-support.md) 화면은 debug 빌드의 [개발 진단] 보조 화면으로 옮겼습니다. 휴식 중에는 열 수 없습니다.

## 개발 환경

- JDK 17
- Android SDK Platform 34
- Gradle 8.10.2 (Wrapper 포함)
- Android Gradle Plugin 8.8.2

Android SDK 경로를 `ANDROID_HOME` 또는 `local.properties`의 `sdk.dir`로 설정한 뒤 빌드합니다.

```sh
./gradlew :app:assembleDebug
```

APK는 `app/build/outputs/apk/debug/app-debug.apk`에 생성됩니다.
