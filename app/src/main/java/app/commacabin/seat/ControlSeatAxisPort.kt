package app.commacabin.seat

import app.commacabin.control.ControlPort
import app.commacabin.control.ControlProp
import app.commacabin.control.ControlValues
import app.commacabin.control.DRIVER
import app.commacabin.control.Move
import app.commacabin.control.SeatAxis
import app.commacabin.probe.Dispatch
import app.commacabin.probe.ProbeError

/**
 * STEP 27: [SeatAxisPort] over the existing [ControlPort] (in the app, PleosControlPort on the SDK lane), for the driver
 * seat (`ROW_1_LEFT`) backrest only, through [ControlProp.SEAT_BACKREST] read/observe/move and endMotion. It adds no
 * SDK call, thread, queue, gate, timer or session filter of its own; callbacks arrive as the ControlPort delivers them.
 * Not created by the app yet: whether the backrest is supported and its position can be trusted, the measured limits,
 * and the owner that creates this are the next steps' job. Other axes are refused, never reported as done.
 *
 * - Positions: the ControlPort's text must be a canonical Int ("0" is 0). null stays null (no value). Any other text
 *   (fraction, spaces, out of Int range, leading zeros) is an [INVALID_POSITION] error, the same for read and listener.
 * - MOVE: the caller's [Dispatch] goes down unchanged. Only a call that certainly never reached the ControlPort (other
 *   axis, STOP as a MOVE, blank motion key) gets onNotSent here; ControlPort errors pass through as they are.
 * - STOP: the ControlPort's own STOP path (first in line, never gated; it uses neither motion key nor dispatch). A
 *   refusal such as StopAlreadyQueued comes back as an error. It passes on any backrest STOP it is asked for; who
 *   owns the seat globally and how an earlier run's motion is recovered is not decided or implemented here.
 * - [endMotion] passes the core's exact key; nothing here releases a motion by itself.
 */
class ControlSeatAxisPort(private val port: ControlPort) : SeatAxisPort {

    override fun observe(axis: SeatAxis, onRegistered: (ProbeError?) -> Unit, onUpdate: (Int?) -> Unit, onError: (ProbeError) -> Unit): AutoCloseable {
        if (axis != SeatAxis.BACKREST) {
            onRegistered(unsupported(axis))
            return AutoCloseable {}
        }
        return port.observe(ControlProp.SEAT_BACKREST, DRIVER, { text ->
            when (val p = position(text)) {
                is Position.Ok -> onUpdate(p.value)
                is Position.Bad -> onError(p.error)
            }
        }, onError, onRegistered)
    }

    override fun read(axis: SeatAxis, ok: (Int?) -> Unit, err: (ProbeError) -> Unit) {
        if (axis != SeatAxis.BACKREST) return err(unsupported(axis))
        port.read(ControlProp.SEAT_BACKREST, DRIVER, { text ->
            when (val p = position(text)) {
                is Position.Ok -> ok(p.value)
                is Position.Bad -> err(p.error)
            }
        }, err)
    }

    override fun move(axis: SeatAxis, command: Move, motionKey: String, dispatch: Dispatch, ok: (echo: String?) -> Unit, err: (ProbeError) -> Unit) {
        val refused = when {
            axis != SeatAxis.BACKREST -> unsupported(axis).text()
            command == Move.STOP -> "STOP은 MOVE로 보내지 않아요"
            motionKey.isBlank() -> "이동 예약 키가 비어 있어요"
            else -> null
        }
        if (refused != null) return dispatch.onNotSent(refused)
        port.move(DRIVER, command, motionKey, dispatch, ok, err)
    }

    override fun stop(axis: SeatAxis, ok: (echo: String?) -> Unit, err: (ProbeError) -> Unit) {
        if (axis != SeatAxis.BACKREST) return err(unsupported(axis))
        port.move(DRIVER, Move.STOP, "", Dispatch.NONE, ok, err)
    }

    override fun endMotion(motionKey: String) = port.endMotion(motionKey)

    private sealed interface Position {
        data class Ok(val value: Int?) : Position
        data class Bad(val error: ProbeError) : Position
    }

    private fun position(text: String?): Position {
        if (text == null) return Position.Ok(null)
        val v = ControlValues.parse(ControlProp.SEAT_BACKREST, text) as Int?
        return if (v != null && v.toString() == text) Position.Ok(v) else Position.Bad(INVALID_POSITION)
    }

    private fun unsupported(axis: SeatAxis) = ProbeError(UNSUPPORTED_AXIS, "운전석 ${axis.name} 축은 지원하지 않아요(등받이만)", null)

    companion object {
        const val UNSUPPORTED_AXIS = "UnsupportedSeatAxis"
        val INVALID_POSITION = ProbeError("InvalidSeatPosition", "등받이 위치가 정수 형식이 아니에요", null)
    }
}
