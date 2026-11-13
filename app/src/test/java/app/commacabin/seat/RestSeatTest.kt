package app.commacabin.seat

import app.commacabin.control.Move
import app.commacabin.control.SeatAxis
import app.commacabin.probe.Access
import app.commacabin.probe.FakeScheduler
import app.commacabin.probe.GearState
import app.commacabin.probe.ParkingState
import app.commacabin.probe.SDK_ERROR
import app.commacabin.rest.EndReason
import app.commacabin.support.AreaBound
import app.commacabin.support.CapabilityResult
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Test fixture only: these values are not measured anywhere and never reach the product. */
private val ENV = SeatEnvironment(Move.FORWARD, reclineDelta = 40, tolerance = 2, noChangeMs = 500, maxMoveMs = 5_000, answerMs = 3_000, settleMs = 300, confirmReads = 2, confirmIntervalMs = 200)
private val PARKED = ParkingState(GearState.PARK, false)
private val DRIVING = ParkingState(GearState.NOT_PARK, false)

private class FakeCapability : SeatCapabilityQuery {
    var range: IntRange? = 0..100
    var hold = false
    var calls = 0
    val held = mutableListOf<Pair<(IntRange) -> Unit, (String) -> Unit>>()

    override fun check(ok: (IntRange) -> Unit, err: (String) -> Unit) {
        calls++
        val r = range
        when {
            hold -> held += ok to err
            r != null -> ok(r)
            else -> err("지원 안 함")
        }
    }
}

class RestSeatTest {
    @get:Rule val temp = TemporaryFolder()

    private inner class Rig(val env: SeatEnvironment? = ENV, stored: SeatSnapshot? = null, file: File? = null) {
        val events = mutableListOf<String>()
        val port = FakeSeatPort(events).also { it.position = 20 }
        val cap = FakeCapability()
        val memory = FakeJournal(events, stored)
        val journal: SeatJournal = file?.let { FileSeatJournal(it) } ?: memory
        val scheduler = FakeScheduler()
        var parking = PARKED
        /** A debug-tool block for new applies only. */
        var block: String? = null
        private var n = 0
        val seat = RestSeat(port, cap, journal, scheduler, { parking }, env, { "id-${++n}" }, startBlock = { block })

        init {
            seat.onDoor(true)
            seat.onScreen(true)
        }

        fun calls() = listOf(port.subs.size, port.reads, port.moveCalls.size, port.stops.size, port.ended.size)
        fun tick() = scheduler.advance(0)
        val phase get() = seat.state.phase

        /** The MOVE answers, the seat reaches [at], the STOP answers, the confirming reads run (decisions not yet). */
        fun reach(at: Int) {
            port.moves.last().ok("MOVE")
            port.emit(at)
            port.stops.last().ok("STOP")
            scheduler.advance(ENV.settleMs)
            scheduler.advance(ENV.confirmIntervalMs)
        }

        /** Rest 1 started, the backrest confirmed at 59 and watched. */
        fun watching() {
            seat.start()
            assertEquals(1, seat.onRestStart())
            reach(59)
            tick()
            assertEquals(RestSeatPhase.WATCHING, phase)
        }

        fun restoreTo(at: Int) {
            port.moves.last().ok("MOVE")
            port.emit(at)
            port.stops.last().ok("STOP")
            scheduler.advance(ENV.settleMs)
            scheduler.advance(ENV.confirmIntervalMs)
            tick()
        }

        fun stored(): SeatSnapshot? = (journal.load() as? SeatJournalLoad.Found)?.snapshot
    }

    // --- environment and ownership ---

    @Test
    fun `no verified environment and no record - nothing is called or saved`() {
        val r = Rig(env = null)
        r.seat.start()
        assertNull(r.seat.onRestStart())
        r.seat.onRestEnd(1, EndReason.USER)
        r.seat.onParking(DRIVING)
        r.tick()
        assertEquals(listOf(0, 0, 0, 0, 0), r.calls())
        assertEquals(0, r.cap.calls)
        assertTrue(r.memory.saved.isEmpty())
    }

    @Test
    fun `no verified environment still sends the safety STOP for an unfinished earlier record`() {
        val pending = SeatSnapshot("old", SeatPurpose.APPLY, SeatAxis.BACKREST, 20, 20, 60, SeatRequestMark("m-old", SeatRequestState.ENTERING))
        val r = Rig(env = null, stored = pending)
        r.seat.start()
        assertEquals(1, r.port.stops.size)
        assertEquals(RestSeatPhase.LOCKED, r.phase)
        assertNull(r.seat.onRestStart())
        assertTrue(r.port.moveCalls.isEmpty())
    }

