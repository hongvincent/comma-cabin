package app.commacabin.climate

import app.commacabin.control.ControlPort
import app.commacabin.control.ControlProp
import app.commacabin.control.Move
import app.commacabin.control.SideState
import app.commacabin.probe.Access
import app.commacabin.probe.FakeScheduler
import app.commacabin.probe.GearState
import app.commacabin.probe.ParkingState
import app.commacabin.probe.ProbeError
import app.commacabin.probe.RecordLease
import app.commacabin.support.AreaBound
import app.commacabin.support.CapabilityResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val FIVE = "ROW_1_LEFT+ROW_1_RIGHT+ROW_2_CENTER+ROW_2_LEFT+ROW_2_RIGHT"
private const val FRONT = "ROW_1_LEFT+ROW_1_RIGHT"
private const val REAR = "ROW_2_CENTER+ROW_2_LEFT+ROW_2_RIGHT"
private const val DRIVER = DRIVER_AREA
private const val PASSENGER = "ROW_1_RIGHT"

/** A write waiting for the test. [prop] null = the auto state. */
private class W(val prop: ClimateProp?, val area: String, val value: Float?, val auto: Boolean?, val ok: (Any?) -> Unit, val err: (ProbeError) -> Unit)

private class FakePort : ClimatePort {
    val caps = mutableMapOf<ClimateProp, Triple<Access, Map<String, ClosedFloatingPointRange<Float>>, Float?>?>(
        ClimateProp.FAN to Triple(Access.READ_WRITE, mapOf(FRONT to 1f..8f), null),
        ClimateProp.TEMP to Triple(Access.READ_WRITE, mapOf(DRIVER to 15f..30f, PASSENGER to 15f..30f), 0.5f),
    )
    val sides = mutableMapOf<Side, Pair<Access, List<String>>?>(
        Side.POWER to (Access.READ_WRITE to listOf(FIVE)),
        Side.AUTO to (Access.READ_WRITE to listOf(FRONT)),
        Side.DUAL to (Access.READ_WRITE to listOf(FRONT)),
    )
    val values = mutableMapOf((ClimateProp.FAN to FRONT) to 3f, (ClimateProp.TEMP to DRIVER) to 22f, (ClimateProp.TEMP to PASSENGER) to 22f)
    val sideValues = mutableMapOf((Side.POWER to FIVE) to true, (Side.AUTO to FRONT) to false, (Side.DUAL to FRONT) to false)
    val failSide = mutableSetOf<Side>()
    val failValues = mutableSetOf<ClimateProp>()
    val writes = mutableListOf<W>()
    val listeners = mutableMapOf<Pair<ClimateProp, String>, (Float?) -> Unit>()
    var calls = 0
    var holdValueReads = false
    val held = mutableListOf<() -> Unit>()

    fun release() = held.toList().also { held.clear() }.forEach { it() }

    override fun capability(prop: ClimateProp, ok: (Access, Map<String, ClosedFloatingPointRange<Float>>, Float?) -> Unit, err: (ProbeError) -> Unit) {
        calls++
        caps[prop]?.let { ok(it.first, it.second, it.third) } ?: err(ProbeError("X", "no", null))
    }

    override fun sideAreas(side: Side, ok: (Access, List<String>) -> Unit, err: (ProbeError) -> Unit) {
        calls++
        sides[side]?.let { ok(it.first, it.second) } ?: err(ProbeError("X", "no", null))
    }

    override fun read(prop: ClimateProp, area: String, ok: (Float?) -> Unit, err: (ProbeError) -> Unit) {
        calls++
        val a = { if (prop in failValues) err(ProbeError("X", "read failed", null)) else ok(values[prop to area]) }
        if (holdValueReads) held += a else a()
    }

    override fun readSide(side: Side, area: String, ok: (Boolean?) -> Unit, err: (ProbeError) -> Unit) {
        calls++
        if (side in failSide) err(ProbeError("X", "read failed", null)) else ok(sideValues[side to area])
    }

    override fun write(prop: ClimateProp, area: String, value: Float, ok: (Float?) -> Unit, err: (ProbeError) -> Unit) {
        calls++
        writes += W(prop, area, value, null, { ok(it as Float?) }, err)
    }

    override fun writeAuto(area: String, value: Boolean, ok: (Boolean?) -> Unit, err: (ProbeError) -> Unit) {
        calls++
        writes += W(null, area, null, value, { ok(it as Boolean?) }, err)
    }

