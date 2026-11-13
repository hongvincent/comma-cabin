package app.commacabin.lighting

import app.commacabin.probe.Access
import app.commacabin.probe.FakeScheduler
import app.commacabin.probe.GearState
import app.commacabin.probe.ParkingState
import app.commacabin.probe.ProbeError
import app.commacabin.probe.RecordLease
import app.commacabin.probe.SwitchState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import app.commacabin.probe.Dispatch

private const val FIVE = "ROW_1_LEFT+ROW_1_RIGHT+ROW_2_CENTER+ROW_2_LEFT+ROW_2_RIGHT"
private const val FRONT = "ROW_1_LEFT+ROW_1_RIGHT"
private const val REAR = "ROW_2_CENTER+ROW_2_LEFT+ROW_2_RIGHT"
private const val WHITE_READ = 0x00FFFFFF
private val WARM = LightValues.WARM_COLOR

private class Write(val prop: LightProp, val area: String, val value: Int, val ok: (Int?) -> Unit, val err: (ProbeError) -> Unit)

/** Writes wait for the test. [holdValueReads]/[holdAll] make reads wait in [held]. */
private class FakePort : LightingPort {
    var bright: Pair<Access, Map<String, IntRange>>? = Access.READ_WRITE to mapOf(FIVE to 0..100)
    var color: Pair<Access, List<String>>? = Access.READ_WRITE to listOf(FIVE)
    var switchAreas: Pair<Access, List<String>>? = Access.READ_WRITE to listOf(FIVE)
    val switches = mutableMapOf(FIVE to SwitchState.ON)
    val values = mutableMapOf((LightProp.BRIGHTNESS to FIVE) to 70, (LightProp.COLOR to FIVE) to WHITE_READ)
    val writes = mutableListOf<Write>()
    val listeners = mutableMapOf<Pair<LightProp, String>, (Int?) -> Unit>()
    /** Properties whose listener registration fails (reported, nothing registered). */
    val failRegister = mutableSetOf<LightProp>()
    /** Each registered listener's status callback: a later error means the listener failed. */
    val listening = mutableMapOf<Pair<LightProp, String>, (ProbeError?) -> Unit>()
    /** When true, a write waits in [queued] (the SDK lane) until [runQueued]; its gate runs then. */
    var holdDispatch = false
    val queued = mutableListOf<() -> Unit>()
    val switchReads = mutableListOf<String>()
    var calls = 0
    var holdAll = false
    var holdValueReads = false
    /** Value reads of these properties fail with an error. */
    val failReads = mutableSetOf<LightProp>()
    val held = mutableListOf<() -> Unit>()

    private fun answer(value: Boolean = false, a: () -> Unit) {
        calls++
        if (holdAll || (value && holdValueReads)) held += a else a()
    }

    fun release() = held.toList().also { held.clear() }.forEach { it() }

    fun runQueued() = queued.toList().also { queued.clear() }.forEach { it() }

    override fun brightnessCapability(ok: (Access, Map<String, IntRange>) -> Unit, err: (ProbeError) -> Unit) =
        answer { bright?.let { ok(it.first, it.second) } ?: err(ProbeError("X", "no", null)) }

    override fun colorCapability(ok: (Access, List<String>) -> Unit, err: (ProbeError) -> Unit) =
        answer { color?.let { ok(it.first, it.second) } ?: err(ProbeError("X", "no", null)) }

    override fun switchCapability(ok: (Access, List<String>) -> Unit, err: (ProbeError) -> Unit) =
        answer { switchAreas?.let { ok(it.first, it.second) } ?: err(ProbeError("X", "no", null)) }

    override fun readSwitch(area: String, ok: (SwitchState?) -> Unit, err: (ProbeError) -> Unit) = answer {
        switchReads += area
        ok(switches[area])
    }

    override fun read(prop: LightProp, area: String, ok: (Int?) -> Unit, err: (ProbeError) -> Unit) = answer(value = true) {
        if (prop in failReads) err(ProbeError("VehicleException", "read failed", null)) else ok(values[prop to area])
    }

    override fun write(prop: LightProp, area: String, value: Int, dispatch: Dispatch, ok: (Int?) -> Unit, err: (ProbeError) -> Unit) {
        val run = {
            if (dispatch.now()) {
                calls++
                writes += Write(prop, area, value, ok, err)
            }
        }
        if (holdDispatch) queued += run else run()
    }

    override fun observe(prop: LightProp, area: String, onListening: (ProbeError?) -> Unit, onUpdate: (Int?) -> Unit): AutoCloseable {
        if (prop in failRegister) {
            onListening(ProbeError("SecurityException", "register denied", null))
            return AutoCloseable {}
        }
        listeners[prop to area] = onUpdate
        listening[prop to area] = onListening
        onListening(null)
        return AutoCloseable { listeners.remove(prop to area) }
    }
}

