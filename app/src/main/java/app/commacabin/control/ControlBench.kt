package app.commacabin.control

import app.commacabin.probe.Access
import app.commacabin.probe.Cancellable
import app.commacabin.probe.Dispatch
import app.commacabin.probe.ParkingState
import app.commacabin.probe.ProbeError
import app.commacabin.probe.RecordLease
import app.commacabin.probe.Scheduler
import app.commacabin.rest.DoorStatus
import app.commacabin.support.CapabilityResult
import java.util.UUID

const val CONTROL_READ_TIMEOUT_MS = 5_000L
const val CONTROL_WRITE_TIMEOUT_MS = 5_000L
/** Wait after a terminal callback before the confirming read. */
const val CONTROL_SETTLE_MS = 500L
/** Safety bound of one seat move while exploring or restoring; not a way to reach a position. */
const val SEAT_WATCHDOG_MS = 1_000L
/** Watch for movement after STOP before the confirming read. */
const val SEAT_SETTLE_MS = 1_000L
/** Direct position reads while the seat moves. */
const val SEAT_POLL_MS = 200L
/** Warm amber, fully opaque as the color setter documents ("Alpha is fixed to 0xFF"). */
const val WARM_COLOR = "0xFFFF9A40"
const val DRIVER = "ROW_1_LEFT"
private const val LOG_LIMIT = 300

data class PendingRequest(val jobId: String, val requestId: String, val label: String, val timedOut: Boolean = false)

data class ControlUiState(
    val records: List<ControlRecord> = emptyList(),
    val pending: Map<ControlProp, PendingRequest> = emptyMap(),
    val stopPending: PendingRequest? = null,
    val busy: Boolean = false,
    val seatMoving: Boolean = false,
    val loadProblem: String? = null,
    val storageFault: String? = null,
    val live: Map<String, String> = emptyMap(),
    val message: String = "",
    val log: List<String> = emptyList(),
)

/**
 * Debug-only STEP 7 verification of the remaining vehicle writes, following product-spec 6-1:
 * record before every command (no record = no command), one owner of the record file, re-read after the
 * terminal callback, a timeout never ends a request, and an unanswered request locks its property (also
 * across restarts). Only the user's buttons start a write. The seat STOP is the only command sent without
 * the pre-write checks. Main thread only.
 */
