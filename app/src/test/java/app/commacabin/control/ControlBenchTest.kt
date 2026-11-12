package app.commacabin.control

import app.commacabin.probe.Access
import app.commacabin.probe.FakeScheduler
import app.commacabin.probe.GearState
import app.commacabin.probe.ParkingState
import app.commacabin.probe.ProbeError
import app.commacabin.probe.RecordLease
import app.commacabin.rest.DoorStatus
import app.commacabin.support.AreaBound
import app.commacabin.support.CapabilityResult
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val FIVE = "ROW_1_LEFT+ROW_1_RIGHT+ROW_2_CENTER+ROW_2_LEFT+ROW_2_RIGHT"
private const val DUAL = "ROW_1_LEFT+ROW_1_RIGHT"

private class Call(val what: String, val ok: (String?) -> Unit, val err: (ProbeError) -> Unit)

/** A vehicle whose writes wait for the test; [apply] makes an answered write change the stored value. */
private class FakeControlPort : ControlPort {
    val caps = mutableMapOf(
        ControlProp.SEAT_BACKREST to CapabilityResult(Access.READ_WRITE, listOf(AreaBound(DRIVER, "0", "100"), AreaBound("ROW_1_RIGHT", "0", "100"))),
        ControlProp.AMBIENT_COLOR to CapabilityResult(Access.READ_WRITE, listOf(AreaBound(FIVE))),
        ControlProp.HVAC_FAN_SPEED to CapabilityResult(Access.READ_WRITE, listOf(AreaBound(DUAL, "1", "8"))),
        ControlProp.HVAC_TEMPERATURE to CapabilityResult(Access.READ_WRITE, listOf(AreaBound(DRIVER, "15.0", "30.0"), AreaBound("ROW_1_RIGHT", "15.0", "30.0"))),
    )
    var seatMove = CapabilityResult(Access.READ_WRITE, listOf(AreaBound(DRIVER), AreaBound("ROW_1_RIGHT")))
    val values = mutableMapOf<Pair<ControlProp, String>, String?>(
        (ControlProp.SEAT_BACKREST to DRIVER) to "0",
        (ControlProp.AMBIENT_COLOR to FIVE) to "0x00FFFFFF",
        (ControlProp.HVAC_FAN_SPEED to DUAL) to "3",
        (ControlProp.HVAC_TEMPERATURE to DRIVER) to "22.0",
        (ControlProp.HVAC_TEMPERATURE to "ROW_1_RIGHT") to "22.0",
    )
    val side = mutableMapOf<String, String?>(
        "LIGHT_SWITCH $FIVE" to "ON",
        "HVAC_POWER $FIVE" to "true",
        "HVAC_AUTO $DUAL" to "false",
        "HVAC_DUAL $DUAL" to "false",
    )
    var failReads = false
    var autoAccess = Access.READ_WRITE
    var observeThrows = false
    /** When true, capability answers wait in [held] until the test releases them. */
    var holdCapability = false
    /** When true, side-state reads wait in [held] too. */
    var holdSide = false
    val held = mutableListOf<() -> Unit>()
    val writes = mutableListOf<Call>()
    val moves = mutableListOf<Call>()
    val listeners = mutableMapOf<ControlProp, (String?) -> Unit>()

    override fun capability(prop: ControlProp, ok: (CapabilityResult, CapabilityResult?, Float?) -> Unit, err: (ProbeError) -> Unit) {
        val answer = { ok(caps.getValue(prop), if (prop == ControlProp.SEAT_BACKREST) seatMove else null, if (prop == ControlProp.HVAC_TEMPERATURE) 0.5f else null) }
        if (holdCapability) held += answer else answer()
    }

    override fun sideAreas(state: SideState, ok: (Access, List<String>) -> Unit, err: (ProbeError) -> Unit) =
        ok(if (state == SideState.HVAC_AUTO) autoAccess else Access.READ_WRITE, side.keys.filter { it.startsWith(state.name + " ") }.map { it.substringAfter(' ') })

    override fun read(prop: ControlProp, area: String, ok: (String?) -> Unit, err: (ProbeError) -> Unit) =
        if (failReads) err(ProbeError("VehicleException", "read failed", null)) else ok(values[prop to area])