private class MemoryStore(var records: List<LightRecord> = emptyList()) : LightStore {
    var failSaves = false
    var saves = 0
    override fun load(): LightLoad = if (records.isEmpty()) LightLoad.Empty else LightLoad.Found(records)
    override fun save(records: List<LightRecord>): Boolean {
        if (failSaves) return false
        this.records = records
        saves++
        return true
    }
}

class RestLightingTest {
    private val port = FakePort()
    private val store = MemoryStore()
    private val scheduler = FakeScheduler()
    private var parking = ParkingState(GearState.PARK, false)
    private var debug: String? = null
    private val lease = RecordLease()
    private val log = mutableListOf<String>()
    private var ids = 0
    private val lighting by lazy { owner() }

    private fun owner(s: LightStore = store) =
        RestLighting(port, s, scheduler, { parking }, { debug }, lease, { log += it }, newId = { "id-${++ids}" }).also { it.start() }

    /** The vehicle takes [value] (a color reads back with alpha 0x00), then the terminal callback and the read run. */
    private fun answer(w: Write, value: Int? = w.value, echo: Int? = w.value) {
        if (value != null) port.values[w.prop to w.area] = if (w.prop == LightProp.COLOR) value and 0xFFFFFF else value
        w.ok(echo)
        scheduler.advance(LIGHT_SETTLE_MS)
    }

    private fun record(prop: LightProp, area: String = FIVE) = store.records.single { it.prop == prop && it.area == area }

    private fun setParking(p: ParkingState) {
        parking = p
        lighting.onParking(p)
    }

    @Test
    fun `choice off reads and writes nothing, a switch that is off writes nothing and says why`() {
        lighting.onRestStart(false)
        assertEquals(0, port.calls)
        assertEquals("무드등 조정을 꺼 두었어요", lighting.state.restLine)

        port.switches[FIVE] = SwitchState.OFF
        lighting.onRestStart(true)
        assertTrue(port.writes.isEmpty())
        assertEquals(0, store.saves)
        assertTrue(lighting.state.items.all { it.result == LightResult.SKIPPED && it.text.contains("꺼져 있어") })
        assertEquals("무드등이 꺼져 있어 조정하지 않았어요", lighting.state.restLine)
    }

    @Test
    fun `every area is applied record-first and restored with alpha FF payloads, judged by RGB on the read`() {
        port.bright = Access.READ_WRITE to mapOf(FRONT to 0..100, REAR to 0..100)
        port.values[LightProp.BRIGHTNESS to FRONT] = 70
        port.values[LightProp.BRIGHTNESS to REAR] = 60
        val run = lighting.onRestStart(true)

        val first = port.writes.single()
        assertEquals(30, first.value)
        assertFalse(record(LightProp.BRIGHTNESS, FRONT).responseReceived) // saved before the command
        answer(first)
        answer(port.writes[1])
        val warm = port.writes[2]
        assertEquals(WARM, warm.value)
        answer(warm) // the vehicle reads back 0x00FF9A40
        assertEquals(LightStage.APPLY_CONFIRMED, record(LightProp.COLOR).stage)
        assertEquals("무드등을 낮추고 따뜻한 색으로 맞췄어요", lighting.state.restLine)

        lighting.onRestEnd(run, "USER")
        answer(port.writes[3])
        answer(port.writes[4])
        val back = port.writes[5]
        assertEquals(0xFFFFFFFF.toInt(), back.value)
        answer(back) // reads 0x00FFFFFF
        assertEquals(listOf(70, 60), port.writes.subList(3, 5).map { it.value })
        assertTrue(store.records.isEmpty())
        assertTrue(lighting.state.items.all { it.result == LightResult.RESTORED })
    }

    @Test
    fun `the brightness target is 30 within the range and never at or above the current value`() {
        port.bright = Access.READ_WRITE to mapOf(FIVE to 40..100)
        lighting.onRestStart(true)
        assertEquals(40, port.writes.single().value)

        val other = MemoryStore()
        port.bright = Access.READ_WRITE to mapOf(FIVE to 0..100)
        port.values[LightProp.BRIGHTNESS to FIVE] = 30
        port.color = Access.READ_WRITE to emptyList()
        port.writes.clear()
        val second = owner(other)
        second.onRestStart(true)
        assertTrue(port.writes.isEmpty())
        assertTrue(second.state.items.any { it.text.contains("이미 충분히 어두워") })
        assertTrue(second.state.items.any { it.text.contains("색 조정을 지원하지 않아") })
    }

