package app.commacabin.support

import app.commacabin.audio.FocusChange
import app.commacabin.audio.FocusHandle
import app.commacabin.audio.TtsSlot
import app.commacabin.probe.FakeScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeEngine(private val throwOnInit: Boolean = false) : TtsEngine {
    var ok: (() -> Unit)? = null
    var fail: ((String) -> Unit)? = null
    var localeRequests = 0
    var releases = 0

    override fun initialize(ok: () -> Unit, fail: (String) -> Unit) {
        if (throwOnInit) throw SecurityException("need TTS_SERVICE")
        this.ok = ok
        this.fail = fail
    }

    override fun setKoreanLocale() {
        check(releases == 0) { "used after release" }
        localeRequests++
    }

    var events: ((TtsEvent) -> Unit)? = null
    val spoken = mutableListOf<String>()

    override fun speak(text: String, onEvent: (TtsEvent) -> Unit) {
        check(releases == 0) { "used after release" }
        spoken += text
        events = onEvent
    }

    var stops = 0

    override fun stop() {
        stops++
    }

    override fun release() {
        releases++
    }
}

class TtsDiagnosticsTest {
    private val scheduler = FakeScheduler()
    private val engines = mutableListOf<FakeEngine>()
    private var nextThrows = false
    private val slot = TtsSlot()
    private var focusHeld = 0
    private var focusThrows = false
    private var focusListener: ((FocusChange) -> Unit)? = null
    private val tts = TtsDiagnostics({ FakeEngine(nextThrows).also { engines += it } }, scheduler, slot, { _, onChange ->
        if (focusThrows) throw SecurityException("focus")
        focusListener = onChange
        focusHeld++
        FocusHandle { focusHeld-- }
    })

    private fun ready(): FakeEngine {
        tts.start()
        engines.last().ok!!.invoke()
        return engines.last()
    }

    @Test
    fun `success requests Korean once and says synthesis is not verified`() {
        tts.start()
        assertEquals(TtsStatus.RUNNING, tts.state.status)
        engines.single().ok!!.invoke()

        assertEquals(TtsStatus.READY, tts.state.status)
        assertEquals("초기화 성공. 한국어 설정 요청 완료, 실제 합성 미검증", tts.state.detail)
        assertEquals(1, engines.single().localeRequests)
        assertEquals(0, engines.single().releases)

        scheduler.advance(TTS_INIT_TIMEOUT_MS * 2)
        assertEquals(TtsStatus.READY, tts.state.status)
    }

    @Test
    fun `failure is shown with its reason and the object is released`() {
        tts.start()
        engines.single().fail!!.invoke("ErrorType.ON_DEVICE_ENGINE_ERROR")

        assertEquals(TtsStatus.FAILED, tts.state.status)
        assertEquals("ErrorType.ON_DEVICE_ENGINE_ERROR", tts.state.detail)
        assertEquals(1, engines.single().releases)
        assertEquals(0, engines.single().localeRequests)
    }

    @Test
    fun `no answer times out, releases, and a late answer is ignored`() {
        tts.start()
        scheduler.advance(TTS_INIT_TIMEOUT_MS)
        assertEquals(TtsStatus.TIMEOUT, tts.state.status)
        assertEquals(1, engines.single().releases)

        val stateAfterTimeout = tts.state
        engines.single().ok!!.invoke()
        engines.single().fail!!.invoke("ErrorType.TIMEOUT")
        assertEquals(stateAfterTimeout, tts.state)
        assertEquals(0, engines.single().localeRequests)
    }

    @Test
    fun `a new attempt releases the old object, uses a new one, and ignores the old callback`() {
        tts.start()
        val first = engines.single()
        tts.start()
        val second = engines.last()

        assertEquals(2, engines.size)
        assertEquals(1, first.releases)
        first.ok!!.invoke()
        assertEquals(TtsStatus.RUNNING, tts.state.status)
        assertEquals(0, first.localeRequests)

        second.ok!!.invoke()
        assertEquals(TtsStatus.READY, tts.state.status)
        assertEquals(2, tts.state.attempt)
        assertEquals(1, second.localeRequests)

        // The old attempt's timer must not end the new one.
        scheduler.advance(TTS_INIT_TIMEOUT_MS)
        assertEquals(TtsStatus.READY, tts.state.status)
    }