    @Test
    fun `a consistent earlier record is left alone - no move, restore or deletion`() {
        val done = SeatSnapshot(
            "old", SeatPurpose.APPLY, SeatAxis.BACKREST, 20, 20, 60, SeatRequestMark("m-old", SeatRequestState.COMPLETED),
            listOf(SeatRequestMark("s-old", SeatRequestState.COMPLETED, afterMove = true)), SeatOutcome.CONFIRMED,
        )
        val r = Rig(stored = done)
        r.seat.start()
        assertEquals(RestSeatPhase.LOCKED, r.phase)
        assertNull(r.seat.onRestStart())
        assertEquals(listOf(0, 0, 0, 0, 0), r.calls())
        assertTrue(r.memory.saved.isEmpty())
        assertEquals(done, r.memory.stored)
    }

    @Test
    fun `apply, watch, restore, clean-up on a real file, then the next rest starts a new job`() {
        val f = File(temp.newFolder(), FileSeatJournal.FILE_NAME)
        val r = Rig(file = f)
        r.watching()
        assertEquals(listOf(Move.FORWARD), r.port.entered)
        val coreSub = r.port.subs.first()
        assertTrue(coreSub.closed) // the core closed its listener before the owner's watch
        r.seat.onRestEnd(1, EndReason.USER)
        r.tick()
        assertEquals(RestSeatPhase.RESTORING, r.phase)
        assertEquals(Move.BACKWARD, r.port.entered.last())
        r.restoreTo(21)
        assertEquals(RestSeatPhase.IDLE, r.phase)
        assertFalse(f.exists())

        r.port.position = 21
        assertEquals(2, r.seat.onRestStart())
        assertEquals(Move.FORWARD, r.port.entered.last())
        assertEquals(3, r.port.entered.size)
        assertTrue(r.port.ended.size == 2)
    }

    // --- watch ---

    @Test
    fun `a change during the rest is latched even when the seat comes back, and the record is kept held`() {
        val r = Rig()
        r.watching()
        r.port.emit(55)
        assertEquals(RestSeatPhase.HELD, r.phase)
        r.port.emit(59) // back to the applied position: still a user change
        r.seat.onRestEnd(1, EndReason.USER)
        r.tick()
        assertEquals(1, r.port.moveCalls.size)
        val s = r.stored()!!
        assertEquals(SeatOutcome.HELD, s.outcome)
        assertTrue(s.held!!.contains("바뀌었"))
        assertNull(r.seat.onRestStart())
    }

    @Test
    fun `watch failures hold the job - registration error or timeout, listener error, failed read, missing or out-of-range value`() {
        val cases = listOf<Pair<String, (Rig) -> Unit>>(
            "등록 실패" to { it.port.registration = SDK_ERROR; it.tick() },
            "등록 응답 없음" to { it.port.holdRegistration = true; it.tick(); it.scheduler.advance(ENV.answerMs) },
            "리스너 오류" to { it.tick(); it.port.subs.last().error(SDK_ERROR) },
            "감시 위치 조회" to { it.port.readError = SDK_ERROR; it.tick() },
            "믿을 수 없어요(null)" to { it.tick(); it.port.emit(null) },
            "믿을 수 없어요(101)" to { it.tick(); it.port.emit(101) },
        )
        for ((why, fail) in cases) {
            val r = Rig()
            r.seat.start()
            assertEquals(1, r.seat.onRestStart())
            r.reach(59)
            fail(r)
            assertEquals(why, RestSeatPhase.HELD, r.phase)
            assertTrue(why, r.seat.state.message!!.contains(why))
            assertEquals(why, SeatOutcome.HELD, r.stored()!!.outcome)
            r.port.readError = null
            r.seat.onRestEnd(1, EndReason.USER)
            r.tick()
            assertEquals(why, 1, r.port.moveCalls.size)
        }
    }

    @Test
    fun `ending before the watch registration is acknowledged holds, and its late answer changes nothing`() {
        val r = Rig()
        r.seat.start()
        r.seat.onRestStart()
        r.port.holdRegistration = true
        r.reach(59)
        r.tick()
        r.seat.onRestEnd(1, EndReason.USER)
        r.tick()
        assertEquals(RestSeatPhase.HELD, r.phase)
        r.port.subs.last().registered(null)
        r.port.subs.last().update(30)
        r.tick()
        assertEquals(1, r.port.moveCalls.size)
        assertEquals(RestSeatPhase.HELD, r.phase)
    }

