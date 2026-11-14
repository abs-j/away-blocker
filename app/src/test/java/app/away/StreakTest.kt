package app.away

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreakTest {
    private val min = 60_000L

    @Test
    fun startsAtZeroTowardsTheFirstMilestone() {
        assertEquals(0f, Streak.progress(0), 0.0001f)
        assertEquals(15 * min, Streak.next(0))
    }

    @Test
    fun progressIsMeasuredWithinTheCurrentStretch() {
        // Halfway between 8h and 24h is 16h.
        assertEquals(0.5f, Streak.progress(16 * 60 * min), 0.0001f)
        assertEquals(24 * 60 * min, Streak.next(16 * 60 * min))
        assertEquals(8 * 60 * min, Streak.prev(16 * 60 * min))
    }

    @Test
    fun aMilestoneStartsTheNextStretchFromZero() {
        assertEquals(0f, Streak.progress(60 * min), 0.0001f)
        assertEquals(180 * min, Streak.next(60 * min))
    }

    @Test
    fun pastTheLastMarkItKeepsGoing() {
        val away = 100 * 60 * min
        assertTrue(Streak.next(away) > away)
        assertTrue(Streak.progress(away) in 0f..1f)
    }

    @Test
    fun motivationReactsToBeatingTheBest() {
        val best = 2 * 60 * min
        assertEquals("New personal best.", Motivation.line(3 * 60 * min, best, 0))
        assertTrue(Motivation.line((best * 0.9).toLong(), best, 0).startsWith("Close to your best"))
    }
}
