package app.away

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {
    private val now = 1_000_000L

    @Test
    fun wholeAppRuleLetsEveryChatThrough() {
        val rules = mapOf("beeper" to now + 5_000)
        assertTrue(PassRules.isPassing(rules, "beeper", "Poker group", now))
        assertTrue(PassRules.isPassing(rules, "beeper", null, now))
    }

    @Test
    fun chatRuleOnlyLetsThatChatThrough() {
        val rules = mapOf(PassRules.key("beeper", "Poker Group") to now + 5_000)
        assertTrue(PassRules.isPassing(rules, "beeper", "poker group", now))
        assertFalse(PassRules.isPassing(rules, "beeper", "Work chat", now))
        assertFalse(PassRules.isPassing(rules, "beeper", null, now))
    }

    @Test
    fun expiredRuleNoLongerPasses() {
        assertFalse(PassRules.isPassing(mapOf("a" to now - 1), "a", null, now))
        assertFalse(PassRules.isPassing(mapOf("a" to now), "a", null, now))
    }

    @Test
    fun alwaysNeverExpires() {
        assertTrue(PassRules.isPassing(mapOf("a" to Prefs.PASS_ALWAYS), "a", null, Long.MAX_VALUE - 1))
    }

    @Test
    fun keyTrimsAndLowercasesTheTitle() {
        assertEquals("a|poker", PassRules.key("a", "  Poker "))
        assertEquals("a", PassRules.key("a", "   "))
    }

    @Test
    fun dayWindowIncludesStartButNotEnd() {
        assertTrue(Blocking.inWindow(9 * 60, 9 * 60, 17 * 60))
        assertTrue(Blocking.inWindow(16 * 60 + 59, 9 * 60, 17 * 60))
        assertFalse(Blocking.inWindow(17 * 60, 9 * 60, 17 * 60))
        assertFalse(Blocking.inWindow(8 * 60, 9 * 60, 17 * 60))
    }

    @Test
    fun overnightWindowWrapsPastMidnight() {
        assertTrue(Blocking.inWindow(23 * 60, 22 * 60, 6 * 60))
        assertTrue(Blocking.inWindow(2 * 60, 22 * 60, 6 * 60))
        assertFalse(Blocking.inWindow(12 * 60, 22 * 60, 6 * 60))
        assertFalse(Blocking.inWindow(6 * 60, 22 * 60, 6 * 60))
    }

    @Test
    fun durationsReadNaturally() {
        assertEquals("0m", Blocking.formatDuration(0))
        assertEquals("59m", Blocking.formatDuration(59 * 60_000L))
        assertEquals("1h 0m", Blocking.formatDuration(60 * 60_000L))
        assertEquals("13h 20m", Blocking.formatDuration((13 * 60 + 20) * 60_000L))
    }
}