    @Test
    fun `late events of the closed watch do not touch the restore`() {
        val r = Rig()
        r.watching()
        val watchSub = r.port.subs.last()
        r.seat.onRestEnd(1, EndReason.USER)
        r.tick()
        assertEquals(RestSeatPhase.RESTORING, r.phase)
        watchSub.update(30)
        watchSub.error(SDK_ERROR)
        r.restoreTo(21)
        assertEquals(RestSeatPhase.IDLE, r.phase)
    }

    // --- ends during the apply ---

    @Test
    fun `a door opening during the apply stops it and restores once after the stop is checked`() {
        val r = Rig()
        r.seat.start()
        r.seat.onRestStart()
        r.port.emit(30)
        r.seat.onRestEnd(1, EndReason.DOOR_OPENED)
        assertEquals(1, r.port.stops.size) // the STOP goes before any decision
        r.port.moves[0].ok("MOVE") // answered after the STOP was sent: one more STOP follows
        assertEquals(2, r.port.stops.size)
        r.port.stops.toList().forEach { it.ok("STOP") }
        r.scheduler.advance(ENV.settleMs)
        r.tick()
        assertEquals(RestSeatPhase.RESTORING, r.phase)
        assertEquals(Move.BACKWARD, r.port.entered.last())
        r.restoreTo(21)
        assertEquals(RestSeatPhase.IDLE, r.phase)
    }

    @Test
    fun `after the intentional stop, a listener failure, a missing value or a move away still blocks the restore`() {
        val faults = listOf<Pair<String, (Rig) -> Unit>>(
            "리스너" to { it.port.subs.first().error(SDK_ERROR) },
            "값 없음" to { it.port.emit(null) },
            "반대 방향" to { it.port.emit(25) },
        )
        for ((why, fault) in faults) {
            val r = Rig()
            r.seat.start()
            r.seat.onRestStart()
            r.port.emit(30)
            r.seat.onRestEnd(1, EndReason.APP_LEFT)
            fault(r) // while stopping
            r.port.moves[0].ok("MOVE")
            r.port.stops.toList().forEach { it.ok("STOP") }
            r.port.position = 30
            r.scheduler.advance(ENV.settleMs)
            r.tick()
            assertEquals(why, RestSeatPhase.HELD, r.phase)
            assertEquals(why, listOf(Move.FORWARD), r.port.entered)
        }
    }

    @Test
    fun `a changed position after a post-stop read is a failure of trust, not a fresh value`() {
        for (endRightAfter in listOf(false, true)) {
            val q = Rig()
            q.seat.start()
            q.seat.onRestStart()
            q.port.moves[0].ok("MOVE")
            q.port.emit(59) // target reached: STOP
            q.port.stops[0].ok("STOP")
            q.scheduler.advance(ENV.settleMs) // first read after the stop: 59, on target
            q.port.emit(40) // then the position moves before the next confirming read
            if (endRightAfter) q.seat.onRestEnd(1, EndReason.USER)
            q.port.emit(59) // and comes back: the earlier 59 is no current proof
            q.tick()
            q.scheduler.advance(60_000)
            assertEquals("$endRightAfter", RestSeatPhase.HELD, q.phase)
            assertEquals("$endRightAfter", listOf(Move.FORWARD), q.port.entered)
            assertEquals("$endRightAfter", SeatOutcome.HELD, q.stored()!!.outcome)
        }
    }

    @Test
    fun `an error during the apply holds without any retry`() {
        val r = Rig()
        r.seat.start()
        r.seat.onRestStart()
        r.port.moves[0].ok("MOVE")
        r.scheduler.advance(ENV.noChangeMs) // no change: STOP
        r.port.stops[0].ok("STOP")
        r.scheduler.advance(ENV.settleMs)
        r.tick()
        assertEquals(RestSeatPhase.HELD, r.phase)
        r.seat.onRestEnd(1, EndReason.USER)
        r.tick()
        assertEquals(1, r.port.moveCalls.size)
    }

    @Test
    fun `parking lost holds for good - no restore after P comes back or a later normal end`() {
        val r = Rig()
        r.seat.start()
        r.seat.onRestStart()
        r.port.emit(30)
        r.parking = DRIVING
        r.seat.onParking(DRIVING)
        assertEquals(1, r.port.stops.size)
        r.port.moves[0].ok("MOVE")
        r.port.stops.toList().forEach { it.ok("STOP") }
        r.scheduler.advance(ENV.settleMs)
        r.tick()
        r.parking = PARKED
        r.seat.onParking(PARKED)
        r.seat.onRestEnd(1, EndReason.USER)
        r.tick()
        r.scheduler.advance(60_000)
        assertEquals(RestSeatPhase.HELD, r.phase)
        assertEquals(1, r.port.moveCalls.size)
        assertEquals(SeatOutcome.HELD, r.stored()!!.outcome)
    }

