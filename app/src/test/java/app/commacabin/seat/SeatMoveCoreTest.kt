package app.commacabin.seat

import app.commacabin.control.Move
import app.commacabin.control.SeatAxis
import app.commacabin.probe.Dispatch
import app.commacabin.probe.FakeScheduler
import app.commacabin.probe.ProbeError
import app.commacabin.probe.SDK_ERROR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lane-like seat port: a MOVE waits in a queue until its turn, then gate, onStart and the SDK entry; a STOP gives up the
 * MOVEs not entered yet (one past onStart is reported not sent at once, one at its gate once the gate returns), as
 * SdkLane does. The test answers every SDK callback itself.
 */
class FakeSeatPort(private val events: MutableList<String>) : SeatAxisPort {
    class Answer(val ok: (String?) -> Unit, val err: (ProbeError) -> Unit)
    class ReadAnswer(val ok: (Int?) -> Unit, val err: (ProbeError) -> Unit)
    class Sub(val registered: (ProbeError?) -> Unit, val update: (Int?) -> Unit, val error: (ProbeError) -> Unit) {
        var closed = false
    }

    private class Queued(val command: Move, val key: String, val dispatch: Dispatch, val answer: Answer) {
        var granted = false
        var abandoned = false
        var reported = false
    }

    /** What a read returns now (null: the SDK answers no value). */
    var position: Int? = 0
    var registration: ProbeError? = null
    var holdRegistration = false
    var readError: ProbeError? = null
    var holdReads = false
    val heldReads = mutableListOf<ReadAnswer>()
    var holdDispatch = false
    /** False: a STOP gives up nothing, only the core's own gate protects. */
    var cancelOnStop = true
    var afterStart: () -> Unit = {}
    var gating = false

    val subs = mutableListOf<Sub>()
    val moveCalls = mutableListOf<Move>()
    val entered = mutableListOf<Move>()
    val moves = mutableListOf<Answer>()
    val notSent = mutableListOf<String>()
    val stops = mutableListOf<Answer>()
    val ended = mutableListOf<String>()
    var reads = 0
    private val queue = mutableListOf<Queued>()
    private var inFlight: Queued? = null

    override fun observe(axis: SeatAxis, onRegistered: (ProbeError?) -> Unit, onUpdate: (Int?) -> Unit, onError: (ProbeError) -> Unit): AutoCloseable {
        events += "observe"
        val sub = Sub(onRegistered, onUpdate, onError)
        subs += sub
        if (!holdRegistration) onRegistered(registration)
        return AutoCloseable { sub.closed = true }
    }

    /** The live listener reports [v]; a read returns it from now on. */
    fun emit(v: Int?) {
        if (v != null) position = v
        subs.filter { !it.closed }.forEach { it.update(v) }
    }

    override fun read(axis: SeatAxis, ok: (Int?) -> Unit, err: (ProbeError) -> Unit) {
        reads++
        events += "read"
        val e = readError
        when {
            holdReads -> heldReads += ReadAnswer(ok, err)
            e != null -> err(e)
            else -> ok(position)
        }
    }

    override fun move(axis: SeatAxis, command: Move, motionKey: String, dispatch: Dispatch, ok: (echo: String?) -> Unit, err: (ProbeError) -> Unit) {
        events += "queue:$command"
        moveCalls += command
        queue += Queued(command, motionKey, dispatch, Answer(ok, err))
        if (!holdDispatch) runQueued()
    }

    fun runQueued() {
        while (queue.isNotEmpty()) enter(queue.removeAt(0))
    }

    private fun report(q: Queued, why: String) {
        if (q.reported) return
        q.reported = true
        notSent += q.key
        events += "notSent:${q.key}"
        q.dispatch.onNotSent(why)
    }

    private fun enter(q: Queued) {
        inFlight = q
        try {
            gating = true
            val why = q.dispatch.gate()
            gating = false
            if (why != null || q.abandoned) return report(q, why ?: "STOP")
            q.dispatch.onStart()
            if (q.abandoned) return report(q, "STOP")
            q.granted = true
            afterStart()
            if (q.reported) return
            entered += q.command
            moves += q.answer
            events += "enter:${q.command}"
        } finally {
            inFlight = null
            gating = false
        }
    }

    override fun stop(axis: SeatAxis, ok: (echo: String?) -> Unit, err: (ProbeError) -> Unit) {
        events += "stop"
        stops += Answer(ok, err)
        if (!cancelOnStop) return
        queue.toList().forEach {
            queue.remove(it)
            report(it, "STOP")
        }
        inFlight?.let { if (it.granted) report(it, "STOP") else it.abandoned = true }
    }

    override fun endMotion(motionKey: String) {
        ended += motionKey
        events += "end:$motionKey"
    }
}

class FakeJournal(private val events: MutableList<String>, var stored: SeatSnapshot? = null) : SeatJournal {
    var unreadable: String? = null
    var fail: (SeatSnapshot) -> Boolean = { false }
    val saved = mutableListOf<SeatSnapshot>()

    override fun load(): SeatJournalLoad = when {
        unreadable != null -> SeatJournalLoad.Unreadable(unreadable!!)
        stored != null -> SeatJournalLoad.Found(stored!!)
        else -> SeatJournalLoad.Empty
    }

    override fun save(snapshot: SeatSnapshot): Boolean {
        if (fail(snapshot)) {
            events += "saveFailed"
            return false
        }
        saved += snapshot
        stored = snapshot
        events += "save:${snapshot.move.state}"
        return true
    }

    var failClear = false
    var clears = 0

    override fun clear(expected: SeatSnapshot): Boolean {
        events += "clear"
        if (failClear || stored != expected) return false
        clears++
        stored = null
        return true
    }
}

val LIMITS = SeatMoveLimits(
    range = 0..100, tolerance = 2, increaseWith = Move.FORWARD, noChangeMs = 500, maxMoveMs = 5_000, answerMs = 3_000,
    settleMs = 300, confirmReads = 2, confirmIntervalMs = 200,
)

fun req(
    start: Int = 20,
    target: Int = 60,
    original: Int = start,
    purpose: SeatPurpose = SeatPurpose.APPLY,
    job: String = "job-1",
    n: Int = 1,
    limits: SeatMoveLimits = LIMITS,
) = SeatMoveRequest(job, purpose, "move-$n", "stop-$n", "final-$n", original, start, target, limits)

private class Rig(stored: SeatSnapshot? = null, unreadable: String? = null) {
    val events = mutableListOf<String>()
    val port = FakeSeatPort(events)
    val journal = FakeJournal(events, stored).also { it.unreadable = unreadable }
    val scheduler = FakeScheduler()
    var safe: String? = null
    var other: String? = null
    var onSafety: () -> Unit = {}

