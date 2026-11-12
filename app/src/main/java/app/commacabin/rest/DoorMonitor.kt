package app.commacabin.rest

import app.commacabin.probe.Access
import app.commacabin.probe.Cancellable
import app.commacabin.probe.ProbeError
import app.commacabin.probe.Scheduler
import app.commacabin.support.CapabilityResult
import app.commacabin.support.Feature
import app.commacabin.support.VehicleReadPort

enum class DoorStatus { CHECKING, CLOSED, OPEN, UNSUPPORTED, ERROR }

/** [detail] is for logs only. */
data class DoorState(val status: DoorStatus = DoorStatus.CHECKING, val detail: String = "")

const val DOOR_AREA = "ROW_1_RIGHT"
const val DOOR_POLL_MS = 1_000L
const val DOOR_READ_TIMEOUT_MS = 2_000L
const val DOOR_RETRY_MS = 1_000L

/**
 * Passenger door for the rest flow. After the capability check it subscribes and reads the current value
 * directly, then reads again [DOOR_POLL_MS] after every answer: the first notification has been seen to
 * never arrive, so the flow never waits for it.
 *
 * It runs only between start() and stop() (the screen is visible). start, stop and every recovery open a
 * new generation; replies and events of an older generation are dropped. A read reply is also dropped
 * when a newer read or event was applied after that read was sent, so a slow reply cannot overwrite a
 * later door event. null, a missing area, an out-of-range value, a read or subscription error and a
 * timeout are never CLOSED. Main thread only.
 */