    @Test
    fun `ending while capability or the first read waits - their late answers start nothing`() {
        val r = Rig()
        r.seat.start()
        r.cap.hold = true
        assertEquals(1, r.seat.onRestStart())
        r.seat.onRestEnd(1, EndReason.USER)
        r.cap.held.single().first(0..100)
        r.tick()
        assertEquals(0, r.port.reads)
        assertTrue(r.port.moveCalls.isEmpty())
        assertEquals(RestSeatPhase.IDLE, r.phase)

        val q = Rig()
        q.seat.start()
        q.port.holdReads = true
        q.seat.onRestStart()
        q.seat.onRestEnd(1, EndReason.TIME_UP)
        q.port.heldReads.single().ok(20)
        q.tick()
        assertTrue(q.port.moveCalls.isEmpty())
    }

    @Test
    fun `an unanswered MOVE keeps everything pending - no restore, no clean-up`() {
        val f = File(temp.newFolder(), FileSeatJournal.FILE_NAME)
        val r = Rig(file = f)
        r.seat.start()
        r.seat.onRestStart()
        r.port.emit(59)
        r.port.stops[0].ok("STOP")
        r.scheduler.advance(ENV.answerMs)
        r.seat.onRestEnd(1, EndReason.USER)
        r.port.stops.last().ok("STOP")
        r.scheduler.advance(60_000)
        r.tick()
        assertEquals(1, r.port.moveCalls.size)
        assertTrue(f.exists())
        assertNull(r.stored()!!.outcome)

        // The process restarts: the restart manager sends a STOP and the seat stays locked.
        val p = Rig(file = f)
        p.seat.start()
        assertEquals(1, p.port.stops.size)
        assertEquals(RestSeatPhase.LOCKED, p.phase)
        assertNull(p.seat.onRestStart())
    }

    @Test
    fun `a new rest while the previous job restores leaves the seat alone and the previous job intact`() {
        val r = Rig()
        r.watching()
        r.seat.onRestEnd(1, EndReason.USER)
        r.tick()
        assertEquals(RestSeatPhase.RESTORING, r.phase)
        val jobId = r.seat.state.jobId
        assertNull(r.seat.onRestStart())
        assertEquals(jobId, r.seat.state.jobId)
        r.seat.onRestEnd(2, EndReason.USER) // another run: ignored
        r.restoreTo(21)
        assertEquals(RestSeatPhase.IDLE, r.phase)
        r.port.position = 21
        assertEquals(2, r.seat.onRestStart())
    }

    @Test
    fun `no move is needed - nothing is written`() {
        val r = Rig()
        r.seat.start()
        r.port.position = 100 // the recline delta is clamped to the range end
        assertEquals(1, r.seat.onRestStart())
        assertTrue(r.port.moveCalls.isEmpty())
        assertTrue(r.memory.saved.isEmpty())
        assertEquals(RestSeatPhase.IDLE, r.phase)
    }

    // --- review regressions ---

    @Test
    fun `while the rest runs, an unconfirmed apply is held and never restored`() {
        val r = Rig()
        r.seat.start()
        r.seat.onRestStart()
        r.port.moves[0].ok("MOVE")
        r.port.emit(59)
        r.port.position = 50 // reads after the stop are off the target
        r.port.stops[0].ok("STOP")
        r.scheduler.advance(ENV.settleMs)
        r.tick()
        assertEquals(RestSeatPhase.HELD, r.phase)
        r.seat.onRestEnd(1, EndReason.USER)
        r.tick()
        r.scheduler.advance(60_000)
        assertEquals(1, r.port.moveCalls.size)
    }

    @Test
    fun `ending while confirming, before any read after the stop, holds`() {
        val r = Rig()
        r.seat.start()
        r.seat.onRestStart()
        r.port.moves[0].ok("MOVE")
        r.port.emit(59)
        r.port.stops[0].ok("STOP") // confirming: no read yet
        r.seat.onRestEnd(1, EndReason.USER)
        r.tick()
        assertEquals(RestSeatPhase.HELD, r.phase)
        assertEquals(1, r.port.moveCalls.size)
    }

    @Test
    fun `ending while the watch's first read waits holds, and that late read changes nothing`() {
        val r = Rig()
        r.seat.start()
        r.seat.onRestStart()
        r.reach(59)
        r.port.holdReads = true
        r.tick() // registered; the first read waits
        r.seat.onRestEnd(1, EndReason.USER)
        r.tick()
        assertEquals(RestSeatPhase.HELD, r.phase)
        r.port.heldReads.single().ok(59)
        r.tick()
        assertEquals(1, r.port.moveCalls.size)

        val t = Rig()
        t.seat.start()
        t.seat.onRestStart()
        t.reach(59)
        t.port.holdReads = true
        t.tick()
        t.scheduler.advance(ENV.answerMs) // the first read never answers
        assertEquals(RestSeatPhase.HELD, t.phase)
    }

