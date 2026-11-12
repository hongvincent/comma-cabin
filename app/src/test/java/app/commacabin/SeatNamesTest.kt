package app.commacabin

import org.junit.Assert.assertEquals
import org.junit.Test

class SeatNamesTest {
    @Test
    fun `area keys become easy seat names and an SDK key is never shown`() {
        assertEquals("운전석", seatName("ROW_1_LEFT"))
        assertEquals("조수석", seatName("ROW_1_RIGHT"))
        assertEquals("앞좌석", seatName("ROW_1_LEFT+ROW_1_RIGHT"))
        assertEquals("뒷좌석", seatName("ROW_2_CENTER+ROW_2_LEFT+ROW_2_RIGHT"))
        assertEquals("앞·뒤 좌석", seatName("ROW_1_LEFT+ROW_1_RIGHT+ROW_2_CENTER+ROW_2_LEFT+ROW_2_RIGHT"))
        assertEquals("앞·뒤 좌석", seatName("ROW_1_LEFT+ROW_1_RIGHT+ROW_2_LEFT+ROW_2_RIGHT")) // no rear centre: still not "all"
        assertEquals("운전석·뒤 왼쪽", seatName("ROW_2_LEFT+ROW_1_LEFT"))
        assertEquals("다른 좌석", seatName("ROW_3_LEFT"))
        assertEquals("여러 좌석", seatName("ROW_1_LEFT+ROW_3_LEFT"))
        assertEquals("", seatName(""))
    }
}
