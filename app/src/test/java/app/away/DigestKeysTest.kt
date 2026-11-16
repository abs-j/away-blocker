package app.away

import org.junit.Assert.assertEquals
import org.junit.Test

class DigestKeysTest {
    private fun item(app: String = "Chat", title: String = "Hi", text: String = "Hello", time: Long = 1000L, pkg: String = "com.chat") =
        DigestItem(app = app, title = title, text = text, time = time, urgent = false, reason = "", pkg = pkg)

    @Test
    fun distinctItemsGetDistinctKeys() {
        val keys = DigestKeys.of(listOf(item(time = 1), item(time = 2), item(title = "Other")))
        assertEquals(3, keys.toSet().size)
    }

    @Test
    fun identicalItemsStillGetUniqueKeys() {
        val keys = DigestKeys.of(listOf(item(), item(), item()))
        assertEquals(3, keys.toSet().size)
        assertEquals(keys[0] + "#2", keys[1])
    }

    @Test
    fun sameTimeSameTitleDifferentTextAreUnique() {
        val keys = DigestKeys.of(listOf(item(text = "one"), item(text = "two")))
        assertEquals(2, keys.toSet().size)
    }

    @Test
    fun keysAreStableForTheSameList() {
        val list = listOf(item(), item(), item(title = "x"))
        assertEquals(DigestKeys.of(list), DigestKeys.of(list))
    }
}
