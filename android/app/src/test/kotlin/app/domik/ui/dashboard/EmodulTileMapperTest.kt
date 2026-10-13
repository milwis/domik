package app.domik.ui.dashboard

import app.domik.data.TileId
import app.domik.integrations.emodul.DemoEmodulApi
import app.domik.integrations.emodul.EmodulError
import app.domik.integrations.emodul.EmodulFixtures
import app.domik.integrations.emodul.EmodulKey
import app.domik.integrations.emodul.EmodulModuleData
import app.domik.integrations.emodul.EmodulModuleInfo
import app.domik.integrations.emodul.EmodulParser
import app.domik.integrations.emodul.EmodulSnapshot
import app.domik.integrations.emodul.EmodulState
import app.domik.integrations.emodul.EmodulTexts
import app.domik.integrations.emodul.EmodulTile
import app.domik.integrations.emodul.EmodulZone
import app.domik.integrations.emodul.MenuType
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** [EmodulTileMapper] i [EmodulDataAge]: tabela z `notes/EMODUL.md` §7.5 na fixtures i na danych demo. */
class EmodulTileMapperTest {
    private val pl = Locale.forLanguageTag("pl-PL")
    private val zone = ZoneOffset.UTC

    /** Polskie teksty jak w `values/strings.xml` (test nie czyta zasobów Androida). */
    private val texts = EmodulUiTexts { label, args ->
        when (label) {
            EmodulLabel.SETPOINT -> "zadana"
            EmodulLabel.RETURN -> "powrót"
            EmodulLabel.OPENING -> "otwarcie"
            EmodulLabel.PUMP -> "pompa"
            EmodulLabel.ON -> "zał."
            EmodulLabel.OFF -> "wył."
            EmodulLabel.RUNNING -> "Pracuje"
            EmodulLabel.STOPPED -> "Wyłączony"
            EmodulLabel.ACTIVE -> "Aktywny"
            EmodulLabel.INACTIVE -> "Nieaktywny"
            EmodulLabel.GEAR -> "bieg"
            EmodulLabel.BATTERY -> "bateria"
            EmodulLabel.SIGNAL -> "sygnał"
            EmodulLabel.HUMIDITY -> "wilgotność"
            EmodulLabel.RUN_TIME -> "czas pracy"
            EmodulLabel.DATE -> "data"
            EmodulLabel.VERSION -> "wersja"
            EmodulLabel.TYPE -> "typ"
            EmodulLabel.VALUE -> "wartość"
            EmodulLabel.BY_CURVE -> "wg krzywej grzewczej"
            EmodulLabel.ZONE_N -> "Strefa ${args[0]}"
            EmodulLabel.YESTERDAY -> "wczoraj"
            EmodulLabel.READING -> "Odczyt ${args[0]}"
            EmodulLabel.SPOKEN_AT -> "Ostatni odczyt o ${args[0]}"
            EmodulLabel.SPOKEN_DATE -> "Ostatni odczyt ${args[0]} o ${args[1]}"
        }
    }

    private val info = EmodulModuleInfo("0123456789abcdef0123456789abcdef", "Kocioł testowy", "pellet", "v", online = true)
    private val updated: Instant = EmodulFixtures.snapshot.lastUpdate!!
    private val fetchedAt = updated.toEpochMilli() + 10_000L

    private fun module(
        snapshot: EmodulSnapshot = EmodulFixtures.snapshot,
        online: Boolean = true,
        slot: Int = 1,
        enabled: Boolean = true,
    ) = EmodulModuleData(
        slot, info.copy(online = online), enabled, snapshot, EmodulFixtures.menus, EmodulFixtures.texts, fetchedAtMillis = fetchedAt,
    )

    private val fixtureUi: Map<TileId, EmodulTileUi> by lazy { EmodulTileMapper.map(module(), false, pl, texts) }

    private fun ui(id: Int): EmodulTileUi = fixtureUi.getValue(TileId.Emodul(1, id))

    // Typy kafelków (fixtures)

    @Test
    fun `typ 6 CO - aktualna z widgetu, zadana z menu, ikona kotla`() {
        val tile = ui(1006)
        assertEquals(EmodulFixtures.texts.text(app.domik.integrations.emodul.TxtId(1050)), tile.title)
        assertEquals("65,0°", tile.primary)
        assertEquals(listOf("zadana" to "65°"), tile.details)
        assertEquals(EmodulIcon.BOILER, tile.icon)
        assertFalse(tile.active)
        assertTrue(tile.description.contains("zadana 65 °C"), tile.description)
        assertTrue(tile.description.contains("65,0 °C"), tile.description)
    }

