package app.commacabin.probe

/**
 * How one vehicle write is let through, decided on the main thread right before the SDK call (not when it was
 * queued). Every part runs on the main thread.
 * - [gate]: null = send now; a text = do not send, and why.
 * - [onStart]: tracking and timers of the operation, done before the SDK call is entered. It marks the start of
 *   the entry only, not a delivery to the vehicle and not a response.
 * - [onNotSent]: the write certainly never reached the SDK (refused, cancelled, queue full or closed). Called at
 *   most once, and never together with an SDK call of the same write. It may come before or after [onStart].
 */
class Dispatch(
    val gate: () -> String?,
    val onStart: () -> Unit,
    val onNotSent: (String) -> Unit,
) {
    /** For a port without a lane (tests): decide and start at once. True = the caller sends now. */
    fun now(): Boolean {
        val why = gate()
        if (why != null) {
            onNotSent(why)
            return false
        }
        onStart()
        return true
    }

    companion object {
        /** For calls that are not gated (the seat STOP). */
        val NONE = Dispatch({ null }, {}, {})
    }
}
