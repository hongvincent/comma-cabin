package app.commacabin.rest

import app.commacabin.audio.ONBOARDING_VERSION
import app.commacabin.audio.RestAudio
import app.commacabin.audio.RestAudioPort
import app.commacabin.audio.RestSettings
import app.commacabin.audio.SoundLevel
import app.commacabin.audio.SoundMode
import app.commacabin.probe.Cancellable
import app.commacabin.probe.GearState
import app.commacabin.probe.ParkingState
import app.commacabin.probe.Scheduler
import java.time.ZoneId

const val REST_TICK_MS = 250L
const val CLOCK_TICK_MS = 1_000L

enum class Screen { ONBOARDING, READY, RESTING, ENDED }

enum class EndKind { NORMAL, USER, STOPPED }

enum class EndReason(val kind: EndKind, val text: String) {
    TIME_UP(EndKind.NORMAL, "정한 휴식 시간이 끝났어요. 픽업 2분 전이에요."),
    USER(EndKind.USER, "휴식 끝내기를 눌렀어요."),
    DOOR_OPENED(EndKind.STOPPED, "조수석 문이 열려 휴식을 멈췄어요."),
    LEFT_PARK(EndKind.STOPPED, "기어가 P에서 바뀌어 휴식을 멈췄어요."),
    RESTRICTED(EndKind.STOPPED, "주행 제한이 걸려 휴식을 멈췄어요."),
    VEHICLE_UNKNOWN(EndKind.STOPPED, "차량 상태를 확인할 수 없어 휴식을 멈췄어요."),
    DOOR_UNKNOWN(EndKind.STOPPED, "조수석 문 상태를 확인할 수 없어 휴식을 멈췄어요."),
    APP_LEFT(EndKind.STOPPED, "앱 화면을 벗어나 휴식을 멈췄어요."),
    AUDIO_LOST(EndKind.STOPPED, "다른 소리가 재생돼 휴식을 멈췄어요."),
}

/** Why a rest cannot start. [endReason] is what the same condition ends a running rest with. */
enum class Block(val text: String, val endReason: EndReason?) {
    GEAR_UNKNOWN("기어 상태를 확인할 수 없어요", EndReason.VEHICLE_UNKNOWN),
    NOT_PARKED("기어를 P(주차)로 바꿔 주세요", EndReason.LEFT_PARK),
    RESTRICTION_UNKNOWN("주행 제한 상태를 확인할 수 없어요", EndReason.VEHICLE_UNKNOWN),
    RESTRICTED("주행 제한 중이라 쉴 수 없어요", EndReason.RESTRICTED),
    DOOR_CHECKING("조수석 문 상태를 확인하고 있어요", EndReason.DOOR_UNKNOWN),
    DOOR_OPEN("조수석 문이 열려 있어요", EndReason.DOOR_OPENED),
    DOOR_UNSUPPORTED("이 차에서는 조수석 문 상태를 확인할 수 없어 시작할 수 없어요", EndReason.DOOR_UNKNOWN),
    DOOR_ERROR("조수석 문 신호에 문제가 있어요", EndReason.DOOR_UNKNOWN),
    TIME_PASSED("휴식 종료 시각이 이미 지났어요. 픽업 시각을 늦춰 주세요", null),
    TIME_SHORT("쉴 시간이 3분보다 짧아요. 픽업 시각을 늦춰 주세요", null),
}

/** Parked means P and no driving restriction; P alone is not enough. */
fun parkingBlock(p: ParkingState): Block? = when {
    p.gear == GearState.UNKNOWN -> Block.GEAR_UNKNOWN
    p.gear == GearState.NOT_PARK -> Block.NOT_PARKED
    p.uxRestricted == null -> Block.RESTRICTION_UNKNOWN
    p.uxRestricted -> Block.RESTRICTED
    else -> null
}

fun doorBlock(d: DoorStatus): Block? = when (d) {
    DoorStatus.CLOSED -> null
    DoorStatus.CHECKING -> Block.DOOR_CHECKING
    DoorStatus.OPEN -> Block.DOOR_OPEN
    DoorStatus.UNSUPPORTED -> Block.DOOR_UNSUPPORTED
    DoorStatus.ERROR -> Block.DOOR_ERROR
}

fun timeBlock(restMs: Long): Block? = when {
    restMs <= 0 -> Block.TIME_PASSED
    restMs < MIN_REST_MS -> Block.TIME_SHORT
    else -> null
}

/**
 * [endWallMs] is only for showing the end time. The countdown compares the monotonic clock with
 * [endElapsedMs], fixed at start, so a wall-clock change cannot lengthen or shorten a running rest.
 */