    @Test
    fun `typ 6 CWU - ikona wody i zadana 55`() {
        val tile = ui(1007)
        assertEquals("55,9°", tile.primary)
        assertEquals(listOf("zadana" to "55°"), tile.details)
        assertEquals(EmodulIcon.WATER, tile.icon)
    }

    @Test
    fun `typ 6 bez menu - widget z zerem to kreska, a wartosc glowna pochodzi z widgetu z danymi`() {
        val withoutMenus = EmodulTileMapper.map(module().copy(menus = emptyMap()), false, pl, texts)
        val tile = withoutMenus.getValue(TileId.Emodul(1, 1006))
        assertEquals("65,0°", tile.primary)
        assertEquals(listOf(EmodulFixtures.texts.text(app.domik.integrations.emodul.TxtId(937))!! to "—"), tile.details)
        assertEquals(EmodulIcon.GENERIC, tile.icon)
    }

    @Test
    fun `typ 1 - czujnik temperatury`() {
        val tile = ui(1009)
        assertEquals("12,0°", tile.primary)
        assertEquals(EmodulIcon.THERMOMETER, tile.icon)
        assertEquals(emptyList<Pair<String, String>>(), tile.details)
    }

    @Test
    fun `typy 11, 21, 22 - Pracuje lub Wylaczony, bieg wentylatora`() {
        val relay = ui(1002)
        assertEquals("Wyłączony", relay.primary)
        assertFalse(relay.active)
        assertEquals(EmodulIcon.RELAY, relay.icon)
        assertEquals(EmodulFixtures.texts.text(app.domik.integrations.emodul.TxtId(550)), relay.title)

        val fan = ui(1011)
        assertEquals("Pracuje", fan.primary)
        assertTrue(fan.active)
        assertEquals(listOf("bieg" to "100 %"), fan.details)
        assertEquals(EmodulIcon.FAN, fan.icon)

        assertEquals(EmodulIcon.PUMP, ui(1023).icon)
    }

    @Test
    fun `typ 23 - zawor na krzywej grzewczej pokazuje aktualna i zadana oraz podpis`() {
        val valve = ui(1012)
        assertEquals("Zawór wbudowany", valve.title)
        assertEquals("32,3°", valve.primary)
        assertEquals(
            listOf("zadana" to "28°", "powrót" to "55,8°", "otwarcie" to "0 %", "pompa" to "wył."),
            valve.details,
        )
        assertEquals("wg krzywej grzewczej", valve.note)
        assertEquals(0f, valve.progress)
        assertEquals(EmodulIcon.VALVE, valve.icon)
        assertTrue(valve.description.contains("32,3 °C"), valve.description)
        assertTrue(valve.description.contains("zadana 28 °C"), valve.description)
        assertTrue(valve.description.contains("wg krzywej grzewczej"), valve.description)
    }

    @Test
    fun `typ 24 - zawor numer 1 bez krzywej nie ma podpisu`() {
        val valve = ui(1014)
        assertEquals("Zawór 1", valve.title)
        assertNull(valve.note)
        assertEquals("zadana" to "48°", valve.details.first())
    }

    @Test
    fun `typ 31 - paliwo z paskiem postepu i czasem pracy`() {
        val fuel = ui(1010)
        assertEquals("78 %", fuel.primary)
        assertEquals(listOf("czas pracy" to "≈ 59 h"), fuel.details)
        assertEquals(0.78f, fuel.progress)
        assertEquals(EmodulIcon.FUEL, fuel.icon)
    }

    @Test
    fun `typ 32 - dezynfekcja`() {
        val tile = ui(3630)
        assertEquals("0 %", tile.primary)
        assertEquals(0f, tile.progress)
    }

    @Test
    fun `typ 40 - naglowek jako tytul i stan jako wartosc`() {
        val tile = ui(2040)
        assertEquals("Stan sterownika", tile.title)
        assertEquals("Ruszt", tile.primary)
        assertEquals(EmodulIcon.INFO, tile.icon)
    }

    @Test
    fun `typ 50 - wersja sterownika bez identyfikatora`() {
        val tile = ui(2000)
        assertEquals("ST-978 F2", tile.primary)
        assertEquals(listOf("wersja" to "2.7.0"), tile.details)
        assertFalse(tile.description.contains("mainController", ignoreCase = true))
    }

    @Test
    fun `typy 2 i 3 - czujnik plomienia i rozpalanie`() {
        val fire = ui(1016)
        assertEquals("Nieaktywny", fire.primary)
        assertEquals(EmodulIcon.FLAME, fire.icon)
        assertEquals("Aktywny", ui(2010).primary)
        assertEquals(EmodulIcon.FLAME, ui(2010).icon)
    }

