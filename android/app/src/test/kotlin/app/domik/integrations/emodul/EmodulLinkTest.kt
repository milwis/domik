package app.domik.integrations.emodul

import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EmodulLinkTest {
    private val pl = Locale.forLanguageTag("pl-PL")
    private val menus = EmodulFixtures.menus
    private val texts = EmodulFixtures.texts

    private fun views(id: Int, menus: Map<MenuType, EmodulMenu> = this.menus) =
        EmodulLinks.widgetViews(EmodulFixtures.tile<EmodulTile.Widgets>(id), menus, pl)

    @Test
    fun `CO - zadana 65 z menu a aktualna z widzetu 2`() {
        val (setpoint, current) = views(1006)
        assertEquals(WidgetRole.SETPOINT, setpoint.role)
        assertEquals(1, setpoint.index)
        assertEquals("65°", setpoint.text!!.short)
        assertEquals("65 °C", setpoint.text!!.spoken)
        assertEquals(WidgetRole.CURRENT, current.role)
        assertEquals("65,0°", current.text!!.short)
    }

    @Test
    fun `CWU - zadana 55 a aktualna 55,9`() {
        val (setpoint, current) = views(1007)
        assertEquals("55°", setpoint.text!!.short)
        assertEquals(WidgetRole.CURRENT, current.role)
        assertEquals("55,9°", current.text!!.short)
    }

    @Test
    fun `bez menu widzet z zerem pokazuje kreske a drugi jednostke`() {
        val (zeroed, other) = views(1006, emptyMap())
        assertEquals(WidgetRole.OTHER, zeroed.role)
        assertNull(zeroed.text)
        assertEquals("65,0°", other.text!!.short)
    }

    @Test
    fun `id jednoczesnie w MU i MI nie jest wiazane`() {
        fun menu(type: MenuType) = EmodulParser.parseMenu(
            EmodulFixtures.menuJson(
                EmodulFixtures.element(2060, 0, 1, 937, """{"format":1,"value":65,"min":53,"max":80,"txtId":1035}"""),
            ),
            type,
        )
        val both = mapOf(MenuType.MU to menu(MenuType.MU), MenuType.MI to menu(MenuType.MI))
        val tile = EmodulFixtures.tile<EmodulTile.Widgets>(1006)
        assertNull(EmodulLinks.menuItemFor(tile, both))
        val (first, _) = EmodulLinks.widgetViews(tile, both, pl)
        assertEquals(WidgetRole.OTHER, first.role)
        assertNull(first.text)
        assertEquals(2060, EmodulLinks.menuItemFor(tile, mapOf(MenuType.MU to menu(MenuType.MU)))?.id)
    }

    @Test
    fun `element menu bez zgodnego formatu albo bez wartosci nie jest zadana`() {
        fun views(params: String, kind: Int = 1): List<EmodulWidgetView> {
            val menu = EmodulParser.parseMenu(
                EmodulFixtures.menuJson(EmodulFixtures.element(2060, 0, kind, 937, params)), MenuType.MU,
            )
            return EmodulLinks.widgetViews(EmodulFixtures.tile(1006), mapOf(MenuType.MU to menu), pl)
        }
        assertEquals(WidgetRole.OTHER, views("""{"format":2,"value":65}""").first().role)
        assertEquals(WidgetRole.OTHER, views("""{"format":1}""").first().role)
        assertEquals(WidgetRole.OTHER, views("""{"format":3,"value":65}""", kind = 3).first().role)
        // wartosc ×0,1 (kind 2)
        val tenths = views("""{"format":2,"value":655}""", kind = 2).first()
        assertEquals(WidgetRole.SETPOINT, tenths.role)
        assertEquals("65,5°", tenths.text!!.short)
    }

    @Test
    fun `zawor wbudowany ma krzywa i nazwe grupy z MI`() {
        val builtIn = EmodulFixtures.tile<EmodulTile.Valve>(1012)
        assertTrue(EmodulLinks.usesHeatingCurve(builtIn, menus))
        assertEquals("Zawór wbudowany", EmodulLinks.valveName(builtIn, menus, texts))
    }

    @Test
    fun `zawor 1 ma nazwe z MI i nie ma krzywej`() {
        val valve1 = EmodulFixtures.tile<EmodulTile.Valve>(1014)
        assertFalse(EmodulLinks.usesHeatingCurve(valve1, menus))
        assertEquals("Zawór 1", EmodulLinks.valveName(valve1, menus, texts))
    }

    @Test
    fun `nazwa zaworu bez menu to tekst zastepczy`() {
        val valve1 = EmodulFixtures.tile<EmodulTile.Valve>(1014)
        assertEquals("Zawór 1 (zastępczy)", EmodulLinks.valveName(valve1, emptyMap(), texts))
        assertFalse(EmodulLinks.usesHeatingCurve(valve1, emptyMap()))
    }

    @Test
    fun `zerwany lancuch menu i brak tekstu grupy daja tekst zastepczy`() {
        val broken = EmodulParser.parseMenu(
            EmodulFixtures.menuJson(
                EmodulFixtures.element(0, 0, 0, 389),
                EmodulFixtures.element(2429, 777, 1, 759),
            ),
            MenuType.MI,
        )
        val valve1 = EmodulFixtures.tile<EmodulTile.Valve>(1014)
        assertEquals("Zawór 1 (zastępczy)", EmodulLinks.valveName(valve1, mapOf(MenuType.MI to broken), texts))
        val noText = EmodulParser.parseMenu(
            EmodulFixtures.menuJson(
                EmodulFixtures.element(0, 0, 0, 389),
                EmodulFixtures.element(30000, 0, 0, 1),
                EmodulFixtures.element(30001, 30000, 0, 99998),
                EmodulFixtures.element(2429, 30001, 1, 759),
            ),
            MenuType.MI,
        )
        assertEquals("Zawór 1 (zastępczy)", EmodulLinks.valveName(valve1, mapOf(MenuType.MI to noText), texts))
    }

    @Test
    fun `cykl w menu nie zapetla`() {
        val cyclic = EmodulParser.parseMenu(
            EmodulFixtures.menuJson(
                EmodulFixtures.element(2429, 11, 1, 759),
                EmodulFixtures.element(11, 12, 0, 1),
                EmodulFixtures.element(12, 11, 0, 1),
            ),
            MenuType.MI,
        )
        val valve1 = EmodulFixtures.tile<EmodulTile.Valve>(1014)
        assertEquals("Zawór 1 (zastępczy)", EmodulLinks.valveName(valve1, mapOf(MenuType.MI to cyclic), texts))
    }
}
