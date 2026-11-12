package app.commacabin.rest

import app.commacabin.audio.AudioFocusPort
import app.commacabin.audio.ONBOARDING_VERSION
import app.commacabin.audio.RestAudio
import app.commacabin.audio.RestAudioPort
import app.commacabin.audio.RestSettings
import app.commacabin.audio.SoundLevel
import app.commacabin.audio.SoundFactory
import app.commacabin.audio.SoundMode
import app.commacabin.audio.SoundPlayer
import app.commacabin.audio.TtsSlot
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

/** Records what the flow asks of the audio side. [refuse] makes begin/setSound refuse with that reason. */
class FakeAudio : RestAudioPort {
    var refuse: String? = null
    /** Runs inside begin(), e.g. a vehicle change while the audio is being prepared. */
    var duringBegin: () -> Unit = {}
    val calls = mutableListOf<String>()

    override fun begin(session: Int, endElapsedMs: Long, sound: SoundMode, level: SoundLevel): String? {
        calls += "begin $session $sound $level"
        duringBegin()
        return refuse
    }

    override fun setSound(session: Int, sound: SoundMode): String? {
        calls += "sound $session $sound"
        return refuse
    }

    override fun setLevel(level: SoundLevel) {
        calls += "level $level"
    }

    override fun end(session: Int, normal: Boolean) {
        calls += "end $session normal=$normal"
    }
}

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
    private var settings = RestSettings(onboardingVersion = ONBOARDING_VERSION)
    private val saved = mutableListOf<RestSettings>()
    private val audio = FakeAudio()
    private val log = mutableListOf<String>()

    private val flow by lazy {
        RestFlow(
            scheduler = scheduler,
            wallClock = { wallBase + scheduler.now + wallShift },
            elapsedClock = { 5_000_000 + scheduler.now },
            zone = { zone },
            parking = { parking },
            door = { door },
            settings = settings,
            saveSettings = { saved += it },
            audio = audio,
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
    fun `onboarding is shown once and only its version is stored`() {
        settings = RestSettings(onboardingVersion = 1) // the silent STEP 5 onboarding was seen
        assertEquals(Screen.ONBOARDING, state.screen)
        flow.start()
        assertEquals(Screen.ONBOARDING, state.screen)

        flow.dismissOnboarding()

        assertEquals(Screen.READY, state.screen)
        assertEquals(listOf(RestSettings(onboardingVersion = ONBOARDING_VERSION)), saved)
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

    @Test
    fun `the audio side is asked only after the vehicle and time checks, and its refusal keeps the ready screen`() {
        settings = RestSettings(onboardingVersion = ONBOARDING_VERSION, sound = SoundMode.ON)
        door = DoorState(DoorStatus.OPEN)
        flow.start()
        assertTrue(audio.calls.isEmpty())

        door = DoorState(DoorStatus.CLOSED)
        audio.refuse = "focus refused"
        flow.start()
        assertEquals(Screen.READY, state.screen)
        assertEquals("시작하지 않았어요. focus refused", state.notice)

        audio.refuse = null
        flow.start()
        assertEquals(Screen.RESTING, state.screen)
        assertEquals(1, state.session!!.id) // a refused start does not use up a session number
        assertEquals(listOf("begin 1 ON MEDIUM", "begin 1 ON MEDIUM"), audio.calls)
    }

    @Test
    fun `a refused sound switch while resting keeps the rest quiet and is not remembered`() {
        flow.start()
        audio.refuse = "busy"
        flow.setSound(SoundMode.ON)
        assertEquals(SoundMode.QUIET, state.sound)
        assertEquals("busy", state.notice)
        assertTrue(saved.none { it.sound == SoundMode.ON })

        runFor(5 * 60_000L) // nothing turns it on later by itself
        assertEquals(SoundMode.QUIET, state.sound)
        assertEquals(1, audio.calls.count { it.startsWith("sound") })

        audio.refuse = null
        flow.setSound(SoundMode.ON)
        assertEquals(SoundMode.ON, state.sound)
        assertEquals(SoundMode.ON, saved.last().sound)
    }

    @Test
    fun `sound choice and level are remembered from the ready screen`() {
        flow.setSound(SoundMode.ON)
        flow.setLevel(SoundLevel.HIGH)

        assertEquals(RestSettings(ONBOARDING_VERSION, SoundMode.ON, SoundLevel.HIGH), saved.last())
        assertEquals(listOf("level HIGH"), audio.calls)
        flow.start()
        assertEquals("begin 1 ON HIGH", audio.calls.last())
    }

    @Test
    fun `a permanent audio loss ends the rest once and the audio side hears each end once`() {
        flow.start()
        flow.onAudioLost(1)
        flow.onAudioLost(1)
        assertEquals(EndReason.AUDIO_LOST, state.ending!!.reason)

        flow.restAgain()
        flow.start()
        runFor(25 * 60_000L)

        assertEquals(listOf("end 1 normal=false", "end 2 normal=true"), audio.calls.filter { it.startsWith("end") })
    }

    @Test
    fun `a sound failure of the current session makes the rest quiet and keeps the stored choice`() {
        settings = RestSettings(onboardingVersion = ONBOARDING_VERSION, sound = SoundMode.ON)
        flow.start()

        flow.onSoundFailed(2, "other session")
        assertEquals(SoundMode.ON, state.sound)

        flow.onSoundFailed(1, "휴식 소리를 재생하지 못했어요. 조용히 쉬고 있어요")
        assertEquals(SoundMode.QUIET, state.sound)
        assertEquals("휴식 소리를 재생하지 못했어요. 조용히 쉬고 있어요", state.notice)
        assertTrue(saved.isEmpty())

        flow.setSound(SoundMode.ON) // the user can try again
        assertEquals("sound 1 ON", audio.calls.last())
    }

    @Test
    fun `listenTo connects the audio callbacks exactly as the activity uses them`() {
        val real = RestAudio(
            scheduler, { 5_000_000 + scheduler.now }, AudioFocusPort { _, _ -> null },
            object : SoundFactory {
                override fun restLoop(): SoundPlayer = error("unused")
                override fun chime(): SoundPlayer = error("unused")
                override val chimeDurationMs = 0L
            },
            { error("unused") }, TtsSlot(),
        )
        settings = RestSettings(onboardingVersion = ONBOARDING_VERSION, sound = SoundMode.ON)
        flow.listenTo(real)
        flow.start()

        real.onSoundFailed(1, "failed")
        assertEquals(SoundMode.QUIET, state.sound)
        real.onFocusLost(1)
        assertEquals(EndReason.AUDIO_LOST, state.ending!!.reason)
    }

    @Test
    fun `a change while the audio is prepared cancels the start and releases the audio`() {
        audio.duringBegin = { parking = ParkingState(GearState.NOT_PARK, false) }
        flow.start()

        assertEquals(Screen.READY, state.screen)
        assertEquals("시작하지 않았어요. ${Block.NOT_PARKED.text}", state.notice)
        assertEquals(listOf("begin 1 QUIET MEDIUM", "end 1 normal=false"), audio.calls)

        audio.duringBegin = {}
        parking = parked
        flow.start()
        assertEquals(1, state.session!!.id)
    }

    // --- STEP 8: lighting hooks and settings ---

    @Test
    fun `each session reaches the lighting once at start and once at its end, after its sound was told to stop`() {
        val events = mutableListOf<String>()
        flow.onSessionStarted = { id, on -> events += "start $id $on" }
        flow.onSessionEnded = { id, reason -> events += "end $id $reason after ${audio.calls.last()}" }
        flow.start()
        runFor(5_000)
        flow.endByUser()
        flow.endByUser()
        flow.onHidden()

        assertEquals(listOf("start 1 true", "end 1 USER after end 1 normal=false"), events)
    }

    @Test
    fun `the lighting choice is only remembered on the ready screen and passed at the next start`() {
        var passed: Boolean? = null
        flow.onSessionStarted = { _, on -> passed = on }
        flow.setLighting(false)
        assertFalse(state.lighting)
        assertEquals(false, saved.last().lighting)
        assertTrue(audio.calls.isEmpty())

        flow.start()
        flow.setLighting(true) // not while resting
        assertEquals(false, passed)
        assertFalse(state.lighting)
    }

    @Test
    fun `pressing the quiet choice already shown after a sound failure stores quiet`() {
        settings = RestSettings(onboardingVersion = ONBOARDING_VERSION, sound = SoundMode.ON)
        flow.start()
        flow.onSoundFailed(1, "failed")
        assertTrue(saved.isEmpty())

        flow.setSound(SoundMode.QUIET)
        assertEquals(SoundMode.QUIET, saved.single().sound)
        assertEquals(SoundMode.QUIET, state.sound)
        flow.setSound(SoundMode.QUIET)
        assertEquals(1, saved.size)
    }

    @Test
    fun `climate choices are only remembered on the ready screen and carried by the state at the start`() {
        flow.setFan(false)
        flow.setTempC(22.25f)
        flow.setTempC(Float.NaN)
        assertEquals(false, saved.last().fan)
        assertEquals(22.25f, saved.last().tempC)
        assertTrue(audio.calls.isEmpty())

        var seen: RestState? = null
        flow.onSessionStarted = { _, _ -> seen = flow.state }
        flow.start()
        flow.setTemp(false) // not while resting
        assertEquals(false, seen!!.fan)
        assertEquals(true, seen!!.temp)
        assertEquals(22.25f, seen!!.tempC)
        assertTrue(state.temp)
    }
}
