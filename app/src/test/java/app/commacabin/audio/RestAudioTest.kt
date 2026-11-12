package app.commacabin.audio

import app.commacabin.probe.FakeScheduler
import app.commacabin.support.TtsEngine
import app.commacabin.support.TtsEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakePlayer(val kind: String) : SoundPlayer {
    val events = mutableListOf<String>()
    var playing = false
    var gain = -1f
    var lastEnvelope: Envelope? = null
    var released = false
    var failPlay = false
    var failVolume = false

    override fun play() {
        check(!released) { "played after release" }
        if (failPlay) throw IllegalStateException("play")
        playing = true
        events += "play"
    }

    override fun pause() {
        playing = false
        events += "pause"
    }

    override fun setVolume(volume: Float) {
        if (failVolume) throw IllegalStateException("setVolume returned -3")
        gain = volume
        events += "volume $volume"
    }

    override fun setEnvelope(envelope: Envelope) {
        lastEnvelope = envelope
        events += "envelope ${envelope.durationMs} ${envelope.volumes}"
    }

    override fun release() {
        released = true
        playing = false
        events += "release"
    }
}

private class FakeSounds : SoundFactory {
    val created = mutableListOf<FakePlayer>()
    var failLoop = false
    var failChime = false
    var chimeFailsToPlay = false
    var loopFailsToPlay = false
    /** Runs while the loop is being prepared, e.g. to let time pass. */
    var whilePreparing: () -> Unit = {}
    val loops get() = created.filter { it.kind == "loop" }
    val chimes get() = created.filter { it.kind == "chime" }

    override fun restLoop(): SoundPlayer {
        if (failLoop) throw IllegalStateException("track")
        whilePreparing()
        return FakePlayer("loop").also {
            it.failPlay = loopFailsToPlay
            created += it
        }
    }

    override fun chime(): SoundPlayer {
        if (failChime) throw IllegalStateException("track")
        return FakePlayer("chime").also {
            it.failPlay = chimeFailsToPlay
            created += it
        }
    }

    override val chimeDurationMs = 1_800L
}

private class FakeFocus : AudioFocusPort {
    class Request(val mayDuck: Boolean, val granted: Boolean, val onChange: (FocusChange) -> Unit) {
        var abandoned = false
    }

    var grant = true
    var throwOnRequest = false
    val requests = mutableListOf<Request>()
    val held get() = requests.filter { it.granted && !it.abandoned }

    override fun request(transientMayDuck: Boolean, onChange: (FocusChange) -> Unit): FocusHandle? {
        if (throwOnRequest) throw SecurityException("focus")
        val r = Request(transientMayDuck, grant, onChange)
        requests += r
        return if (grant) FocusHandle { r.abandoned = true } else null
    }
}

private class FakeVoice : TtsEngine {
    var ok: (() -> Unit)? = null
    var fail: ((String) -> Unit)? = null
    var locale = 0
    val spoken = mutableListOf<String>()
    var onEvent: ((TtsEvent) -> Unit)? = null
    var stops = 0
    var released = false

    override fun initialize(ok: () -> Unit, fail: (String) -> Unit) {
        this.ok = ok
        this.fail = fail
    }

    override fun setKoreanLocale() {
        locale++
    }

    override fun speak(text: String, onEvent: (TtsEvent) -> Unit) {
        check(!released) { "spoke after release" }
        spoken += text
        this.onEvent = onEvent
    }

    override fun stop() {
        stops++
    }

    override fun release() {
        released = true
    }
}

class RestAudioTest {
    private val scheduler = FakeScheduler()
    private val focus = FakeFocus()
    private val sounds = FakeSounds()
    private val voices = mutableListOf<FakeVoice>()
    private val slot = TtsSlot()
    private val lost = mutableListOf<Int>()
    private val failed = mutableListOf<Int>()
    private val audio = RestAudio(scheduler, { scheduler.now }, focus, sounds, { FakeVoice().also { voices += it } }, slot).also {
        it.vehicleOk = { vehicleOk }
        it.onFocusLost = { id -> lost += id }
        it.onSoundFailed = { id, _ -> failed += id }
    }
    private var vehicleOk = true

