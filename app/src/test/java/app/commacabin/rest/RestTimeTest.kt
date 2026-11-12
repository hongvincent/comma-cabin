package app.commacabin.rest

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RestTimeTest {
    private val zone = ZoneId.of("Asia/Seoul")

    private fun at(day: Int, hour: Int, minute: Int, second: Int = 0, nano: Int = 0) =
        ZonedDateTime.of(2026, 11, day, hour, minute, second, nano, zone).toInstant().toEpochMilli()

    @Test
    fun `default pickup is now plus 20 minutes rounded up to the next 5-minute mark`() {
        assertEquals(at(12, 19, 25), RestTime.defaultPickup(at(12, 19, 3), zone))
        assertEquals(at(12, 19, 25), RestTime.defaultPickup(at(12, 19, 5), zone))
        assertEquals(at(12, 19, 30), RestTime.defaultPickup(at(12, 19, 5, 1), zone))
        assertEquals(at(12, 19, 30), RestTime.defaultPickup(at(12, 19, 5, 0, 1_000_000), zone))
    }

    @Test
    fun `a pickup after midnight is on the next date and keeps its real length`() {
        val now = at(12, 23, 50)
        val pickup = RestTime.defaultPickup(now, zone)

        assertEquals(at(13, 0, 10), pickup)
        assertEquals(1L, RestTime.dayOffset(pickup, now, zone))
        assertEquals(at(13, 0, 8), RestTime.restEnd(pickup))
        assertEquals(18 * 60_000L, RestTime.restMs(pickup, now))
    }

    @Test
    fun `the rest end can stay on today while the pickup is tomorrow`() {
        val now = at(12, 23, 41)
        val pickup = at(13, 0, 1)

        assertEquals(1L, RestTime.dayOffset(pickup, now, zone))
        assertEquals(at(12, 23, 59), RestTime.restEnd(pickup))
        assertEquals(0L, RestTime.dayOffset(RestTime.restEnd(pickup), now, zone))
        assertEquals(18 * 60_000L, RestTime.restMs(pickup, now))
    }

    @Test
    fun `at least three minutes of rest are needed and a passed end is reported separately`() {
        assertNull(timeBlock(MIN_REST_MS))
        assertEquals(Block.TIME_SHORT, timeBlock(MIN_REST_MS - 1))
        assertEquals(Block.TIME_SHORT, timeBlock(1))
        assertEquals(Block.TIME_PASSED, timeBlock(0))
        assertEquals(Block.TIME_PASSED, timeBlock(-60_000))
    }
}
