package app.commacabin.sdk

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.MoveControl
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.AmbientLightBrightnessColorListener
import ai.pleos.playground.vehicle.listener.HvacFanSpeedListener
import ai.pleos.playground.vehicle.listener.HvacTemperatureListener
import ai.pleos.playground.vehicle.listener.SeatBackrestAnglePositionListener
import android.os.Handler
import app.commacabin.control.ControlPort
import app.commacabin.control.ControlProp
import app.commacabin.control.ControlValues
import app.commacabin.control.Move
import app.commacabin.control.SideState
import app.commacabin.probe.Access
import app.commacabin.probe.ProbeError
import app.commacabin.support.AreaBound
import app.commacabin.support.CapabilityResult

/** Public Vehicle SDK 2.3.2 only: capability, current value, set/move and listeners. Callbacks go to [main]. */
class PleosControlPort(private val vehicle: Vehicle, private val main: Handler) : ControlPort {

    override fun capability(
        prop: ControlProp,
        ok: (cap: CapabilityResult, moveAreas: CapabilityResult?, increment: Float?) -> Unit,
        err: (ProbeError) -> Unit,
    ) {
        val fail: (Exception) -> Unit = { e -> main.post { err(e.toProbeError()) } }
        call(err) {
            when (prop) {
                ControlProp.SEAT_BACKREST -> vehicle.getSeat().checkSeatBackrestAnglePositionCapabilities({ config, map ->
                    val position = result(config, map.map { (a, b) -> AreaBound(key(a), b.minValue.toString(), b.maxValue.toString()) })
                    call(err) {
                        vehicle.getSeat().checkMoveSeatBackrestAngleCapabilities({ moveConfig, list ->
                            main.post { ok(position, result(moveConfig, list.map { AreaBound(key(it)) }), null) }
                        }, fail)
                    }
                }, fail)
                ControlProp.AMBIENT_COLOR -> vehicle.getLight().checkAmbientLightBrightnessColorCapabilities({ config, list ->
                    main.post { ok(result(config, list.map { AreaBound(key(it)) }), null, null) }
                }, fail)
                ControlProp.HVAC_FAN_SPEED -> vehicle.getHVAC().checkHvacFanSpeedCapabilities({ config, map ->
                    main.post { ok(result(config, map.map { (a, b) -> AreaBound(key(a), b.minValue.toString(), b.maxValue.toString()) }), null, null) }
                }, fail)
                ControlProp.HVAC_TEMPERATURE -> vehicle.getHVAC().checkHvacTemperatureCapabilities({ config, map, array ->
                    main.post { ok(result(config, map.map { (a, b) -> AreaBound(key(a), b.minValue.toString(), b.maxValue.toString()) }), null, array?.incrementCelsius) }
                }, fail)
            }
        }
    }

    override fun sideAreas(state: SideState, ok: (Access, List<String>) -> Unit, err: (ProbeError) -> Unit) {
        val fail: (Exception) -> Unit = { e -> main.post { err(e.toProbeError()) } }
        val done: (CapabilityConfig, List<SeatArea>) -> Unit = { config, list -> main.post { ok(config.accessType.toAccess(), list.map { key(it) }) } }
        call(err) {
            when (state) {
                SideState.LIGHT_SWITCH -> vehicle.getLight().checkAmbientLightSwitchCapabilities({ config, map ->
                    main.post { ok(config.accessType.toAccess(), map.keys.map { key(it) }) }
                }, fail)
                SideState.HVAC_POWER -> vehicle.getHVAC().checkHvacPowerCapabilities(done, fail)
                SideState.HVAC_AUTO -> vehicle.getHVAC().checkHvacAutoCapabilities(done, fail)
                SideState.HVAC_DUAL -> vehicle.getHVAC().checkHvacDualCapabilities(done, fail)
            }
        }
    }

    override fun read(prop: ControlProp, area: String, ok: (String?) -> Unit, err: (ProbeError) -> Unit) {
        val fail: (Exception) -> Unit = { e -> main.post { err(e.toProbeError()) } }
        call(err) {
            when (prop) {
                ControlProp.SEAT_BACKREST -> vehicle.getSeat().getCurrentSeatBackrestAnglePosition(seatArea(area), { v -> main.post { ok(v?.toString()) } }, fail)
                ControlProp.AMBIENT_COLOR -> vehicle.getLight().getAmbientLightBrightnessColor(seatArea(area), { v -> main.post { ok(v?.let { ControlValues.color(it) }) } }, fail)
                ControlProp.HVAC_FAN_SPEED -> vehicle.getHVAC().getHvacFanSpeed(seatArea(area), { v -> main.post { ok(v?.toString()) } }, fail)
                ControlProp.HVAC_TEMPERATURE -> vehicle.getHVAC().getHvacTemperature(seatArea(area), TemperatureUnit.CELSIUS, { v -> main.post { ok(v?.toString()) } }, fail)
            }
        }
    }

    override fun readSide(state: SideState, area: String, ok: (String?) -> Unit, err: (ProbeError) -> Unit) {
        val fail: (Exception) -> Unit = { e -> main.post { err(e.toProbeError()) } }
        val value: (Any?) -> Unit = { v -> main.post { ok(v?.toString()) } }
        call(err) {
            when (state) {
                SideState.LIGHT_SWITCH -> vehicle.getLight().getAmbientLightSwitch(seatArea(area), value, fail)
                SideState.HVAC_POWER -> vehicle.getHVAC().getHvacPowerState(seatArea(area), value, fail)
                SideState.HVAC_AUTO -> vehicle.getHVAC().getHvacAutoState(seatArea(area), value, fail)
                SideState.HVAC_DUAL -> vehicle.getHVAC().getHvacDualState(seatArea(area), value, fail)
            }
        }
    }

