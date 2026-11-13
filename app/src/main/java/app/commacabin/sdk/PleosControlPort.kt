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
import app.commacabin.control.ControlPort
import app.commacabin.control.ControlProp
import app.commacabin.control.ControlValues
import app.commacabin.control.Move
import app.commacabin.control.SideState
import app.commacabin.probe.Access
import app.commacabin.probe.Dispatch
import app.commacabin.probe.ProbeError
import app.commacabin.support.AreaBound
import app.commacabin.support.CapabilityResult

/**
 * Public Vehicle SDK 2.3.2 only: capability, current value, set/move and listeners. Every SDK entry runs on
 * [lane]'s worker, never on the main thread; callbacks are posted to the main thread. [owner] is the lane owner
 * [SdkLane.close] cancels.
 */
class PleosControlPort(private val vehicle: Vehicle, private val lane: SdkLane, private val owner: Any) : ControlPort {

    override fun capability(
        prop: ControlProp,
        ok: (cap: CapabilityResult, moveAreas: CapabilityResult?, increment: Float?) -> Unit,
        err: (ProbeError) -> Unit,
    ) {
        val fail: (Exception) -> Unit = { e -> lane.post { err(e.toProbeError()) } }
        lane.readCall(owner, "capability $prop", err) {
            when (prop) {
                ControlProp.SEAT_BACKREST -> vehicle.getSeat().checkSeatBackrestAnglePositionCapabilities({ config, map ->
                    val position = result(config, map.map { (a, b) -> AreaBound(key(a), b.minValue.toString(), b.maxValue.toString()) })
                    // The second capability is a new SDK entry: back on the lane, not on this callback's thread.
                    lane.readCall(owner, "capability SEAT_BACKREST move", err) {
                        vehicle.getSeat().checkMoveSeatBackrestAngleCapabilities({ moveConfig, list ->
                            lane.post { ok(position, result(moveConfig, list.map { AreaBound(key(it)) }), null) }
                        }, fail)
                    }
                }, fail)
                ControlProp.AMBIENT_COLOR -> vehicle.getLight().checkAmbientLightBrightnessColorCapabilities({ config, list ->
                    lane.post { ok(result(config, list.map { AreaBound(key(it)) }), null, null) }
                }, fail)
                ControlProp.HVAC_FAN_SPEED -> vehicle.getHVAC().checkHvacFanSpeedCapabilities({ config, map ->
                    lane.post { ok(result(config, map.map { (a, b) -> AreaBound(key(a), b.minValue.toString(), b.maxValue.toString()) }), null, null) }
                }, fail)
                ControlProp.HVAC_TEMPERATURE -> vehicle.getHVAC().checkHvacTemperatureCapabilities({ config, map, array ->
                    lane.post { ok(result(config, map.map { (a, b) -> AreaBound(key(a), b.minValue.toString(), b.maxValue.toString()) }), null, array?.incrementCelsius) }
                }, fail)
            }
        }
    }

    override fun sideAreas(state: SideState, ok: (Access, List<String>) -> Unit, err: (ProbeError) -> Unit) {
        val fail: (Exception) -> Unit = { e -> lane.post { err(e.toProbeError()) } }
        val done: (CapabilityConfig, List<SeatArea>) -> Unit = { config, list -> lane.post { ok(config.accessType.toAccess(), list.map { key(it) }) } }
        lane.readCall(owner, "capability side $state", err) {
            when (state) {
                SideState.LIGHT_SWITCH -> vehicle.getLight().checkAmbientLightSwitchCapabilities({ config, map ->
                    lane.post { ok(config.accessType.toAccess(), map.keys.map { key(it) }) }
                }, fail)
                SideState.HVAC_POWER -> vehicle.getHVAC().checkHvacPowerCapabilities(done, fail)
                SideState.HVAC_AUTO -> vehicle.getHVAC().checkHvacAutoCapabilities(done, fail)
                SideState.HVAC_DUAL -> vehicle.getHVAC().checkHvacDualCapabilities(done, fail)
            }
        }
    }

