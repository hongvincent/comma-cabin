package app.commacabin.sdk

import app.commacabin.probe.Dispatch
import app.commacabin.probe.ProbeError
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The app's only way into the vehicle SDK: one worker thread runs every SDK entry, one at a time, so the main
 * thread never calls the SDK (whose permission check runs Kotlin reflection on the calling thread) and never waits
 * for it. The queue lock is held only to add, take or cancel a task, never while an SDK call runs.
 *
 * - Reads wait in order; a read whose deadline passed before its turn is skipped (its onSkipped, on main).
 * - A write asks its [Dispatch.gate] on main right before the SDK entry, then [Dispatch.onStart] runs on main; the
 *   worker enters the SDK only after onStart has finished. The task state is atomic: a write ends in exactly one
 *   of "entered the SDK" or "not sent" (onNotSent once), and a late main message never revives a given-up write.
 * - STOP goes before everything waiting; at most one STOP per owner and label (one axis of one seat) waits at a
 *   time. It also cancels the owner's motion writes not in the SDK yet (waiting, or taken and still at its gate or
 *   onStart): they end as not sent and never enter. One already in the SDK runs to its end, then the STOP. A running
 *   SDK call cannot be interrupted (the SDK has no way to do so). Other writes are not touched by a STOP.
 * - From the moment a write that may move the seat (with its motion key) enters until [endMotion] with the same
 *   owner and key, only STOP enters the SDK; everything else waits (listeners already registered keep calling
 *   back). [endMotion] is the owner's decision after that motion's STOP answer, never a timer or a close.
 * - [close] (main) cancels the owner's tasks not yet in the SDK (writes: onNotSent at once), removes its
 *   registered listeners (as clean-up tasks) and refuses new tasks. STOP and clean-up still run for it.
 * - Bounds: reads, writes and subscriptions together at most [capacity] waiting (beyond: refused and reported);
 *   STOP at most one waiting per owner and label; clean-up at most one per registered listener.
 *
 * This does not make the SDK itself thread safe: its own coroutines still run beside this worker.
 */
