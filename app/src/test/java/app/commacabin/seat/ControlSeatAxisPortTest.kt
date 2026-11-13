package app.commacabin.seat

import app.commacabin.control.ControlPort
import app.commacabin.control.ControlProp
import app.commacabin.control.Move
import app.commacabin.control.SeatAxis
import app.commacabin.control.SideState
import app.commacabin.probe.Access
import app.commacabin.probe.Dispatch
import app.commacabin.probe.FakeScheduler
import app.commacabin.probe.ProbeError
import app.commacabin.probe.SDK_ERROR
import app.commacabin.support.CapabilityResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Records every ControlPort call; the test answers each callback itself. */
class FakeControlPort : ControlPort {
    class Read(val prop: ControlProp, val area: String, val ok: (String?) -> Unit, val err: (ProbeError) -> Unit)
    class MoveCall(val area: String, val control: Move, val key: String, val dispatch: Dispatch, val ok: (String?) -> Unit, val err: (ProbeError) -> Unit)
    class Observe(
        val prop: ControlProp, val area: String, val onUpdate: (String?) -> Unit, val onError: (ProbeError) -> Unit,
        val onRegistered: (ProbeError?) -> Unit,
    ) {
        var closed = 0
    }

    val calls = mutableListOf<String>()
    val reads = mutableListOf<Read>()
    val moves = mutableListOf<MoveCall>()
    val observes = mutableListOf<Observe>()
    val ended = mutableListOf<String>()

    val capabilities = mutableListOf<Pair<(CapabilityResult, CapabilityResult?, Float?) -> Unit, (ProbeError) -> Unit>>()

    override fun capability(prop: ControlProp, ok: (CapabilityResult, CapabilityResult?, Float?) -> Unit, err: (ProbeError) -> Unit) {
        calls += "capability $prop"
        capabilities += ok to err
    }

    override fun sideAreas(state: SideState, ok: (Access, List<String>) -> Unit, err: (ProbeError) -> Unit) {
        calls += "sideAreas"
    }

    override fun read(prop: ControlProp, area: String, ok: (String?) -> Unit, err: (ProbeError) -> Unit) {
        calls += "read"
        reads += Read(prop, area, ok, err)
    }

    override fun readSide(state: SideState, area: String, ok: (String?) -> Unit, err: (ProbeError) -> Unit) {
        calls += "readSide"
    }

    override fun write(prop: ControlProp, area: String, value: String, dispatch: Dispatch, ok: (String?) -> Unit, err: (ProbeError) -> Unit) {
        calls += "write"
    }

    override fun writeSide(state: SideState, area: String, value: String, dispatch: Dispatch, ok: (String?) -> Unit, err: (ProbeError) -> Unit) {
        calls += "writeSide"
    }

    override fun move(area: String, control: Move, motionKey: String, dispatch: Dispatch, ok: (String?) -> Unit, err: (ProbeError) -> Unit) {
        calls += "move $control"
        moves += MoveCall(area, control, motionKey, dispatch, ok, err)
    }

    override fun endMotion(motionKey: String) {
        calls += "endMotion"
        ended += motionKey
    }

    override fun observe(prop: ControlProp, area: String, onUpdate: (String?) -> Unit, onError: (ProbeError) -> Unit, onRegistered: (ProbeError?) -> Unit): AutoCloseable {
        calls += "observe"
        val o = Observe(prop, area, onUpdate, onError, onRegistered)
        observes += o
        return AutoCloseable { o.closed++ }
    }
}

/** A dispatch that records what was asked of it. The adapter must not run gate or onStart. */
private class Probe {
    var gates = 0
    var starts = 0
    val notSent = mutableListOf<String>()
    val dispatch = Dispatch({ gates++; null }, { starts++ }, { notSent += it })
}

class ControlSeatAxisPortTest {
    private val lower = FakeControlPort()
    private val port = ControlSeatAxisPort(lower)

    private val good = listOf("0" to 0, "-5" to -5, "100" to 100, "2147483647" to Int.MAX_VALUE, "-2147483648" to Int.MIN_VALUE)
    private val bad = listOf("1.5", "0.0", "", " ", " 1", "1 ", "2147483648", "-2147483649", "007", "-0", "+1", "1e2", "abc", "0x1")

    @Test
    fun `the constructor calls nothing`() {
        ControlSeatAxisPort(lower)
        assertTrue(lower.calls.isEmpty())
    }

