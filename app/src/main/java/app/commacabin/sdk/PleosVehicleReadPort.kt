package app.commacabin.sdk

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.SeatBackrestAnglePositionListener
import app.commacabin.probe.Access
import app.commacabin.probe.ProbeError
import app.commacabin.support.AreaBound
import app.commacabin.support.CapabilityResult
import app.commacabin.support.Feature
import app.commacabin.support.VehicleReadPort
import java.util.Locale

/**
 * Read-only Pleos Vehicle SDK calls for the support diagnostic: capability, current value and
 * door/seat subscriptions. It never calls a set/move API. Every SDK entry runs on [lane]'s worker, never on the
 * main thread; callbacks are posted to the main thread. [owner] is the lane owner [SdkLane.close] cancels.
 */
class PleosVehicleReadPort(private val vehicle: Vehicle, private val lane: SdkLane, private val owner: Any) : VehicleReadPort {

    override fun capability(feature: Feature, ok: (CapabilityResult) -> Unit, err: (ProbeError) -> Unit) {
        val fail: (Exception) -> Unit = { e -> lane.post { err(e.toProbeError()) } }
        fun done(config: CapabilityConfig, areas: List<AreaBound>, extra: String? = null) {
            lane.post { ok(CapabilityResult(config.accessType.toAccess(), areas, extra)) }
        }
        lane.readCall(owner, "capability $feature", err) {
            when (feature) {
                Feature.DOOR_POSITION -> vehicle.getDoor().checkDoorPositionCapabilities({ config, map ->
                    done(config, map.map { (a, b) -> AreaBound(key(a), b.minValue.toString(), b.maxValue.toString()) })
                }, fail)
                Feature.SEAT_BACKREST_POSITION -> vehicle.getSeat().checkSeatBackrestAnglePositionCapabilities({ config, map ->
                    done(config, map.map { (a, b) -> AreaBound(key(a), b.minValue.toString(), b.maxValue.toString()) })
                }, fail)
                Feature.SEAT_BACKREST_MOVE -> vehicle.getSeat().checkMoveSeatBackrestAngleCapabilities({ config, list ->
                    done(config, list.map { AreaBound(key(it)) })
                }, fail)
                // Capability only: the SDK's bounds as they are, no select/save call.
                Feature.SEAT_MEMORY_SUPPORT -> vehicle.getSeat().checkSeatMemoryCapabilities({ config, map ->
                    done(config, map.map { (a, b) -> AreaBound(key(a), b.minValue.toString(), b.maxValue.toString()) })
                }, fail)
                Feature.HVAC_POWER -> vehicle.getHVAC().checkHvacPowerCapabilities({ config, list ->
                    done(config, list.map { AreaBound(key(it)) })
                }, fail)
                Feature.HVAC_AUTO -> vehicle.getHVAC().checkHvacAutoCapabilities({ config, list ->
                    done(config, list.map { AreaBound(key(it)) })
                }, fail)
                Feature.HVAC_DUAL -> vehicle.getHVAC().checkHvacDualCapabilities({ config, list ->
                    done(config, list.map { AreaBound(key(it)) })
                }, fail)
                Feature.HVAC_FAN_SPEED -> vehicle.getHVAC().checkHvacFanSpeedCapabilities({ config, map ->
                    done(config, map.map { (a, b) -> AreaBound(key(a), b.minValue.toString(), b.maxValue.toString()) })
                }, fail)
                Feature.HVAC_TEMPERATURE -> vehicle.getHVAC().checkHvacTemperatureCapabilities({ config, map, array ->
                    val step = array?.let { "간격 ${it.incrementCelsius}°C, 설정 범위 ${it.minCelsius}..${it.maxCelsius}°C, raw ${it.raw}" } ?: "온도 설정 배열 없음"
                    done(config, map.map { (a, b) -> AreaBound(key(a), b.minValue.toString(), b.maxValue.toString()) }, step)
                }, fail)
                Feature.AMBIENT_COLOR -> vehicle.getLight().checkAmbientLightBrightnessColorCapabilities({ config, list ->
                    done(config, list.map { AreaBound(key(it)) })
                }, fail)
            }
        }
    }

