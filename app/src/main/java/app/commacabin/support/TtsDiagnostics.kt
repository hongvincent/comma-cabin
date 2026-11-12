package app.commacabin.support

import app.commacabin.audio.AudioFocusPort
import app.commacabin.audio.FocusChange
import app.commacabin.audio.FocusHandle
import app.commacabin.audio.TtsSlot
import app.commacabin.probe.Cancellable
import app.commacabin.probe.Scheduler

const val TTS_INIT_TIMEOUT_MS = 10_000L
/** From speak() to Done/Stop/Error; a cold ON_DEVICE engine took about 5 s on the emulator. */
const val TTS_SPEAK_TIMEOUT_MS = 15_000L
const val TTS_TEST_TEXT = "픽업 2분 전이에요"

/** EventListener callbacks of the TextToSpeech SDK. */
sealed interface TtsEvent {
    data object Ready : TtsEvent
    data object Start : TtsEvent
    data object Rms : TtsEvent
    data object Done : TtsEvent
    data object Stop : TtsEvent
    data class Error(val type: String) : TtsEvent
}

/** One TextToSpeech object. A released object is never used again; each attempt gets a new one. */
interface TtsEngine {
    fun initialize(ok: () -> Unit, fail: (String) -> Unit)
    fun setKoreanLocale()

    /** Registers one event listener (on the first call) and requests one utterance. Events arrive on the main thread. */
    fun speak(text: String, onEvent: (TtsEvent) -> Unit)
    fun stop()

    /** Removes the event listener (if any), then releases the object. */
    fun release()
}

enum class TtsStatus(val label: String) {
    IDLE("시도 전"), RUNNING("초기화 중"), READY("초기화 성공"), FAILED("초기화 실패"), TIMEOUT("응답 없음")
}

data class TtsUiState(val status: TtsStatus = TtsStatus.IDLE, val attempt: Int = 0, val detail: String = "")

/**
 * ON_DEVICE TextToSpeech check, started by the user: initialization, then (on request) one test sentence.
 * A new attempt, a timeout, close() or the rest flow taking the TTS slot releases the current object, and
 * any later callback for it is ignored.
 */