    @Test
    fun `typ 3 - rozpalanie 0 to nieaktywny, nieznana wartosc zostaje surowa, brak wartosci to brak tekstu`() {
        fun firing(value: Int?) = mapOne(EmodulTile.FiringUp(5, 0, 0, null, true, value))
        assertEquals("Nieaktywny", firing(0).primary)
        assertEquals("7", firing(7).primary)
        assertNull(firing(null).primary)
    }

    @Test
    fun `typ 41 - zegar sterownika i data`() {
        val clock = EmodulTile.ControllerDate(1, 0, 0, null, true, java.time.LocalDateTime.of(2026, 10, 13, 17, 37))
        val tile = mapOne(clock)
        assertEquals("17:37", tile.primary)
        assertEquals(listOf("data" to "13.10.2026"), tile.details)
        assertEquals(EmodulIcon.CLOCK, tile.icon)
    }

    @Test
    fun `nieznany typ - nazwa z opisu API i surowy typ`() {
        val tile = mapOne(EmodulTile.Unknown(7, 0, 0, null, true, type = 252, description = "OpenTherm"))
        assertEquals("OpenTherm", tile.title)
        assertNull(tile.primary)
        assertEquals(listOf("typ" to "252"), tile.details)
        assertEquals(EmodulIcon.GENERIC, tile.icon)
        assertEquals("Kafelek 8", mapOne(EmodulTile.Unknown(8, 0, 0, null, true, 1, "")).title)
    }

    @Test
    fun `brak odczytu czujnika to kreska`() {
        val sensor = EmodulTile.TemperatureSensor(5, 0, 0, null, true, app.domik.integrations.emodul.TxtId(795), null, true, null, null)
        assertEquals("—", mapOne(sensor).primary)
    }

    @Test
    fun `strefa - aktualna, zadana, wilgotnosc i grzanie`() {
        val snapshot = EmodulSnapshot(
            tiles = emptyList(),
            zones = listOf(EmodulZone(101, "Salon", 21.9, 23.0, true, 45, true), EmodulZone(102, "", null, null, null, 0, true)),
            order = emptyList(), lastUpdate = updated,
        )
        val ui = EmodulTileMapper.map(module(snapshot), false, pl, texts)
        val salon = ui.getValue(TileId.EmodulZone(1, 101))
        assertEquals("Salon", salon.title)
        assertEquals("21,9°", salon.primary)
        assertEquals(listOf("zadana" to "23,0°", "wilgotność" to "45 %"), salon.details)
        assertTrue(salon.active)
        val empty = ui.getValue(TileId.EmodulZone(1, 102))
        assertEquals("Strefa 102", empty.title)
        assertEquals("—", empty.primary)
        assertEquals(emptyList<Pair<String, String>>(), empty.details)
        assertFalse(empty.active)
    }

    private fun mapOne(tile: EmodulTile): EmodulTileUi {
        val snapshot = EmodulSnapshot(listOf(tile), emptyList(), emptyList(), updated)
        return EmodulTileMapper.map(module(snapshot), false, pl, texts).getValue(TileId.Emodul(1, tile.id))
    }

    // Czas danych, nieaktualność, moduły

    @Test
    fun `czas danych to tilesLastUpdate sterownika, a nie czas pobrania`() {
        val tile = ui(1006)
        assertEquals(updated, tile.dataTime)
        assertEquals(fetchedAt, tile.fetchedAtMillis)
        assertEquals(info.name, tile.module)
    }

    @Test
    fun `bez tilesLastUpdate czasem danych jest czas pobrania`() {
        val snapshot = EmodulFixtures.snapshot.copy(lastUpdate = null)
        val tile = EmodulTileMapper.map(module(snapshot), false, pl, texts).getValue(TileId.Emodul(1, 1006))
        assertEquals(Instant.ofEpochMilli(fetchedAt), tile.dataTime)
    }

    @Test
    fun `nieaktualne po 15 minutach, offline zawsze`() {
        val tile = ui(1006)
        assertFalse(tile.isStale(updated.toEpochMilli() + 14 * 60_000L))
        assertTrue(tile.isStale(updated.toEpochMilli() + 16 * 60_000L))
        val offline = EmodulTileMapper.map(module(online = false), false, pl, texts).getValue(TileId.Emodul(1, 1006))
        assertTrue(offline.isStale(updated.toEpochMilli()))
    }

