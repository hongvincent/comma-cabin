# Comma Cabin

현대자동차그룹 차량용 앱 개발 공모전을 위한 Android 프로젝트입니다.

`jeonyul` 브랜치에서 새로 개발합니다. 현재는 빈 화면을 실행하는 앱과 Gradle 빌드 설정만 있습니다.

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