data class Session(val id: Int, val endWallMs: Long, val endElapsedMs: Long)

data class Ending(val sessionId: Int, val reason: EndReason, val atWallMs: Long)

data class RestState(
    val screen: Screen,
    val pickupMs: Long,
    val nowMs: Long,
    val zone: ZoneId,
    val parking: ParkingState = ParkingState(),
    val door: DoorState = DoorState(),
    val sound: SoundMode = SoundMode.QUIET,
    val level: SoundLevel = SoundLevel.MEDIUM,
    val lighting: Boolean = true,
    val fan: Boolean = true,
    val temp: Boolean = true,
    val tempC: Float = 22.0f,
    val session: Session? = null,
    val remainingMs: Long = 0,
    val dimmed: Boolean = false,
    val ending: Ending? = null,
    val notice: String? = null,
) {
    val restMs: Long get() = RestTime.restMs(pickupMs, nowMs)
    val parkingBlock: Block? get() = parkingBlock(parking)
    val doorBlock: Block? get() = doorBlock(door.status)
    val timeBlock: Block? get() = timeBlock(restMs)
    val canStart: Boolean get() = screen == Screen.READY && parkingBlock == null && doorBlock == null && timeBlock == null
    val finalMinute: Boolean get() = screen == Screen.RESTING && remainingMs <= FINAL_NOTICE_MS
}

/**
 * Onboarding → ready → resting → ended → ready. Only [RestSettings] are stored, so a new process never
 * resumes a rest. Every session has its own id; a timer or end request for another session, or for a
 * session that already ended, changes nothing. Vehicle state only ends a rest; it never starts one.
 * Main thread only.
 */