    @Test
    fun `mapa stanu - tylko Ready, tylko wlaczone moduly z migawka, flaga wielu modulow`() {
        assertTrue(EmodulTileMapper.map(EmodulState.NotConfigured, pl, texts).isEmpty())
        assertTrue(EmodulTileMapper.map(EmodulState.Loading, pl, texts).isEmpty())
        assertTrue(EmodulTileMapper.map(EmodulState.KeyRejected, pl, texts).isEmpty())
        assertTrue(EmodulTileMapper.map(EmodulState.Failed(EmodulError.NETWORK), pl, texts).isEmpty())

        val one = EmodulTileMapper.map(EmodulState.Ready(listOf(module(), module(slot = 2, enabled = false)), null), pl, texts)
        assertTrue(one.keys.all { it is TileId.EmodulBase && it.slot == 1 })
        assertTrue(one.values.none { it.multiModule })

        val two = EmodulTileMapper.map(EmodulState.Ready(listOf(module(), module(slot = 2)), null), pl, texts)
        assertEquals(setOf(1, 2), two.keys.map { (it as TileId.EmodulBase).slot }.toSet())
        assertTrue(two.values.all { it.multiModule })
    }

    // Demo

    @Test
    fun `demo - kazdy z 18 kafelkow ma tytul, czas danych i opis`() {
        val clock = 1_760_000_000_000L
        val api = DemoEmodulApi { clock }
        val key = EmodulKey("demo.demo.demo-demo-demo-demo", 1L)
        val snapshot = EmodulParser.parseModule(api.module(key, DemoEmodulApi.UDID))
        val menus = MenuType.entries.associateWith { EmodulParser.parseMenu(api.menu(key, DemoEmodulApi.UDID, it), it) }
        val dictionary = EmodulParser.parseI18n(api.i18n(key, "pl"))
        val data = EmodulModuleData(
            1, info.copy(udid = DemoEmodulApi.UDID), true, snapshot, menus, EmodulTexts(dictionary, EmodulFixtures.TestFallbacks), clock,
        )
        val result = EmodulTileMapper.map(data, false, pl, texts)
        assertEquals(18, result.size)
        result.values.forEach {
            assertTrue(it.title.isNotBlank(), it.toString())
            assertTrue(it.description.startsWith(it.title), it.description)
            assertNotNull(it.dataTime)
            assertFalse(it.isStale(clock + 60_000L))
        }
        val co = result.getValue(TileId.Emodul(1, 2))
        assertEquals("Temperatura CO", co.title)
        assertEquals(listOf("zadana" to "65°"), co.details)
        assertEquals(EmodulIcon.BOILER, co.icon)
        assertEquals(EmodulIcon.WATER, result.getValue(TileId.Emodul(1, 3)).icon)
        assertEquals("wg krzywej grzewczej", result.getValue(TileId.Emodul(1, 5)).note)
        assertNull(result.getValue(TileId.Emodul(1, 4)).note)
        assertEquals("64 %", result.getValue(TileId.Emodul(1, 7)).primary)
    }

    // Czas danych na kafelku

    private val noon = Instant.parse("2026-10-13T14:32:00Z")

    private fun age(millisAfter: Long, compact: Boolean = false) =
        EmodulDataAge.label(noon, noon.toEpochMilli() + millisAfter, zone, texts, compact)

    private val hour = 3_600_000L

    @Test
    fun `podpis czasu danych - zawsze godzina sterownika z podpisem, bez teraz i minut temu`() {
        assertEquals("Odczyt 14:32", age(0))
        assertEquals("Odczyt 14:32", age(5 * 60_000L))
        assertEquals("Odczyt 14:32", age(9 * hour)) // do końca dnia (23:32)
        assertEquals("Odczyt 14:32", age(-10 * 60_000L)) // zegar tabletu cofnięty względem sterownika
        assertEquals("Odczyt wczoraj 14:32", age(10 * hour)) // 00:32 następnego dnia
        assertEquals("Odczyt wczoraj 14:32", age(33 * hour))
        assertEquals("Odczyt 13.10 14:32", age(34 * hour)) // przedwczoraj
        assertEquals("Odczyt 13.10 14:32", age(24 * 5 * hour))
    }

    @Test
    fun `podpis czasu danych na ciasnym kafelku - sama godzina`() {
        assertEquals("14:32", age(0, compact = true))
        assertEquals("wczoraj 14:32", age(10 * hour, compact = true))
        assertEquals("13.10 14:32", age(48 * hour, compact = true))
    }

    @Test
    fun `podpis czasu danych dla czytnika ekranu`() {
        fun spoken(after: Long) = EmodulDataAge.spoken(noon, noon.toEpochMilli() + after, zone, texts)
        assertEquals("Ostatni odczyt o 14:32", spoken(5 * 60_000L))
        assertEquals("Ostatni odczyt wczoraj o 14:32", spoken(12 * hour))
        assertEquals("Ostatni odczyt 13.10 o 14:32", spoken(60 * hour))
    }

    @Test
    fun `godzina w pasku uwagi - dzisiaj sama godzina, wczesniej z data`() {
        val now = noon.toEpochMilli() + 3_600_000L
        assertEquals("14:32", bannerSince(noon, now, zone))
        assertEquals("13.10 14:32", bannerSince(noon, now + 24 * 3_600_000L, zone))
    }
}