    override fun current(feature: Feature, area: String, ok: (String?) -> Unit, err: (ProbeError) -> Unit) {
        val fail: (Exception) -> Unit = { e -> lane.post { err(e.toProbeError()) } }
        val value: (Any?) -> Unit = { v -> lane.post { ok(v?.toString()) } }
        lane.readCall(owner, "read $feature $area", err) {
            when (feature) {
                Feature.DOOR_POSITION -> vehicle.getDoor().getCurrentDoorPosition(doorArea(area), value, fail)
                Feature.SEAT_BACKREST_POSITION -> vehicle.getSeat().getCurrentSeatBackrestAnglePosition(seatArea(area), value, fail)
                Feature.HVAC_POWER -> vehicle.getHVAC().getHvacPowerState(seatArea(area), value, fail)
                Feature.HVAC_AUTO -> vehicle.getHVAC().getHvacAutoState(seatArea(area), value, fail)
                Feature.HVAC_DUAL -> vehicle.getHVAC().getHvacDualState(seatArea(area), value, fail)
                Feature.HVAC_FAN_SPEED -> vehicle.getHVAC().getHvacFanSpeed(seatArea(area), value, fail)
                Feature.HVAC_TEMPERATURE -> vehicle.getHVAC().getHvacTemperature(seatArea(area), TemperatureUnit.CELSIUS, value, fail)
                Feature.AMBIENT_COLOR -> vehicle.getLight().getAmbientLightBrightnessColor(seatArea(area), { v ->
                    lane.post { ok(v?.let { String.format(Locale.ROOT, "0x%08X (raw %d)", it, it) }) }
                }, fail)
                Feature.SEAT_BACKREST_MOVE -> lane.post { err(ProbeError("not-read", "이동 기능은 현재값을 조회하지 않음", null)) }
                Feature.SEAT_MEMORY_SUPPORT -> lane.post { err(ProbeError("not-read", "프리셋 기능은 현재값을 조회하지 않음", null)) }
            }
        }
    }

    /**
     * Registered and removed on the lane; the handle returns at once. A registration that did not happen (queue
     * full, a throw) reaches [onError]. Null: this feature is not subscribed.
     */
    override fun observe(feature: Feature, area: String, onUpdate: (String?) -> Unit, onError: (ProbeError) -> Unit): AutoCloseable? =
        when (feature) {
            Feature.DOOR_POSITION, Feature.SEAT_BACKREST_POSITION ->
                lane.subscribe(owner, "listener $feature $area", { e -> e?.let(onError) }) { register(feature, area, onUpdate, onError) }
            else -> null
        }

    private fun register(feature: Feature, area: String, onUpdate: (String?) -> Unit, onError: (ProbeError) -> Unit): AutoCloseable? =
        when (feature) {
            Feature.DOOR_POSITION -> {
                val doorArea = doorArea(area)
                val listener = object : DoorPositionListener {
                    override fun onPositionUpdated(pos: Int?) {
                        lane.post { onUpdate(pos?.toString()) }
                    }

                    override fun onFailed(e: Exception) {
                        lane.post { onError(e.toProbeError()) }
                    }
                }
                vehicle.getDoor().registerDoorPosition(doorArea, listener)
                AutoCloseable { vehicle.getDoor().unregisterDoorPosition(doorArea, listener) }
            }
            Feature.SEAT_BACKREST_POSITION -> {
                val seatArea = seatArea(area)
                val listener = object : SeatBackrestAnglePositionListener {
                    override fun onSeatBackrestAnglePositionUpdated(position: Int?) {
                        lane.post { onUpdate(position?.toString()) }
                    }

                    override fun onFailed(e: Exception) {
                        lane.post { onError(e.toProbeError()) }
                    }
                }
                vehicle.getSeat().registerSeatBackrestAnglePosition(seatArea, listener)
                AutoCloseable { vehicle.getSeat().unregisterSeatBackrestAnglePosition(seatArea, listener) }
            }
            else -> null
        }

    private fun key(area: SeatArea) = area.values.map { it.name }.sorted().joinToString("+")

    private fun key(area: DoorArea) = area.values.map { it.name }.sorted().joinToString("+")

    private fun seatArea(key: String) = SeatArea(*key.split('+').map { SeatValue.valueOf(it) }.toTypedArray())

    private fun doorArea(key: String) = DoorArea(*key.split('+').map { DoorValue.valueOf(it) }.toTypedArray())

    private fun AccessType?.toAccess(): Access = when (this) {
        AccessType.READ -> Access.READ
        AccessType.WRITE -> Access.WRITE
        AccessType.READ_WRITE -> Access.READ_WRITE
        AccessType.NONE, null -> Access.NONE
    }
}