    override fun readSide(state: SideState, area: String, ok: (String?) -> Unit, err: (ProbeError) -> Unit) {
        val answer = { ok(side["${state.name} $area"]) }
        if (holdSide) held += answer else answer()
    }

    override fun write(prop: ControlProp, area: String, value: String, ok: (String?) -> Unit, err: (ProbeError) -> Unit) {
        writes += Call("${prop.name} $area=$value", ok, err)
    }

    override fun writeSide(state: SideState, area: String, value: String, ok: (String?) -> Unit, err: (ProbeError) -> Unit) {
        writes += Call("${state.name} $area=$value", ok, err)
    }

    override fun move(area: String, control: Move, ok: (String?) -> Unit, err: (ProbeError) -> Unit) {
        moves += Call(control.name, ok, err)
    }

    override fun observe(prop: ControlProp, area: String, onUpdate: (String?) -> Unit, onError: (ProbeError) -> Unit): AutoCloseable {
        if (observeThrows) throw SecurityException("register denied")
        listeners[prop] = onUpdate
        return AutoCloseable { listeners.remove(prop) }
    }
}

private class MemoryStore(var records: List<ControlRecord> = emptyList()) : ControlStore {
    var failSaves = false
    val saved = mutableListOf<List<ControlRecord>>()
    override fun load(): ControlLoad = if (records.isEmpty()) ControlLoad.Empty else ControlLoad.Found(records)
    override fun save(records: List<ControlRecord>): Boolean {
        if (failSaves) return false
        this.records = records
        saved += records
        return true
    }
}

class ControlBenchTest {
    private val port = FakeControlPort()
    private val store = MemoryStore()
    private val scheduler = FakeScheduler()
    private var parking = ParkingState(GearState.PARK, false)
    private var door = DoorStatus.CLOSED
    private var brightnessRecord = false
    private var ids = 0
    private val bench by lazy {
        ControlBench(port, store, scheduler, { parking }, { door }, { brightnessRecord }, { scheduler.now }, RecordLease(), newId = { "id-${++ids}" }).also { it.start(); it.onVisible() }
    }

    private fun record(prop: ControlProp) = store.records.single { it.prop == prop }

    /** The vehicle takes [value] (or not), then the callback arrives and the confirming read runs. */
    private fun answer(call: Call, echo: String?, apply: Pair<ControlProp, String>? = null, value: String? = null) {
        if (apply != null) port.values[apply] = value
        call.ok(echo)
        scheduler.advance(CONTROL_SETTLE_MS)
    }

    @Test
    fun `the record is saved before the command and a confirmed re-read marks it applied`() {
        bench.apply(ControlProp.HVAC_FAN_SPEED)

        val write = port.writes.single()
        assertEquals("HVAC_FAN_SPEED $DUAL=2", write.what)
        val saved = record(ControlProp.HVAC_FAN_SPEED)
        assertEquals(ControlStage.APPLY_REQUESTED, saved.stage)
        assertFalse(saved.responseReceived)
        assertEquals(mapOf("HVAC_POWER $FIVE" to "true", "HVAC_AUTO $DUAL" to "false", "HVAC_DUAL $DUAL" to "false"), saved.related)

        answer(write, "2", ControlProp.HVAC_FAN_SPEED to DUAL, "2")

        assertEquals(ControlStage.APPLY_CONFIRMED, record(ControlProp.HVAC_FAN_SPEED).stage)
        assertEquals("2", record(ControlProp.HVAC_FAN_SPEED).lastRead)
    }

    @Test
    fun `an echo of the target is not taken as the vehicle value`() {
        bench.apply(ControlProp.HVAC_FAN_SPEED)
        answer(port.writes.single(), "2") // the vehicle still reads 3

        val r = record(ControlProp.HVAC_FAN_SPEED)
        assertEquals(ControlStage.RESULT_UNKNOWN, r.stage)
        assertEquals("onComplete(2)", r.lastEcho)
        assertEquals("3", r.lastRead)
    }

    @Test
    fun `a failing save means no command at all, and later writes stay blocked`() {
        store.failSaves = true
        bench.apply(ControlProp.HVAC_FAN_SPEED)
        bench.exploreSeat(Move.BACKWARD)

        assertTrue(port.writes.isEmpty())
        assertTrue(port.moves.isEmpty())
        assertTrue(bench.state.storageFault != null)
    }