    @Test
    fun `the watch stays on while the restore is checked - a change and return then is latched, no MOVE`() {
        val r = Rig()
        r.watching()
        r.cap.hold = true
        r.seat.onRestEnd(1, EndReason.USER)
        r.tick()
        assertEquals(RestSeatPhase.RESTORE_CHECK, r.phase)
        r.port.emit(40)
        r.port.emit(59)
        r.cap.held.single().first(0..100)
        r.tick()
        assertEquals(RestSeatPhase.HELD, r.phase)
        assertEquals(1, r.port.moveCalls.size)
        assertEquals(SeatOutcome.HELD, r.stored()!!.outcome)
    }

    @Test
    fun `a hold during the restore is never lifted - the waiting restore MOVE is refused at its gate`() {
        val r = Rig()
        r.watching()
        r.port.holdDispatch = true
        r.seat.onRestEnd(1, EndReason.USER)
        r.tick()
        assertEquals(RestSeatPhase.RESTORING, r.phase)
        r.parking = DRIVING
        r.seat.onParking(DRIVING)
        r.port.runQueued()
        assertEquals(listOf(Move.FORWARD), r.port.entered)
        r.parking = PARKED
        r.seat.onParking(PARKED)
        r.seat.onRestEnd(1, EndReason.USER)
        r.port.stops.toList().forEach { it.ok("STOP") }
        r.tick()
        assertEquals(RestSeatPhase.HELD, r.phase)
        assertEquals(listOf(Move.FORWARD), r.port.entered)
    }

    @Test
    fun `the door or the screen lost during the apply ends it without the rest end, and restores after the stop`() {
        for (loss in listOf<(RestSeat) -> Unit>({ it.onDoor(false) }, { it.onDoor(null) }, { it.onScreen(false) })) {
            val r = Rig()
            r.seat.start()
            r.seat.onRestStart()
            r.port.emit(30)
            loss(r.seat)
            assertEquals(1, r.port.stops.size)
            r.port.moves[0].ok("MOVE")
            r.port.stops.toList().forEach { it.ok("STOP") }
            r.scheduler.advance(ENV.settleMs)
            r.tick()
            assertEquals(RestSeatPhase.RESTORING, r.phase)
            r.seat.onDoor(false) // during the restore: does not block it
            r.seat.onScreen(false)
            r.restoreTo(21)
            assertEquals(RestSeatPhase.IDLE, r.phase)
        }
    }

    @Test
    fun `every rest end is mapped - parking-type ends hold, the others may restore`() {
        val allowed = EndReason.entries.filter { seatRestoreAllowed(it) }.toSet()
        assertEquals(setOf(EndReason.TIME_UP, EndReason.USER, EndReason.DOOR_OPENED, EndReason.DOOR_UNKNOWN, EndReason.APP_LEFT, EndReason.AUDIO_LOST), allowed)
        for (reason in EndReason.entries) {
            val r = Rig()
            r.watching()
            r.seat.onRestEnd(1, reason)
            r.tick()
            val restored = r.port.moveCalls.size == 2
            assertEquals(reason.name, reason in allowed, restored)
        }
    }

    @Test
    fun `the restart manager stays and its late STOP answer shows on the locked owner`() {
        val pending = SeatSnapshot("old", SeatPurpose.APPLY, SeatAxis.BACKREST, 20, 20, 60, SeatRequestMark("m-old", SeatRequestState.ENTERING))
        val r = Rig(env = null, stored = pending)
        r.seat.start()
        assertTrue(r.seat.state.message!!.contains("STOP 응답 대기"))
        r.port.stops.single().ok("STOP")
        assertEquals(RestSeatPhase.LOCKED, r.phase)
        assertTrue(r.seat.state.message!!.contains("STOP 응답 받음(실제 정지·복원은 확인 안 됨)"))
    }

    // --- STEP 33 3rd review ---

    @Test
    fun `rest tokens are process-unique - an older or never-issued token ends nothing`() {
        val r = Rig()
        r.watching()
        assertEquals(1, r.seat.state.token)
        r.seat.onRestEnd(1, EndReason.USER)
        r.tick()
        r.restoreTo(21)
        r.port.position = 21
        val second = r.seat.onRestStart()
        assertEquals(2, second)
        val stops = r.port.stops.size
        r.seat.onRestEnd(1, EndReason.USER) // a late end of the older rest (or a new Activity reusing its own 1)
        r.seat.onRestEnd(99, EndReason.LEFT_PARK)
        r.tick()
        assertEquals(RestSeatPhase.APPLYING, r.phase)
        assertEquals(stops, r.port.stops.size)
        assertEquals(2, r.seat.state.token)
    }

