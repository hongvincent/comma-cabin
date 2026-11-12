package app.commacabin.audio

enum class SoundMode { ON, QUIET }

/** Player volume for each level; the system media volume still applies on top. */
enum class SoundLevel(val gain: Float) { LOW(0.3f), MEDIUM(0.55f), HIGH(0.85f) }

/** One PCM track: the looping rest sound or the one-shot chime. Main thread only. Every call may throw. */
interface SoundPlayer {
    fun play()
    fun pause()

    /** Level and ducking only; the time-based fade is the [Envelope]. */
    fun setVolume(volume: Float)

    /**
     * Native time envelope (VolumeShaper) multiplied with the volume. A new envelope is in place before the
     * previous one is removed, and the last value (0) persists after the end.
     */
    fun setEnvelope(envelope: Envelope)

    /** Silences and stops the track first, then removes the envelope and releases the track. */
    fun release()
}

/** Curve points from now ([times] 0) to the end of the rest ([times] 1), over [durationMs]. */
data class Envelope(val durationMs: Long, val times: List<Float>, val volumes: List<Float>)

/**
 * 1.0 until [fadeMs] before the end, then linear down to 0 at the end. Inside the fade it starts at the
 * value that belongs to this moment ([leftMs] / [fadeMs]).
 */
fun endEnvelope(leftMs: Long, fadeMs: Long): Envelope {
    require(leftMs > 0 && fadeMs > 0)
    return if (leftMs <= fadeMs) {
        Envelope(leftMs, listOf(0f, 1f), listOf(leftMs.toFloat() / fadeMs, 0f))
    } else {
        Envelope(leftMs, listOf(0f, (leftMs - fadeMs).toFloat() / leftMs, 1f), listOf(1f, 1f, 0f))
    }
}

interface SoundFactory {
    /** Throws when the sound cannot be prepared. */
    fun restLoop(): SoundPlayer

    /** Throws when the sound cannot be prepared. */
    fun chime(): SoundPlayer
    val chimeDurationMs: Long
}

enum class FocusChange { GAIN, LOSS, LOSS_TRANSIENT, LOSS_TRANSIENT_CAN_DUCK }

fun interface FocusHandle {
    fun abandon()
}

/**
 * Media audio focus. The request is synchronous and delayed focus is never accepted, so a request is
 * either granted (a handle) or refused (null) before this call returns. [onChange] arrives on the main thread.
 */
fun interface AudioFocusPort {
    fun request(transientMayDuck: Boolean, onChange: (FocusChange) -> Unit): FocusHandle?
}

/**
 * The Speech Service registers TextToSpeech callbacks per application, so at most one TextToSpeech object
 * may exist in this process. Whoever takes the slot first revokes the previous holder, which releases its
 * object synchronously.
 */
class TtsSlot {
    private var owner: Any? = null
    private var revoke: (() -> Unit)? = null

    fun take(newOwner: Any, onRevoked: () -> Unit) {
        if (owner !== newOwner) {
            val previous = revoke
            owner = null
            revoke = null
            previous?.invoke()
        }
        owner = newOwner
        revoke = onRevoked
    }

    fun leave(holder: Any) {
        if (owner !== holder) return
        owner = null
        revoke = null
    }
}
