package app.commacabin.sdk

import app.commacabin.probe.Dispatch
import app.commacabin.probe.ProbeError
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every wait in this file is bounded by this, so a broken lane fails a test instead of hanging it. */
private const val WAIT_MS = 3_000L
private const val WORKER = "test-sdk"

private class Owner(private val name: String) {
    override fun toString() = name
}

private val A = Owner("A")
private val B = Owner("B")
private val FLUSH = Owner("flush")
private val BLOCKER = Owner("blocker")

private fun eventually(done: () -> Boolean): Boolean {
    val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MS)
    while (!done()) {
        if (System.nanoTime() > end) return false
        Thread.sleep(5)
    }
    return true
}

/** One gated write: what its dispatch saw. Every callback is recorded in [Rig.events] in order. */
private class Probe(rig: Rig, label: String, gate: () -> String?, start: () -> Unit) {
    val gates = AtomicInteger()
    val starts = AtomicInteger()
    val sdk = AtomicInteger()
    val notSent = CopyOnWriteArrayList<String>()
    val dispatch = Dispatch(
        gate = {
            gates.incrementAndGet()
            rig.events += "gate:$label"
            gate()
        },
        onStart = {
            starts.incrementAndGet()
            rig.events += "start:$label"
            start()
            rig.events += "started:$label"
        },
        onNotSent = { why ->
            notSent += why
            rig.events += "notSent:$label"
        },
    )
}

/** A lane with a real worker thread. The test thread is the main thread: it runs what the lane posts to [main]. */
private class Rig(gateTimeoutMs: Long = 1_000, readDeadlineMs: Long = 8_000, capacity: Int = 64) {
    val main = LinkedBlockingQueue<() -> Unit>()
    val logs = CopyOnWriteArrayList<String>()
    val events = CopyOnWriteArrayList<String>()
    val clock = AtomicLong(1_000)
    val lane = SdkLane(
        post = { main.put(it) },
        log = { logs += it },
        clock = { clock.get() },
        gateTimeoutMs = gateTimeoutMs,
        readDeadlineMs = readDeadlineMs,
        capacity = capacity,
        startWorker = { r -> Thread(r, WORKER).apply { isDaemon = true }.start() },
    )

    /** Runs main messages until [done]; false when [WAIT_MS] passed first. */
    fun runMainUntil(done: () -> Boolean): Boolean {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MS)
        while (!done()) {
            val left = end - System.nanoTime()
            if (left <= 0) return false
            main.poll(minOf(left, TimeUnit.MILLISECONDS.toNanos(10)), TimeUnit.NANOSECONDS)?.invoke()
        }
        return true
    }

    /** Runs main messages for [ms]: to show that something does not happen meanwhile. */
    fun runMainFor(ms: Long) {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms)
        while (true) {
            val left = end - System.nanoTime()
            if (left <= 0) return
            main.poll(left, TimeUnit.NANOSECONDS)?.invoke()
        }
    }

    /** Runs what is already on main, without waiting. */
    fun runQueuedMain() {
        while (true) (main.poll() ?: return).invoke()
    }

    /** Waits until every task queued before this point is done (main keeps running). Not during a motion. */
    fun flush() {
        val done = AtomicBoolean(false)
        lane.read(FLUSH, "flush", {}) { done.set(true) }
        assertTrue("lane drained", runMainUntil { done.get() })
        runQueuedMain()
    }

    /** Occupies the worker with a blocked SDK read until the returned latch is released. */
    fun blockWorker(): CountDownLatch {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        lane.read(BLOCKER, "blocker", {}) {
            entered.countDown()
            release.await(WAIT_MS, TimeUnit.MILLISECONDS)
        }
        assertTrue("worker blocked", entered.await(WAIT_MS, TimeUnit.MILLISECONDS))
        return release
    }

    fun write(
        owner: Any,
        label: String,
        gate: () -> String? = { null },
        start: () -> Unit = {},
        motionKey: String? = null,
        sdk: () -> Unit = {},
    ): Probe {
        val p = Probe(this, label, gate, start)
        lane.write(owner, label, p.dispatch, motionKey) {
            p.sdk.incrementAndGet()
            events += "sdk:$label"
            sdk()
        }
        return p
    }
}

