package app.commacabin.sdk

import ai.pleos.playground.vehicle.api.Light
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.listener.AmbientLightBrightnessListener
import app.commacabin.probe.Access
import app.commacabin.probe.AreaId
import app.commacabin.probe.BrightnessCapability
import app.commacabin.probe.Dispatch
import app.commacabin.probe.LightPort
import app.commacabin.probe.ProbeError
import app.commacabin.probe.SwitchState

/**
 * Pleos Vehicle SDK ambient-brightness calls, all entered on [lane]'s worker (never on main). Callbacks are posted
 * to the main thread. [owner] is the lane owner whose tasks [SdkLane.close] cancels.
 */
class PleosLightPort(private val light: Light, private val lane: SdkLane, private val owner: Any) : LightPort {
    override fun checkBrightnessCapabilities(onResult: (BrightnessCapability) -> Unit, onError: (ProbeError) -> Unit) {
        lane.readCall(owner, "capability ambient brightness", onError) {
            light.checkAmbientLightBrightnessCapabilities(
                { config, areas ->
                    val bounds = areas.mapNotNull { (area, b) ->
                        val min = b.minValue
                        val max = b.maxValue
                        if (min > max) null else areaId(area) to (min..max)
                    }.toMap()
                    lane.post { onResult(BrightnessCapability(config.accessType.toAccess(), bounds)) }
                },
                { e -> lane.post { onError(e.toProbeError()) } },
            )
        }
    }

    override fun getSwitch(area: AreaId, onResult: (SwitchState?) -> Unit, onError: (ProbeError) -> Unit) {
        lane.readCall(owner, "read ambient switch", onError) {
            light.getAmbientLightSwitch(
                seatArea(area),
                { value -> lane.post { onResult(value?.toSwitchState()) } },
                { e -> lane.post { onError(e.toProbeError()) } },
            )
        }
    }

    override fun getBrightness(area: AreaId, onResult: (Int?) -> Unit, onError: (ProbeError) -> Unit) {
        lane.readCall(owner, "read ambient brightness", onError) {
            light.getAmbientLightBrightness(
                seatArea(area),
                { value -> lane.post { onResult(value) } },
                { e -> lane.post { onError(e.toProbeError()) } },
            )
        }
    }

    override fun setBrightness(area: AreaId, value: Int, dispatch: Dispatch, onComplete: (Int?) -> Unit, onError: (ProbeError) -> Unit) {
        lane.writeCall(owner, "write ambient brightness $value", dispatch, onError) {
            light.setAmbientLightBrightness(
                seatArea(area),
                value,
                { _, returned -> lane.post { onComplete(returned) } },
                { e -> lane.post { onError(e.toProbeError()) } },
            )
        }
    }

    /** Registered and removed on the lane; the handle returns at once. */
    override fun observeBrightness(area: AreaId, onListening: (ProbeError?) -> Unit, onUpdate: (Int?) -> Unit): AutoCloseable {
        val seat = seatArea(area)
        val listener = object : AmbientLightBrightnessListener {
            override fun onBrightnessUpdated(brightness: Int?) {
                lane.post { onUpdate(brightness) }
            }

            override fun onFailed(e: Exception) {
                lane.post { onListening(e.toProbeError()) }
            }
        }
        return lane.subscribe(owner, "ambient brightness listener", onListening) {
            light.registerAmbientLightBrightnessListener(seat, listener)
            AutoCloseable { light.unregisterAmbientLightBrightnessListener(seat, listener) }
        }
    }

    private fun areaId(area: SeatArea) = AreaId(area.values.map { it.name }.sorted().joinToString("+"))

    private fun seatArea(area: AreaId) = SeatArea(*area.key.split('+').map { SeatValue.valueOf(it) }.toTypedArray())

    private fun AccessType?.toAccess(): Access = when (this) {
        AccessType.READ -> Access.READ
        AccessType.WRITE -> Access.WRITE
        AccessType.READ_WRITE -> Access.READ_WRITE
        AccessType.NONE, null -> Access.NONE
    }

    private fun LightSwitch.toSwitchState(): SwitchState = when (this) {
        LightSwitch.OFF -> SwitchState.OFF
        LightSwitch.ON -> SwitchState.ON
        LightSwitch.DAYTIME_RUNNING -> SwitchState.DAYTIME_RUNNING
        LightSwitch.AUTOMATIC -> SwitchState.AUTOMATIC
    }
}

private val ERROR_CODE_NAME = Regex("ERROR_[A-Z_]+")

/**
 * The SDK's exception type is obfuscated and ErrorCode lives in a runtime-only artifact,
 * so the code name is taken from the message only when the message contains one.
 */
internal fun Exception.toProbeError(): ProbeError {
    val text = message
    return ProbeError(javaClass.name, text, text?.let { ERROR_CODE_NAME.find(it)?.value })
}
