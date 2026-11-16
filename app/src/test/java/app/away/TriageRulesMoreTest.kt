package app.away

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TriageRulesMoreTest {
    @Test
    fun callMeBackAsksYouToCall() {
        assertEquals(
            Verdict(true, "asks you to call"),
            TriageRules.local("Sam", "Can you call me back?", null, emptyList()),
        )
    }

    @Test
    fun whereAreYouNeedsYouNow() {
        assertEquals(
            Verdict(true, "needs you now"),
            TriageRules.local("Sam", "where are you", null, emptyList()),
        )
    }

    @Test
    fun promoWordsVetoUrgentRules() {
        assertNull(TriageRules.local("Shop", "Urgent: 50% off today", null, emptyList()))
    }

    @Test
    fun emergencyRuleIsCheckedBeforeCallRule() {
        assertEquals("emergency", TriageRules.local("Sam", "Urgent, call me", null, emptyList())!!.reason)
    }

    @Test
    fun blankVipsNeverMatch() {
        assertFalse(TriageRules.vipHit("Mom says hi", listOf("", "   ")))
    }

    @Test
    fun vipFollowedByPunctuationMatches() {
        assertTrue(TriageRules.vipHit("Hi Mom!", listOf("mom")))
    }

    @Test
    fun vipWithRegexCharactersIsEscaped() {
        assertTrue(TriageRules.vipHit("Dr. K here", listOf("Dr. K")))
        assertFalse(TriageRules.vipHit("DrX K here", listOf("Dr. K")))
    }
}
