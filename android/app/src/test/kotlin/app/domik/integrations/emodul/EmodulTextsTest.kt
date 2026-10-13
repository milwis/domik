package app.domik.integrations.emodul

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EmodulTextsTest {
    private val snapshot = EmodulFixtures.snapshot
    private val menus = EmodulFixtures.menus
    private val texts = EmodulFixtures.texts

    @Test
    fun `slownik fixtures zawiera wszystkie potrzebne id`() {
        val needed = EmodulTexts.requiredIds(snapshot, menus)
        val missing = needed.filterNot { texts.has(it) }
        assertTrue(missing.isEmpty(), "Brak w słowniku: $missing")
        // nazwy kafelkow, widzety, nastawy i grupy zaworow
        for (id in listOf(795, 550, 571, 937, 938, 1050, 1041, 1039, 676, 1035, 856, 991, 988, 441)) {
            assertTrue(id in needed, "Brak id $id w wymaganych")
        }
    }

    @Test
    fun `wymagane id nie obejmuja statusId kafelka typu 6 ani id spoza kafelkow`() {
        val needed = EmodulTexts.requiredIds(snapshot, menus)
        assertFalse(1 in needed) // statusId = 1 kafelkow 6 to nie txtId
        assertFalse(0 in needed)
        assertFalse(-1 in needed)
    }

    @Test
    fun `podzbior zachowuje tylko zadane id`() {
        val full = mapOf(1 to "a", 2 to "b", 3 to "c")
        assertEquals(mapOf(1 to "a", 3 to "c"), EmodulTexts.subset(full, setOf(1, 3, 9)))
    }

    @Test
    fun `tekst ze slownika i brak dla id niedodatniego`() {
        assertEquals("Temperatura zewnętrzna", texts.text(TxtId(795)))
        assertNull(texts.text(TxtId(0)))
        assertNull(texts.text(TxtId(-1)))
        assertNull(texts.text(TxtId(99999)))
    }

    @Test
    fun `nazwy kafelkow z fixtures`() {
        assertEquals("Temperatura zewnętrzna", texts.title(EmodulFixtures.tile<EmodulTile.TemperatureSensor>(1009)))
        assertEquals("Podajnik", texts.title(EmodulFixtures.tile<EmodulTile.Relay>(1002)))
        assertEquals("Stan sterownika", texts.title(EmodulFixtures.tile<EmodulTile.Text>(2040)))
        assertEquals("DISINFECTION", texts.title(EmodulFixtures.tile<EmodulTile.Disinfection>(3630))) // txtId 0 → zastępczy
    }

    @Test
    fun `teksty zastepcze gdy brak nazwy`() {
        val empty = EmodulTexts(emptyMap(), EmodulFixtures.TestFallbacks)
        assertEquals("FLAME_SENSOR", empty.title(EmodulFixtures.tile<EmodulTile.FireSensor>(1016)))
        assertEquals("FIRING_UP", empty.title(EmodulFixtures.tile<EmodulTile.FiringUp>(2010)))
        assertEquals("STATUS", empty.title(EmodulFixtures.tile<EmodulTile.Widgets>(1006)))
        assertEquals("FAN", empty.title(EmodulFixtures.tile<EmodulTile.Fan>(1011)))
        assertEquals("Zawór wbudowany (zastępczy)", empty.title(EmodulFixtures.tile<EmodulTile.Valve>(1012)))
        assertEquals("Zawór 1 (zastępczy)", empty.title(EmodulFixtures.tile<EmodulTile.Valve>(1014)))
        assertEquals("FUEL", empty.title(EmodulFixtures.tile<EmodulTile.Fuel>(1010)))
        assertEquals("DISINFECTION", empty.title(EmodulFixtures.tile<EmodulTile.Disinfection>(3630)))
        assertEquals("CONTROLLER", empty.title(EmodulFixtures.tile<EmodulTile.Version>(2000)))
        assertEquals("Wyjście 1002", empty.title(EmodulFixtures.tile<EmodulTile.Relay>(1002)))
        assertEquals("Pompa 1023", empty.title(EmodulFixtures.tile<EmodulTile.Pump>(1023)))
        assertEquals("Czujnik 1009", empty.title(EmodulFixtures.tile<EmodulTile.TemperatureSensor>(1009)))
        assertEquals("Kafelek 2040", empty.title(EmodulFixtures.tile<EmodulTile.Text>(2040)))
    }

    @Test
    fun `nieznany kafelek ma opis z API albo numer`() {
        val withDescription = EmodulTile.Unknown(5, 0, 0, null, true, 252, "OpenTherm")
        val without = EmodulTile.Unknown(6, 0, 0, null, true, 253, "")
        assertEquals("OpenTherm", texts.title(withDescription))
        assertEquals("Kafelek 6", texts.title(without))
    }
}