    override fun read(prop: ControlProp, area: String, ok: (String?) -> Unit, err: (ProbeError) -> Unit) {
        val fail: (Exception) -> Unit = { e -> lane.post { err(e.toProbeError()) } }
        lane.readCall(owner, "read $prop $area", err) {
            when (prop) {
                ControlProp.SEAT_BACKREST -> vehicle.getSeat().getCurrentSeatBackrestAnglePosition(seatArea(area), { v -> lane.post { ok(v?.toString()) } }, fail)
                ControlProp.AMBIENT_COLOR -> vehicle.getLight().getAmbientLightBrightnessColor(seatArea(area), { v -> lane.post { ok(v?.let { ControlValues.color(it) }) } }, fail)
                ControlProp.HVAC_FAN_SPEED -> vehicle.getHVAC().getHvacFanSpeed(seatArea(area), { v -> lane.post { ok(v?.toString()) } }, fail)
                ControlProp.HVAC_TEMPERATURE -> vehicle.getHVAC().getHvacTemperature(seatArea(area), TemperatureUnit.CELSIUS, { v -> lane.post { ok(v?.toString()) } }, fail)
            }
        }
    }

    override fun readSide(state: SideState, area: String, ok: (String?) -> Unit, err: (ProbeError) -> Unit) {
        val fail: (Exception) -> Unit = { e -> lane.post { err(e.toProbeError()) } }
        val value: (Any?) -> Unit = { v -> lane.post { ok(v?.toString()) } }
        lane.readCall(owner, "read side $state $area", err) {
            when (state) {
                SideState.LIGHT_SWITCH -> vehicle.getLight().getAmbientLightSwitch(seatArea(area), value, fail)
                SideState.HVAC_POWER -> vehicle.getHVAC().getHvacPowerState(seatArea(area), value, fail)
                SideState.HVAC_AUTO -> vehicle.getHVAC().getHvacAutoState(seatArea(area), value, fail)
                SideState.HVAC_DUAL -> vehicle.getHVAC().getHvacDualState(seatArea(area), value, fail)
            }
        }
    }

    override fun write(prop: ControlProp, area: String, value: String, dispatch: Dispatch, ok: (echo: String?) -> Unit, err: (ProbeError) -> Unit) {
        val fail: (Exception) -> Unit = { e -> lane.post { err(e.toProbeError()) } }
        lane.writeCall(owner, "write $prop $area $value", dispatch, err) {
            when (prop) {
                ControlProp.AMBIENT_COLOR -> vehicle.getLight().setAmbientLightBrightnessColor(seatArea(area), ControlValues.parse(prop, value) as Int, { _, v ->
                    lane.post { ok(ControlValues.color(v)) }
                }, fail)
                ControlProp.HVAC_FAN_SPEED -> vehicle.getHVAC().setHvacFanSpeed(seatArea(area), ControlValues.parse(prop, value) as Int, { _, v ->
                    lane.post { ok(v?.toString()) }
                }, fail)
                ControlProp.HVAC_TEMPERATURE -> vehicle.getHVAC().setHvacTemperature(seatArea(area), ControlValues.parse(prop, value) as Float, TemperatureUnit.CELSIUS, { _, v ->
                    lane.post { ok(v?.toString()) }
                }, fail)
                ControlProp.SEAT_BACKREST -> throw IllegalArgumentException("the seat moves only by direction")
            }
        }
    }

    override fun writeSide(state: SideState, area: String, value: String, dispatch: Dispatch, ok: (echo: String?) -> Unit, err: (ProbeError) -> Unit) {
        val fail: (Exception) -> Unit = { e -> lane.post { err(e.toProbeError()) } }
        lane.writeCall(owner, "write side $state $area $value", dispatch, err) {
            require(state == SideState.HVAC_AUTO && (value == "true" || value == "false")) { "only the HVAC auto state is written" }
            vehicle.getHVAC().setHvacAuto(seatArea(area), value == "true", { _, v -> lane.post { ok(v?.toString()) } }, fail)
        }
    }