class DoorMonitor(
    private val port: VehicleReadPort,
    private val scheduler: Scheduler,
    private val log: (String) -> Unit = {},
    private val onChange: (DoorState) -> Unit = {},
) {
    var state = DoorState()
        private set

    private var running = false
    private var gen = 0
    private var bounds: IntRange? = null
    private var observer: AutoCloseable? = null
    private val pending = mutableListOf<Cancellable>()
    /** Increases for every read sent and every event received, across generations. */
    private var stamp = 0L
    private var applied = 0L

    private sealed interface Reply<out T> {
        data class Ok<T>(val value: T) : Reply<T>
        data class Failed(val error: ProbeError) : Reply<Nothing>
        data object TimedOut : Reply<Nothing>
    }

    fun start() {
        if (running) return
        running = true
        begin()
    }

    /** Ends polling and the subscription. The state goes back to CHECKING, never stays CLOSED. */
    fun stop() {
        if (!running) return
        running = false
        invalidate()
        publish(DoorState(DoorStatus.CHECKING, "stopped"))
    }

    private fun begin() {
        invalidate()
        // During recovery the error stays on screen until a real answer arrives.
        if (state.status != DoorStatus.ERROR) publish(DoorState(DoorStatus.CHECKING, "generation $gen"))
        val g = gen
        request<CapabilityResult>(g, "capability", { ok, err -> port.capability(Feature.DOOR_POSITION, ok, err) }) { reply ->
            when (reply) {
                is Reply.Ok -> onCapability(g, reply.value)
                is Reply.Failed ->
                    if (reply.error.code?.startsWith("ERROR_NOT_SUPPORTED") == true) unsupported(reply.error.text())
                    else recover("capability ${reply.error.text()}")
                Reply.TimedOut -> recover("capability timeout")
            }
        }
    }

    private fun onCapability(g: Int, cap: CapabilityResult) {
        val area = cap.areas.firstOrNull { it.area == DOOR_AREA }
        val min = area?.min?.toIntOrNull()
        val max = area?.max?.toIntOrNull()
        val range = if (min != null && max != null && min < max) min..max else null
        when {
            cap.access != Access.READ && cap.access != Access.READ_WRITE -> unsupported("access ${cap.access}")
            area == null -> unsupported("no $DOOR_AREA in ${cap.areas.map { it.area }}")
            range == null -> unsupported("bounds ${area.min}..${area.max}")
            else -> {
                bounds = range
                log("capability ok, $DOOR_AREA $range")
                if (subscribe(g)) read(g)
            }
        }
    }

    private fun subscribe(g: Int): Boolean {
        val closeable = try {
            port.observe(Feature.DOOR_POSITION, DOOR_AREA, { v -> onEvent(g, v) }, { e -> onEventError(g, e) })
        } catch (e: RuntimeException) {
            recover("subscribe failed ${e.javaClass.simpleName}")
            return false
        }
        if (closeable == null) {
            recover("subscribe unavailable")
            return false
        }
        observer = closeable
        return true
    }

    private fun read(g: Int) {
        val sent = ++stamp
        request<String?>(g, "read", { ok, err -> port.current(Feature.DOOR_POSITION, DOOR_AREA, ok, err) }) { reply ->
            when (reply) {
                is Reply.Ok -> {
                    if (sent < applied) log("ignored read #$sent (${reply.value}): newer update #$applied already applied")
                    else apply(sent, reply.value, "read")
                    later(DOOR_POLL_MS) { if (g == gen && running) read(g) }
                }
                is Reply.Failed -> recover("read ${reply.error.text()}")
                Reply.TimedOut -> recover("read timeout")
            }
        }
    }

    private fun onEvent(g: Int, value: String?) {
        if (g != gen || !running) {
            log("ignored event of generation $g: $value")
            return
        }
        apply(++stamp, value, "event")
    }

    private fun onEventError(g: Int, error: ProbeError) {
        if (g != gen || !running) return
        recover("event error ${error.text()}")
    }

    private fun apply(s: Long, raw: String?, source: String) {
        applied = s
        val range = checkNotNull(bounds)
        val v = raw?.toIntOrNull()
        publish(
            when {
                v == null -> DoorState(DoorStatus.ERROR, "$source value $raw")
                v !in range -> DoorState(DoorStatus.ERROR, "$source value $v outside $range")
                v == range.first -> DoorState(DoorStatus.CLOSED, "$source $v")
                else -> DoorState(DoorStatus.OPEN, "$source $v")
            },
        )
    }

    /** Shows the error, drops this generation and starts over after [DOOR_RETRY_MS]. */
    private fun recover(reason: String) {
        invalidate()
        publish(DoorState(DoorStatus.ERROR, reason))
        val g = gen
        later(DOOR_RETRY_MS) { if (g == gen && running) begin() }
    }

    private fun unsupported(reason: String) {
        invalidate()
        publish(DoorState(DoorStatus.UNSUPPORTED, reason))
    }

    private fun invalidate() {
        gen++
        pending.toList().forEach { it.cancel() }
        pending.clear()
        observer?.let { runCatching { it.close() } }
        observer = null
        bounds = null
    }

    private fun later(delayMs: Long, task: () -> Unit) {
        lateinit var timer: Cancellable
        timer = scheduler.schedule(delayMs) {
            pending.remove(timer)
            task()
        }
        pending += timer
    }

    /** One answer per request: the first of reply, error or timeout. Anything for an older generation is dropped. */
    private fun <T> request(g: Int, label: String, call: (ok: (T) -> Unit, err: (ProbeError) -> Unit) -> Unit, done: (Reply<T>) -> Unit) {
        var finished = false
        lateinit var timer: Cancellable
        fun finish(reply: Reply<T>) {
            if (finished) return
            finished = true
            timer.cancel()
            pending.remove(timer)
            if (g != gen || !running) {
                log("ignored $label reply of generation $g")
                return
            }
            done(reply)
        }
        timer = scheduler.schedule(DOOR_READ_TIMEOUT_MS) { finish(Reply.TimedOut) }
        pending += timer
        try {
            call({ finish(Reply.Ok(it)) }, { finish(Reply.Failed(it)) })
        } catch (e: RuntimeException) {
            finish(Reply.Failed(ProbeError(e.javaClass.name, e.message, null)))
        }
    }

    private fun publish(next: DoorState) {
        if (next.status == state.status) return
        log("door ${state.status} -> ${next.status} (${next.detail})")
        state = next
        onChange(next)
    }
}
