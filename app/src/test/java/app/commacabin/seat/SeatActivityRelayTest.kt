package app.commacabin.seat

import app.commacabin.probe.FakeScheduler
import app.commacabin.probe.GearState
import app.commacabin.probe.ParkingState
import app.commacabin.rest.DoorStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeatActivityRelayTest {
    private val env = SeatEnvironment(app.commacabin.control.Move.FORWARD, 40, 2, 500, 5_000, 3_000, 300, 2, 200)

    private class Rig(env: SeatEnvironment) {
        val port = FakeSeatPort(mutableListOf()).also { it.position = 20 }
        val scheduler = FakeScheduler()
        var n = 0
        val seat = RestSeat(port, { ok, _ -> ok(0..100) }, FakeJournal(mutableListOf()), scheduler, { ParkingState(GearState.PARK, false) }, env, { "id-${++n}" })
        val relay = SeatActivityRelay(seat)
    }

    @Test
    fun `an older Activity's late screen, door or destroy signal never ends the newer one's seat job`() {
        val r = Rig(env)
        r.seat.start()
        val old = Any()
        val new = Any()
        r.relay.attach(old)
        r.relay.door(old, DoorStatus.CLOSED)
        r.relay.attach(new) // a new Activity is shown before the old one stops
        r.relay.door(new, DoorStatus.CLOSED)
        assertEquals(1, r.seat.onRestStart())
        assertEquals(RestSeatPhase.APPLYING, r.seat.state.phase)
        r.relay.hidden(old)
        r.relay.door(old, DoorStatus.OPEN)
        r.relay.detach(old)
        assertTrue(r.port.stops.isEmpty())
        assertEquals(RestSeatPhase.APPLYING, r.seat.state.phase)
        r.relay.hidden(new) // the current one: the job ends and its STOP goes
        assertEquals(1, r.port.stops.size)
    }

    @Test
    fun `door mapping - only CLOSED lets an apply start, OPEN ends it, the rest are unknown`() {
        for ((status, starts) in listOf(DoorStatus.CLOSED to true, DoorStatus.OPEN to false, DoorStatus.CHECKING to false, DoorStatus.UNSUPPORTED to false, DoorStatus.ERROR to false)) {
            val r = Rig(env)
            r.seat.start()
            val a = Any()
            r.relay.attach(a)
            r.relay.door(a, status)
            r.seat.onRestStart()
            assertEquals(status.name, starts, r.port.entered.isNotEmpty())
        }
        val r = Rig(env)
        r.seat.start()
        val a = Any()
        r.relay.attach(a)
        r.relay.door(a, DoorStatus.CLOSED)
        r.seat.onRestStart()
        r.relay.door(a, DoorStatus.ERROR) // unknown while applying: the job ends
        assertEquals(1, r.port.stops.size)
    }

    @Test
    fun `a new Activity starts with the door unknown - an older CLOSED is not carried over, an older callback is dropped`() {
        val r = Rig(env)
        r.seat.start()
        val old = Any()
        val new = Any()
        r.relay.attach(old)
        r.relay.door(old, DoorStatus.CLOSED)
        r.relay.hidden(old)
        r.relay.attach(new) // its own door check has not answered yet
        r.relay.door(old, DoorStatus.CLOSED) // a late callback of the older Activity
        r.seat.onRestStart()
        assertTrue(r.port.entered.isEmpty())

        val q = Rig(env)
        q.seat.start()
        q.relay.attach(old)
        q.relay.door(old, DoorStatus.CLOSED)
        q.relay.attach(new)
        q.relay.door(new, DoorStatus.CLOSED) // the new Activity's own answer
        q.seat.onRestStart()
        assertEquals(1, q.port.entered.size)
    }
}
