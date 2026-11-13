package app.commacabin.seat

import app.commacabin.control.Move
import app.commacabin.control.SeatAxis
import app.commacabin.probe.Dispatch
import app.commacabin.probe.FakeScheduler
import app.commacabin.probe.ProbeError
import app.commacabin.sdk.STOP_ALREADY_QUEUED
import app.commacabin.sdk.SdkLane
import app.commacabin.sdk.readCall
import app.commacabin.sdk.stopCall
import app.commacabin.sdk.writeCall
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val WAIT_MS = 3_000L

/** Stand-in for the vehicle SDK: called on the lane's worker thread only; the test answers on main. */
private class LaneSdk {
    val calls = CopyOnWriteArrayList<String>()
    @Volatile var position = 20
    @Volatile var listener: ((Int?) -> Unit)? = null
    /** Keeps the worker inside the MOVE's SDK call until released. */
    @Volatile var moveBlock: CountDownLatch? = null
    val moveAnswers = LinkedBlockingQueue<(String?) -> Unit>()
    val stopAnswers = LinkedBlockingQueue<(String?) -> Unit>()
}

/**
 * Test-only port shaped like PleosControlPort: every SDK call goes through the real SdkLane (worker thread, gate on
 * main, STOP first, motion reservation). Not a production adapter; there is none in STEP 25.
 */
private class LanePort(private val lane: SdkLane, private val sdk: LaneSdk) : SeatAxisPort {
    private val owner = Any()

    override fun observe(axis: SeatAxis, onRegistered: (ProbeError?) -> Unit, onUpdate: (Int?) -> Unit, onError: (ProbeError) -> Unit): AutoCloseable =
        lane.subscribe(owner, "listener $axis", onRegistered) {
            sdk.calls += "register"
            sdk.listener = { v -> lane.post { onUpdate(v) } }
            AutoCloseable {
                sdk.calls += "unregister"
                sdk.listener = null
            }
        }

    override fun read(axis: SeatAxis, ok: (Int?) -> Unit, err: (ProbeError) -> Unit) =
        lane.readCall(owner, "read $axis", err) {
            sdk.calls += "read"
            val v = sdk.position
            lane.post { ok(v) }
        }

    override fun move(axis: SeatAxis, command: Move, motionKey: String, dispatch: Dispatch, ok: (echo: String?) -> Unit, err: (ProbeError) -> Unit) =
        lane.writeCall(owner, "move $axis $command", dispatch, err, motionKey) {
            sdk.calls += "move $command"
            sdk.moveAnswers += { v -> lane.post { ok(v) } }
            sdk.moveBlock?.await(WAIT_MS, TimeUnit.MILLISECONDS)
        }

    override fun stop(axis: SeatAxis, ok: (echo: String?) -> Unit, err: (ProbeError) -> Unit) {
        val queued = lane.stopCall(owner, "STOP $axis", err) {
            sdk.calls += "stop"
            sdk.stopAnswers += { v -> lane.post { ok(v) } }
        }
        if (!queued) lane.post { err(STOP_ALREADY_QUEUED) }
    }

    override fun endMotion(motionKey: String) = lane.endMotion(owner, motionKey)
}

class SeatMoveLaneTest {
    /** The test thread is the main thread: it runs what the lane posts here. */
    private val main = LinkedBlockingQueue<() -> Unit>()
    private val lane = SdkLane(
        post = { main.put(it) },
        log = {},
        clock = { System.nanoTime() / 1_000_000 },
        startWorker = { r -> Thread(r, "test-seat-sdk").apply { isDaemon = true }.start() },
    )
    private val sdk = LaneSdk()
    private val port = LanePort(lane, sdk)
    private val scheduler = FakeScheduler()
    private val journal = FakeJournal(mutableListOf())