    fun newCore() = SeatMoveCore(SeatAxis.BACKREST, port, journal, scheduler, { onSafety(); safe }, { other })

    var core = newCore()
    val s get() = core.state
    val snap get() = core.state.snapshot!!

    /** The seat reads [SeatMoveRequest.start] when the attempt begins. */
    fun begin(r: SeatMoveRequest): String? {
        port.position = r.start
        return core.begin(r)
    }

    fun confirmAll(l: SeatMoveLimits = LIMITS) {
        scheduler.advance(l.settleMs)
        repeat(l.confirmReads - 1) { scheduler.advance(l.confirmIntervalMs) }
    }

    fun noPortCalls() {
        assertTrue(port.subs.isEmpty())
        assertEquals(0, port.reads)
        assertTrue(port.moveCalls.isEmpty())
        assertTrue(port.stops.isEmpty())
    }
}


class SeatMoveCoreTest {

    private fun applyConfirmed(r: Rig) {
        assertNull(r.begin(req(start = 20, target = 60)))
        r.port.moves[0].ok("FORWARD")
        r.port.emit(59)
        r.port.stops[0].ok("STOP")
        r.confirmAll()
        assertEquals(SeatOutcome.CONFIRMED, r.s.outcome)
    }

    // --- normal targets ---

    @Test
    fun `increase - STOP on target, then only reads after the motion ended confirm it`() {
        val r = Rig()
        assertNull(r.begin(req(start = 20, target = 60)))
        assertEquals(listOf(Move.FORWARD), r.port.entered)
        assertEquals(SeatPhase.MOVING, r.s.phase)
        r.port.moves[0].ok("FORWARD")
        r.port.emit(35)
        r.scheduler.advance(100)
        r.port.emit(50)
        assertTrue(r.port.stops.isEmpty())
        r.port.emit(59)
        assertEquals(1, r.port.stops.size)
        assertEquals(SeatPhase.STOPPING, r.s.phase)
        r.port.stops[0].ok("STOP")
        assertEquals(listOf("move-1"), r.port.ended)
        assertEquals(1, r.port.reads) // the read before the MOVE only
        r.confirmAll()
        assertEquals(SeatOutcome.CONFIRMED, r.s.outcome)
        assertEquals(listOf<Int?>(59, 59), r.s.reads)

        val e = r.events
        assertTrue(e.indexOf("save:SAVED") < e.indexOf("queue:FORWARD"))
        assertTrue(e.indexOf("save:ENTERING") < e.indexOf("enter:FORWARD"))
        val end = e.indexOf("end:move-1")
        assertEquals(1, e.subList(0, end).count { it == "read" })
        assertEquals(2, e.subList(end, e.size).count { it == "read" })
        val j = r.journal.stored!!
        assertEquals(SeatOutcome.CONFIRMED, j.outcome)
        assertEquals(SeatPurpose.APPLY, j.purpose)
        assertEquals(20, j.original)
        assertNull(j.held)
        assertTrue(j.stops.single().afterMove)
        assertNull(r.s.lock)
    }

    @Test
    fun `restore - a new run reuses the kept apply record and moves back the other way`() {
        val r = Rig()
        applyConfirmed(r)
        r.core = r.newCore()
        assertNull(r.s.lock)
        assertEquals(20, r.snap.original)
        assertNull(r.begin(req(purpose = SeatPurpose.RESTORE, start = 59, target = 20, original = 20, n = 2)))
        assertEquals(listOf(Move.FORWARD, Move.BACKWARD), r.port.entered)
        r.port.moves[1].ok("BACKWARD")
        r.port.emit(40)
        r.port.emit(21)
        r.port.stops[1].ok("STOP")
        r.confirmAll()
        assertEquals(SeatOutcome.CONFIRMED, r.s.outcome)
        val j = r.journal.stored!!
        assertEquals(SeatPurpose.RESTORE, j.purpose)
        assertEquals(20, j.original)
        assertEquals(20, j.target)
        assertEquals(listOf("move-1", "move-2"), r.port.ended)
    }

    @Test
    fun `zero is a normal start and target, and the increasing command is the caller's`() {
        val lim = LIMITS.copy(increaseWith = Move.BACKWARD)
        val r = Rig()
        assertNull(r.begin(req(start = 0, target = 40, limits = lim)))
        assertEquals(listOf(Move.BACKWARD), r.port.entered)
        r.port.moves[0].ok(null)
        r.port.emit(10)
        r.port.emit(39)
        r.port.stops[0].ok(null)
        r.confirmAll(lim)
        assertEquals(SeatOutcome.CONFIRMED, r.s.outcome)

        assertNull(r.begin(req(purpose = SeatPurpose.RESTORE, start = 39, target = 0, original = 0, n = 2, limits = lim)))
        assertEquals(Move.FORWARD, r.port.entered.last())
        r.port.moves[1].ok(null)
        r.port.emit(1)
        r.port.stops[1].ok(null)
        r.confirmAll(lim)
        assertEquals(SeatOutcome.CONFIRMED, r.s.outcome)
        assertEquals(listOf<Int?>(1, 1), r.s.reads)
    }

    // --- nothing sent without its preconditions ---

    @Test
    fun `a failed save sends no MOVE, and a STOP still goes after one`() {
        val r = Rig()
        r.journal.fail = { true }
        assertNull(r.begin(req()))
        assertEquals(SeatOutcome.NOT_SENT, r.s.outcome)
        assertTrue(r.port.moveCalls.isEmpty())
        assertNull(r.s.snapshot)
        assertTrue(r.s.lock!!.contains("저장 실패"))
        assertNotNull(r.begin(req(n = 2)))

        val g = Rig()
        g.journal.fail = { it.move.state == SeatRequestState.ENTERING } // the save at the gate fails
        assertNull(g.begin(req()))
        assertEquals(1, g.port.moveCalls.size)
        assertTrue(g.port.entered.isEmpty())
        assertEquals(listOf("move-1"), g.port.notSent)
        assertEquals(SeatOutcome.NOT_SENT, g.s.outcome)

        val m = Rig()
        assertNull(m.begin(req()))
        m.journal.fail = { true } // storage breaks while the seat moves
        m.port.emit(59)
        assertEquals(1, m.port.stops.size)
        assertTrue(m.s.lock!!.contains("저장 실패"))
        assertNull(m.core.stop("비상", "stop-e"))
        assertEquals(2, m.port.stops.size)
    }