class ControlBench(
    private val port: ControlPort,
    private val store: ControlStore,
    private val scheduler: Scheduler,
    private val parking: () -> ParkingState,
    private val door: () -> DoorStatus,
    private val brightnessRecordPresent: () -> Boolean,
    private val clock: () -> Long,
    private val lease: RecordLease,
    private val log: (String) -> Unit = {},
    private val newId: () -> String = { UUID.randomUUID().toString() },
    /** Product lighting busy or holding records: no vehicle write from here (seat STOP excepted). */
    private val externalBlock: () -> String? = { null },
    /** Another seat tool's request not settled (the debug seat preset): no seat move from here, STOP excepted. */
    private val seatBlock: () -> String? = { null },
) {
    var state = ControlUiState()
        private set
    var onState: (ControlUiState) -> Unit = {}

    private var leaseId = 0
    private var closed = false
    /** Between onStop and onStart no new preparation or command starts; open requests are still handled. */
    /** True until the control screen is actually shown ([onVisible]); nothing new starts while hidden. */
    private var hidden = true
    private val alive: Boolean get() = !closed && lease.isHeld(leaseId)
    private var opSeq = 0
    /** A listener and what it proved: [registered] only once the registration call returned normally. */
    private class Watch {
        var handle: AutoCloseable? = null
        var registered = false
        /** The value a waiting command is based on; a listener value off it refuses that command at its gate. */
        var basis: String? = null
        var heard: String? = null
    }
    private val observers = mutableMapOf<ControlProp, Watch>()
    /** Counts STOP requests: a move still waiting in the SDK lane when one comes is refused at its gate. */
    private var stopSeq = 0
    /** A motion whose STOP failed: the lane keeps it reserved, and no new move starts until a STOP answers. */
    private var heldMotion: String? = null
    private val writeTimers = mutableMapOf<String, Cancellable>()
    private var seat: SeatRun? = null
    /** Jobs whose auto state was already restored once in this run; never repeated. */
    private val autoRestored = mutableSetOf<String>()

    /** One seat move (explore or restore) and its STOP. */
    private class SeatRun(val jobId: String, val restore: Boolean, val direction: Move, val original: Int, val start: Int, val startedAt: Long, val range: IntRange) {
        var distance = kotlin.math.abs(start - original)
        var poll: Cancellable? = null
        var moveId = ""
        var moveDone = false
        var moveResult: String? = null
        var stopId: String? = null
        var stopDone = false
        var stopResult: String? = null
        var stopReason: String? = null
        var stopAt = 0L
        var watchdog: Cancellable? = null
        var settling = false
        val updates = mutableListOf<String>()
    }

    // --- lifecycle ---

    fun start() {
        if (closed || leaseId != 0) return
        leaseId = lease.acquire()
        when (val loaded = store.load()) {
            ControlLoad.Empty -> note("기록 확인: 남은 제어 기록 없음")
            is ControlLoad.Found -> {
                update { copy(records = loaded.records) }
                loaded.records.forEach { note("기록 불러옴: ${describe(it)}") }
            }
            is ControlLoad.Unreadable -> {
                update { copy(loadProblem = loaded.reason) }
                note("기록 읽기 실패: ${loaded.reason}. 기록을 지우지 않고 쓰기·삭제를 막아요")
            }
        }
    }

    /**
     * The control screen is no longer shown (another tab, the rest screen, Back, or Activity onStop): a moving
     * seat is stopped, any preparation still waiting for reads is cancelled (its late replies are ignored and
     * send nothing), and no new work starts until [onVisible]. Requests already sent keep being handled by
     * their job and request ids.
     */
    fun onHidden() {
        if (closed) return
        if (!hidden) log("제어 검증 화면 숨김")
        hidden = true
        if (seat != null) stopSeat("화면 이탈")
        if (state.busy) {
            opSeq++
            note("화면 이탈: 준비 중이던 작업을 취소했어요(명령 보내지 않음)")
            update { copy(busy = false, message = "화면 이탈로 준비 중인 작업을 취소했어요") }
        }
    }

    /** The control screen is shown again. Nothing resumes by itself; new work starts only from a button. */
    fun onVisible() {
        if (closed || !hidden) return
        hidden = false
        log("제어 검증 화면 보임: 새 작업은 버튼으로만 시작")
    }

    /**
     * Activity end: STOP a moving seat (or a motion whose STOP failed), then ignore every later callback and show
     * nothing more. Records stay on disk as they are, except that a command that certainly never entered the SDK
     * still takes its own record back (see [unsent]).
     */
    fun close() {
        if (closed) return
        if (seat != null || heldMotion != null) stopSeat("화면 종료")
        closed = true
        writeTimers.values.forEach { it.cancel() }
        writeTimers.clear()
        seat?.watchdog?.cancel()
        observers.values.forEach { w -> w.handle?.let { runCatching { it.close() } } }
        observers.clear()
    }

    fun onParking(next: ParkingState) {
        if (seat != null && !next.parked) stopSeat("주차 조건 상실(기어=${next.gear}, 주행 제한=${next.uxRestricted})")
    }

    fun onDoor(next: DoorStatus) {
        if (seat != null && next != DoorStatus.CLOSED) stopSeat("조수석 문 $next")
    }

    // --- display ---

    /** Reads every property and shared state for display only; never writes. */
    fun refresh() {
        if (!alive || state.busy) return
        val token = begin("새로고침(조회만)") ?: return
        val values = mutableMapOf<String, String>()
        val props = ControlProp.entries.toList()
        fun sides(i: Int) {
            if (i == SideState.entries.size) {
                update { copy(live = live + values) }
                note("조회값: " + values.toSortedMap().entries.joinToString { "${it.key}=${it.value}" })
                return end(token, "조회를 마쳤어요")
            }
            val s = SideState.entries[i]
            ask<List<String>>(token, "${s.name} 영역", { ok, err -> port.sideAreas(s, { _, list -> ok(list) }, err) }) { areas ->
                val area = areas.firstOrNull { DRIVER in it.split('+') } ?: return@ask run { values[s.name] = "영역 없음"; sides(i + 1) }
                askOrNull(token, "${s.name} $area", { ok, err -> port.readSide(s, area, ok, err) }) { v ->
                    values["${s.name} $area"] = v ?: "null"
                    sides(i + 1)
                }
            }
        }
        fun step(i: Int) {
            if (i == props.size) return sides(0)
            val prop = props[i]
            ask(token, "${prop.label} 지원", { ok, err -> port.capability(prop, { c, m, inc -> ok(Triple(c, m, inc)) }, err) }) { (cap, _, _) ->
                val areas = cap.areas.map { it.area }.filter { prop != ControlProp.HVAC_TEMPERATURE && DRIVER in it.split('+') || prop == ControlProp.HVAC_TEMPERATURE }
                fun read(j: Int) {
                    if (j == areas.size) return step(i + 1)
                    askOrNull(token, "${prop.label} ${areas[j]}", { ok, err -> port.read(prop, areas[j], ok, err) }) { v ->
                        values["${prop.name} ${areas[j]}"] = v ?: "null"
                        read(j + 1)
                    }
                }
                read(0)
            }
        }
        step(0)
    }

    // --- value properties: color, fan, temperature ---

    /** One small change of [prop]: warm color, fan one step down, driver temperature one increment. */
    fun apply(prop: ControlProp) {
        require(prop != ControlProp.SEAT_BACKREST)
        if (!alive) return
        vehicleBlock()?.let { return reject(it) }
        recordOf(prop)?.let { return reject("${prop.label} 기록이 남아 있어 새로 바꾸지 않아요(${it.stage.label})") }
        hvacConflict(prop)?.let { return reject(it) }
        if (prop == ControlProp.AMBIENT_COLOR && brightnessRecordPresent()) return reject("밝기 검증 기록이 남아 있어 무드등 색을 바꾸지 않아요")
        if (!parking().parked) return reject("주차(P + 주행 제한 없음)가 확인되지 않아 쓰지 않아요")
        val token = begin("${prop.label} 적용 준비") ?: return
        capability(token, prop) { area, bounds, increment ->
            precondition(token, prop) { related ->
                ask<String?>(token, "${prop.label} 현재값", { ok, err -> port.read(prop, area, ok, err) }) { original ->
                    if (original == null || ControlValues.parse(prop, original) == null) return@ask end(token, "${prop.label} 현재값을 읽지 못해 쓰지 않아요($original)")
                    if (!inBounds(prop, original, bounds)) return@ask end(token, "${prop.label} 현재값 ${original}이 지원 범위 $bounds 밖이라 원래대로 되돌릴 수 없어 쓰지 않아요")
                    val target = targetFor(prop, original, bounds, increment) ?: return@ask end(token, "${prop.label}: 목표값을 정할 수 없어 쓰지 않아요(현재 $original, 범위 $bounds, 간격 $increment)")
                    if (!inBounds(prop, target, bounds)) return@ask end(token, "${prop.label} 목표값 ${target}이 지원 범위 $bounds 밖이라 쓰지 않아요")
                    relatedValues(token, prop, area, related) { all ->
                        ControlValues.relatedProblem(prop, all)?.let { return@relatedValues end(token, "${prop.label}: $it. 쓰지 않아요") }
                        if (!parking().parked) return@relatedValues end(token, "명령 직전 주차 조건이 없어 쓰지 않아요")
                        if (recordOf(prop) != null || state.pending.containsKey(prop)) return@relatedValues end(token, "같은 속성에 진행 중인 작업이 있어 쓰지 않아요")
                        val record = ControlRecord(newId(), prop, area, original, target, all, ControlStage.APPLY_REQUESTED, newId(), responseReceived = false)
                        if (!persist(state.records + record)) return@relatedValues end(token, "기록을 저장하지 못해 ${prop.label}을 바꾸지 않았어요(요청 안 함)")
                        note("기록 저장 후 적용 요청(SDK 차례 대기): ${describe(record)}")
                        // Registered before the command (the lane keeps the order): a change while it waits refuses it.
                        observe(prop, area, basis = original)
                        sendWrite(record, target, token) { unsent(record, token, "명령 직전 조건이 바뀌어") }
                    }
                }
            }
        }
    }

    /** Explicit [원래대로]: original value back, only after every response of this record arrived. */
    fun restore(prop: ControlProp) {
        require(prop != ControlProp.SEAT_BACKREST)
        if (!alive) return
        vehicleBlock()?.let { return reject(it) }
        val r = recordOf(prop) ?: return reject("${prop.label}: 되돌릴 기록이 없어요")
        lockReason(r)?.let { return reject(it) }
        if (state.pending.containsKey(prop)) return reject("${prop.label}: 응답을 기다리는 요청이 있어요")
        hvacConflict(prop)?.let { return reject(it) }
        if (!parking().parked) return reject("주차(P + 주행 제한 없음)가 확인되지 않아 복원하지 않아요(복원 보류)")
        val token = begin("${prop.label} 복원 준비") ?: return
        capability(token, prop) { area, bounds, _ ->
            if (area != r.area) return@capability end(token, "기록 영역 ${r.area}이 지원 영역($area)과 달라 쓰지 않아요")
            if (!inBounds(prop, r.original, bounds)) return@capability end(token, "원래값 ${r.original}이 지원 범위 $bounds 밖이라 쓰지 않아요")
            precondition(token, prop) { _ ->
                val current = recordOf(prop)
                if (current?.jobId != r.jobId || state.pending.containsKey(prop) || !parking().parked) return@precondition end(token, "명령 직전 조건이 바뀌어 복원하지 않아요")
                val next = current.copy(stage = ControlStage.RESTORE_REQUESTED, requestId = newId(), responseReceived = false, lastEcho = null)
                if (!persist(replace(next))) return@precondition end(token, "기록을 갱신하지 못해 복원 명령을 보내지 않았어요")
                note("기록 갱신 후 복원 요청(SDK 차례 대기): ${describe(next)}")
                observe(prop, area)
                sendWrite(next, next.original, token) {
                    if (state.records.firstOrNull { it.jobId == next.jobId }?.requestId == next.requestId) persist(replace(current), afterClose = true)
                    end(token, "명령 직전 조건이 바뀌어 복원 명령을 보내지 않았어요(기록은 이전 단계로)")
                }
            }
        }
    }

    /** Explicit [기록 지우기]; refused while a request is open or the record is locked. */
    fun discard(prop: ControlProp) {
        if (!alive) return
        writeBlock()?.let { return reject(it) }
        val r = recordOf(prop) ?: return reject("${prop.label}: 지울 기록이 없어요")
        lockReason(r)?.let { return reject(it) }
        if (state.pending.containsKey(prop) || (prop == ControlProp.SEAT_BACKREST && seat != null)) return reject("${prop.label}: 진행 중인 요청이 있어요")
        if (!persist(state.records - r)) return reject("기록을 지우지 못했어요")
        closeObserver(prop)
        note("사용자가 기록을 지움: ${describe(r)}")
        update { copy(message = "${prop.label} 기록을 지웠어요") }
    }

    // --- seat ---

    /** Direction probe: move once, STOP at the first position change or after [SEAT_WATCHDOG_MS]. */
    fun exploreSeat(direction: Move) {
        require(direction != Move.STOP)
        if (!alive) return
        vehicleBlock()?.let { return reject(it) }
        if (seat != null) return reject("등받이가 이미 움직이는 중이에요")
        recordOf(ControlProp.SEAT_BACKREST)?.let { return reject("등받이 기록이 남아 있어 새로 움직이지 않아요(${it.stage.label})") }
        seatSafe()?.let { return reject(it) }
        val token = begin("등받이 탐색 준비") ?: return
        capability(token, ControlProp.SEAT_BACKREST) { area, bounds, _ ->
            val range = seatRange(bounds) ?: return@capability end(token, "등받이 위치 범위($bounds)를 해석할 수 없어 움직이지 않아요")
            ask<String?>(token, "등받이 현재 위치", { ok, err -> port.read(ControlProp.SEAT_BACKREST, area, ok, err) }) { text ->
                val position = text?.let { ControlValues.parse(ControlProp.SEAT_BACKREST, it) as Int? } ?: return@ask end(token, "등받이 위치를 읽지 못해 움직이지 않아요($text)")
                if (position !in range) return@ask end(token, "등받이 위치 ${position}이 범위 $range 밖이라 움직이지 않아요")
                // Registered before the move (the lane keeps the order); the move's gate needs the registration confirmed.
                if (!observe(ControlProp.SEAT_BACKREST, area, basis = position.toString())) return@ask end(token, "위치 리스너를 등록하지 못해 움직이지 않아요")
                seatSafe()?.let { return@ask end(token, it) }
                val record = ControlRecord(newId(), ControlProp.SEAT_BACKREST, area, position.toString(), direction.name, emptyMap(), ControlStage.APPLY_REQUESTED, newId(), responseReceived = false)
                if (!persist(state.records + record)) return@ask end(token, "기록을 저장하지 못해 등받이를 움직이지 않았어요(요청 안 함)")
                note("기록 저장 후 등받이 ${direction.name}(SDK 차례 대기): ${describe(record)}")
                val run = SeatRun(record.jobId, restore = false, direction = direction, original = position, start = position, startedAt = clock(), range = range)
                sendMove(run, record.requestId, token, "등받이 ${direction.name} 요청을 보냈어요") { unsent(record, token, "명령 직전 안전 조건이 바뀌어") }
            }
        }
    }

    /** Moves back toward the recorded position in the direction the probe showed, STOP on reaching it. */
    fun restoreSeat() {
        if (!alive) return
        vehicleBlock()?.let { return reject(it) }
        if (seat != null) return reject("등받이가 움직이는 중이에요")
        val r = recordOf(ControlProp.SEAT_BACKREST) ?: return reject("등받이: 되돌릴 기록이 없어요")
        lockReason(r)?.let { return reject(it) }
        seatSafe()?.let { return reject(it) }
        val original = r.original.toInt()
        val explored = r.lastRead?.toIntOrNull()
        val probe = Move.valueOf(r.target)
        if (explored == null || explored == original) return reject("탐색에서 위치 변화가 확인되지 않아 복원 방향을 정할 수 없어요")
        val token = begin("등받이 복원 준비") ?: return
        capability(token, ControlProp.SEAT_BACKREST) { area, bounds, _ ->
            if (area != r.area || !inBounds(ControlProp.SEAT_BACKREST, r.original, bounds)) return@capability end(token, "기록 영역·원래 위치가 지원 범위와 맞지 않아 움직이지 않아요")
            ask<String?>(token, "등받이 현재 위치", { ok, err -> port.read(ControlProp.SEAT_BACKREST, area, ok, err) }) { text ->
                val now = text?.toIntOrNull() ?: return@ask end(token, "등받이 위치를 읽지 못해 움직이지 않아요($text)")
                if (now == original) return@ask end(token, "이미 원래 위치($original)라 움직이지 않아요. 기록은 [기록 지우기]로만 지워요")
                // The probe moved `probe` toward `explored`; go the other way when the target lies on the other side.
                val direction = if ((original - now > 0) == (explored - original > 0)) probe else opposite(probe)
                seatSafe()?.let { return@ask end(token, it) }
                val current = recordOf(ControlProp.SEAT_BACKREST)
                if (current?.jobId != r.jobId) return@ask end(token, "기록이 바뀌어 움직이지 않아요")
                val range = seatRange(bounds) ?: return@ask end(token, "등받이 위치 범위를 해석할 수 없어 움직이지 않아요")
                if (now !in range) return@ask end(token, "등받이 위치 ${now}이 범위 $range 밖이라 움직이지 않아요")
                if (!observe(ControlProp.SEAT_BACKREST, area, basis = now.toString())) return@ask end(token, "위치 리스너를 등록하지 못해 움직이지 않아요")
                val next = current.copy(stage = ControlStage.RESTORE_REQUESTED, requestId = newId(), responseReceived = false, lastEcho = null)
                if (!persist(replace(next))) return@ask end(token, "기록을 갱신하지 못해 등받이를 움직이지 않았어요")
                note("기록 갱신 후 등받이 복원 ${direction.name}(SDK 차례 대기): ${now} → $original")
                val run = SeatRun(r.jobId, restore = true, direction = direction, original = original, start = now, startedAt = clock(), range = range)
                sendMove(run, next.requestId, token, "등받이 복원 요청을 보냈어요") {
                    if (state.records.firstOrNull { it.jobId == next.jobId }?.requestId == next.requestId) persist(replace(current), afterClose = true)
                    end(token, "명령 직전 안전 조건이 바뀌어 움직이지 않았어요(기록은 이전 단계로)")
                }
            }
        }
    }

    /**
     * STOP at once, without the pre-write checks (product-spec 6-1 exception). It goes before everything waiting
     * in the SDK lane, and a move still waiting there is refused at its gate. While an earlier STOP has not
     * answered, no second one is queued (it would get no answer); that is said, not dropped silently.
     */
    fun stopSeat(reason: String) {
        if (closed) return
        if (state.stopPending != null) return note("등받이 STOP($reason): 먼저 보낸 STOP이 아직 응답 전이라 그 응답을 기다려요")
        stopSeq++
        // The motion this STOP answers for: the move that entered (or last failed to stop).
        val key = seat?.moveId?.takeIf { it.isNotEmpty() } ?: heldMotion
        val run = seat?.takeIf { it.stopId == null }
        run?.watchdog?.cancel()
        run?.poll?.cancel()
        val id = newId()
        val label = "등받이 STOP($reason)"
        run?.let {
            it.stopId = id
            it.stopReason = reason
            it.stopAt = clock()
        }
        note("$label 보냄")
        update { copy(stopPending = PendingRequest(run?.jobId ?: "-", id, label)) }
        writeTimers[id] = scheduler.schedule(CONTROL_WRITE_TIMEOUT_MS) {
            if (state.stopPending?.requestId == id) {
                note("$label: ${CONTROL_WRITE_TIMEOUT_MS}ms 동안 응답 없음(결과 불명, 위치 리스너로 확인)")
                update { copy(stopPending = stopPending?.copy(timedOut = true)) }
            }
        }
        port.move(DRIVER, Move.STOP, key ?: "", Dispatch.NONE, { echo -> onStopDone(id, key, "onComplete($echo)") }, { e -> onStopDone(id, key, "onFailed(${e.text()})") })
    }

    // --- writes and callbacks ---

    /**
     * [record] is saved; the write waits in the SDK lane while the preparation [token] stays open. Right before the
     * SDK entry, on main: [commandGate]. The timeout starts then and the preparation ends. [notSent]: it never
     * entered; this request's tracking is undone first, the caller handles its record.
     */
    private fun sendWrite(record: ControlRecord, value: String, token: Int, notSent: () -> Unit) {
        val reqId = record.requestId
        val label = if (record.stage == ControlStage.RESTORE_REQUESTED) "복원" else "적용"
        val dispatch = Dispatch(
            gate = { commandGate(token, record.prop, record.jobId, reqId) },
            onStart = {
                observers[record.prop]?.basis = null
                update { copy(pending = pending + (record.prop to PendingRequest(record.jobId, reqId, label))) }
                writeTimers[reqId] = scheduler.schedule(CONTROL_WRITE_TIMEOUT_MS) { onWriteTimeout(record.prop, reqId) }
                note("${record.prop.label} $label 요청 #${reqId.take(8)} payload=$value")
                end(token, "${record.prop.label} $label 요청을 보냈어요")
            },
            onNotSent = { why ->
                observers[record.prop]?.basis = null
                writeTimers.remove(reqId)?.cancel()
                if (state.pending[record.prop]?.requestId == reqId) update { copy(pending = pending - record.prop) }
                note("${record.prop.label} $label 요청 #${reqId.take(8)} 보내지 않음: $why")
                notSent()
            },
        )
        port.write(record.prop, record.area, value, dispatch, { echo -> onWriteDone(record.jobId, reqId, "onComplete($echo)", ok = true) }, { e ->
            onWriteDone(record.jobId, reqId, "onFailed(${e.text()})", ok = false)
        })
    }

    /**
     * Right before a command enters the SDK: the preparation is still current (shown, parked, files writable, no
     * product lighting), the record is still this request, and the property's listener is registered with no value
     * off the one the command is based on.
     */
    private fun commandGate(token: Int, prop: ControlProp, jobId: String, requestId: String): String? {
        val w = observers[prop]
        return when {
            !stillValid(token) -> "명령 직전 조건이 바뀌었어요"
            state.records.firstOrNull { it.jobId == jobId }?.requestId != requestId -> "기록이 바뀌었어요"
            w == null || !w.registered -> "${prop.label} 리스너 등록을 확인하지 못했어요"
            w.heard != null -> "명령을 기다리는 동안 ${prop.label} 값이 ${w.heard}(으)로 바뀌었어요"
            else -> null
        }
    }

    /** setHvacAuto back to the recorded value, as a tracked request of the same job (pre-write checks again). */
    private fun restoreAuto(r: ControlRecord, autoKey: String) {
        val area = autoKey.substringAfter(' ')
        withTimeout<Pair<Access, List<String>>>({ ok, err -> port.sideAreas(SideState.HVAC_AUTO, { a, list -> ok(a to list) }, err) }) { cap, error ->
            if (!alive) return@withTimeout
            val held = when {
                cap == null -> "HVAC_AUTO 지원 확인 실패($error)"
                cap.first != Access.READ_WRITE -> "HVAC_AUTO 읽기·쓰기 지원 아님(${cap.first}): 복원 뒤 재조회로 확인할 수 없음"
                area !in cap.second -> "HVAC_AUTO 영역 $area 미지원(${cap.second})"
                !parking().parked || hidden -> "주차 조건 없음 또는 화면 이탈"
                state.storageFault != null -> "기록 저장 오류"
                externalBlock() != null -> "제품 휴식 무드등 작업 중"
                else -> null
            }
            if (held != null) {
                persist(replace(r.copy(stage = ControlStage.RESTORE_FAILED, note = "자동 상태 복원 보류: $held")))
                return@withTimeout note("${r.prop.label} 자동 상태 복원 보류: $held. 기록 유지")
            }
            sendAuto(r, autoKey, area)
        }
    }

    private fun sendAuto(r: ControlRecord, autoKey: String, area: String) {
        val next = r.copy(stage = ControlStage.RESTORE_REQUESTED, requestId = newId(), responseReceived = false, lastEcho = null, note = "자동 상태 복원 요청")
        if (!persist(replace(next))) return
        val value = r.related.getValue(autoKey)
        val reqId = next.requestId
        val dispatch = Dispatch(
            gate = {
                when {
                    !alive || hidden || !parking().parked -> "주차 조건 없음 또는 화면 이탈"
                    state.storageFault != null -> "기록 저장 오류"
                    externalBlock() != null -> "제품 휴식 무드등 작업 중"
                    state.records.firstOrNull { it.jobId == r.jobId }?.requestId != reqId -> "기록이 바뀌었어요"
                    else -> null
                }
            },
            onStart = {
                update { copy(pending = pending + (r.prop to PendingRequest(r.jobId, reqId, "자동 상태 복원"))) }
                writeTimers[reqId] = scheduler.schedule(CONTROL_WRITE_TIMEOUT_MS) { onWriteTimeout(r.prop, reqId) }
                note("HVAC_AUTO $area 복원 요청 #${reqId.take(8)} payload=$value")
            },
            onNotSent = { why ->
                writeTimers.remove(reqId)?.cancel()
                if (state.pending[r.prop]?.requestId == reqId) update { copy(pending = pending - r.prop) }
                // Certainly not sent: held like a refused auto restore (this request only).
                if (state.records.firstOrNull { it.jobId == r.jobId }?.requestId == reqId) {
                    persist(replace(r.copy(stage = ControlStage.RESTORE_FAILED, note = "자동 상태 복원 보류: 보내지 않음($why)")), afterClose = true)
                }
                note("${r.prop.label} 자동 상태 복원 보내지 않음: $why. 기록 유지")
            },
        )
        port.writeSide(SideState.HVAC_AUTO, area, value, dispatch, { echo -> onWriteDone(r.jobId, reqId, "onComplete($echo)", ok = true) }, { e ->
            onWriteDone(r.jobId, reqId, "onFailed(${e.text()})", ok = false)
        })
    }

    private fun onWriteTimeout(prop: ControlProp, reqId: String) {
        writeTimers.remove(reqId)
        val p = state.pending[prop] ?: return
        if (p.requestId != reqId) return
        note("${prop.label} ${p.label} 요청 #${reqId.take(8)}: ${CONTROL_WRITE_TIMEOUT_MS}ms 동안 응답 없음 → 결과 불명(응답 대기). 기록·잠금 유지")
        update { copy(pending = pending + (prop to p.copy(timedOut = true)), message = "${prop.label} 결과 불명(응답 대기)") }
    }

    private fun onWriteDone(jobId: String, reqId: String, result: String, ok: Boolean) {
        if (!alive) return log("ignored late write callback after close #${reqId.take(8)} $result")
        writeTimers.remove(reqId)?.cancel()
        val r = state.records.firstOrNull { it.jobId == jobId }
        if (r == null || r.requestId != reqId) return note("이전 요청의 늦은 응답 무시 #${reqId.take(8)} $result")
        update { copy(pending = pending - r.prop) }
        note("${r.prop.label} 응답 #${reqId.take(8)} $result")
        val answered = r.copy(responseReceived = true, lastEcho = result)
        if (!persist(replace(answered))) return
        scheduler.schedule(CONTROL_SETTLE_MS) { confirm(jobId, reqId, ok) }
    }

    /** Re-read after the terminal callback; the callback value is never taken as the vehicle value. */
    private fun confirm(jobId: String, reqId: String, callbackOk: Boolean) {
        val r = state.records.firstOrNull { it.jobId == jobId && it.requestId == reqId } ?: return
        readOrNull(r.prop, r.area) { value, readError ->
            relatedNow(r) { now ->
                val r2 = state.records.firstOrNull { it.jobId == jobId && it.requestId == reqId } ?: return@relatedNow
                val side = r2.related.filter { (k, v) -> now[k] != null && now[k] != v }.map { (k, v) -> "$k $v→${now[k]}" }
                val sideText = if (side.isEmpty()) "공유 상태 변화 없음" else "공유 상태 변화: ${side.joinToString()}"
                val read = value ?: "조회 실패($readError)"
                if (r2.stage == ControlStage.RESTORE_REQUESTED) {
                    val autoKey = r2.related.keys.firstOrNull { it.startsWith("HVAC_AUTO ") }
                    val onlyAuto = autoKey != null && side.size == 1 && now[autoKey] != r2.related[autoKey] && now[autoKey] in setOf("true", "false")
                    if (callbackOk && ControlValues.same(r2.prop, value, r2.original) && onlyAuto && autoRestored.add(r2.jobId)) {
                        note("${r2.prop.label} 값은 원래대로, 자동 상태만 ${now[autoKey]} → 원래 ${r2.related[autoKey]}로 복원 요청")
                        return@relatedNow restoreAuto(r2, autoKey!!)
                    }
                    if (callbackOk && ControlValues.same(r2.prop, value, r2.original) && side.isEmpty()) {
                        if (!deleteConfirmed(r2, read)) return@relatedNow
                        closeObserver(r2.prop)
                        note("${r2.prop.label} 복원 확인: 재조회 $read = 원래값, $sideText. 기록 삭제")
                        update { copy(message = "${r2.prop.label} 원래대로 확인") }
                    } else {
                        persist(replace(r2.copy(stage = ControlStage.RESTORE_FAILED, lastRead = read, note = sideText)))
                        note("${r2.prop.label} 복원 실패/불명: 응답 ${r2.lastEcho}, 재조회 $read(원래 ${r2.original}), $sideText. 기록 유지")
                    }
                } else {
                    val confirmed = callbackOk && ControlValues.same(r2.prop, value, r2.target)
                    val stage = if (confirmed) ControlStage.APPLY_CONFIRMED else ControlStage.RESULT_UNKNOWN
                    persist(replace(r2.copy(stage = stage, lastRead = read, note = sideText)))
                    note("${r2.prop.label} ${stage.label}: 요청 ${r2.target}, 응답 ${r2.lastEcho}, 재조회 $read, $sideText")
                }
            }
        }
    }

    /**
     * The move waits in the SDK lane while the preparation [token] stays open. Its gate: no STOP since it was
     * queued, [commandGate] (position listener registered, no position change meanwhile) and the seat safety. On
     * entering it reserves the lane under its own request id until a STOP of it answers ([onStopDone]). Polling
     * reads wait in the lane during the motion, so the listener and the watchdog bound it.
     */
    private fun sendMove(run: SeatRun, requestId: String, token: Int, sent: String, notSent: () -> Unit) {
        val stops = stopSeq
        val dispatch = Dispatch(
            gate = {
                if (stopSeq != stops) "STOP 요청이 있었어요" else commandGate(token, ControlProp.SEAT_BACKREST, run.jobId, requestId) ?: seatSafe()
            },
            onStart = {
                observers[ControlProp.SEAT_BACKREST]?.basis = null
                seat = run
                run.moveId = requestId
                update { copy(seatMoving = true, pending = pending + (ControlProp.SEAT_BACKREST to PendingRequest(run.jobId, requestId, run.direction.name))) }
                run.watchdog = scheduler.schedule(SEAT_WATCHDOG_MS) { if (seat === run) stopSeat("watchdog ${SEAT_WATCHDOG_MS}ms") }
                pollSeat(run)
                note("등받이 ${run.direction.name} 요청 #${requestId.take(8)} (시작 위치 ${run.start}, 원래 ${run.original})")
                end(token, sent)
            },
            onNotSent = { why ->
                observers[ControlProp.SEAT_BACKREST]?.basis = null
                if (seat === run) {
                    // Started here but never entered the SDK: no motion to wait for.
                    seat = null
                    run.watchdog?.cancel()
                    run.poll?.cancel()
                    update { copy(seatMoving = false, pending = pending - ControlProp.SEAT_BACKREST) }
                }
                note("등받이 ${run.direction.name} 요청 #${requestId.take(8)} 보내지 않음: $why")
                notSent()
            },
        )
        port.move(DRIVER, run.direction, requestId, dispatch, { echo -> onMoveDone(requestId, "onComplete($echo)", ok = true) }, { e -> onMoveDone(requestId, "onFailed(${e.text()})", ok = false) })
    }

    private fun onMoveDone(requestId: String, result: String, ok: Boolean) {
        if (!alive) return log("ignored late move callback after close $result")
        val run = seat?.takeIf { it.moveId == requestId } ?: return note("이전 이동 요청의 늦은 응답 무시 $result")
        run.moveDone = true
        run.moveResult = result
        note("등받이 ${run.direction.name} 응답 $result (+${clock() - run.startedAt}ms)")
        if (!ok) stopSeat("이동 요청 실패")
        settleIfDone(run)
    }

    /**
     * [key]: the motion this STOP was sent for. Its answer releases that motion's lane reservation (an old STOP's
     * late answer cannot release a newer motion); a failed STOP keeps it and blocks new moves. Also after close,
     * or every SDK call of the app would keep waiting.
     */
    private fun onStopDone(id: String, key: String?, result: String) {
        if (key != null) {
            if (result.startsWith("onComplete")) {
                port.endMotion(key)
                if (heldMotion == key) heldMotion = null
            } else {
                heldMotion = key
            }
        }
        if (!alive) return log("ignored late STOP callback after close $result")
        writeTimers.remove(id)?.cancel()
        if (state.stopPending?.requestId == id) update { copy(stopPending = null) }
        val run = seat?.takeIf { it.stopId == id }
        note("등받이 STOP 응답 $result" + (run?.let { " (+${clock() - it.stopAt}ms)" } ?: ""))
        if (run == null) return
        run.stopDone = true
        run.stopResult = result
        settleIfDone(run)
    }

    private fun settleIfDone(run: SeatRun) {
        if (!run.moveDone || !run.stopDone || run.settling) return
        run.settling = true
        scheduler.schedule(SEAT_SETTLE_MS) { finishSeat(run) }
    }

    private fun finishSeat(run: SeatRun) {
        if (!alive || seat !== run) return
        readOrNull(ControlProp.SEAT_BACKREST, DRIVER) { text, readError ->
            seat = null
            update { copy(seatMoving = false, pending = pending - ControlProp.SEAT_BACKREST) }
            val r = state.records.firstOrNull { it.jobId == run.jobId } ?: return@readOrNull
            val now = text?.toIntOrNull()
            val callbacks = "이동 ${run.moveResult}, STOP(${run.stopReason}) ${run.stopResult}"
            val updates = "위치 알림 ${run.updates.ifEmpty { listOf("없음") }.joinToString()}"
            val read = text ?: "조회 실패($readError)"
            val bothOk = run.moveResult?.startsWith("onComplete") == true && run.stopResult?.startsWith("onComplete") == true
            if (run.restore) {
                if (bothOk && now == run.original) {
                    if (!deleteConfirmed(r.copy(lastEcho = callbacks), read)) return@readOrNull
                    closeObserver(ControlProp.SEAT_BACKREST)
                    note("등받이 복원 확인: 재조회 $now = 원래 ${run.original}. $callbacks. $updates. 기록 삭제")
                } else {
                    persist(replace(r.copy(stage = ControlStage.RESTORE_FAILED, responseReceived = true, lastEcho = callbacks, lastRead = read, note = updates)))
                    note("등받이 복원 실패/불명: 재조회 $read(원래 ${run.original}). $callbacks. $updates")
                }
            } else {
                val moved = now != null && now != run.original
                val stage = if (bothOk && moved) ControlStage.APPLY_CONFIRMED else ControlStage.RESULT_UNKNOWN
                persist(replace(r.copy(stage = stage, responseReceived = true, lastEcho = callbacks, lastRead = read, note = updates)))
                note("등받이 ${run.direction.name} 탐색 결과 ${stage.label}: 위치 ${run.original} → $read. $callbacks. $updates")
            }
        }
    }

    // --- listeners ---

    /**
     * Starts [prop]'s listener once and sets the value a waiting command is based on ([basis]). The handle proves
     * nothing: only a normal return of the registration call ([Watch.registered]) lets a command through its gate.
     * False only when the registration already failed here.
     */
    private fun observe(prop: ControlProp, area: String, basis: String? = null): Boolean {
        observers[prop]?.let { w ->
            w.basis = basis
            w.heard = null
            return true
        }
        val w = Watch()
        w.basis = basis
        observers[prop] = w
        return try {
            w.handle = port.observe(prop, area, { value -> onUpdate(prop, area, value) }, { e ->
                if (!alive) return@observe
                note("${prop.label} 리스너 오류: ${e.text()}")
                // It observes nothing reliable now: removed, so a waiting command is refused at its gate.
                if (observers[prop] === w) closeObserver(prop)
                if (prop == ControlProp.SEAT_BACKREST && seat != null) stopSeat("위치 리스너 오류")
            }, { e ->
                if (observers[prop] !== w) return@observe
                if (e == null) {
                    w.registered = true
                } else {
                    observers.remove(prop)
                    if (alive) note("${prop.label} 리스너 등록 실패: ${e.text()}")
                }
            })
            observers[prop] === w
        } catch (e: RuntimeException) {
            observers.remove(prop)
            note("${prop.label} 리스너 등록 실패: ${e.javaClass.name}")
            false
        }
    }

    /**
     * Seat position from the listener or the direct read during a move. Exploring: the first change STOPs.
     * Restoring: reaching or passing the original STOPs, and so does moving away from it. null, an error or
     * a value out of range STOPs at once. The watchdog is only the upper bound.
     */
    private fun onSeatPosition(value: String?, source: String) {
        val run = seat ?: return
        run.updates += "+${clock() - run.startedAt}ms $source:$value"
        note("등받이 위치 $source $value (+${clock() - run.startedAt}ms)")
        if (run.stopId != null) return
        val v = value?.let { ControlValues.parse(ControlProp.SEAT_BACKREST, it) as Int? } ?: return stopSeat("위치 $source 값 없음")
        if (v !in run.range) return stopSeat("범위 ${run.range} 밖 위치 $v")
        if (!run.restore) {
            if (v != run.original) stopSeat("첫 위치 변화($source)")
            return
        }
        val diff = v - run.original
        val startSide = run.start - run.original > 0
        when {
            diff == 0 || (diff > 0) != startSide -> stopSeat("원래 위치 도달·통과($source)")
            kotlin.math.abs(diff) > run.distance -> stopSeat("원래 위치와 반대 방향 변화($source)")
            else -> run.distance = kotlin.math.abs(diff)
        }
    }

    /** Direct reads while moving, alongside the listener. A failed read STOPs. */
    private fun pollSeat(run: SeatRun) {
        run.poll = scheduler.schedule(SEAT_POLL_MS) {
            if (seat !== run || run.stopId != null || !alive) return@schedule
            readOrNull(ControlProp.SEAT_BACKREST, DRIVER) { v, e ->
                if (seat !== run || run.stopId != null) return@readOrNull
                if (v == null) return@readOrNull stopSeat("위치 직접 조회 실패($e)")
                onSeatPosition(v, "조회")
                pollSeat(run)
            }
        }
    }

    private fun seatRange(bounds: Pair<String?, String?>?): IntRange? {
        val min = bounds?.first?.toIntOrNull() ?: return null
        val max = bounds.second?.toIntOrNull() ?: return null
        return if (min <= max) min..max else null
    }

    /** Fan and temperature share the HVAC auto state: one HVAC scenario must be fully cleared before the next. */
    private fun hvacConflict(prop: ControlProp): String? {
        val hvac = setOf(ControlProp.HVAC_FAN_SPEED, ControlProp.HVAC_TEMPERATURE)
        if (prop !in hvac) return null
        val other = (hvac - prop).single()
        return when {
            recordOf(other) != null -> "${other.label} 기록이 남아 있어 ${prop.label}은 받지 않아요(공조 자동 상태 공유)"
            state.pending.containsKey(other) -> "${other.label} 요청이 진행 중이라 ${prop.label}은 받지 않아요"
            else -> null
        }
    }

    /** The preparation [token] is still the current one and the vehicle is still parked. */
    private fun stillValid(token: Int) = alive && !hidden && token == opSeq && parking().parked && state.storageFault == null && externalBlock() == null

    /** A record saved for a command that was certainly not sent: remove it (product-spec 6-1 "요청 안 함"). */
    private fun unsent(record: ControlRecord, token: Int, why: String) {
        if (state.records.any { it.jobId == record.jobId && it.requestId == record.requestId }) {
            closeObserver(record.prop)
            if (persist(state.records.filter { it.jobId != record.jobId }, afterClose = true)) note("$why 명령을 보내지 않았어요. 보내지 않은 기록이라 지웠어요: ${describe(record)}")
        }
        end(token, "$why ${record.prop.label} 명령을 보내지 않았어요(요청 안 함)")
    }

    private fun closeObserver(prop: ControlProp) {
        observers.remove(prop)?.handle?.let { runCatching { it.close() } }
    }

    private fun onUpdate(prop: ControlProp, area: String, value: String?) {
        if (!alive) return
        // A command of ours waiting in the lane: a value off its basis refuses it at the gate.
        observers[prop]?.let { w -> w.basis?.let { if (!ControlValues.same(prop, value, it)) w.heard = value ?: "null" } }
        update { copy(live = live + ("${prop.name} $area" to (value ?: "null"))) }
        if (prop == ControlProp.SEAT_BACKREST && seat != null) return onSeatPosition(value, "알림")
        note("${prop.label} 리스너 $value")
        val r = recordOf(prop) ?: return
        if (state.pending.containsKey(prop) || r.stage != ControlStage.APPLY_CONFIRMED || value == null) return
        val expected = if (prop == ControlProp.SEAT_BACKREST) r.lastRead else r.target
        if (!ControlValues.same(prop, value, expected)) {
            persist(replace(r.copy(stage = ControlStage.USER_CHANGED, lastRead = value, note = "앱 요청이 없을 때 $expected → $value")))
            note("${prop.label} 사용자 변경 판정: $expected → $value. 자동 복원하지 않아요")
        }
    }

    // --- steps ---

    /** Capability: [prop]'s area with read and write, its bounds and the temperature increment. */
    private fun capability(token: Int, prop: ControlProp, then: (area: String, bounds: Pair<String?, String?>?, increment: Float?) -> Unit) {
        ask(token, "${prop.label} 지원 확인", { ok, err -> port.capability(prop, { c, m, inc -> ok(Triple(c, m, inc)) }, err) }) { (cap, move, inc) ->
            val area = pickArea(prop, cap) ?: return@ask end(token, "${prop.label}: 운전석을 포함한 지원 영역이 없어 쓰지 않아요(${cap.areas.map { it.area }})")
            val bounds = cap.areas.first { it.area == area }.let { it.min to it.max }.takeIf { it.first != null }
            when {
                prop == ControlProp.SEAT_BACKREST && (cap.access != Access.READ && cap.access != Access.READ_WRITE) -> end(token, "등받이 위치를 읽을 수 없어 움직이지 않아요(${cap.access})")
                prop == ControlProp.SEAT_BACKREST && bounds == null -> end(token, "등받이 위치 범위가 없어 움직이지 않아요")
                prop == ControlProp.SEAT_BACKREST && (move == null || DRIVER !in move.areas.map { it.area } || (move.access != Access.WRITE && move.access != Access.READ_WRITE)) ->
                    end(token, "운전석 등받이 이동을 지원하지 않아요(${move?.access}, ${move?.areas?.map { it.area }})")
                prop != ControlProp.SEAT_BACKREST && cap.access != Access.READ_WRITE -> end(token, "${prop.label}: 읽기·쓰기 권한이 없어요(${cap.access})")
                prop == ControlProp.HVAC_TEMPERATURE && (inc == null || inc <= 0f) -> end(token, "온도 간격을 확인하지 못해 쓰지 않아요($inc)")
                else -> then(area, bounds, inc)
            }
        }
    }

    /** Light switch ON for the color, HVAC power on for fan and temperature; the values go into [related]. */
    private fun precondition(token: Int, prop: ControlProp, then: (Map<String, String>) -> Unit) {
        val states = if (prop == ControlProp.AMBIENT_COLOR) listOf(SideState.LIGHT_SWITCH) else listOf(SideState.HVAC_POWER, SideState.HVAC_AUTO, SideState.HVAC_DUAL)
        val values = mutableMapOf<String, String>()
        fun next(i: Int) {
            if (i == states.size) {
                val blocked = when (prop) {
                    ControlProp.AMBIENT_COLOR -> values.entries.firstOrNull { it.key.startsWith("LIGHT_SWITCH") }?.value != "ON"
                    else -> values.entries.firstOrNull { it.key.startsWith("HVAC_POWER") }?.value != "true"
                }
                if (blocked) return end(token, "${prop.label}: ${if (prop == ControlProp.AMBIENT_COLOR) "무드등 스위치가 켜져 있지 않아" else "공조 전원이 켜져 있지 않아"} 쓰지 않아요($values)")
                return then(values)
            }
            val s = states[i]
            ask<List<String>>(token, "${s.name} 영역", { ok, err -> port.sideAreas(s, { _, list -> ok(list) }, err) }) { areas ->
                val area = areas.firstOrNull { DRIVER in it.split('+') } ?: return@ask end(token, "${s.name} 영역이 없어 쓰지 않아요")
                ask<String?>(token, "${s.name} $area", { ok, err -> port.readSide(s, area, ok, err) }) { v ->
                    if (v == null) return@ask end(token, "${s.name} 값이 없어 쓰지 않아요")
                    values["${s.name} $area"] = v
                    next(i + 1)
                }
            }
        }
        next(0)
    }

    /** For temperature also the other areas' target temperatures. */
    private fun relatedValues(token: Int, prop: ControlProp, area: String, base: Map<String, String>, then: (Map<String, String>) -> Unit) {
        if (prop != ControlProp.HVAC_TEMPERATURE) return then(base)
        ask(token, "온도 영역", { ok, err -> port.capability(prop, { c, _, _ -> ok(c) }, err) }) { cap ->
            val others = cap.areas.map { it.area }.filter { it != area }
            val values = base.toMutableMap()
            fun next(i: Int) {
                if (i == others.size) return then(values)
                ask<String?>(token, "온도 ${others[i]}", { ok, err -> port.read(prop, others[i], ok, err) }) { v ->
                    if (v == null) return@ask end(token, "온도 ${others[i]} 값이 없어 쓰지 않아요")
                    values["HVAC_TEMPERATURE ${others[i]}"] = v
                    next(i + 1)
                }
            }
            next(0)
        }
    }

    /** Re-reads every key of [r].related (display and side-effect check, no token). */
    private fun relatedNow(r: ControlRecord, then: (Map<String, String>) -> Unit) {
        val keys = r.related.keys.toList()
        val out = mutableMapOf<String, String>()
        fun next(i: Int) {
            if (!alive) return log("ignored late related read after close or lease loss (${r.prop.name})")
            if (i == keys.size) return then(out)
            val (name, area) = keys[i].split(' ', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            val cb: (String?, String?) -> Unit = { v, e ->
                out[keys[i]] = v ?: "조회 실패($e)"
                next(i + 1)
            }
            val side = SideState.entries.firstOrNull { it.name == name }
            if (side != null) withTimeout({ ok, err -> port.readSide(side, area, ok, err) }, cb)
            else withTimeout({ ok, err -> port.read(ControlProp.valueOf(name), area, ok, err) }, cb)
        }
        next(0)
    }

    private fun targetFor(prop: ControlProp, original: String, bounds: Pair<String?, String?>?, increment: Float?): String? = when (prop) {
        ControlProp.AMBIENT_COLOR -> WARM_COLOR.takeIf { !ControlValues.same(prop, original, it) }
        ControlProp.HVAC_FAN_SPEED -> {
            val now = original.toIntOrNull()
            val min = bounds?.first?.toIntOrNull()
            if (now == null || min == null) null else (now - 1).takeIf { it >= min && it >= 1 }?.toString()
        }
        ControlProp.HVAC_TEMPERATURE -> {
            val now = original.toFloatOrNull()
            val min = bounds?.first?.toFloatOrNull()
            val max = bounds?.second?.toFloatOrNull()
            if (now == null || min == null || max == null || increment == null) null
            else listOf(now + increment, now - increment).firstOrNull { it in min..max }?.toString()
        }
        ControlProp.SEAT_BACKREST -> null
    }

    private fun inBounds(prop: ControlProp, value: String, bounds: Pair<String?, String?>?): Boolean {
        if (prop == ControlProp.AMBIENT_COLOR) return ControlValues.parse(prop, value) != null
        val v = value.toFloatOrNull() ?: return false
        val min = bounds?.first?.toFloatOrNull() ?: return false
        val max = bounds.second?.toFloatOrNull() ?: return false
        return v in min..max
    }

    private fun pickArea(prop: ControlProp, cap: CapabilityResult): String? {
        val areas = cap.areas.map { it.area }
        return when (prop) {
            ControlProp.SEAT_BACKREST, ControlProp.HVAC_TEMPERATURE -> areas.firstOrNull { it == DRIVER } ?: areas.firstOrNull { DRIVER in it.split('+') }
            else -> areas.firstOrNull { DRIVER in it.split('+') }
        }
    }

    private fun seatSafe(): String? = when {
        !parking().parked -> "주차(P + 주행 제한 없음)가 확인되지 않아 등받이를 움직이지 않아요"
        door() != DoorStatus.CLOSED -> "조수석 문 닫힘이 확인되지 않아 등받이를 움직이지 않아요(${door()})"
        state.pending.containsKey(ControlProp.SEAT_BACKREST) || state.stopPending != null -> "등받이에 응답을 기다리는 요청이 있어요"
        heldMotion != null -> "STOP 응답을 받지 못해 등받이 이동 예약이 남아 있어요. [STOP]을 다시 눌러 주세요"
        else -> seatBlock()?.let { "등받이를 움직이지 않아요: $it" }
    }

    private fun opposite(m: Move) = if (m == Move.BACKWARD) Move.FORWARD else Move.BACKWARD

    // --- reads with timeouts ---

    /** A step of the running operation [token]; failure or timeout ends the operation without writing. */
    private fun <T> ask(token: Int, label: String, call: (ok: (T) -> Unit, err: (ProbeError) -> Unit) -> Unit, then: (T) -> Unit) {
        withTimeout(call) { value: T?, error ->
            if (!alive || token != opSeq) return@withTimeout log("ignored late read: $label")
            if (error != null) return@withTimeout end(token, "$label 실패: $error. 쓰지 않아요")
            @Suppress("UNCHECKED_CAST")
            then(value as T)
        }
    }

    /** Like [ask] but a null value is passed on (display reads). */
    private fun askOrNull(token: Int, label: String, call: (ok: (String?) -> Unit, err: (ProbeError) -> Unit) -> Unit, then: (String?) -> Unit) {
        withTimeout(call) { value, error ->
            if (!alive || token != opSeq) return@withTimeout
            then(value ?: error?.let { "조회 실패($it)" })
        }
    }

    private fun readOrNull(prop: ControlProp, area: String, then: (String?, String?) -> Unit) =
        withTimeout<String?>({ ok, err -> port.read(prop, area, ok, err) }) { v, e -> if (alive) then(v, e) }

    /** Exactly one answer: value, error or "timeout". Late replies after the answer are dropped. */
    private fun <T> withTimeout(call: (ok: (T) -> Unit, err: (ProbeError) -> Unit) -> Unit, done: (T?, String?) -> Unit) {
        var answered = false
        lateinit var timer: Cancellable
        fun finish(v: T?, e: String?) {
            if (answered) return
            answered = true
            timer.cancel()
            done(v, e)
        }
        timer = scheduler.schedule(CONTROL_READ_TIMEOUT_MS) { finish(null, "${CONTROL_READ_TIMEOUT_MS}ms 응답 없음") }
        try {
            call({ finish(it, null) }, { finish(null, it.text()) })
        } catch (e: RuntimeException) {
            finish(null, "${e.javaClass.name}: ${e.message}")
        }
    }

    // --- state ---

    private fun begin(label: String): Int? {
        update { copy(busy = true, message = label) }
        note(label)
        return ++opSeq
    }

    private fun end(token: Int, message: String) {
        if (token != opSeq) return
        note(message)
        update { copy(busy = false, message = message) }
    }

    private fun reject(message: String) {
        note("거절: $message")
        update { copy(message = message) }
    }

    /** [writeBlock] plus the product lighting: checked before every vehicle write (not for deleting a record). */
    private fun vehicleBlock(): String? = writeBlock() ?: externalBlock()?.let { "제품 휴식 무드등 작업 때문에 차량에 쓰지 않아요: $it" }

    private fun writeBlock(): String? = when {
        hidden -> "화면이 보이지 않는 동안에는 새로 쓰지 않아요"
        state.loadProblem != null -> "기록을 읽을 수 없어 쓰지 않아요: ${state.loadProblem}"
        state.storageFault != null -> "기록 저장 오류가 있어 쓰지 않아요: ${state.storageFault}"
        state.busy -> "다른 단계가 진행 중이에요"
        else -> null
    }

    /** A record whose latest request has not answered in this run (or an earlier unresolved one) is locked. */
    fun lockReason(r: ControlRecord): String? = when {
        r.priorRequestUnresolved -> "${r.prop.label}: 이전 요청의 결과를 알 수 없어 앱에서 쓰거나 지우지 않아요"
        r.awaitingResponse && state.pending[r.prop]?.requestId != r.requestId ->
            "${r.prop.label}: 응답을 받지 못한 요청(${r.requestId.take(8)})이 남아 있어 앱에서 쓰거나 지우지 않아요"
        else -> null
    }

    private fun recordOf(prop: ControlProp) = state.records.firstOrNull { it.prop == prop }

    private fun replace(next: ControlRecord) = state.records.map { if (it.jobId == next.jobId) next else it }

    /**
     * Saves the list; a failure sets the storage fault, which blocks every later write and delete.
     * A closed bench or one that lost the lease never touches the file: requests it already sent are left to
     * the current owner, which reloads the record. Only [afterClose] (a command that certainly never entered the
     * SDK taking its own record back) may still write after close, while no newer bench holds the file. After a
     * storage fault a record may still be updated with an answer (attempted), but no record is added or deleted.
     */
    private fun persist(next: List<ControlRecord>, afterClose: Boolean = false): Boolean {
        if (!alive && !(afterClose && lease.isHeld(leaseId))) {
            log("ignored record change after close or lease loss")
            return false
        }
        if (state.storageFault != null && next.map { it.jobId }.toSet() != state.records.map { it.jobId }.toSet()) {
            note("기록 저장 오류 뒤라 기록을 추가하거나 지우지 않아요")
            return false
        }
        if (!store.save(next)) {
            update { copy(storageFault = "기록 파일 저장 실패(${store.lastError})", busy = false) }
            note("기록 저장 실패(${store.lastError}): 이후 쓰기·삭제를 막아요")
            return false
        }
        update { copy(records = next) }
        return true
    }

    /** Restore confirmed: delete the record, or after a storage fault keep it and save only the answer (attempted). */
    private fun deleteConfirmed(r: ControlRecord, read: String): Boolean {
        if (state.storageFault == null) return persist(state.records.filter { it.jobId != r.jobId })
        persist(replace(r.copy(responseReceived = true, lastRead = read, note = "복원 확인(재조회 = 원래값). 기록 저장 오류가 있어 기록을 지우지 않음")))
        note("${r.prop.label}: 복원은 확인됐지만 기록 저장 오류가 있어 기록을 지우지 않아요")
        return false
    }

    private fun describe(r: ControlRecord) =
        "${r.prop.label} ${r.area} ${r.stage.label}, 원래 ${r.original}, 목표 ${r.target}, 응답 ${if (r.responseReceived) "받음" else "없음"}" +
            (r.lastEcho?.let { ", 응답 $it" } ?: "") + (r.lastRead?.let { ", 재조회 $it" } ?: "") + (r.note?.let { ", $it" } ?: "")

    private fun note(line: String) {
        log(line)
        update { copy(log = (log + line).takeLast(LOG_LIMIT)) }
    }

    /** After close the state still follows (records) but nothing is shown. */
    private inline fun update(change: ControlUiState.() -> ControlUiState) {
        state = state.change()
        if (!closed) onState(state)
    }
}
