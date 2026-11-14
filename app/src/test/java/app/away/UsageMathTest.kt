package app.away

import app.away.UsageMath.Ev
import app.away.UsageMath.Kind.PAUSE
import app.away.UsageMath.Kind.RESUME
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageMathTest {
    private val min = 60_000L

    @Test
    fun simpleStretchIsCounted() {
        val t = UsageMath.total(
            listOf(Ev("a", "Main", RESUME, 0), Ev("a", "Main", PAUSE, 10 * min)), emptySet(), 60 * min,
        )
        assertEquals(10 * min, t["a"])
    }

    /** The Prime Video bug: the old screen's pause arrives after the player's resume. */
    @Test
    fun lateStopOfPreviousScreenDoesNotCancelTheNewOne() {
        val t = UsageMath.total(
            listOf(
                Ev("prime", "Main", RESUME, 0),
                Ev("prime", "Player", RESUME, 1 * min),
                Ev("prime", "Main", PAUSE, 1 * min + 10), // arrives after the player came forward
                Ev("prime", "Player", PAUSE, 41 * min),
            ),
            emptySet(), 100 * min,
        )
        assertEquals(41 * min, t["prime"])
    }

    @Test
    fun anotherAppComingForwardEndsAnAppWhosePauseWasNeverSeen() {
        val t = UsageMath.total(
            listOf(Ev("a", "Main", RESUME, 0), Ev("b", "Main", RESUME, 5 * min), Ev("b", "Main", PAUSE, 8 * min)),
            emptySet(), 60 * min,
        )
        assertEquals(5 * min, t["a"])
        assertEquals(3 * min, t["b"])
    }

    @Test
    fun anAppStillOpenRunsUntilNow() {
        val t = UsageMath.total(listOf(Ev("a", "Main", RESUME, 10 * min)), emptySet(), 25 * min)
        assertEquals(15 * min, t["a"])
    }

    @Test
    fun skippedPackagesAreIgnored() {
        val t = UsageMath.total(
            listOf(Ev("home", "Launcher", RESUME, 0), Ev("home", "Launcher", PAUSE, 30 * min)), setOf("home"), 60 * min,
        )
        assertNull(t["home"])
    }

    @Test
    fun pauseWithoutResumeIsIgnored() {
        val t = UsageMath.total(listOf(Ev("a", "Main", PAUSE, 5 * min)), emptySet(), 10 * min)
        assertNull(t["a"])
    }

    @Test
    fun twoScreensOpenAtOnceCountOnce() {
        val t = UsageMath.total(
            listOf(
                Ev("a", "One", RESUME, 0), Ev("a", "Two", RESUME, 2 * min),
                Ev("a", "One", PAUSE, 3 * min), Ev("a", "Two", PAUSE, 6 * min),
            ),
            emptySet(), 60 * min,
        )
        assertEquals(6 * min, t["a"])
    }
}