    @Test
    fun `no MOVE without a confirmed listener registration`() {
        val r = Rig()
        r.port.registration = SDK_ERROR
        assertNull(r.begin(req()))
        assertEquals(SeatOutcome.NOT_SENT, r.s.outcome)
        assertEquals(0, r.port.reads)
        assertTrue(r.port.moveCalls.isEmpty())
        assertTrue(r.journal.saved.isEmpty())

        val h = Rig()
        h.port.holdRegistration = true
        assertNull(h.begin(req()))
        h.scheduler.advance(LIMITS.answerMs)
        assertEquals(SeatOutcome.NOT_SENT, h.s.outcome)
        assertTrue(h.s.reason!!.contains("등록 응답 없음"))
        h.port.subs[0].registered(null) // late: starts nothing
        assertEquals(0, h.port.reads)
        assertTrue(h.port.moveCalls.isEmpty())
    }

    @Test
    fun `a position other than the start, before or while the MOVE waits, sends nothing`() {
        val r = Rig()
        r.port.position = 25
        assertNull(r.core.begin(req(start = 20)))
        assertEquals(SeatOutcome.NOT_SENT, r.s.outcome)
        assertTrue(r.port.moveCalls.isEmpty())
        assertTrue(r.journal.saved.isEmpty())

        val q = Rig()
        q.port.holdDispatch = true
        assertNull(q.begin(req()))
        q.port.emit(25) // the user moved the seat meanwhile
        q.port.runQueued()
        assertTrue(q.port.entered.isEmpty())
        assertEquals(SeatOutcome.NOT_SENT, q.s.outcome)
        assertTrue(q.s.reason!!.contains("바뀌었어요"))
    }

    @Test
    fun `requests the core cannot act on safely are refused before anything is saved or sent`() {
        val bad = listOf(
            req(start = 20, target = 21), // already within tolerance
            req(purpose = SeatPurpose.RESTORE, start = 60, target = 30, original = 20),
            req(limits = LIMITS.copy(increaseWith = Move.STOP)),
            req(limits = LIMITS.copy(tolerance = -1)),
            req(target = 101),
            req(limits = LIMITS.copy(confirmReads = 0)),
            req(limits = LIMITS.copy(noChangeMs = 0)),
            req().copy(stopRequestId = "move-1"),
            req().copy(finalStopRequestId = " "),
        )
        bad.forEach { b ->
            val r = Rig()
            assertNotNull(b.toString(), r.begin(b))
            r.noPortCalls()
            assertTrue(r.journal.saved.isEmpty())
        }
        val r = Rig()
        r.safe = "기어 P 아님"
        assertEquals("기어 P 아님", r.begin(req()))
        r.noPortCalls()

        val busy = Rig()
        busy.port.holdDispatch = true
        assertNull(busy.begin(req()))
        val kept = busy.journal.stored
        val saves = busy.journal.saved.size
        assertNotNull(busy.s.lock)
        assertEquals(busy.s.lock, busy.core.begin(req(n = 2))) // refused by the lock; the seat input is not touched
        assertEquals(1, busy.port.moveCalls.size)
        assertEquals(1, busy.port.subs.size)
        assertEquals(1, busy.port.reads)
        assertEquals(saves, busy.journal.saved.size)
        assertEquals(kept, busy.journal.stored)
        assertEquals("move-1", busy.snap.move.requestId)
    }

    // --- STOP before the SDK entry ---

    @Test
    fun `STOP before entry - the waiting MOVE never enters, also when only the core's gate refuses it`() {
        for (cancel in listOf(true, false)) {
            val r = Rig()
            r.port.holdDispatch = true
            r.port.cancelOnStop = cancel
            assertNull(r.begin(req()))
            assertEquals(SeatPhase.QUEUED, r.s.phase)
            assertNull(r.core.stop("수동", "stop-m"))
            r.port.runQueued()
            assertTrue(r.port.entered.isEmpty())
            assertEquals(1, r.port.stops.size)
            assertEquals(listOf("move-1"), r.port.notSent)
            assertEquals(SeatRequestState.NOT_SENT, r.snap.move.state)
            assertEquals("stop-m", r.snap.stops.single().requestId)
            assertNull(r.s.outcome) // waits for the STOP answer
            assertNotNull(r.s.lock)
            r.port.stops[0].ok("STOP")
            assertEquals(SeatOutcome.NOT_SENT, r.s.outcome)
            assertTrue(r.port.ended.isEmpty())
            assertNull(r.s.lock)
        }
    }

    @Test
    fun `STEP 17 regression - a STOP after onStart or during the gate still ends the MOVE not sent`() {
        val a = Rig()
        a.port.afterStart = { a.core.stop("수동", "stop-m") }
        val g = Rig()
        g.onSafety = { if (g.port.gating && g.port.stops.isEmpty()) g.core.stop("수동", "stop-m") }
        for (r in listOf(a, g)) {
            assertNull(r.begin(req()))
            assertTrue(r.port.entered.isEmpty())
            assertEquals(1, r.port.stops.size)
            assertEquals(listOf("move-1"), r.port.notSent)
            assertEquals(SeatRequestState.NOT_SENT, r.snap.move.state)
            r.port.stops[0].ok("STOP")
            assertEquals(SeatOutcome.NOT_SENT, r.s.outcome)
            assertTrue(r.port.ended.isEmpty())
        }
    }

    // --- no false success ---

    @Test
    fun `an echo of the target is no evidence - no change STOPs on the limit, held whatever is read after`() {
        val r = Rig()
        assertNull(r.begin(req(start = 20, target = 60)))
        r.port.moves[0].ok("60")
        assertEquals(SeatPhase.MOVING, r.s.phase)
        assertTrue(r.port.stops.isEmpty())
        r.scheduler.advance(LIMITS.noChangeMs)
        assertEquals(1, r.port.stops.size)
        assertTrue(r.s.reason!!.contains("무변화"))
        r.port.stops[0].ok("STOP")
        r.port.position = 60
        r.confirmAll()
        assertEquals(SeatOutcome.HELD, r.s.outcome)
        assertTrue(r.journal.stored!!.held!!.contains("무변화"))
    }

    @Test
    fun `values seen before the motion ended confirm nothing, and nothing is read before it`() {
        val r = Rig()
        assertNull(r.begin(req(start = 20, target = 60)))
        r.port.moves[0].ok("FORWARD")
        r.port.emit(60)
        assertEquals(1, r.port.stops.size)
        r.scheduler.advance(10_000)
        assertEquals(1, r.port.reads)
        assertEquals(SeatPhase.STOPPING, r.s.phase)
        assertNull(r.s.outcome)
        r.port.position = 50 // where the seat really ended
        r.port.stops[0].ok("STOP")
        r.confirmAll()
        assertEquals(SeatOutcome.UNCONFIRMED, r.s.outcome)
        assertEquals(listOf<Int?>(50), r.s.reads)
    }

