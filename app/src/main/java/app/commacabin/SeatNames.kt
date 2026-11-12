package app.commacabin

/**
 * Easy seat names for the vehicle's area keys, so the product screen never shows an SDK key.
 * ROW_1_LEFT is the driver, as in the climate rules (`DRIVER_AREA`).
 */
fun seatName(area: String): String {
    val seats = area.split('+').filter { it.isNotBlank() }.toSet()
    if (seats.isEmpty()) return ""
    if (seats.any { it !in SEATS }) return if (seats.size == 1) "다른 좌석" else "여러 좌석"
    val front = seats.containsAll(listOf("ROW_1_LEFT", "ROW_1_RIGHT"))
    val rear = seats.containsAll(listOf("ROW_2_LEFT", "ROW_2_RIGHT"))
    val rows = seats.map { it.removePrefix("ROW_").substringBefore('_') }.toSet()
    return when {
        rows == setOf("1") && front -> "앞좌석"
        rows == setOf("2") && rear -> "뒷좌석"
        // The key alone never proves every seat of the car is in it, so this is never called "all seats".
        front && rear -> "앞·뒤 좌석"
        else -> SEATS.filterKeys { it in seats }.values.joinToString("·")
    }
}

private val SEATS = linkedMapOf(
    "ROW_1_LEFT" to "운전석",
    "ROW_1_CENTER" to "앞 가운데",
    "ROW_1_RIGHT" to "조수석",
    "ROW_2_LEFT" to "뒤 왼쪽",
    "ROW_2_CENTER" to "뒤 가운데",
    "ROW_2_RIGHT" to "뒤 오른쪽",
)
