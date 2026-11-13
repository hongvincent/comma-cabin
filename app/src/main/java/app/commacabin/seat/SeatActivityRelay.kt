package app.commacabin.seat

import app.commacabin.rest.DoorStatus

/**
 * Passes the door and screen signals of the one Activity attached now to the process-wide [RestSeat]. An older
 * Activity's late onStop, onDestroy or door callback (after a newer one attached) never reaches the seat job.
 * Main thread only.
 */
class SeatActivityRelay(private val seat: RestSeat) {
    private var current: Any? = null

    /**
     * The Activity is shown: it becomes the only source of signals. The door starts unknown (an earlier Activity's
     * CLOSED is never carried over) until this Activity reports its own door state.
     */
    fun attach(activity: Any) {
        current = activity
        seat.onDoor(null)
        seat.onScreen(true)
    }

    /** The Activity is no longer shown. Ignored unless it is the attached one. */
    fun hidden(activity: Any) {
        if (current !== activity) return
        seat.onScreen(false)
    }

    /** The Activity is destroyed: it stops being a source. Ignored unless it is the attached one. */
    fun detach(activity: Any) {
        if (current !== activity) return
        current = null
        seat.onScreen(false)
    }

    /** CLOSED is closed; OPEN is open; checking, unsupported or an error is unknown. */
    fun door(activity: Any, status: DoorStatus) {
        if (current !== activity) return
        seat.onDoor(
            when (status) {
                DoorStatus.CLOSED -> true
                DoorStatus.OPEN -> false
                DoorStatus.CHECKING, DoorStatus.UNSUPPORTED, DoorStatus.ERROR -> null
            },
        )
    }
}