    @Test
    fun `read goes to the driver backrest and converts strictly, null stays null, a bad text is an error`() {
        for ((text, value) in good + listOf<Pair<String?, Int?>>(null to null)) {
            var got: Int? = -1
            var error: ProbeError? = null
            port.read(SeatAxis.BACKREST, { got = it }, { error = it })
            val r = lower.reads.last()
            assertEquals(ControlProp.SEAT_BACKREST, r.prop)
            assertEquals("ROW_1_LEFT", r.area)
            r.ok(text)
            assertEquals("$text", value, got)
            assertNull(error)
        }
        for (text in bad) {
            var got = false
            var error: ProbeError? = null
            port.read(SeatAxis.BACKREST, { got = true }, { error = it })
            lower.reads.last().ok(text)
            assertTrue("'$text'", !got && error == ControlSeatAxisPort.INVALID_POSITION)
        }
        var passed: ProbeError? = null
        port.read(SeatAxis.BACKREST, {}, { passed = it })
        lower.reads.last().err(SDK_ERROR)
        assertSame(SDK_ERROR, passed)
    }

    @Test
    fun `observe passes the real registration result, converts like read, forwards errors and close`() {
        val updates = mutableListOf<Int?>()
        val errors = mutableListOf<ProbeError>()
        val registered = mutableListOf<ProbeError?>()
        val handle = port.observe(SeatAxis.BACKREST, { registered += it }, { updates += it }, { errors += it })
        val o = lower.observes.single()
        assertEquals(ControlProp.SEAT_BACKREST, o.prop)
        assertEquals("ROW_1_LEFT", o.area)
        assertTrue(registered.isEmpty()) // the handle is no registration

        o.onRegistered(null)
        assertEquals(listOf<ProbeError?>(null), registered)
        for ((text, value) in good) {
            o.onUpdate(text)
            assertEquals(value, updates.last())
        }
        o.onUpdate(null)
        assertNull(updates.last())
        val before = updates.size
        bad.forEach { o.onUpdate(it) }
        assertEquals(before, updates.size)
        assertEquals(List(bad.size) { ControlSeatAxisPort.INVALID_POSITION }, errors)
        o.onError(SDK_ERROR)
        assertSame(SDK_ERROR, errors.last())

        handle.close()
        assertEquals(1, o.closed)
        o.onUpdate("7") // a late callback is not filtered here; the core decides
        assertEquals(7, updates.last())

        val failed = mutableListOf<ProbeError?>()
        port.observe(SeatAxis.BACKREST, { failed += it }, {}, {})
        lower.observes.last().onRegistered(SDK_ERROR)
        assertEquals(listOf<ProbeError?>(SDK_ERROR), failed)
    }

    @Test
    fun `MOVE hands the same Dispatch down without running it, and passes answers and errors as they are`() {
        val p = Probe()
        val echoes = mutableListOf<String?>()
        val errors = mutableListOf<ProbeError>()
        port.move(SeatAxis.BACKREST, Move.FORWARD, "move-1", p.dispatch, { echoes += it }, { errors += it })
        val m = lower.moves.single()
        assertEquals("ROW_1_LEFT", m.area)
        assertEquals(Move.FORWARD, m.control)
        assertEquals("move-1", m.key)
        assertSame(p.dispatch, m.dispatch)
        assertEquals(0, p.gates)
        assertEquals(0, p.starts)
        assertTrue(p.notSent.isEmpty())
        m.ok("FORWARD")
        m.err(SDK_ERROR)
        assertEquals(listOf<String?>("FORWARD"), echoes)
        assertEquals(listOf(SDK_ERROR), errors)
        assertTrue(p.notSent.isEmpty()) // a ControlPort error is not "not sent"
        assertEquals(listOf("move FORWARD"), lower.calls)

        port.move(SeatAxis.BACKREST, Move.BACKWARD, "move-2", Probe().dispatch, {}, {})
        assertEquals(Move.BACKWARD, lower.moves.last().control)
    }