class TtsDiagnostics(
    private val create: () -> TtsEngine,
    private val scheduler: Scheduler,
    private val slot: TtsSlot,
    private val focus: AudioFocusPort,
    private val log: (String) -> Unit = {},
) {
    var state = TtsUiState()
        private set
    var onState: (TtsUiState) -> Unit = {}

    private var closed = false
    private var attempt = 0
    private var engine: TtsEngine? = null
    private var timer: Cancellable? = null
    /** Held only while the test sentence runs. */
    private var speakFocus: FocusHandle? = null
    private var speakSeq = 0
    private var speakTimer: Cancellable? = null

    fun start() {
        if (closed) return
        releaseEngine()
        val thisAttempt = ++attempt
        log("tts attempt $thisAttempt start (ON_DEVICE)")
        update(TtsUiState(TtsStatus.RUNNING, thisAttempt, "ON_DEVICE 초기화 요청"))
        val created = try {
            create()
        } catch (e: RuntimeException) {
            finish(thisAttempt, TtsStatus.FAILED, "객체 생성 실패: ${e.javaClass.name}: ${e.message}")
            return
        }
        engine = created
        slot.take(this) { onRevoked(thisAttempt) }
        timer = scheduler.schedule(TTS_INIT_TIMEOUT_MS) { onTimeout(thisAttempt) }
        try {
            created.initialize({ onSuccess(thisAttempt) }, { reason -> onFailure(thisAttempt, reason) })
        } catch (e: RuntimeException) {
            onFailure(thisAttempt, "${e.javaClass.name}: ${e.message}")
        }
    }

    fun close() {
        if (closed) return
        closed = true
        releaseEngine()
    }

    /**
     * Speaks [TTS_TEST_TEXT] once with the initialized object: app focus (GAIN_TRANSIENT_MAY_DUCK) first,
     * then speak(). The utterance ends on Done/Stop/Error, or is stopped on a focus loss, [stopSpeech]
     * (screen left), a new attempt or after [TTS_SPEAK_TIMEOUT_MS]. SDK events carry no utterance id, so
     * after any end the object is released (focus abandoned) and the next sentence needs a new initialization.
     */
    fun speakTest() {
        val current = engine ?: return
        if (closed || state.status != TtsStatus.READY || speakFocus != null) return
        val thisAttempt = attempt
        val thisSpeak = ++speakSeq
        speakFocus = try {
            focus.request(transientMayDuck = true) { change -> onSpeakFocus(thisSpeak, change) }
        } catch (e: RuntimeException) {
            log("tts attempt $thisAttempt app focus request failed: ${e.javaClass.name}: ${e.message}")
            null
        }
        log("tts attempt $thisAttempt app focus GAIN_TRANSIENT_MAY_DUCK -> ${if (speakFocus != null) "granted" else "refused"}")
        if (speakFocus == null) return update(state.copy(detail = "앱 오디오 포커스를 얻지 못해 말하지 않았어요"))
        var rms = 0
        val events = mutableListOf<String>()
        log("tts attempt $thisAttempt speak requested")
        update(state.copy(detail = "말하기 요청"))
        speakTimer = scheduler.schedule(TTS_SPEAK_TIMEOUT_MS) { if (thisSpeak == speakSeq) stopSpeech("${TTS_SPEAK_TIMEOUT_MS}ms 동안 끝나지 않아 멈췄어요") }
        try {
            current.speak(TTS_TEST_TEXT) { event ->
                if (!current(thisAttempt) || thisSpeak != speakSeq || speakFocus == null) {
                    return@speak log("tts ignored late event attempt=$thisAttempt speak=$thisSpeak $event")
                }
                if (event == TtsEvent.Rms) {
                    rms++
                    return@speak
                }
                log("tts attempt $thisAttempt event $event (rms so far $rms)")
                events += event.toString()
                val detail = "이벤트: ${events.joinToString(" → ")} (RMS ${rms}회)"
                if (event == TtsEvent.Done || event == TtsEvent.Stop || event is TtsEvent.Error) endUtterance(detail, stop = false)
                else update(state.copy(detail = detail))
            }
        } catch (e: RuntimeException) {
            log("tts attempt $thisAttempt speak failed: ${e.javaClass.name}: ${e.message}")
            endUtterance("말하기 요청 실패: ${e.javaClass.name}", stop = false)
        }
    }

    /** onStop or another reason: stops the test sentence if it is still going. */
    fun stopSpeech(reason: String = "화면을 벗어나 말하기를 멈췄어요") {
        if (speakFocus == null) return
        endUtterance(reason, stop = true)
    }

    /** Stop (if asked), abandon the focus and release the object; the next sentence starts from initialization. */
    private fun endUtterance(detail: String, stop: Boolean) {
        releaseEngine(stopSpeaking = stop)
        log("tts attempt $attempt sentence ended: $detail")
        update(TtsUiState(TtsStatus.IDLE, attempt, "$detail · 다시 말하려면 초기화부터 해 주세요"))
    }

    private fun onSpeakFocus(thisSpeak: Int, change: FocusChange) {
        log("tts attempt $attempt app focus $change")
        if (thisSpeak != speakSeq || speakFocus == null) return
        if (change == FocusChange.LOSS || change == FocusChange.LOSS_TRANSIENT) stopSpeech("오디오 포커스를 잃어 말하기를 멈췄어요")
    }

    /** The current utterance is over: later events of it are ignored and the focus is abandoned. */
    private fun endSpeech() {
        speakSeq++
        speakTimer?.cancel()
        speakTimer = null
        val held = speakFocus ?: return
        speakFocus = null
        runCatching { held.abandon() }
        log("tts attempt $attempt app focus abandoned")
    }

    /** The rest flow took the TextToSpeech slot: release this object now. */
    private fun onRevoked(thisAttempt: Int) {
        if (!current(thisAttempt)) return
        releaseEngine()
        log("tts attempt $thisAttempt released for the rest flow")
        update(TtsUiState(TtsStatus.IDLE, thisAttempt, "휴식 안내가 음성을 쓰도록 해제했어요"))
    }

    private fun onSuccess(thisAttempt: Int) {
        if (!current(thisAttempt)) return log("tts ignored late success attempt=$thisAttempt")
        timer?.cancel()
        timer = null
        try {
            engine?.setKoreanLocale()
        } catch (e: RuntimeException) {
            log("tts attempt $thisAttempt setLocale failed: ${e.javaClass.name}: ${e.message}")
            update(TtsUiState(TtsStatus.READY, thisAttempt, "초기화 성공. 한국어 설정 요청 실패: ${e.javaClass.name}: ${e.message}"))
            return
        }
        log("tts attempt $thisAttempt init success; setLocale(ko-KR) requested")
        update(TtsUiState(TtsStatus.READY, thisAttempt, "초기화 성공. 한국어 설정 요청 완료, 실제 합성 미검증"))
    }

    private fun onFailure(thisAttempt: Int, reason: String) {
        if (!current(thisAttempt)) return log("tts ignored late failure attempt=$thisAttempt $reason")
        finish(thisAttempt, TtsStatus.FAILED, reason)
    }

    private fun onTimeout(thisAttempt: Int) {
        if (!current(thisAttempt)) return
        timer = null
        finish(thisAttempt, TtsStatus.TIMEOUT, "${TTS_INIT_TIMEOUT_MS}ms 동안 응답 없음, 객체 해제")
    }

    private fun finish(thisAttempt: Int, status: TtsStatus, detail: String) {
        releaseEngine()
        log("tts attempt $thisAttempt ${status.name} $detail")
        update(TtsUiState(status, thisAttempt, detail))
    }

    /** Only the newest attempt, while its object is still held, may change the state. */
    private fun current(thisAttempt: Int) = !closed && thisAttempt == attempt && engine != null

    /** [stopSpeaking]: a sentence still running is stopped first (new attempt, close, revoke, forced end). */
    private fun releaseEngine(stopSpeaking: Boolean = true) {
        timer?.cancel()
        timer = null
        if (stopSpeaking && speakFocus != null) runCatching { engine?.stop() }.onFailure { e -> log("tts stop failed: ${e.javaClass.name}") }
        endSpeech()
        engine?.let { runCatching { it.release() }.onFailure { e -> log("tts release failed: ${e.javaClass.name}") } }
        engine = null
        slot.leave(this)
    }

    private fun update(next: TtsUiState) {
        if (closed) return
        state = next
        onState(next)
    }
}