    override fun observe(prop: ClimateProp, area: String, onUpdate: (Float?) -> Unit): AutoCloseable {
        listeners[prop to area] = onUpdate
        return AutoCloseable { listeners.remove(prop to area) }
    }
}

private class MemoryStore(var groups: List<ClimateGroup> = emptyList()) : ClimateStore {
    var failSaves = false
    var saves = 0
    override fun load(): ClimateLoad = if (groups.isEmpty()) ClimateLoad.Empty else ClimateLoad.Found(groups)
    override fun save(groups: List<ClimateGroup>): Boolean {
        if (failSaves) return false
        this.groups = groups
        saves++
        return true
    }
}

class RestClimateTest {
    private val port = FakePort()
    private val store = MemoryStore()
    private val scheduler = FakeScheduler()
    private var parking = ParkingState(GearState.PARK, false)
    private var debug: String? = null
    private val lease = RecordLease()
    private val log = mutableListOf<String>()
    private var ids = 0
    private val climate by lazy { owner() }
    private val both = ClimateChoice(fan = true, temp = true, tempC = 22.5f)

    private fun owner(s: ClimateStore = store) =
        RestClimate(port, s, scheduler, { parking }, { debug }, lease, { log += it }, newId = { "id-${++ids}" }).also { it.start() }

    /** The vehicle takes the value (unless [value] says otherwise), then the terminal callback and the reads run. */
    private fun answer(w: W, value: Float? = w.value, ok: Boolean = true, sideEffect: () -> Unit = {}) {
        if (w.prop != null && value != null) port.values[w.prop to w.area] = value
        if (w.prop == null) port.sideValues[Side.AUTO to w.area] = w.auto!!
        sideEffect()
        if (ok) w.ok(if (w.prop == null) w.auto else w.value) else w.err(ProbeError("VehicleException", "failed", null))
        scheduler.advance(CLIMATE_SETTLE_MS)
    }

    private fun group() = store.groups.single()

    @Test
    fun `both choices off reads nothing, power off writes nothing and never turns it on`() {
        climate.onRestStart(ClimateChoice(fan = false, temp = false, tempC = 22f))
        assertEquals(0, port.calls)
        assertEquals("공조 조정을 꺼 두었어요", climate.state.restLine)

        port.sideValues[Side.POWER to FIVE] = false
        climate.onRestStart(both)
        assertTrue(port.writes.isEmpty())
        assertEquals(0, store.saves)
        assertTrue(climate.state.items.filter { it.result == ClimateResult.SKIPPED }.any { it.text.contains("공조가 꺼져 있어") })
    }

    @Test
    fun `a rest lowers the fan and sets the driver only, then restores both with the auto original saved once`() {
        val run = climate.onRestStart(both)
        val fan = port.writes.single()
        assertEquals(2f, fan.value)
        assertFalse(group().members.single().responseReceived) // saved before the command
        assertFalse(group().autoOriginal)
        answer(fan)
        val temp = port.writes[1]
        assertEquals(DRIVER, temp.area)
        assertEquals(22.5f, temp.value)
        answer(temp)
        assertEquals(22f, port.values[ClimateProp.TEMP to PASSENGER]) // never written
        assertEquals("바람을 한 단계 줄이고 운전석 온도를 22.5°C로 맞췄어요", climate.state.restLine)

        climate.onRestEnd(run, "USER")
        assertEquals(DRIVER, port.writes[2].area) // temperature first
        assertEquals(22f, port.writes[2].value)
        answer(port.writes[2])
        assertEquals(3f, port.writes[3].value)
        answer(port.writes[3])
        assertEquals(4, port.writes.size) // auto unchanged: no auto write
        assertTrue(store.groups.isEmpty())
    }

    @Test
    fun `an auto change across the fan command has no knowable source - no temperature, no automatic auto restore, only an explicit restore`() {
        val run = climate.onRestStart(both)
        val fan = port.writes.single()
        answer(fan, sideEffect = { port.sideValues[Side.AUTO to FRONT] = true }) // auto moved while the fan request was open
        assertEquals(GroupStage.RESULT_UNKNOWN, group().stage)
        assertFalse(group().autoOriginal) // the original stays the one read before the first change
        assertEquals(1, port.writes.size) // no temperature for this group
        climate.onRestEnd(run, "USER")
        assertEquals(1, port.writes.size) // no automatic restore, no auto write

        climate.restoreRecords()
        assertEquals(3f, port.writes[1].value)
        answer(port.writes[1])
        val auto = port.writes[2]
        assertNull(auto.prop)
        assertEquals(false, auto.auto)
        answer(auto)
        assertTrue(store.groups.isEmpty())
    }