    @Test
    fun `a storage fault of the core is shown as a hold - first save failed after its replace, or saves failing later`() {
        val f = File(temp.newFolder(), FileSeatJournal.FILE_NAME)
        val events = mutableListOf<String>()
        val port = FakeSeatPort(events).also { it.position = 20 }
        val scheduler = FakeScheduler()
        var n = 0
        val seat = RestSeat(port, FakeCapability(), FileSeatJournal(f, syncDirectory = { throw java.io.IOException("fsync dir") }), scheduler, { PARKED }, ENV, { "id-${++n}" })
        seat.onDoor(true)
        seat.onScreen(true)
        seat.start()
        assertEquals(1, seat.onRestStart())
        scheduler.advance(0)
        assertEquals(RestSeatPhase.HELD, seat.state.phase)
        assertTrue(seat.state.message!!.contains("저장 실패"))
        assertTrue(port.moveCalls.isEmpty())
        assertTrue(f.exists()) // SAVED stays on disk; nothing deletes it
        assertNull(seat.onRestStart())
        scheduler.advance(60_000)
        assertTrue(port.moveCalls.isEmpty())

        // Saves start failing while the seat moves: the STOP still goes, and a confirmed APPLY is held, not watched.
        val g = File(temp.newFolder(), FileSeatJournal.FILE_NAME)
        var fail = false
        val q = Rig()
        var m = 0
        val qs = RestSeat(q.port, q.cap, FileSeatJournal(g, syncDirectory = { if (fail) throw java.io.IOException("x") else app.commacabin.control.SYNC_DIRECTORY(it) }), q.scheduler, { PARKED }, ENV, { "q-${++m}" })
        qs.onDoor(true)
        qs.onScreen(true)
        qs.start()
        assertNotNull(qs.onRestStart())
        fail = true
        q.reach(59)
        q.tick()
        assertEquals(RestSeatPhase.HELD, qs.state.phase)
        assertEquals(1, q.port.subs.size) // only the core's listener: no watch
        assertEquals(1, q.port.moveCalls.size)
    }

    @Test
    fun `a failed STOP or a missing answer is shown while the attempt stays pending, and a parking loss still sends a STOP`() {
        val r = Rig()
        r.seat.start()
        r.seat.onRestStart()
        r.port.moves[0].ok("MOVE")
        r.port.emit(59)
        r.port.stops[0].err(SDK_ERROR)
        assertEquals(RestSeatPhase.STOP_FAILED, r.phase)
        r.parking = DRIVING
        r.seat.onParking(DRIVING)
        assertEquals(2, r.port.stops.size)
        r.tick()
        assertEquals(1, r.port.moveCalls.size)

        val t = Rig()
        t.seat.start()
        t.seat.onRestStart()
        t.port.emit(59) // STOP; neither MOVE nor STOP answers
        t.scheduler.advance(ENV.answerMs)
        assertEquals(RestSeatPhase.NO_ANSWER, t.phase)
        t.seat.onRestEnd(1, EndReason.USER)
        t.scheduler.advance(60_000)
        assertEquals(1, t.port.moveCalls.size)
    }

    @Test
    fun `holdRecord with a failing save - memory and state held, the disk record unchanged, the storage fault kept`() {
        val events = mutableListOf<String>()
        val port = FakeSeatPort(events).also { it.position = 20 }
        val journal = FakeJournal(events)
        val scheduler = FakeScheduler()
        val core = SeatMoveCore(SeatAxis.BACKREST, port, journal, scheduler, { null }, { null })
        assertNull(core.begin(req()))
        port.moves[0].ok("MOVE")
        port.emit(59)
        port.stops[0].ok("STOP")
        scheduler.advance(LIMITS.settleMs)
        scheduler.advance(LIMITS.confirmIntervalMs)
        val disk = journal.stored
        journal.fail = { it.outcome == SeatOutcome.HELD }
        assertNotNull(core.holdRecord("job-1", "move-1", "감시 실패"))
        assertEquals(SeatOutcome.CONFIRMED, journal.stored!!.outcome)
        assertEquals(disk, journal.stored)
        assertEquals(SeatOutcome.HELD, core.state.snapshot!!.outcome)
        assertEquals(SeatOutcome.HELD, core.state.outcome)
        assertEquals(SeatOutcome.HELD, core.state.result!!.outcome)
        assertTrue(core.state.lock!!.contains("저장 실패"))
        assertFalse(core.state.clearable)
    }