class SdkLaneTest {

    @Test
    fun blockedSdkReadLeavesTheMainThreadRunning() {
        val rig = Rig()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sdkThread = AtomicReference<String>()
        val before = System.nanoTime()
        rig.lane.read(A, "read slow", {}) {
            sdkThread.set(Thread.currentThread().name)
            entered.countDown()
            release.await(WAIT_MS, TimeUnit.MILLISECONDS)
        }
        val tookMs = (System.nanoTime() - before) / 1_000_000
        assertTrue("read() returns at once (${tookMs}ms)", tookMs < 200)
        assertTrue(entered.await(WAIT_MS, TimeUnit.MILLISECONDS))

        // A main-thread timeout still fires while the SDK call is blocked.
        var timedOut = false
        rig.main.put { timedOut = true }
        assertTrue(rig.runMainUntil { timedOut })
        assertEquals(1L, release.count)

        // One SDK entry at a time: the next read waits for the blocked one.
        val next = AtomicInteger()
        rig.lane.read(A, "read next", {}) { next.incrementAndGet() }
        rig.runMainFor(100)
        assertEquals(0, next.get())
        release.countDown()
        rig.flush()
        assertEquals(1, next.get())
        assertEquals(WORKER, sdkThread.get())
        assertNotEquals(Thread.currentThread().name, sdkThread.get())
    }

    @Test
    fun refusedGateNeverEntersTheSdk() {
        val rig = Rig()
        val p = rig.write(A, "w", gate = { "P가 아니에요" })
        rig.flush()
        assertEquals(listOf("P가 아니에요"), p.notSent.toList())
        assertEquals(1, p.gates.get())
        assertEquals(0, p.starts.get())
        assertEquals(0, p.sdk.get())
    }

    @Test
    fun throwingGateOrStartIsNotSentOnce() {
        val rig = Rig()
        val gate = rig.write(A, "gate throws", gate = { throw IllegalStateException("x") })
        val start = rig.write(A, "start throws", start = { throw IllegalStateException("y") })
        rig.flush()
        assertEquals(1, gate.notSent.size)
        assertTrue(gate.notSent[0], gate.notSent[0].contains("확인에 실패"))
        assertEquals(0, gate.starts.get())
        assertEquals(0, gate.sdk.get())
        assertEquals(1, start.notSent.size)
        assertTrue(start.notSent[0], start.notSent[0].contains("준비에 실패"))
        assertEquals(1, start.starts.get())
        assertEquals(0, start.sdk.get())
    }

    @Test
    fun lateGateMessageAfterTheGateTimeoutIsNotSentOnceAndNeverEnters() {
        val rig = Rig(gateTimeoutMs = 100)
        val p = rig.write(A, "w")
        // The main thread is busy (not drained) for longer than the gate timeout.
        assertTrue(eventually { rig.logs.any { it.contains("w 보내기 직전 확인이") } })
        assertEquals("the late gate message and the worker's not-sent report", 2, rig.main.size)
        rig.runQueuedMain()
        rig.flush()
        rig.runMainFor(50)
        assertEquals(listOf("보내기 직전 확인이 늦어 보내지 않았어요"), p.notSent.toList())
        assertEquals(0, p.gates.get())
        assertEquals(0, p.starts.get())
        assertEquals(0, p.sdk.get())

        // The lane goes on normally.
        val next = rig.write(A, "next")
        rig.flush()
        assertEquals(1, next.sdk.get())
        assertTrue(next.notSent.isEmpty())
    }

    @Test
    fun startStillRunningAtTheGateTimeoutIsNotSentOnceAndNeverRevived() {
        val rig = Rig(gateTimeoutMs = 500)
        var gaveUpSeen = false
        val p = rig.write(A, "w", start = {
            // onStart (main) outlives the gate timeout: the worker gives up meanwhile.
            gaveUpSeen = eventually { rig.logs.any { it.contains("w 보내기 직전 확인이") } }
        })
        assertTrue(rig.runMainUntil { p.notSent.isNotEmpty() })
        rig.flush()
        rig.runMainFor(100)
        assertTrue(gaveUpSeen)
        assertEquals(listOf("보내기 직전 확인이 늦어 보내지 않았어요"), p.notSent.toList())
        assertEquals(1, p.gates.get())
        assertEquals(1, p.starts.get())
        assertEquals(0, p.sdk.get())
        assertTrue(rig.events.indexOf("started:w") < rig.events.indexOf("notSent:w"))
    }

