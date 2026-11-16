package app.away

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TriageRulesTest {
    private fun local(t: String, x: String, c: String? = null, v: List<String> = emptyList()) =
        TriageRules.local(t, x, c, v)

    @Test
    fun otpIsUrgent() {
        assertEquals(Verdict(true, "verification code"), local("Bank", "Your verification code is 482913"))
    }

    @Test
    fun promoCodeIsNotAnOtp() {
        assertNull(local("Shop", "Use code 1234 for 20% off"))
    }

    @Test
    fun lowValueCategoryWins() {
        assertEquals(Verdict(false, "social or promo category"), local("X", "urgent call me", "promo"))
    }

    @Test
    fun vipMatchesWholeWordCaseInsensitive() {
        assertEquals("VIP contact", local("mom", "hi", null, listOf("Mom"))!!.reason)
    }

    @Test
    fun vipDoesNotMatchInsideWords() {
        assertNull(local("Ann", "give me a moment", null, listOf("Mom")))
    }

    @Test
    fun vipMatchesCjk() {
        assertTrue(TriageRules.vipHit("王伟说 你好", listOf("王伟")))
    }

    @Test
    fun emergencyRule() {
        assertEquals("emergency", local("Sam", "call me back asap")!!.reason)
    }

    @Test
    fun travelRule() {
        assertEquals("travel change", local("Airline", "Your flight delayed to 9pm")!!.reason)
    }

    @Test
    fun securityRule() {
        assertEquals("security alert", local("Google", "Suspicious sign-in attempt")!!.reason)
    }

    @Test
    fun ordinaryChatIsLeftToAi() {
        assertNull(local("Sam", "lunch tomorrow?"))
    }
}