    @Test
    fun `an area is mapped to the one switch area containing it, spanning or overlapping switch areas are skipped`() {
        port.switchAreas = Access.READ_WRITE to listOf(FRONT, REAR)
        port.switches[FRONT] = SwitchState.ON
        port.switches[REAR] = SwitchState.ON
        port.color = Access.READ_WRITE to listOf(FRONT)
        port.values[LightProp.COLOR to FRONT] = WHITE_READ
        lighting.onRestStart(true)

        val w = port.writes.single()
        assertEquals(LightProp.COLOR, w.prop)
        assertEquals(listOf(FRONT), port.switchReads)
        assertTrue(lighting.state.items.first { it.prop == LightProp.BRIGHTNESS }.text.contains("스위치 영역"))

        port.switchAreas = Access.READ_WRITE to listOf(FIVE, FRONT)
        port.writes.clear()
        val second = owner(MemoryStore())
        second.onRestStart(true)
        assertTrue(port.writes.single().prop == LightProp.BRIGHTNESS) // FIVE is in exactly one switch area
        answer(port.writes.single())
        assertTrue(second.state.items.first { it.prop == LightProp.COLOR }.text.contains("스위치 영역")) // FRONT is in two
    }

    @Test
    fun `a different RGB is not applied and an alpha other than 00 or FF is never written`() {
        port.color = Access.READ_WRITE to listOf(FIVE)
        port.bright = Access.READ_WRITE to emptyMap()
        lighting.onRestStart(true)
        answer(port.writes.single(), value = 0x00FF9A41)
        assertEquals(LightStage.RESULT_UNKNOWN, record(LightProp.COLOR).stage)

        port.values[LightProp.COLOR to FIVE] = 0x80FFFFFF.toInt()
        port.writes.clear()
        val second = owner(MemoryStore())
        second.onRestStart(true)
        assertTrue(port.writes.isEmpty())
        assertTrue(second.state.items.any { it.text.contains("해석할 수 없어") })
        assertFalse(LightValues.same(LightProp.COLOR, 0x80FF9A40.toInt(), WARM))
    }

    @Test
    fun `a failing save sends nothing and then blocks adding or removing records`() {
        store.failSaves = true
        lighting.onRestStart(true)
        assertTrue(port.writes.isEmpty())
        assertNotNull(lighting.state.problem)

        store.failSaves = false
        lighting.onRestStart(true)
        assertTrue(port.writes.isEmpty())
        assertTrue(lighting.state.restLine!!.contains("저장할 수 없어"))
    }

    @Test
    fun `a timeout keeps the request open and its record locked, the late answer is read and then restored after the end`() {
        val run = lighting.onRestStart(true)
        val slow = port.writes.single()
        scheduler.advance(LIGHT_WRITE_TIMEOUT_MS)
        assertFalse(record(LightProp.BRIGHTNESS).responseReceived) // the vehicle still reads 70 = original: still locked
        answer(port.writes[1]) // the next item went on

        lighting.discardRecords()
        lighting.onRestEnd(run, "USER")
        answer(port.writes[2]) // color restore
        assertEquals(3, port.writes.size) // no restore for the open brightness request
        assertFalse(record(LightProp.BRIGHTNESS).responseReceived)

        answer(slow) // late onComplete, read 30
        val back = port.writes[3]
        assertEquals(70, back.value)
        answer(back)
        assertTrue(store.records.isEmpty())
    }

    @Test
    fun `ending while a preparation read waits cancels it and its late replies apply nothing`() {
        port.holdAll = true
        val run = lighting.onRestStart(true)
        lighting.onRestEnd(run, "USER")
        repeat(5) { port.release() }
        assertTrue(port.writes.isEmpty())
        assertEquals(0, store.saves)
    }

    @Test
    fun `user changes stay - the listener after the first value and echo, and the read right before the restore`() {
        val run = lighting.onRestStart(true)
        answer(port.writes[0])
        answer(port.writes[1])
        val brightness = port.listeners.getValue(LightProp.BRIGHTNESS to FIVE)
        brightness(30) // first value or echo = the app's value
        assertEquals(LightStage.APPLY_CONFIRMED, record(LightProp.BRIGHTNESS).stage)
        port.values[LightProp.BRIGHTNESS to FIVE] = 50 // the user really changed it
        brightness(50)
        assertEquals(LightStage.USER_CHANGED, record(LightProp.BRIGHTNESS).stage)

        port.values[LightProp.COLOR to FIVE] = 0x0000FF00 // changed without a listener event
        lighting.onRestEnd(run, "USER")
        assertEquals(2, port.writes.size)
        assertEquals(LightStage.USER_CHANGED, record(LightProp.COLOR).stage)
        assertTrue(lighting.state.items.all { it.result == LightResult.USER_CHANGED })
    }

    @Test
    fun `losing parking holds - a late answer after P is back sends no restore until an explicit restore`() {
        val run = lighting.onRestStart(true)
        val sent = port.writes.single()
        setParking(ParkingState(GearState.NOT_PARK, false))
        lighting.onRestEnd(run, "LEFT_PARK", parkingLost = true)
        setParking(ParkingState(GearState.PARK, false))

        answer(sent) // late onComplete, reads the target 30
        assertEquals(LightStage.RESTORE_HELD, record(LightProp.BRIGHTNESS).stage)
        assertEquals(1, port.writes.size) // no color apply (cancelled) and no automatic restore

        lighting.restoreRecords()
        val back = port.writes[1]
        assertEquals(70, back.value)
        answer(back)
        assertTrue(store.records.isEmpty())
    }

