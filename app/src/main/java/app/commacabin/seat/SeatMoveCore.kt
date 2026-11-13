package app.commacabin.seat

import app.commacabin.control.Move
import app.commacabin.control.SeatAxis
import app.commacabin.probe.Cancellable
import app.commacabin.probe.Dispatch
import app.commacabin.probe.ProbeError
import app.commacabin.probe.Scheduler
import kotlin.math.abs

/**
 * STEP 25: moves one axis of the driver seat toward a target and confirms it only from reads taken after the motion
 * ended. Pure Kotlin, main thread only. NOT connected to the app: its adapter ([ControlSeatAxisPort]) and journal file
 * ([FileSeatJournal]) exist but nothing in the app creates them, so nothing here proves a real or emulated seat moves.
 * Do not wire it before the environment check.
 *
 * - Before a MOVE: the listener registration is confirmed, a read equals [SeatMoveRequest.start], and the journal
 *   saved this attempt. At the MOVE's gate (right before the SDK entry): same attempt, no STOP since, listener sound and
 *   unchanged, [safety] and [otherWork] clear, and the journal saves the entering state. Any failure: no MOVE.
 * - While moving, no reads (the lane holds them, see [SeatAxisPort]). Listener values and limits decide the STOP.
 *   Only "target reached" is a normal STOP; every other cause (limit, wrong/missing/out-of-range value, listener
 *   failure, MOVE failure, [interrupt], [stop]) is latched as [SeatSnapshot.held] and the attempt ends HELD.
 * - [interrupt] (safety or screen lost) sends a new safety STOP whenever the seat may move or its stop is not
 *   confirmed, also after a failed or unanswered STOP and with a storage fault.
 * - Every STOP is tracked by its own ID, answer and timeout, and is never held back by another unanswered STOP. A STOP
 *   sent before the MOVE answered is followed by one more STOP right after that answer. The motion ends
 *   ([SeatAxisPort.endMotion]) only when the MOVE answered, every STOP answered, and the latest STOP sent after the MOVE
 *   answer answered onComplete. Only reads after that, all in range and on target, confirm.
 * - A timeout ends nothing: the request stays pending in memory and in the journal and keeps [SeatMoveState.lock].
 *   Late answers only update their own request. Nothing starts by itself: no resume, retry or deletion.
 * - Time comes only from [scheduler] delays (the app's Handler uses uptime, not the wall clock).
 */
