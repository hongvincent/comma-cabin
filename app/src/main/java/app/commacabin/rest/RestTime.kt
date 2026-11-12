package app.commacabin.rest

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Initial validation values (product-spec 0-1). */
const val PICKUP_LEAD_MINUTES = 20L
const val PICKUP_STEP_MINUTES = 5L
const val PREP_MARGIN_MS = 2 * 60_000L
const val MIN_REST_MS = 3 * 60_000L
const val FINAL_NOTICE_MS = 60_000L
const val DIM_AFTER_MS = 10_000L

/**
 * Wall-clock date and time math for the ready screen. Pickup values are epoch milliseconds, so a pickup
 * after midnight is simply a later instant on the next local date. A running rest does not use these
 * values for counting down; it compares the monotonic clock with an end fixed at start (RestFlow).
 */
object RestTime {
    /** now + 20 minutes, rounded up to the next 5-minute mark of the local time in [zone]. */
    fun defaultPickup(nowMs: Long, zone: ZoneId): Long {
        val lead = Instant.ofEpochMilli(nowMs).atZone(zone).plusMinutes(PICKUP_LEAD_MINUTES)
        var t = lead.truncatedTo(ChronoUnit.MINUTES)
        if (t.isBefore(lead)) t = t.plusMinutes(1)
        val extra = (PICKUP_STEP_MINUTES - t.minute % PICKUP_STEP_MINUTES) % PICKUP_STEP_MINUTES
        return t.plusMinutes(extra).toInstant().toEpochMilli()
    }

    fun restEnd(pickupMs: Long): Long = pickupMs - PREP_MARGIN_MS

    /** Rest time if started at [nowMs]. Negative once the rest end has passed. */
    fun restMs(pickupMs: Long, nowMs: Long): Long = restEnd(pickupMs) - nowMs

    /** Local calendar days from today to the date of [atMs]: 0 today, 1 tomorrow, -1 yesterday. */
    fun dayOffset(atMs: Long, nowMs: Long, zone: ZoneId): Long = ChronoUnit.DAYS.between(
        Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate(),
        Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate(),
    )
}
