# 시트 복원 기록 파일 형식 (STEP 26, 정리 계약 STEP 29)

- 상태: 구현·단위 검증만 했다. 앱에서 만들거나 쓰지 않는다(제품 미연결, 실제 Android 파일 쓰기 0). F11은 미충족이다.
- 코드: `app/src/main/java/app/commacabin/seat/SeatJournalFile.kt`(`SeatJournalCodec`, `FileSeatJournal`). 시트 코어는 [verification/vehicle-controls.md](verification/vehicle-controls.md) 4-1-3을 본다.

## 1. 파일과 경로

- 파일 이름: `rest-seat-recovery.json`(`FileSeatJournal.FILE_NAME`). 앞으로 앱의 `filesDir` 바로 아래 한 파일로 쓸 용도다.
- 작업 ID나 차량 값을 파일 이름·경로에 넣지 않는다.
- 기존 기록 파일(`vehicle-control-records.json`, `ambient-probe-recovery.json`, `rest-lighting-records.json`, `rest-climate-records.json`, `seat-preset-probe.json`)과 별개의 새 파일이다. 옮겨 오는 마이그레이션은 없다.
- 임시 파일: 같은 디렉터리의 `rest-seat-recovery.json.tmp`. 읽을 때는 쓰지도 지우지도 않는다.

## 2. 형식 (formatVersion 1)

UTF-8 JSON 객체 하나다. 모든 필드가 있어야 하고 모르는 필드는 허용하지 않는다. nullable 값은 `null`로 적는다.

```json
{"formatVersion":1,"snapshot":{
  "jobId":"job-1","purpose":"APPLY","axis":"BACKREST","original":20,"start":20,"target":60,
  "move":{"requestId":"move-1","state":"COMPLETED","answer":"onComplete(FORWARD)","timedOut":false,"afterMove":false},
  "stops":[{"requestId":"stop-1","state":"COMPLETED","answer":"onComplete(STOP)","timedOut":false,"afterMove":true}],
  "outcome":null,"held":null}}
```

| 필드 | 형식 |
| --- | --- |
| `formatVersion` | 정수 1. 다른 값은 지원하지 않는 버전으로 거부한다. |
| `jobId`, `requestId` | 비어 있지 않은 문자열. MOVE·STOP 요청 ID는 서로 겹치면 안 된다. |
| `purpose` | `APPLY` / `RESTORE` |
| `axis` | `FORE_AFT` / `BACKREST` / `HEIGHT` / `TILT` / `LUMBAR` |
| `original`, `start`, `target` | 32비트 정수 그대로. SDK 단위·범위(예: 0..100)는 형식에서 가정하지 않는다. |
| `state` | `SAVED` / `ENTERING` / `NOT_SENT` / `COMPLETED` / `FAILED` |
| `answer`, `held` | 문자열 또는 `null` |
| `timedOut`, `afterMove` | `true` / `false` |
| `stops` | 요청 객체 배열(보낸 순서) |
| `outcome` | `CONFIRMED` / `UNCONFIRMED` / `HELD` / `NOT_SENT` / `STOP_FAILED` 또는 `null` |

- enum은 현재 Kotlin 이름 그대로 쓴다. 이름을 바꾸면 형식 버전도 바꿔야 한다.
- 문자열 안의 제어 문자와 surrogate는 `\uXXXX`로 적는다.

## 3. 읽기 계약