    @Test
    fun `a failed auto restore is not retried and keeps the record`() {
        val run = climate.onRestStart(both.copy(temp = false))
        answer(port.writes[0], sideEffect = { port.sideValues[Side.AUTO to FRONT] = true })
        climate.onRestEnd(run, "USER")
        climate.restoreRecords()
        answer(port.writes[1])
        val auto = port.writes[2]
        auto.ok(false) // the echo says done, but the auto state does not move
        scheduler.advance(CLIMATE_SETTLE_MS)
        assertEquals(GroupStage.AUTO_RESTORE_FAILED, group().stage)
        scheduler.advance(60_000)
        assertEquals(3, port.writes.size)
    }

    @Test
    fun `an explicit auto restore checks read-write support and the area again`() {
        val run = climate.onRestStart(both.copy(temp = false))
        answer(port.writes[0], sideEffect = { port.sideValues[Side.AUTO to FRONT] = true })
        climate.onRestEnd(run, "USER")
        port.sides[Side.AUTO] = Access.READ to listOf(FRONT)
        climate.restoreRecords()
        answer(port.writes[1])
        assertEquals(2, port.writes.size)
        assertEquals(1, store.groups.size)
    }

    @Test
    fun `the record goes only after a full re-read - auto restored but the fan at 4 keeps it with the actual value shown`() {
        port.sideValues[Side.AUTO to FRONT] = true // original auto on
        val run = climate.onRestStart(both.copy(temp = false))
        answer(port.writes[0])
        port.sideValues[Side.AUTO to FRONT] = false // the user turned auto off while resting
        climate.onRestEnd(run, "USER")
        assertEquals(GroupStage.EXTERNAL_CHANGE, group().stage)
        climate.restoreRecords()
        answer(port.writes[1]) // fan 3
        val auto = port.writes[2]
        assertEquals(true, auto.auto)
        answer(auto, sideEffect = { port.values[ClimateProp.FAN to FRONT] = 4f }) // auto mode adjusts the fan
        assertEquals(1, store.groups.size)
        assertTrue(climate.state.earlier.any { it.prop == ClimateProp.FAN && it.text.contains("4") } || climate.state.items.any { it.prop == ClimateProp.FAN && it.text.contains("4") })
        assertEquals(3, port.writes.size) // no retry
        assertTrue(climate.state.items.any { it.prop == null && it.text.contains("원래대로") })
    }

    @Test
    fun `an SDK error stays in the log - the screen gets the reason in words`() {
        port.caps[ClimateProp.TEMP] = null
        climate.loadTempRange()
        assertEquals("운전석 온도 범위를 확인하지 못했어요", climate.state.tempRangeProblem)
        port.failValues += ClimateProp.FAN
        climate.onRestStart(both.copy(temp = false))
        assertTrue(port.writes.isEmpty())
        assertEquals("현재 값을 읽지 못해 바꾸지 않았어요", climate.state.items.single { it.prop == ClimateProp.FAN }.text)
        assertTrue(log.any { it.startsWith("현재 값을 읽지 못해 바꾸지 않았어요 — 원인:") && it.contains("read failed") })
    }

    @Test
    fun `a failed last read keeps the record`() {
        val run = climate.onRestStart(both.copy(temp = false))
        answer(port.writes[0])
        climate.onRestEnd(run, "USER")
        val back = port.writes[1]
        port.values[ClimateProp.FAN to FRONT] = 3f
        port.failValues += ClimateProp.FAN // the confirm read and the final read fail
        back.ok(3f)
        scheduler.advance(CLIMATE_SETTLE_MS)
        assertEquals(1, store.groups.size)
    }

