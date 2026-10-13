package app.domik.integrations.emodul

import app.domik.data.EmodulCatalog
import app.domik.data.EmodulTileSeed
import app.domik.data.TileId
import app.domik.data.TileSize
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EmodulSeedsTest {
    private val key = EmodulKey("demo.demo.demo-demo-demo-demo", 1L)
    private val api = DemoEmodulApi { 1_760_000_000_000L }
    private val info = EmodulModuleInfo(DemoEmodulApi.UDID, "Demo", "pellet", "", online = true)
    private val texts = EmodulTexts(emptyMap(), EmodulFixtures.TestFallbacks)

    private fun data(slot: Int, snapshot: EmodulSnapshot?, enabled: Boolean = true, selection: EmodulSelection = EmodulSelection.EMPTY) =
        EmodulModuleData(slot, info, enabled, snapshot, emptyMap(), texts, fetchedAtMillis = 1L, selection = selection)

    private val demo = EmodulParser.parseModule(api.module(key, DemoEmodulApi.UDID))

    private fun all(slot: Int, snapshot: EmodulSnapshot): EmodulSelection {
        val ids = EmodulSeeds.candidates(data(slot, snapshot)).map { it.id }.toSet()
        return EmodulSelection(ids, ids)
    }

    @Test
    fun `demo - domyslne zaznaczenie to kafelki domyslnych rodzajow w kolejnosci eModul`() {
        val module = data(1, demo)
        val candidates = EmodulSeeds.candidates(module)
        assertEquals(18, candidates.size)
        // 40 (tekst), 6 CO, 6 CWU, 23 i 24 (zawory), 1 (temp. zewn.), 31 (paliwo)
        assertEquals((1..7).map { TileId.Emodul(1, it) }, candidates.filter { it.preselected }.map { it.id })
        assertEquals((1..7).map { TileId.Emodul(1, it) }.toSet(), EmodulSeeds.defaultSelection(module))
        assertEquals((1..18).map { TileId.Emodul(1, it) }, candidates.map { it.id })
    }

    @Test
    fun `bez wyboru katalog modulu jest pusty - wybor jest zrodlem prawdy`() {
        assertEquals(emptyList<EmodulTileSeed>(), EmodulSeeds.of(data(1, demo)))
    }

    @Test
    fun `of zwraca tylko wybrane kafelki obecne w migawce, w kolejnosci eModul`() {
        val selection = EmodulSelection(
            selected = setOf(TileId.Emodul(1, 6), TileId.Emodul(1, 2), TileId.Emodul(1, 9999)),
            known = emptySet(),
        )
        val seeds = EmodulSeeds.of(data(1, demo, selection = selection))
        assertEquals(listOf<TileId>(TileId.Emodul(1, 2), TileId.Emodul(1, 6)), seeds.map { it.id })
    }

    @Test
    fun `demo - rozmiary - zawory L, widgety i tekst M, reszta S`() {
        val sizes = EmodulSeeds.of(data(1, demo, selection = all(1, demo))).associate { it.id to it.defaultSize }
        assertEquals(18, sizes.size)
        assertEquals(TileSize.M, sizes[TileId.Emodul(1, 1)])
        assertEquals(TileSize.M, sizes[TileId.Emodul(1, 2)])
        assertEquals(TileSize.L, sizes[TileId.Emodul(1, 4)])
        assertEquals(TileSize.L, sizes[TileId.Emodul(1, 5)])
        assertEquals(TileSize.S, sizes[TileId.Emodul(1, 6)])
        assertEquals(TileSize.S, sizes[TileId.Emodul(1, 7)])
        assertTrue(sizes.values.all { it in listOf(TileSize.S, TileSize.M, TileSize.L) })
    }

    @Test
    fun `identyfikatory niosa numer slotu`() {
        assertTrue(EmodulSeeds.candidates(data(3, demo)).all { it.id.slot == 3 })
    }

    @Test
    fun `fixture - strefy sa kandydatami (L), kafelki przed strefami, najwyzej osiem zaznaczonych domyslnie`() {
        val candidates = EmodulSeeds.candidates(data(2, EmodulFixtures.snapshot))
        assertTrue(candidates.count { it.preselected } <= EmodulSeeds.MAX_VISIBLE)
        assertEquals(candidates.map { it.id }.distinct(), candidates.map { it.id })
        val zones = candidates.filter { it.id is TileId.EmodulZone }
        assertEquals(EmodulFixtures.snapshot.zones.size, zones.size)
        assertTrue(zones.all { it.defaultSize == TileSize.L })
        assertTrue(candidates.indexOfFirst { it.id is TileId.EmodulZone }.let { it < 0 || candidates.drop(it).all { c -> c.id is TileId.EmodulZone } })
    }

    @Test
    fun `limit osmiu domyslnie zaznaczonych - nadmiarowe kafelki sa odznaczone`() {
        val many = EmodulSnapshot(
            tiles = (1..12).map { demo.tiles[1] },
            zones = emptyList(), order = emptyList(), lastUpdate = null,
        )
        val candidates = EmodulSeeds.candidates(data(1, many))
        assertEquals(12, candidates.size)
        assertEquals(EmodulSeeds.MAX_VISIBLE, candidates.count { it.preselected })
    }

    @Test
    fun `modul bez migawki nie ma kandydatow ani ziaren`() {
        assertEquals(emptyList<Any>(), EmodulSeeds.candidates(data(1, null)))
        assertEquals(emptyList<Any>(), EmodulSeeds.of(data(1, null, selection = EmodulSelection(setOf(TileId.Emodul(1, 1)), emptySet()))))
    }

    // Katalog ze stanu

    @Test
    fun `katalog - brak klucza to katalog pusty, ladowanie, odrzucony klucz i blad to null`() {
        assertEquals(EmodulCatalog(emptyMap()), EmodulState.NotConfigured.toCatalog())
        assertNull(EmodulState.KeyRejected.toCatalog())
        assertNull(EmodulState.Loading.toCatalog())
        assertNull(EmodulState.Failed(EmodulError.NETWORK).toCatalog())
    }

    @Test
    fun `katalog - gotowe - tylko wlaczone sloty, slot bez migawki ma null`() {
        val state = EmodulState.Ready(
            modules = listOf(data(1, demo, selection = all(1, demo)), data(2, demo, enabled = false), data(3, null)), error = null,
        )
        val catalog = state.toCatalog()!!
        assertEquals(setOf(1, 3), catalog.slots.keys)
        assertEquals(18, catalog.slots[1]!!.size)
        assertNull(catalog.slots[3])
    }
}
