package app.away

import org.junit.Assert.assertEquals
import org.junit.Test

class AppsTest {
    private val a = AppEntry("pkg.a", "Alpha")
    private val b = AppEntry("pkg.b", "Beta")
    private val c = AppEntry("pkg.c", "Gamma")

    @Test
    fun rankOrder() {
        assertEquals(0, Apps.rank("Instagram", "insta"))
        assertEquals(1, Apps.rank("Google Maps", "maps"))
        assertEquals(2, Apps.rank("YouTube", "tube"))
        assertEquals(3, Apps.rank("Google Maps", "gm"))
        assertEquals(4, Apps.rank("Signal", "sgl"))
        assertEquals(-1, Apps.rank("Signal", "xyz"))
    }

    @Test
    fun searchUsesRenamesAndRanks() {
        val renames = mapOf(a.pkg to "Zeta")
        assertEquals(listOf(b, a), Apps.search(listOf(a, b), renames, ""))
        assertEquals(listOf(a), Apps.search(listOf(a, b), renames, "ze"))
    }

    @Test
    fun searchTrimsAndLowercases() {
        assertEquals(listOf(a), Apps.search(listOf(a, b), emptyMap(), "  ALP "))
    }

    @Test
    fun filterVisibleRules() {
        val all = listOf(a, b, c)
        // Hidden apps are always excluded.
        assertEquals(listOf(b, c), Apps.filterVisible(all, setOf(a.pkg), emptySet(), false) { false })
        // Blocked apps are excluded only while blocking is active.
        assertEquals(all, Apps.filterVisible(all, emptySet(), setOf(b.pkg), false) { false })
        assertEquals(listOf(a, c), Apps.filterVisible(all, emptySet(), setOf(b.pkg), true) { false })
        // Apps over their daily limit are excluded.
        assertEquals(listOf(a, b), Apps.filterVisible(all, emptySet(), emptySet(), false) { it == c.pkg })
    }
}