    @Test
    fun `review 1 - a STOP for any cause but the target stays held even when the seat ends on target`() {
        val lim = LIMITS.copy(maxMoveMs = 1_000)
        val causes = listOf<Pair<String, (Rig) -> Unit>>(
            "주차 조건 상실" to { assertNull(it.core.interrupt("주차 조건 상실", "stop-i")) },
            "조수석 문 열림" to { assertNull(it.core.interrupt("조수석 문 열림", "stop-i")) },
            "화면 이탈" to { assertNull(it.core.interrupt("화면 이탈", "stop-i")) },
            "수동 STOP" to { assertNull(it.core.stop("사용자", "stop-u")) },
            "리스너 실패" to { it.port.subs[0].error(SDK_ERROR) },
            "반대 방향" to { it.port.emit(5); it.port.emit(3) },
            "값 없음" to { it.port.emit(null) },
            "범위" to { it.port.emit(101) },
            "무변화" to { it.scheduler.advance(lim.noChangeMs) },
            "이동 상한" to { repeat(10) { i -> it.port.emit(1 + i); it.scheduler.advance(100) } },
            "이동 요청 실패" to { it.port.moves[0].err(SDK_ERROR) },
        )
        for ((cause, input) in causes) {
            val r = Rig()
            assertNull(r.begin(req(start = 0, target = 20, limits = lim)))
            if (cause != "이동 요청 실패") r.port.moves[0].ok("FORWARD")
            input(r)
            assertEquals(cause, 1, r.port.stops.size)
            r.port.stops[0].ok("STOP")
            assertEquals(cause, listOf("move-1"), r.port.ended)
            r.port.position = 20 // the seat happens to end on target
            r.confirmAll(lim)
            assertEquals(cause, SeatOutcome.HELD, r.s.outcome)
            assertTrue(cause, r.s.reason!!.contains(cause))
            assertTrue(cause, r.journal.stored!!.held!!.contains(cause))
            assertEquals(cause, 1, r.port.moveCalls.size)
        }
    }

    @Test
    fun `review 1 - an interrupt while confirming cancels it and holds, and parking coming back resumes nothing`() {
        val r = Rig()
        assertNull(r.begin(req(start = 0, target = 20)))
        r.port.moves[0].ok("FORWARD")
        r.port.emit(20)
        r.port.stops[0].ok("STOP")
        r.scheduler.advance(LIMITS.settleMs) // the first confirming read, on target
        assertEquals(listOf<Int?>(20), r.s.reads)
        r.safe = "기어 P 아님"
        assertNotNull(r.core.interrupt("주차 조건 상실", "stop-i")) // already stopped: only the confirmation is cancelled
        assertEquals(SeatOutcome.HELD, r.s.outcome)
        assertTrue(r.s.reason!!.contains("주차 조건 상실"))
        r.safe = null
        r.scheduler.advance(10_000)
        assertEquals(2, r.port.reads) // the second confirming read never ran
        assertEquals(1, r.port.moveCalls.size)
        assertEquals(1, r.port.stops.size)
        assertEquals(SeatOutcome.HELD, r.journal.stored!!.outcome)

        // Interrupted while its normal STOP is unanswered: a safety STOP too, and held.
        val s = Rig()
        assertNull(s.begin(req(start = 0, target = 20)))
        s.port.moves[0].ok("FORWARD")
        s.port.emit(20)
        assertNull(s.core.interrupt("화면 이탈", "stop-i"))
        assertEquals(2, s.port.stops.size)
        s.port.stops[0].ok("STOP")
        s.port.stops[1].ok("STOP")
        s.confirmAll()
        assertEquals(SeatOutcome.HELD, s.s.outcome)
    }

    @Test
    fun `review 5 - a listener failure while stopping or confirming stays held, whatever is read after`() {
        // Normal target STOP sent, the listener fails before the STOP answer.
        val s = Rig()
        assertNull(s.begin(req(start = 0, target = 20)))
        s.port.moves[0].ok("FORWARD")
        s.port.emit(20)
        assertEquals(SeatPhase.STOPPING, s.s.phase)
        s.port.subs[0].error(SDK_ERROR)
        assertEquals(1, s.port.stops.size)
        s.port.stops[0].ok("STOP")
        s.confirmAll()
        assertEquals(listOf<Int?>(20, 20), s.s.reads)
        assertEquals(SeatOutcome.HELD, s.s.outcome)
        assertTrue(s.journal.stored!!.held!!.contains("리스너 실패"))

        // STOP answered first, the listener fails while the MOVE answer is awaited.
        val w = Rig()
        assertNull(w.begin(req(start = 0, target = 20)))
        w.port.emit(20)
        w.port.stops[0].ok("STOP")
        w.port.subs[0].error(SDK_ERROR)
        w.port.moves[0].ok("FORWARD")
        w.port.stops[1].ok("STOP")
        w.confirmAll()
        assertEquals(SeatOutcome.HELD, w.s.outcome)

        // While confirming: one read on target before the failure, the next would be on target too.
        val c = Rig()
        assertNull(c.begin(req(start = 0, target = 20)))
        c.port.moves[0].ok("FORWARD")
        c.port.emit(20)
        c.port.stops[0].ok("STOP")
        c.scheduler.advance(LIMITS.settleMs)
        c.port.subs[0].error(SDK_ERROR)
        assertEquals(SeatOutcome.HELD, c.s.outcome)
        c.scheduler.advance(10_000)
        assertEquals(2, c.port.reads)
        assertEquals(1, c.port.stops.size)
    }

