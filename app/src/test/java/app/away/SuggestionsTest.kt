package app.away

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestionsTest {
    private val min = 60_000L
    private val today = mapOf("prime" to 41 * min, "yt" to 19 * min, "chrome" to 5 * min, "settings" to 30 * min, "ig" to 50 * min)
    private val labels = today.keys

    @Test
    fun picksTheBiggestUnlistedAppsOverTheThreshold() {
        val s = Suggestions.pick(today, labels, blocked = setOf("ig"), neverBlock = setOf("settings"), dismissed = emptySet())
        assertEquals(listOf("prime", "yt"), s.map { it.first })
        assertEquals(41 * min, s.first().second)
    }

    @Test
    fun skipsDismissedAndAlreadyListedApps() {
        val s = Suggestions.pick(today, labels, setOf("prime"), emptySet(), dismissed = setOf("ig", "yt"))
        assertEquals(listOf("settings"), s.map { it.first })
    }

    @Test
    fun nothingBelowTheThreshold() {
        val s = Suggestions.pick(mapOf("chrome" to 5 * min), setOf("chrome"), emptySet(), emptySet(), emptySet())
        assertTrue(s.isEmpty())
    }

    @Test
    fun ignoresAppsWithoutALauncherLabel() {
        val s = Suggestions.pick(mapOf("service" to 60 * min), emptySet(), emptySet(), emptySet(), emptySet())
        assertTrue(s.isEmpty())
    }
}
