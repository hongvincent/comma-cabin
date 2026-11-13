package app.commacabin.seat

import app.commacabin.control.Move
import app.commacabin.control.SeatAxis
import app.commacabin.probe.Dispatch
import app.commacabin.probe.ProbeError

/*
 * STEP 25 contract of the seat target-move core ([SeatMoveCore]).
 * Implemented by [ControlSeatAxisPort] (STEP 27, backrest over the existing ControlPort) and [FileSeatJournal]
 * (STEP 26). Nothing in the app creates them or the core yet, so no real or emulated seat has been moved by it.
 */

/**
 * One axis of the driver seat (no other seat). Every callback arrives on the main thread; failures come through the
 * error callbacks, never as a throw. Values are in the SDK's own unit.
 *
 * Lane constraint (existing SdkLane, not changed): from a MOVE's SDK entry until [endMotion] only STOP enters the SDK;
 * reads, registrations and listener removals wait. So the core never reads while moving: the listener and its
 * no-change/max-move limits decide the STOP, and the position is read only after [endMotion].
 */
interface SeatAxisPort {
    /**
     * The handle is no proof of a registration. [onRegistered]: null once the registration call returned normally,
     * else why it did not happen. [onError]: the listener failed later and observes nothing reliable from then on.
     */
    fun observe(axis: SeatAxis, onRegistered: (ProbeError?) -> Unit, onUpdate: (Int?) -> Unit, onError: (ProbeError) -> Unit): AutoCloseable

    fun read(axis: SeatAxis, ok: (Int?) -> Unit, err: (ProbeError) -> Unit)

    /**
     * A MOVE (never STOP), gated by [dispatch] right before the SDK entry. [Dispatch.onNotSent] may also follow
     * [Dispatch.onStart]. Entering reserves the motion [motionKey] until [endMotion]. [ok] receives the SDK's
     * onComplete value: an echo, not a position.
     */
    fun move(axis: SeatAxis, command: Move, motionKey: String, dispatch: Dispatch, ok: (echo: String?) -> Unit, err: (ProbeError) -> Unit)

    /** Never gated; goes first and gives up this owner's MOVEs not in the SDK yet (they get onNotSent). */
    fun stop(axis: SeatAxis, ok: (echo: String?) -> Unit, err: (ProbeError) -> Unit)

    /** The MOVE [motionKey] answered and a STOP sent after that answer answered onComplete: the reservation may end. */
    fun endMotion(motionKey: String)
}

/** Durable journal of one seat job. Removed only by [clear], on the owner's explicit call through the core. */
interface SeatJournal {
    fun load(): SeatJournalLoad

    /** True only once [snapshot] is durably stored. */
    fun save(snapshot: SeatSnapshot): Boolean

    /**
     * Removes the stored snapshot only when it is exactly [expected] and that one is finished (outcome set, every
     * request ended). True only once the removal is durable. A false result is no proof that nothing was removed.
     */
    fun clear(expected: SeatSnapshot): Boolean
}

sealed interface SeatJournalLoad {
    data object Empty : SeatJournalLoad
    data class Found(val snapshot: SeatSnapshot) : SeatJournalLoad
    data class Unreadable(val reason: String) : SeatJournalLoad
}

enum class SeatPurpose { APPLY, RESTORE }

enum class SeatRequestState {
    /** Saved and handed to the port, not past its gate yet: it may still be sent. */
    SAVED,
    /** MOVE: its gate passed (saved as such first), so it may be in the SDK. STOP: handed to the port. */
    ENTERING,
    /** Only from this run's [Dispatch.onNotSent] for this request: it certainly never entered the SDK. */
    NOT_SENT,
    COMPLETED,
    FAILED,
}

data class SeatRequestMark(
    val requestId: String,
    val state: SeatRequestState = SeatRequestState.SAVED,
    /** The terminal answer (onComplete/onFailed text) or the not-sent reason. */
    val answer: String? = null,
    /** No terminal answer within the answer limit: result unknown, the request stays pending. */
    val timedOut: Boolean = false,
    /**
     * STOP only: sent after the MOVE's terminal answer. Only such a STOP can end the motion; SDK callback order is no
     * proof of the order the vehicle applied the commands in.
     */
    val afterMove: Boolean = false,
) {
    /** May be in the SDK (no proof of delivery). */
    val entered: Boolean get() = state == SeatRequestState.ENTERING || state == SeatRequestState.COMPLETED || state == SeatRequestState.FAILED
    val terminal: Boolean get() = state == SeatRequestState.NOT_SENT || state == SeatRequestState.COMPLETED || state == SeatRequestState.FAILED
}

data class SeatSnapshot(
    val jobId: String,
    val purpose: SeatPurpose,
    val axis: SeatAxis,
    val original: Int,
    val start: Int,
    val target: Int,
    val move: SeatRequestMark,
    /** Every STOP of this attempt, in the order sent. */
    val stops: List<SeatRequestMark> = emptyList(),
    /** null = not finished. On load, any request not terminal or an inconsistent snapshot locks, whatever this says. */
    val outcome: SeatOutcome? = null,
    /** Latched cause of a STOP that was not "target reached" (abort or failure). Never cleared by a later value. */
    val held: String? = null,
)