    @Test
    fun `an end from an older session changes nothing and earlier jobs never touch the new rest's items`() {
        val first = lighting.onRestStart(true)
        answer(port.writes[0])
        answer(port.writes[1])
        lighting.onRestEnd(first, "USER")
        val oldRestore = port.writes[2] // brightness restore of the first rest, still open

        val second = lighting.onRestStart(true)
        val itemsOfSecond = lighting.state.items
        assertTrue(itemsOfSecond.all { it.result == LightResult.SKIPPED && it.text.contains("이전 기록") })
        lighting.onRestEnd(first, "APP_LEFT") // late end of the old session
        answer(oldRestore)

        assertEquals(itemsOfSecond, lighting.state.items)
        assertEquals(itemsOfSecond.first().text, lighting.state.restLine)
        assertTrue(lighting.state.earlier.any { it.result == LightResult.RESTORED })
        assertTrue(second > first)
    }

    @Test
    fun `a replaced owner never changes the file or the vehicle`() {
        lighting.onRestStart(true)
        val sent = port.writes.single()
        val newer = owner() // marks the unanswered record as an earlier unresolved one
        val savesBefore = store.saves
        answer(sent)
        scheduler.advance(LIGHT_WRITE_TIMEOUT_MS + LIGHT_READ_TIMEOUT_MS)
        assertEquals(savesBefore, store.saves)
        assertEquals(1, port.writes.size)
        assertNull(lighting.busyReason())
        assertNotNull(newer.busyReason()) // the new owner sees the record (locked: it never answered for it)
    }

    @Test
    fun `a record that never answered in an earlier run stays locked - no restore, no delete, not reapplied`() {
        store.records = listOf(LightRecord("job", LightProp.BRIGHTNESS, FIVE, 70, 30, LightStage.APPLY_REQUESTED, "req", 30, responseReceived = false))
        lighting.restoreRecords()
        lighting.discardRecords()
        lighting.onRestStart(true)

        assertTrue(record(LightProp.BRIGHTNESS).priorRequestUnresolved)
        assertEquals(LightResult.LOCKED, lighting.state.open.single { it.prop == LightProp.BRIGHTNESS }.result)
        assertTrue(port.writes.none { it.prop == LightProp.BRIGHTNESS })
        assertTrue(lighting.state.items.first { it.prop == LightProp.BRIGHTNESS }.text.contains("이전 기록"))
    }

    @Test
    fun `a debug change only redraws the restore block - on after the first debug read of the next run, off with debug work or records, no vehicle call`() {
        store.records = listOf(LightRecord("job", LightProp.BRIGHTNESS, FIVE, 70, 30, LightStage.APPLY_CONFIRMED, "req", 30, responseReceived = true))
        port.values[LightProp.BRIGHTNESS to FIVE] = 30
        val block = "개발 진단 작업이나 기록이 남아 있어 되돌릴 수 없어요"
        debug = "밝기 검증 작업이 진행 중이에요" // the new Activity's first debug read, still answering
        assertEquals(block, lighting.state.restoreBlock)
        val calls = port.calls
        val saves = store.saves

        debug = null // the read answered
        lighting.onDebugChanged()
        assertNull(lighting.state.restoreBlock)
        debug = "제어 검증 작업이 진행 중이에요"
        lighting.onDebugChanged()
        assertEquals(block, lighting.state.restoreBlock)
        lighting.restoreRecords() // the press is checked again
        debug = null
        lighting.onDebugChanged()
        scheduler.advance(60_000)
        assertNull(lighting.state.restoreBlock)
        assertEquals(calls, port.calls) // no read, no write, no automatic restore
        assertEquals(saves, store.saves)
        assertEquals(LightStage.APPLY_CONFIRMED, record(LightProp.BRIGHTNESS).stage)

        lighting.restoreRecords()
        assertEquals(70, port.writes.single().value)
        answer(port.writes.single())
        assertTrue(store.records.isEmpty())
    }

