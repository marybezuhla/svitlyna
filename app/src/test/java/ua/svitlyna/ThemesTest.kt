package ua.svitlyna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.svitlyna.data.ThemeMode
import ua.svitlyna.ui.theme.Theme
import ua.svitlyna.ui.theme.contrast
import ua.svitlyna.ui.theme.currentTheme

class ThemesTest {
    @Test fun pairsBothWays() {
        val pairs = mapOf(
            Theme.SYSTEM to Theme.SYSTEM_DARK, Theme.NEBO to Theme.ZORIANA_NICH, Theme.KALYNA to Theme.MAK,
            Theme.LINEN to Theme.CHORNOZEM, Theme.VISK to Theme.SMEREKA, Theme.GLYNA to Theme.ZHAR,
        )
        for ((day, night) in pairs) {
            assertEquals(night, Theme.pairOf(day))
            assertEquals(day, Theme.pairOf(night))
        }
        assertEquals(6, Theme.DAY.size)
        assertEquals(6, Theme.NIGHT.size)
        assertTrue(Theme.DAY.none { it.dark } && Theme.NIGHT.all { it.dark })
    }

    @Test fun textReadsOnEveryTheme() {
        for (t in Theme.entries) {
            val p = t.palette ?: continue
            assertTrue("$t text", contrast(p.onSurface, p.background) >= 4.5)
            assertTrue("$t text on cards", contrast(p.onSurface, p.surface) >= 4.5)
            assertTrue("$t small text", contrast(p.onSurfaceVariant, p.background) >= 4.5)
            assertTrue("$t buttons", contrast(p.onPrimary, p.primary) >= 4.5)
        }
    }

    @Test fun dayOrNightAsChosen() {
        assertEquals(Theme.NEBO, currentTheme(ThemeMode.SYSTEM, false, "nebo", "zoriana_nich"))
        assertEquals(Theme.ZORIANA_NICH, currentTheme(ThemeMode.SYSTEM, true, "nebo", "zoriana_nich"))
        assertEquals(Theme.ZORIANA_NICH, currentTheme(ThemeMode.DARK, false, "nebo", "zoriana_nich"))
        assertEquals(Theme.NEBO, currentTheme(ThemeMode.LIGHT, true, "nebo", "zoriana_nich"))
        // Unknown or wrong-side keys fall back to the phone's colours
        assertEquals(Theme.SYSTEM, currentTheme(ThemeMode.LIGHT, false, "mak", "x"))
        assertEquals(Theme.SYSTEM_DARK, currentTheme(ThemeMode.DARK, false, "nebo", "gone"))
    }
}
