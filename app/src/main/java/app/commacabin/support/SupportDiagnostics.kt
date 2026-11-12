package app.commacabin.support

import app.commacabin.probe.Access
import app.commacabin.probe.Cancellable
import app.commacabin.probe.ProbeError
import app.commacabin.probe.Scheduler

const val SUPPORT_READ_TIMEOUT_MS = 5_000L
private const val EVENT_LIMIT = 200

enum class RowStatus(val label: String) {
    RUNNING("조회 중"), OK("응답"), UNSUPPORTED("미지원"), ERROR("오류"), TIMEOUT("응답 없음"), SKIPPED("건너뜀")
}

enum class RowKind(val label: String) { CAPABILITY("지원 확인"), CURRENT("현재값"), SUBSCRIPTION("구독") }

data class SupportRow(
    val feature: Feature,
    val kind: RowKind,
    val area: String?,
    val status: RowStatus,
    val detail: String,
    val atMs: Long,
)

data class SupportEvent(val feature: Feature, val area: String, val value: String?, val atMs: Long)

data class SupportUiState(
    val running: Boolean = false,
    val run: Int = 0,
    val rows: List<SupportRow> = emptyList(),
    val events: List<SupportEvent> = emptyList(),
    val observing: List<String> = emptyList(),
    /** The user stopped subscriptions; none are made again until the next run. */
    val observeStopped: Boolean = false,
    val message: String = "",
)

/**
 * Read-only support check. Starts only from a user action, gives every read its own id and timeout,
 * and ignores anything that arrives for an older run or after close().
 */