    @Test
    fun `a read-only check shows the vehicle's brightness and color - no command, no record change, an unknown color format is not compared`() {
        lighting.checkRecords(this)
        assertEquals(0, port.calls) // no records, no read

        store.records = listOf(
            LightRecord("jb", LightProp.BRIGHTNESS, FIVE, 70, 30, LightStage.APPLY_CONFIRMED, "rb", 30, responseReceived = true),
            LightRecord("jc", LightProp.COLOR, FIVE, WHITE_READ, WARM, LightStage.APPLY_CONFIRMED, "rc", WARM, responseReceived = true),
        )
        port.values[LightProp.BRIGHTNESS to FIVE] = 50 // changed in the vehicle settings while the app was off
        port.values[LightProp.COLOR to FIVE] = WARM and 0xFFFFFF
        val next = owner()
        val before = store.records
        val saves = store.saves
        next.checkRecords(this)
        scheduler.advance(60_000)
        assertTrue(port.writes.isEmpty())
        assertEquals(saves, store.saves)
        assertEquals(before, store.records)
        assertTrue(next.state.open.single { it.prop == LightProp.BRIGHTNESS }.checked!!.run { contains("50") && contains("원래와 달라요") })
        assertTrue(next.state.open.single { it.prop == LightProp.COLOR }.checked!!.contains("휴식 목표 색과 같아요"))

        port.values[LightProp.COLOR to FIVE] = 0x12FF9A40 // an alpha the color contract cannot read
        next.checkRecords(this)
        assertEquals("색을 읽었지만 비교할 수 없는 값이에요", next.state.open.single { it.prop == LightProp.COLOR }.checked)
    }

    @Test
    fun `an older screen stopping late keeps the newer check, a late answer after a new rest is dropped, a locked record stays locked`() {
        store.records = listOf(LightRecord("job", LightProp.BRIGHTNESS, FIVE, 70, 30, LightStage.APPLY_REQUESTED, "req", 30, responseReceived = false))
        port.values[LightProp.BRIGHTNESS to FIVE] = 70 // reads the original, yet the answer never came
        val older = Any()
        port.holdValueReads = true
        lighting.checkRecords(older)
        lighting.checkRecords(this)
        lighting.stopCheck(older)
        port.holdValueReads = false
        port.release()
        assertFalse(lighting.state.checking)
        assertTrue(lighting.state.open.single().checked!!.contains("원래와 같아요"))
        assertNotNull(lighting.state.restoreBlock)
        assertNotNull(lighting.state.discardBlock)
        assertTrue(record(LightProp.BRIGHTNESS).priorRequestUnresolved)

        port.holdValueReads = true
        lighting.checkRecords(this)
        port.holdValueReads = false
        lighting.onRestStart(true) // the locked record blocks it; the check is dropped
        port.release()
        assertFalse(lighting.state.checking)
        assertTrue(lighting.state.open.all { it.checked == null })
        assertTrue(port.writes.none { it.prop == LightProp.BRIGHTNESS })
    }

    @Test
    fun `debug work or records block applying and an explicit restore`() {
        debug = "밝기 검증 기록이 남아 있어요"
        lighting.onRestStart(true)
        assertTrue(port.writes.isEmpty())
        assertTrue(lighting.state.restLine!!.contains("개발 진단"))

        store.records = listOf(LightRecord("job", LightProp.BRIGHTNESS, FIVE, 70, 30, LightStage.APPLY_CONFIRMED, "req", 30, responseReceived = true))
        val other = owner()
        other.restoreRecords()
        assertTrue(port.writes.isEmpty())
        assertTrue(other.state.restoreBlock!!.contains("개발 진단"))
    }

    @Test
    fun `the switch turned off while the current value read waits means no command`() {
        port.holdValueReads = true
        lighting.onRestStart(true)
        port.switches[FIVE] = SwitchState.OFF
        repeat(3) { port.release() }
        assertTrue(port.writes.isEmpty())
        assertEquals(0, store.saves)
    }

    @Test
    fun `a restore checks support, range and the switch again, and never turns the light back on`() {
        store.records = listOf(LightRecord("job", LightProp.BRIGHTNESS, FIVE, 70, 30, LightStage.RESTORE_HELD, "req", 30, responseReceived = true))
        port.values[LightProp.BRIGHTNESS to FIVE] = 30
        port.bright = Access.READ_WRITE to mapOf(FIVE to 0..50)
        lighting.restoreRecords()
        assertTrue(port.writes.isEmpty())
        assertTrue(lighting.state.earlier.single().text.contains("범위 밖"))

        port.bright = Access.READ to mapOf(FIVE to 0..100)
        lighting.restoreRecords()
        assertTrue(port.writes.isEmpty())

        port.bright = Access.READ_WRITE to mapOf(FIVE to 0..100)
        port.switches[FIVE] = SwitchState.OFF
        lighting.restoreRecords()
        assertTrue(port.writes.isEmpty())
        assertTrue(lighting.state.earlier.single().text.contains("꺼져 있어"))
        assertEquals(1, store.records.size)
    }

    @Test
    fun `a storage fault on the restore request sends nothing and keeps the record`() {
        val run = lighting.onRestStart(true)
        answer(port.writes[0])
        answer(port.writes[1])
        store.failSaves = true
        lighting.onRestEnd(run, "USER")
        assertEquals(2, port.writes.size)
        assertEquals(2, store.records.size)
        assertNotNull(lighting.state.problem)
        assertNotNull(lighting.state.restoreBlock)
    }