    private val minute = 60_000L
    private val loop get() = sounds.loops.last()
    private val voice get() = voices.last()

    private fun beginOn(end: Long = 10 * minute, session: Int = 1): String? = audio.begin(session, end, SoundMode.ON, SoundLevel.MEDIUM)

    @Test
    fun `sound on needs GAIN and a refusal starts nothing`() {
        focus.grant = false

        assertNotNull(beginOn())
        assertTrue(sounds.created.isEmpty())
        assertTrue(voices.isEmpty())
        assertFalse(focus.requests.single().mayDuck)

        focus.grant = true
        sounds.failLoop = true
        assertNotNull(beginOn())
        assertTrue(focus.held.isEmpty())
    }

    @Test
    fun `quiet asks for no focus and plays nothing, but prepares the voice`() {
        assertNull(audio.begin(1, 10 * minute, SoundMode.QUIET, SoundLevel.MEDIUM))
        voice.ok!!()

        assertTrue(focus.requests.isEmpty())
        assertTrue(sounds.created.isEmpty())
        assertEquals(1, voice.locale)
    }

    @Test
    fun `transient loss pauses, duck lowers, and regain resumes only with the vehicle still ok`() {
        beginOn()
        val f = focus.requests.single()
        assertTrue(loop.playing)
        assertEquals(SoundLevel.MEDIUM.gain, loop.gain)

        f.onChange(FocusChange.LOSS_TRANSIENT)
        assertFalse(loop.playing)
        assertTrue(audio.ui.pausedByOther)
        vehicleOk = false
        f.onChange(FocusChange.GAIN)
        assertFalse(loop.playing)

        f.onChange(FocusChange.LOSS_TRANSIENT)
        vehicleOk = true
        f.onChange(FocusChange.GAIN)
        assertTrue(loop.playing)

        f.onChange(FocusChange.LOSS_TRANSIENT_CAN_DUCK)
        assertEquals(SoundLevel.MEDIUM.gain * DUCK_FACTOR, loop.gain)
        f.onChange(FocusChange.GAIN)
        assertEquals(SoundLevel.MEDIUM.gain, loop.gain)
        assertTrue(lost.isEmpty())
    }

    @Test
    fun `a new sound gets level volume and the end envelope before its first play`() {
        beginOn(end = 10 * minute)

        assertEquals(listOf("volume ${SoundLevel.MEDIUM.gain}", "envelope 600000 [1.0, 1.0, 0.0]", "play"), loop.events)
        assertEquals(listOf(0f, 0.9f, 1f), loop.lastEnvelope!!.times)
    }

    @Test
    fun `regain in the last minute resumes with the envelope of that moment, set before play`() {
        beginOn(end = 5 * minute)
        val f = focus.requests.single()
        scheduler.advance(4 * minute + 30_000)
        f.onChange(FocusChange.LOSS_TRANSIENT)
        scheduler.advance(15_000)
        f.onChange(FocusChange.GAIN)

        assertEquals(listOf("volume ${SoundLevel.MEDIUM.gain}", "envelope 15000 [0.25, 0.0]", "play"), loop.events.takeLast(3))
        assertEquals(SoundLevel.MEDIUM.gain, loop.gain) // the fade is in the envelope, not multiplied into the volume
    }

    @Test
    fun `end envelope holds full volume until the last minute and ends at zero`() {
        assertEquals(Envelope(600_000, listOf(0f, 0.9f, 1f), listOf(1f, 1f, 0f)), endEnvelope(600_000, 60_000))
        assertEquals(Envelope(60_000, listOf(0f, 1f), listOf(1f, 0f)), endEnvelope(60_000, 60_000))
        assertEquals(Envelope(30_000, listOf(0f, 1f), listOf(0.5f, 0f)), endEnvelope(30_000, 60_000))
    }

