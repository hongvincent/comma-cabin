package app.commacabin.seat

import app.commacabin.control.CONTROL_WRITE_TIMEOUT_MS
import app.commacabin.control.ControlPort
import app.commacabin.control.ControlProp
import app.commacabin.control.DRIVER
import app.commacabin.control.Move
import app.commacabin.control.SeatAxis
import app.commacabin.probe.Access
import app.commacabin.probe.Cancellable
import app.commacabin.probe.ParkingState
import app.commacabin.probe.Scheduler
import app.commacabin.rest.EndReason
import java.util.UUID
import kotlin.math.abs

/**
 * Values measured and reviewed in an environment whose seat position can be trusted. Nothing is assumed: the
 * direction that reclines, how far, the tolerance and every time bound come from that measurement only.
 */
data class SeatEnvironment(
    val increaseWith: Move,
    /** Added to the start position (Long arithmetic, clamped to the capability range) for the rest pose. */
    val reclineDelta: Int,
    val tolerance: Int,
    val noChangeMs: Long,
    val maxMoveMs: Long,
    /** Also the bound for this owner's own capability checks, reads and listener registration. */
    val answerMs: Long,
    val settleMs: Long,
    val confirmReads: Int,
    val confirmIntervalMs: Long,
) {
    fun limits(range: IntRange) = SeatMoveLimits(range, tolerance, increaseWith, noChangeMs, maxMoveMs, answerMs, settleMs, confirmReads, confirmIntervalMs)
}

/** No environment has been verified: the product never moves the seat (F11 stays unmet). Not a setting or a toggle. */
val VERIFIED_SEAT_ENVIRONMENT: SeatEnvironment? = null

/** The agreed end policy, the same as the lighting and climate: only parking-type ends hold; every other end may restore. */
fun seatRestoreAllowed(reason: EndReason): Boolean = when (reason) {
    EndReason.TIME_UP, EndReason.USER, EndReason.DOOR_OPENED, EndReason.DOOR_UNKNOWN, EndReason.APP_LEFT, EndReason.AUDIO_LOST -> true
    EndReason.LEFT_PARK, EndReason.RESTRICTED, EndReason.VEHICLE_UNKNOWN -> false
}

/** Whether the driver backrest can be read with bounds and moved: its range, or why not. Answers on main. */
fun interface SeatCapabilityQuery {
    fun check(ok: (IntRange) -> Unit, err: (String) -> Unit)
}

/** [SeatCapabilityQuery] over the existing ControlPort (position capability with bounds, move capability). */
fun controlCapability(port: ControlPort) = SeatCapabilityQuery { ok, err ->
    port.capability(ControlProp.SEAT_BACKREST, { cap, move, _ ->
        val bound = cap.areas.firstOrNull { it.area == DRIVER }
        val min = bound?.min?.toIntOrNull()
        val max = bound?.max?.toIntOrNull()
        when {
            cap.access != Access.READ && cap.access != Access.READ_WRITE -> err("운전석 등받이 위치를 읽을 수 없어요")
            min == null || max == null || min > max -> err("운전석 등받이 위치 범위가 없어요")
            move == null || (move.access != Access.WRITE && move.access != Access.READ_WRITE) || move.areas.none { it.area == DRIVER } ->
                err("운전석 등받이 이동을 지원하지 않아요")
            else -> ok(min..max)
        }
    }, { e -> err("지원 조회 실패(${e.text()})") })
}

/** [STOP_FAILED] / [NO_ANSWER]: shown while the core's attempt is still pending; nothing is finished by them. */
enum class RestSeatPhase { IDLE, LOCKED, PREPARING, APPLYING, WATCHING, RESTORE_CHECK, RESTORING, HELD, STOP_FAILED, NO_ANSWER }

/** [token]: the rest token [RestSeat.onRestStart] gave out for the job shown. */
data class RestSeatState(val phase: RestSeatPhase = RestSeatPhase.IDLE, val token: Int? = null, val jobId: String? = null, val message: String? = null)