    @Test
    fun `a timeout keeps the request open and locked, and the late answer is still handled`() {
        bench.apply(ControlProp.HVAC_FAN_SPEED)
        val write = port.writes.single()
        scheduler.advance(CONTROL_WRITE_TIMEOUT_MS)

        assertTrue(bench.state.pending.getValue(ControlProp.HVAC_FAN_SPEED).timedOut)
        assertEquals(ControlStage.APPLY_REQUESTED, record(ControlProp.HVAC_FAN_SPEED).stage)
        bench.restore(ControlProp.HVAC_FAN_SPEED)
        bench.discard(ControlProp.HVAC_FAN_SPEED)
        assertEquals(1, port.writes.size)
        assertEquals(1, store.records.size)

        answer(write, "2", ControlProp.HVAC_FAN_SPEED to DUAL, "2")
        assertEquals(ControlStage.APPLY_CONFIRMED, record(ControlProp.HVAC_FAN_SPEED).stage)
        assertTrue(bench.state.pending.isEmpty())
    }

    @Test
    fun `onFailed is result unknown whatever the value reads, and the record stays`() {
        bench.apply(ControlProp.HVAC_TEMPERATURE)
        port.values[ControlProp.HVAC_TEMPERATURE to DRIVER] = "22.5"
        port.writes.single().err(ProbeError("VehicleException", "ERROR_INTERNAL", null))
        scheduler.advance(CONTROL_SETTLE_MS)

        val r = record(ControlProp.HVAC_TEMPERATURE)
        assertEquals(ControlStage.RESULT_UNKNOWN, r.stage)
        assertTrue(r.responseReceived)
        assertEquals("22.5", r.target)
    }

    @Test
    fun `a record without a response from an earlier run locks writes and deletion`() {
        store.records = listOf(
            ControlRecord("job", ControlProp.HVAC_FAN_SPEED, DUAL, "3", "2", mapOf("HVAC_POWER $FIVE" to "true", "HVAC_AUTO $DUAL" to "false", "HVAC_DUAL $DUAL" to "false"),
                ControlStage.APPLY_CONFIRMED, "req", responseReceived = false),
        )
        bench.restore(ControlProp.HVAC_FAN_SPEED)
        bench.discard(ControlProp.HVAC_FAN_SPEED)
        bench.apply(ControlProp.HVAC_FAN_SPEED)

        assertTrue(port.writes.isEmpty())
        assertEquals(1, store.records.size)
    }

    @Test
    fun `switch off, power off, not parked or a failed read send nothing`() {
        port.side["LIGHT_SWITCH $FIVE"] = "OFF"
        bench.apply(ControlProp.AMBIENT_COLOR)
        port.side["HVAC_POWER $FIVE"] = "false"
        bench.apply(ControlProp.HVAC_FAN_SPEED)
        bench.apply(ControlProp.HVAC_TEMPERATURE)
        port.side["HVAC_POWER $FIVE"] = "true"
        parking = ParkingState(GearState.PARK, true)
        bench.apply(ControlProp.HVAC_FAN_SPEED)
        parking = ParkingState(GearState.PARK, false)
        port.failReads = true
        bench.apply(ControlProp.HVAC_FAN_SPEED)

        assertTrue(port.writes.isEmpty())
        assertTrue(store.records.isEmpty())
    }

    @Test
    fun `the color is not touched while a brightness record exists`() {
        brightnessRecord = true
        bench.apply(ControlProp.AMBIENT_COLOR)
        assertTrue(port.writes.isEmpty())

        brightnessRecord = false
        bench.apply(ControlProp.AMBIENT_COLOR)
        assertEquals("AMBIENT_COLOR $FIVE=$WARM_COLOR", port.writes.single().what)
    }

    @Test
    fun `restore is confirmed only by the re-read original and unchanged shared state`() {
        bench.apply(ControlProp.HVAC_TEMPERATURE)
        assertEquals("HVAC_TEMPERATURE $DRIVER=22.5", port.writes[0].what)
        assertEquals("22.0", record(ControlProp.HVAC_TEMPERATURE).related["HVAC_TEMPERATURE ROW_1_RIGHT"])
        answer(port.writes[0], "22.5", ControlProp.HVAC_TEMPERATURE to DRIVER, "22.5")

        bench.restore(ControlProp.HVAC_TEMPERATURE)
        assertEquals("HVAC_TEMPERATURE $DRIVER=22.0", port.writes[1].what)
        port.values[ControlProp.HVAC_TEMPERATURE to "ROW_1_RIGHT"] = "22.5" // the other side followed
        answer(port.writes[1], "22.0", ControlProp.HVAC_TEMPERATURE to DRIVER, "22.0")

        val r = record(ControlProp.HVAC_TEMPERATURE)
        assertEquals(ControlStage.RESTORE_FAILED, r.stage)
        assertTrue(r.note!!.contains("HVAC_TEMPERATURE ROW_1_RIGHT 22.0→22.5"))
    }

