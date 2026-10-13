package app.domik.data

import app.domik.deimic.config.HomeConfig
import app.domik.deimic.config.Output
import app.domik.deimic.config.OutputKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Kafelki eModul w układzie pulpitu: kody, zapis v4 i uzgadnianie z katalogiem (`EmodulCatalog`). Katalog zawiera
 * wyłącznie kafelki **wybrane przez użytkownika** (§6.4a) – wszystkie są widoczne, a odznaczone znikają z układu.
 */
class DashboardLayoutEmodulTest {
    private val config = HomeConfig(
        levels = emptyList(), rooms = emptyList(), modules = listOf(1),
        outputs = listOf(
            Output(
                dbId = 1001L, module = 1, relay = 1, kind = OutputKind.ON_OFF, rawType = 1, name = "Światło", roomId = null,
                levelId = null, iconId = null, canOn = true, outputsNumber = null,
            ),
        ),
        heatingZones = emptyList(), shutters = emptyList(),
    )

    private fun tile(slot: Int, id: Int) = TileId.Emodul(slot, id)
    private fun zone(slot: Int, id: Int) = TileId.EmodulZone(slot, id)
    private fun seed(id: TileId.EmodulBase, size: TileSize = TileSize.S) = EmodulTileSeed(id, size)
    private fun catalog(vararg slots: Pair<Int, List<EmodulTileSeed>?>) = EmodulCatalog(mapOf(*slots))
    private fun entry(id: TileId, size: TileSize = id.kind.defaultSize) = TileEntry(id, size)

    private val switchId = TileId.Switch(1, 1)

    // Kody

    @Test
    fun `kody kafelkow eModul - kafelek i strefa, zapis i odczyt`() {
        assertEquals("e:1:1006", tile(1, 1006).code)
        assertEquals("ez:2:101", zone(2, 101).code)
        assertEquals(tile(1, 1006), TileId.decode("e:1:1006"))
        assertEquals(zone(2, 101), TileId.decode("ez:2:101"))
        assertEquals(TileKind.EMODUL, tile(1, 1).kind)
        assertEquals(TileKind.EMODUL, zone(1, 1).kind)
        assertEquals(1006, tile(1, 1006).number)
        assertEquals(101, zone(2, 101).number)
    }

    @Test
    fun `uszkodzone kody eModul sa pomijane`() {
        listOf("e:x:1", "e:1", "e:1:2:3", "e:0:5", "e:-1:5", "ez:a:b", "ez:0:1", "e::", "ex:1:1").forEach {
            assertNull(TileId.decode(it), it)
        }
    }

    @Test
    fun `zapis v4 z kafelkami eModul odtwarza sie bez zmian`() {
        val layout = DashboardLayout(
            tiles = listOf(entry(TileId.Weather, TileSize.M), entry(tile(1, 6), TileSize.M), entry(zone(2, 101), TileSize.L)),
            hidden = listOf(entry(tile(1, 40), TileSize.S)),
        )
        assertEquals(layout, DashboardLayout.decode(layout.encode()))
        assertTrue(layout.encode().contains("e:1:6"))
        assertTrue(layout.encode().contains("ez:2:101"))
    }

    @Test
    fun `nieczytelny wpis eModul w zapisie jest pomijany, reszta zostaje`() {
        val layout = DashboardLayout(listOf(entry(TileId.Weather, TileSize.M), entry(tile(1, 6), TileSize.M)), emptyList())
        val text = layout.encode().replace("e:1:6", "e:x:6")
        assertEquals(listOf<TileId>(TileId.Weather), DashboardLayout.decode(text)!!.tiles.map { it.id })
    }

    @Test
    fun `rozmiar kafelka eModul spoza S, M, L jest zastapiony dozwolonym`() {
        assertThrows(IllegalArgumentException::class.java) { seed(tile(1, 1), TileSize(4, 4)) }
        val text = DashboardLayout(listOf(entry(tile(1, 6), TileSize.M)), emptyList()).encode().replace("e:1:6 2x1", "e:1:6 4x4")
        val decoded = DashboardLayout.decode(text)!!
        assertTrue(TileKind.EMODUL.allows(decoded.tiles.single().size))
    }

