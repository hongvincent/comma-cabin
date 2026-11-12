package app.commacabin.support

import app.commacabin.probe.Access
import app.commacabin.probe.FakeScheduler
import app.commacabin.probe.ProbeError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeReadPort : VehicleReadPort {
    val caps = mutableMapOf<Feature, CapabilityResult>()
    val capErrors = mutableMapOf<Feature, ProbeError>()
    val values = mutableMapOf<Pair<Feature, String>, String?>()
    /** Features whose reads never answer until released. */
    val silent = mutableSetOf<Feature>()
    val held = mutableListOf<() -> Unit>()
    /** Features whose current-value reads (only) wait in [held]. */
    val heldCurrent = mutableSetOf<Feature>()
    /** Features whose observe() throws synchronously. */
    val observeThrows = mutableSetOf<Feature>()
    val listeners = mutableMapOf<Pair<Feature, String>, (String?) -> Unit>()
    val errorListeners = mutableMapOf<Pair<Feature, String>, (ProbeError) -> Unit>()
    var closedObservers = 0

    override fun capability(feature: Feature, ok: (CapabilityResult) -> Unit, err: (ProbeError) -> Unit) {
        val answer = {
            val e = capErrors[feature]
            if (e != null) err(e) else ok(caps[feature] ?: CapabilityResult(Access.NONE, emptyList()))
        }
        if (feature in silent) held += answer else answer()
    }

    override fun current(feature: Feature, area: String, ok: (String?) -> Unit, err: (ProbeError) -> Unit) {
        val answer = { ok(values[feature to area]) }
        if (feature in silent || feature in heldCurrent) held += answer else answer()
    }

    override fun observe(feature: Feature, area: String, onUpdate: (String?) -> Unit, onError: (ProbeError) -> Unit): AutoCloseable? {
        if (feature != Feature.DOOR_POSITION && feature != Feature.SEAT_BACKREST_POSITION) return null
        if (feature in observeThrows) throw SecurityException("register denied")
        listeners[feature to area] = onUpdate
        errorListeners[feature to area] = onError
        return AutoCloseable { closedObservers++ }
    }
}

class SupportDiagnosticsTest {
    private val port = FakeReadPort()
    private val scheduler = FakeScheduler()
    private val diagnostics = SupportDiagnostics(port, scheduler, { scheduler.now })

    private val driver = "ROW_1_LEFT"
    private val dual = "ROW_1_LEFT+ROW_1_RIGHT"

    private fun allSupported() {
        port.caps[Feature.DOOR_POSITION] = CapabilityResult(Access.READ_WRITE, listOf(AreaBound("ROW_1_LEFT", "0", "1"), AreaBound("ROW_1_RIGHT", "0", "1")))
        port.caps[Feature.SEAT_BACKREST_POSITION] = CapabilityResult(Access.READ, listOf(AreaBound(driver, "0", "100")))
        port.caps[Feature.SEAT_BACKREST_MOVE] = CapabilityResult(Access.WRITE, listOf(AreaBound(driver)))
        for (f in listOf(Feature.HVAC_POWER, Feature.HVAC_AUTO, Feature.HVAC_DUAL)) {
            port.caps[f] = CapabilityResult(Access.READ_WRITE, listOf(AreaBound(dual)))
        }
        port.caps[Feature.HVAC_FAN_SPEED] = CapabilityResult(Access.READ_WRITE, listOf(AreaBound(dual, "0", "8")))
        port.caps[Feature.HVAC_TEMPERATURE] = CapabilityResult(Access.READ_WRITE, listOf(AreaBound(dual, "16.0", "30.0")), "간격 0.5°C")
        port.caps[Feature.AMBIENT_COLOR] = CapabilityResult(Access.READ_WRITE, listOf(AreaBound("ROW_1_LEFT+ROW_1_RIGHT+ROW_2_CENTER")))
        port.values[Feature.DOOR_POSITION to "ROW_1_RIGHT"] = "0"
        port.values[Feature.SEAT_BACKREST_POSITION to driver] = "40"
        port.values[Feature.HVAC_POWER to dual] = "false"
        port.values[Feature.HVAC_AUTO to dual] = "false"
        port.values[Feature.HVAC_DUAL to dual] = "false"
        port.values[Feature.HVAC_FAN_SPEED to dual] = "0"
        port.values[Feature.HVAC_TEMPERATURE to dual] = "22.0"
        port.values[Feature.AMBIENT_COLOR to "ROW_1_LEFT+ROW_1_RIGHT+ROW_2_CENTER"] = "0xFF00E5FF (raw -16718337)"
    }

    private fun rows(feature: Feature, kind: RowKind) = diagnostics.state.rows.filter { it.feature == feature && it.kind == kind }