    @Test
    fun `a restore that leaves only the auto state changed restores auto once with setHvacAuto`() {
        bench.apply(ControlProp.HVAC_FAN_SPEED)
        port.side["HVAC_AUTO $DUAL"] = "true"
        answer(port.writes[0], "2", ControlProp.HVAC_FAN_SPEED to DUAL, "2")
        bench.restore(ControlProp.HVAC_FAN_SPEED)
        answer(port.writes[1], "3", ControlProp.HVAC_FAN_SPEED to DUAL, "3")

        assertEquals("HVAC_AUTO $DUAL=false", port.writes[2].what)
        assertEquals(ControlStage.RESTORE_REQUESTED, record(ControlProp.HVAC_FAN_SPEED).stage)
        port.side["HVAC_AUTO $DUAL"] = "false"
        answer(port.writes[2], "false")

        assertTrue(store.records.isEmpty())
        assertEquals(3, port.writes.size)
    }

    @Test
    fun `a listener change while the app is not writing is a user change and is not restored automatically`() {
        bench.apply(ControlProp.HVAC_FAN_SPEED)
        answer(port.writes[0], "2", ControlProp.HVAC_FAN_SPEED to DUAL, "2")

        port.listeners.getValue(ControlProp.HVAC_FAN_SPEED)("5")

        assertEquals(ControlStage.USER_CHANGED, record(ControlProp.HVAC_FAN_SPEED).stage)
        assertEquals(1, port.writes.size)
    }

    // --- seat ---

    private fun seatRecord() = record(ControlProp.SEAT_BACKREST)

    @Test
    fun `seat probe stops at the first position change and is answered only after MOVE and STOP`() {
        bench.exploreSeat(Move.BACKWARD)
        assertEquals("BACKWARD", port.moves.single().what)
        assertEquals(ControlStage.APPLY_REQUESTED, seatRecord().stage)

        port.values[ControlProp.SEAT_BACKREST to DRIVER] = "3"
        port.listeners.getValue(ControlProp.SEAT_BACKREST)("3")
        assertEquals(listOf("BACKWARD", "STOP"), port.moves.map { it.what })

        port.moves[1].ok("STOP") // STOP answers first
        scheduler.advance(SEAT_SETTLE_MS)
        assertFalse(seatRecord().responseReceived)
        port.moves[0].ok("BACKWARD")
        scheduler.advance(SEAT_SETTLE_MS)

        val r = seatRecord()
        assertTrue(r.responseReceived)
        assertEquals(ControlStage.APPLY_CONFIRMED, r.stage)
        assertEquals("3", r.lastRead)
    }

    @Test
    fun `a STOP answer alone, or no answer at all, keeps the seat record locked, also after a restart`() {
        bench.exploreSeat(Move.BACKWARD)
        scheduler.advance(SEAT_WATCHDOG_MS)
        assertEquals("STOP", port.moves[1].what) // watchdog bound
        port.moves[1].ok("STOP")
        scheduler.advance(10 * SEAT_SETTLE_MS)

        assertFalse(seatRecord().responseReceived)
        bench.restoreSeat()
        bench.discard(ControlProp.SEAT_BACKREST)
        bench.exploreSeat(Move.BACKWARD)
        assertEquals(2, port.moves.size)

        val restarted = ControlBench(port, store, scheduler, { parking }, { door }, { false }, { scheduler.now }, RecordLease()).also { it.start(); it.onVisible() }
        assertTrue(restarted.lockReason(seatRecord()) != null)
        restarted.restoreSeat()
        restarted.discard(ControlProp.SEAT_BACKREST)
        assertEquals(2, port.moves.size)
        assertEquals(1, store.records.size)
    }

