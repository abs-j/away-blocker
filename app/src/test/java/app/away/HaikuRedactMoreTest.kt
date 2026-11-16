package app.away

import org.junit.Assert.assertEquals
import org.junit.Test

class HaikuRedactMoreTest {
    @Test
    fun wwwLinkIsRedacted() {
        assertEquals("see [link] now", Haiku.redact("see www.x.com now"))
    }

    @Test
    fun sixDigitsAreANumberNotAPhone() {
        assertEquals("Order [num]", Haiku.redact("Order 123456"))
    }

    @Test
    fun sevenDigitsAreRedacted() {
        assertEquals("[num]", Haiku.redact("1234567"))
    }

    @Test
    fun shortDashedNumbersStay() {
        assertEquals("Code 12-34", Haiku.redact("Code 12-34"))
    }

    @Test
    fun emailIsRedactedBeforeNumbers() {
        assertEquals("[email]", Haiku.redact("a1234@b.com"))
    }

    @Test
    fun nestedJsonKeepsOutermostObject() {
        assertEquals("{\"a\":{\"b\":1}}", Haiku.extractJson("{\"a\":{\"b\":1}} trailing"))
    }

    @Test
    fun emptyObjectIsExtracted() {
        assertEquals("{}", Haiku.extractJson("{}"))
    }
}
