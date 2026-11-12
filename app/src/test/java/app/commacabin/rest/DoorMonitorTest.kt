package app.commacabin.rest

import app.commacabin.probe.Access
import app.commacabin.probe.FakeScheduler
import app.commacabin.probe.ProbeError
import app.commacabin.support.AreaBound
import app.commacabin.support.CapabilityResult
import app.commacabin.support.Feature
import app.commacabin.support.VehicleReadPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private class DoorPort : VehicleReadPort {
    var capability = CapabilityResult(Access.READ_WRITE, listOf(AreaBound("ROW_1_LEFT", "0", "1"), AreaBound(DOOR_AREA, "0", "1")))
    var capabilityError: ProbeError? = null
    /** When true, capability checks wait in [heldCapability]. */
    var holdCapability = false
    val heldCapability = mutableListOf<() -> Unit>()
    var value: String? = "0"
    var readError: ProbeError? = null
    /** When true, reads wait in [heldReads] until the test answers them. */
    var holdReads = false
    val heldReads = mutableListOf<Pair<(String?) -> Unit, (ProbeError) -> Unit>>()
    var reads = 0
    var observeThrows = false
    var listener: ((String?) -> Unit)? = null
    var errorListener: ((ProbeError) -> Unit)? = null
    var openSubscriptions = 0

    override fun capability(feature: Feature, ok: (CapabilityResult) -> Unit, err: (ProbeError) -> Unit) {
        val answer = { capabilityError?.let { err(it) } ?: ok(capability) }
        if (holdCapability) heldCapability += answer else answer()
    }

    override fun current(feature: Feature, area: String, ok: (String?) -> Unit, err: (ProbeError) -> Unit) {
        reads++
        when {
            holdReads -> heldReads += ok to err
            readError != null -> err(readError!!)
            else -> ok(value)
        }
    }

    override fun observe(feature: Feature, area: String, onUpdate: (String?) -> Unit, onError: (ProbeError) -> Unit): AutoCloseable {
        if (observeThrows) throw SecurityException("register denied")
        listener = onUpdate
        errorListener = onError
        openSubscriptions++
        return AutoCloseable { openSubscriptions-- }
    }
}

class DoorMonitorTest {
    private val port = DoorPort()
    private val scheduler = FakeScheduler()
    private val seen = mutableListOf<DoorStatus>()
    private val monitor = DoorMonitor(port, scheduler) { seen += it.status }

    private val status get() = monitor.state.status

    @Test
    fun `closed comes from capability and a direct read, without waiting for a notification`() {
        monitor.start()

        assertEquals(DoorStatus.CLOSED, status)
        assertEquals(1, port.reads)
        assertEquals(1, port.openSubscriptions)
        assertEquals(listOf(DoorStatus.CLOSED), seen)
    }

    @Test
    fun `a read sent before a newer door event cannot overwrite that event`() {
        port.holdReads = true
        monitor.start()
        assertEquals(DoorStatus.CHECKING, status)

        port.listener!!("1")
        assertEquals(DoorStatus.OPEN, status)
        port.heldReads.removeAt(0).first("0")

        assertEquals(DoorStatus.OPEN, status)
    }

    @Test
    fun `a read sent after the event is applied normally`() {
        monitor.start()
        port.listener!!("1")
        assertEquals(DoorStatus.OPEN, status)

        port.value = "0"
        scheduler.advance(DOOR_POLL_MS)

        assertEquals(DoorStatus.CLOSED, status)
    }

    @Test
    fun `polling notices an opened door when no notification arrives`() {
        monitor.start()
        port.value = "1"

        scheduler.advance(DOOR_POLL_MS)

        assertEquals(DoorStatus.OPEN, status)
    }

    @Test
    fun `null and out-of-range values are errors, never closed`() {
        port.value = null
        monitor.start()
        assertEquals(DoorStatus.ERROR, status)

        port.value = "2"
        scheduler.advance(DOOR_POLL_MS)
        assertEquals(DoorStatus.ERROR, status)

        port.value = "0"
        scheduler.advance(DOOR_POLL_MS)
        assertEquals(DoorStatus.CLOSED, status)
    }

    @Test
    fun `a missing area, no read access or a not-supported error is unsupported and reads nothing`() {
        port.capability = CapabilityResult(Access.READ_WRITE, listOf(AreaBound("ROW_1_LEFT", "0", "1")))
        monitor.start()
        assertEquals(DoorStatus.UNSUPPORTED, status)

        monitor.stop()
        port.capability = CapabilityResult(Access.WRITE, listOf(AreaBound(DOOR_AREA, "0", "1")))
        monitor.start()
        assertEquals(DoorStatus.UNSUPPORTED, status)

        monitor.stop()
        port.capabilityError = ProbeError("VehicleException", "ERROR_NOT_SUPPORTED", "ERROR_NOT_SUPPORTED")
        monitor.start()
        assertEquals(DoorStatus.UNSUPPORTED, status)

        assertEquals(0, port.reads)
        assertEquals(0, port.openSubscriptions)
    }