    @Test
    fun `review 2 - extreme, out-of-range or missing values after the STOP are never on target`() {
        val tight = LIMITS.copy(tolerance = 0)
        val full = tight.copy(range = Int.MIN_VALUE..Int.MAX_VALUE)
        val cases = listOf<Pair<SeatMoveLimits, Int?>>(
            tight to Int.MIN_VALUE,
            tight to Int.MAX_VALUE,
            tight to -1,
            tight to 101,
            tight to null,
            full to Int.MIN_VALUE,
            full to Int.MAX_VALUE,
        )
        for ((lim, read) in cases) {
            val r = Rig()
            assertNull(r.begin(req(start = 10, target = 0, limits = lim)))
            r.port.moves[0].ok("BACKWARD")
            r.port.emit(0)
            r.port.stops[0].ok("STOP")
            r.port.position = read
            r.confirmAll(lim)
            assertEquals("$read in ${lim.range}", SeatOutcome.UNCONFIRMED, r.s.outcome)
        }
        // The same values from the listener while confirming.
        for (v in listOf(Int.MIN_VALUE, Int.MAX_VALUE, null)) {
            val r = Rig()
            assertNull(r.begin(req(start = 10, target = 0, limits = tight)))
            r.port.moves[0].ok("BACKWARD")
            r.port.emit(0)
            r.port.stops[0].ok("STOP")
            r.port.emit(v)
            assertEquals("$v", SeatOutcome.UNCONFIRMED, r.s.outcome)
        }
        // A start and target at the Int ends are accepted without overflow and reached normally.
        val r = Rig()
        assertNull(r.begin(req(start = Int.MAX_VALUE, target = Int.MIN_VALUE, limits = full)))
        r.port.moves[0].ok("BACKWARD")
        r.port.emit(Int.MIN_VALUE)
        assertEquals(1, r.port.stops.size)
        r.port.stops[0].ok("STOP")
        r.confirmAll(full)
        assertEquals(SeatOutcome.CONFIRMED, r.s.outcome)
    }

    // --- MOVE and STOP answers in any order ---

    @Test
    fun `STOP answered before the MOVE - no release or read until a STOP sent after the MOVE answer ends it`() {
        val r = Rig()
        assertNull(r.begin(req(start = 20, target = 60)))
        r.port.emit(59)
        r.port.stops[0].ok("STOP") // before the MOVE's answer
        r.scheduler.advance(LIMITS.settleMs + 1_000)
        assertTrue(r.port.ended.isEmpty())
        assertEquals(1, r.port.reads)
        assertEquals(SeatPhase.STOPPING, r.s.phase)
        assertNull(r.s.outcome)
        assertEquals(SeatRequestState.COMPLETED, r.snap.stops[0].state)
        assertFalse(r.snap.stops[0].afterMove)

        r.port.moves[0].ok("FORWARD") // the MOVE answers last
        assertEquals(2, r.port.stops.size)
        assertEquals(listOf("stop-1", "final-1"), r.snap.stops.map { it.requestId })
        assertTrue(r.snap.stops[1].afterMove)
        assertTrue(r.port.ended.isEmpty())
        r.port.stops[1].ok("STOP")
        assertEquals(listOf("move-1"), r.port.ended)
        r.confirmAll()
        assertEquals(SeatOutcome.CONFIRMED, r.s.outcome)
        val e = r.events
        assertTrue(e.lastIndexOf("stop") < e.indexOf("end:move-1"))
        assertEquals(1, e.subList(0, e.indexOf("end:move-1")).count { it == "read" })

        // The MOVE never answers: the STOP is a safety step only; the motion and the lock stay.
        val n = Rig()
        assertNull(n.begin(req()))
        n.port.emit(59)
        n.port.stops[0].ok("STOP")
        n.scheduler.advance(60_000)
        assertEquals(SeatPhase.STOPPING, n.s.phase)
        assertTrue(n.port.ended.isEmpty())
        assertEquals(1, n.port.reads)
        assertEquals(1, n.port.stops.size)
        assertTrue(n.snap.move.timedOut)
        assertNotNull(n.s.lock)
    }

    @Test
    fun `MOVE pending, STOP1 pending, MOVE answers - STOP2 goes at once, and STOP2 done with STOP1 pending releases nothing`() {
        val r = Rig()
        assertNull(r.begin(req(start = 20, target = 60)))
        r.port.emit(59) // STOP1, the MOVE not answered yet
        r.port.moves[0].ok("FORWARD")
        assertEquals(2, r.port.stops.size) // STOP2 sent without waiting for STOP1
        assertTrue(r.snap.stops[1].afterMove)
        r.port.stops[1].ok("STOP")
        assertEquals(SeatRequestState.ENTERING, r.snap.stops[0].state)
        r.scheduler.advance(LIMITS.answerMs)
        assertTrue(r.snap.stops[0].timedOut)
        assertTrue(r.port.ended.isEmpty())
        assertEquals(1, r.port.reads)
        assertTrue(r.s.lock!!.contains("stop-1"))
        assertNotNull(r.core.begin(req(n = 2)))

        r.port.stops[0].ok("STOP") // STOP1 known at last
        assertEquals(listOf("move-1"), r.port.ended)
        r.confirmAll()
        assertEquals(SeatOutcome.CONFIRMED, r.s.outcome)
        assertEquals(2, r.port.stops.size)

        // STOP1 never answers: still locked, nothing released or read.
        val n = Rig()
        assertNull(n.begin(req()))
        n.port.emit(59)
        n.port.moves[0].ok("FORWARD")
        n.port.stops[1].ok("STOP")
        n.scheduler.advance(60_000)
        assertTrue(n.port.ended.isEmpty())
        assertEquals(1, n.port.reads)
        assertNotNull(n.s.lock)
        assertNull(n.journal.stored!!.outcome)
    }

    @Test
    fun `an emergency STOP goes while an earlier STOP is unanswered, each tracked on its own`() {
        val r = Rig()
        assertNull(r.begin(req(start = 20, target = 60)))
        r.port.emit(59) // STOP1, unanswered
        assertNull(r.core.stop("비상", "stop-e"))
        assertEquals(2, r.port.stops.size)
        assertEquals(listOf("stop-1", "stop-e"), r.snap.stops.map { it.requestId })
        r.port.moves[0].ok("FORWARD")
        assertEquals(3, r.port.stops.size) // and the STOP after the MOVE answer
        r.port.stops[2].ok("STOP")
        r.port.stops[1].ok("STOP")
        assertTrue(r.port.ended.isEmpty())
        r.port.stops[0].ok("STOP")
        assertEquals(listOf("move-1"), r.port.ended)
        r.confirmAll()
        assertEquals(SeatOutcome.HELD, r.s.outcome)
        assertTrue(r.s.reason!!.contains("수동 STOP"))
    }

