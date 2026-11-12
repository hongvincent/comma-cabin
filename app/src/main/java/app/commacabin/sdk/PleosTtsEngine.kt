package app.commacabin.sdk

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.os.Handler
import app.commacabin.support.TtsEngine
import java.util.Locale

/** Pleos TextToSpeech in ON_DEVICE mode only. No speak, registerApp or server mode. Callbacks go to [main]. */
class PleosTtsEngine(context: Context, private val main: Handler) : TtsEngine {
    private val tts = TextToSpeech(context, Mode.ON_DEVICE)

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

    override fun release() = tts.release()
}