    @Test
    fun `an explicit restore decides by the current value, not by an old RESTORED stage`() {
        val fan = ClimateMember(ClimateProp.FAN, FRONT, 3f, 2f, MemberStage.RESTORED, "r", 3f, responseReceived = true)
        store.groups = listOf(ClimateGroup("g", FRONT, FIVE, FRONT, false, mapOf("POWER" to true, "AUTO" to false, "DUAL" to false), GroupStage.HELD, listOf(fan)))
        port.values[ClimateProp.FAN to FRONT] = 5f // changed again since
        climate.restoreRecords()
        assertEquals(3f, port.writes.single().value)
        answer(port.writes.single())
        assertTrue(store.groups.isEmpty())
    }

    @Test
    fun `groups sharing a power area never overlap - an open request in one blocks the other until it answers`() {
        port.caps[ClimateProp.FAN] = Triple(Access.READ_WRITE, mapOf(FRONT to 1f..8f, REAR to 1f..8f), null)
        port.sides[Side.AUTO] = Access.READ_WRITE to listOf(FRONT, REAR)
        port.sideValues[Side.AUTO to REAR] = false
        port.values[ClimateProp.FAN to REAR] = 4f
        val run = climate.onRestStart(both.copy(temp = false))
        val front = port.writes.single()
        scheduler.advance(CLIMATE_WRITE_TIMEOUT_MS) // no answer: the shared power area is unknown
        assertEquals(1, port.writes.size)
        assertTrue(climate.state.items.any { it.text.contains("기다리고") })
        climate.onRestEnd(run, "USER")
        assertEquals(1, port.writes.size)
        answer(front) // late answer, then the group is restored by the usual rules
        assertEquals(3f, port.writes[1].value)
        answer(port.writes[1])
        assertTrue(store.groups.isEmpty())
    }

    @Test
    fun `an original temperature off the vehicle grid is never changed`() {
        port.values[ClimateProp.TEMP to DRIVER] = 22.25f
        climate.onRestStart(both.copy(fan = false))
        assertTrue(port.writes.isEmpty())
        assertEquals(0, store.saves)
    }

    @Test
    fun `a mode change during a restore stops the further writes`() {
        val run = climate.onRestStart(both)
        answer(port.writes[0])
        answer(port.writes[1])
        climate.onRestEnd(run, "USER")
        val temp = port.writes[2]
        answer(temp, sideEffect = { port.sideValues[Side.AUTO to FRONT] = true }) // auto moved across the temperature restore
        assertEquals(3, port.writes.size) // no fan restore after it
        assertEquals(GroupStage.RESULT_UNKNOWN, group().stage)
    }

    @Test
    fun `every fan area is handled per group, and one group's failure does not stop the other`() {
        port.caps[ClimateProp.FAN] = Triple(Access.READ_WRITE, mapOf(FRONT to 1f..8f, REAR to 1f..8f), null)
        port.sides[Side.AUTO] = Access.READ_WRITE to listOf(FRONT, REAR)
        port.sideValues[Side.AUTO to REAR] = false
        port.values[ClimateProp.FAN to REAR] = 4f
        climate.onRestStart(both.copy(temp = false))
        answer(port.writes[0], ok = false) // front: onFailed
        val rear = port.writes[1]
        assertEquals(REAR, rear.area)
        assertEquals(3f, rear.value)
        answer(rear)
        assertEquals(2, store.groups.size)
        assertEquals(MemberStage.RESULT_UNKNOWN, store.groups.single { it.autoArea == FRONT }.members.single().stage)
        assertEquals(MemberStage.APPLIED, store.groups.single { it.autoArea == REAR }.members.single().stage)
    }

    @Test
    fun `the fan never goes below max of min and 1, and never to off`() {
        port.caps[ClimateProp.FAN] = Triple(Access.READ_WRITE, mapOf(FRONT to 0f..8f), null)
        port.values[ClimateProp.FAN to FRONT] = 1f
        climate.onRestStart(both.copy(temp = false))
        assertTrue(port.writes.isEmpty())
        assertTrue(climate.state.items.single { it.prop == ClimateProp.FAN }.text.contains("가장 약한"))
    }

