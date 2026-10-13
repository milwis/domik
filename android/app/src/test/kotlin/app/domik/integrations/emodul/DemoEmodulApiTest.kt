package app.domik.integrations.emodul

import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class DemoEmodulApiTest {
    private var now = 1_760_000_000_000L
    private val api = DemoEmodulApi { now }
    private val key = EmodulKey("demo.demo.demo-demo-demo-demo", 1L)

    private val module get() = EmodulParser.parseModule(api.module(key, DemoEmodulApi.UDID))
    private fun menu(type: MenuType) = EmodulParser.parseMenu(api.menu(key, DemoEmodulApi.UDID, type), type)
    private val menus get() = MenuType.entries.associateWith(::menu)
    private val texts get() = EmodulTexts(EmodulParser.parseI18n(api.i18n(key, "pl")), EmodulFixtures.TestFallbacks)

    @Test
    fun `wszystkie odpowiedzi sa poprawnym JSON-em i parsuja sie parserem produkcyjnym`() {
        val modules = EmodulParser.parseModules(api.modules(key))
        assertEquals(listOf(DemoEmodulApi.UDID), modules.map { it.udid })
        assertEquals("pellet", modules.single().type)
        assertTrue(modules.single().online)
        assertTrue(EmodulUrls.isValidUdid(DemoEmodulApi.UDID))
        assertEquals(18, module.tiles.size)
        assertTrue(menus.getValue(MenuType.MU).items.isNotEmpty())
        assertTrue(menus.getValue(MenuType.MI).items.isNotEmpty())
        assertTrue(EmodulParser.parseI18n(api.i18n(key, "pl")).isNotEmpty())
        assertTrue(EmodulParser.parseI18n(api.i18n(key, "en")).isNotEmpty())
    }

    @Test
    fun `jest co najmniej jeden kafelek kazdego obslugiwanego typu i zaden nie jest nieznany`() {
        val tiles = module.tiles
        val kinds = tiles.map { it::class }.toSet()
        val expected = setOf(
            EmodulTile.TemperatureSensor::class, EmodulTile.FireSensor::class, EmodulTile.FiringUp::class,
            EmodulTile.Widgets::class, EmodulTile.Relay::class, EmodulTile.Pump::class, EmodulTile.Fan::class,
            EmodulTile.Valve::class, EmodulTile.Fuel::class, EmodulTile.Disinfection::class, EmodulTile.Text::class,
            EmodulTile.ControllerDate::class, EmodulTile.Version::class, EmodulTile.PeripheralVersion::class,
            EmodulTile.Container::class,
        )
        assertEquals(expected, kinds)
        assertFalse(tiles.any { it is EmodulTile.Unknown })
        assertEquals(2, tiles.count { it is EmodulTile.Valve })
        assertEquals(setOf(true, false), tiles.filterIsInstance<EmodulTile.Valve>().map { it.builtIn }.toSet())
    }

    @Test
    fun `identyfikatory kafelkow sa unikalne a kolejnosc obejmuje wszystkie`() {
        val snapshot = module
        assertEquals(snapshot.tiles.size, snapshot.tiles.map { it.id }.toSet().size)
        assertEquals(snapshot.tiles.map { it.id }, snapshot.order)
        assertTrue(snapshot.tiles.all { it.visible && it.parentId == 0 })
    }

    @Test
    fun `brak danych osobowych w odpowiedziach`() {
        val all = listOf(
            api.modules(key), api.module(key, DemoEmodulApi.UDID), api.menu(key, DemoEmodulApi.UDID, MenuType.MU),
            api.menu(key, DemoEmodulApi.UDID, MenuType.MI), api.i18n(key, "pl"), api.i18n(key, "en"),
        )
        assertTrue(all.none { '@' in it })
    }

    @Test
    fun `kociol CO i CWU oddycha wokol zakresow ze specyfikacji i zalezy od czasu`() {
        val seen = mutableListOf<Double>()
        val seenWater = mutableListOf<Double>()
        repeat(120) {
            now = 1_760_000_000_000L + it * 10_000L
            val tiles = module.tiles.filterIsInstance<EmodulTile.Widgets>()
            seen += tiles[0].w2!!.raw / 10.0
            seenWater += tiles[1].w2!!.raw / 10.0
        }
        assertTrue(seen.all { it in 62.0..66.0 }, "CO $seen")
        assertTrue(seenWater.all { it in 49.5..51.0 }, "CWU $seenWater")
        assertTrue(seen.max() - seen.min() > 1.0)
        assertTrue(seenWater.max() - seenWater.min() > 0.5)
    }

    @Test
    fun `wynik jest deterministyczny dla tego samego czasu`() {
        assertEquals(api.module(key, DemoEmodulApi.UDID), api.module(key, DemoEmodulApi.UDID))
    }

    @Test
    fun `podajnik przelacza sie co 30 sekund`() {
        fun feeder(): Boolean = module.tiles.filterIsInstance<EmodulTile.Relay>().first { it.id == 8 }.on
        now = 30_000L * 100
        val first = feeder()
        now += 29_000L
        assertEquals(first, feeder())
        now += 2_000L
        assertNotEquals(first, feeder())
    }

    @Test
    fun `dane demo nigdy nie sa nieaktualne`() {
        val snapshot = module
        assertNotNull(snapshot.lastUpdate)
        assertFalse(EmodulPolicy.isStale(snapshot.lastUpdate, now, now))
        now += 5 * 60_000L
        assertFalse(EmodulPolicy.isStale(module.lastUpdate, now, now))
        assertEquals(EmodulStaleness.Fresh, EmodulPolicy.staleness(module.lastUpdate, now, true, now))
    }

    @Test
    fun `wartosci zadane CO i CWU biora sie z menu uzytkownika`() {
        val snapshot = module
        val menus = menus
        val co = snapshot.tiles.single { it.id == 2 } as EmodulTile.Widgets
        val water = snapshot.tiles.single { it.id == 3 } as EmodulTile.Widgets

        val coViews = EmodulLinks.widgetViews(co, menus, Locale.forLanguageTag("pl"))
        assertEquals(listOf(WidgetRole.SETPOINT, WidgetRole.CURRENT), coViews.map { it.role })
        assertEquals("65°", coViews[0].text!!.short)
        val waterViews = EmodulLinks.widgetViews(water, menus, Locale.forLanguageTag("pl"))
        assertEquals("52°", waterViews[0].text!!.short)
        assertEquals(WidgetRole.CURRENT, waterViews[1].role)
    }

    @Test
    fun `zawory maja nazwy z menu instalatora a drugi krzywa grzewcza`() {
        val snapshot = module
        val menus = menus
        val texts = texts
        val builtIn = snapshot.tiles.single { it.id == 4 } as EmodulTile.Valve
        val valve1 = snapshot.tiles.single { it.id == 5 } as EmodulTile.Valve

        assertEquals("Zawór wbudowany", EmodulLinks.valveName(builtIn, menus, texts))
        assertEquals("Zawór 1", EmodulLinks.valveName(valve1, menus, texts))
        assertFalse(EmodulLinks.usesHeatingCurve(builtIn, menus))
        assertTrue(EmodulLinks.usesHeatingCurve(valve1, menus))
    }

    @Test
    fun `wszystkie identyfikatory tekstow z kafelkow i menu sa w slowniku demo po polsku i angielsku`() {
        val snapshot = module
        val required = EmodulTexts.requiredIds(snapshot, menus)
        for (language in listOf("pl", "en")) {
            val dictionary = EmodulParser.parseI18n(api.i18n(key, language))
            assertEquals(emptySet<Int>(), required.filterNot { it in dictionary }.toSet(), language)
        }
        assertNotEquals(api.i18n(key, "pl"), api.i18n(key, "en"))
    }

    @Test
    fun `obcy modul nie istnieje`() {
        assertThrows(IOException::class.java) { api.module(key, "0123456789abcdef0123456789abcdef") }
        assertThrows(IOException::class.java) { api.menu(key, "0123456789abcdef0123456789abcdef", MenuType.MU) }
    }

    @Test
    fun `demo nie ma sieci a klucz-wypelniacz nie jest wypisywany`() {
        assertEquals("EmodulKey(<ukryty>)", key.toString())
        // zegar nie jest dotykany poza odczytem wartości – brak wątków, gniazd, opóźnień
        assertTrue(api.modules(key).isNotEmpty())
    }
}