    @Test
    fun `losing park, an opened door, leaving the screen or a failed move stop the seat at once`() {
        bench.exploreSeat(Move.BACKWARD)
        bench.onParking(ParkingState(GearState.NOT_PARK, false))
        assertEquals("STOP", port.moves[1].what)

        val second = ControlBench(port, MemoryStore(), scheduler, { parking }, { door }, { false }, { scheduler.now }, RecordLease()).also { it.start(); it.onVisible() }
        second.exploreSeat(Move.BACKWARD)
        second.onDoor(DoorStatus.OPEN)
        assertEquals("STOP", port.moves[3].what)

        val third = ControlBench(port, MemoryStore(), scheduler, { parking }, { door }, { false }, { scheduler.now }, RecordLease()).also { it.start(); it.onVisible() }
        third.exploreSeat(Move.BACKWARD)
        third.onHidden()
        assertEquals("STOP", port.moves[5].what)

        val fourth = ControlBench(port, MemoryStore(), scheduler, { parking }, { door }, { false }, { scheduler.now }, RecordLease()).also { it.start(); it.onVisible() }
        fourth.exploreSeat(Move.BACKWARD)
        port.moves[6].err(ProbeError("VehicleException", "move failed", null))
        assertEquals("STOP", port.moves[7].what)
    }

    @Test
    fun `no seat move when its position cannot be read or the door is not closed`() {
        port.values[ControlProp.SEAT_BACKREST to DRIVER] = null
        bench.exploreSeat(Move.BACKWARD)
        port.values[ControlProp.SEAT_BACKREST to DRIVER] = "0"
        door = DoorStatus.CHECKING
        bench.exploreSeat(Move.BACKWARD)

        assertTrue(port.moves.isEmpty())
        assertTrue(store.records.isEmpty())
    }

    @Test
    fun `seat restore goes the other way, stops on reaching the original and deletes the record on a confirmed read`() {
        bench.exploreSeat(Move.BACKWARD)
        port.values[ControlProp.SEAT_BACKREST to DRIVER] = "4"
        port.listeners.getValue(ControlProp.SEAT_BACKREST)("4")
        port.moves[0].ok("BACKWARD")
        port.moves[1].ok("STOP")
        scheduler.advance(SEAT_SETTLE_MS)

        bench.restoreSeat()
        assertEquals("FORWARD", port.moves[2].what)
        port.listeners.getValue(ControlProp.SEAT_BACKREST)("2")
        assertEquals(3, port.moves.size)
        port.values[ControlProp.SEAT_BACKREST to DRIVER] = "0"
        port.listeners.getValue(ControlProp.SEAT_BACKREST)("0")
        assertEquals("STOP", port.moves[3].what)
        port.moves[2].ok("FORWARD")
        port.moves[3].ok("STOP")
        scheduler.advance(SEAT_SETTLE_MS)

        assertTrue(store.records.isEmpty())
    }

    // --- record file ---

    @Test
    fun `a failed directory sync is a failed save, so the command is never sent`() {
        val dir = Files.createTempDirectory("control").toFile()
        val failing = FileControlStore(File(dir, FileControlStore.FILE_NAME)) { throw java.io.IOException("fsync dir") }
        val b = ControlBench(port, failing, scheduler, { parking }, { door }, { false }, { scheduler.now }, RecordLease()).also { it.start(); it.onVisible() }

        b.apply(ControlProp.HVAC_FAN_SPEED)

        assertTrue(port.writes.isEmpty())
        assertTrue(b.state.storageFault!!.contains("fsync dir"))
        val working = FileControlStore(File(dir, "ok.json"))
        assertTrue(working.save(emptyList()))
    }

