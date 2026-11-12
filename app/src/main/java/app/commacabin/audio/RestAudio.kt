package app.commacabin.audio

import app.commacabin.probe.Cancellable
import app.commacabin.probe.Scheduler
import app.commacabin.rest.FINAL_NOTICE_MS
import app.commacabin.support.TTS_INIT_TIMEOUT_MS
import app.commacabin.support.TTS_SPEAK_TIMEOUT_MS
import app.commacabin.support.TtsEngine
import app.commacabin.support.TtsEvent

const val DUCK_FACTOR = 0.3f
const val CHIME_GAP_MS = 200L
const val ANNOUNCE_TEXT = "픽업 2분 전이에요"

/** What the rest flow needs from the audio side. */
interface RestAudioPort {
    /** Called at the final start check. null = may start (rest focus held when [sound] is ON); otherwise the reason. */
    fun begin(session: Int, endElapsedMs: Long, sound: SoundMode, level: SoundLevel): String?

    /** null = applied; otherwise why the rest sound was not turned on (the rest stays quiet). */
    fun setSound(session: Int, sound: SoundMode): String?
    fun setLevel(level: SoundLevel)

    /** [normal] = the rest reached its end time; only then the chime and voice play. */
    fun end(session: Int, normal: Boolean)
}

enum class Announce { NONE, RUNNING, VOICE, CHIME_ONLY, SCREEN_ONLY }

data class AudioUi(val playing: Boolean = false, val pausedByOther: Boolean = false, val announce: Announce = Announce.NONE)

/**
 * Rest sound, its audio focus, the final fade and the end announcement. Main thread only.
 *
 * - Every focus request, player, voice object and announcement has its own generation; a callback for an
 *   older one is ignored, so an ended session or a cancelled announcement can never play again.
 * - The sound has its own end, independent of the screen's tick and of the main thread: before every
 *   play() (new sound or resume) the live monotonic clock is checked against the session end and a native
 *   envelope ([endEnvelope], VolumeShaper) is set from that moment: 1 until 60 s before the end, then down
 *   to 0 at the end, where it stays. The volume itself is only level × duck. A stop timer also releases the
 *   player at the end time.
 * - A failing player or focus call never crashes the app or shows sound that is not playing: the sound is
 *   released with its focus and the flow is told ([onSoundFailed]).
 * - Nothing is played without focus: a focus loss during the chime cancels the announcement (screen only),
 *   and a loss during the voice stops it (chime only).
 * - At most one TextToSpeech object exists ([TtsSlot]); it is prepared at the start of every rest.
 */
