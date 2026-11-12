package app.commacabin.sdk

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.VolumeShaper
import android.os.Handler
import app.commacabin.R
import app.commacabin.audio.AudioFocusPort
import app.commacabin.audio.Envelope
import app.commacabin.audio.FocusChange
import app.commacabin.audio.FocusHandle
import app.commacabin.audio.SoundFactory
import app.commacabin.audio.SoundPlayer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The rest sound and the chime are media sounds (MUSIC context in the Pleos audio focus matrix). */
private val MEDIA = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
    .build()

class AndroidAudioFocus(context: Context, private val main: Handler) : AudioFocusPort {
    private val audio = context.getSystemService(AudioManager::class.java)

    override fun request(transientMayDuck: Boolean, onChange: (FocusChange) -> Unit): FocusHandle? {
        val request = AudioFocusRequest.Builder(if (transientMayDuck) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK else AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(MEDIA)
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener({ change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> onChange(FocusChange.LOSS)
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> onChange(FocusChange.LOSS_TRANSIENT)
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> onChange(FocusChange.LOSS_TRANSIENT_CAN_DUCK)
                    else -> if (change > 0) onChange(FocusChange.GAIN)
                }
            }, main)
            .build()
        return if (audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            FocusHandle { audio.abandonAudioFocusRequest(request) }
        } else {
            null
        }
    }
}

/** 16-bit mono PCM read from one of the app's own WAV resources (tools/gen_rest_audio.py). */
private class Pcm(val rate: Int, val data: ByteArray) {
    val frames: Int get() = data.size / 2

    companion object {
        fun read(context: Context, resId: Int): Pcm {
            val bytes = context.resources.openRawResource(resId).use { it.readBytes() }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(bytes.size > 12 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "not a WAV file" }
            var pos = 12
            var rate = 0
            while (pos + 8 <= bytes.size) {
                val id = String(bytes, pos, 4)
                val size = buf.getInt(pos + 4)
                val body = pos + 8
                when (id) {
                    "fmt " -> {
                        require(buf.getShort(body).toInt() == 1 && buf.getShort(body + 2).toInt() == 1 && buf.getShort(body + 14).toInt() == 16) {
                            "only 16-bit mono PCM"
                        }
                        rate = buf.getInt(body + 4)
                    }
                    "data" -> {
                        require(rate > 0 && body + size <= bytes.size) { "broken WAV" }
                        return Pcm(rate, bytes.copyOfRange(body, body + size))
                    }
                }
                pos = body + size + (size and 1)
            }
            throw IllegalArgumentException("no data chunk")
        }
    }
}

/**
 * Static AudioTrack: the whole sound sits in the track, and the loop points make the rest sound repeat
 * sample-exactly with no gap at the loop boundary.
 */
private class PcmTrack(pcm: Pcm, loop: Boolean) : SoundPlayer {
    private val track = AudioTrack.Builder()
        .setAudioAttributes(MEDIA)
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(pcm.rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
        )
        .setTransferMode(AudioTrack.MODE_STATIC)
        .setBufferSizeInBytes(pcm.data.size)
        .build()
    private var shaper: VolumeShaper? = null

    init {
        try {
            check(track.state == AudioTrack.STATE_NO_STATIC_DATA) { "track state ${track.state}" }
            check(track.write(pcm.data, 0, pcm.data.size) == pcm.data.size) { "static write failed" }
            if (loop) check(track.setLoopPoints(0, pcm.frames, -1) == AudioTrack.SUCCESS) { "loop points failed" }
        } catch (e: RuntimeException) {
            track.release()
            throw e
        }
    }

    override fun play() = track.play()

    override fun pause() = track.pause()

    override fun setVolume(volume: Float) {
        val result = track.setVolume(volume)
        check(result == AudioTrack.SUCCESS) { "setVolume returned $result" }
    }

    /** The new shaper is applied before the old one is closed, so attenuation is never removed in between. */
    override fun setEnvelope(envelope: Envelope) {
        val config = VolumeShaper.Configuration.Builder()
            .setDuration(envelope.durationMs.coerceAtLeast(1))
            .setCurve(envelope.times.toFloatArray(), envelope.volumes.toFloatArray())
            .setInterpolatorType(VolumeShaper.Configuration.INTERPOLATOR_TYPE_LINEAR)
            .build()
        val next = track.createVolumeShaper(config)
        try {
            next.apply(VolumeShaper.Operation.PLAY)
        } catch (e: RuntimeException) {
            runCatching { next.close() }
            throw e
        }
        val previous = shaper
        shaper = next
        previous?.let { runCatching { it.close() } }
    }

    /**
     * VolumeShaper.close() removes its attenuation at once, so the track is silenced and stopped first;
     * every step is attempted even if an earlier one fails.
     */
    override fun release() {
        runCatching { track.pause() }
        runCatching { track.stop() }
        shaper?.let { runCatching { it.close() } }
        shaper = null
        runCatching { track.release() }
    }
}

class AndroidSounds(private val context: Context) : SoundFactory {
    private val chimePcm by lazy { Pcm.read(context, R.raw.announce_chime) }

    override fun restLoop(): SoundPlayer = PcmTrack(Pcm.read(context, R.raw.rest_loop), loop = true)

    override fun chime(): SoundPlayer = PcmTrack(chimePcm, loop = false)

    override val chimeDurationMs: Long get() = chimePcm.frames * 1000L / chimePcm.rate
}