    private fun runMainUntil(done: () -> Boolean): Boolean {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MS)
        while (!done()) {
            val left = end - System.nanoTime()
            if (left <= 0) return false
            main.poll(minOf(left, TimeUnit.MILLISECONDS.toNanos(10)), TimeUnit.NANOSECONDS)?.invoke()
        }
        return true
    }

    private fun runMainFor(ms: Long) {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms)
        while (true) {
            val left = end - System.nanoTime()
            if (left <= 0) return
            main.poll(left, TimeUnit.NANOSECONDS)?.invoke()
        }
    }

    private fun answer(queue: LinkedBlockingQueue<(String?) -> Unit>, echo: String) {
        val a = queue.poll(WAIT_MS, TimeUnit.MILLISECONDS)
        assertNotNull("SDK call entered", a)
        a!!(echo)
    }

    @Test
    fun `real SdkLane - nothing is read during the motion, and only a STOP after the MOVE answer releases it`() {
        val lim = LIMITS.copy(settleMs = 0, confirmIntervalMs = 0)
        val core = SeatMoveCore(SeatAxis.BACKREST, port, journal, scheduler, { null }, { null })
        sdk.position = 20
        assertNull(core.begin(req(start = 20, target = 60, limits = lim)))
        // The lane reserves the motion just before the worker enters the SDK call: wait for that call as well.
        assertTrue(runMainUntil { lane.motion != null && core.state.phase == SeatPhase.MOVING && sdk.calls.contains("move FORWARD") })
        assertEquals("move-1", lane.motion?.second)
        assertEquals(listOf("register", "read", "move FORWARD"), sdk.calls.toList())

        // Another read asked during the motion waits in the lane.
        var probed = false
        port.read(SeatAxis.BACKREST, { probed = true }, { probed = true })

        sdk.position = 59
        sdk.listener!!(59) // the listener still calls back during the motion
        assertTrue(runMainUntil { sdk.calls.count { it == "stop" } == 1 }) // STOP enters during the motion

        answer(sdk.stopAnswers, "STOP") // the STOP answers before the MOVE
        runMainFor(200)
        assertNotNull(lane.motion)
        assertFalse(probed)
        assertEquals(1, sdk.calls.count { it == "read" })
        assertEquals(SeatPhase.STOPPING, core.state.phase)

        answer(sdk.moveAnswers, "FORWARD") // the MOVE answers last: one more STOP enters, still during the motion
        assertTrue(runMainUntil { sdk.calls.count { it == "stop" } == 2 })
        runMainFor(100)
        assertNotNull(lane.motion)
        assertFalse(probed)

        answer(sdk.stopAnswers, "STOP")
        assertTrue(runMainUntil { lane.motion == null && probed }) // released: the waiting read runs now
        scheduler.advance(0) // settle: the first confirming read
        assertTrue(runMainUntil { core.state.reads.size == 1 })
        scheduler.advance(0)
        assertTrue(runMainUntil { core.state.outcome != null })
        assertEquals(SeatOutcome.CONFIRMED, core.state.outcome)

        val calls = sdk.calls.toList()
        assertEquals(listOf("register", "read", "move FORWARD", "stop", "stop"), calls.take(5))
        assertEquals(3, calls.drop(5).count { it == "read" }) // the waiting read and two confirming reads, all after the release
    }

    @Test
    fun `real SdkLane - a safety STOP refused as already queued is an API failure, not a stop, and releases nothing`() {
        val lim = LIMITS.copy(settleMs = 0, confirmReads = 1)
        val core = SeatMoveCore(SeatAxis.BACKREST, port, journal, scheduler, { null }, { null })
        sdk.position = 20
        val release = CountDownLatch(1)
        sdk.moveBlock = release // the MOVE's SDK call keeps the worker busy
        assertNull(core.begin(req(start = 20, target = 60, limits = lim)))
        assertTrue(runMainUntil { sdk.calls.contains("move FORWARD") && core.state.phase == SeatPhase.MOVING })

        sdk.position = 59
        sdk.listener!!(59) // STOP1 waits in the lane behind the running MOVE call
        assertTrue(runMainUntil { core.state.snapshot?.stops?.size == 1 })
        assertNull(core.interrupt("조수석 문 열림", "stop-door")) // handed to the port, which refuses it
        assertTrue(runMainUntil { core.state.snapshot?.stops?.getOrNull(1)?.state == SeatRequestState.FAILED })
        assertTrue(core.state.snapshot!!.stops[1].answer!!.contains("StopAlreadyQueued"))
        assertEquals(0, sdk.calls.count { it == "stop" })
        assertNotNull(core.state.lock)

        release.countDown()
        assertTrue(runMainUntil { sdk.calls.count { it == "stop" } == 1 }) // the waiting STOP1 enters
        answer(sdk.moveAnswers, "FORWARD") // the MOVE answers: one more STOP
        assertTrue(runMainUntil { sdk.calls.count { it == "stop" } == 2 })
        answer(sdk.stopAnswers, "STOP")
        answer(sdk.stopAnswers, "STOP")
        assertTrue(runMainUntil { lane.motion == null })
        scheduler.advance(0)
        assertTrue(runMainUntil { core.state.outcome != null })
        assertEquals(SeatOutcome.HELD, core.state.outcome)
        assertEquals(
            listOf(SeatRequestState.COMPLETED, SeatRequestState.FAILED, SeatRequestState.COMPLETED),
            core.state.snapshot!!.stops.map { it.state },
        )
    }
}