    /** The gate (main) outlives the gate timeout, then ends with [end]: never started, never entered, not sent once. */
    private fun gateOutlivesTheTimeout(end: () -> String?, expected: String) {
        val rig = Rig(gateTimeoutMs = 100)
        var gaveUpSeen = false
        val p = rig.write(A, "w", gate = {
            gaveUpSeen = eventually { rig.logs.any { it.contains("w 보내기 직전 확인이") } }
            end()
        })
        assertTrue(rig.runMainUntil { p.notSent.isNotEmpty() })
        rig.flush()
        rig.runMainFor(100)
        assertTrue(gaveUpSeen)
        assertEquals(listOf(expected), p.notSent.toList())
        assertEquals(1, p.gates.get())
        assertEquals("no late onStart for a write given up during its gate", 0, p.starts.get())
        assertEquals(0, p.sdk.get())

        val next = rig.write(A, "next")
        rig.flush()
        assertEquals(1, next.sdk.get())
    }

    @Test
    fun gateOutlivingTheTimeoutThenAllowingIsNotSentOnceWithoutStart() =
        gateOutlivesTheTimeout({ null }, "보내기 직전 확인이 늦어 보내지 않았어요")

    @Test
    fun gateOutlivingTheTimeoutThenRefusingIsNotSentOnceWithItsReason() =
        gateOutlivesTheTimeout({ "P가 아니에요" }, "P가 아니에요")

    @Test
    fun gateOutlivingTheTimeoutThenThrowingIsNotSentOnce() =
        gateOutlivesTheTimeout({ throw IllegalStateException("x") }, "보내기 직전 확인에 실패했어요(IllegalStateException)")

    @Test
    fun startOutlivingTheTimeoutThenThrowingIsNotSentOnce() {
        val rig = Rig(gateTimeoutMs = 100)
        val p = rig.write(A, "w", start = {
            eventually { rig.logs.any { it.contains("w 보내기 직전 확인이") } }
            throw IllegalStateException("y")
        })
        assertTrue(rig.runMainUntil { p.notSent.isNotEmpty() })
        rig.flush()
        rig.runMainFor(100)
        assertEquals(listOf("요청 준비에 실패했어요(IllegalStateException)"), p.notSent.toList())
        assertEquals(1, p.starts.get())
        assertEquals(0, p.sdk.get())
    }

    @Test
    fun closingOneOwnerLeavesAnotherOwnersQueuedWriteGoing() {
        val rig = Rig()
        val release = rig.blockWorker()
        val screen = rig.write(A, "screen write")
        val restore = rig.write(B, "product restore")
        rig.lane.close(A)
        release.countDown()
        rig.flush()
        assertEquals(listOf("화면이 닫혀 보내지 않았어요"), screen.notSent.toList())
        assertEquals(0, screen.sdk.get())
        assertTrue(restore.notSent.isEmpty())
        assertEquals(1, restore.gates.get())
        assertEquals(1, restore.sdk.get())
    }

    @Test
    fun sdkEntryWaitsForStartAndAnImmediateCallbackComesAfterIt() {
        val rig = Rig()
        val p = rig.write(A, "w", start = { Thread.sleep(50) }, sdk = { rig.lane.post { rig.events += "callback:w" } })
        assertTrue(rig.runMainUntil { "callback:w" in rig.events })
        assertEquals(listOf("gate:w", "start:w", "started:w", "sdk:w", "callback:w"), rig.events.toList())
        assertTrue(p.notSent.isEmpty())
    }