    @Test
    fun `temperature - driver-only area, exact grid value sent unrounded, off-grid or Sync on skipped while the fan goes on`() {
        port.caps[ClimateProp.TEMP] = Triple(Access.READ_WRITE, mapOf(DRIVER to 15f..30f), 0.25f)
        climate.onRestStart(both.copy(fan = false, tempC = 22.25f))
        assertEquals(22.25f, port.writes.single().value)

        val other = owner(MemoryStore())
        port.writes.clear()
        port.caps[ClimateProp.TEMP] = Triple(Access.READ_WRITE, mapOf(DRIVER to 15f..30f), 0.5f)
        other.onRestStart(both.copy(fan = false, tempC = 22.3f))
        assertTrue(port.writes.isEmpty())
        assertTrue(other.state.items.single { it.prop == ClimateProp.TEMP }.text.contains("다시 골라"))
        assertEquals(TempRange(15f, 30f, 0.5f), other.state.tempRange)

        val third = owner(MemoryStore())
        port.sideValues[Side.DUAL to FRONT] = true
        third.onRestStart(both)
        assertEquals(1, port.writes.size)
        assertEquals(ClimateProp.FAN, port.writes.single().prop)
        answer(port.writes.single())
        assertTrue(third.state.items.any { it.prop == ClimateProp.TEMP && it.text.contains("Sync") })

        val fourth = owner(MemoryStore())
        port.writes.clear()
        port.caps[ClimateProp.TEMP] = Triple(Access.READ_WRITE, mapOf(FRONT to 15f..30f), 0.5f) // no driver-only area
        fourth.onRestStart(both.copy(fan = false))
        assertTrue(port.writes.isEmpty())
    }

    @Test
    fun `the SDK adapter sends 22_25 as 22_25 and a fan only as a whole step`() {
        val sent = mutableListOf<String>()
        val control = object : ControlPort {
            override fun capability(prop: ControlProp, ok: (CapabilityResult, CapabilityResult?, Float?) -> Unit, err: (ProbeError) -> Unit) =
                ok(CapabilityResult(Access.READ_WRITE, listOf(AreaBound(DRIVER, "15.0", "30.0"))), null, 0.25f)
            override fun sideAreas(state: SideState, ok: (Access, List<String>) -> Unit, err: (ProbeError) -> Unit) = Unit
            override fun read(prop: ControlProp, area: String, ok: (String?) -> Unit, err: (ProbeError) -> Unit) = ok("Infinity")
            override fun readSide(state: SideState, area: String, ok: (String?) -> Unit, err: (ProbeError) -> Unit) = Unit
            override fun write(prop: ControlProp, area: String, value: String, ok: (String?) -> Unit, err: (ProbeError) -> Unit) {
                sent += "$prop $value"
            }
            override fun writeSide(state: SideState, area: String, value: String, ok: (String?) -> Unit, err: (ProbeError) -> Unit) = Unit
            override fun move(area: String, control: Move, ok: (String?) -> Unit, err: (ProbeError) -> Unit) = Unit
            override fun observe(prop: ControlProp, area: String, onUpdate: (String?) -> Unit, onError: (ProbeError) -> Unit) = AutoCloseable {}
        }
        val sdk = SdkClimatePort(control)
        sdk.write(ClimateProp.TEMP, DRIVER, 22.25f, {}, {})
        var rejected = false
        sdk.write(ClimateProp.FAN, FRONT, 2.5f, {}, { rejected = true })
        var read: Float? = 0f
        sdk.read(ClimateProp.TEMP, DRIVER, { read = it }, {})
        assertEquals(listOf("HVAC_TEMPERATURE 22.25"), sent)
        assertTrue(rejected)
        assertNull(read) // a non-finite value is no value
        assertEquals("22.25", ClimateValues.show(ClimateProp.TEMP, 22.25f))
        assertFalse(ClimateValues.onGrid(Float.NaN, 15f, 0.5f))
        assertFalse(ClimateValues.onGrid(22f, 15f, Float.POSITIVE_INFINITY))
    }

    @Test
    fun `a failing save sends nothing and then blocks adding records`() {
        store.failSaves = true
        climate.onRestStart(both)
        assertTrue(port.writes.isEmpty())
        assertNotNull(climate.state.problem)
        store.failSaves = false
        climate.onRestStart(both)
        assertTrue(port.writes.isEmpty())
    }

    @Test
    fun `a timeout locks the group, the late answer is read, and the group is restored after the end`() {
        val run = climate.onRestStart(both)
        val slow = port.writes.single()
        scheduler.advance(CLIMATE_WRITE_TIMEOUT_MS)
        assertEquals(1, port.writes.size) // the temperature of the same group waits: nothing new for a locked group
        assertTrue(group().locked)
        climate.restoreRecords()
        climate.discardRecords()
        climate.onRestEnd(run, "USER")
        assertEquals(1, port.writes.size)

        answer(slow) // late onComplete, read 2
        assertEquals(3f, port.writes[1].value)
        answer(port.writes[1])
        assertTrue(store.groups.isEmpty())
    }