    // --- STEP 34 app wiring contracts ---

    @Test
    fun `a debug block stops only a new apply - never the STOP, an allowed restore or a running job's callbacks`() {
        val r = Rig()
        r.seat.start()
        r.block = "디버그 시트 작업 중"
        assertNull(r.seat.onRestStart())
        assertEquals(listOf(0, 0, 0, 0, 0), r.calls())

        // A block that appears while the apply waits at its gate refuses it there.
        val g = Rig()
        g.seat.start()
        g.port.holdDispatch = true
        assertEquals(1, g.seat.onRestStart())
        g.block = "디버그 시트 작업 중"
        g.port.runQueued()
        assertTrue(g.port.entered.isEmpty())

        // A running job: the block comes later and changes nothing for its STOP, restore and clean-up.
        val w = Rig()
        w.watching()
        w.block = "디버그 시트 작업 중"
        w.seat.onRestEnd(1, EndReason.USER)
        w.tick()
        assertEquals(RestSeatPhase.RESTORING, w.phase)
        w.restoreTo(21)
        assertEquals(RestSeatPhase.IDLE, w.phase)
    }

    @Test
    fun `busy only with a job or a record, and the product sentence names no state, ID or path`() {
        val empty = Rig(env = null)
        empty.seat.start()
        assertNull(empty.seat.busyReason())
        assertNull(empty.seat.productNotice())

        val pending = SeatSnapshot("qa-job", SeatPurpose.APPLY, SeatAxis.BACKREST, 20, 20, 60, SeatRequestMark("qa-move", SeatRequestState.ENTERING))
        val locked = Rig(env = null, stored = pending)
        locked.seat.start()
        assertNotNull(locked.seat.busyReason())
        val notice = locked.seat.productNotice()!!
        for (raw in listOf("qa-job", "qa-move", "LOCKED", "ENTERING", "STOP", ".json", "/")) assertFalse(raw, notice.contains(raw))

        val running = Rig()
        running.watching()
        assertNotNull(running.seat.busyReason())
        assertNull(running.seat.productNotice())
    }

    // --- core API ---

    @Test
    fun `holdRecord keeps a moved record held and the core state follows, NOT_SENT keeps NOT_SENT, others are refused`() {
        val events = mutableListOf<String>()
        val port = FakeSeatPort(events).also { it.position = 20 }
        val journal = FakeJournal(events)
        val scheduler = FakeScheduler()
        val core = SeatMoveCore(SeatAxis.BACKREST, port, journal, scheduler, { null }, { null })
        val seen = mutableListOf<SeatMoveState>()
        core.onState = { seen += it }
        assertNull(core.begin(req()))
        assertNotNull(core.holdRecord("job-1", "move-1", "x")) // still running
        port.moves[0].ok("MOVE")
        port.emit(59)
        port.stops[0].ok("STOP")
        scheduler.advance(LIMITS.settleMs)
        scheduler.advance(LIMITS.confirmIntervalMs)
        assertEquals(SeatOutcome.CONFIRMED, core.state.outcome)
        assertTrue(core.state.clearable.not()) // an APPLY is never clearable
        val bytes = journal.stored
        assertNotNull(core.holdRecord("job-2", "move-1", "x"))
        assertNotNull(core.holdRecord("job-1", "move-9", "x"))
        assertEquals(bytes, journal.stored)
        assertNull(core.holdRecord("job-1", "move-1", "휴식 중 위치 변경"))
        assertEquals(SeatOutcome.HELD, journal.stored!!.outcome)
        assertEquals(SeatOutcome.HELD, core.state.outcome)
        assertEquals(SeatOutcome.HELD, core.state.result!!.outcome)
        assertEquals(SeatOutcome.CONFIRMED, core.state.result!!.endedAs)
        assertEquals("휴식 중 위치 변경", core.state.result!!.heldAfter)
        assertEquals(SeatOutcome.HELD, seen.last().outcome) // the callback never shows the old CONFIRMED again
        assertNotNull(core.clearRecord()) // held: nothing to clean

        // NOT_SENT stays NOT_SENT (no MOVE entered) and only gains the cause.
        val n = FakeJournal(mutableListOf())
        val p2 = FakeSeatPort(mutableListOf()).also { it.position = 20; it.holdDispatch = true }
        val c2 = SeatMoveCore(SeatAxis.BACKREST, p2, n, FakeScheduler(), { null }, { null })
        assertNull(c2.begin(req()))
        assertNull(c2.interrupt("휴식 종료", "stop-x"))
        p2.stops[0].ok("STOP")
        assertEquals(SeatOutcome.NOT_SENT, c2.state.outcome)
        assertNull(c2.holdRecord("job-1", "move-1", "보류"))
        assertEquals(SeatOutcome.NOT_SENT, n.stored!!.outcome)
        assertNotNull(n.stored!!.held)
        assertTrue(n.stored!!.stoppedConsistently())

        // A pending record loaded from an earlier run: refused, untouched.
        val pending = SeatSnapshot("old", SeatPurpose.APPLY, SeatAxis.BACKREST, 20, 20, 60, SeatRequestMark("m-old", SeatRequestState.ENTERING))
        val j3 = FakeJournal(mutableListOf(), pending)
        val c3 = SeatMoveCore(SeatAxis.BACKREST, FakeSeatPort(mutableListOf()), j3, FakeScheduler(), { null }, { null })
        assertNotNull(c3.holdRecord("old", "m-old", "x"))
        assertEquals(pending, j3.stored)
        assertTrue(j3.saved.isEmpty())
    }