    @Test
    fun `the codec round-trips and rejects loose colors and bad shared values`() {
        val good = ControlRecord("j", ControlProp.AMBIENT_COLOR, FIVE, "0x00FFFFFF", WARM_COLOR, mapOf("LIGHT_SWITCH $FIVE" to "ON"), ControlStage.APPLY_CONFIRMED, "r", true)
        assertEquals(ControlLoad.Found(listOf(good)), ControlCodec.decode(ControlCodec.encode(listOf(good))))

        assertNull(ControlValues.parse(ControlProp.AMBIENT_COLOR, "0x-0000001"))
        assertNull(ControlValues.parse(ControlProp.AMBIENT_COLOR, "0xFFFFFF"))
        assertNull(ControlValues.parse(ControlProp.HVAC_FAN_SPEED, "+3"))
        val bad = listOf(
            good.copy(original = "0x-0000001"),
            good.copy(related = mapOf("LIGHT_SWITCH $FIVE" to "maybe")),
            good.copy(related = emptyMap()),
            good.copy(related = mapOf("LIGHT_SWITCH $FIVE" to "ON", "HVAC_AUTO $DUAL" to "false")),
            good.copy(prop = ControlProp.HVAC_FAN_SPEED, original = "3", target = "2", related = mapOf("HVAC_POWER $FIVE" to "1", "HVAC_AUTO $DUAL" to "false", "HVAC_DUAL $DUAL" to "false")),
        )
        bad.forEach { assertTrue(it.toString(), ControlCodec.decode(ControlCodec.encode(listOf(it))) is ControlLoad.Unreadable) }
    }

    // --- review boundaries ---

    @Test
    fun `leaving the screen while a preparation waits cancels it, and its late replies send nothing`() {
        port.holdCapability = true
        bench.apply(ControlProp.HVAC_FAN_SPEED)
        bench.onHidden()
        port.held.forEach { it() }
        port.held.clear()
        assertTrue(port.writes.isEmpty())
        assertTrue(store.records.isEmpty())
        assertFalse(bench.state.busy)

        bench.exploreSeat(Move.BACKWARD) // still hidden
        port.holdCapability = false
        bench.apply(ControlProp.AMBIENT_COLOR)
        assertTrue(port.moves.isEmpty())
        assertTrue(port.writes.isEmpty())

        bench.onVisible()
        bench.apply(ControlProp.AMBIENT_COLOR)
        assertEquals(1, port.writes.size)
    }

    @Test
    fun `the seat does not start outside its range or without a position listener`() {
        port.values[ControlProp.SEAT_BACKREST to DRIVER] = "120"
        bench.exploreSeat(Move.BACKWARD)
        port.values[ControlProp.SEAT_BACKREST to DRIVER] = "0"
        port.observeThrows = true
        bench.exploreSeat(Move.BACKWARD)

        assertTrue(port.moves.isEmpty())
        assertTrue(store.records.isEmpty())
    }

    @Test
    fun `a null position or a direct read showing a change stops the seat before the watchdog`() {
        bench.exploreSeat(Move.BACKWARD)
        port.listeners.getValue(ControlProp.SEAT_BACKREST)(null)
        assertEquals(listOf("BACKWARD", "STOP"), port.moves.map { it.what })

        val second = ControlBench(port, MemoryStore(), scheduler, { parking }, { door }, { false }, { scheduler.now }, RecordLease()).also { it.start(); it.onVisible() }
        second.exploreSeat(Move.BACKWARD)
        port.values[ControlProp.SEAT_BACKREST to DRIVER] = "1" // the listener stays silent
        scheduler.advance(SEAT_POLL_MS)
        assertEquals("STOP", port.moves[3].what)
        assertTrue(SEAT_POLL_MS < SEAT_WATCHDOG_MS)
    }

    @Test
    fun `while restoring, moving away from the original stops at once`() {
        bench.exploreSeat(Move.BACKWARD)
        port.values[ControlProp.SEAT_BACKREST to DRIVER] = "4"
        port.listeners.getValue(ControlProp.SEAT_BACKREST)("4")
        port.moves[0].ok("BACKWARD")
        port.moves[1].ok("STOP")
        scheduler.advance(SEAT_SETTLE_MS)

        bench.restoreSeat()
        port.listeners.getValue(ControlProp.SEAT_BACKREST)("5")

        assertEquals(listOf("BACKWARD", "STOP", "FORWARD", "STOP"), port.moves.map { it.what })
    }

    @Test
    fun `fan and temperature wait for each other, while an independent property can still be checked`() {
        bench.apply(ControlProp.HVAC_FAN_SPEED)
        answer(port.writes[0], "2", ControlProp.HVAC_FAN_SPEED to DUAL, "2")

        bench.apply(ControlProp.HVAC_TEMPERATURE)
        assertEquals(1, port.writes.size)
        bench.apply(ControlProp.AMBIENT_COLOR)
        assertEquals("AMBIENT_COLOR $FIVE=$WARM_COLOR", port.writes[1].what)

        bench.restore(ControlProp.HVAC_FAN_SPEED)
        answer(port.writes[2], "3", ControlProp.HVAC_FAN_SPEED to DUAL, "3")
        assertNull(store.records.firstOrNull { it.prop == ControlProp.HVAC_FAN_SPEED })
        bench.apply(ControlProp.HVAC_TEMPERATURE)
        assertEquals("HVAC_TEMPERATURE $DRIVER=22.5", port.writes[3].what)
    }