    @Test
    fun `the codec keeps typed values and rejects broken records`() {
        val good = LightRecord("j", LightProp.COLOR, FIVE, WHITE_READ, WARM, LightStage.RESTORE_REQUESTED, "r", 0xFFFFFFFF.toInt(), responseReceived = false, lastRead = 0x00FF9A40)
        assertEquals(LightLoad.Found(listOf(good)), LightCodec.decode(LightCodec.encode(listOf(good))))
        val dim = good.copy(jobId = "k", requestId = "s", prop = LightProp.BRIGHTNESS, original = 70, target = 30, payload = 30, lastRead = null)
        listOf(
            listOf(good.copy(target = 0x00FF9A40)),
            listOf(good.copy(payload = 0x00FFFFFF)),
            listOf(good.copy(original = 0x80FFFFFF.toInt())),
            listOf(dim.copy(target = 80)),
            listOf(good, dim.copy(jobId = "j")),
            listOf(good, dim.copy(requestId = "r")),
            listOf(good, good.copy(jobId = "x", requestId = "y")),
        ).forEach { assertTrue(it.toString(), LightCodec.decode(LightCodec.encode(it)) is LightLoad.Unreadable) }
        assertTrue(LightCodec.decode("""{"formatVersion":2,"records":[]}""") is LightLoad.Unreadable)
    }

    @Test
    fun `later folds the records with no read or write, and showing them again only unfolds`() {
        store.records = listOf(LightRecord("job", LightProp.BRIGHTNESS, FIVE, 70, 30, LightStage.RESTORE_HELD, "req", 30, responseReceived = true))
        val calls = port.calls.also { lighting.state }
        val saves = store.saves
        lighting.defer()
        assertTrue(lighting.state.deferred)
        lighting.showRecords()
        assertFalse(lighting.state.deferred)
        assertEquals(1, lighting.state.open.size)
        assertEquals(calls, port.calls)
        assertEquals(saves, store.saves)
        assertTrue(port.writes.isEmpty())
    }

    @Test
    fun `onFailed after the value changed or a failed re-read is neither applied nor unchanged, keeps the record and lets the other property go on`() {
        val run = lighting.onRestStart(true)
        val dim = port.writes.single()
        port.values[LightProp.BRIGHTNESS to FIVE] = 30 // the vehicle took it...
        dim.err(ProbeError("VehicleException", "set failed", null)) // ...but the terminal callback is onFailed
        scheduler.advance(LIGHT_SETTLE_MS)
        assertEquals(LightStage.RESULT_UNKNOWN, record(LightProp.BRIGHTNESS).stage)
        assertTrue(record(LightProp.BRIGHTNESS).responseReceived)

        val warm = port.writes[1] // the color still goes on
        port.failReads += LightProp.COLOR
        answer(warm) // onComplete, but the re-read fails
        assertEquals(LightStage.RESULT_UNKNOWN, record(LightProp.COLOR).stage)
        assertEquals("무드등 조정 결과를 확인하지 못했어요", lighting.state.restLine)

        lighting.onRestEnd(run, "USER")
        val back = port.writes[2]
        assertEquals(70, back.value) // brightness reads the app's 30: restored by the normal rules
        answer(back)
        assertEquals(3, port.writes.size) // color: the current value cannot be read, so nothing is sent
        assertEquals(LightStage.RESTORE_HELD, record(LightProp.COLOR).stage)
        assertTrue(store.records.none { it.prop == LightProp.BRIGHTNESS })
        assertEquals("현재 값을 읽지 못해 되돌리지 않았어요", lighting.state.items.single { it.prop == LightProp.COLOR }.text) // the SDK error stays in the log
        assertTrue(log.any { it.contains("read failed") })

        port.failReads.clear()
        lighting.restoreRecords()
        answer(port.writes[3])
        assertTrue(store.records.isEmpty())
    }

    @Test
    fun `an old cached listener value is checked by a real read - stale 70 with an actual 30 is not a user change`() {
        val run = lighting.onRestStart(true)
        answer(port.writes[0])
        answer(port.writes[1])
        port.listeners.getValue(LightProp.BRIGHTNESS to FIVE)(70) // replayed cache on registration; the vehicle reads 30
        port.listeners.getValue(LightProp.COLOR to FIVE)(WHITE_READ) // the same for the color; the vehicle reads warm
        assertEquals(LightStage.APPLY_CONFIRMED, record(LightProp.BRIGHTNESS).stage)
        assertEquals(LightStage.APPLY_CONFIRMED, record(LightProp.COLOR).stage)

        lighting.onRestEnd(run, "USER")
        assertEquals(70, port.writes[2].value)
        answer(port.writes[2])
        assertEquals(0xFFFFFFFF.toInt(), port.writes[3].value)
        answer(port.writes[3])
        assertTrue(store.records.isEmpty())
    }