    @Test
    fun `reads every feature, only the target door and driver seat values, and starts the two subscriptions`() {
        allSupported()
        diagnostics.startAll()

        assertFalse(diagnostics.state.running)
        assertEquals(listOf("0"), rows(Feature.DOOR_POSITION, RowKind.CURRENT).map { it.detail })
        assertEquals("ROW_1_RIGHT", rows(Feature.DOOR_POSITION, RowKind.CURRENT).single().area)
        assertEquals(listOf("40"), rows(Feature.SEAT_BACKREST_POSITION, RowKind.CURRENT).map { it.detail })
        assertTrue(rows(Feature.SEAT_BACKREST_MOVE, RowKind.CURRENT).isEmpty())
        assertTrue(rows(Feature.HVAC_TEMPERATURE, RowKind.CAPABILITY).single().detail.contains("간격 0.5°C"))
        assertEquals("0xFF00E5FF (raw -16718337)", rows(Feature.AMBIENT_COLOR, RowKind.CURRENT).single().detail)
        assertTrue(diagnostics.state.rows.all { it.status == RowStatus.OK })
        assertEquals(setOf(Feature.DOOR_POSITION to "ROW_1_RIGHT", Feature.SEAT_BACKREST_POSITION to driver), port.listeners.keys)

        port.listeners.getValue(Feature.DOOR_POSITION to "ROW_1_RIGHT").invoke("1")
        assertEquals("1", diagnostics.state.events.single().value)
    }

    @Test
    fun `unsupported, error and no-read-access cases are labelled and never shown as values`() {
        allSupported()
        port.caps[Feature.DOOR_POSITION] = CapabilityResult(Access.READ_WRITE, listOf(AreaBound("ROW_1_LEFT", "0", "1")))
        port.caps[Feature.SEAT_BACKREST_POSITION] = CapabilityResult(Access.NONE, emptyList())
        port.capErrors[Feature.HVAC_DUAL] = ProbeError("x", "ERROR_NOT_SUPPORTED_PROPERTY", "ERROR_NOT_SUPPORTED_PROPERTY")
        port.capErrors[Feature.HVAC_AUTO] = ProbeError("x", "ERROR_INTERNAL_SERVICE", "ERROR_INTERNAL_SERVICE")
        port.caps[Feature.HVAC_FAN_SPEED] = CapabilityResult(Access.WRITE, listOf(AreaBound(dual, "0", "8")))
        diagnostics.startAll()

        assertEquals(RowStatus.UNSUPPORTED, rows(Feature.DOOR_POSITION, RowKind.CURRENT).single().status)
        assertEquals(RowStatus.UNSUPPORTED, rows(Feature.SEAT_BACKREST_POSITION, RowKind.CAPABILITY).single().status)
        assertTrue(rows(Feature.SEAT_BACKREST_POSITION, RowKind.CURRENT).isEmpty())
        assertEquals(RowStatus.UNSUPPORTED, rows(Feature.HVAC_DUAL, RowKind.CAPABILITY).single().status)
        assertEquals(RowStatus.ERROR, rows(Feature.HVAC_AUTO, RowKind.CAPABILITY).single().status)
        assertEquals(RowStatus.SKIPPED, rows(Feature.HVAC_FAN_SPEED, RowKind.CURRENT).single().status)
        assertTrue(port.listeners.isEmpty())
        assertFalse(diagnostics.state.running)
    }

    @Test
    fun `a null value is reported as no value, not as a physical state`() {
        allSupported()
        port.values[Feature.DOOR_POSITION to "ROW_1_RIGHT"] = null
        diagnostics.startAll()
        assertTrue(rows(Feature.DOOR_POSITION, RowKind.CURRENT).single().detail.startsWith("null(값 없음"))
    }

    @Test
    fun `a silent read times out, the run continues, and its late answer is ignored`() {
        allSupported()
        port.silent += Feature.SEAT_BACKREST_POSITION
        diagnostics.startAll()
        assertTrue(diagnostics.state.running)
        assertEquals(RowStatus.RUNNING, rows(Feature.SEAT_BACKREST_POSITION, RowKind.CAPABILITY).single().status)

        scheduler.advance(SUPPORT_READ_TIMEOUT_MS)
        assertEquals(RowStatus.TIMEOUT, rows(Feature.SEAT_BACKREST_POSITION, RowKind.CAPABILITY).single().status)
        assertFalse(diagnostics.state.running)
        assertEquals(RowStatus.OK, rows(Feature.AMBIENT_COLOR, RowKind.CURRENT).single().status)

        val before = diagnostics.state.rows
        port.held.forEach { it() }
        assertEquals(before, diagnostics.state.rows)
        assertTrue(rows(Feature.SEAT_BACKREST_POSITION, RowKind.CURRENT).isEmpty())
    }