class RestAudio(
    private val scheduler: Scheduler,
    private val elapsed: () -> Long,
    private val focus: AudioFocusPort,
    private val sounds: SoundFactory,
    private val createVoice: () -> TtsEngine,
    private val slot: TtsSlot,
    private val log: (String) -> Unit = {},
) : RestAudioPort {
    var ui = AudioUi()
        private set
    var onState: (AudioUi) -> Unit = {}
    var onFocusLost: (session: Int) -> Unit = {}
    /** The rest sound stopped by itself (player failure); the rest goes on quietly. */
    var onSoundFailed: (session: Int, reason: String) -> Unit = { _, _ -> }
    /** Parked, no driving restriction and passenger door closed, read at the moment of a focus regain. */
    var vehicleOk: () -> Boolean = { false }

    private var session = 0
    private var endElapsed = 0L
    private var level = SoundLevel.MEDIUM
    private var player: SoundPlayer? = null
    /** The player is paused because of a transient focus loss and has not been resumed. */
    private var paused = false
    private val sessionTimers = mutableListOf<Cancellable>()

    private var focusGen = 0
    private var heldFocus: FocusHandle? = null
    private var transientLoss = false
    private var ducked = false

    private var voice: TtsEngine? = null
    private var voiceGen = 0
    private var voiceReady = false
    private var speaking = false
    private var voiceTimer: Cancellable? = null

    private var job = 0
    private var jobSeq = 0
    private var chime: SoundPlayer? = null
    private val jobTimers = mutableListOf<Cancellable>()
    private var announce = Announce.NONE

    override fun begin(session: Int, endElapsedMs: Long, sound: SoundMode, level: SoundLevel): String? {
        stopAll()
        this.level = level
        this.session = session
        endElapsed = endElapsedMs
        if (sound == SoundMode.ON) {
            turnSoundOn(session)?.let { reason ->
                this.session = 0
                publish()
                return "$reason. 조용히로 바꾸면 시작할 수 있어요"
            }
        }
        log("session $session audio begin: $sound $level, end elapsed=$endElapsedMs")
        scheduleSessionTimers(session)
        prepareVoice()
        publish()
        return null
    }

    override fun setSound(session: Int, sound: SoundMode): String? {
        if (session == 0 || session != this.session) return null
        if (sound == SoundMode.QUIET) {
            releasePlayer()
            releaseFocus()
            log("session $session rest sound off, focus released")
            publish()
            return null
        }
        if (player != null) return null
        val reason = turnSoundOn(session)
        publish()
        return reason?.let { "$it. 조용히 쉬고 있어요" }
    }

    override fun setLevel(level: SoundLevel) {
        this.level = level
        applyVolume()
    }

    override fun end(session: Int, normal: Boolean) {
        if (session == 0 || session != this.session) return log("ignored audio end of session $session")
        this.session = 0
        cancel(sessionTimers)
        releasePlayer()
        log("session $session audio end, normal=$normal")
        if (normal) {
            announce()
        } else {
            releaseFocus()
            releaseVoice()
            publish()
        }
    }

    /** onStop, onDestroy or a new rest: everything stops and no callback of what ran before can play again. */
    fun stopAll() {
        session = 0
        cancel(sessionTimers)
        releasePlayer()
        if (job != 0) log("announcement $job cancelled")
        job = 0
        cancel(jobTimers)
        releaseChime()
        releaseFocus()
        releaseVoice()
        announce = Announce.NONE
        publish()
    }

    // --- rest sound ---

    /** Focus, a new player, then a live check right before play(). null = playing; otherwise the reason. */
    private fun turnSoundOn(id: Int): String? {
        if (elapsed() >= endElapsed) return "휴식 시간이 끝나 휴식 소리를 켜지 않았어요"
        if (!requestFocus(transientMayDuck = false)) return "다른 소리가 재생 중이라 휴식 소리를 켤 수 없어요"
        val created = try {
            sounds.restLoop()
        } catch (e: RuntimeException) {
            log("rest sound not prepared: ${e.javaClass.name}: ${e.message}")
            releaseFocus()
            return "휴식 소리를 재생하지 못했어요"
        }
        val left = endElapsed - elapsed()
        if (id != session || left <= 0) {
            log("rest sound ready after the end time; not played")
            runCatching { created.release() }
            releaseFocus()
            return "휴식 시간이 끝나 휴식 소리를 켜지 않았어요"
        }
        try {
            playFrom(created, left)
        } catch (e: RuntimeException) {
            log("rest sound play failed: ${e.javaClass.name}: ${e.message}")
            runCatching { created.release() }
            releaseFocus()
            return "휴식 소리를 재생하지 못했어요"
        }
        player = created
        paused = false
        return null
    }

    /** Volume and the envelope for this moment first, then play(), so the first sample is already right. */
    private fun playFrom(target: SoundPlayer, leftMs: Long) {
        target.setVolume(volume())
        target.setEnvelope(endEnvelope(leftMs, FINAL_NOTICE_MS))
        target.play()
        log("rest sound playing at ${volume()}, ${leftMs}ms to its end")
    }

    /** Level × duck only; the time-based fade belongs to the envelope. */
    private fun volume(): Float = level.gain * (if (ducked) DUCK_FACTOR else 1f)

    private fun applyVolume() {
        val p = player ?: return
        try {
            p.setVolume(volume())
        } catch (e: RuntimeException) {
            failSound("volume ${e.javaClass.name}")
        }
    }

    private fun scheduleSessionTimers(id: Int) {
        val now = elapsed()
        sessionTimers += scheduler.schedule((endElapsed - now).coerceAtLeast(0)) {
            if (id != session) return@schedule
            log("session $id rest sound stopped at its end time (elapsed=${elapsed()})")
            releasePlayer()
            publish()
        }
    }

    private fun releasePlayer() {
        player?.let { runCatching { it.release() } }
        player = null
        paused = false
    }

    /** A player call failed: no sound, no focus, and the flow shows the rest as quiet. */
    private fun failSound(why: String) {
        val id = session
        log("rest sound failed: $why")
        releasePlayer()
        releaseFocus()
        publish()
        if (id != 0) onSoundFailed(id, "휴식 소리를 재생하지 못했어요. 조용히 쉬고 있어요")
    }

    // --- focus ---

    private fun requestFocus(transientMayDuck: Boolean): Boolean {
        releaseFocus()
        val gen = ++focusGen
        heldFocus = try {
            focus.request(transientMayDuck) { change -> onFocus(gen, change) }
        } catch (e: RuntimeException) {
            log("focus request failed: ${e.javaClass.name}: ${e.message}")
            null
        }
        log("focus request ${if (transientMayDuck) "GAIN_TRANSIENT_MAY_DUCK" else "GAIN"} #$gen -> ${if (heldFocus != null) "granted" else "refused"}")
        return heldFocus != null
    }

    private fun releaseFocus() {
        heldFocus?.let {
            runCatching { it.abandon() }
            log("focus #$focusGen abandoned")
        }
        heldFocus = null
        focusGen++
        transientLoss = false
        ducked = false
    }

    private fun onFocus(gen: Int, change: FocusChange) {
        if (gen != focusGen) return log("ignored focus $change of request #$gen")
        log("focus #$gen $change")
        if (job != 0) return onAnnounceFocus(change)
        when (change) {
            FocusChange.LOSS_TRANSIENT -> {
                transientLoss = true
                player?.let { p ->
                    try {
                        p.pause()
                        paused = true
                    } catch (e: RuntimeException) {
                        failSound("pause ${e.javaClass.name}")
                    }
                }
            }
            FocusChange.LOSS_TRANSIENT_CAN_DUCK -> {
                ducked = true
                applyVolume()
            }
            FocusChange.GAIN -> {
                transientLoss = false
                ducked = false
                if (paused) resumeIfAllowed() else applyVolume()
            }
            FocusChange.LOSS -> {
                val id = session
                releasePlayer()
                releaseFocus()
                publish()
                if (id != 0) onFocusLost(id)
                return
            }
        }
        publish()
    }

    /**
     * Same session still running, the live monotonic clock before its end, and the vehicle still parked with
     * the door closed. The volume for this moment is set before play(). Otherwise the player stays paused.
     */
    private fun resumeIfAllowed() {
        val p = player ?: return
        val left = endElapsed - elapsed()
        if (session == 0 || left <= 0 || !vehicleOk()) {
            return log("rest sound not resumed (session=$session, ${left}ms to the end)")
        }
        try {
            playFrom(p, left)
        } catch (e: RuntimeException) {
            return failSound("resume ${e.javaClass.name}")
        }
        paused = false
        log("rest sound resumed")
    }

    // --- voice ---

    private fun prepareVoice() {
        releaseVoice()
        val gen = ++voiceGen
        slot.take(this) { onVoiceRevoked(gen) }
        val created = try {
            createVoice()
        } catch (e: RuntimeException) {
            log("voice not created: ${e.javaClass.name}")
            slot.leave(this)
            return
        }
        voice = created
        voiceTimer = scheduler.schedule(TTS_INIT_TIMEOUT_MS) {
            if (gen == voiceGen && !voiceReady) {
                log("voice init timeout")
                releaseVoice()
            }
        }
        try {
            created.initialize({ onVoiceReady(gen) }, { reason -> onVoiceFailed(gen, reason) })
        } catch (e: RuntimeException) {
            onVoiceFailed(gen, "${e.javaClass.name}: ${e.message}")
        }
    }

    private fun onVoiceReady(gen: Int) {
        if (gen != voiceGen) return log("ignored late voice init")
        voiceTimer?.cancel()
        try {
            voice?.setKoreanLocale()
        } catch (e: RuntimeException) {
            return onVoiceFailed(gen, "setLocale ${e.javaClass.name}")
        }
        voiceReady = true
        log("voice ready (ON_DEVICE, ko-KR requested)")
    }

    private fun onVoiceFailed(gen: Int, reason: String) {
        if (gen != voiceGen) return
        log("voice failed: $reason")
        releaseVoice()
    }

    private fun onVoiceRevoked(gen: Int) {
        if (gen != voiceGen) return
        log("voice released for the diagnostic")
        releaseVoice()
        if (job != 0 && chime == null) finishAnnounce(job, Announce.CHIME_ONLY, "voice taken by the diagnostic")
    }

    private fun releaseVoice() {
        voiceTimer?.cancel()
        voiceTimer = null
        voiceGen++
        voiceReady = false
        val current = voice ?: return
        voice = null
        if (speaking) runCatching { current.stop() }
        speaking = false
        runCatching { current.release() }
        slot.leave(this)
    }

    // --- end announcement (normal end only) ---

    private fun announce() {
        val j = ++jobSeq
        job = j
        announce = Announce.RUNNING
        publish()
        // ON mode keeps the rest focus for the announcement; otherwise ask for a short, duckable focus now.
        if (heldFocus == null || transientLoss) {
            if (!requestFocus(transientMayDuck = true)) return finishAnnounce(j, Announce.SCREEN_ONLY, "focus refused")
        } else {
            log("announcement $j keeps the rest focus #$focusGen")
        }
        chime = try {
            sounds.chime()
        } catch (e: RuntimeException) {
            return finishAnnounce(j, Announce.SCREEN_ONLY, "chime not prepared ${e.javaClass.name}")
        }
        try {
            chime?.setVolume(level.gain)
            chime?.play()
        } catch (e: RuntimeException) {
            return finishAnnounce(j, Announce.SCREEN_ONLY, "chime play failed ${e.javaClass.name}")
        }
        log("announcement $j chime")
        jobTimers += scheduler.schedule(sounds.chimeDurationMs + CHIME_GAP_MS) { afterChime(j) }
    }

    /** The voice starts only while this announcement still holds its focus. */
    private fun afterChime(j: Int) {
        if (j != job) return
        releaseChime()
        if (heldFocus == null || transientLoss) return finishAnnounce(j, Announce.CHIME_ONLY, "no focus for the voice")
        val current = voice
        if (current == null || !voiceReady) return finishAnnounce(j, Announce.CHIME_ONLY, "voice not ready")
        val gen = voiceGen
        try {
            speaking = true
            current.speak(ANNOUNCE_TEXT) { event -> onVoiceEvent(j, gen, event) }
        } catch (e: RuntimeException) {
            speaking = false
            return finishAnnounce(j, Announce.CHIME_ONLY, "speak failed ${e.javaClass.name}")
        }
        log("announcement $j speak requested")
        jobTimers += scheduler.schedule(TTS_SPEAK_TIMEOUT_MS) { if (j == job) finishAnnounce(j, Announce.CHIME_ONLY, "speak timeout") }
    }

    private fun onVoiceEvent(j: Int, gen: Int, event: TtsEvent) {
        if (j != job || gen != voiceGen) return
        if (event == TtsEvent.Rms) return
        log("announcement $j voice $event")
        when (event) {
            TtsEvent.Done -> {
                speaking = false
                finishAnnounce(j, Announce.VOICE, "voice done")
            }
            TtsEvent.Stop -> {
                speaking = false
                finishAnnounce(j, Announce.CHIME_ONLY, "voice stopped")
            }
            is TtsEvent.Error -> {
                speaking = false
                finishAnnounce(j, Announce.CHIME_ONLY, "voice error ${event.type}")
            }
            else -> Unit
        }
    }

    /**
     * Losing focus (permanently or for a while) ends the announcement: during the chime nothing more is
     * played (screen only); during the voice it is stopped (chime only). Ducking only lowers the chime.
     */
    private fun onAnnounceFocus(change: FocusChange) {
        val j = job
        when (change) {
            FocusChange.LOSS, FocusChange.LOSS_TRANSIENT ->
                if (chime != null) finishAnnounce(j, Announce.SCREEN_ONLY, "focus $change during the chime")
                else finishAnnounce(j, Announce.CHIME_ONLY, "focus $change during the voice")
            FocusChange.LOSS_TRANSIENT_CAN_DUCK -> runCatching { chime?.setVolume(level.gain * DUCK_FACTOR) }
            FocusChange.GAIN -> runCatching { chime?.setVolume(level.gain) }
        }
    }

    private fun finishAnnounce(j: Int, outcome: Announce, why: String) {
        if (j != job) return
        job = 0
        cancel(jobTimers)
        releaseChime()
        releaseVoice()
        releaseFocus()
        announce = outcome
        log("announcement $j finished: $outcome ($why)")
        publish()
    }

    private fun releaseChime() {
        chime?.let { runCatching { it.release() } }
        chime = null
    }

    private fun cancel(timers: MutableList<Cancellable>) {
        timers.forEach { it.cancel() }
        timers.clear()
    }

    private fun publish() {
        val next = AudioUi(playing = player != null && !paused, pausedByOther = player != null && paused, announce = announce)
        if (next == ui) return
        ui = next
        onState(next)
    }
}