    // Uklad domyslny

    @Test
    fun `uklad domyslny bez katalogu nie ma kafelkow eModul`() {
        val layout = DashboardLayout.default(config)
        assertTrue(layout.tiles.none { it.id is TileId.EmodulBase } && layout.hidden.none { it.id is TileId.EmodulBase })
    }

    @Test
    fun `uklad domyslny z katalogiem - wszystkie wybrane na koncu pulpitu, widoczne, rozmiary z ziaren`() {
        val catalog = catalog(
            1 to listOf(seed(tile(1, 6), TileSize.M), seed(zone(1, 5), TileSize.L), seed(tile(1, 11))),
        )
        val layout = DashboardLayout.default(config, emodul = catalog)
        assertEquals(listOf<TileId>(tile(1, 6), zone(1, 5), tile(1, 11)), layout.tiles.takeLast(3).map { it.id })
        assertEquals(listOf(TileSize.M, TileSize.L, TileSize.S), layout.tiles.takeLast(3).map { it.size })
        assertTrue(switchId in layout.tiles.map { it.id })
        assertTrue(layout.hidden.none { it.id is TileId.EmodulBase })
    }

    @Test
    fun `reconcile bez zapisu uzywa ukladu domyslnego z katalogiem`() {
        val catalog = catalog(1 to listOf(seed(tile(1, 6))))
        assertEquals(DashboardLayout.default(config, emodul = catalog), DashboardLayout.reconcile(null, config, emodul = catalog))
    }

    // Uzgadnianie

    private val saved = DashboardLayout(
        tiles = listOf(entry(TileId.Weather, TileSize.M), entry(switchId), entry(tile(1, 6), TileSize.M)),
        hidden = listOf(entry(tile(1, 40)), entry(zone(2, 101), TileSize.L)),
    )

    private fun ids(entries: List<TileEntry>) = entries.map { it.id }

    @Test
    fun `reconcile bez katalogu (ladowanie) zostawia wpisy eModul`() {
        val result = DashboardLayout.reconcile(saved, config, emodul = null)
        assertEquals(saved.tiles, result.tiles.filter { it.id != TileId.Agenda })
        assertEquals(saved.hidden, result.hidden)
    }

    @Test
    fun `reconcile z pustym katalogiem (klucz usuniety) usuwa wszystkie wpisy eModul`() {
        val result = DashboardLayout.reconcile(saved, config, emodul = EmodulCatalog(emptyMap()))
        assertTrue((result.tiles + result.hidden).none { it.id is TileId.EmodulBase })
        assertTrue(switchId in ids(result.tiles))
    }

    @Test
    fun `reconcile - slot nieobecny w katalogu traci wpisy, pozostale sloty zostaja`() {
        val result = DashboardLayout.reconcile(saved, config, emodul = catalog(1 to null))
        assertTrue(tile(1, 6) in ids(result.tiles) && tile(1, 40) in ids(result.hidden))
        assertTrue(zone(2, 101) !in ids(result.hidden))
    }

    @Test
    fun `reconcile - slot bez danych (null) zachowuje wpisy`() {
        val result = DashboardLayout.reconcile(saved, config, emodul = catalog(1 to null, 2 to null))
        assertTrue(tile(1, 6) in ids(result.tiles))
        assertEquals(listOf<TileId>(tile(1, 40), zone(2, 101)), ids(result.hidden))
    }

    @Test
    fun `reconcile - kafelek, ktorego nie ma juz w module, znika z pulpitu i z ukrytych`() {
        val result = DashboardLayout.reconcile(saved, config, emodul = catalog(1 to listOf(seed(tile(1, 40))), 2 to emptyList()))
        assertTrue(tile(1, 6) !in ids(result.tiles))
        assertEquals(listOf<TileId>(tile(1, 40)), ids(result.hidden))
    }

