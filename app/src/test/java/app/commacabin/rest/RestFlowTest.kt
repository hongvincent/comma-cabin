package app.commacabin.rest

import app.commacabin.probe.FakeScheduler
import app.commacabin.probe.GearState
import app.commacabin.probe.ParkingState
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestFlowTest {
    private val scheduler = FakeScheduler()
    private val zone = ZoneId.of("Asia/Seoul")
    private val parked = ParkingState(GearState.PARK, false)
    /** 19:03:00 local; the default pickup is 19:25, so a rest started now lasts until 19:23 (20 minutes). */
    private val wallBase = at(12, 19, 3)
    /** Simulates a user or network changing the system clock; the monotonic clock never moves back. */
    private var wallShift = 0L
    private var parking = parked
    private var door = DoorState(DoorStatus.CLOSED)
    private var onboardingSeen = true
    private var stored = false
    private val log = mutableListOf<String>()

    private val flow by lazy {
        RestFlow(
            scheduler = scheduler,
            wallClock = { wallBase + scheduler.now + wallShift },
            elapsedClock = { 5_000_000 + scheduler.now },
            zone = { zone },
            parking = { parking },
            door = { door },
            onboardingSeen = onboardingSeen,
            markOnboardingSeen = { stored = true },
            log = { log += it },
        ).also { it.onVisible() }
    }

    private val state get() = flow.state

    private fun at(day: Int, hour: Int, minute: Int, second: Int = 0) =
        ZonedDateTime.of(2026, 11, day, hour, minute, second, 0, zone).toInstant().toEpochMilli()

    private fun setParking(p: ParkingState) {
        parking = p
        flow.onParking(p)
    }

    private fun setDoor(status: DoorStatus) {
        door = DoorState(status)
        flow.onDoor(door)
    }

    /** Moves time in tick-sized steps, as the real handler would. */
    private fun runFor(ms: Long) {
        var left = ms
        while (left > 0) {
            val step = minOf(REST_TICK_MS, left)
            scheduler.advance(step)
            left -= step
        }
    }

    @Test
    fun `onboarding is shown once and only its flag is stored`() {
        onboardingSeen = false
        assertEquals(Screen.ONBOARDING, state.screen)
        flow.start()
        assertEquals(Screen.ONBOARDING, state.screen)

        flow.dismissOnboarding()

        assertEquals(Screen.READY, state.screen)
        assertTrue(stored)
    }

    @Test
    fun `start checks the vehicle again at the moment of the press`() {
        assertTrue(state.canStart)

        parking = ParkingState(GearState.NOT_PARK, false) // changed, but the callback has not arrived yet
        flow.start()
        assertEquals(Screen.READY, state.screen)
        assertEquals("시작하지 않았어요. ${Block.NOT_PARKED.text}", state.notice)

        parking = parked
        door = DoorState(DoorStatus.OPEN)
        flow.start()
        assertEquals(Screen.READY, state.screen)
        assertEquals(Block.DOOR_OPEN, state.doorBlock)

        door = DoorState(DoorStatus.CLOSED)
        flow.start()
        assertEquals(Screen.RESTING, state.screen)
    }

    @Test
    fun `parked needs P and no driving restriction`() {
        assertNull(parkingBlock(parked))
        assertEquals(Block.RESTRICTED, parkingBlock(ParkingState(GearState.PARK, true)))
        assertEquals(Block.RESTRICTION_UNKNOWN, parkingBlock(ParkingState(GearState.PARK, null)))
        assertEquals(Block.NOT_PARKED, parkingBlock(ParkingState(GearState.NOT_PARK, false)))
        assertEquals(Block.NOT_PARKED, parkingBlock(ParkingState(GearState.NOT_PARK, true)))
        assertEquals(Block.GEAR_UNKNOWN, parkingBlock(ParkingState(GearState.UNKNOWN, false)))
        // A Car service reconnect resets to this until both signals answer again.
        assertEquals(Block.GEAR_UNKNOWN, parkingBlock(ParkingState()))

        for (p in listOf(ParkingState(GearState.PARK, true), ParkingState(GearState.PARK, null), ParkingState())) {
            setParking(p)
            flow.start()
            assertEquals(Screen.READY, state.screen)
        }
    }

    @Test
    fun `door checking, unsupported and error never allow a start`() {
        for (status in listOf(DoorStatus.CHECKING, DoorStatus.UNSUPPORTED, DoorStatus.ERROR, DoorStatus.OPEN)) {
            setDoor(status)
            assertFalse(state.canStart)
            flow.start()
            assertEquals(Screen.READY, state.screen)
        }
    }

    @Test
    fun `less than three minutes of rest cannot start`() {
        repeat(3) { flow.adjustPickup(-5) } // pickup 19:10, rest end 19:08, 5 minutes left
        assertTrue(state.canStart)
        runFor(2 * 60_000 + 1_000) // 19:05:01, 2:59 left
        assertEquals(Block.TIME_SHORT, state.timeBlock)

        flow.start()

        assertEquals(Screen.READY, state.screen)
    }

    @Test
    fun `moving the pickup into the past keeps today's date`() {
        repeat(6) { flow.adjustPickup(-5) } // 18:55

        assertEquals(at(12, 18, 55), state.pickupMs)
        assertEquals(0L, RestTime.dayOffset(state.pickupMs, state.nowMs, zone))
        assertEquals(Block.TIME_PASSED, state.timeBlock)
    }

    @Test
    fun `a normal end compares the monotonic clock with the end fixed at start`() {
        flow.start()
        val session = state.session!!
        assertEquals(at(12, 19, 23), session.endWallMs)

        runFor(20 * 60_000L - 61_000)
        assertFalse(state.finalMinute)
        runFor(1_000)
        assertTrue(state.finalMinute)
        runFor(59_750)
        assertEquals(Screen.RESTING, state.screen)
        runFor(250)

        assertEquals(Screen.ENDED, state.screen)
        assertEquals(Ending(session.id, EndReason.TIME_UP, at(12, 19, 23)), state.ending)
    }

    @Test
    fun `a late tick still ends the rest once and at the right time`() {
        flow.start()

        scheduler.advance(25 * 60_000L) // a single tick arriving five minutes late

        assertEquals(Screen.ENDED, state.screen)
        assertEquals(EndReason.TIME_UP, state.ending!!.reason)
        assertEquals(1, log.count { it.contains("end: TIME_UP") })
    }

    @Test
    fun `changing the system clock cannot lengthen or shorten a running rest`() {
        flow.start()
        runFor(60_000)

        wallShift = -60 * 60_000L // clock set back one hour
        runFor(19 * 60_000L - 250)
        assertEquals(Screen.RESTING, state.screen)
        runFor(250)
        assertEquals(EndReason.TIME_UP, state.ending!!.reason)

        wallShift = 0
        flow.restAgain()
        flow.adjustPickup(10) // 19:35, a 10-minute rest from 19:23
        flow.start()
        wallShift = 3 * 60 * 60_000L // clock moved three hours ahead
        runFor(60_000)
        assertEquals(Screen.RESTING, state.screen)
        assertEquals(9 * 60_000L, state.remainingMs)
    }

    @Test
    fun `each stop cause ends at once with its own reason`() {
        val causes: List<Pair<() -> Unit, EndReason>> = listOf(
            { setDoor(DoorStatus.OPEN) } to EndReason.DOOR_OPENED,
            { setDoor(DoorStatus.ERROR) } to EndReason.DOOR_UNKNOWN,
            { setDoor(DoorStatus.CHECKING) } to EndReason.DOOR_UNKNOWN,
            { setParking(ParkingState(GearState.NOT_PARK, false)) } to EndReason.LEFT_PARK,
            { setParking(ParkingState(GearState.PARK, true)) } to EndReason.RESTRICTED,
            { setParking(ParkingState()) } to EndReason.VEHICLE_UNKNOWN,
            { flow.onHidden() } to EndReason.APP_LEFT,
            { flow.endByUser() } to EndReason.USER,
        )
        for ((cause, reason) in causes) {
            if (state.screen == Screen.ENDED) flow.restAgain()
            setDoor(DoorStatus.CLOSED)
            setParking(parked)
            flow.onVisible()
            flow.start()
            assertEquals(Screen.RESTING, state.screen)

            cause()

            assertEquals(reason, state.ending!!.reason)
            assertEquals(Screen.ENDED, state.screen)
        }
    }

    @Test
    fun `an end is handled once and later callbacks neither change nor revive it`() {
        flow.start()
        runFor(1_000)
        setDoor(DoorStatus.OPEN)
        val ending = state.ending!!

        setParking(ParkingState(GearState.NOT_PARK, true))
        flow.onHidden()
        flow.endByUser()
        runFor(30 * 60_000L)
        setParking(parked)
        setDoor(DoorStatus.CLOSED)
        flow.onVisible()

        assertEquals(Screen.ENDED, state.screen)
        assertEquals(ending, state.ending)
        assertEquals(1, log.count { it.contains(" end: ") })
    }

    @Test
    fun `a new session is not changed by the previous session's time`() {
        flow.start()
        val first = state.session!!
        runFor(60_000)
        flow.touch() // wake the dim screen first
        flow.endByUser()
        flow.restAgain()
        flow.adjustPickup(5)
        flow.start()
        val second = state.session!!
        assertNotEquals(first.id, second.id)

        runFor(first.endElapsedMs - (5_000_000 + scheduler.now) + 1_000) // past the first session's end
        assertEquals(Screen.RESTING, state.screen)
        assertEquals(second, state.session)

        runFor(5 * 60_000L)
        assertEquals(Ending(second.id, EndReason.TIME_UP, state.ending!!.atWallMs), state.ending)
    }

    @Test
    fun `the screen dims after 10 seconds and the first touch only wakes it`() {
        flow.start()
        runFor(DIM_AFTER_MS - REST_TICK_MS)
        assertFalse(state.dimmed)
        runFor(REST_TICK_MS)
        assertTrue(state.dimmed)

        flow.endByUser() // a press on a dim screen must not act
        assertEquals(Screen.RESTING, state.screen)
        flow.touch()
        assertFalse(state.dimmed)

        runFor(5_000)
        flow.touch() // activity keeps it bright
        runFor(DIM_AFTER_MS - REST_TICK_MS)
        assertFalse(state.dimmed)
        runFor(REST_TICK_MS)
        assertTrue(state.dimmed)

        flow.touch()
        flow.endByUser()
        assertEquals(EndReason.USER, state.ending!!.reason)
        assertFalse(state.dimmed)
    }

    @Test
    fun `rest again keeps a pickup that has not passed and suggests a new one when it has`() {
        flow.start()
        runFor(60_000)
        flow.touch() // wake the dim screen first
        flow.endByUser()
        assertEquals(EndReason.USER, state.ending!!.reason)
        flow.restAgain()
        assertEquals(Screen.READY, state.screen)
        assertEquals(at(12, 19, 25), state.pickupMs)
        assertNull(state.ending)

        flow.start()
        runFor(23 * 60_000L) // 19:27: past both the rest end and the pickup
        assertEquals(EndReason.TIME_UP, state.ending!!.reason)
        flow.restAgain()

        assertEquals(at(12, 19, 50), state.pickupMs)
        assertEquals(Screen.READY, state.screen)
    }

    @Test
    fun `leaving and coming back shows the ended screen and does not restart`() {
        flow.start()
        flow.onHidden()
        flow.onVisible()
        setDoor(DoorStatus.CLOSED)
        runFor(5_000)

        assertEquals(Screen.ENDED, state.screen)
        assertEquals(EndReason.APP_LEFT, state.ending!!.reason)
    }
}
