package app.commacabin.sdk

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.MoveControl
import android.os.Handler
import app.commacabin.control.SeatAxis
import app.commacabin.control.SeatPresetPort
import app.commacabin.probe.Access
import app.commacabin.probe.ProbeError

/**
 * Debug-only public SDK calls for the seat preset check: capability, position reads, selectSeatMemory and
 * per-axis STOP. selectSeatMemory has no completion callback; nothing here turns its return into a result.
 * Callbacks are re-posted to [main].
 */
class PleosSeatPresetPort(private val vehicle: Vehicle, private val main: Handler) : SeatPresetPort {

    override fun capability(ok: (Access, Map<String, IntRange>) -> Unit, err: (ProbeError) -> Unit) = call(err) {
        vehicle.getSeat().checkSeatMemoryCapabilities({ config, map ->
            val numbers = map.map { (a, b) -> key(a) to (b.minValue..b.maxValue) }.toMap()
            main.post { ok(config.accessType.toAccess(), numbers) }
        }, { e -> main.post { err(e.toProbeError()) } })
    }

    override fun position(axis: SeatAxis, area: String, ok: (Int?) -> Unit, err: (ProbeError) -> Unit) = call(err) {
        val seat = vehicle.getSeat()
        val value: (Int?) -> Unit = { v -> main.post { ok(v) } }
        val fail: (Exception) -> Unit = { e -> main.post { err(e.toProbeError()) } }
        when (axis) {
            SeatAxis.FORE_AFT -> seat.getCurrentSeatForeAftPosition(seatArea(area), value, fail)
            SeatAxis.BACKREST -> seat.getCurrentSeatBackrestAnglePosition(seatArea(area), value, fail)
            SeatAxis.HEIGHT -> seat.getCurrentSeatHeightPosition(seatArea(area), value, fail)
            SeatAxis.TILT -> seat.getCurrentSeatTiltPosition(seatArea(area), value, fail)
            SeatAxis.LUMBAR -> seat.getCurrentSeatLumbarForeAftPosition(seatArea(area), value, fail)
        }
    }

    override fun select(area: String, number: Int, onFailed: (ProbeError) -> Unit) {
        vehicle.getSeat().selectSeatMemory(seatArea(area), number) { e -> main.post { onFailed(e.toProbeError()) } }
    }

    override fun stop(axis: SeatAxis, area: String, ok: () -> Unit, err: (ProbeError) -> Unit) = call(err) {
        val seat = vehicle.getSeat()
        val done: (SeatArea, MoveControl?) -> Unit = { _, _ -> main.post { ok() } }
        val fail: (Exception) -> Unit = { e -> main.post { err(e.toProbeError()) } }
        when (axis) {
            SeatAxis.FORE_AFT -> seat.moveSeatForeAft(seatArea(area), MoveControl.STOP, done, fail)
            SeatAxis.BACKREST -> seat.moveSeatBackrestAngle(seatArea(area), MoveControl.STOP, done, fail)
            SeatAxis.HEIGHT -> seat.moveSeatHeight(seatArea(area), MoveControl.STOP, done, fail)
            SeatAxis.TILT -> seat.moveSeatTilt(seatArea(area), MoveControl.STOP, done, fail)
            SeatAxis.LUMBAR -> seat.moveSeatLumbarForeAft(seatArea(area), MoveControl.STOP, done, fail)
        }
    }

    /** A synchronous throw is reported like an onFailed callback. */
    private inline fun call(crossinline err: (ProbeError) -> Unit, block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            main.post { err(e.toProbeError()) }
        }
    }

    private fun key(area: SeatArea) = area.values.map { it.name }.sorted().joinToString("+")

    private fun seatArea(key: String) = SeatArea(*key.split('+').map { SeatValue.valueOf(it) }.toTypedArray())

    private fun AccessType?.toAccess(): Access = when (this) {
        AccessType.READ -> Access.READ
        AccessType.WRITE -> Access.WRITE
        AccessType.READ_WRITE -> Access.READ_WRITE
        AccessType.NONE, null -> Access.NONE
    }
}
