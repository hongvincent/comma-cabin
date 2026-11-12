package app.commacabin.sdk

import ai.pleos.playground.vehicle.api.Light
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.listener.AmbientLightBrightnessListener
import android.os.Handler
import app.commacabin.probe.Access
import app.commacabin.probe.AreaId
import app.commacabin.probe.BrightnessCapability
import app.commacabin.probe.LightPort
import app.commacabin.probe.ProbeError
import app.commacabin.probe.SwitchState

/** Pleos Vehicle SDK ambient-brightness calls. Callbacks are re-posted to [main]. */
class PleosLightPort(private val light: Light, private val main: Handler) : LightPort {
    override fun checkBrightnessCapabilities(onResult: (BrightnessCapability) -> Unit, onError: (ProbeError) -> Unit) {
        call(onError) {
            light.checkAmbientLightBrightnessCapabilities(
                { config, areas ->
                    val bounds = areas.mapNotNull { (area, b) ->
                        val min = b.minValue
                        val max = b.maxValue
                        if (min > max) null else areaId(area) to (min..max)
                    }.toMap()
                    main.post { onResult(BrightnessCapability(config.accessType.toAccess(), bounds)) }
                },
                { e -> main.post { onError(e.toProbeError()) } },
            )
        }
    }

    override fun getSwitch(area: AreaId, onResult: (SwitchState?) -> Unit, onError: (ProbeError) -> Unit) {
        call(onError) {
            light.getAmbientLightSwitch(
                seatArea(area),
                { value -> main.post { onResult(value?.toSwitchState()) } },
                { e -> main.post { onError(e.toProbeError()) } },
            )
        }
    }

    override fun getBrightness(area: AreaId, onResult: (Int?) -> Unit, onError: (ProbeError) -> Unit) {
        call(onError) {
            light.getAmbientLightBrightness(
                seatArea(area),
                { value -> main.post { onResult(value) } },
                { e -> main.post { onError(e.toProbeError()) } },
            )
        }
    }

    override fun setBrightness(area: AreaId, value: Int, onComplete: (Int?) -> Unit, onError: (ProbeError) -> Unit) {
        call(onError) {
            light.setAmbientLightBrightness(
                seatArea(area),
                value,
                { _, returned -> main.post { onComplete(returned) } },
                { e -> main.post { onError(e.toProbeError()) } },
            )
        }
    }

    override fun observeBrightness(area: AreaId, onUpdate: (Int?) -> Unit): AutoCloseable {
        val seat = seatArea(area)
        val listener = object : AmbientLightBrightnessListener {
            override fun onBrightnessUpdated(brightness: Int?) {
                main.post { onUpdate(brightness) }
            }

            override fun onFailed(e: Exception) {
                main.post { onUpdate(null) }
            }
        }
        light.registerAmbientLightBrightnessListener(seat, listener)
        return AutoCloseable { light.unregisterAmbientLightBrightnessListener(seat, listener) }
    }

    /** A synchronous throw is reported like an onFailed callback. */
    private inline fun call(crossinline onError: (ProbeError) -> Unit, block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            main.post { onError(e.toProbeError()) }
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