    @Test
    fun closeCancelsTheOwnersQueuedTasksAndRefusesNewOnes() {
        val rig = Rig()
        val release = rig.blockWorker()
        val reads = AtomicInteger()
        val skipped = AtomicInteger()
        val registers = AtomicInteger()
        val others = AtomicInteger()
        rig.lane.read(A, "read", { skipped.incrementAndGet() }) { reads.incrementAndGet() }
        rig.lane.subscribe(A, "listener", {}) { registers.incrementAndGet(); AutoCloseable {} }
        val queued = rig.write(A, "queued")
        rig.lane.read(B, "other owner", {}) { others.incrementAndGet() }

        rig.lane.close(A)
        // Reported before close returns, without waiting for the worker.
        assertEquals(listOf("화면이 닫혀 보내지 않았어요"), queued.notSent.toList())

        // New tasks of the closed owner (the write ungated): refused at once.
        rig.lane.read(A, "read after", { skipped.incrementAndGet() }) { reads.incrementAndGet() }
        rig.lane.subscribe(A, "listener after", {}) { registers.incrementAndGet(); AutoCloseable {} }
        val after = rig.write(A, "after")
        assertEquals(listOf("화면이 닫혀 보내지 않았어요"), after.notSent.toList())

        release.countDown()
        rig.flush()
        rig.runMainFor(50)
        assertEquals(0, reads.get())
        assertEquals(0, skipped.get())
        assertEquals(0, registers.get())
        assertEquals(0, queued.gates.get() + queued.starts.get() + queued.sdk.get())
        assertEquals(0, after.gates.get() + after.starts.get() + after.sdk.get())
        assertEquals(1, queued.notSent.size)
        assertEquals(1, after.notSent.size)
        assertEquals(1, others.get())
    }

    @Test
    fun closeWhileTheWorkerWaitsForTheGateIsNotSentOnceAndTheLateGateDoesNothing() {
        val rig = Rig(gateTimeoutMs = 10_000)
        val w = rig.write(A, "w")
        // The worker took the write and posted its gate; the main thread has not run it yet.
        assertTrue(eventually { rig.main.size == 1 })
        rig.lane.close(A)
        assertEquals(listOf("화면이 닫혀 보내지 않았어요"), w.notSent.toList())

        // close released the worker at once (main not drained, the gate timeout is 10 s).
        val read = AtomicInteger()
        rig.lane.read(B, "read", {}) { read.incrementAndGet() }
        assertTrue(eventually { read.get() == 1 })

        rig.runQueuedMain() // the late gate message
        rig.flush()
        assertEquals(0, w.gates.get())
        assertEquals(0, w.starts.get())
        assertEquals(0, w.sdk.get())
        assertEquals(1, w.notSent.size)
    }

    @Test
    fun stopGoesFirstAndTheSameStopIsQueuedOnce() {
        val rig = Rig()
        val release = rig.blockWorker()
        rig.lane.read(A, "read", {}) { rig.events += "sdk:read" }
        rig.write(A, "move")
        assertTrue(rig.lane.stop(A, "STOP X") { rig.events += "sdk:STOP X" })
        assertFalse(rig.lane.stop(A, "STOP X") { rig.events += "sdk:STOP X again" })
        assertTrue(rig.lane.stop(A, "STOP Y") { rig.events += "sdk:STOP Y" })
        assertTrue(rig.lane.stop(B, "STOP X") { rig.events += "sdk:B STOP X" })
        release.countDown()
        rig.flush()
        assertEquals(
            listOf("sdk:STOP X", "sdk:STOP Y", "sdk:B STOP X", "sdk:read", "gate:move", "start:move", "started:move", "sdk:move"),
            rig.events.toList(),
        )
        // Once it ran, the same STOP can be queued again.
        assertTrue(rig.lane.stop(A, "STOP X") { rig.events += "sdk:STOP X later" })
        rig.flush()
        assertEquals("sdk:STOP X later", rig.events.last())
    }

    @Test
    fun stopWhileAMoveWaitsGivesTheMoveUpItselfAndRunsAlone() {
        val rig = Rig()
        val release = rig.blockWorker()
        val move = rig.write(A, "move", motionKey = "m1")
        val stops = AtomicInteger()
        rig.lane.stop(A, "STOP") { stops.incrementAndGet(); rig.events += "sdk:STOP" }
        assertEquals(listOf("STOP 요청으로 보내지 않았어요"), move.notSent.toList()) // no gate needed to refuse it
        release.countDown()
        rig.flush()
        assertEquals(1, stops.get())
        assertEquals(0, move.gates.get() + move.starts.get() + move.sdk.get())
        assertEquals(1, move.notSent.size)
        assertEquals(listOf("sdk:STOP"), rig.events.filter { it.startsWith("sdk:") })
        assertNull(rig.lane.motion)
    }