    @Test
    fun `losing parking holds - a late answer after P is back sends nothing until an explicit restore`() {
        val run = climate.onRestStart(both)
        val sent = port.writes.single()
        parking = ParkingState(GearState.NOT_PARK, false)
        climate.onParking(parking)
        climate.onRestEnd(run, "LEFT_PARK", parkingLost = true)
        parking = ParkingState(GearState.PARK, false)
        climate.onParking(parking)
        answer(sent)
        assertEquals(1, port.writes.size)
        assertEquals(GroupStage.HELD, group().stage)

        climate.restoreRecords()
        assertEquals(3f, port.writes[1].value)
        answer(port.writes[1])
        assertTrue(store.groups.isEmpty())
    }

    @Test
    fun `a stale first listener value is checked by reads, a real change stops the group and only an explicit restore writes`() {
        val run = climate.onRestStart(both)
        answer(port.writes[0])
        answer(port.writes[1])
        port.listeners.getValue(ClimateProp.FAN to FRONT)(3f) // cached old value; the vehicle reads 2
        assertEquals(GroupStage.ACTIVE, group().stage)

        port.values[ClimateProp.FAN to FRONT] = 5f
        port.listeners.getValue(ClimateProp.FAN to FRONT)(5f)
        assertEquals(GroupStage.EXTERNAL_CHANGE, group().stage)
        climate.onRestEnd(run, "USER")
        assertEquals(2, port.writes.size)
        assertTrue(climate.state.items.any { it.text.contains("차량에서 바뀐") })

        climate.restoreRecords()
        answer(port.writes[2]) // temperature 22
        answer(port.writes[3]) // fan 3
        assertTrue(store.groups.isEmpty())
    }

    @Test
    fun `a shared state changed from the vehicle side keeps the group as it is at the end`() {
        val run = climate.onRestStart(both)
        answer(port.writes[0])
        answer(port.writes[1])
        port.sideValues[Side.AUTO to FRONT] = true // the user turned auto on while resting
        climate.onRestEnd(run, "USER")
        assertEquals(2, port.writes.size)
        assertEquals(GroupStage.EXTERNAL_CHANGE, group().stage)
    }

    @Test
    fun `an auto change that could not be attributed is left for a manual check, not reverted`() {
        val run = climate.onRestStart(both.copy(temp = false))
        port.failSide += Side.AUTO
        answer(port.writes[0]) // the auto read right after the command fails
        port.failSide -= Side.AUTO
        port.sideValues[Side.AUTO to FRONT] = true
        climate.onRestEnd(run, "USER")
        assertEquals(1, port.writes.size)
        assertEquals(GroupStage.RESULT_UNKNOWN, group().stage)
        assertTrue(log.any { it.contains("출처를 알 수 없어") })
    }

    @Test
    fun `an explicit restore with Sync on keeps the temperature, with power off writes nothing, and auto goes back last`() {
        val run = climate.onRestStart(both)
        answer(port.writes[0])
        answer(port.writes[1])
        port.sideValues[Side.DUAL to FRONT] = true
        climate.onRestEnd(run, "USER") // Sync turned on from the vehicle: external, nothing written
        assertEquals(2, port.writes.size)

        port.sideValues[Side.POWER to FIVE] = false
        climate.restoreRecords()
        assertEquals(2, port.writes.size)
        assertEquals(1, store.groups.size)

        port.sideValues[Side.POWER to FIVE] = true
        port.sideValues[Side.AUTO to FRONT] = true
        climate.restoreRecords()
        assertEquals(2, port.writes.size) // the temperature is held first (Sync), so the group stops before the fan
        assertTrue(climate.state.earlier.any { it.text.contains("연동") } || climate.state.items.any { it.text.contains("연동") })

        port.sideValues[Side.DUAL to FRONT] = false
        climate.restoreRecords()
        answer(port.writes[2])
        answer(port.writes[3])
        val auto = port.writes[4]
        assertEquals(false, auto.auto)
        answer(auto)
        assertTrue(store.groups.isEmpty())
    }