    @Test
    fun `a new run ignores answers and events from the previous run`() {
        allSupported()
        port.silent += Feature.DOOR_POSITION
        diagnostics.startAll()
        val lateFirst = port.held.toList()
        port.held.clear()
        port.silent.clear()

        diagnostics.startAll()
        val oldDoorListenerCount = port.listeners.size
        val rowsAfterSecond = diagnostics.state.rows
        lateFirst.forEach { it() }

        assertEquals(rowsAfterSecond, diagnostics.state.rows)
        assertEquals(2, diagnostics.state.run)
        assertEquals(2, oldDoorListenerCount)
    }

    @Test
    fun `an event from a stopped subscription or after close changes nothing`() {
        allSupported()
        diagnostics.startAll()
        val oldDoor = port.listeners.getValue(Feature.DOOR_POSITION to "ROW_1_RIGHT")
        diagnostics.startAll()
        oldDoor("1")
        assertTrue(diagnostics.state.events.isEmpty())

        val currentDoor = port.listeners.getValue(Feature.DOOR_POSITION to "ROW_1_RIGHT")
        val currentDoorError = port.errorListeners.getValue(Feature.DOOR_POSITION to "ROW_1_RIGHT")
        diagnostics.stopObserving()
        assertTrue(port.closedObservers >= 4)
        assertTrue(diagnostics.state.observing.isEmpty())
        assertTrue(diagnostics.state.observeStopped)

        // Callbacks of this same run that were already queued when the user stopped.
        val stateAfterStop = diagnostics.state
        currentDoor("1")
        currentDoorError(ProbeError("x", "late", "ERROR_INTERNAL_SERVICE"))
        assertEquals(stateAfterStop, diagnostics.state)

        port.silent += Feature.HVAC_POWER
        diagnostics.startAll()
        val held = port.held.toList()
        val stateBeforeClose = diagnostics.state
        diagnostics.close()
        held.forEach { it() }
        currentDoor("1")
        scheduler.advance(SUPPORT_READ_TIMEOUT_MS * 3)
        diagnostics.startAll()

        assertEquals(stateBeforeClose, diagnostics.state)
        assertNull(diagnostics.state.events.firstOrNull())
    }

    @Test
    fun `stopping while the seat read is pending keeps subscriptions off until the next run`() {
        allSupported()
        port.heldCurrent += Feature.SEAT_BACKREST_POSITION
        diagnostics.startAll()
        assertEquals(setOf(Feature.DOOR_POSITION to "ROW_1_RIGHT"), port.listeners.keys)

        diagnostics.stopObserving()
        port.held.toList().also { port.held.clear() }.forEach { it() }

        // The value read still finishes, but no subscription comes back.
        assertEquals(listOf("40"), rows(Feature.SEAT_BACKREST_POSITION, RowKind.CURRENT).map { it.detail })
        assertEquals(setOf(Feature.DOOR_POSITION to "ROW_1_RIGHT"), port.listeners.keys)
        assertTrue(diagnostics.state.observing.isEmpty())
        assertTrue(diagnostics.state.observeStopped)
        assertFalse(diagnostics.state.running)

        port.heldCurrent.clear()
        diagnostics.startAll()
        assertEquals(setOf(Feature.DOOR_POSITION to "ROW_1_RIGHT", Feature.SEAT_BACKREST_POSITION to driver), port.listeners.keys)
        assertEquals(2, diagnostics.state.observing.size)
        assertFalse(diagnostics.state.observeStopped)
        port.listeners.getValue(Feature.SEAT_BACKREST_POSITION to driver).invoke("35")
        assertEquals("35", diagnostics.state.events.single().value)
    }

    @Test
    fun `a subscription that throws on register is shown as failed, not as subscribed`() {
        allSupported()
        port.observeThrows += Feature.DOOR_POSITION
        diagnostics.startAll()

        val failed = diagnostics.state.rows.filter { it.feature == Feature.DOOR_POSITION && it.status == RowStatus.ERROR }
        assertEquals(1, failed.size)
        assertEquals(RowKind.SUBSCRIPTION, failed.single().kind)
        assertEquals("ROW_1_RIGHT", failed.single().area)
        assertTrue(failed.single().detail.startsWith("구독 등록 실패"))
        assertTrue(failed.single().detail.contains("SecurityException"))
        assertEquals(RowStatus.OK, rows(Feature.DOOR_POSITION, RowKind.CURRENT).single().status)
        assertEquals(listOf("${Feature.SEAT_BACKREST_POSITION.label} $driver"), diagnostics.state.observing)
        assertFalse(diagnostics.state.running)
    }
}