    @Test
    fun closeKeepsTheOwnersStopsAndDropsItsMove() {
        val rig = Rig()
        val release = rig.blockWorker()
        val move = rig.write(A, "move", motionKey = "m1")
        val stops = AtomicInteger()
        assertTrue(rig.lane.stop(A, "STOP") { stops.incrementAndGet() })
        rig.lane.close(A)
        assertTrue("a clean-up STOP after close is accepted", rig.lane.stop(A, "STOP other axis") { stops.incrementAndGet() })
        release.countDown()
        rig.flush()
        assertEquals(2, stops.get())
        assertEquals(0, move.sdk.get())
        assertEquals(listOf("STOP 요청으로 보내지 않았어요"), move.notSent.toList()) // given up by the STOP, once
        assertNull(rig.lane.motion)
    }

    @Test
    fun duringAMotionOnlyStopEntersAndOnlyThatMotionsKeyReleasesIt() {
        val rig = Rig()
        val unregistered = AtomicInteger()
        val listener = rig.lane.subscribe(B, "door listener", {}) { AutoCloseable { unregistered.incrementAndGet() } }
        rig.flush()

        val move1 = rig.write(A, "move 1", motionKey = "m1")
        assertTrue(rig.runMainUntil { move1.sdk.get() == 1 })
        assertEquals(A to "m1", rig.lane.motion)

        val reads = AtomicInteger()
        rig.lane.read(B, "seat position", {}) { reads.incrementAndGet() }
        listener.close() // its removal is an SDK call too: it waits
        val light = rig.write(B, "light")
        rig.runMainFor(200)
        assertEquals(0, reads.get())
        assertEquals(0, unregistered.get())
        assertEquals(0, light.gates.get())

        val stops = AtomicInteger()
        rig.lane.stop(A, "STOP backrest") { stops.incrementAndGet() }
        assertTrue(rig.runMainUntil { stops.get() == 1 })
        rig.runMainFor(100)
        assertEquals("the STOP alone does not release the motion", 0, reads.get())
        rig.lane.endMotion(B, "m1") // not its owner
        rig.lane.endMotion(A, "m0") // not its key
        assertEquals(A to "m1", rig.lane.motion)
        rig.runMainFor(100)
        assertEquals(0, reads.get())

        rig.lane.endMotion(A, "m1")
        assertNull(rig.lane.motion)
        val move2 = rig.write(A, "move 2", motionKey = "m2")
        assertTrue(rig.runMainUntil { move2.sdk.get() == 1 })
        assertEquals(1, reads.get())
        assertEquals(1, unregistered.get())
        assertEquals(1, light.sdk.get())
        assertEquals(A to "m2", rig.lane.motion)

        // A late answer of the first motion's STOP must not release the second motion.
        rig.lane.endMotion(A, "m1")
        assertEquals(A to "m2", rig.lane.motion)
        rig.lane.read(B, "seat position 2", {}) { reads.incrementAndGet() }
        rig.runMainFor(150)
        assertEquals(1, reads.get())

        rig.lane.endMotion(A, "m2")
        assertNull(rig.lane.motion)
        rig.flush()
        assertEquals(2, reads.get())
    }

    @Test
    fun subscriptionClosedBeforeItsTurnNeverRegisters() {
        val rig = Rig()
        val release = rig.blockWorker()
        val registers = AtomicInteger()
        val unregisters = AtomicInteger()
        val handle = rig.lane.subscribe(A, "listener", {}) {
            registers.incrementAndGet()
            AutoCloseable { unregisters.incrementAndGet() }
        }
        handle.close()
        release.countDown()
        rig.flush()
        assertEquals(0, registers.get())
        assertEquals(0, unregisters.get())
    }