- `Files.notExists`로 없다고 확정될 때만 Empty다. 존재 여부를 판정하지 못하면(`exists`와 `notExists`가 모두 false) Unreadable이다. `File.exists()`의 부정으로 판단하지 않는다.
- 존재 판정과 읽기 중의 `IOException`·`SecurityException`은 Unreadable로 돌려주고 포트 밖으로 던지지 않는다. 메모리 부족 같은 JVM 오류나 프로그래머 오류는 이 범위가 아니다.
- 아래의 파일 내용 문제도 Unreadable이다. 어느 경우든 파일은 그대로 둔다.
- Unreadable인 경우:
  - 읽기 상한 256 KiB(262144바이트) 초과. 실제로 읽은 바이트 수로 판정한다.
  - UTF-8이 아닌 내용. 빈 파일이나 공백만 있는 파일.
  - 깨진 JSON. 주석, 따옴표 없는 이름, 작은따옴표, 이름 중복, 뒤에 남은 내용, 너무 깊은 중첩(16단계 초과)도 포함한다.
  - 필드 누락이나 모르는 필드, 잘못된 타입.
  - 소수·지수·문자열·범위 밖 정수, 문자열로 된 Boolean.
  - 모르는 enum 값, 빈 ID, 중복 요청 ID, 지원하지 않는 버전.
- 기본값(0이나 빈 목록)으로 고쳐 읽지 않는다.
- Android 내장 org.json과 JVM 테스트용 org.json은 둘 다 관대하고 숫자 타입도 서로 다르다. 그래서 작은 엄격 리더를 직접 쓴다.
- 읽기와 생성자는 파일을 만들거나 바꾸지 않는다.

## 4. 형식 검증과 상태 검증의 경계

- 코덱은 구조만 본다. 응답이 없는 요청(SAVED·ENTERING), 타임아웃, `outcome: null`도 형식이 맞으면 정상 기록(Found)이다. Empty나 완료로 바꾸지 않는다.
- 완료 여부와 결과의 앞뒤 정합성은 시트 코어가 불러올 때 판단한다. 요청이 하나라도 끝나지 않았거나 결과와 맞지 않으면 코어가 잠근다. outcome만 보고 풀지 않는다.
- 정합성 판정(`stoppedConsistently`)은 코어·비상 STOP 관리자·정리가 함께 쓴다.
  - 모든 요청이 끝나야 한다.
  - `afterMove`가 MOVE와 맞아야 한다. MOVE의 SDK 종료 응답(COMPLETED/FAILED)이 있을 때만 STOP이 true일 수 있고, 한 번 true면 뒤도 true다. MOVE 자신은 `afterMove`가 false여야 한다.
  - RESTORE는 목표가 원래값이어야 한다.
  - 결과별 조건: NOT_SENT는 MOVE도 NOT_SENT. CONFIRMED·UNCONFIRMED는 MOVE 완료, MOVE 뒤 가장 최근 STOP 완료, held 없음. HELD는 MOVE 완료·실패, MOVE 뒤 가장 최근 STOP 완료, held 있음.
  - 이 판정에 맞지 않는 기록도 형식이 맞으면 Found로 읽는다. 이후 코어는 잠그고, 비상 STOP 관리자는 안전 STOP 대상으로 본다.

## 5. 쓰기 계약

- 순서: 같은 디렉터리 임시 파일 쓰기 → 파일 fsync → `ATOMIC_MOVE`로 교체 → 부모 디렉터리 fsync. 모두 끝난 뒤에만 `save = true`다.
- 원자적 교체를 지원하지 않으면 실패로 끝낸다. 비원자적 rename으로 바꾸지 않는다.
- 다음 경우에는 쓰지 않고 실패를 돌려준다.
  - 저장할 기록을 인코딩한 뒤 같은 디코더로 다시 읽었을 때 Unreadable이거나 원래와 다른 경우(빈 ID, 중복 요청 ID 등). 읽기와 같은 검증을 쓰고 규칙을 따로 두지 않는다.
  - 현재 파일이 Unreadable(지원하지 않는 버전 포함)
  - 다른 `jobId`·`axis`·`original`의 기록
  - 기존 요청 ID의 의미를 바꾸는 기록
    - 같은 MOVE ID: 목적·시작·목표는 그대로여야 한다. 기존 STOP 목록은 같은 순서와 같은 `afterMove`로 앞부분에 남아 있어야 한다.
    - 다른 MOVE ID(다음 시도): 이전 MOVE·STOP이 모두 끝났어야 하고, 이전 요청 ID를 하나도 다시 쓰지 않아야 한다.

