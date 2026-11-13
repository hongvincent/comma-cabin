package app.commacabin.seat

import app.commacabin.control.SeatAxis
import app.commacabin.probe.Cancellable
import app.commacabin.probe.Scheduler
import java.util.UUID

/** What [SeatEmergencyStop] holds after [SeatEmergencyStop.start]. */
data class SeatRecoveryState(
    val started: Boolean = false,
    /** The journal content as this manager knows it (earlier STOPs kept, its own STOPs appended). */
    val snapshot: SeatSnapshot? = null,
    /** Why no new MOVE, restore or record deletion may start. Never lifted here once set. */
    val lock: String? = null,
    /** What has to be checked by hand. Never says "stopped" or "restored". */
    val notice: String? = null,
    /** The first failed journal save; later STOPs still go. */
    val storageFault: String? = null,
    /** IDs of the STOPs this manager sent, in order. */
    val sentStops: List<String> = emptyList(),
)

/**
 * STEP 28: on a new process start, finds an earlier run's seat attempt that may still be moving and sends a new
 * emergency STOP for it, without ever calling that attempt finished. Not created by the app yet.
 *
 * Contract: one instance per process, owned by the future process owner, main thread only, living as long as the
 * process (no close: late answers are still recorded). It must not run beside a [SeatMoveCore] on the same journal;
 * making sure of that is the process owner's job. The constructor calls nothing; [start] reads the journal once.
 *
 * - No call: Empty; Unreadable; a snapshot whose target cannot be trusted (not BACKREST, blank or repeated request
 *   IDs). The last two are locked for a manual check; nothing is written or invented.
 * - No call, no lock: a BACKREST snapshot consistently stopped (outcome and every request agree, see [consistentlyStopped]).
 * - Otherwise one STOP at [start] and a lock that never lifts here: outcome null (SAVED is no proof of not sending in
 *   an earlier run), STOP_FAILED, or an outcome that its requests contradict.
 * - Each STOP gets a new internal ID, is appended to the snapshot and saved before it is sent (a failed save latches
 *   [SeatRecoveryState.storageFault] but never holds the STOP back, and may still have changed the file). Earlier
 *   requests are never changed. afterMove is true only when the MOVE's own answer (COMPLETED/FAILED) is recorded.
 * - An answer updates only its own STOP, once. onComplete means the STOP was answered, not that the seat stopped or
 *   anything was restored. A timeout only marks timedOut. No MOVE, restore, read, deletion or endMotion ever: the
 *   earlier process' lane reservation is not this lane's, and nothing here may release another owner's.
 */