    @Test
    fun `other axes, STOP as a MOVE or a blank key never reach the ControlPort and are reported not sent`() {
        val cases = SeatAxis.entries.filter { it != SeatAxis.BACKREST }.map { Triple(it, Move.FORWARD, "move-1") } +
            listOf(Triple(SeatAxis.BACKREST, Move.STOP, "move-1"), Triple(SeatAxis.BACKREST, Move.FORWARD, " "))
        for ((axis, command, key) in cases) {
            val p = Probe()
            var answered = false
            port.move(axis, command, key, p.dispatch, { answered = true }, { answered = true })
            assertEquals("$axis $command '$key'", 1, p.notSent.size)
            assertEquals(0, p.gates)
            assertEquals(0, p.starts)
            assertTrue(!answered)
        }
        val errors = mutableListOf<ProbeError>()
        var regErr: ProbeError? = null
        SeatAxis.entries.filter { it != SeatAxis.BACKREST }.forEach { axis ->
            port.read(axis, { throw AssertionError() }, { errors += it })
            port.stop(axis, { throw AssertionError() }, { errors += it })
            port.observe(axis, { regErr = it }, {}, {}).close()
            assertEquals(ControlSeatAxisPort.UNSUPPORTED_AXIS, regErr!!.type)
        }
        assertTrue(errors.all { it.type == ControlSeatAxisPort.UNSUPPORTED_AXIS })
        assertEquals(8, errors.size)
        assertTrue(lower.calls.isEmpty())
    }

    @Test
    fun `STOP takes the ControlPort's own ungated STOP path, and a refusal comes back as an error`() {
        val echoes = mutableListOf<String?>()
        val errors = mutableListOf<ProbeError>()
        port.stop(SeatAxis.BACKREST, { echoes += it }, { errors += it })
        val s = lower.moves.single()
        assertEquals("ROW_1_LEFT", s.area)
        assertEquals(Move.STOP, s.control)
        assertEquals("", s.key) // PleosControlPort uses neither key nor dispatch for STOP
        assertSame(Dispatch.NONE, s.dispatch)
        s.ok("STOP")
        assertEquals(listOf<String?>("STOP"), echoes)

        port.stop(SeatAxis.BACKREST, { echoes += it }, { errors += it })
        val refusal = ProbeError("StopAlreadyQueued", "같은 STOP이 이미 대기 중이에요", null)
        lower.moves.last().err(refusal)
        assertEquals(listOf(refusal), errors)
        assertEquals(1, echoes.size)
    }

    @Test
    fun `endMotion only passes the exact key it is given, never by itself`() {
        port.move(SeatAxis.BACKREST, Move.FORWARD, "move-1", Probe().dispatch, {}, {})
        lower.moves.last().ok("FORWARD")
        port.stop(SeatAxis.BACKREST, {}, {})
        lower.moves.last().ok("STOP")
        assertTrue(lower.ended.isEmpty())
        port.endMotion("move-1")
        assertEquals(listOf("move-1"), lower.ended)
    }

    @Test
    fun `with the core - one attempt maps onto backrest calls, and another axis reaches nothing`() {
        val scheduler = FakeScheduler()
        val core = SeatMoveCore(SeatAxis.BACKREST, port, FakeJournal(mutableListOf()), scheduler, { null }, { null })
        assertNull(core.begin(req(start = 20, target = 60)))
        val o = lower.observes.single()
        o.onRegistered(null)
        lower.reads.last().ok("20")
        val m = lower.moves.single()
        assertEquals(Move.FORWARD, m.control)
        assertEquals("move-1", m.key)
        assertTrue(m.dispatch.now()) // the lane's part: gate passed, onStart ran
        m.ok("FORWARD")
        o.onUpdate("59")
        val s = lower.moves.last()
        assertEquals(Move.STOP, s.control)
        s.ok("STOP")
        assertEquals(listOf("move-1"), lower.ended)
        scheduler.advance(LIMITS.settleMs)
        lower.reads.last().ok("59")
        scheduler.advance(LIMITS.confirmIntervalMs)
        lower.reads.last().ok("59")
        assertEquals(SeatOutcome.CONFIRMED, core.state.outcome)
        assertEquals(listOf("observe", "read", "move FORWARD", "move STOP", "endMotion", "read", "read"), lower.calls)

        val other = FakeControlPort()
        val tilt = SeatMoveCore(SeatAxis.TILT, ControlSeatAxisPort(other), FakeJournal(mutableListOf()), FakeScheduler(), { null }, { null })
        assertNull(tilt.begin(req(start = 20, target = 60)))
        assertEquals(SeatOutcome.NOT_SENT, tilt.state.outcome)
        assertTrue(other.calls.isEmpty())
    }
}