    override fun write(prop: ControlProp, area: String, value: String, ok: (echo: String?) -> Unit, err: (ProbeError) -> Unit) {
        val fail: (Exception) -> Unit = { e -> main.post { err(e.toProbeError()) } }
        call(err) {
            when (prop) {
                ControlProp.AMBIENT_COLOR -> vehicle.getLight().setAmbientLightBrightnessColor(seatArea(area), ControlValues.parse(prop, value) as Int, { _, v ->
                    main.post { ok(ControlValues.color(v)) }
                }, fail)
                ControlProp.HVAC_FAN_SPEED -> vehicle.getHVAC().setHvacFanSpeed(seatArea(area), ControlValues.parse(prop, value) as Int, { _, v ->
                    main.post { ok(v?.toString()) }
                }, fail)
                ControlProp.HVAC_TEMPERATURE -> vehicle.getHVAC().setHvacTemperature(seatArea(area), ControlValues.parse(prop, value) as Float, TemperatureUnit.CELSIUS, { _, v ->
                    main.post { ok(v?.toString()) }
                }, fail)
                ControlProp.SEAT_BACKREST -> throw IllegalArgumentException("the seat moves only by direction")
            }
        }
    }

    override fun writeSide(state: SideState, area: String, value: String, ok: (echo: String?) -> Unit, err: (ProbeError) -> Unit) {
        val fail: (Exception) -> Unit = { e -> main.post { err(e.toProbeError()) } }
        call(err) {
            require(state == SideState.HVAC_AUTO && (value == "true" || value == "false")) { "only the HVAC auto state is written" }
            vehicle.getHVAC().setHvacAuto(seatArea(area), value == "true", { _, v -> main.post { ok(v?.toString()) } }, fail)
        }
    }

    override fun move(area: String, control: Move, ok: (echo: String?) -> Unit, err: (ProbeError) -> Unit) {
        val fail: (Exception) -> Unit = { e -> main.post { err(e.toProbeError()) } }
        val sdk = when (control) {
            Move.BACKWARD -> MoveControl.BACKWARD
            Move.FORWARD -> MoveControl.FORWARD
            Move.STOP -> MoveControl.STOP
        }
        call(err) { vehicle.getSeat().moveSeatBackrestAngle(seatArea(area), sdk, { _, v -> main.post { ok(v?.name) } }, fail) }
    }

    override fun observe(prop: ControlProp, area: String, onUpdate: (String?) -> Unit, onError: (ProbeError) -> Unit): AutoCloseable {
        val target = seatArea(area)
        return when (prop) {
            ControlProp.SEAT_BACKREST -> {
                val listener = object : SeatBackrestAnglePositionListener {
                    override fun onSeatBackrestAnglePositionUpdated(position: Int?) {
                        main.post { onUpdate(position?.toString()) }
                    }

                    override fun onFailed(e: Exception) {
                        main.post { onError(e.toProbeError()) }
                    }
                }
                vehicle.getSeat().registerSeatBackrestAnglePosition(target, listener)
                AutoCloseable { vehicle.getSeat().unregisterSeatBackrestAnglePosition(target, listener) }
            }
            ControlProp.AMBIENT_COLOR -> {
                val listener = object : AmbientLightBrightnessColorListener {
                    override fun onBrightnessColorUpdated(argbColor: Int?) {
                        main.post { onUpdate(argbColor?.let { ControlValues.color(it) }) }
                    }

                    override fun onFailed(e: Exception) {
                        main.post { onError(e.toProbeError()) }
                    }
                }
                vehicle.getLight().registerAmbientLightBrightnessColorListener(target, listener)
                AutoCloseable { vehicle.getLight().unregisterAmbientLightBrightnessColorListener(target, listener) }
            }
            ControlProp.HVAC_FAN_SPEED -> {
                val listener = object : HvacFanSpeedListener {
                    override fun onHvacFanSpeedUpdated(speed: Int?) {
                        main.post { onUpdate(speed?.toString()) }
                    }

                    override fun onFailed(e: Exception) {
                        main.post { onError(e.toProbeError()) }
                    }
                }
                vehicle.getHVAC().registerHvacFanSpeed(target, listener)
                AutoCloseable { vehicle.getHVAC().unregisterHvacFanSpeed(target, listener) }
            }
            ControlProp.HVAC_TEMPERATURE -> {
                val listener = object : HvacTemperatureListener {
                    override fun onHvacTemperatureUpdated(temperature: Float?) {
                        main.post { onUpdate(temperature?.toString()) }
                    }

                    override fun onFailed(e: Exception) {
                        main.post { onError(e.toProbeError()) }
                    }
                }
                vehicle.getHVAC().registerHvacTemperature(target, listener)
                AutoCloseable { vehicle.getHVAC().unregisterHvacTemperature(target, listener) }
            }
        }
    }

    private inline fun call(crossinline err: (ProbeError) -> Unit, block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            main.post { err(e.toProbeError()) }
        }
    }

    private fun result(config: CapabilityConfig, areas: List<AreaBound>) = CapabilityResult(config.accessType.toAccess(), areas)

    private fun key(area: SeatArea) = area.values.map { it.name }.sorted().joinToString("+")

    private fun seatArea(key: String) = SeatArea(*key.split('+').map { SeatValue.valueOf(it) }.toTypedArray())

    private fun AccessType?.toAccess(): Access = when (this) {
        AccessType.READ -> Access.READ
        AccessType.WRITE -> Access.WRITE
        AccessType.READ_WRITE -> Access.READ_WRITE
        AccessType.NONE, null -> Access.NONE
    }
}