    @Test
    fun `the sound stops at its end time on its own timer and never plays again`() {
        beginOn(end = 5 * minute)
        val f = focus.requests.single()
        scheduler.advance(5 * minute - 1)
        assertFalse(loop.released)

        scheduler.advance(1)
        assertTrue(loop.released)
        assertFalse(audio.ui.playing)

        f.onChange(FocusChange.LOSS_TRANSIENT)
        f.onChange(FocusChange.GAIN)
        assertNotNull(audio.setSound(1, SoundMode.ON))
        assertEquals(1, sounds.loops.size)
        assertFalse(sounds.loops.any { it.playing })
    }

    @Test
    fun `a regain after the session ended does not play`() {
        beginOn()
        val f = focus.requests.single()
        f.onChange(FocusChange.LOSS_TRANSIENT)
        audio.end(1, normal = false)

        f.onChange(FocusChange.GAIN)

        assertFalse(sounds.loops.any { it.playing })
        assertTrue(focus.held.isEmpty())
    }

    @Test
    fun `a permanent loss stops at once and ends the rest through the flow`() {
        beginOn()
        focus.requests.single().onChange(FocusChange.LOSS)

        assertEquals(listOf(1), lost)
        assertTrue(loop.released)
        assertTrue(focus.held.isEmpty())
    }

    @Test
    fun `turning the sound off releases player and focus, turning it on again may be refused and stays off`() {
        beginOn()
        val first = loop
        assertNull(audio.setSound(1, SoundMode.QUIET))
        assertTrue(first.released)
        assertTrue(focus.held.isEmpty())

        focus.grant = false
        assertNotNull(audio.setSound(1, SoundMode.ON))
        assertEquals(1, sounds.loops.size)
        scheduler.advance(5 * minute)
        assertEquals(1, sounds.loops.size)

        focus.grant = true
        assertNull(audio.setSound(1, SoundMode.ON))
        assertTrue(loop.playing)
    }

    @Test
    fun `callbacks of an older focus request change nothing`() {
        beginOn()
        val old = focus.requests.single()
        audio.setSound(1, SoundMode.QUIET)
        audio.setSound(1, SoundMode.ON)

        old.onChange(FocusChange.LOSS_TRANSIENT)
        old.onChange(FocusChange.LOSS)

        assertTrue(loop.playing)
        assertTrue(lost.isEmpty())
    }

    @Test
    fun `level changes apply to the playing sound`() {
        beginOn()
        audio.setLevel(SoundLevel.HIGH)

        assertEquals(SoundLevel.HIGH.gain, loop.gain)
    }

    @Test
    fun `normal end with sound on keeps the focus for one chime and one voice line, then releases everything`() {
        beginOn()
        voice.ok!!()
        audio.end(1, normal = true)
        audio.end(1, normal = true)

        assertEquals(1, focus.requests.size)
        assertTrue(sounds.chimes.single().playing)
        assertTrue(voice.spoken.isEmpty())
        scheduler.advance(sounds.chimeDurationMs + CHIME_GAP_MS)
        assertTrue(sounds.chimes.single().released)
        assertEquals(listOf(ANNOUNCE_TEXT), voice.spoken)

        voice.onEvent!!(TtsEvent.Start)
        assertEquals(Announce.RUNNING, audio.ui.announce)
        voice.onEvent!!(TtsEvent.Done)

        assertEquals(Announce.VOICE, audio.ui.announce)
        assertTrue(focus.held.isEmpty())
        assertTrue(voice.released)
        assertEquals(1, voice.spoken.size)
    }

    @Test
    fun `normal end when quiet asks a duckable focus, and a refusal means screen only`() {
        audio.begin(1, 10 * minute, SoundMode.QUIET, SoundLevel.MEDIUM)
        voice.ok!!()
        focus.grant = false

        audio.end(1, normal = true)

        assertTrue(focus.requests.single().mayDuck)
        assertTrue(sounds.chimes.isEmpty())
        assertEquals(Announce.SCREEN_ONLY, audio.ui.announce)
        assertTrue(voice.released)
        assertTrue(voice.spoken.isEmpty())
    }