    @Test
    fun `the range comes from the capability, not from a fixed 0 and 1`() {
        port.capability = CapabilityResult(Access.READ, listOf(AreaBound(DOOR_AREA, "0", "100")))
        port.value = "40"
        monitor.start()
        assertEquals(DoorStatus.OPEN, status)

        port.value = "100"
        scheduler.advance(DOOR_POLL_MS)
        assertEquals(DoorStatus.OPEN, status)

        port.value = "0"
        scheduler.advance(DOOR_POLL_MS)
        assertEquals(DoorStatus.CLOSED, status)
    }

    @Test
    fun `a read error is an error and recovers through a new generation`() {
        port.readError = ProbeError("VehicleException", "service down", null)
        monitor.start()
        assertEquals(DoorStatus.ERROR, status)
        assertEquals(0, port.openSubscriptions)

        port.readError = null
        scheduler.advance(DOOR_RETRY_MS)

        assertEquals(DoorStatus.CLOSED, status)
        assertEquals(1, port.openSubscriptions)
    }

    @Test
    fun `a timed-out read is an error and its late reply is ignored`() {
        port.holdReads = true
        monitor.start()
        scheduler.advance(DOOR_READ_TIMEOUT_MS)
        assertEquals(DoorStatus.ERROR, status)

        port.heldReads.removeAt(0).first("0")

        assertEquals(DoorStatus.ERROR, status)
    }

    @Test
    fun `subscription failures are errors, not closed`() {
        port.observeThrows = true
        monitor.start()
        assertEquals(DoorStatus.ERROR, status)
        assertEquals(0, port.reads)

        port.observeThrows = false
        scheduler.advance(DOOR_RETRY_MS)
        assertEquals(DoorStatus.CLOSED, status)

        port.errorListener!!(ProbeError("VehicleException", "listener failed", null))
        assertEquals(DoorStatus.ERROR, status)
        assertEquals(0, port.openSubscriptions)
    }

    @Test
    fun `stop drops pending reads, polls and the subscription so nothing runs in the background`() {
        monitor.start()
        port.holdReads = true
        scheduler.advance(DOOR_POLL_MS)
        val old = port.listener!!
        val readsBefore = port.reads

        monitor.stop()
        assertEquals(DoorStatus.CHECKING, status)
        assertEquals(0, port.openSubscriptions)

        port.heldReads.removeAt(0).first("0")
        old("0")
        scheduler.advance(10 * DOOR_POLL_MS)

        assertEquals(DoorStatus.CHECKING, status)
        assertEquals(readsBefore, port.reads)
    }

    @Test
    fun `after a restart only the new generation can report closed`() {
        port.holdReads = true
        monitor.start()
        val oldListener = port.listener!!
        val oldRead = port.heldReads.removeAt(0)
        monitor.stop()

        monitor.start()
        oldRead.first("0")
        oldListener("0")
        assertEquals(DoorStatus.CHECKING, status)

        port.heldReads.removeAt(0).first("1")
        assertEquals(DoorStatus.OPEN, status)
        oldListener("0")
        assertEquals(DoorStatus.OPEN, status)
    }

    @Test
    fun `a reply after stop does not schedule anything`() {
        port.holdReads = true
        monitor.start()
        val read = port.heldReads.removeAt(0)
        monitor.stop()

        read.second(ProbeError("VehicleException", "late", null))
        scheduler.advance(10 * DOOR_RETRY_MS)

        assertEquals(DoorStatus.CHECKING, status)
        assertEquals(1, port.reads)
        assertNull(seen.firstOrNull { it == DoorStatus.ERROR })
    }

    @Test
    fun `the first capability check may take 5 s while value reads keep the 2 s limit`() {
        port.holdCapability = true
        monitor.start()
        scheduler.advance(DOOR_CAPABILITY_TIMEOUT_MS - 1)
        assertEquals(DoorStatus.CHECKING, status) // still checking: no start, no error yet

        scheduler.advance(1)
        assertEquals(DoorStatus.ERROR, status)

        scheduler.advance(DOOR_RETRY_MS)
        port.holdReads = true
        port.heldCapability.removeAt(port.heldCapability.lastIndex)()
        scheduler.advance(DOOR_READ_TIMEOUT_MS - 1)
        assertEquals(DoorStatus.ERROR, status) // the error stays until a real value arrives
        scheduler.advance(1)
        assertEquals(DoorStatus.ERROR, status)
        assertEquals(1, port.reads)

        port.holdReads = false
        scheduler.advance(DOOR_RETRY_MS)
        port.heldCapability.removeAt(port.heldCapability.lastIndex)()
        assertEquals(DoorStatus.CLOSED, status)
        assertNull(seen.firstOrNull { it == DoorStatus.CLOSED && seen.indexOf(it) < seen.indexOf(DoorStatus.ERROR) })
    }
}