class SeatEmergencyStop(
    private val port: SeatAxisPort,
    private val journal: SeatJournal,
    private val scheduler: Scheduler,
    private val answerMs: Long,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val log: (String) -> Unit = {},
) {
    var state = SeatRecoveryState()
        private set
    var onState: (SeatRecoveryState) -> Unit = {}

    private var snap: SeatSnapshot? = null
    /** An earlier attempt that may still move: STOPs may be sent for it. */
    private var responsible = false
    private val usedIds = mutableSetOf<String>()
    private val timers = mutableMapOf<String, Cancellable>()

    init {
        require(answerMs > 0) { "answerMs must be positive" }
    }

    fun start() {
        if (state.started) return log("시트 비상 STOP 관리자: start는 한 번만 해요")
        state = state.copy(started = true)
        when (val loaded = journal.load()) {
            SeatJournalLoad.Empty -> update()
            is SeatJournalLoad.Unreadable -> manual("시트 기록을 읽지 못했어요(${loaded.reason})")
            is SeatJournalLoad.Found -> classify(loaded.snapshot)
        }
    }

    /** A new STOP for the earlier attempt, also after failed, unanswered or timed-out ones. null = handed to the port. */
    fun emergencyStop(): String? {
        if (!responsible) return "이 관리자가 STOP을 보낼 이전 시트 요청이 없어요".also(log)
        return send("수동 비상 STOP")
    }

    private fun classify(s: SeatSnapshot) {
        snap = s
        val ids = listOf(s.move.requestId) + s.stops.map { it.requestId }
        usedIds += ids
        when {
            s.axis != SeatAxis.BACKREST -> manual("지원하지 않는 축(${s.axis.name})의 시트 기록이에요")
            s.jobId.isBlank() || ids.any { it.isBlank() } || ids.toSet().size != ids.size -> manual("시트 기록의 요청 ID가 비었거나 겹쳐요")
            consistentlyStopped(s) -> update()
            else -> {
                responsible = true
                val why = if (s.outcome == null) "이전 실행의 시트 요청이 끝났는지 알 수 없어요" else "이전 실행의 시트 기록(${s.outcome})이 요청 상태와 맞지 않거나 STOP이 실패했어요"
                state = state.copy(lock = "$why. 새 이동·복원·기록 삭제를 막아요", notice = "$why. 비상 STOP을 보냈어도 실제 정지와 원래 위치는 직접 확인해야 해요")
                send("이전 실행 미완료 기록")
            }
        }
    }

    /**
     * The outcome and every request agree that the seat was stopped, or that no MOVE was ever sent: every request ended,
     * afterMove fits the MOVE (true only once the MOVE's own answer exists, and never back to false after a true), and
     * - NOT_SENT: MOVE NOT_SENT and only STOPs sent before (afterMove false), each answered or failed;
     * - CONFIRMED/UNCONFIRMED: MOVE COMPLETED, the latest STOP after it COMPLETED, no held cause;
     * - HELD: MOVE COMPLETED or FAILED, the latest STOP after it COMPLETED, a held cause.
     */
    private fun consistentlyStopped(s: SeatSnapshot): Boolean {
        val allEnded = s.move.terminal && s.stops.all { it.terminal }
        val moveAnswered = s.move.state == SeatRequestState.COMPLETED || s.move.state == SeatRequestState.FAILED
        val firstAfter = s.stops.indexOfFirst { it.afterMove }
        val afterMoveFits = firstAfter < 0 || (moveAnswered && s.stops.drop(firstAfter).all { it.afterMove })
        val stoppedAfterMove = s.stops.lastOrNull { it.afterMove }?.state == SeatRequestState.COMPLETED
        return allEnded && afterMoveFits && when (s.outcome) {
            SeatOutcome.NOT_SENT -> s.move.state == SeatRequestState.NOT_SENT && firstAfter < 0
            SeatOutcome.CONFIRMED, SeatOutcome.UNCONFIRMED -> s.move.state == SeatRequestState.COMPLETED && stoppedAfterMove && s.held == null
            SeatOutcome.HELD -> moveAnswered && stoppedAfterMove && s.held != null
            SeatOutcome.STOP_FAILED, null -> false
        }
    }

    private fun manual(why: String) {
        state = state.copy(lock = "$why. 새 이동·복원·기록 삭제를 막아요", notice = "$why. 기록은 그대로 두었어요. 시트 상태를 직접 확인해 주세요")
        log(why)
        update()
    }

    private fun send(reason: String): String? {
        val s = snap ?: return "기록이 없어요"
        val id = newId()
        if (id.isBlank() || id in usedIds) {
            state = state.copy(notice = "새 STOP ID를 만들지 못해 STOP을 보내지 않았어요. 기록 잠금은 그대로예요")
            log("비상 STOP 보내지 않음: 새 ID가 비었거나 겹침")
            update()
            return "새 STOP ID를 만들지 못했어요"
        }
        usedIds += id
        val afterMove = s.move.state == SeatRequestState.COMPLETED || s.move.state == SeatRequestState.FAILED
        persist(s.copy(stops = s.stops + SeatRequestMark(id, SeatRequestState.ENTERING, afterMove = afterMove)))
        state = state.copy(sentStops = state.sentStops + id)
        timers[id] = scheduler.schedule(answerMs) {
            timers.remove(id)
            if (stop(id)?.terminal == false) {
                markStop(id) { copy(timedOut = true) }
                log("비상 STOP #$id: ${answerMs}ms 동안 응답 없음(결과 불명, 대기 유지)")
            }
        }
        log("비상 STOP($reason) #$id 보냄")
        update()
        port.stop(s.axis, { echo -> answer(id, "onComplete($echo)", ok = true) }, { e -> answer(id, "onFailed(${e.text()})", ok = false) })
        return null
    }

    private fun answer(id: String, text: String, ok: Boolean) {
        val mark = stop(id)
        if (mark == null || mark.terminal) return log("끝났거나 모르는 비상 STOP #$id 응답 무시: $text")
        timers.remove(id)?.cancel()
        markStop(id) { copy(state = if (ok) SeatRequestState.COMPLETED else SeatRequestState.FAILED, answer = text) }
        log("비상 STOP #$id ${if (ok) "응답 받음(실제 정지는 확인 안 됨)" else "실패"}: $text")
    }

    private fun stop(id: String) = snap?.stops?.firstOrNull { it.requestId == id }

    private fun markStop(id: String, change: SeatRequestMark.() -> SeatRequestMark) {
        val s = snap ?: return
        persist(s.copy(stops = s.stops.map { if (it.requestId == id) it.change() else it }))
        update()
    }

    private fun persist(s: SeatSnapshot) {
        snap = s
        if (journal.save(s)) return
        if (state.storageFault == null) state = state.copy(storageFault = "시트 기록 저장 실패. 비상 STOP은 계속 보내요")
        log("시트 기록 저장 실패(${s.jobId})")
    }

    private fun update() {
        state = state.copy(snapshot = snap)
        onState(state)
    }

    companion object {
        /** Words for one STOP on screen or in logs: an answer is never "stopped" or "restored". */
        fun wording(mark: SeatRequestMark): String = when (mark.state) {
            SeatRequestState.COMPLETED -> "STOP 응답 받음(실제 정지·복원은 확인 안 됨)"
            SeatRequestState.FAILED -> "STOP 실패"
            else -> if (mark.timedOut) "STOP 응답 없음(결과 불명)" else "STOP 응답 대기"
        }
    }
}