    @Test
    fun `voice problems fall back to the chime only`() {
        audio.begin(1, 10 * minute, SoundMode.QUIET, SoundLevel.MEDIUM)
        voice.fail!!("ErrorType.ON_DEVICE_ENGINE_ERROR")
        audio.end(1, normal = true)
        scheduler.advance(sounds.chimeDurationMs + CHIME_GAP_MS)
        assertEquals(Announce.CHIME_ONLY, audio.ui.announce)
        assertTrue(focus.held.isEmpty())

        audio.begin(2, scheduler.now + 10 * minute, SoundMode.QUIET, SoundLevel.MEDIUM)
        voice.ok!!()
        audio.end(2, normal = true)
        scheduler.advance(sounds.chimeDurationMs + CHIME_GAP_MS)
        voice.onEvent!!(TtsEvent.Error("ErrorType.AUDIO_ERROR"))
        assertEquals(Announce.CHIME_ONLY, audio.ui.announce)
        assertTrue(focus.held.isEmpty())

        audio.begin(3, scheduler.now + 10 * minute, SoundMode.QUIET, SoundLevel.MEDIUM)
        audio.end(3, normal = true) // voice never answered its initialization
        scheduler.advance(sounds.chimeDurationMs + CHIME_GAP_MS)
        assertEquals(Announce.CHIME_ONLY, audio.ui.announce)
    }

    @Test
    fun `a chime that cannot play means screen only and no voice`() {
        audio.begin(1, 10 * minute, SoundMode.QUIET, SoundLevel.MEDIUM)
        voice.ok!!()
        sounds.failChime = true

        audio.end(1, normal = true)
        scheduler.advance(sounds.chimeDurationMs + CHIME_GAP_MS)

        assertEquals(Announce.SCREEN_ONLY, audio.ui.announce)
        assertTrue(voice.spoken.isEmpty())
        assertTrue(focus.held.isEmpty())
    }

    @Test
    fun `stops, user ends and leaving play no chime or voice`() {
        beginOn()
        voice.ok!!()

        audio.end(1, normal = false)
        scheduler.advance(10 * minute)

        assertTrue(sounds.chimes.isEmpty())
        assertTrue(voice.spoken.isEmpty())
        assertTrue(voice.released)
        assertTrue(focus.held.isEmpty())
        assertEquals(Announce.NONE, audio.ui.announce)
    }

    @Test
    fun `a new rest cancels the old announcement and its late voice events`() {
        audio.begin(1, 10 * minute, SoundMode.QUIET, SoundLevel.MEDIUM)
        val first = voice
        first.ok!!()
        audio.end(1, normal = true)
        scheduler.advance(sounds.chimeDurationMs + CHIME_GAP_MS)
        val late = first.onEvent!!

        beginOn(end = scheduler.now + 10 * minute, session = 2)
        late(TtsEvent.Done)

        assertTrue(first.released)
        assertEquals(1, first.stops)
        assertEquals(Announce.NONE, audio.ui.announce)
        assertTrue(loop.playing)
        assertEquals(2, voices.size)
        assertEquals(1, focus.held.size)
    }

    @Test
    fun `stopAll on leaving cancels everything and nothing plays afterwards`() {
        beginOn()
        voice.ok!!()
        audio.end(1, normal = true)

        audio.stopAll()
        scheduler.advance(10 * minute)

        assertTrue(sounds.created.all { it.released })
        assertTrue(voice.spoken.isEmpty())
        assertTrue(focus.held.isEmpty())
        assertEquals(AudioUi(), audio.ui)
    }

    @Test
    fun `one TextToSpeech object at a time across the diagnostic and the rest voice`() {
        var diagnosticReleased = false
        slot.take("diagnostic") { diagnosticReleased = true }

        audio.begin(1, 10 * minute, SoundMode.QUIET, SoundLevel.MEDIUM)
        assertTrue(diagnosticReleased)
        voice.ok!!()

        audio.end(1, normal = true)
        scheduler.advance(sounds.chimeDurationMs + CHIME_GAP_MS)
        slot.take("diagnostic") {}

        assertTrue(voice.released)
        assertEquals(Announce.CHIME_ONLY, audio.ui.announce)
        assertTrue(focus.held.isEmpty())
    }