    @Test
    fun `the auto state is not written when HVAC auto is not writable`() {
        port.autoAccess = Access.READ
        bench.apply(ControlProp.HVAC_FAN_SPEED)
        port.side["HVAC_AUTO $DUAL"] = "true"
        answer(port.writes[0], "2", ControlProp.HVAC_FAN_SPEED to DUAL, "2")
        bench.restore(ControlProp.HVAC_FAN_SPEED)
        answer(port.writes[1], "3", ControlProp.HVAC_FAN_SPEED to DUAL, "3")

        assertEquals(2, port.writes.size)
        assertEquals(ControlStage.RESTORE_FAILED, record(ControlProp.HVAC_FAN_SPEED).stage)
    }

    @Test
    fun `a bench whose screen was never shown starts nothing and hiding twice is harmless`() {
        val unseen = ControlBench(port, store, scheduler, { parking }, { door }, { false }, { scheduler.now }, RecordLease()).also { it.start() }
        unseen.apply(ControlProp.HVAC_FAN_SPEED)
        unseen.exploreSeat(Move.BACKWARD)
        assertTrue(port.writes.isEmpty())
        assertTrue(port.moves.isEmpty())
        assertTrue(store.records.isEmpty())

        unseen.onVisible()
        unseen.onHidden()
        unseen.onHidden()
        unseen.apply(ControlProp.HVAC_FAN_SPEED)
        assertTrue(port.writes.isEmpty())

        unseen.onVisible()
        unseen.apply(ControlProp.HVAC_FAN_SPEED)
        assertEquals(1, port.writes.size)
    }

    @Test
    fun `the auto state is not written when HVAC auto is write-only, because it could not be re-read`() {
        port.autoAccess = Access.WRITE
        bench.apply(ControlProp.HVAC_FAN_SPEED)
        port.side["HVAC_AUTO $DUAL"] = "true"
        answer(port.writes[0], "2", ControlProp.HVAC_FAN_SPEED to DUAL, "2")
        bench.restore(ControlProp.HVAC_FAN_SPEED)
        answer(port.writes[1], "3", ControlProp.HVAC_FAN_SPEED to DUAL, "3")

        assertEquals(2, port.writes.size)
        assertEquals(ControlStage.RESTORE_FAILED, record(ControlProp.HVAC_FAN_SPEED).stage)
        assertTrue(record(ControlProp.HVAC_FAN_SPEED).note!!.contains("WRITE"))
    }

    @Test
    fun `a closed or replaced owner leaves the file and the vehicle alone when its held side reads answer late`() {
        val losses = listOf<(ControlBench, RecordLease) -> Unit>(
            { old, _ -> old.close() },
            { _, lease -> ControlBench(port, MemoryStore(), scheduler, { parking }, { door }, { false }, { scheduler.now }, lease).start() },
        )
        losses.forEach { lose ->
            val lease = RecordLease()
            val s = MemoryStore()
            val old = ControlBench(port, s, scheduler, { parking }, { door }, { false }, { scheduler.now }, lease).also { it.start(); it.onVisible() }
            port.values[ControlProp.HVAC_FAN_SPEED to DUAL] = "3"
            port.side["HVAC_AUTO $DUAL"] = "false"
            old.apply(ControlProp.HVAC_FAN_SPEED)
            answer(port.writes.last(), "2", ControlProp.HVAC_FAN_SPEED to DUAL, "2")
            old.restore(ControlProp.HVAC_FAN_SPEED)
            port.holdSide = true
            answer(port.writes.last(), "3", ControlProp.HVAC_FAN_SPEED to DUAL, "3") // confirm now waits on side reads
            assertTrue(port.held.isNotEmpty())
            val saves = s.saved.size
            val writes = port.writes.size

            lose(old, lease)
            port.holdSide = false
            port.side["HVAC_AUTO $DUAL"] = "true" // an owner that went on would try to restore auto
            port.held.toList().also { port.held.clear() }.forEach { it() }
            scheduler.advance(CONTROL_READ_TIMEOUT_MS + CONTROL_WRITE_TIMEOUT_MS)

            assertEquals(saves, s.saved.size)
            assertEquals(writes, port.writes.size)
            assertEquals(ControlStage.RESTORE_REQUESTED, s.records.single().stage)
        }
    }