저장소는 요청 상태 전이(예: 완료 → 대기)를 검사하지 않는다. 상태가 올바르게 바뀌는지는 시트 코어의 책임이다. 저장소는 형식, 대상 기록, 요청 ID의 의미만 지킨다.

- 실패하면 `false`를 돌려주고, `lastError`에 실패 단계와 예외 종류만 남긴다. 값이나 경로는 남기지 않는다.
- 실패가 파일이 바뀌지 않았다는 뜻은 아니다. 특히 교체 뒤 디렉터리 fsync가 실패하면 파일은 이미 새 내용일 수 있다.
- 실패나 손상을 이유로 기록을 지우거나 초기화하지 않는다. 삭제는 아래 6절의 정리로만 한다.
- 단일 owner를 전제로 한다. 한 프로세스의 한 인스턴스만 쓴다. 잠금·CAS·여러 프로세스 동시 쓰기는 지원하지 않는다.

## 6. 정리 계약 (STEP 29)

- 미래 owner가 명시적으로 부르는 `SeatMoveCore.clearRecord()`만 기록을 지운다.
  - 생성, 불러오기, 타임아웃, 현재값 조회, 화면 종료, 다음 시작에서는 지우지 않는다.
- **코어**: 이 실행에서 직접 끝낸 시도의 완료 증거가 있을 때만 정리를 요청한다. 파일에서 결과를 읽은 것만으로는 증거가 되지 않으므로, 재시작한 코어는 거부한다.
  - A: RESTORE가 CONFIRMED로 끝남. 목표가 원래값이고, 정합성 판정을 통과해야 한다.
  - B: 새 APPLY가 NOT_SENT로 끝남. 원래값이 시작값이고, 시작할 때 그 작업의 기록이 없었으며, 첫 저장부터 모든 저장이 성공해야 한다.
  - 증거는 정확한 snapshot이며 한 번만 쓴다. 다음 `begin`이 오면 사라진다. 메모리 기록이 증거와 다르면 거부한다.
  - 진행 중인 시도, 불러온 기록의 잠금, 저장 오류가 있으면 거부한다.
  - 사용한 요청 ID 목록은 지우지 않는다.
- **파일 계층(`FileSeatJournal.clear`)**: 디스크 기록이 기대 snapshot과 정확히 같고, 정리할 수 있는 형태일 때만 지운다.
  - 정리할 수 있는 형태(`removableShape`): 정합하면서 RESTORE·CONFIRMED·목표==원래값, 또는 APPLY·NOT_SENT·원래값==시작값.
  - "이 실행의 증거인지", "앞선 기록이 없었는지"는 파일 계층이 알 수 없고 새로 만들지도 않는다(코어 책임).
  - 파일 없음, Unreadable(지원하지 않는 버전 포함), 다른 기록, 정리할 수 없는 형태는 실패이고 원문을 보존한다.
- **삭제 경로**: 생성자에 받은 기록 파일 하나만 `Files.delete`로 지운 뒤 부모 디렉터리를 fsync한다. `.tmp`, 다른 파일, 폴더는 지우지 않고 재귀 삭제도 없다.
- **실패**
  - `false`와 함께 `lastError`에 단계(기록 파일 삭제 / 디렉터리 fsync)와 예외 종류만 남긴다.
  - 삭제 뒤 디렉터리 fsync가 실패하면 파일은 이미 없을 수 있다.
  - 코어는 정리 실패를 저장 오류로 고정해 이후 이동을 막고, 다시 시도하지 않는다.
- 단일 owner 전제는 그대로다. 여러 프로세스 사이의 CAS는 보장하지 않는다.

## 7. 이번에 하지 않은 것(후속 단계)

- 앱의 `filesDir` 연결과 lease·앱 전역 owner 연결.
- 정리를 부르는 화면·흐름, 자동 복원.
- 제품 연결.