    @Test
    fun `reconcile - zaznaczony kafelek spoza ukladu trafia na koniec pulpitu z rozmiarem domyslnym, sloty rosnaco`() {
        val catalog = catalog(
            1 to listOf(seed(tile(1, 6), TileSize.M), seed(tile(1, 40)), seed(tile(1, 23), TileSize.L)),
            2 to listOf(seed(zone(2, 101), TileSize.L)),
        )
        val result = DashboardLayout.reconcile(saved, config, emodul = catalog)
        // tile(1,6) był na pulpicie (zostaje na swoim miejscu); tile(1,40) i zone(2,101) były ukryte (zostają ukryte)
        assertEquals(saved.tiles.map { it.id } + listOf(tile(1, 23)), ids(result.tiles).filter { it != TileId.Agenda })
        assertEquals(TileSize.L, result.tiles.last().size)
        assertEquals(saved.hidden, result.hidden)
    }

    @Test
    fun `reconcile - pierwsze zaznaczenie modulu - wszystkie wybrane widoczne na koncu pulpitu, sloty rosnaco`() {
        val onlyDeimic = DashboardLayout(listOf(entry(TileId.Weather, TileSize.M), entry(switchId)), emptyList())
        val catalog = catalog(
            3 to listOf(seed(tile(3, 1)), seed(tile(3, 2), TileSize.M)),
            2 to listOf(seed(tile(2, 1)), seed(tile(2, 2))),
        )
        val result = DashboardLayout.reconcile(onlyDeimic, config, emodul = catalog)
        assertEquals(
            listOf<TileId>(TileId.Weather, switchId, TileId.Agenda, tile(2, 1), tile(2, 2), tile(3, 1), tile(3, 2)), ids(result.tiles),
        )
        assertEquals(TileSize.M, result.tiles.last().size)
        assertTrue(result.hidden.isEmpty())
    }

    @Test
    fun `reconcile - ukrycie kafelka w edycji pulpitu nie zmienia wyboru - ukryty zostaje ukryty, nowo zaznaczony jest widoczny`() {
        val oneHidden = DashboardLayout(listOf(entry(TileId.Weather, TileSize.M), entry(switchId)), listOf(entry(tile(1, 6))))
        val result = DashboardLayout.reconcile(oneHidden, config, emodul = catalog(1 to listOf(seed(tile(1, 6)), seed(tile(1, 7)))))
        assertEquals(listOf<TileId>(tile(1, 7)), ids(result.tiles).filterIsInstance<TileId.EmodulBase>())
        assertEquals(listOf<TileId>(tile(1, 6)), ids(result.hidden))
    }

    @Test
    fun `reconcile - odznaczenie usuwa kafelek takze z ukrytych, ponowne zaznaczenie dopisuje go na koniec pulpitu`() {
        val unchecked = DashboardLayout.reconcile(saved, config, emodul = catalog(1 to emptyList(), 2 to emptyList()))
        assertTrue((unchecked.tiles + unchecked.hidden).none { it.id is TileId.EmodulBase })
        val checkedAgain = DashboardLayout.reconcile(unchecked, config, emodul = catalog(1 to listOf(seed(tile(1, 40)))))
        assertEquals(tile(1, 40), checkedAgain.tiles.last().id)
        assertTrue(checkedAgain.hidden.isEmpty())
    }

    @Test
    fun `reconcile - ponowne uzgodnienie jest idempotentne`() {
        val catalog = catalog(1 to listOf(seed(tile(1, 6), TileSize.M), seed(tile(1, 40))))
        val once = DashboardLayout.reconcile(saved, config, emodul = catalog)
        assertEquals(once, DashboardLayout.reconcile(once, config, emodul = catalog))
    }

    @Test
    fun `reconcile bez konfiguracji Deimic zwraca zapis bez zmian, takze z kafelkami eModul`() {
        assertSame(saved, DashboardLayout.reconcile(saved, null, emodul = EmodulCatalog(emptyMap())))
    }

    @Test
    fun `wpisy Deimic i eModul nie przeszkadzaja sobie przy usuwaniu - nieistniejacy przelacznik znika, eModul zostaje`() {
        val withGhost = DashboardLayout(listOf(entry(TileId.Switch(1, 99)), entry(tile(1, 6), TileSize.M)), emptyList())
        val result = DashboardLayout.reconcile(withGhost, config, emodul = null)
        assertFalse(TileId.Switch(1, 99) in ids(result.tiles))
        assertTrue(tile(1, 6) in ids(result.tiles))
    }
}