    @Test
    fun `after a storage fault a confirmed restore keeps its record and saves only the answer`() {
        bench.apply(ControlProp.AMBIENT_COLOR)
        answer(port.writes[0], WARM_COLOR, ControlProp.AMBIENT_COLOR to FIVE, WARM_COLOR)
        bench.restore(ControlProp.AMBIENT_COLOR)
        val restoreWrite = port.writes[1]

        store.failSaves = true
        bench.apply(ControlProp.HVAC_FAN_SPEED) // its record cannot be saved: storage fault, no fan command
        assertTrue(bench.state.storageFault != null)
        store.failSaves = false
        answer(restoreWrite, "0x00FFFFFF", ControlProp.AMBIENT_COLOR to FIVE, "0x00FFFFFF")

        val kept = record(ControlProp.AMBIENT_COLOR)
        assertTrue(kept.responseReceived)
        assertEquals("0x00FFFFFF", kept.lastRead)
        assertEquals(ControlStage.RESTORE_REQUESTED, kept.stage)
        bench.discard(ControlProp.AMBIENT_COLOR)
        bench.restore(ControlProp.AMBIENT_COLOR)
        assertEquals(1, store.records.size)
        assertEquals(2, port.writes.size)
    }

    @Test
    fun `a current value outside the capability or malformed ends with no record and no command`() {
        port.values[ControlProp.HVAC_FAN_SPEED to DUAL] = "9" // max 8: the original could not be restored
        bench.apply(ControlProp.HVAC_FAN_SPEED)
        port.values[ControlProp.HVAC_TEMPERATURE to DRIVER] = "30.5" // max 30.0
        bench.apply(ControlProp.HVAC_TEMPERATURE)
        port.values[ControlProp.AMBIENT_COLOR to FIVE] = "0xFFFFFF"
        bench.apply(ControlProp.AMBIENT_COLOR)

        assertTrue(port.writes.isEmpty())
        assertTrue(store.records.isEmpty())
        assertTrue(store.saved.isEmpty())
        assertTrue(bench.state.log.any { it.contains("현재값 9") })
        assertTrue(bench.state.log.any { it.contains("현재값 30.5") })
    }

    @Test
    fun `the codec rejects a repeated jobId or requestId across records`() {
        val color = ControlRecord("j", ControlProp.AMBIENT_COLOR, FIVE, "0x00FFFFFF", WARM_COLOR, mapOf("LIGHT_SWITCH $FIVE" to "ON"), ControlStage.APPLY_CONFIRMED, "r", true)
        val seat = ControlRecord("k", ControlProp.SEAT_BACKREST, DRIVER, "0", "FORWARD", emptyMap(), ControlStage.RESULT_UNKNOWN, "s", true)
        assertTrue(ControlCodec.decode(ControlCodec.encode(listOf(color, seat))) is ControlLoad.Found)
        assertTrue(ControlCodec.decode(ControlCodec.encode(listOf(color, seat.copy(jobId = "j")))) is ControlLoad.Unreadable)
        assertTrue(ControlCodec.decode(ControlCodec.encode(listOf(color, seat.copy(requestId = "r")))) is ControlLoad.Unreadable)
    }

    @Test
    fun `product lighting work or records block the bench's vehicle writes but not the seat STOP`() {
        var product: String? = "휴식 무드등을 맞추거나 되돌리는 중이에요"
        val b = ControlBench(port, MemoryStore(), scheduler, { parking }, { door }, { false }, { scheduler.now }, RecordLease(), externalBlock = { product }).also { it.start(); it.onVisible() }
        b.apply(ControlProp.AMBIENT_COLOR)
        b.exploreSeat(Move.BACKWARD)
        assertTrue(port.writes.isEmpty())
        assertTrue(port.moves.isEmpty())

        product = null
        b.exploreSeat(Move.BACKWARD)
        product = "busy"
        b.stopSeat("수동")
        assertEquals(listOf("BACKWARD", "STOP"), port.moves.map { it.what })
    }
}
