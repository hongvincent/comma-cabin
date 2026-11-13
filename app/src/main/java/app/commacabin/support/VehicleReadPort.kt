package app.commacabin.support

import app.commacabin.probe.Access
import app.commacabin.probe.ProbeError

/** Read-only features checked by the support diagnostic. There are no write calls in this port. */
enum class Feature(val label: String) {
    DOOR_POSITION("조수석 문 위치"),
    SEAT_BACKREST_POSITION("운전석 등받이 위치"),
    SEAT_BACKREST_MOVE("등받이 이동 지원(조회만)"),
    SEAT_MEMORY_SUPPORT("시트 프리셋 지원(조회만)"),
    HVAC_POWER("공조 전원"),
    HVAC_AUTO("공조 자동"),
    HVAC_DUAL("공조 듀얼"),
    HVAC_FAN_SPEED("풍량"),
    HVAC_TEMPERATURE("목표 온도(°C)"),
    AMBIENT_COLOR("앰비언트 색상(ARGB)"),
}

/** One area of a capability. [min]/[max] are null when the API reports areas without bounds. */
data class AreaBound(val area: String, val min: String? = null, val max: String? = null)

/** [access] is the SDK's CapabilityConfig.accessType. [extra] carries feature-specific data such as the temperature step. */
data class CapabilityResult(val access: Access, val areas: List<AreaBound>, val extra: String? = null)

/**
 * Values are passed as the SDK returned them, rendered to text (null stays null). Every callback must arrive
 * on the diagnostic's (main) thread.
 */
interface VehicleReadPort {
    fun capability(feature: Feature, ok: (CapabilityResult) -> Unit, err: (ProbeError) -> Unit)
    fun current(feature: Feature, area: String, ok: (String?) -> Unit, err: (ProbeError) -> Unit)
    /**
     * Returns null when this diagnostic does not subscribe to [feature]. The handle is no proof of a registration:
     * a registration that did not happen reaches [onError] (on main), like a listener error.
     */
    fun observe(feature: Feature, area: String, onUpdate: (String?) -> Unit, onError: (ProbeError) -> Unit): AutoCloseable?
}