    @Test
    fun `structured result - target, intentional stop with a partial move, and a failure after it`() {
        val events = mutableListOf<String>()
        val port = FakeSeatPort(events).also { it.position = 20 }
        val scheduler = FakeScheduler()
        val core = SeatMoveCore(SeatAxis.BACKREST, port, FakeJournal(events), scheduler, { null }, { null })
        assertNull(core.begin(req()))
        port.moves[0].ok("MOVE")
        port.emit(30)
        assertNull(core.interrupt("휴식 종료", "stop-i"))
        port.emit(33) // still moving the commanded way: no fault
        port.stops[0].ok("STOP")
        scheduler.advance(LIMITS.settleMs)
        val r = core.state.result!!
        assertEquals(SeatStopCause.INTERRUPTED, r.firstCause)
        assertFalse(r.faultAfter)
        assertTrue(r.allAnswered && r.stoppedAfterMove)
        assertEquals(33, r.lastRead)

        val p2 = FakeSeatPort(mutableListOf()).also { it.position = 20 }
        val s2 = FakeScheduler()
        val c2 = SeatMoveCore(SeatAxis.BACKREST, p2, FakeJournal(mutableListOf()), s2, { null }, { null })
        assertNull(c2.begin(req()))
        p2.moves[0].ok("MOVE")
        p2.emit(30)
        c2.interrupt("휴식 종료", "stop-i")
        p2.subs.first().error(SDK_ERROR)
        p2.stops[0].ok("STOP")
        s2.advance(LIMITS.settleMs)
        assertEquals(SeatStopCause.INTERRUPTED, c2.state.result!!.firstCause)
        assertTrue(c2.state.result!!.faultAfter)

        // Target reached, a read of 59 after the stop, then another value: moved after the stop.
        val p3 = FakeSeatPort(mutableListOf()).also { it.position = 20 }
        val s3 = FakeScheduler()
        val c3 = SeatMoveCore(SeatAxis.BACKREST, p3, FakeJournal(mutableListOf()), s3, { null }, { null })
        assertNull(c3.begin(req()))
        p3.moves[0].ok("MOVE")
        p3.emit(59)
        p3.stops[0].ok("STOP")
        s3.advance(LIMITS.settleMs)
        p3.emit(40)
        val r3 = c3.state.result!!
        assertEquals(SeatStopCause.TARGET, r3.firstCause)
        assertTrue(r3.faultAfter)
        assertEquals(SeatOutcome.UNCONFIRMED, r3.outcome)
    }

    @Test
    fun `controlCapability needs a readable driver range and a writable driver move`() {
        val port = FakeControlPort()
        val got = mutableListOf<Any>()
        val q = controlCapability(port)
        val pos = CapabilityResult(Access.READ_WRITE, listOf(AreaBound("ROW_1_LEFT", "0", "100")))
        val move = CapabilityResult(Access.WRITE, listOf(AreaBound("ROW_1_LEFT")))
        q.check({ got.add(it) }, { got.add(it) })
        port.capabilities.last().first(pos, move, null)
        assertEquals(0..100, got.last())
        q.check({ got.add(it) }, { got.add(it) })
        port.capabilities.last().first(pos, null, null)
        assertTrue(got.last() is String)
        q.check({ got.add(it) }, { got.add(it) })
        port.capabilities.last().first(CapabilityResult(Access.READ_WRITE, listOf(AreaBound("ROW_1_LEFT"))), move, null)
        assertTrue(got.last() is String)
        q.check({ got.add(it) }, { got.add(it) })
        port.capabilities.last().second(SDK_ERROR)
        assertTrue((got.last() as String).contains("지원 조회 실패"))
    }

    @Test
    fun `the product environment is not verified`() {
        assertNull(VERIFIED_SEAT_ENVIRONMENT)
    }
}