    @Test
    fun `a timeout ends nothing - pending kept in the journal and locked, late answers settle only their own request`() {
        val r = Rig()
        assertNull(r.begin(req(start = 20, target = 60)))
        r.port.emit(59)
        r.scheduler.advance(LIMITS.answerMs)
        assertTrue(r.snap.move.timedOut)
        assertTrue(r.snap.stops[0].timedOut)
        assertTrue(r.journal.stored!!.move.timedOut && r.journal.stored!!.stops[0].timedOut)
        assertFalse(r.snap.move.terminal)
        assertNull(r.journal.stored!!.outcome)
        assertNotNull(r.s.lock)
        assertEquals(r.s.lock, r.core.begin(req(n = 2)))

        r.port.stops[0].ok("STOP") // late
        assertEquals(SeatRequestState.COMPLETED, r.snap.stops[0].state)
        assertFalse(r.snap.move.terminal)
        assertTrue(r.port.ended.isEmpty())
        assertEquals(1, r.port.reads)
        assertNotNull(r.s.lock)

        r.port.moves[0].ok("FORWARD") // late too: one more STOP, nothing else
        assertEquals(2, r.port.stops.size)
        assertEquals(1, r.port.moveCalls.size)
        r.port.stops[1].ok("STOP")
        r.confirmAll()
        assertEquals(SeatOutcome.CONFIRMED, r.s.outcome)
        assertEquals(1, r.port.entered.size)
    }

    // --- limits and bad inputs ---

    @Test
    fun `a seat that keeps reading 0 STOPs on the no-change limit, and one too slow on the move limit`() {
        val r = Rig()
        assertNull(r.begin(req(start = 0, target = 50)))
        r.port.moves[0].ok("FORWARD")
        repeat(5) {
            r.port.emit(0)
            r.scheduler.advance(100)
        }
        assertEquals(1, r.port.stops.size)
        assertTrue(r.s.reason!!.contains("무변화"))
        r.port.stops[0].ok("STOP")
        r.confirmAll()
        assertEquals(SeatOutcome.HELD, r.s.outcome)
        assertEquals(listOf<Int?>(0), r.s.reads)

        val lim = LIMITS.copy(maxMoveMs = 1_000)
        val slow = Rig()
        assertNull(slow.begin(req(start = 20, target = 90, limits = lim)))
        slow.port.moves[0].ok("FORWARD")
        repeat(10) {
            slow.port.emit(21 + it)
            slow.scheduler.advance(100)
        }
        assertEquals(1, slow.port.stops.size)
        assertTrue(slow.s.reason!!.contains("이동 상한"))
        slow.port.stops[0].ok("STOP")
        slow.confirmAll(lim)
        assertEquals(SeatOutcome.HELD, slow.s.outcome)
    }

    @Test
    fun `wrong direction, a missing or out-of-range value and a listener failure STOP at once, once`() {
        val cases = listOf<Pair<String, (Rig) -> Unit>>(
            "반대 방향" to { it.port.emit(34) },
            "값 없음" to { it.port.emit(null) },
            "범위" to { it.port.emit(101) },
            "리스너 실패" to { it.port.subs[0].error(SDK_ERROR) },
        )
        for ((why, input) in cases) {
            val r = Rig()
            assertNull(r.begin(req(start = 30, target = 60)))
            r.port.moves[0].ok("FORWARD")
            r.port.emit(35)
            input(r)
            assertEquals(why, 1, r.port.stops.size)
            assertTrue(r.s.reason!!.contains(why))
            r.port.emit(60)
            assertEquals(why, 1, r.port.stops.size)
        }
    }

    @Test
    fun `after the motion ended a failed read or a moving value is unconfirmed`() {
        val r = Rig()
        assertNull(r.begin(req()))
        r.port.moves[0].ok("FORWARD")
        r.port.emit(59)
        r.port.stops[0].ok("STOP")
        r.port.readError = SDK_ERROR
        r.confirmAll()
        assertEquals(SeatOutcome.UNCONFIRMED, r.s.outcome)
        assertTrue(r.s.reason!!.contains("조회 실패"))

        val d = Rig()
        assertNull(d.begin(req()))
        d.port.moves[0].ok("FORWARD")
        d.port.emit(59)
        d.port.stops[0].ok("STOP")
        d.scheduler.advance(100)
        d.port.emit(50) // still moving after the STOP
        assertEquals(SeatOutcome.UNCONFIRMED, d.s.outcome)
        assertEquals(1, d.port.reads)
    }

    @Test
    fun `a failed STOP keeps the motion and the lock, only an explicit STOP with a new ID can end it`() {
        val r = Rig()
        assertNull(r.begin(req()))
        r.port.moves[0].ok("FORWARD")
        r.port.emit(59)
        r.port.stops[0].err(SDK_ERROR)
        assertEquals(SeatOutcome.STOP_FAILED, r.s.outcome)
        assertEquals(SeatOutcome.STOP_FAILED, r.journal.stored!!.outcome)
        assertTrue(r.port.ended.isEmpty())
        assertTrue(r.s.lock!!.contains("STOP"))
        assertEquals(r.s.lock, r.core.begin(req(n = 2)))
        r.scheduler.advance(10_000)
        assertEquals(1, r.port.stops.size) // nothing retries by itself
        assertEquals(1, r.port.reads)

        assertNull(r.core.stop("다시", "stop-retry"))
        r.port.stops[1].ok("STOP")
        assertEquals(listOf("move-1"), r.port.ended)
        r.confirmAll()
        assertEquals(SeatOutcome.HELD, r.s.outcome) // stopped on target, but a manual STOP came after
        assertEquals(listOf("stop-1", "stop-retry"), r.snap.stops.map { it.requestId })
    }

    // --- safety STOP after a failed or unanswered STOP ---

    @Test
    fun `a - after a failed STOP, parking lost sends a new safety STOP at once and resumes nothing`() {
        val r = Rig()
        assertNull(r.begin(req()))
        r.port.moves[0].ok("FORWARD")
        r.port.emit(59)
        r.port.stops[0].err(SDK_ERROR)
        assertEquals(SeatOutcome.STOP_FAILED, r.s.outcome)
        r.safe = "기어 P 아님"
        assertNull(r.core.interrupt("주차 조건 상실", "stop-p"))
        assertEquals(2, r.port.stops.size)
        assertEquals(listOf("stop-1", "stop-p"), r.snap.stops.map { it.requestId })
        assertTrue(r.snap.stops[1].afterMove)
        assertTrue(r.port.ended.isEmpty())
        r.port.stops[1].ok("STOP")
        assertEquals(listOf("move-1"), r.port.ended)
        r.confirmAll()
        assertEquals(SeatOutcome.HELD, r.s.outcome)
        assertTrue(r.s.reason!!.contains("주차 조건 상실"))
        r.safe = null
        r.scheduler.advance(10_000)
        assertEquals(1, r.port.moveCalls.size)
        assertEquals(2, r.port.stops.size)
    }

