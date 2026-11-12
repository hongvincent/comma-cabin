package app.commacabin.rest

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
 * Onboarding → ready → resting → ended → ready. Nothing is stored except the onboarding flag, so a new
 * process never resumes a rest. Every session has its own id; a timer or end request for another
 * session, or for a session that already ended, changes nothing. Vehicle state only ends a rest; it
 * never starts one. Main thread only.
 */
class RestFlow(
    private val scheduler: Scheduler,
    private val wallClock: () -> Long,
    private val elapsedClock: () -> Long,
    private val zone: () -> ZoneId,
    private val parking: () -> ParkingState,
    private val door: () -> DoorState,
    onboardingSeen: Boolean,
    private val markOnboardingSeen: () -> Unit,
    private val log: (String) -> Unit = {},
) {
    var state: RestState
        private set
    var onState: (RestState) -> Unit = {}

    private var sessionSeq = 0
    private var lastTouchElapsed = 0L
    private var restTimer: Cancellable? = null
    private var visibleGen = 0
    private var clockTimer: Cancellable? = null

    init {
        val now = wallClock()
        state = RestState(
            screen = if (onboardingSeen) Screen.READY else Screen.ONBOARDING,
            pickupMs = RestTime.defaultPickup(now, zone()),
            nowMs = now,
            zone = zone(),
            parking = parking(),
            door = door(),
        )
    }

    fun dismissOnboarding() {
        if (state.screen != Screen.ONBOARDING) return
        markOnboardingSeen()
        val now = wallClock()
        update { copy(screen = Screen.READY, pickupMs = RestTime.defaultPickup(now, zone()), nowMs = now, zone = zone()) }
    }

    /** Moves the pickup by whole minutes. A past pickup stays in the past; it never rolls to the next day. */
    fun adjustPickup(minutes: Long) {
        if (state.screen != Screen.READY) return
        update { copy(pickupMs = pickupMs + minutes * 60_000L, nowMs = wallClock(), zone = zone(), notice = null) }
    }

    /** Checks every condition again with the latest vehicle state and time before starting. */
    fun start() {
        if (state.screen != Screen.READY) return
        val now = wallClock()
        val p = parking()
        val d = door()
        val restMs = RestTime.restMs(state.pickupMs, now)
        val block = parkingBlock(p) ?: doorBlock(d.status) ?: timeBlock(restMs)
        if (block != null) {
            log("start refused: $block")
            update { copy(parking = p, door = d, nowMs = now, notice = "시작하지 않았어요. ${block.text}") }
            return
        }
        val id = ++sessionSeq
        val elapsed = elapsedClock()
        val session = Session(id, RestTime.restEnd(state.pickupMs), elapsed + restMs)
        lastTouchElapsed = elapsed
        log("session $id start: ${restMs}ms, end wall=${session.endWallMs} elapsed=${session.endElapsedMs}")
        update {
            copy(screen = Screen.RESTING, parking = p, door = d, nowMs = now, session = session, remainingMs = restMs, dimmed = false, ending = null, notice = null)
        }
        scheduleRestTick(id)
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
        update { copy(screen = Screen.ENDED, ending = Ending(id, reason, now), remainingMs = 0, dimmed = false, nowMs = now) }
    }

    private inline fun update(change: RestState.() -> RestState) {
        val next = state.change()
        if (next == state) return
        state = next
        onState(next)
    }
}
