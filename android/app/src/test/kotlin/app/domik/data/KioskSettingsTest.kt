package app.domik.data

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KioskSettingsTest {
    @Test
    fun `ekran zostaje wlaczony tylko przy zasilaniu na pierwszym planie i z wlaczonym ustawieniem`() {
        val on = KioskSettings(keepScreenOnWhenPowered = true)
        assertTrue(on.keepScreenOn(powered = true, visible = true))
        assertFalse(on.keepScreenOn(powered = false, visible = true))
        assertFalse(on.keepScreenOn(powered = true, visible = false))
        assertFalse(on.keepScreenOn(powered = false, visible = false))
    }

    @Test
    fun `wylaczone ustawienie nigdy nie trzyma ekranu`() {
        val off = KioskSettings(keepScreenOnWhenPowered = false)
        assertFalse(off.keepScreenOn(powered = true, visible = true))
    }
}