class RestFlow(
    private val scheduler: Scheduler,
    private val wallClock: () -> Long,
    private val elapsedClock: () -> Long,
    private val zone: () -> ZoneId,
    private val parking: () -> ParkingState,
    private val door: () -> DoorState,
    private var settings: RestSettings,
    private val saveSettings: (RestSettings) -> Unit,
    private val audio: RestAudioPort,
    private val log: (String) -> Unit = {},
    /** A debug seat tool's request not settled, or a seat motion holding the SDK lane: no rest starts meanwhile. */
    private val startBlock: () -> String? = { null },
) {
    var state: RestState
        private set
    var onState: (RestState) -> Unit = {}

    /** Once per session, right after it really started, with the [무드등 조정] choice of that start. */
    var onSessionStarted: (sessionId: Int, lighting: Boolean) -> Unit = { _, _ -> }

    /** Once per session, after its sound was told to stop, so vehicle work never delays the sound stop. */
    var onSessionEnded: (sessionId: Int, reason: EndReason) -> Unit = { _, _ -> }

    private var sessionSeq = 0
    private var lastTouchElapsed = 0L
    private var restTimer: Cancellable? = null
    private var visibleGen = 0
    private var clockTimer: Cancellable? = null

    init {
        val now = wallClock()
        state = RestState(
            screen = if (settings.onboardingSeen) Screen.READY else Screen.ONBOARDING,
            pickupMs = RestTime.defaultPickup(now, zone()),
            nowMs = now,
            zone = zone(),
            parking = parking(),
            door = door(),
            sound = settings.sound,
            level = settings.level,
            lighting = settings.lighting,
            fan = settings.fan,
            temp = settings.temp,
            tempC = settings.tempC,
        )
    }

    fun dismissOnboarding() {
        if (state.screen != Screen.ONBOARDING) return
        store(settings.copy(onboardingVersion = ONBOARDING_VERSION))
        val now = wallClock()
        update { copy(screen = Screen.READY, pickupMs = RestTime.defaultPickup(now, zone()), nowMs = now, zone = zone()) }
    }

    /** Moves the pickup by whole minutes. A past pickup stays in the past; it never rolls to the next day. */
    fun adjustPickup(minutes: Long) {
        if (state.screen != Screen.READY) return
        update { copy(pickupMs = pickupMs + minutes * 60_000L, nowMs = wallClock(), zone = zone(), notice = null) }
    }

    /**
     * [지금 기준으로 다시 설정]: the user's own press on the ready screen when the time is not enough. Suggests the
     * default pickup from the latest clock; nothing else changes and nothing is stored, read or started.
     * Never called by the flow itself, and ignored when the current pickup still leaves enough time.
     */
    fun resetPickup() {
        if (state.screen != Screen.READY) return
        val now = wallClock()
        if (timeBlock(RestTime.restMs(state.pickupMs, now)) == null) return
        log("pickup reset by user")
        update { copy(pickupMs = RestTime.defaultPickup(now, zone()), nowMs = now, zone = zone(), notice = null) }
    }

    /** Checks every condition again with the latest vehicle state and time before starting. */
    fun start() {
        if (state.screen != Screen.READY) return
        val now = wallClock()
        startBlock()?.let { reason ->
            log("start refused: $reason")
            update { copy(nowMs = now, notice = "시작하지 않았어요. $reason") }
            return
        }
        val p = parking()
        val d = door()
        val restMs = RestTime.restMs(state.pickupMs, now)
        val block = parkingBlock(p) ?: doorBlock(d.status) ?: timeBlock(restMs)
        if (block != null) {
            log("start refused: $block")
            update { copy(parking = p, door = d, nowMs = now, notice = "시작하지 않았어요. ${block.text}") }
            return
        }
        val id = sessionSeq + 1
        val elapsed = elapsedClock()
        val session = Session(id, RestTime.restEnd(state.pickupMs), elapsed + restMs)
        audio.begin(id, session.endElapsedMs, state.sound, state.level)?.let { reason ->
            log("start refused by audio: $reason")
            update { copy(parking = p, door = d, nowMs = now, notice = "시작하지 않았어요. $reason") }
            return
        }
        // Final check after the audio preparation: anything that changed meanwhile cancels the start.
        val p2 = parking()
        val d2 = door()
        val late = parkingBlock(p2) ?: doorBlock(d2.status) ?: timeBlock(session.endElapsedMs - elapsedClock())
        if (late != null) {
            audio.end(id, normal = false)
            log("start refused after audio preparation: $late")
            update { copy(parking = p2, door = d2, nowMs = wallClock(), notice = "시작하지 않았어요. ${late.text}") }
            return
        }
        sessionSeq = id
        lastTouchElapsed = elapsed
        log("session $id start: ${restMs}ms, end wall=${session.endWallMs} elapsed=${session.endElapsedMs}")
        update {
            copy(screen = Screen.RESTING, parking = p, door = d, nowMs = now, session = session, remainingMs = restMs, dimmed = false, ending = null, notice = null)
        }
        scheduleRestTick(id)
        onSessionStarted(id, state.lighting)
    }

    /** Any touch while resting. When dimmed it only wakes the screen. */
    fun touch() {
        if (state.screen != Screen.RESTING) return
        lastTouchElapsed = elapsedClock()
        if (state.dimmed) {
            log("session ${state.session?.id} woke")
            update { copy(dimmed = false) }
        }
    }

    /** "휴식 끝내기". Ignored while dimmed: the first touch on a dimmed screen must not end the rest. */
    fun endByUser() {
        val s = state.session ?: return
        if (state.screen != Screen.RESTING || state.dimmed) return
        end(s.id, EndReason.USER)
    }

    /**
     * Ready: just remembered. Resting: turning the sound on needs focus; if refused the rest stays quiet.
     * Pressing the choice already shown still stores it (after a sound failure the screen shows quiet while
     * the stored choice may still be on).
     */
    fun setSound(sound: SoundMode) {
        if (state.screen == Screen.ONBOARDING) return
        if (sound == state.sound) {
            if (settings.sound != sound) store(settings.copy(sound = sound))
            return
        }
        val s = state.session
        if (state.screen == Screen.RESTING && s != null) {
            audio.setSound(s.id, sound)?.let { reason ->
                log("sound $sound refused: $reason")
                update { copy(notice = reason) }
                return
            }
        }
        update { copy(sound = sound, notice = null) }
        store(settings.copy(sound = sound))
    }

    /** [무드등 조정] on/off: only remembered, read at the next start. Nothing is read or written here. */
    fun setLighting(on: Boolean) {
        if (on == state.lighting || state.screen == Screen.ONBOARDING || state.screen == Screen.RESTING) return
        update { copy(lighting = on) }
        store(settings.copy(lighting = on))
    }

    /** [바람 줄이기] on/off: only remembered. */
    fun setFan(on: Boolean) {
        if (on == state.fan || state.screen == Screen.ONBOARDING || state.screen == Screen.RESTING) return
        update { copy(fan = on) }
        store(settings.copy(fan = on))
    }

    /** [운전석 온도 맞추기] on/off: only remembered. */
    fun setTemp(on: Boolean) {
        if (on == state.temp || state.screen == Screen.ONBOARDING || state.screen == Screen.RESTING) return
        update { copy(temp = on) }
        store(settings.copy(temp = on))
    }

    /** The chosen °C: only remembered; the vehicle range and step are checked at the next start. */
    fun setTempC(value: Float) {
        if (!value.isFinite() || value == state.tempC || state.screen == Screen.ONBOARDING || state.screen == Screen.RESTING) return
        update { copy(tempC = value) }
        store(settings.copy(tempC = value))
    }

    fun setLevel(level: SoundLevel) {
        if (level == state.level || state.screen == Screen.ONBOARDING) return
        audio.setLevel(level)
        update { copy(level = level) }
        store(settings.copy(level = level))
    }

    /** Permanent audio focus loss of the rest sound. */
    fun onAudioLost(sessionId: Int) = end(sessionId, EndReason.AUDIO_LOST)

    /**
     * The rest sound of this session stopped by itself (player failure): the rest goes on quietly and the
     * reason is shown. The stored choice stays, like a refused switch: only a choice that took effect is stored.
     */
    fun onSoundFailed(sessionId: Int, reason: String) {
        if (state.screen != Screen.RESTING || state.session?.id != sessionId) return log("ignored sound failure of session $sessionId")
        log("session $sessionId sound failed: $reason")
        update { copy(sound = SoundMode.QUIET, notice = reason) }
    }

    /** The callbacks from the audio side that change the flow. MainActivity uses exactly this. */
    fun listenTo(audio: RestAudio) {
        audio.onFocusLost = ::onAudioLost
        audio.onSoundFailed = ::onSoundFailed
    }

    fun onParking(p: ParkingState) {
        update { copy(parking = p) }
        val s = state.session ?: return
        if (state.screen == Screen.RESTING) parkingBlock(p)?.endReason?.let { end(s.id, it) }
    }

    fun onDoor(d: DoorState) {
        update { copy(door = d) }
        val s = state.session ?: return
        if (state.screen == Screen.RESTING) doorBlock(d.status)?.endReason?.let { end(s.id, it) }
    }

    fun restAgain() {
        if (state.screen != Screen.ENDED) return
        val now = wallClock()
        val pickup = if (state.pickupMs > now) state.pickupMs else RestTime.defaultPickup(now, zone())
        update { copy(screen = Screen.READY, pickupMs = pickup, nowMs = now, zone = zone(), parking = parking(), door = door(), ending = null, notice = null) }
    }

    /** onStart: keeps the ready screen's clock current while visible. */
    fun onVisible() {
        val g = ++visibleGen
        clockTimer?.cancel()
        clockTick(g)
    }

    /** onStop: a running rest ends as APP_LEFT and no timer keeps running in the background. */
    fun onHidden() {
        visibleGen++
        clockTimer?.cancel()
        clockTimer = null
        val s = state.session ?: return
        if (state.screen == Screen.RESTING) end(s.id, EndReason.APP_LEFT)
    }

    private fun clockTick(g: Int) {
        if (g != visibleGen) return
        update { copy(nowMs = wallClock(), zone = zone()) }
        clockTimer = scheduler.schedule(CLOCK_TICK_MS) { clockTick(g) }
    }

    private fun scheduleRestTick(id: Int) {
        restTimer = scheduler.schedule(REST_TICK_MS) { onRestTick(id) }
    }

    private fun onRestTick(id: Int) {
        val s = state.session
        if (state.screen != Screen.RESTING || s?.id != id) {
            log("ignored tick of session $id")
            return
        }
        val elapsed = elapsedClock()
        val remaining = s.endElapsedMs - elapsed
        if (remaining <= 0) return end(id, EndReason.TIME_UP)
        val dim = state.dimmed || elapsed - lastTouchElapsed >= DIM_AFTER_MS
        if (dim && !state.dimmed) log("session $id dimmed")
        update { copy(remainingMs = remaining, dimmed = dim, nowMs = wallClock()) }
        scheduleRestTick(id)
    }

    private fun end(id: Int, reason: EndReason) {
        if (state.screen != Screen.RESTING || state.session?.id != id) {
            log("ignored end $reason of session $id")
            return
        }
        restTimer?.cancel()
        restTimer = null
        val now = wallClock()
        log("session $id end: $reason")
        update { copy(screen = Screen.ENDED, ending = Ending(id, reason, now), remainingMs = 0, dimmed = false, nowMs = now, notice = null) }
        audio.end(id, normal = reason == EndReason.TIME_UP)
        onSessionEnded(id, reason)
    }

    private fun store(next: RestSettings) {
        settings = next
        saveSettings(next)
    }

    private inline fun update(change: RestState.() -> RestState) {
        val next = state.change()
        if (next == state) return
        state = next
        onState(next)
    }
}
