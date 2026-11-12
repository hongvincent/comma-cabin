package app.commacabin.probe

import java.util.UUID

/** Probe-only values. Product values are decided after execution checks (product-spec 6-3). */
const val RESPONSE_TIMEOUT_MS = 5_000L
const val READ_TIMEOUT_MS = 5_000L
private const val TARGET_STEP_DIVISOR = 5
private const val LOG_LIMIT = 300

enum class LightStatus(val label: String) { NOT_CHECKED("확인 전"), CONNECTED("응답함"), ERROR("오류·응답 없음") }

enum class RequestKind(val label: String) { APPLY("변경"), RESTORE("원복") }

data class PendingRequest(val jobId: String, val kind: RequestKind, val sentAtMs: Long, val timedOut: Boolean = false)

data class LogLine(val timeMs: Long, val step: String, val detail: String)

/**
 * Process-wide claim on the recovery record. Only the probe that most recently called start() may read
 * callbacks into its state or touch the record; an older or closed probe becomes inert.
 */
class RecordLease {
    private var current = 0
    fun acquire(): Int = ++current
    fun isHeld(id: Int): Boolean = id != 0 && id == current
}

data class ProbeUiState(
    val parking: ParkingState = ParkingState(),
    val light: LightStatus = LightStatus.NOT_CHECKED,
    val access: Access? = null,
    val area: AreaId? = null,
    val areaReason: String? = null,
    val bounds: IntRange? = null,
    val switch: SwitchState? = null,
    val current: Int? = null,
    val original: Int? = null,
    val target: Int? = null,
    val lastCallback: String? = null,
    val lastRead: Int? = null,
    val record: RecoveryRecord? = null,
    val recordProblem: String? = null,
    val storageFault: String? = null,
    val pending: PendingRequest? = null,
    val busy: Boolean = false,
    val evaluating: Boolean = false,
    val message: String = "",
    val manualGuide: String? = null,
    val log: List<LogLine> = emptyList(),
) {
    /** Set when the record holds a request whose result this run cannot learn. */
    val lockReason: String?
        get() = record?.let { r ->
            when {
                r.priorRequestUnresolved -> "이전 요청의 결과를 알 수 없는 기록이라 앱에서 원복·기록 삭제를 하지 않아요"
                r.awaitingResponse && pending == null ->
                    "응답을 받지 못한 요청(작업 ${r.jobId.take(8)})이 남아 있어 앱에서 원복·기록 삭제를 하지 않아요"
                else -> null
            }
        }

    private val idle: Boolean get() = !busy && !evaluating && pending == null
    private val writable: Boolean get() = idle && recordProblem == null && storageFault == null

    val canStart: Boolean get() = writable && record == null && parking.parked
    val canRestore: Boolean get() = writable && record != null && lockReason == null
    val canDiscard: Boolean get() = writable && record != null && lockReason == null
    val canRefresh: Boolean get() = idle
}

private sealed interface Response {
    data class Completed(val value: Int?) : Response
    data class Failed(val error: ProbeError) : Response

    fun text(): String = when (this) {
        is Completed -> "onComplete(value=$value)"
        is Failed -> "onFailed(${error.text()})"
    }
}

private sealed interface ReadOutcome<out T> {
    data class Ok<T>(val value: T) : ReadOutcome<T>
    data class Failed(val error: ProbeError) : ReadOutcome<Nothing>
    data object TimedOut : ReadOutcome<Nothing>

    fun text(): String = when (this) {
        is Ok -> "응답 $value"
        is Failed -> "오류 ${error.text()}"
        TimedOut -> "${READ_TIMEOUT_MS}ms 동안 응답 없음"
    }
}

/** Lowers by one fifth of the range, never below min. Null when already at min. */
internal fun lowerTarget(current: Int, bounds: IntRange): Int? {
    if (current <= bounds.first) return null
    val step = maxOf(1, (bounds.last - bounds.first) / TARGET_STEP_DIVISOR)
    return maxOf(bounds.first, current - step)
}

/**
 * Lowers one ambient-light area and restores it, following product-spec 6-1. Main-thread only.
 * Never writes without a user action. A vehicle-write timeout never ends the request; a read timeout
 * only ends that read. After close() or losing the lease, every late callback is ignored.
 */
