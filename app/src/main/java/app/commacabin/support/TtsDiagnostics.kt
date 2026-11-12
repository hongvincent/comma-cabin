package app.commacabin.support

import app.commacabin.probe.Cancellable
import app.commacabin.probe.Scheduler

const val TTS_INIT_TIMEOUT_MS = 10_000L

/** One TextToSpeech object. A released object is never used again; each attempt gets a new one. */
interface TtsEngine {
    fun initialize(ok: () -> Unit, fail: (String) -> Unit)
    fun setKoreanLocale()
    fun release()
}

enum class TtsStatus(val label: String) {
    IDLE("시도 전"), RUNNING("초기화 중"), READY("초기화 성공"), FAILED("초기화 실패"), TIMEOUT("응답 없음")
}

data class TtsUiState(val status: TtsStatus = TtsStatus.IDLE, val attempt: Int = 0, val detail: String = "")

/**
 * ON_DEVICE TextToSpeech initialization check, started by the user. It never speaks. A new attempt,
 * a timeout or close() releases the current object, and any later callback for it is ignored.
 */
class TtsDiagnostics(
    private val create: () -> TtsEngine,
    private val scheduler: Scheduler,
    private val log: (String) -> Unit = {},
) {
    var state = TtsUiState()
        private set
    var onState: (TtsUiState) -> Unit = {}

    private var closed = false
    private var attempt = 0
    private var engine: TtsEngine? = null
    private var timer: Cancellable? = null

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

    private fun releaseEngine() {
        timer?.cancel()
        timer = null
        engine?.let { runCatching { it.release() }.onFailure { e -> log("tts release failed: ${e.javaClass.name}") } }
        engine = null
    }

    private fun update(next: TtsUiState) {
        if (closed) return
        state = next
        onState(next)
    }
}
