package app.commacabin.climate

import app.commacabin.control.ControlPort
import app.commacabin.control.ControlProp
import app.commacabin.control.SideState
import app.commacabin.probe.Access
import app.commacabin.probe.ProbeError

/** Shared climate states. Read only, except AUTO which the app writes only to restore the original. */
enum class Side(val control: SideState) { POWER(SideState.HVAC_POWER), AUTO(SideState.HVAC_AUTO), DUAL(SideState.HVAC_DUAL) }

/** What the product climate needs. Areas are seat-area keys. Callbacks on main. Write callbacks are echoes. */
interface ClimatePort {
    fun capability(prop: ClimateProp, ok: (Access, Map<String, ClosedFloatingPointRange<Float>>, Float?) -> Unit, err: (ProbeError) -> Unit)
    fun sideAreas(side: Side, ok: (Access, List<String>) -> Unit, err: (ProbeError) -> Unit)
    fun read(prop: ClimateProp, area: String, ok: (Float?) -> Unit, err: (ProbeError) -> Unit)
    fun readSide(side: Side, area: String, ok: (Boolean?) -> Unit, err: (ProbeError) -> Unit)
    fun write(prop: ClimateProp, area: String, value: Float, ok: (Float?) -> Unit, err: (ProbeError) -> Unit)
    fun writeAuto(area: String, value: Boolean, ok: (Boolean?) -> Unit, err: (ProbeError) -> Unit)
    /** Listener errors are dropped: they are not user changes. */
    fun observe(prop: ClimateProp, area: String, onUpdate: (Float?) -> Unit): AutoCloseable
}

/** The existing public-SDK adapter [control] (getHvac…/setHvacFanSpeed/setHvacTemperature/setHvacAuto). */
class SdkClimatePort(private val control: ControlPort) : ClimatePort {
    private fun controlProp(prop: ClimateProp) = if (prop == ClimateProp.FAN) ControlProp.HVAC_FAN_SPEED else ControlProp.HVAC_TEMPERATURE

    override fun capability(prop: ClimateProp, ok: (Access, Map<String, ClosedFloatingPointRange<Float>>, Float?) -> Unit, err: (ProbeError) -> Unit) =
        control.capability(controlProp(prop), { cap, _, increment ->
            val bounds = cap.areas.mapNotNull { a ->
                val min = a.min?.toFloatOrNull()
                val max = a.max?.toFloatOrNull()
                if (min == null || max == null || !min.isFinite() || !max.isFinite() || min > max) null else a.area to (min..max)
            }.toMap()
            ok(cap.access, bounds, increment?.takeIf { it.isFinite() && it > 0f })
        }, err)

    override fun sideAreas(side: Side, ok: (Access, List<String>) -> Unit, err: (ProbeError) -> Unit) = control.sideAreas(side.control, ok, err)

    override fun read(prop: ClimateProp, area: String, ok: (Float?) -> Unit, err: (ProbeError) -> Unit) =
        control.read(controlProp(prop), area, { v -> ok(number(v)) }, err)

    override fun readSide(side: Side, area: String, ok: (Boolean?) -> Unit, err: (ProbeError) -> Unit) =
        control.readSide(side.control, area, { v -> ok(v?.toBooleanStrictOrNull()) }, err)

    override fun write(prop: ClimateProp, area: String, value: Float, ok: (Float?) -> Unit, err: (ProbeError) -> Unit) {
        val text = ClimateValues.payload(prop, value) ?: return err(ProbeError("InvalidPayload", "not a valid ${prop.name} value: $value", null))
        control.write(controlProp(prop), area, text, { v -> ok(number(v)) }, err)
    }

    override fun writeAuto(area: String, value: Boolean, ok: (Boolean?) -> Unit, err: (ProbeError) -> Unit) =
        control.writeSide(SideState.HVAC_AUTO, area, value.toString(), { v -> ok(v?.toBooleanStrictOrNull()) }, err)

    override fun observe(prop: ClimateProp, area: String, onUpdate: (Float?) -> Unit): AutoCloseable =
        control.observe(controlProp(prop), area, { v -> number(v)?.let(onUpdate) }, {})

    /** Non-finite values (Infinity, NaN) count as no value. */
    private fun number(text: String?): Float? = text?.toFloatOrNull()?.takeIf { it.isFinite() }
}
