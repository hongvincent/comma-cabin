package app.commacabin.control

import app.commacabin.probe.Access
import app.commacabin.probe.ProbeError
import app.commacabin.support.CapabilityResult

enum class Move { BACKWARD, FORWARD, STOP }

/** Shared state read before and after a write, to see side effects and preconditions. */
enum class SideState { LIGHT_SWITCH, HVAC_POWER, HVAC_AUTO, HVAC_DUAL }

/**
 * Public Pleos Vehicle SDK 2.3.2 calls for STEP 7, values as text ([ControlValues] forms). Every callback
 * arrives on the main thread. A synchronous throw is reported through [err]. No call bypasses permissions.
 */
interface ControlPort {
    /**
     * [prop] capability. For SEAT_BACKREST the areas come from the position capability (with bounds) and
     * [moveAreas] from the move capability. [increment] is the Celsius step for HVAC_TEMPERATURE.
     */
    fun capability(prop: ControlProp, ok: (cap: CapabilityResult, moveAreas: CapabilityResult?, increment: Float?) -> Unit, err: (ProbeError) -> Unit)

    /** Access and areas of a side state (the API answers areas without bounds). */
    fun sideAreas(state: SideState, ok: (Access, List<String>) -> Unit, err: (ProbeError) -> Unit)

    fun read(prop: ControlProp, area: String, ok: (String?) -> Unit, err: (ProbeError) -> Unit)
    fun readSide(state: SideState, area: String, ok: (String?) -> Unit, err: (ProbeError) -> Unit)

    /** Not for the seat. [ok] receives the SDK's onComplete value as text (an echo, not a reading). */
    fun write(prop: ControlProp, area: String, value: String, ok: (echo: String?) -> Unit, err: (ProbeError) -> Unit)

    /** setHvacAuto only (restoring the auto state); the app never writes HVAC power, dual or the light switch. */
    fun writeSide(state: SideState, area: String, value: String, ok: (echo: String?) -> Unit, err: (ProbeError) -> Unit)

    /** moveSeatBackrestAngle. [ok] receives the onComplete MoveControl as text. */
    fun move(area: String, control: Move, ok: (echo: String?) -> Unit, err: (ProbeError) -> Unit)

    fun observe(prop: ControlProp, area: String, onUpdate: (String?) -> Unit, onError: (ProbeError) -> Unit): AutoCloseable
}