    @Test
    fun subscriptionClosedByItsHandleWhileRegisteringIsRemovedExactlyOnce() =
        closeWhileRegistering { rig, handle ->
            handle.close()
            rig.lane.close(A)
        }

    @Test
    fun subscriptionClosedByItsOwnerWhileRegisteringIsRemovedExactlyOnce() =
        closeWhileRegistering { rig, handle ->
            rig.lane.close(A)
            handle.close()
        }

    private fun closeWhileRegistering(close: (Rig, AutoCloseable) -> Unit) {
        val rig = Rig()
        val registering = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val unregisters = AtomicInteger()
        val unregisterThread = AtomicReference<String>()
        val handle = rig.lane.subscribe(A, "listener", {}) {
            registering.countDown()
            finish.await(WAIT_MS, TimeUnit.MILLISECONDS)
            AutoCloseable {
                unregisterThread.set(Thread.currentThread().name)
                unregisters.incrementAndGet()
            }
        }
        assertTrue(registering.await(WAIT_MS, TimeUnit.MILLISECONDS))
        close(rig, handle)
        finish.countDown()
        rig.flush()
        handle.close()
        rig.flush()
        assertEquals(1, unregisters.get())
        assertEquals(WORKER, unregisterThread.get())
    }

    @Test
    fun registeredSubscriptionIsRemovedOnceByItsHandleOrByItsOwnersClose() {
        val rig = Rig()
        val first = AtomicInteger()
        val second = AtomicInteger()
        val one = rig.lane.subscribe(A, "one", {}) { AutoCloseable { first.incrementAndGet() } }
        val two = rig.lane.subscribe(A, "two", {}) { AutoCloseable { second.incrementAndGet() } }
        rig.flush()

        one.close()
        one.close()
        rig.flush()
        assertEquals(1, first.get())
        assertEquals(0, second.get())

        rig.lane.close(A) // removes the one still registered
        rig.flush()
        two.close()
        rig.flush()
        assertEquals(1, first.get())
        assertEquals(1, second.get())
    }

    @Test
    fun subscriptionReportsOnlyANormalRegistrationAsRegistered() {
        val rig = Rig()
        val results = CopyOnWriteArrayList<String>()
        fun record(name: String): (ProbeError?) -> Unit = { e -> results += "$name:${e?.type ?: "ok"}" }
        rig.lane.subscribe(A, "ok", record("ok")) { AutoCloseable {} }
        rig.lane.subscribe(A, "throws", record("throws")) { throw SecurityException("denied") }
        rig.lane.subscribe(A, "nothing", record("nothing")) { null }
        rig.flush()
        assertEquals(listOf("ok:ok", "throws:java.lang.SecurityException", "nothing:ListenerNotRegistered"), results.toList())

        // Queue full: refused and reported. A closed owner: refused, no result.
        val full = Rig(capacity = 1)
        val release = full.blockWorker()
        val filled = AtomicBoolean(false)
        full.lane.read(A, "fills the queue", {}) { filled.set(true) }
        full.lane.subscribe(A, "full", record("full")) { AutoCloseable {} }
        full.lane.close(B)
        full.lane.subscribe(B, "closed", record("closed")) { AutoCloseable {} }
        assertTrue(full.runMainUntil { results.size == 4 })
        release.countDown()
        assertTrue(eventually { filled.get() })
        full.flush()
        full.runMainFor(50)
        assertEquals("full:SdkLaneSkipped", results[3])
        assertEquals(4, results.size)
    }

