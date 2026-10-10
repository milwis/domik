package app.domik.ui.theme

import java.time.LocalTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ThemeModeTest {

    private fun t(h: Int, m: Int = 0) = LocalTime.of(h, m)

    @Test
    fun `System podaje ustawienie systemu`() {
        assertTrue(ThemeMode.System.isDark(t(12), systemDark = true))
        assertFalse(ThemeMode.System.isDark(t(23), systemDark = false))
    }

    @Test
    fun `Light i Dark ignoruja system i godzine`() {
        assertFalse(ThemeMode.Light.isDark(t(23), systemDark = true))
        assertTrue(ThemeMode.Dark.isDark(t(12), systemDark = false))
    }

    @Test
    fun `Scheduled przez polnoc 21-0630`() {
        val mode = ThemeMode.Scheduled(t(21), t(6, 30))
        assertFalse(mode.isDark(t(12), systemDark = true)) // system nie ma znaczenia
        assertFalse(mode.isDark(t(20, 59), systemDark = true))
        assertTrue(mode.isDark(t(21), systemDark = false)) // początek włącznie
        assertTrue(mode.isDark(t(23, 59), systemDark = false))
        assertTrue(mode.isDark(t(0), systemDark = false))
        assertTrue(mode.isDark(t(6, 29), systemDark = false))
        assertFalse(mode.isDark(t(6, 30), systemDark = false)) // koniec wyłącznie
        assertFalse(mode.isDark(t(7), systemDark = false))
    }

    @Test
    fun `Scheduled w obrebie jednej doby 13-15`() {
        val mode = ThemeMode.Scheduled(t(13), t(15))
        assertFalse(mode.isDark(t(12, 59), false))
        assertTrue(mode.isDark(t(13), false))
        assertTrue(mode.isDark(t(14, 59), false))
        assertFalse(mode.isDark(t(15), false))
        assertFalse(mode.isDark(t(0), false))
    }

    @Test
    fun `Scheduled z pustym przedzialem nigdy nie jest ciemny`() {
        val mode = ThemeMode.Scheduled(t(8), t(8))
        assertFalse(mode.isDark(t(8), false))
        assertFalse(mode.isDark(t(20), true))
    }

    @Test
    fun `domyslne godziny to 21-0630`() {
        val mode = ThemeMode.Scheduled()
        assertEquals(t(21), mode.darkFrom)
        assertEquals(t(6, 30), mode.darkUntil)
    }

    @Test
    fun `zapis i odczyt ustawienia`() {
        val modes = listOf(
            ThemeMode.System,
            ThemeMode.Light,
            ThemeMode.Dark,
            ThemeMode.Scheduled(t(22, 15), t(5, 45)),
        )
        for (mode in modes) assertEquals(mode, ThemeMode.decode(mode.encode()))
        assertEquals("scheduled;21:00;06:30", ThemeMode.Scheduled().encode())
    }

    @Test
    fun `uszkodzony zapis daje System`() {
        assertEquals(ThemeMode.System, ThemeMode.decode(null))
        assertEquals(ThemeMode.System, ThemeMode.decode(""))
        assertEquals(ThemeMode.System, ThemeMode.decode("scheduled;25:00;06:30"))
        assertEquals(ThemeMode.System, ThemeMode.decode("scheduled;21:00"))
        assertEquals(ThemeMode.System, ThemeMode.decode("pink"))
    }
}
