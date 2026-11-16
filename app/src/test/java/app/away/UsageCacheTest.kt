package app.away

import java.util.Calendar
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class UsageCacheTest {
    private val ttl = 15_000L
    private val day = 1000L
    private lateinit var savedTz: TimeZone

    @Before fun setUp() {
        savedTz = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
    }

    @After fun tearDown() { TimeZone.setDefault(savedTz) }

    @Test fun firstCallLoadsNow() =
        assertEquals(UsageCachePlan.Step.LOAD_NOW, UsageCachePlan.plan(5, 0, 0, day, ttl))

    @Test fun newDayDetected() =
        assertEquals(UsageCachePlan.Step.NEW_DAY, UsageCachePlan.plan(20_000, 10_000, 999, day, ttl))

    @Test fun staleRefreshes() =
        assertEquals(UsageCachePlan.Step.REFRESH, UsageCachePlan.plan(30_000, 10_000, 1000, day, ttl))

    @Test fun freshStaysFresh() =
        assertEquals(UsageCachePlan.Step.FRESH, UsageCachePlan.plan(20_000, 10_000, 1000, day, ttl))

    private fun local(y: Int, m: Int, d: Int, h: Int): Long = Calendar.getInstance().apply {
        clear(); set(y, m, d, h, 0, 0)
    }.timeInMillis

    @Test fun dayStartIsDstAware() {
        val now = local(2026, Calendar.MARCH, 8, 12)
        assertEquals(local(2026, Calendar.MARCH, 8, 0), UsageTracker.dayStart(now))
        assertEquals(24 * 3_600_000L, UsageTracker.dayStart(now) - UsageTracker.dayStart(now, 1))
        assertEquals(23 * 3_600_000L, UsageTracker.dayStart(now, -1) - UsageTracker.dayStart(now))
        assertEquals(local(2026, Calendar.MARCH, 7, 0), UsageTracker.dayStart(now, 1))
    }
}
