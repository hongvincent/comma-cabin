package app.commacabin.seat

import app.commacabin.control.SeatAxis
import app.commacabin.probe.FakeScheduler
import app.commacabin.probe.ProbeError
import app.commacabin.probe.SDK_ERROR
import java.io.File
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val ANSWER_MS = 5_000L

private val S = SeatRequestState.SAVED
private val E = SeatRequestState.ENTERING
private val C = SeatRequestState.COMPLETED
private val F = SeatRequestState.FAILED
private val N = SeatRequestState.NOT_SENT

private fun snap(move: SeatRequestMark, stops: List<SeatRequestMark> = emptyList(), outcome: SeatOutcome? = null, held: String? = null, axis: SeatAxis = SeatAxis.BACKREST) =
    SeatSnapshot("job-1", SeatPurpose.APPLY, axis, 20, 20, 60, move, stops, outcome, held)

private fun mark(id: String, state: SeatRequestState, afterMove: Boolean = false, timedOut: Boolean = false) =
    SeatRequestMark(id, state, if (state == C || state == F) "answer" else null, timedOut, afterMove)

/** Counts loads so the constructor can be shown to read nothing. */
private class CountingJournal(val inner: SeatJournal) : SeatJournal {
    var loads = 0
    override fun load(): SeatJournalLoad = inner.load().also { loads++ }
    override fun save(snapshot: SeatSnapshot): Boolean = inner.save(snapshot)
    override fun clear(expected: SeatSnapshot): Boolean = inner.clear(expected)
}

class SeatEmergencyStopTest {
    @get:Rule val temp = TemporaryFolder()

    private val events = mutableListOf<String>()
    private val port = FakeSeatPort(events)
    private val scheduler = FakeScheduler()
    private var ids = (1..20).map { "es-$it" }.iterator()

    private fun manager(journal: SeatJournal, p: FakeSeatPort = port, s: FakeScheduler = scheduler, newId: () -> String = { ids.next() }) =
        SeatEmergencyStop(p, journal, s, ANSWER_MS, newId)

    private fun memory(stored: SeatSnapshot?) = FakeJournal(events, stored)

    private fun noCalls(p: FakeSeatPort = port) {
        assertTrue(p.subs.isEmpty())
        assertEquals(0, p.reads)
        assertTrue(p.moveCalls.isEmpty())
        assertTrue(p.stops.isEmpty())
        assertTrue(p.ended.isEmpty())
    }

    // Auto-STOP table cases.
    private val consistent = listOf(
        snap(mark("move-1", C), listOf(mark("stop-1", C, afterMove = true)), SeatOutcome.CONFIRMED),
        snap(mark("move-1", C), listOf(mark("stop-1", C, afterMove = true)), SeatOutcome.UNCONFIRMED),
        snap(mark("move-1", F), listOf(mark("stop-1", C, afterMove = true)), SeatOutcome.HELD, held = "화면 이탈"),
        snap(mark("move-1", N), emptyList(), SeatOutcome.NOT_SENT),
        snap(mark("move-1", N), listOf(mark("stop-1", C)), SeatOutcome.NOT_SENT),
        snap(mark("move-1", N), listOf(mark("stop-1", F), mark("stop-2", C)), SeatOutcome.NOT_SENT), // a failed STOP for a MOVE never sent
        snap(mark("move-1", C), listOf(mark("stop-1", C), mark("final-1", C, afterMove = true)), SeatOutcome.CONFIRMED),
    )
    private val needStop = listOf(
        snap(mark("move-1", S)), // SAVED in an earlier run is no proof of not sending
        snap(mark("move-1", E), listOf(mark("stop-1", E, timedOut = true))),
        snap(mark("move-1", C), listOf(mark("stop-1", C, afterMove = true))), // confirming when the process died
        snap(mark("move-1", N), listOf(mark("stop-1", E))), // NOT_SENT MOVE, but the attempt is unfinished
        snap(mark("move-1", C), listOf(mark("stop-1", F, afterMove = true)), SeatOutcome.STOP_FAILED),
        // Valid format, outcome contradicted by its requests: no proof of completion.
        snap(mark("move-1", E), emptyList(), SeatOutcome.CONFIRMED),
        snap(mark("move-1", C), listOf(mark("stop-1", E, afterMove = true)), SeatOutcome.CONFIRMED),
        snap(mark("move-1", C), listOf(mark("stop-1", C, afterMove = false)), SeatOutcome.CONFIRMED), // no STOP after the MOVE
        snap(mark("move-1", S), emptyList(), SeatOutcome.NOT_SENT), // the NOT_SENT outcome alone is not trusted
        snap(mark("move-1", N), listOf(mark("stop-1", E)), SeatOutcome.NOT_SENT),
        snap(mark("move-1", C), listOf(mark("stop-1", C, afterMove = true)), SeatOutcome.HELD), // held without a cause
        // afterMove that does not fit the MOVE: no answer of the MOVE, yet a STOP "after" it; or back to false.
        snap(mark("move-1", N), listOf(mark("stop-1", C, afterMove = true)), SeatOutcome.NOT_SENT),
        snap(mark("move-1", E), listOf(mark("stop-1", C, afterMove = true)), SeatOutcome.HELD, held = "화면 이탈"),
        snap(mark("move-1", C), listOf(mark("stop-1", C, afterMove = true), mark("stop-2", C)), SeatOutcome.CONFIRMED),
    )

