package app.away

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.Calendar.MARCH
import java.util.Calendar.NOVEMBER
import java.util.TimeZone

class StatsDaysTest {
    private lateinit var saved: TimeZone

    @Before
    fun setUp() {
        saved = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(saved)
    }

    private fun at(y: Int, mon: Int, d: Int, h: Int = 12): Long =
        Calendar.getInstance().apply { clear(); set(y, mon, d, h, 0, 0) }.timeInMillis

    private val HOUR = 3_600_000L

    @Test
    fun keyIsLocalDate() {
        assertEquals("20260309", StatsDays.key(at(2026, MARCH, 9)))
        assertEquals("20260309", StatsDays.key(at(2026, MARCH, 9, 0)))
    }

    @Test
    fun startsAreLocalMidnightsOldestFirst() {
        assertEquals(
            listOf(at(2026, MARCH, 7, 0), at(2026, MARCH, 8, 0), at(2026, MARCH, 9, 0)),
            StatsDays.starts(3, at(2026, MARCH, 9)),
        )
    }

    @Test
    fun springForwardDayIs23Hours() {
        val s = StatsDays.starts(3, at(2026, MARCH, 9))
        assertEquals(24 * HOUR, s[1] - s[0])
        assertEquals(23 * HOUR, s[2] - s[1])
    }

    @Test
    fun fallBackDayIs25Hours() {
        val s = StatsDays.starts(2, at(2026, NOVEMBER, 2))
        assertEquals(25 * HOUR, s[1] - s[0])
    }

    @Test
    fun todayIsLast() {
        assertEquals(at(2026, MARCH, 9, 0), StatsDays.starts(1, at(2026, MARCH, 9)).single())
    }

    @Test
    fun fourteenDistinctDayKeys() {
        val keys = StatsDays.starts(14, at(2026, MARCH, 15)).map { StatsDays.key(it) }
        assertEquals(14, keys.toSet().size)
        assertEquals("20260315", keys.last())
    }

    @Test
    fun expiredDropsOnlyDaysOlderThan21() {
        val now = at(2026, MARCH, 14)
        assertEquals(
            listOf("20260101", "20260220"),
            StatsDays.expired(listOf("20260101", "20260220", "20260221", "20260309"), now),
        )
    }
}