    @Test
    fun `b - with STOP1 unanswered, a door opening or leaving the screen sends a new safety STOP at once`() {
        for (cause in listOf("조수석 문 열림", "화면 이탈")) {
            val r = Rig()
            assertNull(r.begin(req()))
            r.port.moves[0].ok("FORWARD")
            r.port.emit(59) // STOP1, unanswered
            assertNotNull(cause, r.core.interrupt(cause, "stop-1")) // a used ID is refused, the cause still latched
            assertEquals(cause, 1, r.port.stops.size)
            assertNull(cause, r.core.interrupt(cause, "stop-i"))
            assertEquals(cause, 2, r.port.stops.size)
            assertEquals(listOf(SeatRequestState.ENTERING, SeatRequestState.ENTERING), r.snap.stops.map { it.state })
            r.port.stops[1].ok("STOP")
            assertTrue(cause, r.port.ended.isEmpty()) // STOP1 still unknown
            assertEquals(cause, 1, r.port.reads)
            assertTrue(cause, r.s.lock!!.contains("stop-1"))
            r.port.stops[0].ok("STOP")
            assertEquals(cause, listOf("move-1"), r.port.ended)
            r.confirmAll()
            assertEquals(cause, SeatOutcome.HELD, r.s.outcome)
            assertTrue(cause, r.journal.stored!!.held!!.contains(cause))
        }
    }

    @Test
    fun `c - a safety STOP goes even when the journal cannot save`() {
        val r = Rig()
        assertNull(r.begin(req()))
        r.port.moves[0].ok("FORWARD")
        r.port.emit(59)
        r.port.stops[0].err(SDK_ERROR) // STOP_FAILED
        r.journal.fail = { true }
        assertNull(r.core.interrupt("주차 조건 상실", "stop-p"))
        assertEquals(2, r.port.stops.size)
        assertTrue(r.s.lock!!.contains("저장 실패"))

        val u = Rig()
        assertNull(u.begin(req()))
        u.port.moves[0].ok("FORWARD")
        u.port.emit(59) // STOP1, unanswered
        u.journal.fail = { true }
        assertNull(u.core.interrupt("화면 이탈", "stop-i"))
        assertEquals(2, u.port.stops.size)
        assertTrue(u.s.lock!!.contains("저장 실패"))
    }

    @Test
    fun `d - while an earlier STOP stays unknown, no new MOVE, no release, no result and the lock stays`() {
        val r = Rig()
        assertNull(r.begin(req()))
        r.port.emit(59) // STOP1; the MOVE not answered either
        assertNull(r.core.interrupt("화면 이탈", "stop-i"))
        r.port.moves[0].ok("FORWARD") // one more STOP after the MOVE answer
        assertEquals(listOf("stop-1", "stop-i", "final-1"), r.snap.stops.map { it.requestId })
        r.port.stops[2].ok("STOP")
        r.port.stops[1].ok("STOP")
        r.scheduler.advance(LIMITS.answerMs)
        assertTrue(r.snap.stops[0].timedOut)
        assertTrue(r.journal.stored!!.stops[0].timedOut)
        assertNull(r.journal.stored!!.outcome)
        assertNull(r.s.outcome)
        assertTrue(r.s.lock!!.contains("stop-1"))
        assertEquals(r.s.lock, r.core.begin(req(n = 2)))
        assertEquals(1, r.port.moveCalls.size)
        assertTrue(r.port.ended.isEmpty())
        assertEquals(1, r.port.reads)

        assertNull(r.core.interrupt("주차 조건 상실", "stop-j")) // still allowed while STOP1 is unknown
        assertEquals(4, r.port.stops.size)
        r.port.stops[3].ok("STOP")
        assertTrue(r.port.ended.isEmpty())
        r.port.stops[0].ok("STOP") // STOP1 known at last
        assertEquals(listOf("move-1"), r.port.ended)
        r.confirmAll()
        assertEquals(SeatOutcome.HELD, r.s.outcome)
        assertTrue(r.s.reason!!.contains("화면 이탈"))
    }

    // --- request IDs ---

    @Test
    fun `review 3 - STOP IDs must be new, and a stale or repeated answer cannot end another request`() {
        val r = Rig()
        assertNull(r.begin(req()))
        r.port.moves[0].ok("FORWARD")
        r.port.emit(59)
        r.port.stops[0].err(SDK_ERROR) // stop-1 failed
        for (id in listOf("stop-1", "", " ", "move-1", "final-1")) assertNotNull(id, r.core.stop("다시", id))
        assertEquals(1, r.port.stops.size)

        assertNull(r.core.stop("다시", "stop-2"))
        r.port.stops[0].ok("STOP") // a repeated, late answer of stop-1
        assertEquals(SeatRequestState.FAILED, r.snap.stops[0].state)
        assertEquals(SeatRequestState.ENTERING, r.snap.stops[1].state)
        assertTrue(r.s.lock!!.contains("stop-2"))
        assertTrue(r.port.ended.isEmpty())
        r.port.moves[0].ok("FORWARD") // a repeated MOVE answer changes nothing
        assertEquals(2, r.port.stops.size)

        r.port.stops[1].ok("STOP")
        r.port.stops[1].ok("STOP") // repeated
        assertEquals(listOf("move-1"), r.port.ended)
        r.confirmAll()
        assertEquals(SeatOutcome.HELD, r.s.outcome)
        assertEquals(listOf("move-1"), r.port.ended)

        // No STOP without an attempt of this core: nothing is sent and nothing is recorded.
        assertNotNull(r.core.stop("수동", "stop-9"))
        val idle = Rig()
        assertNotNull(idle.core.stop("수동", "stop-1"))
        idle.noPortCalls()
        assertTrue(idle.journal.saved.isEmpty())
    }

    // --- safety and screen ---