    @Test
    fun `after close the object is released and later callbacks or starts change nothing`() {
        tts.start()
        val engine = engines.single()
        val stateBeforeClose = tts.state
        tts.close()
        assertEquals(1, engine.releases)

        engine.ok!!.invoke()
        scheduler.advance(TTS_INIT_TIMEOUT_MS)
        tts.start()

        assertEquals(stateBeforeClose, tts.state)
        assertEquals(1, engines.size)
        assertEquals(0, engine.localeRequests)
    }

    @Test
    fun `a synchronous throw from initialize is a failure, not a success`() {
        nextThrows = true
        tts.start()

        assertEquals(TtsStatus.FAILED, tts.state.status)
        assertTrue(tts.state.detail.contains("SecurityException"))
        assertEquals(1, engines.single().releases)
        assertFalse(tts.state.detail.contains("성공"))
    }

    @Test
    fun `the test sentence holds app focus until its end event, then the object is released`() {
        val engine = ready()
        tts.speakTest()
        assertEquals(listOf(TTS_TEST_TEXT), engine.spoken)
        assertEquals(1, focusHeld)

        engine.events!!(TtsEvent.Start)
        engine.events!!(TtsEvent.Done)

        assertEquals(0, focusHeld)
        assertEquals(1, engine.releases)
        assertEquals(0, engine.stops)
        assertEquals(TtsStatus.IDLE, tts.state.status)
        assertTrue(tts.state.detail.startsWith("이벤트: Start → Done (RMS 0회)"))
        tts.speakTest() // no utterance id in SDK events: a new sentence needs a new object
        assertEquals(1, engine.spoken.size)
    }

    @Test
    fun `the rest flow taking the TTS slot stops a running test sentence`() {
        val engine = ready()
        tts.speakTest()

        slot.take("rest") {}

        assertEquals(1, engine.stops)
        assertEquals(1, engine.releases)
        assertEquals(0, focusHeld)
        assertEquals(TtsStatus.IDLE, tts.state.status)
    }

    @Test
    fun `a focus request that throws means no speech`() {
        val engine = ready()
        focusThrows = true

        tts.speakTest()

        assertTrue(engine.spoken.isEmpty())
        assertEquals(0, focusHeld)
        assertEquals("앱 오디오 포커스를 얻지 못해 말하지 않았어요", tts.state.detail)
    }

    @Test
    fun `focus loss, leaving the screen or no end event stop the test sentence and release the object`() {
        var engine = ready()
        tts.speakTest()
        val late = engine.events!!
        focusListener!!(FocusChange.LOSS_TRANSIENT)
        assertEquals(listOf(1, 1, 0), listOf(engine.stops, engine.releases, focusHeld))
        late(TtsEvent.Done)
        assertTrue(tts.state.detail.startsWith("오디오 포커스를 잃어 말하기를 멈췄어요"))

        engine = ready()
        tts.speakTest()
        tts.stopSpeech()
        assertEquals(listOf(1, 1, 0), listOf(engine.stops, engine.releases, focusHeld))

        engine = ready()
        tts.speakTest()
        scheduler.advance(TTS_SPEAK_TIMEOUT_MS)
        assertEquals(listOf(1, 1, 0), listOf(engine.stops, engine.releases, focusHeld))
        late(TtsEvent.Done)
        assertEquals(TtsStatus.IDLE, tts.state.status)
    }

    @Test
    fun `a new attempt stops a running test sentence before releasing its object`() {
        val engine = ready()
        tts.speakTest()

        tts.start()

        assertEquals(1, engine.stops)
        assertEquals(1, engine.releases)
        assertEquals(0, focusHeld)
    }
}
