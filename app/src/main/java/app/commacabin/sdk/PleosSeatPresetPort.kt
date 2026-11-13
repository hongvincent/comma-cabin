package app.commacabin.sdk

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.MoveControl
import app.commacabin.control.SeatAxis
import app.commacabin.control.SeatPresetPort
import app.commacabin.probe.Access
import app.commacabin.probe.Dispatch
import app.commacabin.probe.ProbeError

/**
 * Debug-only public SDK calls for the seat preset check: capability, position reads, selectSeatMemory and
 * per-axis STOP. selectSeatMemory has no completion callback; nothing here turns its return into a result.
 * Every SDK call runs on [lane] as [owner]; callbacks are posted to the main thread.
 */
class PleosSeatPresetPort(private val vehicle: Vehicle, private val lane: SdkLane, private val owner: Any) : SeatPresetPort {

    override fun capability(ok: (Access, Map<String, IntRange>) -> Unit, err: (ProbeError) -> Unit) = lane.readCall(owner, "capability seat memory", err) {
        vehicle.getSeat().checkSeatMemoryCapabilities({ config, map ->
            val numbers = map.map { (a, b) -> key(a) to (b.minValue..b.maxValue) }.toMap()
            lane.post { ok(config.accessType.toAccess(), numbers) }
        }, { e -> lane.post { err(e.toProbeError()) } })
    }

    override fun position(axis: SeatAxis, area: String, ok: (Int?) -> Unit, err: (ProbeError) -> Unit) = lane.readCall(owner, "read seat $axis $area", err) {
        val seat = vehicle.getSeat()
        val value: (Int?) -> Unit = { v -> lane.post { ok(v) } }
        val fail: (Exception) -> Unit = { e -> lane.post { err(e.toProbeError()) } }
        when (axis) {
            SeatAxis.FORE_AFT -> seat.getCurrentSeatForeAftPosition(seatArea(area), value, fail)
            SeatAxis.BACKREST -> seat.getCurrentSeatBackrestAnglePosition(seatArea(area), value, fail)
            SeatAxis.HEIGHT -> seat.getCurrentSeatHeightPosition(seatArea(area), value, fail)
            SeatAxis.TILT -> seat.getCurrentSeatTiltPosition(seatArea(area), value, fail)
            SeatAxis.LUMBAR -> seat.getCurrentSeatLumbarForeAftPosition(seatArea(area), value, fail)
        }
    }

    override fun select(area: String, number: Int, motionKey: String, dispatch: Dispatch, onFailed: (ProbeError) -> Unit) =
        lane.writeCall(owner, "select seat memory $number $area", dispatch, onFailed, motionKey) {
            vehicle.getSeat().selectSeatMemory(seatArea(area), number) { e -> lane.post { onFailed(e.toProbeError()) } }
        }

    override fun endMotion(motionKey: String) = lane.endMotion(owner, motionKey)

    override fun stop(axis: SeatAxis, area: String, ok: () -> Unit, err: (ProbeError) -> Unit) {
        // The same STOP already waits (it goes first and answers): this one is reported, never left unanswered.
        if (!lane.stopCall(owner, "STOP seat $axis $area", err) { stopNow(axis, area, ok, err) }) lane.post { err(STOP_ALREADY_QUEUED) }
    }

    private fun stopNow(axis: SeatAxis, area: String, ok: () -> Unit, err: (ProbeError) -> Unit) {
        val seat = vehicle.getSeat()
        val done: (SeatArea, MoveControl?) -> Unit = { _, _ -> lane.post { ok() } }
        val fail: (Exception) -> Unit = { e -> lane.post { err(e.toProbeError()) } }
        when (axis) {
            SeatAxis.FORE_AFT -> seat.moveSeatForeAft(seatArea(area), MoveControl.STOP, done, fail)
            SeatAxis.BACKREST -> seat.moveSeatBackrestAngle(seatArea(area), MoveControl.STOP, done, fail)
            SeatAxis.HEIGHT -> seat.moveSeatHeight(seatArea(area), MoveControl.STOP, done, fail)
            SeatAxis.TILT -> seat.moveSeatTilt(seatArea(area), MoveControl.STOP, done, fail)
            SeatAxis.LUMBAR -> seat.moveSeatLumbarForeAft(seatArea(area), MoveControl.STOP, done, fail)
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