    @Test
    fun `the constructor reads nothing and start reads once, a second start does nothing`() {
        val journal = CountingJournal(memory(needStop[0]))
        val m = manager(journal)
        assertEquals(0, journal.loads)
        noCalls()
        m.start()
        m.start()
        assertEquals(1, journal.loads)
        assertEquals(1, port.stops.size)
    }

    @Test
    fun `a zero or negative answer timeout is refused before anything is called`() {
        for (ms in listOf(0L, -1L)) {
            val journal = CountingJournal(memory(needStop[0]))
            val refused = runCatching { SeatEmergencyStop(port, journal, scheduler, ms) }
            assertTrue(refused.exceptionOrNull() is IllegalArgumentException)
            assertEquals(0, journal.loads)
            noCalls()
        }
    }

    @Test
    fun `nothing stored, or a stopped and consistent record, means no call and no lock`() {
        val empty = manager(memory(null))
        empty.start()
        noCalls()
        assertNull(empty.state.lock)
        assertNotNull(empty.emergencyStop())
        for (s in consistent) {
            val p = FakeSeatPort(mutableListOf())
            val journal = memory(s)
            val m = manager(journal, p)
            m.start()
            noCalls(p)
            assertNull(s.toString(), m.state.lock)
            assertTrue(journal.saved.isEmpty())
            assertNotNull(m.emergencyStop())
            assertTrue(p.stops.isEmpty())
        }
    }

    @Test
    fun `unreadable or untrustworthy targets are locked for a manual check with no call and the bytes kept`() {
        val f = File(temp.newFolder(), FileSeatJournal.FILE_NAME).also { it.writeText("{\"formatVersion\":2}") }
        val bytes = f.readBytes()
        val u = manager(FileSeatJournal(f))
        u.start()
        noCalls()
        assertNotNull(u.state.lock)
        assertTrue(u.state.notice!!.contains("직접 확인"))
        assertArrayEquals(bytes, f.readBytes())
        assertNotNull(u.emergencyStop())
        noCalls()

        val untrusted = listOf(
            snap(mark("move-1", E), axis = SeatAxis.HEIGHT),
            snap(mark("move-1", E), listOf(mark("move-1", E))), // repeated ID (only a memory journal can hold it)
            snap(mark(" ", E)),
        )
        for (s in untrusted) {
            val p = FakeSeatPort(mutableListOf())
            val journal = memory(s)
            val m = manager(journal, p)
            m.start()
            noCalls(p)
            assertNotNull(m.state.lock)
            assertTrue(journal.saved.isEmpty())
            assertEquals(s, journal.stored)
        }
    }

    @Test
    fun `a possibly unfinished earlier attempt gets one STOP, saved first, with earlier requests unchanged`() {
        for (s in needStop) {
            val ev = mutableListOf<String>()
            val p = FakeSeatPort(ev)
            val journal = FakeJournal(ev, s)
            val m = manager(journal, p, FakeScheduler(), newId = { "es-new" })
            m.start()
            assertEquals(s.toString(), 1, p.stops.size)
            assertTrue(p.subs.isEmpty() && p.reads == 0 && p.moveCalls.isEmpty() && p.ended.isEmpty())
            assertNotNull(m.state.lock)
            val saved = journal.stored!!
            assertEquals(s, saved.copy(stops = saved.stops.dropLast(1))) // job, original, MOVE, earlier STOPs as they were
            val added = saved.stops.last()
            assertEquals("es-new", added.requestId)
            assertEquals(E, added.state)
            assertEquals(s.move.state == C || s.move.state == F, added.afterMove)
            assertEquals(listOf("save:${s.move.state}", "stop"), ev.takeLast(2)) // saved before it was sent
            assertEquals(listOf("es-new"), m.state.sentStops)
        }
    }

    @Test
    fun `a failing save latches a fault but the STOP still goes, and the file may still have changed`() {
        val journal = memory(needStop[1]).also { it.fail = { true } }
        val m = manager(journal)
        m.start()
        assertEquals(1, port.stops.size)
        assertNotNull(m.state.storageFault)
        assertNotNull(m.state.lock)
        assertEquals("es-1", m.state.snapshot!!.stops.last().requestId) // still tracked in memory

        val f = File(temp.newFolder(), FileSeatJournal.FILE_NAME)
        assertTrue(FileSeatJournal(f).save(needStop[0]))
        val p = FakeSeatPort(mutableListOf())
        val m2 = manager(FileSeatJournal(f, syncDirectory = { throw IOException("fsync dir") }), p)
        m2.start()
        assertEquals(1, p.stops.size)
        assertNotNull(m2.state.storageFault)
        val onDisk = (FileSeatJournal(f).load() as SeatJournalLoad.Found).snapshot
        assertEquals(listOf("es-2"), onDisk.stops.map { it.requestId }) // replaced before the directory fsync failed
    }