/**
 * STEP 33: the process-wide owner of the seat during a rest (not created by the app yet). Main thread only, living as
 * long as the process: no close, so answers still arrive after an Activity ends.
 *
 * - [start]: the restart manager runs first and stays a field (also without a verified environment: an unfinished
 *   earlier record gets a safety STOP, and its answers keep updating [state]). A locked or any remaining record keeps
 *   the seat untouched in this process; only with no record and a verified environment is the [SeatMoveCore] created.
 *   Only one of them ever writes the journal.
 * - Rest start: capability, a read, then APPLY (the core's gate needs this rest running, parking, the door known closed
 *   and the screen shown). A confirmed APPLY is then watched by this owner, after the core closed its listener: the
 *   registration acknowledged and a fresh read equal to the core's last read make the watch ready. A different value
 *   latches a user change; a missing or out-of-range value, a failed or late registration or read, a listener error
 *   latches a watch failure. Both hold the job at once (record kept held) and nothing undoes them.
 * - Any end (rest end, [onDoor] not closed, [onScreen] hidden, [onParking] lost while the rest runs): the end and the
 *   hold decision are fixed first, then the core is interrupted. A hold is sticky. The watch of a ready job stays open
 *   through the restore checks and is closed right before the core's own new listener; otherwise it closes at once.
 * - Restore, once, only after an end that allows it: every request answered, the MOVE and a later STOP completed, no
 *   failure of trust after the first cause, a read after the stop (or the watch baseline), a fresh capability, parking,
 *   and a fresh read equal to that value; the core then registers anew and checks it again. A confirmed restore is
 *   cleaned with the core's evidence; anything else is held, never retried. While the rest runs, an unconfirmed or
 *   failed APPLY is held, never restored.
 */
