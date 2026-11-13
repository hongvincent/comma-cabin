package app.commacabin.control

import app.commacabin.probe.Access
import app.commacabin.probe.GearState
import app.commacabin.probe.ParkingState
import app.commacabin.probe.ProbeError
import app.commacabin.rest.DoorStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakePresetPort : SeatPresetPort {
    var access = Access.WRITE
    var numbers = mapOf("ROW_1_LEFT" to 1..2)
    val selects = mutableListOf<Pair<String, Int>>()
    val failures = mutableListOf<(ProbeError) -> Unit>()
    var throwOnSelect = false
    var stops = 0

    override fun capability(ok: (Access, Map<String, IntRange>) -> Unit, err: (ProbeError) -> Unit) = ok(access, numbers)
    override fun position(axis: SeatAxis, area: String, ok: (Int?) -> Unit, err: (ProbeError) -> Unit) = ok(0)
    override fun select(area: String, number: Int, onFailed: (ProbeError) -> Unit) {
        if (throwOnSelect) throw IllegalStateException("binder gone")
        selects += area to number
        failures += onFailed
    }
    override fun stop(axis: SeatAxis, area: String, ok: () -> Unit, err: (ProbeError) -> Unit) {
        stops++
        ok()
    }
}

private class MemoryPresetStore(var jobs: List<PresetJob> = emptyList(), val unreadable: Boolean = false) : PresetStore {
    var failSaves = false
    override fun load(): List<PresetJob>? = if (unreadable) null else jobs
    override fun save(jobs: List<PresetJob>): Boolean {
        if (failSaves) return false
        this.jobs = jobs
        return true
    }
}

class SeatPresetBenchTest {
    private val port = FakePresetPort()
    private val store = MemoryPresetStore()
    private var parking = ParkingState(GearState.PARK, false)
    private var door = DoorStatus.CLOSED
    private var external: String? = null
    private var ids = 0

    private fun bench(s: PresetStore = store) =
        SeatPresetBench(port, s, { parking }, { door }, { 1_000L }, newId = { "job-${++ids}" }, externalBlock = { external }).also {
            it.start()
            it.onVisible()
            it.checkCapability()
        }

    private fun job(id: String) = store.jobs.single { it.jobId == id }

    @Test
    fun `a select goes out only when shown, parked, door closed, nothing in the way, within the capability and after the record is saved`() {
        val b = SeatPresetBench(port, store, { parking }, { door }, { 1_000L }, newId = { "job-${++ids}" }, externalBlock = { external }).also { it.start(); it.onVisible() }
        b.select(1) // capability not read yet
        b.checkCapability()
        b.select(3) // outside 1..2
        parking = ParkingState(GearState.NOT_PARK, false)
        b.select(1)
        parking = ParkingState(GearState.PARK, false)
        door = DoorStatus.OPEN
        b.select(1)
        door = DoorStatus.CLOSED
        external = "휴식 중에는 요청하지 않아요"
        b.select(1)
        external = null
        b.onHidden()
        b.select(1)
        b.onVisible()
        store.failSaves = true
        b.select(1)
        assertTrue(port.selects.isEmpty())
        assertTrue(store.jobs.isEmpty())
        assertNull(b.busyReason())

        store.failSaves = false
        b.select(2)
        assertEquals(listOf("ROW_1_LEFT" to 2), port.selects)
        assertEquals(PresetStage.REQUESTED, store.jobs.single().stage) // saved before the call
        assertNotNull(b.busyReason()) // the other seat tools and the rest start are held
        assertTrue(PresetStage.entries.none { it.label.contains("완료") || it.label.contains("성공") })
    }

    @Test
    fun `closing the observation keeps the lock, and only an operator confirmation with a basis of a closed request releases it`() {
        val b = bench()
        b.select(2)
        b.confirm("logs") // still observed
        b.closeObservation()
        assertEquals(PresetStage.CLOSED, job("job-1").stage)
        b.select(1)
        assertEquals(1, port.selects.size)
        b.confirm("  ")
        assertEquals(PresetStage.CLOSED, job("job-1").stage)

        b.confirm("VHAL select value 2 + SDK onResult true; all areas moves 0, actuator 0")
        assertEquals(PresetStage.CONFIRMED, job("job-1").stage)
        assertEquals("VHAL select value 2 + SDK onResult true; all areas moves 0, actuator 0", job("job-1").basis)
        assertNull(b.busyReason())
        assertTrue(b.state.message.startsWith("운영자 확인 #job-1")) // no stale refusal left on screen
        b.select(1)
        assertEquals(listOf("ROW_1_LEFT" to 2, "ROW_1_LEFT" to 1), port.selects)
    }

    @Test
    fun `an unconfirmed record of an earlier run locks a new screen until the operator confirms it`() {
        store.jobs = listOf(PresetJob("old", "ROW_1_LEFT", 2, emptyMap(), PresetStage.CLOSED, 1L))
        val b = bench()
        assertNotNull(b.busyReason())
        b.select(1)
        assertTrue(port.selects.isEmpty())

        b.confirm("checked outside")
        b.select(1)
        assertEquals(1, port.selects.size)
    }

    @Test
    fun `a late onFailed to a closed screen writes only its own job and keeps the newer screen's job`() {
        val old = bench()
        old.select(2)
        old.close()
        old.select(1) // a closed screen sends nothing
        old.stopAll()
        assertEquals(1, port.selects.size)
        assertEquals(0, port.stops)

        val now = bench()
        now.closeObservation()
        now.confirm("checked outside")
        now.select(1)
        val oldScreen = old.state

        port.failures[0](ProbeError("VehicleException", "late", null))
        assertEquals(ProbeError("VehicleException", "late", null).text(), job("job-1").error)
        assertEquals(PresetStage.CONFIRMED, job("job-1").stage)
        assertEquals(PresetStage.REQUESTED, job("job-2").stage)
        assertNull(job("job-2").error)
        assertEquals(oldScreen, old.state) // the closed screen is not updated
        assertEquals("job-2", now.state.unsettled?.jobId)
    }

    @Test
    fun `no write access or an unreadable record sends nothing, and a throwing select stays locked with its error`() {
        port.access = Access.READ
        bench().select(1)
        assertTrue(port.selects.isEmpty())

        port.access = Access.WRITE
        val damaged = bench(MemoryPresetStore(unreadable = true))
        damaged.select(1)
        assertTrue(port.selects.isEmpty())
        assertNotNull(damaged.busyReason())

        port.throwOnSelect = true
        val b = bench()
        b.select(1)
        assertEquals(PresetStage.REQUESTED, store.jobs.single().stage)
        assertTrue(store.jobs.single().error!!.contains("binder gone"))
        assertNotNull(b.busyReason())
        b.stopAll() // the emergency STOP is never held
        assertEquals(SeatAxis.entries.size, port.stops)
    }
}