    @Test
    fun `an end from an older session changes nothing and a replaced owner never writes`() {
        val first = climate.onRestStart(both.copy(temp = false))
        answer(port.writes[0])
        climate.onRestEnd(first, "USER")
        val restore = port.writes[1]
        val second = climate.onRestStart(both.copy(temp = false))
        val items = climate.state.items
        assertTrue(items.single { it.prop == ClimateProp.FAN }.text.contains("이전 공조 기록"))
        climate.onRestEnd(first, "APP_LEFT")
        answer(restore)
        assertEquals(items, climate.state.items)
        assertTrue(second > first)

        val other = MemoryStore()
        val a = owner(other)
        a.onRestStart(both.copy(temp = false))
        val sent = port.writes.last()
        owner(MemoryStore())
        val saves = other.saves
        answer(sent)
        assertEquals(saves, other.saves)
    }

    @Test
    fun `a debug change only redraws the restore block - on after the first debug read of the next run, off with debug work or records, no vehicle call`() {
        val fan = ClimateMember(ClimateProp.FAN, FRONT, 3f, 2f, MemberStage.APPLIED, "r1", 2f, responseReceived = true)
        val temp = ClimateMember(ClimateProp.TEMP, DRIVER, 22f, 22.5f, MemberStage.APPLIED, "r2", 22.5f, responseReceived = true)
        store.groups = listOf(ClimateGroup("g", FRONT, FIVE, FRONT, false, mapOf("POWER" to true, "AUTO" to false, "DUAL" to false), GroupStage.ACTIVE, listOf(fan, temp)))
        port.values[ClimateProp.FAN to FRONT] = 2f
        port.values[ClimateProp.TEMP to DRIVER] = 22.5f
        val block = "개발 진단 작업이나 기록이 남아 있어 되돌릴 수 없어요"
        debug = "밝기 검증 작업이 진행 중이에요" // the new Activity's first debug read, still answering
        assertEquals(block, climate.state.restoreBlock)
        val calls = port.calls
        val saves = store.saves

        debug = null // the read answered
        climate.onDebugChanged()
        assertNull(climate.state.restoreBlock)
        debug = "제어 검증 기록이 남아 있어요"
        climate.onDebugChanged()
        assertEquals(block, climate.state.restoreBlock)
        climate.restoreRecords() // the press is checked again
        debug = null
        climate.onDebugChanged()
        scheduler.advance(60_000)
        assertNull(climate.state.restoreBlock)
        assertEquals(calls, port.calls) // no read, no write, no automatic restore
        assertEquals(saves, store.saves)
        assertEquals(GroupStage.ACTIVE, group().stage)

        climate.restoreRecords()
        assertEquals(22f, port.writes[0].value)
        answer(port.writes[0])
        assertEquals(3f, port.writes[1].value)
        answer(port.writes[1])
        assertEquals(2, port.writes.size)
        assertTrue(store.groups.isEmpty())
    }

    @Test
    fun `a group that never answered in an earlier run stays locked, and debug work blocks apply and restore`() {
        val m = ClimateMember(ClimateProp.FAN, FRONT, 3f, 2f, MemberStage.APPLY_REQUESTED, "r", 2f, responseReceived = false)
        store.groups = listOf(ClimateGroup("g", FRONT, FIVE, FRONT, false, mapOf("POWER" to true, "AUTO" to false, "DUAL" to false), GroupStage.ACTIVE, listOf(m)))
        climate.restoreRecords()
        climate.discardRecords()
        climate.onRestStart(both)
        assertTrue(group().priorRequestUnresolved)
        assertTrue(port.writes.isEmpty())

        debug = "제어 검증 기록이 남아 있어요"
        val other = owner(MemoryStore())
        other.onRestStart(both)
        assertTrue(port.writes.isEmpty())
        assertTrue(other.state.restLine!!.contains("개발 진단"))
    }

    @Test
    fun `onFailed after the value changed is not applied, and a late check read after the end overwrites nothing`() {
        val run = climate.onRestStart(both)
        answer(port.writes[0], ok = false)
        assertEquals(MemberStage.RESULT_UNKNOWN, group().members.single { it.prop == ClimateProp.FAN }.stage)
        answer(port.writes[1])
        port.holdValueReads = true
        port.values[ClimateProp.TEMP to DRIVER] = 25f
        port.listeners.getValue(ClimateProp.TEMP to DRIVER)(25f) // a check read waits
        climate.onRestEnd(run, "USER")
        port.holdValueReads = false
        port.values[ClimateProp.TEMP to DRIVER] = 22.5f
        repeat(4) { port.release() }
        assertTrue(log.any { it.contains("확인 조회 결과 무시") })
        assertEquals(DRIVER, port.writes[2].area)
    }