class RestSeat(
    private val port: SeatAxisPort,
    private val capability: SeatCapabilityQuery,
    private val journal: SeatJournal,
    private val scheduler: Scheduler,
    private val parking: () -> ParkingState,
    private val environment: SeatEnvironment? = VERIFIED_SEAT_ENVIRONMENT,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val log: (String) -> Unit = {},
    /**
     * Why a NEW apply must not start now (debug seat tools, their records, another seat motion). Asked when a rest
     * starts and at the apply's gate only: never for a safety STOP, an allowed restore or callbacks of a running job.
     */
    private val startBlock: () -> String? = { null },
) {
    var state = RestSeatState()
        private set
    var onState: (RestSeatState) -> Unit = {}

    private var started = false
    /** Kept for the whole process: its late STOP answers are recorded and shown. */
    private var manager: SeatEmergencyStop? = null
    private var core: SeatMoveCore? = null
    private var job: Job? = null
    /** Invalidates this owner's pending capability checks and reads. */
    private var gen = 0
    /** Invalidates the watch listener's late callbacks. */
    private var watchGen = 0
    private var watch: AutoCloseable? = null
    private var watchTimer: Cancellable? = null
    private var doorClosed: Boolean? = null
    private var screenShown = false
    /** Process-unique rest tokens; a caller's own session numbers (which restart per Activity) are never matched. */
    private var tokenSeq = 0

    private class Job(val token: Int, val jobId: String) {
        var phase = RestSeatPhase.PREPARING
        var restoring = false
        var ending = false
        /** Sticky: once set, no restore for this job. */
        var hold: String? = null
        var range: IntRange = 0..0
        var original = 0
        val moveIds = mutableListOf<String>()
        var baseline: Int? = null
        var watchAck = false
        /** Registration acknowledged and the first fresh read equal to the baseline. */
        var watchReady = false
        val handled = mutableSetOf<String>()
    }

    fun start() {
        if (started) return
        started = true
        val m = SeatEmergencyStop(port, journal, scheduler, CONTROL_WRITE_TIMEOUT_MS, newId, log)
        manager = m
        m.onState = { ms -> if (state.phase == RestSeatPhase.LOCKED) show(RestSeatPhase.LOCKED, locked(ms)) }
        m.start()
        when {
            m.state.lock != null -> show(RestSeatPhase.LOCKED, locked(m.state))
            m.state.snapshot != null -> show(RestSeatPhase.LOCKED, "이전 실행의 시트 기록이 남아 있어요. 자동으로 움직이거나 지우지 않아요")
            environment == null -> show(RestSeatPhase.IDLE, "시트 자동 조절 환경이 검증되지 않아 시트를 움직이지 않아요")
            else -> core = SeatMoveCore(SeatAxis.BACKREST, port, journal, scheduler, { gate() }, { null }, log).also { it.onState = ::onCore }
        }
    }

    /** The passenger door as known now (null = unknown). Not closed while a rest's seat job runs: that job ends. */
    fun onDoor(closed: Boolean?) {
        doorClosed = closed
        if (closed != true) runningJob()?.let { end(it, true, "조수석 문 ${closed ?: "확인 불가"}") }
    }

    /** The rest screen shown or not. Hidden while a rest's seat job runs: that job ends (as leaving the app). */
    fun onScreen(shown: Boolean) {
        screenShown = shown
        if (!shown) runningJob()?.let { end(it, true, "화면 이탈") }
    }

    /**
     * A rest started. Returns this owner's process-unique token for it, to be given back to [onRestEnd]; null = the
     * seat is not touched in this rest (and nothing was called or saved for it). The caller keeps its own session to
     * token mapping (as for the lighting and climate runs); no other number ends a job.
     */
    fun onRestStart(): Int? {
        val env = environment ?: return null
        val c = core ?: return null
        if (job != null) return null.also { log("이전 시트 작업이 끝나지 않아 이번 휴식은 시트를 건드리지 않아요") }
        if (c.state.lock != null || c.state.snapshot != null) return null
        startBlock()?.let { why ->
            log("새 시트 적용 안 함: $why")
            return null
        }
        val j = Job(++tokenSeq, newId())
        job = j
        val g = ++gen
        show(RestSeatPhase.PREPARING)
        ask({ g == gen }, env.answerMs, "지원 조회", { ok, err -> capability.check(ok, err) }, { endJob(j, it) }) { range ->
            j.range = range
            ask({ g == gen }, env.answerMs, "위치 조회", ::readPosition, { endJob(j, it) }) { v -> apply(j, env, v) }
        }
        return j.token
    }

    /** [token]: exactly what [onRestStart] returned for that rest; any other value (an older rest's late end) is ignored. */
    fun onRestEnd(token: Int, reason: EndReason) {
        val j = job?.takeIf { it.token == token } ?: return
        when {
            !j.ending -> end(j, seatRestoreAllowed(reason), "휴식 종료(${reason.name})")
            !seatRestoreAllowed(reason) -> holdAfterEnd(j, "휴식 종료(${reason.name})") // a parking-type end after an earlier end
        }
    }

    fun onParking(next: ParkingState) {
        if (next.parked) return // never a reason to resume or retry
        val j = job ?: return
        if (!j.ending) end(j, false, "주차 조건 상실") else holdAfterEnd(j, "주차 조건 상실")
    }

    /** Parking-type loss after the end was fixed: hold for good and stop whatever still runs. */
    private fun holdAfterEnd(j: Job, why: String) {
        if (j.phase == RestSeatPhase.HELD) return
        if (j.hold == null) j.hold = why
        gen++
        closeWatch()
        when (j.phase) {
            RestSeatPhase.APPLYING, RestSeatPhase.RESTORING -> core?.interrupt("주차 조건 상실", newId())
            RestSeatPhase.PREPARING -> endJob(j, "준비 중 주차 조건이 바뀌어 시트를 움직이지 않았어요")
            else -> hold(j, j.hold!!)
        }
    }

    // --- ends ---

    private fun runningJob(): Job? = job?.takeIf { !it.ending && it.phase in RUNNING }

    /** Fixes the end (and a hold when [allow] is false) before anything can call back, then stops what runs. */
    private fun end(j: Job, allow: Boolean, why: String) {
        j.ending = true
        if (!allow && j.hold == null) j.hold = why
        gen++
        val keepWatch = j.phase == RestSeatPhase.WATCHING && j.watchReady && j.hold == null
        if (!keepWatch) closeWatch()
        when (j.phase) {
            RestSeatPhase.PREPARING -> endJob(j, "준비 중에 휴식이 끝나 시트를 움직이지 않았어요")
            RestSeatPhase.APPLYING -> core?.interrupt(why, newId())
            RestSeatPhase.WATCHING -> if (!keepWatch) hold(j, j.hold ?: "감시 준비(등록·첫 확인) 전에 휴식이 끝났어요") else later(j) { decide(j, null) }
            else -> Unit
        }
    }

    // --- apply ---

    private fun apply(j: Job, env: SeatEnvironment, v: Int) {
        val c = core ?: return
        if (v !in j.range) return endJob(j, "현재 위치가 지원 범위 밖이라 움직이지 않아요")
        val target = (v.toLong() + env.reclineDelta).coerceIn(j.range.first.toLong(), j.range.last.toLong())
        if (abs(target - v) <= env.tolerance) return endJob(j, "움직일 필요가 없어 시트를 건드리지 않았어요")
        j.original = v
        j.phase = RestSeatPhase.APPLYING
        val id = newId()
        j.moveIds += id
        show(RestSeatPhase.APPLYING)
        c.begin(SeatMoveRequest(j.jobId, SeatPurpose.APPLY, id, newId(), newId(), v, v, target.toInt(), env.limits(j.range)))?.let { why ->
            endJob(j, "시트 적용을 시작하지 않았어요($why)")
        }
    }

    /** The core's gate. APPLY: this rest running, parking, the door known closed, the screen shown. RESTORE: parking and a valid, unheld restore. */
    private fun gate(): String? {
        val j = job ?: return "시트 작업이 없어요"
        return when {
            !parking().parked -> "주차 조건이 아니에요"
            j.hold != null -> "보류된 시트 작업이에요"
            j.restoring -> if (j.phase == RestSeatPhase.RESTORING) null else "복원 시도가 유효하지 않아요"
            j.ending -> "휴식이 끝났어요"
            doorClosed != true -> "조수석 문이 닫혀 있지 않거나 확인되지 않았어요"
            !screenShown -> "화면이 보이지 않아요"
            else -> startBlock()
        }
    }

    /**
     * For the debug write tools: the product seat holds a job (running, held or failed) or a record of an earlier run.
     * null with no verified environment and nothing stored, so it never blocks the other features then.
     */
    fun busyReason(): String? = when {
        job != null -> "제품 시트 작업이나 보류 기록이 있어요"
        state.phase == RestSeatPhase.LOCKED -> "제품 시트 기록이 남아 있어요"
        core?.state?.lock != null -> "제품 시트 기록에 문제가 있어요"
        else -> null
    }

    /** A short product sentence, only when a seat record really needs the person's own check; null otherwise. */
    fun productNotice(): String? = when (state.phase) {
        RestSeatPhase.LOCKED, RestSeatPhase.HELD -> "이전 시트 조절이 끝까지 확인되지 않았어요. 차량에서 시트 자세를 직접 확인해 주세요."
        RestSeatPhase.STOP_FAILED, RestSeatPhase.NO_ANSWER -> "시트가 멈췄는지 확인되지 않았어요. 차량에서 시트 자세를 직접 확인해 주세요."
        else -> null
    }

    // --- core results ---

    private fun onCore(s: SeatMoveState) {
        val j = job ?: return
        if (s.phase != SeatPhase.DONE && s.snapshot?.jobId == j.jobId) pendingTrouble(j, s)
        val r = s.result ?: return
        if (s.phase != SeatPhase.DONE || r.jobId != j.jobId || r.moveRequestId !in j.moveIds || !j.handled.add(r.moveRequestId)) return
        later(j) { handle(j, r) }
    }

    /**
     * The core's attempt is still pending but a STOP failed or a request did not answer: say so, keep the job held, and
     * leave the attempt as it is (its late answers still count; a parking loss still sends a new STOP).
     */
    private fun pendingTrouble(j: Job, s: SeatMoveState) {
        val snap = s.snapshot ?: return
        val requests = listOf(snap.move) + snap.stops
        when {
            s.outcome == SeatOutcome.STOP_FAILED -> {
                if (j.hold == null) j.hold = "STOP 실패"
                show(RestSeatPhase.STOP_FAILED, "STOP이 실패해 시트가 멈췄는지 확인되지 않았어요. 기록을 잠그고 직접 확인이 필요해요")
            }
            requests.any { it.timedOut && !it.terminal } ->
                show(RestSeatPhase.NO_ANSWER, "시트 요청의 응답이 없어 결과를 알 수 없어요. 기록을 잠그고 기다려요")
        }
    }

    private fun handle(j: Job, first: SeatAttemptResult) {
        val c = core ?: return
        // A lock or storage fault of the core comes first: never shown as a normal end or a watch.
        c.state.lock?.let { return hold(j, "시트 기록 오류로 보류해요($it)") }
        // The core's latest view of this attempt (a hold may have changed it since).
        val r = c.state.result?.takeIf { it.moveRequestId == first.moveRequestId } ?: first
        when {
            r.purpose == SeatPurpose.APPLY && r.endedAs == SeatOutcome.NOT_SENT -> {
                // Nothing moved: an unsaved attempt left no record; a saved one is cleaned with the core's evidence.
                if (c.state.snapshot == null || c.clearRecord() == null) endJob(j, "시트를 움직이지 않았어요(${r.firstCause})")
                else hold(j, "보내지 않은 적용 기록을 정리하지 못했어요")
            }
            r.purpose == SeatPurpose.APPLY && !j.ending ->
                if (j.hold == null && r.outcome == SeatOutcome.CONFIRMED && r.firstCause == SeatStopCause.TARGET && !r.faultAfter) startWatch(j, r)
                else hold(j, "휴식 중 적용이 확인되지 않아 보류해요(${r.outcome}, ${r.firstCause})")
            r.purpose == SeatPurpose.APPLY -> decide(j, r)
            r.outcome == SeatOutcome.CONFIRMED && !r.faultAfter -> {
                val why = c.clearRecord()
                if (why == null) endJob(j, "원래 자세로 돌아온 것을 확인하고 기록을 정리했어요") else hold(j, "기록 정리 실패($why)")
            }
            else -> hold(j, "복원이 확인되지 않았어요(${r.outcome}, ${r.firstCause})")
        }
    }

    /** After an allowed end. [r]: the APPLY result when the rest ended during it; null when ending from a ready watch. */
    private fun decide(j: Job, r: SeatAttemptResult?) {
        j.hold?.let { return hold(j, it) }
        val expected = if (r == null) {
            j.baseline ?: return hold(j, "감시 기준 위치가 없어 원복하지 않아요")
        } else {
            val trusted = (r.firstCause == SeatStopCause.TARGET || r.firstCause == SeatStopCause.INTERRUPTED) &&
                !r.faultAfter && r.allAnswered && r.stoppedAfterMove && r.heldAfter == null
            if (!trusted) return hold(j, "적용 중 오류나 확인할 수 없는 상태가 있어 원복하지 않아요(${r.firstCause}${if (r.faultAfter) ", 뒤이은 오류" else ""})")
            r.lastRead ?: return hold(j, "정지 뒤 확인한 위치가 없어 원복하지 않아요")
        }
        restore(j, expected)
    }

    // --- restore ---

    /** [expected]: the position confirmed after the stop (or the watch baseline); a fresh read must equal it. */
    private fun restore(j: Job, expected: Int) {
        val env = environment ?: return
        j.phase = RestSeatPhase.RESTORE_CHECK
        show(RestSeatPhase.RESTORE_CHECK)
        val g = ++gen
        ask({ g == gen }, env.answerMs, "지원 재조회", { ok, err -> capability.check(ok, err) }, { hold(j, it) }) { range ->
            if (!parking().parked) return@ask hold(j, "주차 조건이 아니에요")
            ask({ g == gen }, env.answerMs, "위치 재조회", ::readPosition, { hold(j, it) }) { v ->
                when {
                    j.hold != null -> hold(j, j.hold!!)
                    v != expected -> hold(j, "정지 뒤 확인한 위치($expected)와 지금 위치($v)가 달라요")
                    v !in range -> hold(j, "정지 뒤 위치가 범위 밖이에요")
                    abs(v.toLong() - j.original) <= env.tolerance -> hold(j, "원래 위치 오차 안이지만 확인된 복원이 없어 기록을 남겨요")
                    else -> {
                        // The owner's watch ends only now, right before the core's own new listener.
                        closeWatch()
                        j.phase = RestSeatPhase.RESTORING
                        j.restoring = true
                        val id = newId()
                        j.moveIds += id
                        show(RestSeatPhase.RESTORING)
                        core?.begin(SeatMoveRequest(j.jobId, SeatPurpose.RESTORE, id, newId(), newId(), j.original, v, j.original, env.limits(range)))
                            ?.let { why -> hold(j, "복원을 시작하지 않았어요($why)") }
                    }
                }
            }
        }
    }

    // --- watch (from a confirmed APPLY until the core's restore listener) ---

    private fun startWatch(j: Job, r: SeatAttemptResult) {
        val env = environment ?: return
        j.baseline = r.lastRead
        j.phase = RestSeatPhase.WATCHING
        show(RestSeatPhase.WATCHING)
        val wg = ++watchGen
        watchTimer = scheduler.schedule(env.answerMs) { if (wg == watchGen && !j.watchAck) watchFailed(j, "감시 등록 응답 없음") }
        watch = port.observe(SeatAxis.BACKREST, { e ->
            if (wg != watchGen) return@observe
            if (e != null) return@observe watchFailed(j, "감시 등록 실패(${e.text()})")
            j.watchAck = true
            watchTimer?.cancel()
            ask({ wg == watchGen }, env.answerMs, "감시 위치 조회", ::readPosition, { watchFailed(j, it) }) { v ->
                seen(j, v)
                if (j.hold == null) j.watchReady = true
            }
        }, { v -> if (wg == watchGen) seen(j, v) }, { e -> if (wg == watchGen) watchFailed(j, "감시 리스너 오류(${e.text()})") })
        if (wg != watchGen) closeHandle()
    }

    private fun seen(j: Job, v: Int?) {
        when {
            v == null || v !in j.range -> watchFailed(j, "감시 위치 값을 믿을 수 없어요($v)")
            v != j.baseline -> hold(j, "휴식 중 시트 위치가 바뀌었어요")
        }
    }

    private fun watchFailed(j: Job, why: String) = hold(j, "위치 감시 실패: $why")

    private fun closeWatch() {
        watchGen++
        watchTimer?.cancel()
        watchTimer = null
        closeHandle()
    }

    private fun closeHandle() {
        watch?.close()
        watch = null
    }

    // --- helpers ---

    /** Sticky: the first cause stays. The core keeps the record as held once it is idle (refused while it runs). */
    private fun hold(j: Job, cause: String) {
        if (j.hold == null) j.hold = cause
        closeWatch()
        gen++
        j.phase = RestSeatPhase.HELD
        val c = core
        val record = c?.state?.snapshot
        if (c != null && record != null && record.jobId == j.jobId && record.move.requestId in j.moveIds) {
            c.holdRecord(j.jobId, record.move.requestId, j.hold!!)?.let { log("시트 기록 보류 고정 안 됨: $it") }
        }
        show(RestSeatPhase.HELD, "${j.hold}. 시트 스위치로 직접 맞춰 주세요")
    }

    private fun endJob(j: Job, message: String) {
        if (job !== j) return
        closeWatch()
        job = null
        show(RestSeatPhase.IDLE, message)
    }

    /** Decisions run after the core's callback has returned, never inside it. */
    private fun later(j: Job, block: () -> Unit) {
        scheduler.schedule(0) { if (job === j) block() }
    }

    private fun readPosition(ok: (Int) -> Unit, err: (String) -> Unit) =
        port.read(SeatAxis.BACKREST, { v -> if (v == null) err("값 없음") else ok(v) }, { e -> err(e.text()) })

    /** One answer within [ms]; answers of an old generation and after the limit are dropped. */
    private fun <T> ask(current: () -> Boolean, ms: Long, what: String, call: (ok: (T) -> Unit, err: (String) -> Unit) -> Unit, failed: (String) -> Unit, then: (T) -> Unit) {
        var done = false
        lateinit var timer: Cancellable
        fun answer(block: () -> Unit) {
            if (done) return
            done = true
            timer.cancel()
            if (!current()) return log("늦은 $what 응답 무시")
            block()
        }
        timer = scheduler.schedule(ms) { answer { failed("$what 응답 없음(${ms}ms)") } }
        call({ v -> answer { then(v) } }, { e -> answer { failed("$what 실패($e)") } })
    }

    private fun locked(ms: SeatRecoveryState): String {
        val last = ms.snapshot?.stops?.lastOrNull { it.requestId in ms.sentStops }
        return listOfNotNull(ms.notice ?: ms.lock, last?.let { "비상 STOP: ${SeatEmergencyStop.wording(it)}" }).joinToString(". ")
    }

    private fun show(phase: RestSeatPhase, message: String? = state.message) {
        state = RestSeatState(phase, job?.token, job?.jobId, message)
        onState(state)
    }

    private companion object {
        val RUNNING = setOf(RestSeatPhase.PREPARING, RestSeatPhase.APPLYING, RestSeatPhase.WATCHING)
    }
}
