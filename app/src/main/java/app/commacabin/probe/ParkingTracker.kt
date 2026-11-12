package app.commacabin.probe

/**
 * Parking state with connection generations. Every (re)connect or disconnect starts a new binding and
 * resets to UNKNOWN; events from an older binding or after close() are dropped. Main thread only.
 */
class ParkingTracker(private val onChange: (ParkingState) -> Unit) {
    var state = ParkingState()
        private set

    private var binding = 0
    private var closed = false
    private var lastGearTimestamp = Long.MIN_VALUE

    /** Starts a new binding and returns its id, or null once closed. */
    fun newBinding(): Int? {
        if (closed) return null
        binding++
        lastGearTimestamp = Long.MIN_VALUE
        publish(ParkingState())
        return binding
    }

    fun isCurrent(id: Int): Boolean = !closed && id == binding

    /** [timestampNanos] <= 0 means the event carries no usable timestamp. */
    fun gear(id: Int, gear: GearState, timestampNanos: Long) {
        if (!isCurrent(id)) return
        if (timestampNanos > 0) {
            if (timestampNanos < lastGearTimestamp) return
            lastGearTimestamp = timestampNanos
        }
        publish(state.copy(gear = gear))
    }

    fun uxRestricted(id: Int, restricted: Boolean?) {
        if (!isCurrent(id)) return
        publish(state.copy(uxRestricted = restricted))
    }

    /** Reports UNKNOWN (not parked) once, then ignores everything. */
    fun close() {
        if (closed) return
        publish(ParkingState())
        closed = true
        binding++
    }

    private fun publish(next: ParkingState) {
        if (next == state) return
        state = next
        onChange(next)
    }
}