    @Test
    fun `a differing value that the real read confirms is kept as the user's, for brightness and color`() {
        val run = lighting.onRestStart(true)
        answer(port.writes[0])
        answer(port.writes[1])
        port.values[LightProp.BRIGHTNESS to FIVE] = 50
        port.listeners.getValue(LightProp.BRIGHTNESS to FIVE)(50)
        port.values[LightProp.COLOR to FIVE] = 0x0000FF00
        port.listeners.getValue(LightProp.COLOR to FIVE)(0x0000FF00)
        assertEquals(50, record(LightProp.BRIGHTNESS).lastRead)
        assertEquals(LightStage.USER_CHANGED, record(LightProp.COLOR).stage)

        lighting.onRestEnd(run, "USER")
        assertEquals(2, port.writes.size)
        assertTrue(store.records.all { it.stage == LightStage.USER_CHANGED })
    }

    @Test
    fun `a check read answering after the end, a restore or an owner change overwrites nothing`() {
        val run = lighting.onRestStart(true)
        answer(port.writes[0])
        answer(port.writes[1])
        port.holdValueReads = true
        port.values[LightProp.BRIGHTNESS to FIVE] = 50
        port.listeners.getValue(LightProp.BRIGHTNESS to FIVE)(50) // check read waits
        lighting.onRestEnd(run, "USER") // the restore starts and its own current read waits too
        port.holdValueReads = false
        port.values[LightProp.BRIGHTNESS to FIVE] = 30
        port.release() // check read (dropped: restoring) and the restore's read (30 = the app's value)
        assertTrue(log.any { it.contains("확인 조회 결과 무시") })
        val back = port.writes[2]
        assertEquals(70, back.value)
        answer(back)
        assertTrue(store.records.none { it.prop == LightProp.BRIGHTNESS })

        // Another owner takes over while a check read waits: the old owner's answer changes nothing.
        val other = MemoryStore()
        val first = owner(other)
        val run2 = first.onRestStart(true)
        answer(port.writes.last())
        answer(port.writes.last())
        port.holdValueReads = true
        port.values[LightProp.BRIGHTNESS to FIVE] = 50
        port.listeners.getValue(LightProp.BRIGHTNESS to FIVE)(50)
        val saves = other.saves
        owner(MemoryStore())
        port.release()
        assertEquals(saves, other.saves)
        assertEquals(LightStage.APPLY_CONFIRMED, other.records.single { it.prop == LightProp.BRIGHTNESS }.stage)
        assertTrue(run2 > 0)
    }


    // --- STEP 17: commands wait in the SDK lane; the gate decides right before the SDK entry ---

    private fun item(prop: LightProp) = lighting.state.items.single { it.prop == prop }

    @Test
    fun `queue - a user change heard while a new apply waits refuses it, so nothing is written or restored over it`() {
        port.holdDispatch = true
        val run = lighting.onRestStart(true)
        // The listener was registered before the reads; the saved command waits in the lane.
        assertTrue(port.listeners.containsKey(LightProp.BRIGHTNESS to FIVE))
        assertEquals(1, port.queued.size)
        assertFalse(record(LightProp.BRIGHTNESS).responseReceived)

        port.listeners.getValue(LightProp.BRIGHTNESS to FIVE)(55) // the user turns it to 55 meanwhile
        port.runQueued()
        assertTrue(port.writes.none { it.prop == LightProp.BRIGHTNESS })
        assertTrue(store.records.none { it.prop == LightProp.BRIGHTNESS })
        assertEquals(LightResult.SKIPPED, item(LightProp.BRIGHTNESS).result)
        assertFalse(port.listeners.containsKey(LightProp.BRIGHTNESS to FIVE))

        port.holdDispatch = false
        port.runQueued() // the color goes on
        answer(port.writes.single())
        lighting.onRestEnd(run, "USER")
        assertTrue(port.writes.none { it.prop == LightProp.BRIGHTNESS }) // the user's 55 is never written over
        assertEquals(listOf(LightProp.COLOR, LightProp.COLOR), port.writes.map { it.prop })
    }

    @Test
    fun `queue - a listener that failed to register leaves that item unapplied, the rest goes on`() {
        port.failRegister += LightProp.BRIGHTNESS
        lighting.onRestStart(true)
        assertTrue(store.records.none { it.prop == LightProp.BRIGHTNESS })
        assertEquals(LightResult.SKIPPED, item(LightProp.BRIGHTNESS).result)
        assertTrue(item(LightProp.BRIGHTNESS).text.contains("확인할 수 없어"))
        assertEquals(LightProp.COLOR, port.writes.single().prop)
    }