    @Test
    fun fullQueueRefusesReadsWritesAndListenersButNotStop() {
        val rig = Rig(capacity = 2)
        val release = rig.blockWorker()
        val reads = AtomicInteger()
        val skipped = CopyOnWriteArrayList<String>()
        val registers = AtomicInteger()
        rig.lane.read(A, "read 1", { skipped += it }) { reads.incrementAndGet() }
        val w1 = rig.write(A, "w1")

        val w2 = rig.write(A, "w2")
        assertEquals(listOf("SDK 대기열이 가득 차 보내지 않았어요"), w2.notSent.toList())
        rig.lane.read(A, "read 2", { skipped += it }) { reads.incrementAndGet() }
        rig.lane.subscribe(A, "listener", {}) { registers.incrementAndGet(); AutoCloseable {} }
        val stops = AtomicInteger()
        assertTrue(rig.lane.stop(A, "STOP") { stops.incrementAndGet(); rig.events += "sdk:STOP" })
        assertTrue(rig.runMainUntil { skipped.isNotEmpty() })

        release.countDown()
        assertTrue(rig.runMainUntil { w1.sdk.get() == 1 })
        rig.flush()
        assertEquals(listOf("SDK 대기열이 가득 차 조회하지 않았어요"), skipped.toList())
        assertEquals(1, reads.get())
        assertEquals(0, registers.get())
        assertEquals(0, w2.gates.get() + w2.starts.get() + w2.sdk.get())
        assertEquals(1, w2.notSent.size)
        assertEquals(1, stops.get())
        // SDK entries only (w2's refusal is reported synchronously, before any entry): STOP before the waiting write.
        assertEquals(listOf("sdk:STOP", "sdk:w1"), rig.events.filter { it.startsWith("sdk:") })
    }

    @Test
    fun readWhoseDeadlinePassedBeforeItsTurnIsSkipped() {
        val rig = Rig(readDeadlineMs = 1_000)
        val release = rig.blockWorker()
        val reads = AtomicInteger()
        val skipped = CopyOnWriteArrayList<String>()
        rig.lane.read(A, "read", { skipped += it }) { reads.incrementAndGet() }
        rig.clock.addAndGet(1_001)
        release.countDown()
        rig.flush()
        assertEquals(0, reads.get())
        assertEquals(listOf("SDK 대기 시간이 길어 조회하지 않았어요"), skipped.toList())
    }

    @Test
    fun throwingSdkCallIsLoggedAndNotReportedAsNotSent() {
        val rig = Rig()
        val w = rig.write(A, "w", sdk = { throw IllegalStateException("boom") })
        rig.flush()
        assertEquals(1, w.starts.get())
        assertEquals(1, w.sdk.get())
        assertTrue(w.notSent.isEmpty())
        assertTrue(rig.logs.any { it.contains("w threw IllegalStateException") && it.contains("result unknown") })

        // The worker survives.
        val next = rig.write(A, "next")
        rig.flush()
        assertEquals(1, next.sdk.get())
    }

    @Test
    fun writeCallReportsASynchronousThrowAsAnErrorNotAsNotSent() {
        val rig = Rig()
        val errors = CopyOnWriteArrayList<ProbeError>()
        val p = Probe(rig, "w", { null }, {})
        rig.lane.writeCall(A, "w", p.dispatch, { errors += it }) { throw SecurityException("denied") }
        rig.flush()
        assertEquals(1, errors.size)
        assertEquals("java.lang.SecurityException", errors[0].type)
        assertEquals(1, p.starts.get())
        assertTrue(p.notSent.isEmpty())
    }

    // --- a STOP gives up the owner's motion writes that have not entered the SDK ---

    private val stopped = "STOP 요청으로 보내지 않았어요"

    /** The order the main thread saw (the worker's SDK entries are checked separately). */
    private fun Rig.mainEvents() = events.filter { !it.startsWith("sdk:") }

    @Test
    fun `stop requested during the move's onStart - the move never enters, not sent once after onStart, STOP sent`() {
        val rig = Rig()
        var p: Probe? = null
        p = rig.write(A, "move", motionKey = "m1", start = {
            rig.events += "stop requested"
            assertTrue(rig.lane.stop(A, "STOP") { rig.events += "sdk:STOP" })
        })
        assertTrue(rig.runMainUntil { "sdk:STOP" in rig.events && p.notSent.isNotEmpty() })
        rig.flush()
        rig.runMainFor(50)
        assertEquals(listOf("gate:move", "start:move", "stop requested", "started:move", "notSent:move"), rig.mainEvents())
        assertEquals(listOf(stopped), p.notSent.toList())
        assertEquals(0, p.sdk.get())
        assertEquals(1, rig.events.count { it == "sdk:STOP" })
        assertNull(rig.lane.motion)
    }

