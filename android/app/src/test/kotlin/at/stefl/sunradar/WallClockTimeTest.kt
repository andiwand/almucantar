package at.stefl.sunradar

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class WallClockTimeTest {
    private val vienna = ZoneId.of("Europe/Vienna")

    private fun clock(date: LocalDate, minute: Int): String =
        Instant.ofEpochSecond(wallClockTime(date, minute, vienna).toLong())
            .atZone(vienna)
            .toLocalDateTime()
            .toString()

    @Test
    fun theSliderGivesTheWallClockTimeOnDstDays() {
        // The clocks go forward on 2027-03-28 and back on 2026-10-25.
        assertEquals("2027-03-28T10:00", clock(LocalDate.of(2027, 3, 28), 600))
        assertEquals("2027-03-28T23:30", clock(LocalDate.of(2027, 3, 28), 23 * 60 + 30))
        assertEquals("2026-10-25T10:00", clock(LocalDate.of(2026, 10, 25), 600))
    }
}