    @Test
    fun `queue - parking lost while the apply waits sends nothing and its record goes`() {
        port.holdDispatch = true
        lighting.onRestStart(true)
        setParking(ParkingState(GearState.NOT_PARK, false))
        port.runQueued()
        assertTrue(port.writes.isEmpty())
        assertTrue(store.records.isEmpty())
    }

    @Test
    fun `queue - the rest ending while the apply waits sends nothing, restores nothing and leaves no work`() {
        port.holdDispatch = true
        val run = lighting.onRestStart(true)
        lighting.onRestEnd(run, "USER")
        port.runQueued()
        assertTrue(port.writes.isEmpty())
        assertTrue(store.records.isEmpty())
        assertFalse(lighting.state.working)
    }

    @Test
    fun `queue - a storage failure while a command waits refuses it, and its record stays locked`() {
        lighting.onRestStart(true)
        port.holdDispatch = true
        answer(port.writes.single()) // brightness applied; the color command now waits
        assertEquals(1, port.queued.size)
        store.failSaves = true
        port.values[LightProp.BRIGHTNESS to FIVE] = 50
        port.listeners.getValue(LightProp.BRIGHTNESS to FIVE)(50) // a user change whose record update fails
        port.runQueued()
        assertEquals(1, port.writes.size)
        assertFalse(record(LightProp.COLOR).responseReceived) // never sent, and kept: not taken as answered or gone
        assertEquals(LightResult.SKIPPED, item(LightProp.COLOR).result)
    }

    @Test
    fun `queue - an old rest's command refused after a new rest started changes only the earlier results`() {
        port.holdDispatch = true
        val old = lighting.onRestStart(true)
        lighting.onRestEnd(old, "USER")
        lighting.onRestStart(false) // the new rest has the lighting off
        val shown = lighting.state.items
        port.runQueued()
        assertTrue(port.writes.isEmpty())
        assertEquals(shown, lighting.state.items)
        assertEquals("무드등 조정을 꺼 두었어요", lighting.state.restLine)
        assertTrue(lighting.state.earlier.any { it.prop == LightProp.BRIGHTNESS && it.result == LightResult.SKIPPED })
    }

    @Test
    fun `queue - a restore after a door-opened end goes on in the lane, with P and the records as its only conditions`() {
        val run = lighting.onRestStart(true)
        answer(port.writes[0])
        answer(port.writes[1])
        port.holdDispatch = true
        lighting.onRestEnd(run, "DOOR_OPENED")
        assertEquals(1, port.queued.size)
        port.runQueued()
        assertEquals(70, port.writes[2].value)
    }

    @Test
    fun `queue - a user change heard while a restore waits holds it instead of writing over it`() {
        val run = lighting.onRestStart(true)
        answer(port.writes[0])
        answer(port.writes[1])
        port.holdDispatch = true
        lighting.onRestEnd(run, "USER")
        port.listeners.getValue(LightProp.BRIGHTNESS to FIVE)(55)
        port.runQueued()
        assertEquals(2, port.writes.size)
        assertEquals(LightStage.RESTORE_HELD, record(LightProp.BRIGHTNESS).stage)
        assertTrue(record(LightProp.BRIGHTNESS).responseReceived) // back to the answered apply, for a later [원래대로]
    }

    @Test
    fun `queue - a restore of an earlier process registers the listener before its reads, and without one holds`() {
        store.records = listOf(LightRecord("job", LightProp.BRIGHTNESS, FIVE, 70, 30, LightStage.APPLY_CONFIRMED, "req", 30, responseReceived = true))
        port.values[LightProp.BRIGHTNESS to FIVE] = 30
        port.failRegister += LightProp.BRIGHTNESS
        val first = owner()
        first.restoreRecords()
        assertTrue(port.writes.isEmpty())
        assertEquals(LightStage.RESTORE_HELD, record(LightProp.BRIGHTNESS).stage)

        port.failRegister.clear()
        first.restoreRecords()
        assertTrue(port.listeners.containsKey(LightProp.BRIGHTNESS to FIVE))
        assertEquals(70, port.writes.single().value)
    }


    @Test
    fun `queue - a listener failing after it registered refuses the waiting apply, the rest goes on`() {
        port.holdDispatch = true
        lighting.onRestStart(true)
        port.listening.getValue(LightProp.BRIGHTNESS to FIVE)(ProbeError("VehicleException", "listener failed", null))
        assertFalse(port.listeners.containsKey(LightProp.BRIGHTNESS to FIVE)) // removed: it observes nothing reliable
        port.runQueued()
        assertTrue(port.writes.none { it.prop == LightProp.BRIGHTNESS })
        assertTrue(store.records.none { it.prop == LightProp.BRIGHTNESS })
        assertEquals(LightResult.SKIPPED, item(LightProp.BRIGHTNESS).result)
        port.holdDispatch = false
        port.runQueued()
        assertEquals(LightProp.COLOR, port.writes.single().prop)
    }
}