    @Test
    fun `stop requested during the move's gate - no onStart, not sent once (the gate's own refusal wins its text)`() {
        for (refuse in listOf(false, true)) {
            val rig = Rig()
            var p: Probe? = null
            p = rig.write(A, "move", motionKey = "m1", gate = {
                rig.lane.stop(A, "STOP") { rig.events += "sdk:STOP" }
                if (refuse) "P가 아니에요" else null
            })
            assertTrue(rig.runMainUntil { "sdk:STOP" in rig.events && p.notSent.isNotEmpty() })
            rig.flush()
            rig.runMainFor(50)
            assertEquals(listOf(if (refuse) "P가 아니에요" else stopped), p.notSent.toList())
            assertEquals(0, p.starts.get())
            assertEquals(0, p.sdk.get())
            assertNull(rig.lane.motion)
        }
    }

    @Test
    fun `stop while the taken move waits for its gate - not sent at once, the late gate does nothing`() {
        val rig = Rig(gateTimeoutMs = 10_000)
        val p = rig.write(A, "move", motionKey = "m1")
        assertTrue(eventually { rig.main.size == 1 }) // the gate message, not run yet
        val stops = AtomicInteger()
        assertTrue(rig.lane.stop(A, "STOP") { stops.incrementAndGet() })
        assertEquals(listOf(stopped), p.notSent.toList()) // reported before stop() returned
        assertTrue(eventually { stops.get() == 1 }) // the worker did not wait for the gate timeout
        rig.runQueuedMain()
        rig.flush()
        assertEquals(0, p.gates.get() + p.starts.get() + p.sdk.get())
        assertEquals(1, p.notSent.size)
        assertNull(rig.lane.motion)
    }

    @Test
    fun `stop gives up the owner's waiting moves only - its other writes and other owners' moves go on`() {
        val rig = Rig()
        val release = rig.blockWorker()
        val move = rig.write(A, "move", motionKey = "m1")
        val light = rig.write(A, "light")
        val otherMove = rig.write(B, "other move", motionKey = "b1")
        rig.lane.stop(A, "STOP") { rig.events += "sdk:STOP" }
        assertEquals(listOf(stopped), move.notSent.toList())
        release.countDown()
        assertTrue(rig.runMainUntil { otherMove.sdk.get() == 1 })
        rig.lane.endMotion(B, "b1")
        rig.flush()
        assertEquals(listOf("sdk:STOP", "sdk:light", "sdk:other move"), rig.events.filter { it.startsWith("sdk:") })
        assertEquals(0, move.sdk.get())
        assertTrue(light.notSent.isEmpty())
    }

    @Test
    fun `stop after the move entered the SDK - no false not-sent, the STOP follows first and the motion stays until its key`() {
        val rig = Rig()
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val move = rig.write(A, "move", motionKey = "m1", sdk = {
            entered.countDown()
            finish.await(WAIT_MS, TimeUnit.MILLISECONDS)
        })
        assertTrue(rig.runMainUntil { entered.count == 0L })
        rig.write(B, "read-like write") // waits behind the motion
        assertTrue(rig.lane.stop(A, "STOP") { rig.events += "sdk:STOP" })
        assertTrue(move.notSent.isEmpty())
        finish.countDown()
        assertTrue(rig.runMainUntil { "sdk:STOP" in rig.events })
        rig.runMainFor(100)
        assertEquals(listOf("sdk:move", "sdk:STOP"), rig.events.filter { it.startsWith("sdk:") })
        assertEquals(A to "m1", rig.lane.motion)
        rig.lane.endMotion(A, "m1")
        rig.flush()
        assertTrue(move.notSent.isEmpty())
        assertEquals(1, move.sdk.get())
    }

    @Test
    fun `close during onStart - not sent once after onStart returns, never entered`() {
        val rig = Rig()
        var p: Probe? = null
        p = rig.write(A, "w", start = { rig.lane.close(A) })
        assertTrue(rig.runMainUntil { p.notSent.isNotEmpty() })
        rig.flush()
        rig.runMainFor(50)
        assertEquals(listOf("화면이 닫혀 보내지 않았어요"), p.notSent.toList())
        assertTrue(rig.events.indexOf("started:w") < rig.events.indexOf("notSent:w"))
        assertEquals(0, p.sdk.get())
    }
}