class SeatMoveCore(
    private val axis: SeatAxis,
    private val port: SeatAxisPort,
    private val journal: SeatJournal,
    private val scheduler: Scheduler,
    /** Parking, door and screen as of now: null = a MOVE may start. */
    private val safety: () -> String?,
    /** Another seat or vehicle job that must not run beside this one: null = none. */
    private val otherWork: () -> String?,
    private val log: (String) -> Unit = {},
) {
    var state = SeatMoveState()
        private set
    var onState: (SeatMoveState) -> Unit = {}

    /** The journal content as known here: this run's attempt, or the snapshot loaded at start. */
    private var snap: SeatSnapshot? = null
    private var loadLock: String? = null
    private var storageFault: String? = null
    /** The attempt this core owns, until every one of its requests answered and it ended. */
    private var current: Run? = null
    /** Every request ID loaded, reserved or sent here: never reused, so an answer can only reach its own request. */
    private val usedIds = mutableSetOf<String>()
    /** Counts STOP requests: a MOVE queued before one is refused at its gate. */
    private var stopSeq = 0

    private class Run(val req: SeatMoveRequest, val command: Move, val stops: Int) {
        var phase = SeatPhase.PREPARING
        var watch: AutoCloseable? = null
        var registered = false
        /** A listener problem or a value off the start before the MOVE entered: the MOVE's gate refuses. */
        var fault: String? = null
        /** This attempt is in the journal (only then is the MOVE handed over). */
        var saved = false
        var stopSent = false
        /** First cause of a STOP other than "target reached"; never cleared. */
        var held: String? = null
        val autoStopIds = ArrayDeque(listOf(req.stopRequestId, req.finalStopRequestId))
        var last = req.start
        /** Changes on every STOP and at the end: a waiting settle step or confirming read of before is ignored. */
        var confirmSeq = 0
        val reads = mutableListOf<Int?>()
        val timers = mutableListOf<Cancellable>()
        var noChange: Cancellable? = null
        var moveTimer: Cancellable? = null
        val stopTimers = mutableMapOf<String, Cancellable>()
    }

    init {
        when (val loaded = journal.load()) {
            SeatJournalLoad.Empty -> Unit
            is SeatJournalLoad.Found -> {
                val s = loaded.snapshot
                snap = s
                usedIds += ids(s)
                // The outcome alone proves nothing: every request must be answered and fit it, else unknown.
                if (!settled(s)) loadLock = "이전 실행의 시트 기록(${s.jobId})이 완료 불명이거나 앞뒤가 맞지 않아요. 자동으로 쓰거나 지우지 않아요"
            }
            is SeatJournalLoad.Unreadable -> loadLock = "시트 기록을 읽지 못했어요(${loaded.reason}). 쓰기·삭제를 막아요"
        }
        loadLock?.let(log)
        update()
    }

    /** Starts one attempt. null = accepted (its result comes in [state]); else why nothing was started or saved. */
    fun begin(req: SeatMoveRequest): String? {
        refusal(req)?.let { why ->
            log("시트 이동 거부(${req.jobId}): $why")
            return why
        }
        usedIds += listOf(req.moveRequestId, req.stopRequestId, req.finalStopRequestId)
        val l = req.limits
        val decrease = if (l.increaseWith == Move.FORWARD) Move.BACKWARD else Move.FORWARD
        val run = Run(req, if (req.target > req.start) l.increaseWith else decrease, stopSeq)
        current = run
        update { SeatMoveState(jobId = req.jobId) }
        run.timers += scheduler.schedule(l.answerMs) {
            if (current === run && !run.registered) finish(run, SeatOutcome.NOT_SENT, "위치 리스너 등록 응답 없음(${l.answerMs}ms)")
        }
        val handle = port.observe(axis, { e -> onRegistered(run, e) }, { v -> onPosition(run, v) }, { e ->
            if (current !== run) return@observe log("이전 작업의 리스너 오류 무시")
            onListenerFault(run, "위치 리스너 실패(${e.text()})")
        })
        if (current === run) run.watch = handle else handle.close()
        return null
    }

    /**
     * A STOP of this core's running attempt: never gated, also with a storage fault, other STOPs unanswered or after a
     * failed STOP. The attempt is latched HELD. null = sent; else why not. Before the MOVE was handed over there is
     * nothing to stop: the preparation is cancelled instead. STOPs for other tools or earlier runs are not this core's.
     */
    fun stop(reason: String, requestId: String): String? {
        val run = current ?: return "이 코어가 진행 중인 시트 작업이 없어요".also { log("STOP($reason) 보내지 않음: $it") }
        if (run.phase == SeatPhase.PREPARING) {
            latch(run, "수동 STOP($reason)")
            finish(run, SeatOutcome.NOT_SENT, "STOP($reason): 이동을 넘기기 전이라 준비만 취소")
            return "이동을 넘기기 전이라 준비만 취소했어요(보낼 STOP 없음)"
        }
        idProblem(requestId)?.let { return it.also { log("STOP($reason) 보내지 않음: $it") } }
        latch(run, "수동 STOP($reason)")
        sendStop(run, reason, requestId)
        return null
    }

    /**
     * Safety lost or the screen left: latched HELD at once. While the seat may move or its stop is not confirmed (MOVE
     * handed over; STOPs pending, failed or none yet), a new safety STOP [stopRequestId] goes at once, never held back
     * by other unanswered STOPs or a storage fault; every STOP keeps its own answer and the lock. Before the MOVE was
     * handed over the preparation is only cancelled; while confirming after the motion ended, the confirmation is
     * cancelled. Nothing resumes when it comes back. null = handed to the port (no proof anything stopped); else why
     * no STOP was sent.
     */
    fun interrupt(reason: String, stopRequestId: String): String? {
        val run = current ?: return "이 코어가 진행 중인 시트 작업이 없어요".also { log("$reason: $it") }
        latch(run, reason)
        when (run.phase) {
            SeatPhase.PREPARING -> {
                finish(run, SeatOutcome.NOT_SENT, "$reason: 이동을 넘기기 전이라 준비만 취소")
                return "이동을 넘기기 전이라 준비만 취소했어요(보낼 STOP 없음)"
            }
            SeatPhase.CONFIRMING -> {
                finish(run, SeatOutcome.HELD, "$reason: 정지 후 확인 취소")
                return "이미 정지한 뒤 확인 중이라 확인만 취소했어요"
            }
            else -> Unit
        }
        idProblem(stopRequestId)?.let { return it.also { log("$reason 안전 STOP 보내지 않음: $it") } }
        sendStop(run, reason, stopRequestId)
        return null
    }

    private fun idProblem(id: String): String? = when {
        id.isBlank() -> "STOP 요청 ID가 비어 있어요"
        id in usedIds -> "이미 쓴 요청 ID예요"
        else -> null
    }

    // --- preparation ---

    private fun refusal(req: SeatMoveRequest): String? {
        lock()?.let { return it }
        val l = req.limits
        val ids = listOf(req.moveRequestId, req.stopRequestId, req.finalStopRequestId)
        val s = snap
        return when {
            req.jobId.isBlank() || ids.any { it.isBlank() } -> "작업·요청 ID가 비어 있어요"
            ids.toSet().size != ids.size -> "MOVE·STOP 요청 ID가 서로 같아요"
            ids.any { it in usedIds } -> "이미 쓴 요청 ID예요"
            l.range.isEmpty() -> "위치 범위가 비어 있어요"
            req.original !in l.range || req.start !in l.range || req.target !in l.range -> "원래·시작·목표 위치가 범위 ${l.range} 밖이에요"
            l.tolerance < 0 -> "허용 오차가 음수예요"
            l.increaseWith == Move.STOP -> "증가 방향 명령이 STOP이에요"
            l.noChangeMs <= 0 || l.maxMoveMs <= 0 || l.answerMs <= 0 || l.settleMs < 0 || l.confirmIntervalMs < 0 -> "시간 제한 값이 잘못됐어요"
            l.confirmReads < 1 -> "정지 후 확인 조회는 1회 이상이어야 해요"
            req.purpose == SeatPurpose.RESTORE && req.target != req.original -> "복원 목표가 원래 위치가 아니에요"
            abs(req.target.toLong() - req.start) <= l.tolerance -> "시작 위치가 이미 목표 허용 오차 안이라 움직이지 않아요"
            s != null && s.jobId != req.jobId -> "다른 작업(${s.jobId})의 기록이 남아 있어 덮어쓰지 않아요"
            s != null && s.original != req.original -> "원래 위치가 기록의 ${s.original}과 달라요"
            else -> safety() ?: otherWork()
        }
    }

    private fun onRegistered(run: Run, e: ProbeError?) {
        if (current !== run || run.registered || run.phase != SeatPhase.PREPARING) return
        if (e != null) return finish(run, SeatOutcome.NOT_SENT, "위치 리스너 등록 실패(${e.text()})")
        run.registered = true
        read(run) { v, err -> preflightDone(run, v, err) }
    }

    private fun preflightDone(run: Run, v: Int?, err: String?) {
        if (run.phase != SeatPhase.PREPARING) return
        val req = run.req
        val why = when {
            v == null -> "현재 위치 조회 실패(${err ?: "값 없음"})"
            v != req.start -> "현재 위치 $v 가 시작 위치 ${req.start}와 달라요"
            else -> entryProblem(run)
        }
        if (why != null) return finish(run, SeatOutcome.NOT_SENT, why)
        val before = snap
        if (!persist(SeatSnapshot(req.jobId, req.purpose, axis, req.original, req.start, req.target, SeatRequestMark(req.moveRequestId)))) {
            snap = before
            return finish(run, SeatOutcome.NOT_SENT, "기록 저장 실패: 이동을 보내지 않아요")
        }
        run.saved = true
        run.phase = SeatPhase.QUEUED
        update()
        val dispatch = Dispatch(
            gate = { entryProblem(run) ?: savedEntering() },
            onStart = { onMoveStart(run) },
            onNotSent = { why2 -> onMoveNotSent(run, why2) },
        )
        port.move(axis, run.command, req.moveRequestId, dispatch, { echo -> onMoveAnswer(run, "onComplete($echo)", ok = true) }, { e ->
            onMoveAnswer(run, "onFailed(${e.text()})", ok = false)
        })
    }

    /** Checked before the MOVE is handed over and again at its gate. The outside checks run first, then the state. */
    private fun entryProblem(run: Run): String? {
        val outside = safety() ?: otherWork()
        return when {
            current !== run -> "다른 작업으로 바뀌었어요"
            stopSeq != run.stops -> "STOP 요청이 있었어요"
            !run.registered -> "위치 리스너 등록이 확인되지 않았어요"
            else -> run.fault ?: storageFault ?: outside
        }
    }

    private fun savedEntering(): String? {
        val s = snap ?: return "기록이 없어요"
        return if (persist(s.copy(move = s.move.copy(state = SeatRequestState.ENTERING)))) null else "기록 저장 실패: 이동을 보내지 않아요"
    }

    // --- the MOVE ---

    private fun onMoveStart(run: Run) {
        val l = run.req.limits
        run.phase = SeatPhase.MOVING
        run.moveTimer = scheduler.schedule(l.answerMs) {
            if (current === run && snap?.move?.terminal == false) {
                mark(run) { copy(move = move.copy(timedOut = true)) }
                log("MOVE ${run.req.moveRequestId}: ${l.answerMs}ms 동안 응답 없음(결과 불명, 대기 유지)")
                update()
            }
        }
        run.timers += scheduler.schedule(l.maxMoveMs) {
            if (current === run && run.phase == SeatPhase.MOVING) autoStop(run, "이동 상한 ${l.maxMoveMs}ms")
        }
        restartNoChange(run)
        update()
    }

    private fun onMoveNotSent(run: Run, why: String) {
        if (current !== run) return log("이전 작업의 미전송 알림 무시: $why")
        cancelTimers(run)
        mark(run) { copy(move = move.copy(state = SeatRequestState.NOT_SENT, answer = why)) }
        tryFinish(run)
    }

    private fun onMoveAnswer(run: Run, text: String, ok: Boolean) {
        if (current !== run || snap?.move?.terminal != false) return log("끝났거나 중복된 MOVE 응답 무시: $text")
        run.moveTimer?.cancel()
        mark(run) { copy(move = move.copy(state = if (ok) SeatRequestState.COMPLETED else SeatRequestState.FAILED, answer = text)) }
        if (!ok) autoStop(run, "이동 요청 실패")
        tryFinish(run)
    }

    private fun onPosition(run: Run, v: Int?) {
        if (current !== run) return log("이전 작업의 위치 알림 무시: $v")
        val req = run.req
        val l = req.limits
        when (run.phase) {
            SeatPhase.PREPARING, SeatPhase.QUEUED -> if (v != req.start && run.fault == null) run.fault = "이동 전 위치가 $v(으)로 바뀌었어요"
            SeatPhase.MOVING -> {
                if (v == null) return autoStop(run, "위치 값 없음")
                val up = req.target > req.start
                val reached = if (up) v.toLong() >= req.target.toLong() - l.tolerance else v.toLong() <= req.target.toLong() + l.tolerance
                when {
                    v !in l.range -> autoStop(run, "범위 ${l.range} 밖 위치 $v")
                    reached -> autoStop(run, "목표 도달·통과(위치 $v)", abort = false)
                    if (up) v < run.last else v > run.last -> autoStop(run, "반대 방향 변화(${run.last} → $v)")
                    v != run.last -> {
                        run.last = v
                        restartNoChange(run)
                    }
                }
            }
            SeatPhase.CONFIRMING -> if (!onTarget(req, v)) conclude(run, false, "정지 후 위치 변화($v)")
            else -> Unit // STOPPING: values before the motion ended decide nothing.
        }
    }

    /** Latched in every phase: a later good value never makes up for a listener that failed during the attempt. */
    private fun onListenerFault(run: Run, why: String) {
        if (run.fault == null) run.fault = why
        latch(run, why)
        when (run.phase) {
            SeatPhase.MOVING -> autoStop(run, why)
            SeatPhase.CONFIRMING -> conclude(run, false, why)
            else -> update() // before the entry the gate refuses; while stopping the STOPs already go
        }
    }

    private fun restartNoChange(run: Run) {
        run.noChange?.cancel()
        val ms = run.req.limits.noChangeMs
        run.noChange = scheduler.schedule(ms) { if (current === run && run.phase == SeatPhase.MOVING) autoStop(run, "무변화 제한 ${ms}ms") }
    }

    // --- STOP ---

    private fun latch(run: Run, why: String) {
        if (run.held != null) return
        run.held = why
        mark(run) { copy(held = why) }
    }

    /** [abort] (every cause but the target): latched. The attempt's own first STOP, unless one was sent already. */
    private fun autoStop(run: Run, reason: String, abort: Boolean = true) {
        if (abort) latch(run, reason)
        if (!run.stopSent) sendStop(run, reason, run.autoStopIds.removeFirst())
    }

    private fun sendStop(run: Run, reason: String, id: String) {
        stopSeq++
        usedIds += id
        run.stopSent = true
        cancelTimers(run)
        run.confirmSeq++
        run.phase = SeatPhase.STOPPING
        mark(run) { copy(stops = stops + SeatRequestMark(id, SeatRequestState.ENTERING, afterMove = move.terminal)) }
        update { copy(outcome = null, reason = "STOP($reason)", reads = emptyList()) }
        log("STOP($reason) #$id 보냄")
        val ms = run.req.limits.answerMs
        run.stopTimers[id] = scheduler.schedule(ms) {
            if (current === run && snap?.stops?.firstOrNull { it.requestId == id }?.terminal == false) {
                markStop(run, id) { copy(timedOut = true) }
                log("STOP #$id: ${ms}ms 동안 응답 없음(결과 불명, 대기 유지)")
                update()
            }
        }
        port.stop(axis, { echo -> onStopAnswer(run, id, "onComplete($echo)", ok = true) }, { e ->
            onStopAnswer(run, id, "onFailed(${e.text()})", ok = false)
        })
    }

    private fun onStopAnswer(run: Run, id: String, text: String, ok: Boolean) {
        val mark = snap?.stops?.firstOrNull { it.requestId == id }
        if (current !== run || mark == null || mark.terminal) return log("끝났거나 중복된 STOP #$id 응답 무시: $text")
        run.stopTimers.remove(id)?.cancel()
        markStop(run, id) { copy(state = if (ok) SeatRequestState.COMPLETED else SeatRequestState.FAILED, answer = text) }
        tryFinish(run)
    }

    /**
     * After the MOVE answered, STOPs sent before that answer prove nothing (callback order is no apply order): one more
     * STOP goes at once, without waiting for them. Nothing is released, confirmed or ended while any request is open.
     */
    private fun tryFinish(run: Run) {
        val s = snap ?: return
        if (s.move.entered && s.move.terminal && s.stops.isNotEmpty() && s.stops.none { it.afterMove }) {
            return sendStop(run, "MOVE 응답이 STOP 요청보다 늦어 다시 STOP", run.autoStopIds.removeFirst())
        }
        val open = s.stops.any { !it.terminal }
        when {
            !s.move.terminal || open -> update {
                if (!open && s.stops.lastOrNull()?.state == SeatRequestState.FAILED) copy(outcome = SeatOutcome.STOP_FAILED) else this
            }
            s.move.state == SeatRequestState.NOT_SENT -> finish(run, SeatOutcome.NOT_SENT, "이동을 보내지 않음(${s.move.answer})")
            s.stops.isEmpty() -> update() // answered, still moving: the listener or a limit decides the STOP
            s.stops.last { it.afterMove }.state == SeatRequestState.FAILED -> {
                mark(run) { copy(outcome = SeatOutcome.STOP_FAILED) }
                update { copy(outcome = SeatOutcome.STOP_FAILED, reason = "STOP 실패: 이동 예약 유지, 새 ID로 다시 STOP이 필요해요") }
            }
            else -> {
                port.endMotion(run.req.moveRequestId)
                settle(run)
            }
        }
    }

    // --- confirmation, only after the motion ended ---

    private fun settle(run: Run) {
        run.phase = SeatPhase.CONFIRMING
        run.reads.clear()
        if (snap?.outcome != null) mark(run) { copy(outcome = null) }
        val token = ++run.confirmSeq
        scheduler.schedule(run.req.limits.settleMs) { confirm(run, token) }
        update { copy(outcome = null, reads = emptyList()) }
    }

    private fun confirm(run: Run, token: Int) {
        if (current !== run || run.confirmSeq != token) return
        val req = run.req
        read(run) { v, err ->
            if (run.confirmSeq != token) return@read
            run.reads += v
            update { copy(reads = run.reads.toList()) }
            when {
                !onTarget(req, v) -> conclude(run, false, "정지 후 위치 ${v ?: "조회 실패($err)"}: 범위 ${req.limits.range}, 목표 ${req.target}±${req.limits.tolerance} 밖")
                run.reads.size < req.limits.confirmReads -> scheduler.schedule(req.limits.confirmIntervalMs) { confirm(run, token) }
                else -> conclude(run, true, "정지 후 ${run.reads.size}회 조회가 모두 목표 ${req.target}±${req.limits.tolerance} 안")
            }
        }
    }

    /** In range and within tolerance, without Int overflow. */
    private fun onTarget(req: SeatMoveRequest, v: Int?): Boolean =
        v != null && v in req.limits.range && abs(v.toLong() - req.target) <= req.limits.tolerance

    /** A latched cause wins over any read: HELD. */
    private fun conclude(run: Run, onTarget: Boolean, why: String) {
        val held = run.held
        when {
            held != null -> finish(run, SeatOutcome.HELD, "$held (정지 후: $why)")
            onTarget -> finish(run, SeatOutcome.CONFIRMED, why)
            else -> finish(run, SeatOutcome.UNCONFIRMED, why)
        }
    }

    // --- helpers ---

    /** One answer per read: value, error or the answer limit. Answers for an ended attempt are dropped. */
    private fun read(run: Run, then: (Int?, String?) -> Unit) {
        var answered = false
        lateinit var timer: Cancellable
        fun answer(v: Int?, err: String?) {
            if (answered) return
            answered = true
            timer.cancel()
            if (current !== run) return log("이전 작업의 위치 조회 응답 무시: ${v ?: err}")
            then(v, err)
        }
        val ms = run.req.limits.answerMs
        timer = scheduler.schedule(ms) { answer(null, "${ms}ms 응답 없음") }
        port.read(axis, { v -> answer(v, null) }, { e -> answer(null, e.text()) })
    }

    private fun finish(run: Run, outcome: SeatOutcome, why: String) {
        mark(run) { copy(outcome = outcome) }
        cancelTimers(run)
        run.moveTimer?.cancel()
        run.confirmSeq++
        run.watch?.close()
        run.watch = null
        run.phase = SeatPhase.DONE
        current = null
        log("시트 작업 ${run.req.jobId} 끝: $outcome, $why")
        update { copy(phase = SeatPhase.DONE, outcome = outcome, reason = why, reads = run.reads.toList()) }
    }

    private fun cancelTimers(run: Run) {
        run.timers.forEach { it.cancel() }
        run.timers.clear()
        run.noChange?.cancel()
    }

    /** Changes this attempt's journal snapshot; saved when the attempt is in the journal. */
    private fun mark(run: Run, change: SeatSnapshot.() -> SeatSnapshot) {
        val s = snap ?: return
        if (run.saved) persist(s.change())
    }

    private fun markStop(run: Run, id: String, change: SeatRequestMark.() -> SeatRequestMark) =
        mark(run) { copy(stops = stops.map { if (it.requestId == id) it.change() else it }) }

    /** A failed save keeps the attempt going (a STOP is never held back) but blocks every later MOVE. */
    private fun persist(s: SeatSnapshot): Boolean {
        snap = s
        if (journal.save(s)) return true
        if (storageFault == null) storageFault = "시트 기록 저장 실패(${s.jobId}). 새 이동을 막아요"
        log(storageFault!!)
        return false
    }

    private fun ids(s: SeatSnapshot) = listOf(s.move.requestId) + s.stops.map { it.requestId }

    /** Finished, every request answered, and consistent with its outcome. Anything else from an earlier run locks. */
    private fun settled(s: SeatSnapshot): Boolean {
        val ids = ids(s)
        if (s.axis != axis || ids.toSet().size != ids.size || !s.move.terminal || s.stops.any { !it.terminal }) return false
        val ended = s.stops.lastOrNull { it.afterMove }?.state == SeatRequestState.COMPLETED
        return when (s.outcome) {
            SeatOutcome.NOT_SENT -> s.move.state == SeatRequestState.NOT_SENT
            SeatOutcome.CONFIRMED, SeatOutcome.UNCONFIRMED -> s.move.state == SeatRequestState.COMPLETED && ended && s.held == null
            SeatOutcome.HELD -> s.move.entered && ended && s.held != null
            SeatOutcome.STOP_FAILED, null -> false
        }
    }

    private fun lock(): String? = loadLock ?: storageFault ?: current?.let { run ->
        val s = snap
        val open = s?.stops?.filter { !it.terminal }.orEmpty()
        when {
            !run.saved -> "시트 이동 준비 중이에요"
            open.isNotEmpty() -> "STOP 응답을 기다려요(${open.joinToString { it.requestId }})"
            s != null && !s.move.terminal -> if (s.move.timedOut) "MOVE 응답이 없어 결과 불명이에요" else "MOVE 응답을 기다려요"
            state.outcome == SeatOutcome.STOP_FAILED -> "STOP이 실패해 이동 예약이 남아 있어요(새 ID로 다시 STOP 필요)"
            else -> "시트 작업 ${run.req.jobId} 진행 중이에요"
        }
    }

    private fun update(change: SeatMoveState.() -> SeatMoveState = { this }) {
        val next = state.change()
        state = next.copy(phase = current?.phase ?: next.phase, snapshot = snap)
        state = state.copy(lock = lock())
        onState(state)
    }
}