class SdkLane(
    /** Runs on the main thread; never removed by an Activity. */
    val post: (() -> Unit) -> Unit,
    private val log: (String) -> Unit,
    private val clock: () -> Long,
    private val gateTimeoutMs: Long = GATE_TIMEOUT_MS,
    private val readDeadlineMs: Long = READ_DEADLINE_MS,
    private val capacity: Int = CAPACITY,
    startWorker: (Runnable) -> Unit = { Thread(it, WORKER_NAME).apply { isDaemon = true }.start() },
) {
    private enum class Kind { READ, WRITE, STOP, CLEANUP, SUBSCRIBE }

    private class Task(
        val id: Int,
        val owner: Any,
        val kind: Kind,
        val label: String,
        val deadline: Long,
        val block: () -> Unit,
        val dispatch: Dispatch? = null,
        val motionKey: String? = null,
        val onSkipped: ((String) -> Unit)? = null,
    ) {
        val state = AtomicInteger(QUEUED)
        @Volatile var gateLatch: CountDownLatch? = null
        /** Why a STOP or close gave it up while its gate or onStart ran (main reports it once that returns). */
        @Volatile var cancelReason: String? = null
    }

    /** A listener: registered on the worker, removed exactly once by whichever side ends up holding it. */
    private inner class Subscription(
        val owner: Any,
        val label: String,
        val onResult: (ProbeError?) -> Unit,
        val register: () -> AutoCloseable?,
    ) : AutoCloseable {
        private val guard = Any()
        private var registration: AutoCloseable? = null
        private var closed = false
        lateinit var task: Task

        /**
         * Worker: register, unless the handle was closed meanwhile (then it is removed right here, no result). The
         * result goes to main: null only when the registration call returned normally (the SDK gives no other ACK).
         */
        fun registerNow() {
            val r = try {
                register()
            } catch (e: RuntimeException) {
                log("SdkLane: $label 등록 실패 ${e.javaClass.simpleName}")
                post { onResult(e.toProbeError()) }
                return
            }
            if (r == null) {
                post { onResult(ProbeError("ListenerNotRegistered", "$label: 등록하지 못했어요", null)) }
                return
            }
            val removeNow = synchronized(guard) {
                if (closed) true else { registration = r; false }
            }
            if (removeNow) runCatching { r.close() } else post { onResult(null) }
        }

        override fun close() {
            val r = synchronized(guard) {
                if (closed) return
                closed = true
                registration.also { registration = null }
            }
            lock.withLock { subscriptions.remove(this) }
            if (task.state.compareAndSet(QUEUED, CANCELLED)) return
            if (r != null) cleanup(owner, "$label 해제") { runCatching { r.close() } }
        }
    }

    private val lock = ReentrantLock()
    private val hasWork = lock.newCondition()
    private val queue = ArrayDeque<Task>()
    /** Weak: a closed owner nobody holds any more can submit nothing, so it need not be remembered. */
    private val closedOwners: MutableSet<Any> = Collections.newSetFromMap(WeakHashMap())
    private val subscriptions = ArrayList<Subscription>()
    private val nextId = AtomicInteger(0)
    private var current: Task? = null
    private var motionOwner: Any? = null
    private var motionKey: String? = null

    init {
        startWorker(Runnable { while (true) runNext() })
    }

    /** The motion reservation (owner to key), or null. */
    val motion: Pair<Any, String>? get() = lock.withLock { motionOwner?.let { it to motionKey!! } }

    /** Tasks waiting (not counting the running one). */
    val waiting: Int get() = lock.withLock { queue.size }

    fun read(owner: Any, label: String, onSkipped: (String) -> Unit, block: () -> Unit) {
        val refused = lock.withLock {
            when {
                owner in closedOwners -> CLOSED
                bounded() >= capacity -> FULL
                else -> null.also { add(Task(nextId.incrementAndGet(), owner, Kind.READ, label, clock() + readDeadlineMs, block, onSkipped = onSkipped)) }
            }
        }
        // A closed owner gets no callback at all; a full queue is an error the owner sees.
        if (refused == FULL) post { onSkipped("SDK 대기열이 가득 차 조회하지 않았어요") }
    }

    /** [motionKey]: this write may move the seat; the motion it starts is released only by [endMotion] with it. */
    fun write(owner: Any, label: String, dispatch: Dispatch, motionKey: String? = null, block: () -> Unit) {
        val refused = lock.withLock {
            when {
                owner in closedOwners -> CLOSED_TEXT
                bounded() >= capacity -> "SDK 대기열이 가득 차 보내지 않았어요"
                else -> null.also { add(Task(nextId.incrementAndGet(), owner, Kind.WRITE, label, Long.MAX_VALUE, block, dispatch, motionKey)) }
            }
        }
        // The owner calls this on main: a refusal is reported at once, never silently dropped.
        if (refused != null) dispatch.onNotSent(refused)
    }

    /**
     * Main thread. STOP: before every waiting task, also for a closed owner and during a motion. Not gated, never
     * cancelled. First the owner's motion writes not in the SDK yet are given up (see [cancel]): not sent, reported
     * here, or once their gate or onStart returns. False when the same STOP (owner and label) is already waiting:
     * that one goes first and this one is not queued, so its callbacks never come.
     */
    fun stop(owner: Any, label: String, block: () -> Unit): Boolean {
        val due = ArrayList<Task>()
        val queued = lock.withLock {
            // The owner's motion writes not in the SDK yet never enter after this STOP (atomic against the entry).
            val it = queue.iterator()
            while (it.hasNext()) {
                val t = it.next()
                if (t.owner !== owner || t.kind != Kind.WRITE || t.motionKey == null) continue
                if (cancel(t, STOPPED)) due += t
                it.remove()
            }
            current?.takeIf { it.owner === owner && it.kind == Kind.WRITE && it.motionKey != null }?.let { if (cancel(it, STOPPED)) due += it }
            if (queue.any { it.kind == Kind.STOP && it.owner === owner && it.label == label }) return@withLock false
            val t = Task(nextId.incrementAndGet(), owner, Kind.STOP, label, Long.MAX_VALUE, block)
            val firstNonStop = queue.indexOfFirst { it.kind != Kind.STOP }
            if (firstNonStop < 0) queue.addLast(t) else queue.add(firstNonStop, t)
            hasWork.signalAll()
            true
        }
        due.forEach { it.dispatch?.onNotSent(STOPPED) }
        if (due.isNotEmpty()) log("SdkLane: $label 요청으로 SDK에 들어가지 않은 이동 ${due.size}개 취소")
        return queued
    }

    /**
     * Lock held. Gives up [t] unless it is in the SDK already (that check and the worker's entry are one atomic
     * state). True: not sent, and the caller reports it now. False: in the SDK or done already, or its gate or
     * onStart is running on main and main reports it with [why] once that returns (never revived).
     */
    private fun cancel(t: Task, why: String): Boolean {
        val now = t.state.compareAndSet(QUEUED, CANCELLED) || t.state.compareAndSet(GATING, CANCELLED) || t.state.compareAndSet(GRANTED, CANCELLED)
        if (!now) {
            t.cancelReason = why
            if (!t.state.compareAndSet(GATE_RUNNING, ABANDONED) && !t.state.compareAndSet(STARTING, ABANDONED)) {
                t.cancelReason = null
                return false
            }
        }
        // The worker need not wait for the gate any longer: it cannot enter now.
        t.gateLatch?.countDown()
        return now
    }

    /** Safe clean-up (listener removal): also for a closed owner. Waits while a motion is reserved. */
    private fun cleanup(owner: Any, label: String, block: () -> Unit) = lock.withLock {
        add(Task(nextId.incrementAndGet(), owner, Kind.CLEANUP, label, Long.MAX_VALUE, block))
    }

    /**
     * A listener registered on the worker. The handle returns at once and is no proof of a registration: [onResult]
     * (main) says null once the registration call returned normally, or the error (queue full, a throw, nothing
     * registered). A closed owner gets a handle that does nothing and no result. Closed before the registration
     * ran: it never happens (no result). Closed after: one removal is queued. [close] of the owner closes its
     * handles too.
     */
    fun subscribe(owner: Any, label: String, onResult: (ProbeError?) -> Unit, register: () -> AutoCloseable?): AutoCloseable {
        val s = Subscription(owner, label, onResult, register)
        val t = Task(nextId.incrementAndGet(), owner, Kind.SUBSCRIBE, label, Long.MAX_VALUE, { s.registerNow() })
        s.task = t
        val refused = lock.withLock {
            when {
                owner in closedOwners -> CLOSED
                bounded() >= capacity -> FULL
                else -> null.also {
                    subscriptions += s
                    add(t)
                }
            }
        }
        if (refused != null) {
            t.state.set(CANCELLED)
            log("SdkLane: $label 등록하지 않음(${if (refused == CLOSED) "닫힌 화면" else "대기열 가득"})")
            if (refused == FULL) post { onResult(ProbeError("SdkLaneSkipped", "SDK 대기열이 가득 차 리스너를 등록하지 않았어요", null)) }
        }
        return s
    }

    /** That motion's seat is known to be stopped (its STOP answered): other tasks may enter again. */
    fun endMotion(owner: Any, key: String) = lock.withLock {
        if (motionOwner === owner && motionKey == key) {
            motionOwner = null
            motionKey = null
            log("SdkLane: 이동 예약 해제 $key")
            hasWork.signalAll()
        } else {
            log("SdkLane: 이동 예약 해제 요청 무시(현재 예약이 아님) $key")
        }
    }

    /**
     * Main thread. The owner's tasks not yet in the SDK are cancelled (also one already taken but not entered);
     * its writes end as not sent here, before this returns, except one whose gate or onStart is running (a close
     * from inside them): that one is reported once they return. Its registered listeners are removed (clean-up
     * tasks) and new tasks of the owner are refused. Its STOP and clean-up tasks still run; an entered SDK call is
     * not stopped.
     */
    fun close(owner: Any) {
        val cancelled = ArrayList<Task>()
        var dropped = 0
        val subs: List<Subscription>
        lock.withLock {
            closedOwners += owner
            val it = queue.iterator()
            while (it.hasNext()) {
                val t = it.next()
                if (t.owner !== owner || t.kind == Kind.STOP || t.kind == Kind.CLEANUP) continue
                if (cancel(t, CLOSED_TEXT)) cancelled += t
                it.remove()
                dropped++
            }
            // Taken from the queue but not entered yet (a write may be at its gate or onStart, or granted).
            current?.takeIf { it.owner === owner && it.kind != Kind.STOP && it.kind != Kind.CLEANUP }?.let { t ->
                if (cancel(t, CLOSED_TEXT)) cancelled += t
                dropped++
            }
            subs = subscriptions.filter { it.owner === owner }
        }
        cancelled.filter { it.kind == Kind.WRITE }.forEach { t -> t.dispatch?.onNotSent(CLOSED_TEXT) }
        subs.forEach { it.close() }
        if (dropped > 0 || subs.isNotEmpty()) log("SdkLane: 닫힘으로 대기 작업 ${dropped}개 취소, 리스너 ${subs.size}개 정리")
    }

    /** Tasks counted against [capacity]. */
    private fun bounded() = queue.count { it.kind == Kind.READ || it.kind == Kind.WRITE || it.kind == Kind.SUBSCRIBE }

    private fun add(t: Task) {
        queue.addLast(t)
        hasWork.signalAll()
    }

    /** Worker: the next task allowed now. STOP first; during a motion nothing but STOP. */
    private fun take(): Task {
        lock.withLock {
            while (true) {
                val t = queue.firstOrNull { it.kind == Kind.STOP } ?: if (motionOwner == null) queue.firstOrNull() else null
                if (t != null) {
                    queue.remove(t)
                    current = t
                    return t
                }
                hasWork.await()
            }
        }
    }

    private fun runNext() {
        val t = take()
        try {
            when (t.kind) {
                Kind.READ -> when {
                    clock() > t.deadline -> if (t.state.compareAndSet(QUEUED, SKIPPED)) post { t.onSkipped?.invoke("SDK 대기 시간이 길어 조회하지 않았어요") }
                    t.state.compareAndSet(QUEUED, ENTERED) -> enter(t)
                }
                Kind.WRITE -> write(t)
                Kind.STOP, Kind.CLEANUP, Kind.SUBSCRIBE -> if (t.state.compareAndSet(QUEUED, ENTERED)) enter(t)
            }
        } finally {
            lock.withLock { current = null }
        }
    }

    /** Gate on main, onStart on main, then the SDK entry here only once onStart finished and nothing cancelled it. */
    private fun write(t: Task) {
        val d = t.dispatch ?: return
        if (!t.state.compareAndSet(QUEUED, GATING)) return
        val latch = CountDownLatch(1)
        t.gateLatch = latch
        post {
            try {
                if (!t.state.compareAndSet(GATING, GATE_RUNNING)) return@post // given up or cancelled: no gate, no start
                val why = runCatching { d.gate() }.getOrElse { "보내기 직전 확인에 실패했어요(${it.javaClass.simpleName})" }
                if (why != null) {
                    // Refused; also when the worker gave up while the gate ran. A close already reported it (CANCELLED).
                    if (t.state.compareAndSet(GATE_RUNNING, NOT_SENT) || t.state.compareAndSet(ABANDONED, NOT_SENT)) d.onNotSent(why)
                    return@post
                }
                if (!t.state.compareAndSet(GATE_RUNNING, STARTING)) {
                    // Given up while the gate ran (timeout, STOP or close): never started, so no onStart at all.
                    if (t.state.compareAndSet(ABANDONED, NOT_SENT)) d.onNotSent(t.cancelReason ?: LATE)
                    return@post
                }
                // Tracking and timers exist before the SDK entry, so even an at-once callback finds them.
                val started = runCatching { d.onStart() }
                when {
                    started.isFailure -> if (t.state.compareAndSet(STARTING, NOT_SENT) || t.state.compareAndSet(ABANDONED, NOT_SENT)) {
                        d.onNotSent("요청 준비에 실패했어요(${started.exceptionOrNull()?.javaClass?.simpleName})")
                    }
                    t.state.compareAndSet(STARTING, GRANTED) -> Unit
                    // Given up while onStart ran (timeout, STOP or close): onNotSent undoes what onStart started,
                    // exactly once, here, after onStart returned.
                    t.state.compareAndSet(ABANDONED, NOT_SENT) -> d.onNotSent(t.cancelReason ?: LATE)
                }
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(gateTimeoutMs, TimeUnit.MILLISECONDS)) {
            when {
                // The main thread never took the gate: not sent, reported once from here.
                t.state.compareAndSet(GATING, NOT_SENT) -> post { d.onNotSent(LATE) }
                // The gate or onStart is still running on main: give up; main reports it once it returns.
                t.state.compareAndSet(GATE_RUNNING, ABANDONED) || t.state.compareAndSet(STARTING, ABANDONED) -> Unit
            }
            if (t.state.get() != GRANTED) {
                log("SdkLane: #${t.id} ${t.label} 보내기 직전 확인이 ${gateTimeoutMs}ms 안에 끝나지 않아 보내지 않음")
                return
            }
        }
        // Only GRANTED (onStart finished) may enter; refused, abandoned or cancelled writes were reported once already.
        if (!t.state.compareAndSet(GRANTED, ENTERED)) return
        t.motionKey?.let { key ->
            lock.withLock {
                motionOwner = t.owner
                motionKey = key
            }
            log("SdkLane: 이동 예약 시작 $key")
        }
        enter(t)
    }

    /**
     * Logs every write, STOP, clean-up and subscription, but a read only when it is a capability or slow. An
     * unexpected throw is only logged: the call was entered, so its owner keeps its record and timeout.
     */
    private fun enter(t: Task) {
        val start = clock()
        val always = t.kind != Kind.READ || t.label.startsWith("capability")
        if (always) log("SdkLane: enter #${t.id} ${t.kind} ${t.label} on ${Thread.currentThread().name} at $start")
        try {
            t.block()
        } catch (e: Throwable) {
            log("SdkLane: #${t.id} ${t.label} threw ${e.javaClass.simpleName} (entered; result unknown)")
        }
        val took = clock() - start
        if (always || took >= SLOW_READ_MS) log("SdkLane: return #${t.id} ${t.kind} ${t.label} on ${Thread.currentThread().name} after ${took}ms")
    }

    companion object {
        const val WORKER_NAME = "cabin-sdk"
        const val GATE_TIMEOUT_MS = 2_000L
        /** Longer than every owner's read timeout: a skipped read was already given up by its owner. */
        const val READ_DEADLINE_MS = 8_000L
        const val CAPACITY = 64
        private const val SLOW_READ_MS = 300L
        private const val LATE = "보내기 직전 확인이 늦어 보내지 않았어요"
        private const val STOPPED = "STOP 요청으로 보내지 않았어요"
        private const val CLOSED_TEXT = "화면이 닫혀 보내지 않았어요"
        private const val QUEUED = 0
        /** Gate posted to main, not taken yet. */
        private const val GATING = 1
        /** The gate runs on main. */
        private const val GATE_RUNNING = 2
        /** onStart runs on main. */
        private const val STARTING = 3
        /** onStart finished: the worker may enter. */
        private const val GRANTED = 4
        private const val ENTERED = 5
        private const val NOT_SENT = 6
        private const val CANCELLED = 7
        private const val SKIPPED = 8
        /** The worker gave up while the gate or onStart ran; main ends it as not sent when that returns. */
        private const val ABANDONED = 9
        private const val CLOSED = "closed"
        private const val FULL = "full"
    }
}

/** A read through the lane; a skipped read (deadline, full queue) is reported as an error on main. */
internal fun SdkLane.readCall(owner: Any, label: String, err: (ProbeError) -> Unit, block: () -> Unit) =
    read(owner, label, { why -> err(ProbeError("SdkLaneSkipped", why, null)) }) { guarded(err, block) }

/** A gated write through the lane. [motionKey]: it may move the seat (released by endMotion with the same key). */
internal fun SdkLane.writeCall(owner: Any, label: String, dispatch: Dispatch, err: (ProbeError) -> Unit, motionKey: String? = null, block: () -> Unit) =
    write(owner, label, dispatch, motionKey) { guarded(err, block) }

/** Reported by a port when the same STOP already waits in the lane (that one answers; this one is not queued). */
internal val STOP_ALREADY_QUEUED = ProbeError("StopAlreadyQueued", "같은 STOP이 이미 대기 중이에요", null)

/** STOP through the lane: first in line, not gated. False: the same STOP already waits (see [SdkLane.stop]). */
internal fun SdkLane.stopCall(owner: Any, label: String, err: (ProbeError) -> Unit, block: () -> Unit): Boolean =
    stop(owner, label) { guarded(err, block) }

/** A synchronous throw inside the SDK call is reported like its onFailed callback, on main. */
internal fun SdkLane.guarded(err: (ProbeError) -> Unit, block: () -> Unit) {
    try {
        block()
    } catch (e: RuntimeException) {
        post { err(e.toProbeError()) }
    }
}