    @Test
    fun `the codec rejects a non-driver temperature, a Sync area without the driver, non-finite numbers and duplicates`() {
        val fan = ClimateMember(ClimateProp.FAN, FRONT, 3f, 2f, MemberStage.APPLIED, "a", 2f, true)
        val temp = ClimateMember(ClimateProp.TEMP, DRIVER, 22f, 22.5f, MemberStage.APPLIED, "b", 22.5f, true)
        val good = ClimateGroup("g", FRONT, FIVE, FRONT, false, mapOf("POWER" to true, "AUTO" to false, "DUAL" to false), GroupStage.ACTIVE, listOf(fan, temp))
        assertEquals(ClimateLoad.Found(listOf(good)), ClimateCodec.decode(ClimateCodec.encode(listOf(good))))
        listOf(
            good.copy(members = listOf(fan, temp.copy(area = PASSENGER))),
            good.copy(dualArea = PASSENGER),
            good.copy(members = listOf(fan.copy(target = 3f))),
            good.copy(members = listOf(fan, temp.copy(requestId = "a"))),
        ).forEach { assertTrue(it.toString(), ClimateCodec.decode(ClimateCodec.encode(listOf(it))) is ClimateLoad.Unreadable) }
        val huge = ClimateCodec.encode(listOf(good)).replace("\"target\": 22.5", "\"target\": 1e100")
        assertTrue(huge.contains("1e100"))
        assertTrue(ClimateCodec.decode(huge) is ClimateLoad.Unreadable)
    }

    @Test
    fun `one auto area with front and rear power areas - the rear fan is not merged and the written record reads back`() {
        port.caps[ClimateProp.FAN] = Triple(Access.READ_WRITE, mapOf(FRONT to 1f..8f, REAR to 1f..8f), null)
        port.sides[Side.AUTO] = Access.READ_WRITE to listOf(FIVE)
        port.sides[Side.POWER] = Access.READ_WRITE to listOf(FRONT, REAR)
        port.sides[Side.DUAL] = Access.READ_WRITE to listOf(FIVE)
        port.sideValues[Side.AUTO to FIVE] = false
        port.sideValues[Side.POWER to FRONT] = true
        port.sideValues[Side.POWER to REAR] = true
        port.sideValues[Side.DUAL to FIVE] = false
        port.values[ClimateProp.FAN to REAR] = 4f
        climate.onRestStart(both.copy(temp = false))
        answer(port.writes.single())
        assertEquals(1, port.writes.size) // no rear write
        assertTrue(climate.state.items.any { it.area == REAR && it.text.contains("영역이 달라") })
        assertTrue(ClimateCodec.decode(ClimateCodec.encode(store.groups)) is ClimateLoad.Found)
    }

    @Test
    fun `Sync turned on after the last restore write keeps the record with the final readings`() {
        val run = climate.onRestStart(both)
        answer(port.writes[0])
        answer(port.writes[1])
        climate.onRestEnd(run, "USER")
        answer(port.writes[2]) // temperature 22
        answer(port.writes[3], sideEffect = {}) // fan 3
        // (the automatic restore finished above; now the same with Sync moving right at the end)
        assertTrue(store.groups.isEmpty())

        val other = MemoryStore()
        val second = owner(other)
        val r2 = second.onRestStart(both)
        answer(port.writes[4])
        answer(port.writes[5])
        second.onRestEnd(r2, "USER")
        answer(port.writes[6])
        port.values[ClimateProp.FAN to FRONT] = 3f
        port.writes[7].ok(3f)
        port.sideValues[Side.DUAL to FRONT] = true // Sync moves after the confirm read, before the final re-read
        scheduler.advance(CLIMATE_SETTLE_MS)
        val kept = other.groups.single()
        assertEquals(3f, kept.members.single { it.prop == ClimateProp.FAN }.lastRead)
        assertNotEquals(GroupStage.ACTIVE, kept.stage)
    }
}