    override fun move(area: String, control: Move, motionKey: String, dispatch: Dispatch, ok: (echo: String?) -> Unit, err: (ProbeError) -> Unit) {
        val fail: (Exception) -> Unit = { e -> lane.post { err(e.toProbeError()) } }
        val sdk = when (control) {
            Move.BACKWARD -> MoveControl.BACKWARD
            Move.FORWARD -> MoveControl.FORWARD
            Move.STOP -> MoveControl.STOP
        }
        val call = { vehicle.getSeat().moveSeatBackrestAngle(seatArea(area), sdk, { _, v -> lane.post { ok(v?.name) } }, fail) }
        if (control != Move.STOP) return lane.writeCall(owner, "move seat backrest $control $area", dispatch, err, motionKey = motionKey, block = call)
        // The same STOP already waits (it goes first and answers): this one is reported, never left unanswered.
        if (!lane.stopCall(owner, "STOP seat backrest $area", err, call)) lane.post { err(STOP_ALREADY_QUEUED) }
    }

    override fun endMotion(motionKey: String) = lane.endMotion(owner, motionKey)

    /** Registered and removed on the lane; the handle returns at once. */
    override fun observe(prop: ControlProp, area: String, onUpdate: (String?) -> Unit, onError: (ProbeError) -> Unit, onRegistered: (ProbeError?) -> Unit): AutoCloseable {
        val target = seatArea(area)
        return lane.subscribe(owner, "listener $prop $area", onRegistered) { register(prop, target, onUpdate, onError) }
    }

    private fun register(prop: ControlProp, target: SeatArea, onUpdate: (String?) -> Unit, onError: (ProbeError) -> Unit): AutoCloseable =
        when (prop) {
            ControlProp.SEAT_BACKREST -> {
                val listener = object : SeatBackrestAnglePositionListener {
                    override fun onSeatBackrestAnglePositionUpdated(position: Int?) {
                        lane.post { onUpdate(position?.toString()) }
                    }

                    override fun onFailed(e: Exception) {
                        lane.post { onError(e.toProbeError()) }
                    }
                }
                vehicle.getSeat().registerSeatBackrestAnglePosition(target, listener)
                AutoCloseable { vehicle.getSeat().unregisterSeatBackrestAnglePosition(target, listener) }
            }
            ControlProp.AMBIENT_COLOR -> {
                val listener = object : AmbientLightBrightnessColorListener {
                    override fun onBrightnessColorUpdated(argbColor: Int?) {
                        lane.post { onUpdate(argbColor?.let { ControlValues.color(it) }) }
                    }

                    override fun onFailed(e: Exception) {
                        lane.post { onError(e.toProbeError()) }
                    }
                }
                vehicle.getLight().registerAmbientLightBrightnessColorListener(target, listener)
                AutoCloseable { vehicle.getLight().unregisterAmbientLightBrightnessColorListener(target, listener) }
            }
            ControlProp.HVAC_FAN_SPEED -> {
                val listener = object : HvacFanSpeedListener {
                    override fun onHvacFanSpeedUpdated(speed: Int?) {
                        lane.post { onUpdate(speed?.toString()) }
                    }

                    override fun onFailed(e: Exception) {
                        lane.post { onError(e.toProbeError()) }
                    }
                }
                vehicle.getHVAC().registerHvacFanSpeed(target, listener)
                AutoCloseable { vehicle.getHVAC().unregisterHvacFanSpeed(target, listener) }
            }
            ControlProp.HVAC_TEMPERATURE -> {
                val listener = object : HvacTemperatureListener {
                    override fun onHvacTemperatureUpdated(temperature: Float?) {
                        lane.post { onUpdate(temperature?.toString()) }
                    }

                    override fun onFailed(e: Exception) {
                        lane.post { onError(e.toProbeError()) }
                    }
                }
                vehicle.getHVAC().registerHvacTemperature(target, listener)
                AutoCloseable { vehicle.getHVAC().unregisterHvacTemperature(target, listener) }
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