class SupportDiagnostics(
    private val port: VehicleReadPort,
    private val scheduler: Scheduler,
    private val clock: () -> Long,
    private val log: (String) -> Unit = {},
) {
    var state = SupportUiState()
        private set
    var onState: (SupportUiState) -> Unit = {}

    private var closed = false
    private var run = 0
    private var readSeq = 0
    private val activeReads = mutableMapOf<Int, Cancellable>()
    private val observers = mutableListOf<AutoCloseable>()
    /** Bumped on every start and stop, so callbacks of an earlier subscription are ignored. */
    private var observeGen = 0
    private var observeStopped = false

    private sealed interface Outcome<out T> {
        data class Ok<T>(val value: T) : Outcome<T>
        data class Failed(val error: ProbeError) : Outcome<Nothing>
        data object TimedOut : Outcome<Nothing>
    }

    /** Door, seat, HVAC and ambient color, in this order. A new run ends the previous one. */
    fun startAll() {
        if (closed) return
        closeObservers()
        observeStopped = false
        activeReads.values.forEach { it.cancel() }
        activeReads.clear()
        run++
        update {
            copy(running = true, run = this@SupportDiagnostics.run, rows = emptyList(), events = emptyList(), observing = emptyList(), observeStopped = false, message = "조회를 시작했어요")
        }
        log("run $run start")
        val thisRun = run
        val steps = listOf<(() -> Unit) -> Unit>(
            { next -> checkFeature(thisRun, Feature.DOOR_POSITION, { it == DOOR_TARGET }, observe = true, next) },
            { next -> checkFeature(thisRun, Feature.SEAT_BACKREST_POSITION, { it.split('+').contains(DRIVER_SEAT) }, observe = true, next) },
            { next -> checkFeature(thisRun, Feature.SEAT_BACKREST_MOVE, null, observe = false, next) },
            { next -> checkFeature(thisRun, Feature.SEAT_MEMORY_SUPPORT, null, observe = false, next) },
            { next -> checkFeature(thisRun, Feature.HVAC_POWER, { true }, observe = false, next) },
            { next -> checkFeature(thisRun, Feature.HVAC_AUTO, { true }, observe = false, next) },
            { next -> checkFeature(thisRun, Feature.HVAC_DUAL, { true }, observe = false, next) },
            { next -> checkFeature(thisRun, Feature.HVAC_FAN_SPEED, { true }, observe = false, next) },
            { next -> checkFeature(thisRun, Feature.HVAC_TEMPERATURE, { true }, observe = false, next) },
            { next -> checkFeature(thisRun, Feature.AMBIENT_COLOR, { true }, observe = false, next) },
        )
        runSteps(thisRun, steps, 0)
    }

    /** Stops subscriptions until the next [startAll]. Value reads already running still finish. */
    fun stopObserving() {
        if (closed) return
        observeStopped = true
        closeObservers()
        log("observe stopped")
        update { copy(observing = emptyList(), observeStopped = true) }
    }

    /** Ends the diagnostic without touching its last state; every later callback is ignored. */
    fun close() {
        if (closed) return
        closed = true
        closeObservers()
        activeReads.values.forEach { it.cancel() }
        activeReads.clear()
    }

    private fun closeObservers() {
        observeGen++
        observers.forEach { runCatching { it.close() } }
        observers.clear()
    }

    private fun runSteps(thisRun: Int, steps: List<(() -> Unit) -> Unit>, index: Int) {
        if (!current(thisRun)) return
        if (index == steps.size) {
            update { copy(running = false, message = "조회를 마쳤어요") }
            log("run $thisRun done")
            return
        }
        steps[index] { runSteps(thisRun, steps, index + 1) }
    }

    /** [readArea] picks the areas whose current value is read; null means capability only. */
    private fun checkFeature(thisRun: Int, feature: Feature, readArea: ((String) -> Boolean)?, observe: Boolean, next: () -> Unit) {
        val capRow = row(feature, RowKind.CAPABILITY, null, RowStatus.RUNNING, "")
        read<CapabilityResult>(thisRun, "${feature.name} capability", { ok, err -> port.capability(feature, ok, err) }) { outcome ->
            when (outcome) {
                is Outcome.Ok -> {
                    val cap = outcome.value
                    val unsupported = cap.access == Access.NONE || cap.areas.isEmpty()
                    replace(capRow, if (unsupported) RowStatus.UNSUPPORTED else RowStatus.OK, describe(cap))
                    val targets = if (readArea == null || unsupported) emptyList() else cap.areas.map { it.area }.filter(readArea)
                    val readable = cap.access == Access.READ || cap.access == Access.READ_WRITE
                    when {
                        readArea == null || unsupported -> Unit
                        targets.isEmpty() -> row(feature, RowKind.CURRENT, null, RowStatus.UNSUPPORTED, "대상 영역이 capability에 없음")
                        !readable -> row(feature, RowKind.CURRENT, null, RowStatus.SKIPPED, "읽기 접근이 없어 조회하지 않음(${cap.access})")
                    }
                    readValues(thisRun, feature, if (readable) targets else emptyList(), observe) { next() }
                }
                is Outcome.Failed -> {
                    replace(capRow, statusOf(outcome.error), outcome.error.text())
                    next()
                }
                Outcome.TimedOut -> {
                    replace(capRow, RowStatus.TIMEOUT, "${SUPPORT_READ_TIMEOUT_MS}ms 동안 응답 없음")
                    next()
                }
            }
        }
    }

    private fun readValues(thisRun: Int, feature: Feature, areas: List<String>, observe: Boolean, done: () -> Unit) {
        if (areas.isEmpty()) return done()
        val area = areas.first()
        val valueRow = row(feature, RowKind.CURRENT, area, RowStatus.RUNNING, "")
        read<String?>(thisRun, "${feature.name} current $area", { ok, err -> port.current(feature, area, ok, err) }) { outcome ->
            when (outcome) {
                is Outcome.Ok -> {
                    replace(valueRow, RowStatus.OK, outcome.value ?: "null(값 없음, 물리 상태로 보지 않음)")
                    if (observe) startObserving(thisRun, feature, area)
                }
                is Outcome.Failed -> replace(valueRow, statusOf(outcome.error), outcome.error.text())
                Outcome.TimedOut -> replace(valueRow, RowStatus.TIMEOUT, "${SUPPORT_READ_TIMEOUT_MS}ms 동안 응답 없음")
            }
            readValues(thisRun, feature, areas.drop(1), observe, done)
        }
    }

    private fun startObserving(thisRun: Int, feature: Feature, area: String) {
        if (observeStopped) {
            log("observe skipped (stopped) ${feature.name} $area")
            return
        }
        val gen = observeGen
        val closeable = try {
            port.observe(feature, area, { v -> onEvent(thisRun, gen, feature, area, v) }, { e -> onObserveError(thisRun, gen, feature, area, e) })
        } catch (e: RuntimeException) {
            val error = ProbeError(e.javaClass.name, e.message, null)
            log("observe ${feature.name} $area failed: ${error.text()}")
            row(feature, RowKind.SUBSCRIPTION, area, RowStatus.ERROR, "구독 등록 실패: ${error.text()}")
            return
        }
        if (closeable == null) {
            row(feature, RowKind.SUBSCRIPTION, area, RowStatus.UNSUPPORTED, "구독을 만들지 못함")
            return
        }
        observers += closeable
        update { copy(observing = observing + "${feature.label} $area") }
    }

    private fun subscribed(thisRun: Int, gen: Int) = current(thisRun) && gen == observeGen

    private fun onEvent(thisRun: Int, gen: Int, feature: Feature, area: String, value: String?) {
        if (!subscribed(thisRun, gen)) {
            log("ignored stale event run=$thisRun gen=$gen ${feature.name} $area $value")
            return
        }
        log("event ${feature.name} $area $value")
        update { copy(events = (events + SupportEvent(feature, area, value, clock())).takeLast(EVENT_LIMIT)) }
    }

    private fun onObserveError(thisRun: Int, gen: Int, feature: Feature, area: String, error: ProbeError) {
        if (!subscribed(thisRun, gen)) return
        log("event error ${feature.name} $area ${error.text()}")
        update { copy(events = (events + SupportEvent(feature, area, "구독 오류: ${error.text()}", clock())).takeLast(EVENT_LIMIT)) }
    }

    private fun <T> read(thisRun: Int, label: String, call: (ok: (T) -> Unit, err: (ProbeError) -> Unit) -> Unit, done: (Outcome<T>) -> Unit) {
        val id = ++readSeq
        activeReads[id] = scheduler.schedule(SUPPORT_READ_TIMEOUT_MS) { finish(thisRun, id, label, Outcome.TimedOut, done) }
        try {
            call({ v -> finish(thisRun, id, label, Outcome.Ok(v), done) }, { e -> finish(thisRun, id, label, Outcome.Failed(e), done) })
        } catch (e: RuntimeException) {
            finish(thisRun, id, label, Outcome.Failed(ProbeError(e.javaClass.name, e.message, null)), done)
        }
    }

    private fun <T> finish(thisRun: Int, id: Int, label: String, outcome: Outcome<T>, done: (Outcome<T>) -> Unit) {
        if (closed) return
        val timer = activeReads.remove(id)
        if (timer == null || !current(thisRun)) {
            log("ignored late read #$id run=$thisRun $label")
            return
        }
        timer.cancel()
        log("read #$id $label -> ${outcomeText(outcome)}")
        done(outcome)
    }

    private fun current(thisRun: Int) = !closed && thisRun == run

    private fun statusOf(error: ProbeError) =
        if (error.code?.startsWith("ERROR_NOT_SUPPORTED") == true) RowStatus.UNSUPPORTED else RowStatus.ERROR

    private fun describe(cap: CapabilityResult): String {
        val areas = cap.areas.joinToString { a -> if (a.min == null && a.max == null) a.area else "${a.area}:${a.min}..${a.max}" }
        return "접근 ${cap.access}, 영역 [${areas.ifEmpty { "없음" }}]" + (cap.extra?.let { ", $it" } ?: "")
    }

    private fun outcomeText(outcome: Outcome<*>) = when (outcome) {
        is Outcome.Ok -> "ok ${outcome.value}"
        is Outcome.Failed -> "error ${outcome.error.text()}"
        Outcome.TimedOut -> "timeout"
    }

    private fun row(feature: Feature, kind: RowKind, area: String?, status: RowStatus, detail: String): Int {
        update { copy(rows = rows + SupportRow(feature, kind, area, status, detail, clock())) }
        return state.rows.size - 1
    }

    private fun replace(index: Int, status: RowStatus, detail: String) {
        update { copy(rows = rows.toMutableList().also { it[index] = it[index].copy(status = status, detail = detail, atMs = clock()) }) }
    }

    private inline fun update(change: SupportUiState.() -> SupportUiState) {
        if (closed) return
        state = state.change()
        onState(state)
    }

    companion object {
        const val DOOR_TARGET = "ROW_1_RIGHT"
        const val DRIVER_SEAT = "ROW_1_LEFT"
    }
}