class AmbientProbe(
    private val light: LightPort,
    private val store: RecoveryStore,
    private val scheduler: Scheduler,
    private val parking: () -> ParkingState,
    private val clock: () -> Long,
    private val lease: RecordLease = RecordLease(),
    private val log: (String) -> Unit = {},
    private val newJobId: () -> String = { UUID.randomUUID().toString() },
    /** Product lighting busy or holding records: no brightness write from here. */
    private val externalBlock: () -> String? = { null },
) {
    var state = ProbeUiState(parking = parking())
        private set
    var onState: (ProbeUiState) -> Unit = {}

    private var leaseId = 0
    private var closed = false
    private var writeTimeout: Cancellable? = null
    private var observer: AutoCloseable? = null
    private var observerGeneration = 0
    private var readSeq = 0
    private val activeReads = mutableMapOf<Int, Cancellable>()

    private val alive: Boolean get() = !closed && lease.isHeld(leaseId)

    private data class Snapshot(
        val access: Access,
        val area: AreaId,
        val bounds: IntRange,
        val switch: SwitchState?,
        val value: Int?,
    )

    fun start() {
        if (closed || leaseId != 0) return
        leaseId = lease.acquire()
        when (val loaded = store.load()) {
            LoadResult.Empty -> note("기록 확인", "남은 복원 기록 없음")
            is LoadResult.Found -> {
                val r = loaded.record
                update { copy(record = r, area = AreaId(r.area), original = r.originalValue, target = r.targetValue) }
                note("기록 불러옴", describe(r))
                state.lockReason?.let { note("잠금", "$it. 조회와 수동 조정 안내만 해요") }
                if (r.stage == Stage.APPLY_CONFIRMED && state.lockReason == null) observe(AreaId(r.area), r.jobId)
            }
            is LoadResult.Unreadable -> {
                update { copy(recordProblem = loaded.reason) }
                note("기록 읽기 실패", "${loaded.reason}. 기록을 지우지 않고 차량 쓰기·기록 삭제를 막아요")
            }
        }
        refreshGuide()
    }

    /** Ends this probe. A saved record, including one still awaiting a response, stays on disk untouched. */
    fun close() {
        if (closed) return
        closed = true
        writeTimeout?.cancel()
        writeTimeout = null
        activeReads.values.forEach { it.cancel() }
        activeReads.clear()
        stopObserving()
    }

    fun onParkingChanged(next: ParkingState) {
        if (!alive) return
        val was = state.parking
        if (was == next) return
        update { copy(parking = next) }
        note("차량 상태", "기어=${next.gear}, 주행 제한=${next.uxRestricted ?: "확인 안 됨"}")
        if (was.parked && !next.parked && (state.busy || state.pending != null || state.record != null)) {
            note("주차 조건 상실", "이후 차량 쓰기를 보류해요. 다시 주차해도 자동으로 실행하지 않아요")
        }
    }

    /** Reads only. */
    fun refresh() {
        if (!alive) return
        if (!state.canRefresh) return reject("다른 단계가 진행 중이거나 쓰기 응답을 기다리는 중이라 새로고침하지 않아요")
        invalidateReads()
        update { copy(busy = true) }
        readStatus("새로고침") { update { copy(busy = false) } }
    }

    fun startChange() {
        if (!alive) return
        writeBlock()?.let { return reject(it) }
        externalBlock()?.let { return reject("제품 휴식 무드등 작업 때문에 쓰지 않아요: $it") }
        if (state.record != null) return reject("남은 복원 기록이 있어 새로 바꾸지 않아요")
        if (!parking().parked) return reject("주차(P 기어 + 주행 제한 없음)가 확인되지 않아 시작하지 않아요")
        note("0 주차 확인", "P 기어, 주행 제한 없음")
        invalidateReads()
        update { copy(busy = true, original = null, target = null, lastCallback = null, lastRead = null) }
        readStatus("검증") { snap -> continueChange(snap) }
    }

    fun restore() {
        if (!alive) return
        writeBlock()?.let { return reject(it) }
        externalBlock()?.let { return reject("제품 휴식 무드등 작업 때문에 쓰지 않아요: $it") }
        val record = state.record ?: return reject("되돌릴 기록이 없어요")
        state.lockReason?.let { return reject(it) }
        invalidateReads()
        update { copy(busy = true) }
        read<BrightnessCapability>("원복 전 지원 확인", { ok, err -> light.checkBrightnessCapabilities(ok, err) }) { outcome ->
            update { copy(busy = false) }
            continueRestore(record.jobId, outcome)
        }
    }

    fun discardRecord() {
        if (!alive) return
        val reason = writeBlock() ?: when {
            state.record == null -> "지울 기록이 없어요"
            else -> state.lockReason
        }
        if (reason != null) return reject(reason)
        if (!deleteRecord("사용자 기록 지우기")) return reject("기록 파일을 지우지 못했어요")
        stopObserving()
        update { copy(record = null) }
        note("기록 지움", "사용자가 [기록 지우기]를 눌러 지웠어요")
        refreshGuide()
    }

    private fun writeBlock(): String? = when {
        state.busy || state.evaluating -> "다른 단계가 진행 중이에요"
        state.pending != null -> "작업 ${short(state.pending!!.jobId)}의 응답을 기다리는 중이라 차량에 쓰지 않아요"
        state.storageFault != null -> "기록 저장 오류가 있어 차량 쓰기·기록 삭제를 하지 않아요: ${state.storageFault}"
        state.recordProblem != null -> "기록을 읽을 수 없어 차량 쓰기·기록 삭제를 하지 않아요: ${state.recordProblem}"
        else -> null
    }

    /** Every read gets its own id and timeout. A response for an id that already ended is only logged. */
    private fun <T> read(label: String, call: (ok: (T) -> Unit, err: (ProbeError) -> Unit) -> Unit, done: (ReadOutcome<T>) -> Unit) {
        val id = ++readSeq
        activeReads[id] = scheduler.schedule(READ_TIMEOUT_MS) { finishRead(id, label, ReadOutcome.TimedOut, done) }
        call({ v -> finishRead(id, label, ReadOutcome.Ok(v), done) }, { e -> finishRead(id, label, ReadOutcome.Failed(e), done) })
    }

    private fun <T> finishRead(id: Int, label: String, outcome: ReadOutcome<T>, done: (ReadOutcome<T>) -> Unit) {
        if (!alive) return
        val timer = activeReads.remove(id)
        if (timer == null) {
            note("늦은 조회 응답 무시", "$label #$id: ${outcome.text()}")
            return
        }
        timer.cancel()
        note(label, outcome.text())
        done(outcome)
    }

    /** Called whenever the work changes: reads still in flight belong to earlier work, so their answers are only logged. */
    private fun invalidateReads() {
        if (activeReads.isEmpty()) return
        note("이전 조회 무효화", "조회 #${activeReads.keys.joinToString()}의 응답은 이제 상태·기록에 반영하지 않아요")
        activeReads.values.forEach { it.cancel() }
        activeReads.clear()
    }

    private fun readStatus(step: String, then: (Snapshot?) -> Unit) {
        read<BrightnessCapability>("$step 지원 확인", { ok, err -> light.checkBrightnessCapabilities(ok, err) }) { outcome ->
            if (outcome is ReadOutcome.Ok) {
                onCapability(step, outcome.value, then)
            } else {
                update { copy(light = LightStatus.ERROR) }
                then(null)
            }
        }
    }

    private fun onCapability(step: String, cap: BrightnessCapability, then: (Snapshot?) -> Unit) {
        val choice = chooseArea(cap.bounds)
        val bounds = choice?.let { cap.bounds.getValue(it.first) }
        update {
            copy(light = LightStatus.CONNECTED, access = cap.access, area = choice?.first, areaReason = choice?.second, bounds = bounds)
        }
        note("$step 지원 영역", cap.bounds.entries.joinToString { "${it.key.key}:${it.value}" }.ifEmpty { "없음" })
        if (choice == null || bounds == null) {
            then(null)
            return
        }
        val area = choice.first
        read<SwitchState?>("$step 스위치 조회", { ok, err -> light.getSwitch(area, ok, err) }) { sw ->
            val switch = (sw as? ReadOutcome.Ok)?.value
            update { copy(switch = switch) }
            read<Int?>("$step 밝기 조회", { ok, err -> light.getBrightness(area, ok, err) }) { b ->
                val value = (b as? ReadOutcome.Ok)?.value
                update { copy(current = value) }
                then(Snapshot(cap.access, area, bounds, switch, value))
            }
        }
    }

    private fun chooseArea(bounds: Map<AreaId, IntRange>): Pair<AreaId, String>? {
        if (bounds.isEmpty()) return null
        bounds.keys.firstOrNull { it.includesDriver }?.let { return it to "운전석(ROW_1_LEFT)을 포함하는 영역" }
        return bounds.keys.minBy { it.key } to "운전석 영역이 없어 이름순 첫 영역을 선택"
    }

    private fun continueChange(snap: Snapshot?) {
        val reason = when {
            snap == null -> "지원 확인에 실패했거나 응답이 없거나 지원 영역이 없어 쓰지 않아요"
            snap.access != Access.READ_WRITE -> "읽기·쓰기를 모두 지원하지 않아 쓰지 않아요(${snap.access})"
            snap.switch != SwitchState.ON -> "스위치 ON이 확인되지 않아 쓰지 않아요(${snap.switch ?: "확인 안 됨"})"
            snap.value == null -> "현재 밝기를 확인하지 못해 쓰지 않아요"
            snap.value !in snap.bounds -> "현재 밝기 ${snap.value}가 허용 범위 ${snap.bounds} 밖이라 쓰지 않아요"
            lowerTarget(snap.value, snap.bounds) == null -> "이미 최솟값(${snap.bounds.first})이라 더 낮출 수 없어요. 쓰지 않아요"
            else -> null
        }
        if (reason != null || snap == null || snap.value == null) {
            update { copy(busy = false) }
            return reject(reason ?: "쓰지 않아요")
        }
        writeApply(snap.area, snap.value, lowerTarget(snap.value, snap.bounds)!!)
    }

    private fun writeApply(area: AreaId, original: Int, target: Int) {
        update { copy(original = original, target = target) }
        if (!parking().parked) {
            update { copy(busy = false) }
            return reject("쓰기 직전 주차 조건이 사라져 보류해요. 기록을 만들지 않았고 차량에 쓰지 않았어요")
        }
        externalBlock()?.let {
            update { copy(busy = false) }
            return reject("쓰기 직전 제품 휴식 무드등 작업이 있어 기록을 만들지 않았고 차량에 쓰지 않았어요: $it")
        }
        val record = RecoveryRecord(
            jobId = newJobId(),
            area = area.key,
            originalValue = original,
            targetValue = target,
            stage = Stage.APPLY_REQUESTED,
            responseReceived = false,
        )
        if (!saveRecord(record, "변경 요청 기록 저장")) {
            update { copy(busy = false) }
            return reject("복원 기록을 저장하지 못해 차량에 쓰지 않았어요")
        }
        note("기록 저장", describe(record))
        send(record, RequestKind.APPLY, target)
    }

    private fun continueRestore(jobId: String, outcome: ReadOutcome<BrightnessCapability>) {
        val record = state.record
        if (record == null || record.jobId != jobId) return reject("원복 준비 중에 기록이 바뀌어 원복하지 않아요")
        val area = AreaId(record.area)
        val reason = when (outcome) {
            is ReadOutcome.Failed -> "현재 지원 범위를 확인하지 못해 원복하지 않아요"
            ReadOutcome.TimedOut -> "현재 지원 범위 조회에 응답이 없어 원복하지 않아요"
            is ReadOutcome.Ok -> {
                val bounds = outcome.value.bounds[area]
                when {
                    outcome.value.access != Access.READ_WRITE -> "읽기·쓰기를 모두 지원하지 않아 원복하지 않아요(${outcome.value.access})"
                    bounds == null -> "기록의 영역 ${record.area}을(를) 지금 지원하지 않아 원복하지 않아요"
                    record.originalValue !in bounds -> "원래 값 ${record.originalValue}이(가) 현재 허용 범위 $bounds 밖이라 원복하지 않아요"
                    else -> null
                }
            }
        }
        if (reason != null) return reject(reason)
        externalBlock()?.let { return reject("제품 휴식 무드등 작업이 있어 원복하지 않아요: $it") }
        if (!parking().parked) {
            persist(record.copy(stage = Stage.RESTORE_HELD))
            refreshGuide()
            return reject("주차 상태가 아니어서 원복을 보류해요. 차량에 쓰지 않았어요")
        }
        val next = record.copy(jobId = newJobId(), stage = Stage.RESTORE_REQUESTED, responseReceived = false, lastCallbackValue = null)
        if (!saveRecord(next, "원복 요청 기록 저장")) return reject("복원 기록을 갱신하지 못해 차량에 쓰지 않았어요")
        note("기록 갱신", describe(next))
        send(next, RequestKind.RESTORE, record.originalValue)
    }

    private fun send(record: RecoveryRecord, kind: RequestKind, value: Int) {
        val jobId = record.jobId
        update {
            copy(
                record = record,
                pending = PendingRequest(jobId, kind, clock()),
                busy = false,
                lastCallback = null,
                message = "${kind.label} 요청을 보냈어요. 응답을 기다려요",
            )
        }
        refreshGuide()
        writeTimeout = scheduler.schedule(RESPONSE_TIMEOUT_MS) { onWriteTimeout(jobId) }
        note("${kind.label} 요청", "작업 ${short(jobId)}: setAmbientLightBrightness(${record.area}, $value)")
        light.setBrightness(
            AreaId(record.area),
            value,
            onComplete = { v -> onWriteResponse(jobId, Response.Completed(v)) },
            onError = { e -> onWriteResponse(jobId, Response.Failed(e)) },
        )
    }

    private fun onWriteTimeout(jobId: String) {
        if (!alive) return
        val pending = state.pending ?: return
        val area = state.record?.area ?: return
        if (pending.jobId != jobId) return
        writeTimeout = null
        update { copy(pending = pending.copy(timedOut = true), message = "쓰기 응답 대기 시간이 지났어요. 요청을 끝난 것으로 보지 않고 결과를 기다려요") }
        note("쓰기 응답 없음", "작업 ${short(jobId)}: ${RESPONSE_TIMEOUT_MS}ms 동안 응답 없음. 기록 유지, 추가 쓰기 차단")
        read<Int?>("쓰기 대기 중 조회(표시만, 판정에 쓰지 않음)", { ok, err -> light.getBrightness(AreaId(area), ok, err) }) { outcome ->
            if (outcome is ReadOutcome.Ok && state.pending?.jobId == jobId) update { copy(lastRead = outcome.value) }
        }
        refreshGuide()
    }

    private fun onWriteResponse(jobId: String, response: Response) {
        if (!alive) return // Closed or superseded: the record stays exactly as saved on disk.
        val record = state.record
        val kind = when (record?.stage) {
            Stage.APPLY_REQUESTED -> RequestKind.APPLY
            Stage.RESTORE_REQUESTED -> RequestKind.RESTORE
            else -> null
        }
        if (record == null || record.jobId != jobId || kind == null || record.responseReceived) {
            note("쓰기 응답 무시", "현재 처리 중이 아닌 작업 ${short(jobId)}의 응답: ${response.text()}")
            return
        }
        val late = state.pending?.timedOut == true
        if (state.pending?.jobId == jobId) {
            writeTimeout?.cancel()
            writeTimeout = null
        }
        invalidateReads()
        val received = record.copy(responseReceived = true, lastCallbackValue = (response as? Response.Completed)?.value)
        saveRecord(received, "응답 수신 기록 저장")
        update { copy(record = received, pending = null, evaluating = true, lastCallback = response.text()) }
        note(if (late) "늦은 쓰기 응답(콜백)" else "쓰기 응답(콜백)", "작업 ${short(jobId)}: ${response.text()}. 콜백 값은 요청 값이며 실제 조회값이 아니에요")
        read<Int?>("재조회", { ok, err -> light.getBrightness(AreaId(record.area), ok, err) }) { outcome ->
            evaluate(kind, response, (outcome as? ReadOutcome.Ok)?.value)
        }
    }

    private fun evaluate(kind: RequestKind, response: Response, read: Int?) {
        val record = state.record ?: return
        update { copy(lastRead = read, evaluating = false) }
        note("판정 자료", "콜백 ${response.text()} / 재조회 ${read ?: "없음"}")
        when (kind) {
            RequestKind.APPLY -> if (response is Response.Completed && read == record.targetValue) {
                persist(record.copy(stage = Stage.APPLY_CONFIRMED, lastReadValue = read))
                message("적용 확인: 정상 응답 + 재조회 ${record.originalValue} → $read")
                if (state.storageFault == null) observe(AreaId(record.area), record.jobId)
            } else {
                // Neither an error nor a mismatch proves the value did not change.
                persist(record.copy(stage = Stage.RESULT_UNKNOWN, lastReadValue = read))
                message("결과 불명: 정상 응답과 재조회로 적용을 확인하지 못했어요. 기록을 남겨요")
            }
            RequestKind.RESTORE -> if (response is Response.Completed && read == record.originalValue) {
                if (deleteRecord("복원 확인 후 기록 삭제")) {
                    stopObserving()
                    update { copy(record = null) }
                    message("복원 확인: 정상 응답 + 재조회 $read. 기록을 지웠어요")
                } else {
                    update { copy(record = record.copy(lastReadValue = read)) }
                    message("원래 값 $read 을(를) 확인했지만 기록을 지우지 못했어요. 앱은 더 쓰지 않아요")
                }
            } else {
                persist(record.copy(stage = Stage.RESTORE_FAILED, lastReadValue = read))
                message("원복을 확인하지 못했어요. 기록을 남겨요")
            }
        }
        refreshGuide()
    }

    /** Each subscription remembers its generation and job; anything queued from an older one is ignored. */
    private fun observe(area: AreaId, jobId: String) {
        stopObserving()
        val generation = observerGeneration
        observer = light.observeBrightness(area) { v -> onObserved(generation, jobId, v) }
    }

    private fun stopObserving() {
        observerGeneration++
        try {
            observer?.close()
        } catch (e: Exception) {
            note("알림 해제 실패", e.javaClass.simpleName)
        }
        observer = null
    }

    private fun onObserved(generation: Int, jobId: String, value: Int?) {
        if (!alive) return
        val record = state.record
        if (record == null || generation != observerGeneration || record.jobId != jobId) {
            note("이전 구독 알림 무시", "작업 ${short(jobId)}의 알림 $value")
            return
        }
        note("밝기 알림(리스너)", "$value")
        if (state.pending != null || state.busy || state.evaluating || state.lockReason != null || record.stage != Stage.APPLY_CONFIRMED) return
        if (value != null && value != record.targetValue) {
            persist(record.copy(stage = Stage.USER_CHANGED, lastReadValue = value))
            stopObserving()
            message("밝기가 직접 바뀌었어요($value). 자동으로 되돌리지 않아요")
            refreshGuide()
        }
    }

    private fun persist(next: RecoveryRecord) {
        update { copy(record = next) }
        if (saveRecord(next, "${next.stage.label} 기록 저장")) note("기록 갱신", describe(next))
    }

    private fun saveRecord(next: RecoveryRecord, context: String): Boolean {
        if (state.storageFault != null) {
            note("기록 저장 건너뜀", "이미 저장 오류가 있어 '$context'을(를) 하지 않아요")
            return false
        }
        if (store.save(next)) return true
        fault(context)
        return false
    }

    private fun deleteRecord(context: String): Boolean {
        if (state.storageFault != null) {
            note("기록 삭제 건너뜀", "이미 저장 오류가 있어 '$context'을(를) 하지 않아요")
            return false
        }
        if (store.delete()) return true
        fault(context)
        return false
    }

    private fun fault(context: String) {
        update { copy(storageFault = "$context 실패") }
        note("기록 저장 오류", "$context 실패. 이후 차량 쓰기와 기록 삭제를 하지 않아요")
    }

    private fun refreshGuide() {
        val r = state.record
        val guide = when {
            state.recordProblem != null -> "기록을 읽을 수 없어요. 앱은 차량에 쓰거나 기록을 지우지 않아요. 차량의 앰비언트 밝기를 직접 확인해 주세요"
            r == null -> null
            state.lockReason != null || state.storageFault != null ->
                "앱은 이 기록으로 차량에 쓰거나 기록을 지우지 않아요. 앰비언트 밝기를 원래 값 ${r.originalValue}(으)로 직접 확인·조정해 주세요. " +
                    "에뮬레이터에서는 Virtual Console의 Ambient Brightness에서 바꿀 수 있어요"
            r.stage == Stage.APPLY_CONFIRMED -> null
            else -> "앰비언트 밝기를 원래 값 ${r.originalValue}(으)로 직접 맞추거나, 주차 상태에서 [원래대로]를 눌러 주세요"
        }
        update { copy(manualGuide = guide) }
    }

    private fun describe(r: RecoveryRecord): String =
        "작업 ${short(r.jobId)}, 영역 ${r.area}, 원래 ${r.originalValue}, 목표 ${r.targetValue}, 단계 ${r.stage.label}, " +
            "응답 ${if (r.responseReceived) "받음" else "없음"}" + if (r.priorRequestUnresolved) ", 이전 요청 결과 불명" else ""

    private fun short(jobId: String) = jobId.take(8)

    private fun message(text: String) {
        update { copy(message = text) }
        note("결과", text)
    }

    private fun reject(text: String) {
        update { copy(message = text) }
        note("중단", text)
    }

    private fun note(step: String, detail: String) {
        if (!alive) return
        val line = LogLine(clock(), step, detail)
        update { copy(log = (log + line).takeLast(LOG_LIMIT)) }
        log("$step | $detail")
    }

    private inline fun update(change: ProbeUiState.() -> ProbeUiState) {
        if (!alive) return
        state = state.change()
        onState(state)
    }
}
