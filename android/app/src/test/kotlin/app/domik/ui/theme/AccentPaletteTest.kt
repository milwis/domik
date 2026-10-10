package app.domik.ui.theme

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AccentPaletteTest {

    @Test
    fun `domyslna paleta to Zielen`() {
        assertEquals(AccentPalette.Green, AccentPalette.Default)
        assertEquals(AccentPalette.Default, AccentPalette.fromName(null))
        assertEquals(AccentPalette.Default, AccentPalette.fromName("nieznana"))
        assertEquals(AccentPalette.Violet, AccentPalette.fromName("Violet"))
    }

    @Test
    fun `dziewiec stalych palet plus dynamiczna`() {
        assertEquals(10, AccentPalette.entries.size)
        val fixed = AccentPalette.entries.filter { it != AccentPalette.Dynamic }
        assertEquals(9, fixed.size)
        for (palette in fixed) {
            assertNotNull(palette.variant(dark = false), palette.name)
            assertNotNull(palette.variant(dark = true), palette.name)
        }
        assertNull(AccentPalette.Dynamic.variant(dark = false))
        assertNull(AccentPalette.Dynamic.variant(dark = true))
    }

    @Test
    fun `kolory z tapety tylko od Androida 12`() {
        assertFalse(AccentPalette.Dynamic in AccentPalette.availableOn(30))
        assertTrue(AccentPalette.Dynamic in AccentPalette.availableOn(31))
        assertEquals(9, AccentPalette.availableOn(26).size)
        assertEquals(10, AccentPalette.availableOn(36).size)
    }
}