    @Test
    fun `safety lost or the screen left - STOP and hold, parking coming back moves nothing`() {
        val m = Rig()
        assertNull(m.begin(req()))
        m.port.moves[0].ok("FORWARD")
        m.port.emit(30)
        m.safe = "기어 P 아님"
        assertNull(m.core.interrupt("주차 조건 상실", "stop-i"))
        assertEquals(1, m.port.stops.size)
        m.port.stops[0].ok("STOP")
        m.confirmAll()
        assertEquals(SeatOutcome.HELD, m.s.outcome)
        m.safe = null
        m.scheduler.advance(10_000)
        assertEquals(1, m.port.moveCalls.size)
        assertEquals(1, m.port.stops.size)
        assertEquals(SeatPhase.DONE, m.s.phase)

        val q = Rig()
        q.port.holdDispatch = true
        assertNull(q.begin(req()))
        assertNull(q.core.interrupt("조수석 문 열림", "stop-i"))
        q.port.runQueued()
        assertTrue(q.port.entered.isEmpty())
        q.port.stops[0].ok("STOP")
        assertEquals(SeatOutcome.NOT_SENT, q.s.outcome)
        q.scheduler.advance(10_000)
        assertEquals(1, q.port.moveCalls.size)

        // Nothing was handed over yet: the preparation is cancelled, there is nothing to STOP.
        val p = Rig()
        p.port.holdReads = true
        assertNull(p.begin(req()))
        assertNotNull(p.core.interrupt("화면 이탈", "stop-i"))
        assertTrue(p.port.stops.isEmpty())
        assertEquals(SeatOutcome.NOT_SENT, p.s.outcome)
        assertNull(p.s.lock)
        p.port.heldReads[0].ok(20) // the read before the MOVE answers late
        assertTrue(p.port.moveCalls.isEmpty())

        val g = Rig()
        g.port.holdDispatch = true
        assertNull(g.begin(req()))
        g.safe = "기어 P 아님" // lost while the MOVE waits: refused at its gate
        g.port.runQueued()
        assertTrue(g.port.entered.isEmpty())
        assertEquals(SeatOutcome.NOT_SENT, g.s.outcome)
        g.safe = null
        g.scheduler.advance(10_000)
        assertEquals(1, g.port.moveCalls.size)
    }

    // --- stale input and earlier runs ---

    @Test
    fun `stale reads and listener callbacks of an earlier attempt do not touch the next one`() {
        val r = Rig()
        r.port.holdReads = true
        assertNull(r.begin(req(job = "job-a", n = 1)))
        r.scheduler.advance(LIMITS.answerMs) // its read before the MOVE timed out
        assertEquals(SeatOutcome.NOT_SENT, r.s.outcome)
        val oldRead = r.port.heldReads.removeAt(0)
        val oldSub = r.port.subs[0]
        r.port.holdReads = false

        assertNull(r.begin(req(job = "job-b", n = 2)))
        assertEquals(SeatPhase.MOVING, r.s.phase)
        oldRead.ok(60)
        oldSub.update(null)
        oldSub.update(60)
        oldSub.error(SDK_ERROR)
        assertTrue(r.port.stops.isEmpty())
        assertEquals(SeatPhase.MOVING, r.s.phase)
        assertEquals("job-b", r.s.jobId)

        r.port.moves[0].ok("FORWARD")
        r.port.emit(59)
        r.port.stops[0].ok("STOP")
        r.confirmAll()
        assertEquals(SeatOutcome.CONFIRMED, r.s.outcome)
        assertEquals("job-b", r.journal.stored!!.jobId)
    }

    @Test
    fun `review 4 - an earlier run's snapshot locks unless every request answered and fits its outcome`() {
        val done = SeatRequestState.COMPLETED
        val ok = SeatSnapshot(
            "job-old", SeatPurpose.APPLY, SeatAxis.BACKREST, 20, 20, 60, SeatRequestMark("move-old", done),
            listOf(SeatRequestMark("stop-old", done, afterMove = true)), SeatOutcome.CONFIRMED,
        )
        val bad = listOf(
            ok.copy(move = ok.move.copy(state = SeatRequestState.SAVED), stops = emptyList(), outcome = null), // never finished
            ok.copy(move = ok.move.copy(state = SeatRequestState.NOT_SENT), stops = emptyList(), outcome = null), // a saved flag alone
            ok.copy(move = ok.move.copy(state = SeatRequestState.SAVED)), // CONFIRMED, MOVE never answered
            ok.copy(move = ok.move.copy(state = SeatRequestState.ENTERING)),
            ok.copy(stops = ok.stops + SeatRequestMark("stop-open", SeatRequestState.ENTERING, afterMove = true)), // a STOP open
            ok.copy(stops = emptyList()),
            ok.copy(stops = listOf(SeatRequestMark("stop-old", done, afterMove = false))), // no STOP after the MOVE
            ok.copy(held = "화면 이탈"),
            ok.copy(outcome = SeatOutcome.HELD), // held without a cause
            ok.copy(outcome = SeatOutcome.UNCONFIRMED, move = ok.move.copy(state = SeatRequestState.NOT_SENT)),
            ok.copy(outcome = SeatOutcome.STOP_FAILED, stops = listOf(SeatRequestMark("stop-old", SeatRequestState.FAILED, afterMove = true))),
            ok.copy(stops = listOf(SeatRequestMark("move-old", done, afterMove = true))), // a repeated ID
            ok.copy(axis = SeatAxis.HEIGHT),
        )
        for (s in bad) {
            val r = Rig(stored = s)
            assertNotNull(s.toString(), r.s.lock)
            assertEquals(r.s.lock, r.begin(req(job = "job-old", n = 2)))
            assertNotNull(r.core.stop("수동", "stop-x")) // not this core's attempt
            r.noPortCalls()
            assertTrue(r.journal.saved.isEmpty())
            assertEquals(s, r.journal.stored)
        }
        val fine = listOf(
            ok,
            ok.copy(outcome = SeatOutcome.HELD, held = "화면 이탈"),
            ok.copy(move = ok.move.copy(state = SeatRequestState.NOT_SENT), stops = emptyList(), outcome = SeatOutcome.NOT_SENT),
        )
        for (s in fine) assertNull(s.toString(), Rig(stored = s).s.lock)
        val u = Rig(unreadable = "형식 오류")
        assertNotNull(u.s.lock)
        assertNotNull(u.begin(req()))
        u.noPortCalls()
    }

    @Test
    fun `a kept record of another job is not overwritten, and its request IDs are not reused`() {
        val done = SeatSnapshot(
            "job-a", SeatPurpose.APPLY, SeatAxis.BACKREST, 20, 20, 60, SeatRequestMark("move-1", SeatRequestState.COMPLETED),
            listOf(SeatRequestMark("stop-1", SeatRequestState.COMPLETED, afterMove = true)), SeatOutcome.CONFIRMED,
        )
        val r = Rig(stored = done)
        assertNull(r.s.lock)
        assertTrue(r.begin(req(job = "job-b", n = 2))!!.contains("다른 작업"))
        assertTrue(r.begin(req(job = "job-a", purpose = SeatPurpose.RESTORE, start = 60, target = 20, original = 20, n = 1))!!.contains("요청 ID"))
        assertTrue(r.begin(req(job = "job-a", start = 60, target = 30, original = 30, n = 2))!!.contains("원래 위치"))
        r.noPortCalls()
        assertTrue(r.journal.saved.isEmpty())
    }
}
