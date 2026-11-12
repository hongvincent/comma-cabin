package app.commacabin.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientProbeTest {
    private val light = FakeLight()
    private val store = FakeStore()
    private val scheduler = FakeScheduler()
    private val lease = RecordLease()
    private var parking = ParkingState(GearState.PARK, uxRestricted = false)
    private var jobs = 0

    private fun probe(): AmbientProbe =
        AmbientProbe(light, store, scheduler, { parking }, { scheduler.now }, lease, newJobId = { "job-${++jobs}" })
            .also { it.start() }

    private fun setParking(probe: AmbientProbe, next: ParkingState) {
        parking = next
        probe.onParkingChanged(next)
    }

    private fun confirmedRecord(stage: Stage = Stage.APPLY_CONFIRMED, received: Boolean = true, prior: Boolean = false) =
        RecoveryRecord(jobId = "old", area = DRIVER.key, originalValue = 80, targetValue = 60, stage = stage,
            responseReceived = received, priorRequestUnresolved = prior)

    @Test
    fun `normal change and restore saves the record before each write and deletes it after confirmed restore`() {
        val p = probe()
        light.beforeSet = { v ->
            assertEquals(Stage.APPLY_REQUESTED, store.stored!!.stage)
            assertEquals(v, store.stored!!.targetValue)
            assertFalse(store.stored!!.responseReceived)
        }
        p.startChange()

        assertEquals(DRIVER, p.state.area)
        assertEquals(listOf(60), light.sets)
        assertEquals(Stage.APPLY_CONFIRMED, store.stored!!.stage)
        assertTrue(store.stored!!.responseReceived)
        assertEquals(60, p.state.lastRead)

        light.beforeSet = { v ->
            assertEquals(Stage.RESTORE_REQUESTED, store.stored!!.stage)
            assertEquals(80, v)
        }
        p.restore()

        assertEquals(listOf(60, 80), light.sets)
        assertEquals(80, light.value)
        assertNull(store.stored)
        assertNull(p.state.record)
        assertFalse(p.state.busy)
    }

    @Test
    fun `record save failure means zero vehicle writes and a storage fault`() {
        store.failSaveWhen = { true }
        val p = probe()
        p.startChange()

        assertTrue(light.sets.isEmpty())
        assertNull(p.state.record)
        assertNotNull(p.state.storageFault)
        assertFalse(p.state.canStart)
    }

    @Test
    fun `unsupported, read-only, unparked, switch-off and at-minimum cases write nothing`() {
        fun runCase(setup: () -> Unit) {
            light.capabilityError = null
            light.readError = null
            light.capability = BrightnessCapability(Access.READ_WRITE, mapOf(DRIVER to 0..100))
            light.switch = SwitchState.ON
            light.value = 80
            parking = ParkingState(GearState.PARK, false)
            light.sets.clear()
            setup()
            val p = probe()
            p.startChange()
            assertTrue(light.sets.isEmpty())
            assertNull(store.stored)
            assertFalse(p.state.busy)
        }
        runCase { light.capabilityError = SDK_ERROR }
        runCase { light.capability = BrightnessCapability(Access.READ_WRITE, emptyMap()) }
        runCase { light.capability = BrightnessCapability(Access.READ, mapOf(DRIVER to 0..100)) }
        runCase { parking = ParkingState(GearState.UNKNOWN, false) }
        runCase { parking = ParkingState(GearState.PARK, null) }
        runCase { parking = ParkingState(GearState.NOT_PARK, false) }
        runCase { parking = ParkingState(GearState.PARK, true) }
        runCase { light.switch = SwitchState.OFF }
        runCase { light.switch = null }
        runCase { light.value = 0 }
        runCase { light.readError = SDK_ERROR }
    }

    @Test
    fun `write timeout then original read keeps the record, and a late apply response is processed by job id`() {
        light.respond = false
        light.applyOnSet = false
        val p = probe()
        p.startChange()
        assertEquals(listOf(60), light.sets)

        scheduler.advance(RESPONSE_TIMEOUT_MS)
        assertTrue(p.state.pending!!.timedOut)
        assertEquals(80, p.state.lastRead)
        assertEquals(Stage.APPLY_REQUESTED, store.stored!!.stage)
        assertFalse(store.stored!!.responseReceived)

        p.restore()
        p.startChange()
        p.discardRecord()
        p.refresh()
        assertEquals(listOf(60), light.sets)
        assertNotNull(store.stored)

        light.value = 60
        light.heldResponses.single().first(60)

        assertNull(p.state.pending)
        assertEquals(Stage.APPLY_CONFIRMED, store.stored!!.stage)
        assertTrue(store.stored!!.responseReceived)
        assertEquals(60, p.state.lastRead)

        light.respond = true
        light.applyOnSet = true
        p.restore()
        assertEquals(listOf(60, 80), light.sets)
        assertNull(store.stored)
    }

    @Test
    fun `pending request blocks duplicate runs, restore and discard`() {
        light.respond = false
        val p = probe()
        p.startChange()
        p.startChange()
        p.restore()
        p.discardRecord()

        assertEquals(listOf(60), light.sets)
        assertNotNull(store.stored)
        assertFalse(p.state.canStart)
        assertFalse(p.state.canRestore)
        assertFalse(p.state.canDiscard)
    }

    @Test
    fun `losing the parked state during a run holds the write and returning to P does not rerun`() {
        light.holdReads = true
        val p = probe()
        p.startChange()
        setParking(p, ParkingState(GearState.NOT_PARK, false))
        light.releaseReads()

        assertTrue(light.sets.isEmpty())
        assertNull(store.stored)
        assertTrue(p.state.message.contains("보류"))

        setParking(p, ParkingState(GearState.PARK, false))
        assertTrue(light.sets.isEmpty())
    }

    @Test
    fun `losing the parked state before restore holds it without writing`() {
        val p = probe()
        p.startChange()
        setParking(p, ParkingState(GearState.PARK, uxRestricted = true))
        p.restore()

        assertEquals(listOf(60), light.sets)
        assertEquals(Stage.RESTORE_HELD, store.stored!!.stage)

        setParking(p, ParkingState(GearState.PARK, false))
        assertEquals(listOf(60), light.sets)
        assertNotNull(p.state.manualGuide)
    }

    @Test
    fun `restore failure keeps the record even when the read value equals the original`() {
        val p = probe()
        p.startChange()
        light.setError = SDK_ERROR
        light.beforeSet = { light.value = 80 }
        p.restore()

        assertEquals(Stage.RESTORE_FAILED, store.stored!!.stage)
        assertEquals(80, store.stored!!.lastReadValue)
        assertNotNull(p.state.manualGuide)
    }

    @Test
    fun `restore completion with a mismatching read is a failure`() {
        val p = probe()
        p.startChange()
        light.applyOnSet = false
        p.restore()

        assertEquals(Stage.RESTORE_FAILED, store.stored!!.stage)
        assertEquals(60, store.stored!!.lastReadValue)
    }

    @Test
    fun `apply error is result unknown even when the value did not change`() {
        light.setError = SDK_ERROR
        val p = probe()
        p.startChange()

        assertEquals(80, light.value)
        assertEquals(Stage.RESULT_UNKNOWN, store.stored!!.stage)
        assertNotNull(p.state.manualGuide)
    }

    @Test
    fun `relaunch with a confirmed record blocks new runs and writes only on explicit restore`() {
        store.stored = confirmedRecord()
        light.value = 60
        val p = probe()

        p.startChange()
        assertTrue(light.sets.isEmpty())
        assertEquals(Stage.APPLY_CONFIRMED, p.state.record!!.stage)
        assertNotNull(light.observer)

        p.restore()
        assertEquals(listOf(80), light.sets)
        assertNull(store.stored)
    }

    @Test
    fun `relaunch with an unanswered request blocks restore, discard and new writes`() {
        val unanswered = confirmedRecord(stage = Stage.APPLY_REQUESTED, received = false)
        store.stored = unanswered
        val p = probe()

        assertNotNull(p.state.lockReason)
        assertFalse(p.state.canStart)
        assertFalse(p.state.canRestore)
        assertFalse(p.state.canDiscard)
        p.startChange()
        p.restore()
        p.discardRecord()
        p.refresh()

        assertTrue(light.sets.isEmpty())
        assertEquals(unanswered, store.stored)
        assertEquals(0, store.deletes)
        assertNotNull(p.state.manualGuide)
    }

    @Test
    fun `relaunch with an unanswered restore or a prior unresolved request is also locked`() {
        for (record in listOf(
            confirmedRecord(stage = Stage.RESTORE_REQUESTED, received = false),
            confirmedRecord(stage = Stage.RESTORE_FAILED, prior = true),
        )) {
            store.stored = record
            val p = probe()
            p.restore()
            p.discardRecord()
            p.startChange()
            assertTrue(light.sets.isEmpty())
            assertEquals(record, store.stored)
            assertNotNull(p.state.lockReason)
            p.close()
        }
    }

    @Test
    fun `unreadable record blocks writes and deletion and is kept`() {
        store.unreadable = "손상된 기록"
        val p = probe()
        p.startChange()
        p.restore()
        p.discardRecord()

        assertTrue(light.sets.isEmpty())
        assertEquals("손상된 기록", store.unreadable)
        assertEquals(0, store.deletes)
        assertNotNull(p.state.recordProblem)
        assertFalse(p.state.canDiscard)
    }

    @Test
    fun `user change after apply is never overwritten automatically`() {
        val p = probe()
        p.startChange()
        light.observer!!.invoke(45)

        assertEquals(Stage.USER_CHANGED, store.stored!!.stage)
        setParking(p, ParkingState(GearState.NOT_PARK, false))
        setParking(p, ParkingState(GearState.PARK, false))
        p.startChange()
        scheduler.advance(RESPONSE_TIMEOUT_MS * 2)

        assertEquals(listOf(60), light.sets)
        assertEquals(Stage.USER_CHANGED, store.stored!!.stage)
        assertNotNull(p.state.manualGuide)
    }

    @Test
    fun `a capability read without a response times out, frees the probe and its late answer is ignored`() {
        light.holdReads = true
        val p = probe()
        p.startChange()
        assertTrue(p.state.busy)

        scheduler.advance(READ_TIMEOUT_MS)
        assertFalse(p.state.busy)
        assertTrue(light.sets.isEmpty())
        assertEquals(LightStatus.ERROR, p.state.light)
        val lateFromFirstRun = light.heldReads.toList()
        light.heldReads.clear()

        light.holdReads = false
        light.capability = BrightnessCapability(Access.READ_WRITE, mapOf(DRIVER to 0..90))
        p.startChange()
        assertEquals(listOf(62), light.sets)
        val afterRetry = store.stored

        lateFromFirstRun.forEach { it() }
        assertEquals(listOf(62), light.sets)
        assertEquals(afterRetry, store.stored)
        assertEquals(0..90, p.state.bounds)
        assertTrue(p.state.log.any { it.step == "늦은 조회 응답 무시" })
    }

    @Test
    fun `a readback without a response after a write is result unknown and keeps the record`() {
        val p = probe()
        light.respond = false
        p.startChange()
        light.holdReads = true
        light.heldResponses.single().first(60)
        assertTrue(p.state.evaluating)

        scheduler.advance(READ_TIMEOUT_MS)
        assertFalse(p.state.evaluating)
        assertEquals(Stage.RESULT_UNKNOWN, store.stored!!.stage)

        light.releaseReads()
        assertEquals(Stage.RESULT_UNKNOWN, store.stored!!.stage)
        assertEquals(listOf(60), light.sets)
    }

    @Test
    fun `after close late write and read callbacks never touch the record`() {
        light.respond = false
        val p = probe()
        p.startChange()
        val saved = store.stored
        p.close()

        light.heldResponses.single().first(60)
        scheduler.advance(RESPONSE_TIMEOUT_MS + READ_TIMEOUT_MS)
        p.startChange()
        p.restore()
        p.discardRecord()

        assertEquals(saved, store.stored)
        assertFalse(store.stored!!.responseReceived)
        assertEquals(listOf(60), light.sets)
    }

    @Test
    fun `a newer probe takes the lease and the older one becomes inert`() {
        light.respond = false
        val old = probe()
        old.startChange()
        val saved = store.stored!!

        val newer = probe()
        assertNotNull(newer.state.lockReason)

        light.heldResponses.single().first(60)
        old.startChange()
        old.restore()
        old.discardRecord()

        assertEquals(saved, store.stored)
        assertEquals(listOf(60), light.sets)
        assertNotNull(newer.state.lockReason)
    }

    @Test
    fun `failing to save the response stops later writes and deletion`() {
        light.respond = false
        val p = probe()
        p.startChange()
        store.failSaveWhen = { true }
        light.heldResponses.single().first(60)

        assertNotNull(p.state.storageFault)
        assertEquals(Stage.APPLY_REQUESTED, store.stored!!.stage)
        assertFalse(store.stored!!.responseReceived)

        store.failSaveWhen = { false }
        light.respond = true
        p.restore()
        p.discardRecord()
        assertEquals(listOf(60), light.sets)
        assertEquals(0, store.deletes)
        assertFalse(p.state.canRestore)
    }

    @Test
    fun `failing to delete after a confirmed restore keeps the record and blocks more writes`() {
        val p = probe()
        p.startChange()
        store.failDelete = true
        p.restore()

        assertEquals(listOf(60, 80), light.sets)
        assertNotNull(store.stored)
        assertNotNull(p.state.storageFault)
        p.restore()
        p.discardRecord()
        assertEquals(listOf(60, 80), light.sets)
    }

    @Test
    fun `restore checks the current capability, range and save before writing`() {
        fun blocked(setup: () -> Unit) {
            store.stored = confirmedRecord()
            store.failSaveWhen = { false }
            light.capabilityError = null
            light.holdReads = false
            light.capability = BrightnessCapability(Access.READ_WRITE, mapOf(DRIVER to 0..100))
            light.sets.clear()
            setup()
            val p = probe()
            p.restore()
            scheduler.advance(READ_TIMEOUT_MS)
            assertTrue(light.sets.isEmpty())
            assertEquals(Stage.APPLY_CONFIRMED, store.stored!!.stage)
            assertFalse(p.state.busy)
            p.close()
        }
        blocked { light.capability = BrightnessCapability(Access.READ_WRITE, mapOf(PASSENGER to 0..100)) }
        blocked { light.capability = BrightnessCapability(Access.READ, mapOf(DRIVER to 0..100)) }
        blocked { light.capability = BrightnessCapability(Access.READ_WRITE, mapOf(DRIVER to 0..70)) }
        blocked { light.capabilityError = SDK_ERROR }
        blocked { light.holdReads = true }
        blocked { store.failSaveWhen = { it.stage == Stage.RESTORE_REQUESTED } }
    }

    @Test
    fun `a write-timeout diagnostic read that answers after later work changes nothing`() {
        light.respond = false
        val p = probe()
        p.startChange()
        light.holdReads = true
        light.value = 70
        scheduler.advance(RESPONSE_TIMEOUT_MS)
        val diagnostic = light.heldReads.single()
        light.heldReads.clear()
        light.holdReads = false

        light.value = 60
        light.heldResponses.single().first(60)
        assertEquals(Stage.APPLY_CONFIRMED, store.stored!!.stage)
        light.respond = true
        p.restore()
        assertNull(store.stored)
        p.startChange()
        val lastRead = p.state.lastRead
        val record = p.state.record
        val stored = store.stored
        val writes = light.sets.toList()
        assertEquals(listOf(60, 80, 60), writes)

        diagnostic()

        assertEquals(lastRead, p.state.lastRead)
        assertEquals(record, p.state.record)
        assertEquals(stored, store.stored)
        assertEquals(writes, light.sets)
        assertTrue(p.state.log.any { it.step == "늦은 조회 응답 무시" })
    }

    @Test
    fun `a listener callback from an earlier subscription cannot mark a later job as user-changed`() {
        val p = probe()
        p.startChange()
        val firstListener = light.observer!!
        p.restore()
        p.startChange()
        val second = store.stored!!
        assertEquals(Stage.APPLY_CONFIRMED, second.stage)

        firstListener(33)

        assertEquals(second, store.stored)
        assertEquals(Stage.APPLY_CONFIRMED, p.state.record!!.stage)
        assertEquals(listOf(60, 80, 60), light.sets)
        assertTrue(p.state.log.any { it.step == "이전 구독 알림 무시" })
    }

    @Test
    fun `a record without a received response is locked whatever its stage says`() {
        for (stage in listOf(Stage.RESULT_UNKNOWN, Stage.APPLY_CONFIRMED, Stage.USER_CHANGED, Stage.RESTORE_FAILED, Stage.RESTORE_HELD)) {
            val record = confirmedRecord(stage = stage, received = false)
            store.stored = record
            val p = probe()
            assertNotNull(stage.name, p.state.lockReason)
            assertNull(stage.name, light.observer)
            p.startChange()
            p.restore()
            p.discardRecord()

            assertTrue(stage.name, light.sets.isEmpty())
            assertEquals(stage.name, record, store.stored)
            assertEquals(0, store.deletes)
            p.close()
        }
    }

    @Test
    fun `lower target stays inside the range`() {
        assertEquals(60, lowerTarget(80, 0..100))
        assertEquals(0, lowerTarget(1, 0..100))
        assertEquals(2, lowerTarget(3, 0..4))
        assertNull(lowerTarget(0, 0..100))
    }
}
