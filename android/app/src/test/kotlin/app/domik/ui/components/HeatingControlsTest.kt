package app.domik.ui.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HeatingControlsTest {
    private val comfort = HeatingTileState("Strefa", "Pokój", 21f, 22f, HeatingProfile.Comfort, heating = false)
    private val off = comfort.copy(profile = HeatingProfile.Off, canSwitchOn = true)
    private val none = HeatingControls(canDecrease = false, canIncrease = false, increaseSwitchesOn = false, profileEnabled = false)

    @Test
    fun `strefa wlaczona - plus, minus i profil aktywne`() {
        val c = comfort.controls()
        assertTrue(c.canDecrease && c.canIncrease && c.profileEnabled)
        assertFalse(c.increaseSwitchesOn)
    }

    @Test
    fun `granice zakresu`() {
        assertFalse(comfort.copy(canDecrease = false).controls().canDecrease)
        assertFalse(comfort.copy(canIncrease = false).controls().canIncrease)
    }

    @Test
    fun `wylaczona strefa - minus nieaktywny, plus ja wlacza`() {
        val c = off.controls()
        assertFalse(c.canDecrease)
        assertTrue(c.canIncrease && c.increaseSwitchesOn && c.profileEnabled)
    }

    @Test
    fun `wylaczona strefa bez funkcji wlaczania - plus nieaktywny`() {
        val c = off.copy(canSwitchOn = false).controls()
        assertFalse(c.canIncrease || c.increaseSwitchesOn)
        assertTrue(c.profileEnabled)
    }

    @Test
    fun `zajeta strefa blokuje wszystko`() {
        assertEquals(none, comfort.copy(busy = true).controls())
        assertEquals(none, off.copy(busy = true).controls())
    }

    @Test
    fun `brak polaczenia blokuje wszystko`() {
        assertEquals(none, comfort.controls(online = false))
        assertEquals(none, off.controls(online = false))
    }
}
