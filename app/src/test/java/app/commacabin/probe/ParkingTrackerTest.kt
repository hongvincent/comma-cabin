package app.commacabin.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParkingTrackerTest {
    private val seen = mutableListOf<ParkingState>()
    private val tracker = ParkingTracker { seen += it }

    @Test
    fun `events from an older binding cannot overwrite the current state`() {
        val first = tracker.newBinding()!!
        tracker.gear(first, GearState.PARK, 10)
        tracker.uxRestricted(first, false)
        assertTrue(tracker.state.parked)

        val second = tracker.newBinding()!!
        assertFalse(tracker.state.parked)
        tracker.gear(second, GearState.NOT_PARK, 20)
        tracker.uxRestricted(second, true)
        tracker.gear(first, GearState.PARK, 30)
        tracker.uxRestricted(first, false)

        assertEquals(ParkingState(GearState.NOT_PARK, true), tracker.state)
    }

    @Test
    fun `a disconnect resets to unknown and keeps writes blocked`() {
        val id = tracker.newBinding()!!
        tracker.gear(id, GearState.PARK, 10)
        tracker.uxRestricted(id, false)
        tracker.newBinding()

        assertEquals(ParkingState(), tracker.state)
        assertFalse(tracker.state.parked)
    }

    @Test
    fun `after close nothing is accepted and no new binding is handed out`() {
        val id = tracker.newBinding()!!
        tracker.gear(id, GearState.PARK, 10)
        tracker.uxRestricted(id, false)
        tracker.close()

        assertNull(tracker.newBinding())
        assertFalse(tracker.isCurrent(id))
        tracker.gear(id, GearState.PARK, 20)
        tracker.uxRestricted(id, false)
        assertEquals(ParkingState(), tracker.state)
        assertEquals(ParkingState(), seen.last())
    }

    @Test
    fun `an older gear timestamp within the same binding is ignored`() {
        val id = tracker.newBinding()!!
        tracker.gear(id, GearState.NOT_PARK, 20)
        tracker.gear(id, GearState.PARK, 10)
        assertEquals(GearState.NOT_PARK, tracker.state.gear)
    }
}
