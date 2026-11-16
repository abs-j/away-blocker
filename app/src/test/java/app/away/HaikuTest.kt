package app.away

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HaikuTest {
    @Test
    fun redactsEmailLinkAndLongNumber() {
        assertEquals(
            "mail [email] see [link] code [num]",
            Haiku.redact("mail a.b@x.com see https://x.y/z code 12345"),
        )
    }

    @Test
    fun redactsPhoneNumber() {
        assertEquals("Call [num]", Haiku.redact("Call +1 (555) 123-4567"))
    }

    @Test
    fun keepsShortNumbersAndTimes() {
        assertEquals("Meet at 3 or 10:30", Haiku.redact("Meet at 3 or 10:30"))
    }

    @Test
    fun truncatesTo300() {
        assertEquals(300, Haiku.redact("a".repeat(500)).length)
    }

    @Test
    fun extractsJsonObject() {
        assertEquals("{\"urgent\":true}", Haiku.extractJson("Sure {\"urgent\":true} ok"))
    }

    @Test
    fun extractJsonReturnsNullWithoutObject() {
        assertNull(Haiku.extractJson("none"))
        assertNull(Haiku.extractJson("}{"))
    }
}
