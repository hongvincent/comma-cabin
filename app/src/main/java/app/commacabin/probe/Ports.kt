package app.commacabin.probe

/** Seat-area key such as "ROW_1_LEFT" or "ROW_1_LEFT+ROW_1_RIGHT" (sorted SeatValue names). */
data class AreaId(val key: String) {
    val includesDriver: Boolean get() = key.split('+').contains("ROW_1_LEFT")
}

enum class Access { NONE, READ, WRITE, READ_WRITE }

enum class SwitchState { OFF, ON, DAYTIME_RUNNING, AUTOMATIC }

data class BrightnessCapability(val access: Access, val bounds: Map<AreaId, IntRange>)

/** Error as reported by the SDK. [code] is set only when the message names a public ErrorCode. */
data class ProbeError(val type: String, val message: String?, val code: String?) {
    fun text(): String = listOfNotNull(code, type, message).joinToString(" / ")
}

/** Ambient brightness calls. Every callback must arrive on the probe's (main) thread. */
interface LightPort {
    fun checkBrightnessCapabilities(onResult: (BrightnessCapability) -> Unit, onError: (ProbeError) -> Unit)
    fun getSwitch(area: AreaId, onResult: (SwitchState?) -> Unit, onError: (ProbeError) -> Unit)
    fun getBrightness(area: AreaId, onResult: (Int?) -> Unit, onError: (ProbeError) -> Unit)
    /** Gated by [dispatch] right before the SDK entry. */
    fun setBrightness(area: AreaId, value: Int, dispatch: Dispatch, onComplete: (Int?) -> Unit, onError: (ProbeError) -> Unit)
    /**
     * The handle is no proof of a registration. [onListening] (main): null once the registration call returned
     * normally; an error when it did not happen or the listener failed later (it then observes nothing reliable).
     */
    fun observeBrightness(area: AreaId, onListening: (ProbeError?) -> Unit, onUpdate: (Int?) -> Unit): AutoCloseable
}

enum class GearState { UNKNOWN, PARK, NOT_PARK }

/** [uxRestricted] is null until the driving-restriction service answers. */
data class ParkingState(val gear: GearState = GearState.UNKNOWN, val uxRestricted: Boolean? = null) {
    val parked: Boolean get() = gear == GearState.PARK && uxRestricted == false
}

fun interface Cancellable {
    fun cancel()
}

interface Scheduler {
    fun schedule(delayMs: Long, task: () -> Unit): Cancellable
}