    @Test
    fun `answers update only their own STOP once, timeouts end nothing, and the lock never lifts`() {
        val journal = memory(needStop[1]) // MOVE ENTERING, stop-1 unanswered
        val m = manager(journal)
        m.start()
        scheduler.advance(ANSWER_MS)
        var es1 = m.state.snapshot!!.stops.last()
        assertTrue(es1.timedOut)
        assertEquals(E, es1.state)
        assertEquals("STOP 응답 없음(결과 불명)", SeatEmergencyStop.wording(es1))
        assertTrue(journal.stored!!.stops.last().timedOut)

        assertNull(m.emergencyStop()) // again, with a new ID, while es-1 is unknown
        assertEquals(2, port.stops.size)
        assertEquals(listOf("es-1", "es-2"), m.state.sentStops)
        port.stops[0].ok("STOP") // es-1 answers late
        es1 = m.state.snapshot!!.stops.first { it.requestId == "es-1" }
        assertEquals(C, es1.state)
        assertTrue(es1.timedOut)
        assertEquals(E, m.state.snapshot!!.stops.first { it.requestId == "es-2" }.state)
        port.stops[0].ok("STOP") // repeated: changes nothing
        port.stops[0].err(SDK_ERROR)
        assertEquals(C, m.state.snapshot!!.stops.first { it.requestId == "es-1" }.state)

        port.stops[1].err(ProbeError("StopAlreadyQueued", "같은 STOP이 이미 대기 중이에요", null))
        val es2 = m.state.snapshot!!.stops.first { it.requestId == "es-2" }
        assertEquals(F, es2.state)
        assertTrue(es2.answer!!.contains("StopAlreadyQueued"))
        assertEquals("STOP 실패", SeatEmergencyStop.wording(es2))

        assertNull(m.emergencyStop())
        port.position = 20 // the seat even reads its original position
        port.stops[2].ok("STOP")
        assertEquals("STOP 응답 받음(실제 정지·복원은 확인 안 됨)", SeatEmergencyStop.wording(m.state.snapshot!!.stops.last()))
        // Every new STOP answered, yet the earlier MOVE and stop-1 stay as they were, and so does the lock.
        val now = journal.stored!!
        assertEquals(E, now.move.state)
        assertEquals(needStop[1].stops[0], now.stops[0])
        assertNull(now.outcome)
        assertNotNull(m.state.lock)
        assertEquals(0, port.reads)
        assertTrue(port.moveCalls.isEmpty() && port.ended.isEmpty())
        scheduler.advance(60_000)
        assertNotNull(m.state.lock)
    }

    @Test
    fun `a blank or used ID is never sent, and the lock stays`() {
        for (bad in listOf("", "  ", "move-1", "stop-1")) {
            val p = FakeSeatPort(mutableListOf())
            val journal = memory(needStop[1])
            val m = manager(journal, p, newId = { bad })
            m.start()
            assertTrue("'$bad'", p.stops.isEmpty())
            assertNotNull(m.state.lock)
            assertTrue(m.state.notice!!.contains("ID"))
            assertTrue(journal.saved.isEmpty())
        }
        val p = FakeSeatPort(mutableListOf())
        val m = manager(memory(needStop[1]), p, newId = { "same" })
        m.start()
        assertEquals(1, p.stops.size)
        assertNotNull(m.emergencyStop()) // the supplier repeats itself: not sent
        assertEquals(1, p.stops.size)
    }

    @Test
    fun `a restarted process starts from the stored record and sends a new STOP with a new ID`() {
        val f = File(temp.newFolder(), FileSeatJournal.FILE_NAME)
        assertTrue(FileSeatJournal(f).save(needStop[1]))
        val first = manager(FileSeatJournal(f), FakeSeatPort(mutableListOf()), FakeScheduler(), newId = { "run1-stop" })
        first.start() // its STOP is never answered; the process "dies" here

        val p2 = FakeSeatPort(mutableListOf())
        val second = manager(FileSeatJournal(f), p2, FakeScheduler(), newId = { "run2-stop" })
        second.start()
        assertEquals(1, p2.stops.size)
        val stored = (FileSeatJournal(f).load() as SeatJournalLoad.Found).snapshot
        assertEquals(listOf("stop-1", "run1-stop", "run2-stop"), stored.stops.map { it.requestId })
        assertEquals(E, stored.move.state)
        assertEquals(E, stored.stops[1].state) // the earlier run's STOP stays unknown
        assertNotEquals("run1-stop", second.state.sentStops.single())
        assertNotNull(second.state.lock)

        // A core starting on the same record afterwards is still locked.
        val core = SeatMoveCore(SeatAxis.BACKREST, FakeSeatPort(mutableListOf()), FileSeatJournal(f), FakeScheduler(), { null }, { null })
        assertNotNull(core.state.lock)
        assertFalse(f.parentFile!!.list()!!.any { it != f.name && !it.endsWith(FileSeatJournal.TEMP_SUFFIX) })
    }
}