    @Test
    fun `a refused regain does not show the sound as playing`() {
        beginOn()
        val f = focus.requests.single()
        f.onChange(FocusChange.LOSS_TRANSIENT)
        vehicleOk = false

        f.onChange(FocusChange.GAIN)

        assertFalse(loop.playing)
        assertEquals(AudioUi(playing = false, pausedByOther = true), audio.ui)
    }

    @Test
    fun `a sound that is ready only after the end time is released without playing`() {
        audio.begin(1, 5 * minute, SoundMode.QUIET, SoundLevel.MEDIUM)
        scheduler.advance(5 * minute - 1_000)
        sounds.whilePreparing = { scheduler.now += 2_000 } // preparing the track takes us past the end

        assertNotNull(audio.setSound(1, SoundMode.ON))

        assertTrue(loop.released)
        assertFalse(loop.events.contains("play"))
        assertTrue(focus.held.isEmpty())
        assertFalse(audio.ui.playing)
    }

    @Test
    fun `a player that fails to play is released with its focus and the start is refused`() {
        sounds.loopFailsToPlay = true

        assertNotNull(beginOn())

        assertTrue(loop.released)
        assertTrue(focus.held.isEmpty())
        assertEquals(AudioUi(), audio.ui)
        assertTrue(voices.isEmpty())
    }

    @Test
    fun `a focus request that throws counts as refused`() {
        focus.throwOnRequest = true
        assertNotNull(beginOn())
        assertTrue(sounds.created.isEmpty())

        audio.begin(2, 10 * minute, SoundMode.QUIET, SoundLevel.MEDIUM)
        voice.ok!!()
        audio.end(2, normal = true)
        assertEquals(Announce.SCREEN_ONLY, audio.ui.announce)
        assertTrue(voice.released)
    }

    @Test
    fun `a chime that fails to play is released and the announcement is screen only`() {
        audio.begin(1, 10 * minute, SoundMode.QUIET, SoundLevel.MEDIUM)
        voice.ok!!()
        sounds.chimeFailsToPlay = true

        audio.end(1, normal = true)
        scheduler.advance(sounds.chimeDurationMs + CHIME_GAP_MS)

        assertTrue(sounds.chimes.single().released)
        assertEquals(Announce.SCREEN_ONLY, audio.ui.announce)
        assertTrue(voice.spoken.isEmpty())
        assertTrue(focus.held.isEmpty())
    }

    @Test
    fun `a failing volume call stops the sound and tells the flow`() {
        beginOn()
        loop.failVolume = true

        audio.setLevel(SoundLevel.HIGH)

        assertEquals(listOf(1), failed)
        assertTrue(loop.released)
        assertTrue(focus.held.isEmpty())
        assertFalse(audio.ui.playing)
    }

    @Test
    fun `a transient loss during the chime cancels the announcement and a later regain starts no voice`() {
        audio.begin(1, 10 * minute, SoundMode.QUIET, SoundLevel.MEDIUM)
        voice.ok!!()
        audio.end(1, normal = true)
        val f = focus.requests.single()
        assertTrue(sounds.chimes.single().playing)

        f.onChange(FocusChange.LOSS_TRANSIENT)
        scheduler.advance(sounds.chimeDurationMs + CHIME_GAP_MS)
        f.onChange(FocusChange.GAIN)

        assertTrue(voice.spoken.isEmpty())
        assertTrue(sounds.chimes.single().released)
        assertEquals(Announce.SCREEN_ONLY, audio.ui.announce)
        assertTrue(focus.held.isEmpty())
    }

    @Test
    fun `losing focus while the voice speaks stops it`() {
        beginOn()
        voice.ok!!()
        audio.end(1, normal = true)
        scheduler.advance(sounds.chimeDurationMs + CHIME_GAP_MS)
        assertEquals(listOf(ANNOUNCE_TEXT), voice.spoken)

        focus.requests.single().onChange(FocusChange.LOSS_TRANSIENT)

        assertEquals(1, voice.stops)
        assertTrue(voice.released)
        assertEquals(Announce.CHIME_ONLY, audio.ui.announce)
        assertTrue(focus.held.isEmpty())
    }
}
