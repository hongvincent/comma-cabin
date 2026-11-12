package app.commacabin.lighting

import app.commacabin.control.ControlPort
import app.commacabin.control.ControlProp
import app.commacabin.control.ControlValues
import app.commacabin.control.SideState
import app.commacabin.probe.Access
import app.commacabin.probe.AreaId
import app.commacabin.probe.LightPort
import app.commacabin.probe.ProbeError
import app.commacabin.probe.SwitchState

/** What the product lighting needs. Areas are seat-area keys ("ROW_1_LEFT+ROW_1_RIGHT"). Callbacks on main. */
interface LightingPort {
    fun brightnessCapability(ok: (Access, Map<String, IntRange>) -> Unit, err: (ProbeError) -> Unit)
    fun colorCapability(ok: (Access, List<String>) -> Unit, err: (ProbeError) -> Unit)
    fun switchCapability(ok: (Access, List<String>) -> Unit, err: (ProbeError) -> Unit)
    fun readSwitch(area: String, ok: (SwitchState?) -> Unit, err: (ProbeError) -> Unit)
    fun read(prop: LightProp, area: String, ok: (Int?) -> Unit, err: (ProbeError) -> Unit)
    /** [ok] receives the onComplete value: an echo of the request, never proof of the vehicle value. */
    fun write(prop: LightProp, area: String, value: Int, ok: (Int?) -> Unit, err: (ProbeError) -> Unit)
    /** Listener errors are dropped: they are not user changes. */
    fun observe(prop: LightProp, area: String, onUpdate: (Int?) -> Unit): AutoCloseable
}

/** The existing public-SDK adapters: brightness and switch reads from [light], color and switch areas from [control]. */
class SdkLightingPort(private val light: LightPort, private val control: ControlPort) : LightingPort {
    override fun brightnessCapability(ok: (Access, Map<String, IntRange>) -> Unit, err: (ProbeError) -> Unit) =
        light.checkBrightnessCapabilities({ cap -> ok(cap.access, cap.bounds.mapKeys { it.key.key }) }, err)

    override fun colorCapability(ok: (Access, List<String>) -> Unit, err: (ProbeError) -> Unit) =
        control.capability(ControlProp.AMBIENT_COLOR, { cap, _, _ -> ok(cap.access, cap.areas.map { it.area }) }, err)

    override fun switchCapability(ok: (Access, List<String>) -> Unit, err: (ProbeError) -> Unit) =
        control.sideAreas(SideState.LIGHT_SWITCH, ok, err)

    override fun readSwitch(area: String, ok: (SwitchState?) -> Unit, err: (ProbeError) -> Unit) =
        light.getSwitch(AreaId(area), ok, err)

    override fun read(prop: LightProp, area: String, ok: (Int?) -> Unit, err: (ProbeError) -> Unit) = when (prop) {
        LightProp.BRIGHTNESS -> light.getBrightness(AreaId(area), ok, err)
        LightProp.COLOR -> control.read(ControlProp.AMBIENT_COLOR, area, { v -> ok(v?.let { color(it) }) }, err)
    }

    override fun write(prop: LightProp, area: String, value: Int, ok: (Int?) -> Unit, err: (ProbeError) -> Unit) = when (prop) {
        LightProp.BRIGHTNESS -> light.setBrightness(AreaId(area), value, ok, err)
        LightProp.COLOR -> control.write(ControlProp.AMBIENT_COLOR, area, LightValues.hex(value), { v -> ok(v?.let { color(it) }) }, err)
    }

    override fun observe(prop: LightProp, area: String, onUpdate: (Int?) -> Unit): AutoCloseable = when (prop) {
        LightProp.BRIGHTNESS -> light.observeBrightness(AreaId(area)) { v -> if (v != null) onUpdate(v) }
        LightProp.COLOR -> control.observe(ControlProp.AMBIENT_COLOR, area, { v -> v?.let { color(it) }?.let(onUpdate) }, {})
    }

    private fun color(text: String): Int? = ControlValues.parse(ControlProp.AMBIENT_COLOR, text) as Int?
}
