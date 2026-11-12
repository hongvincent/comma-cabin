package app.commacabin.sdk

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.os.Handler
import app.commacabin.support.TtsEngine
import app.commacabin.support.TtsEvent
import java.util.Locale

/**
 * Pleos TextToSpeech in ON_DEVICE mode only. No registerApp, server mode or audio attribute override.
 * One event listener per object, added before the first speak() and removed before release(). Callbacks go to [main].
 */
class PleosTtsEngine(context: Context, private val main: Handler) : TtsEngine {
    private val tts = TextToSpeech(context, Mode.ON_DEVICE)
    private var listener: EventListener? = null
    private var onEvent: (TtsEvent) -> Unit = {}

    override fun initialize(ok: () -> Unit, fail: (String) -> Unit) {
        tts.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                main.post { ok() }
            }

            override fun onFailure(errorType: ErrorType) {
                main.post { fail("ErrorType.${errorType.name}") }
            }
        })
    }

    override fun setKoreanLocale() = tts.setLocale(Locale.KOREA)

    override fun speak(text: String, onEvent: (TtsEvent) -> Unit) {
        this.onEvent = onEvent
        if (listener == null) {
            val created = object : EventListener {
                override fun onReady() = post(TtsEvent.Ready)
                override fun onStart() = post(TtsEvent.Start)
                override fun onUpdatedRms(rms: Double) = post(TtsEvent.Rms)
                override fun onDone() = post(TtsEvent.Done)
                override fun onStop() = post(TtsEvent.Stop)
                override fun onError(errorType: ErrorType) = post(TtsEvent.Error("ErrorType.${errorType.name}"))
            }
            tts.addEventListener(created)
            listener = created
        }
        tts.speak(text, true)
    }

    override fun stop() = tts.stop()

    override fun release() {
        listener?.let { tts.removeEventListener(it) }
        listener = null
        onEvent = {}
        tts.release()
    }

    /** The handler is fixed when the SDK delivers the event, not when the main thread runs it. */
    private fun post(event: TtsEvent) {
        val handler = onEvent
        main.post { handler(event) }
    }
}