enum class SeatOutcome {
    /**
     * Stopped only for reaching the target, MOVE answered onComplete, a STOP sent after it answered onComplete, and
     * every read after that was inside the range and on target.
     */
    CONFIRMED,
    /** Stopped for reaching the target, but not confirmed after it (off target or out of range, read failed, moved). */
    UNCONFIRMED,
    /**
     * Stopped or ended for another cause ([SeatSnapshot.held]: safety, screen, manual STOP, listener failure, a wrong,
     * missing or out-of-range value, a limit, MOVE failure). Never confirmed whatever later reads show; nothing resumes.
     */
    HELD,
    /** The MOVE certainly never entered the SDK in this run, or was never handed over. */
    NOT_SENT,
    /** A STOP answered onFailed: the motion stays reserved and only another explicit STOP may follow. */
    STOP_FAILED,
}

/** Everything the caller states; nothing here is guessed by the core. */
data class SeatMoveLimits(
    /** Valid positions (capability bounds). 0 inside it is a normal value. */
    val range: IntRange,
    /** |position - target| <= tolerance counts as on target. */
    val tolerance: Int,
    /** The MOVE command that makes the position value grow (environment-verified, never assumed). */
    val increaseWith: Move,
    /** STOP when the position has not changed for this long while moving. */
    val noChangeMs: Long,
    /** STOP when one move lasted this long. A bound only, never evidence of a position. */
    val maxMoveMs: Long,
    /** A MOVE/STOP without an answer this long is marked timed out (still pending); a read or registration fails. */
    val answerMs: Long,
    /** Wait after both the MOVE and the STOP answered, before the first confirming read. */
    val settleMs: Long,
    /** Reads after the STOP answer that must all be on target (at least 1). */
    val confirmReads: Int,
    /** Wait between the confirming reads. */
    val confirmIntervalMs: Long,
)

data class SeatMoveRequest(
    val jobId: String,
    val purpose: SeatPurpose,
    val moveRequestId: String,
    /** The core's own first STOP of this attempt (target reached, a limit, a value or listener failure). */
    val stopRequestId: String,
    /** The core's own STOP after a MOVE answer that came later than an earlier STOP was sent (so STOP is last). */
    val finalStopRequestId: String,
    /** Position before the job's first move; the restore target. */
    val original: Int,
    /** Position this attempt starts from; the current read must equal it. */
    val start: Int,
    val target: Int,
    val limits: SeatMoveLimits,
)

enum class SeatPhase { IDLE, PREPARING, QUEUED, MOVING, STOPPING, CONFIRMING, DONE }

data class SeatMoveState(
    val phase: SeatPhase = SeatPhase.IDLE,
    val jobId: String? = null,
    /** Result of the last attempt; null while it runs. */
    val outcome: SeatOutcome? = null,
    /** Why the last attempt stopped, was not sent or ended. */
    val reason: String? = null,
    /** Reads taken after the STOP answer. */
    val reads: List<Int?> = emptyList(),
    /** The journal content as the core knows it (also one loaded from an earlier run). */
    val snapshot: SeatSnapshot? = null,
    /** Why no new move, restore or record deletion may start now; null = nothing blocks it here. */
    val lock: String? = null,
    /** This run finished an attempt whose record [SeatMoveCore.clearRecord] may remove (once). */
    val clearable: Boolean = false,
)

/**
 * The outcome and every request agree that the seat was stopped, or that no MOVE was ever sent: every request ended,
 * afterMove fits the MOVE (true only once the MOVE's own answer exists, and never back to false after a true), and
 * - NOT_SENT: MOVE NOT_SENT and only STOPs sent before (afterMove false), each answered or failed;
 * - CONFIRMED/UNCONFIRMED: MOVE COMPLETED, the latest STOP after it COMPLETED, no held cause;
 * - HELD: MOVE COMPLETED or FAILED, the latest STOP after it COMPLETED, a held cause.
 * The MOVE itself never has afterMove, and a RESTORE always targets its original. A single outcome field is never
 * trusted on its own.
 */
internal fun SeatSnapshot.stoppedConsistently(): Boolean {
    if (move.afterMove || (purpose == SeatPurpose.RESTORE && target != original)) return false
    val allEnded = move.terminal && stops.all { it.terminal }
    val moveAnswered = move.state == SeatRequestState.COMPLETED || move.state == SeatRequestState.FAILED
    val firstAfter = stops.indexOfFirst { it.afterMove }
    val afterMoveFits = firstAfter < 0 || (moveAnswered && stops.drop(firstAfter).all { it.afterMove })
    val stoppedAfterMove = stops.lastOrNull { it.afterMove }?.state == SeatRequestState.COMPLETED
    return allEnded && afterMoveFits && when (outcome) {
        SeatOutcome.NOT_SENT -> move.state == SeatRequestState.NOT_SENT && firstAfter < 0
        SeatOutcome.CONFIRMED, SeatOutcome.UNCONFIRMED -> move.state == SeatRequestState.COMPLETED && stoppedAfterMove && held == null
        SeatOutcome.HELD -> moveAnswered && stoppedAfterMove && held != null
        SeatOutcome.STOP_FAILED, null -> false
    }
}

/**
 * The only snapshots a clean-up may remove, judged from the snapshot alone: consistent and either a confirmed RESTORE
 * back to its original or an APPLY never sent from its original. Whether this run made the evidence (and whether the
 * job had an earlier record) is [SeatMoveCore]'s check; nothing here makes such evidence.
 */
internal fun SeatSnapshot.removableShape(): Boolean = stoppedConsistently() && when (outcome) {
    SeatOutcome.CONFIRMED -> purpose == SeatPurpose.RESTORE && target == original
    SeatOutcome.NOT_SENT -> purpose == SeatPurpose.APPLY && original == start
    else -> false
}
