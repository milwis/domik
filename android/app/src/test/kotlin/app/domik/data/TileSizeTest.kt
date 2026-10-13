package app.domik.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Dowolne rozmiary kafelków (cols × rows): reguły rodzajów, przyciąganie, zapis v4 i migracje v3/v2/v1. */
class TileSizeTest {
    private fun size(cols: Int, rows: Int) = TileSize(cols, rows)

    // Rozmiar

    @Test
    fun `rozmiar ma co najmniej 1x1 i zapis kolumny x wiersze`() {
        assertEquals("2x4", size(2, 4).code)
        assertEquals(size(2, 4), TileSize.parse("2x4"))
        assertEquals(size(8, 8), TileSize.parse("8x8"))
        assertThrows(IllegalArgumentException::class.java) { size(0, 1) }
        assertThrows(IllegalArgumentException::class.java) { size(1, 0) }
        listOf("", "x", "2x", "x4", "0x1", "1x0", "-1x2", "2x4x1", "ax2", "2 x 4", "100x1", "q").forEach {
            assertNull(TileSize.parse(it), it)
        }
    }

    @Test
    fun `dawne kody liter sa czytane - kolumna c to 2x4`() {
        assertEquals(TileSize.S, TileSize.parse("s"))
        assertEquals(size(2, 1), TileSize.parse("m"))
        assertEquals(size(2, 2), TileSize.parse("l"))
        assertEquals(size(4, 2), TileSize.parse("xl"))
        assertEquals(size(2, 4), TileSize.parse("c"))
    }

    // Reguły rodzajów

    @Test
    fun `kalendarz - dowolny rozmiar od 2x2 do 8 kolumn i 8 wierszy`() {
        val agenda = TileKind.AGENDA
        assertTrue(agenda.freeSize)
        assertTrue(listOf(size(2, 2), size(2, 4), size(4, 3), size(3, 7), size(8, 8)).all(agenda::allows))
        assertFalse(listOf(size(1, 1), size(1, 4), size(2, 1), size(9, 2), size(2, 9)).any(agenda::allows))
    }

    @Test
    fun `pogoda - dowolny rozmiar od 1x1 do 8 kolumn i 2 wierszy`() {
        val weather = TileKind.WEATHER
        assertTrue(weather.freeSize)
        assertTrue(listOf(size(1, 1), size(1, 2), size(3, 1), size(8, 2)).all(weather::allows))
        // wyższa niż 2 wiersze rozsypuje układ kafelka (patrz WeatherLayout)
        assertFalse(listOf(size(1, 3), size(2, 4), size(8, 4), size(9, 1)).any(weather::allows))
    }

    @Test
    fun `rozmiary z listy przycisku rozmiar sa dozwolone dla kazdego rodzaju, a pogoda ma najwyzej 2 wiersze`() {
        for (kind in TileKind.entries) {
            assertTrue(kind.presets.all(kind::allows), "$kind: ${kind.presets}")
        }
        assertTrue(TileKind.WEATHER.presets.all { it.rows <= 2 }, "${TileKind.WEATHER.presets}")
    }

    @Test
    fun `przycisk rozmiar dla pogody nigdy nie daje wiecej niz 2 wiersze`() {
        var current = TileKind.WEATHER.defaultSize
        repeat(10) {
            current = TileKind.WEATHER.nextPreset(current) ?: current
            assertTrue(current.rows <= 2, "$current")
        }
        // rozmiar „z palca” (np. dawny 2x4) przechodzi na najbliższy większy z listy, a nie rośnie dalej
        assertTrue((TileKind.WEATHER.nextPreset(size(2, 4)) ?: size(1, 1)).rows <= 2)
    }

    @Test
    fun `ogrzewanie i rolety 1x1, 2x1 i 2x2, przelaczniki tylko 1x1`() {
        assertEquals(listOf(size(1, 1), size(2, 1), size(2, 2)), allowedUpTo4x4(TileKind.ZONE))
        assertEquals(listOf(size(1, 1)), allowedUpTo4x4(TileKind.SWITCH))
        assertEquals(listOf(size(1, 1), size(2, 1), size(2, 2)), allowedUpTo4x4(TileKind.SHUTTER))
        assertFalse(TileKind.ZONE.freeSize)
        assertTrue(TileKind.ZONE.resizable && TileKind.SHUTTER.resizable)
        assertFalse(TileKind.SWITCH.resizable)
        TileKind.entries.forEach { kind ->
            assertTrue(kind.allows(kind.defaultSize), "$kind: rozmiar domyślny dozwolony")
            assertTrue(kind.presets.all(kind::allows), "$kind: lista przycisku „rozmiar” dozwolona")
        }
    }

    @Test
    fun `kafelki eModul - rozmiary S, M, L (1x1, 2x1, 2x2), zmiana rozmiaru przyciskiem`() {
        assertEquals(listOf(size(1, 1), size(2, 1), size(2, 2)), allowedUpTo4x4(TileKind.EMODUL))
        assertFalse(TileKind.EMODUL.freeSize)
        assertTrue(TileKind.EMODUL.resizable)
        assertEquals(TileSize.M, TileKind.EMODUL.defaultSize)
        assertEquals(listOf(TileSize.S, TileSize.M, TileSize.L), TileKind.EMODUL.presets)
        assertEquals(TileSize.M, TileKind.EMODUL.nextPreset(TileSize.S))
        assertEquals(TileSize.L, TileKind.EMODUL.nextPreset(TileSize.M))
        assertEquals(TileSize.S, TileKind.EMODUL.nextPreset(TileSize.L))
        assertEquals(TileSize.M, TileKind.EMODUL.coerce(size(4, 1)))
        assertTrue(TileKind.EMODUL.allows(TileKind.EMODUL.coerce(size(6, 6))))
        assertFalse(TileKind.EMODUL.configurable)
    }

    @Test
    fun `ustawienia kafelka (przycisk zebatki w edycji) ma tylko pogoda`() {
        assertEquals(listOf(TileKind.WEATHER), TileKind.entries.filter { it.configurable })
        assertTrue(TileKind.WEATHER.configurable)
        assertTrue(TileId.Weather.kind.configurable)
        assertFalse(TileKind.AGENDA.configurable || TileKind.ZONE.configurable || TileKind.SWITCH.configurable || TileKind.SHUTTER.configurable)
    }

    private fun allowedUpTo4x4(kind: TileKind) =
        (1..4).flatMap { rows -> (1..4).map { cols -> size(cols, rows) } }.filter(kind::allows).sortedBy { it.cols * 10 + it.rows }

    // Przyciąganie (przeciąganie rogu)

    @Test
    fun `przyciaganie dowolnego rozmiaru - zaokraglenie i obciecie do zakresu oraz liczby kolumn`() {
        val agenda = TileKind.AGENDA
        assertEquals(size(2, 4), agenda.nearest(2.4f, 3.6f, columns = 8))
        assertEquals(size(3, 4), agenda.nearest(2.6f, 4.4f, columns = 8))
        assertEquals(size(2, 2), agenda.nearest(0.3f, 0.2f, columns = 8)) // nie mniej niż 2×2
        assertEquals(size(6, 8), agenda.nearest(7.8f, 11f, columns = 6)) // szerokość do siatki, wysokość do 8
        assertEquals(size(2, 2), agenda.nearest(5f, 2f, columns = 2))
        assertEquals(size(1, 1), TileKind.WEATHER.nearest(0.2f, 0.4f, columns = 8))
        assertEquals(size(4, 2), TileKind.WEATHER.nearest(4.2f, 6f, columns = 8)) // wysokość do 2 wierszy
    }

    @Test
    fun `przyciaganie rozmiaru z listy - najblizszy dozwolony`() {
        val zone = TileKind.ZONE
        assertEquals(size(2, 1), zone.nearest(2.2f, 1.4f))
        assertEquals(size(2, 2), zone.nearest(2.2f, 1.6f))
        assertEquals(size(2, 2), zone.nearest(6f, 5f))
        assertEquals(size(1, 1), zone.nearest(0.5f, 0.5f))
        assertEquals(size(1, 1), zone.nearest(1.4f, 1f))
        assertEquals(size(1, 1), zone.nearest(1.5f, 1f)) // remis – wcześniejszy na liście
        val switch = TileKind.SWITCH
        assertEquals(size(1, 1), switch.nearest(1.4f, 1.9f))
        assertEquals(size(1, 1), switch.nearest(2f, 1f))
        assertEquals(size(2, 2), TileKind.SHUTTER.nearest(1f, 4f))
    }

    @Test
    fun `migracja rozmiaru - dozwolony zostaje, inny staje sie najblizszym dozwolonym`() {
        assertEquals(size(2, 4), TileKind.AGENDA.coerce(size(2, 4)))
        assertEquals(size(2, 2), TileKind.AGENDA.coerce(TileSize.S))
        assertEquals(size(2, 2), TileKind.AGENDA.coerce(TileSize.M))
        assertEquals(size(8, 8), TileKind.AGENDA.coerce(size(12, 20)))
        assertEquals(size(2, 2), TileKind.WEATHER.coerce(size(2, 4))) // dawna pogoda 2×4 -> 2×2
        assertEquals(size(6, 2), TileKind.WEATHER.coerce(size(6, 4)))
        assertEquals(size(2, 2), TileKind.ZONE.coerce(TileSize.XL))
        assertEquals(size(1, 1), TileKind.ZONE.coerce(TileSize.S))
        assertEquals(size(1, 1), TileKind.SWITCH.coerce(TileSize.XL))
        assertEquals(size(1, 1), TileKind.SWITCH.coerce(TileSize.M))
        assertEquals(size(2, 2), TileKind.SHUTTER.coerce(TileSize.L))
        assertEquals(size(1, 1), TileKind.SHUTTER.coerce(TileSize.S))
        assertEquals(size(2, 2), TileKind.SHUTTER.coerce(TileSize.XL))
    }

    // Przycisk „rozmiar”

    @Test
    fun `przycisk rozmiar - kolejny z listy, pomija szersze niz siatka, spoza listy przechodzi na wiekszy`() {
        val agenda = TileKind.AGENDA // lista: 2×2, 4×2, 2×4, 4×4
        assertEquals(size(4, 2), agenda.nextPreset(size(2, 2)))
        assertEquals(size(2, 2), agenda.nextPreset(size(4, 4)))
        assertEquals(size(2, 4), agenda.nextPreset(size(2, 2), columns = 3)) // 4×2 i 4×4 się nie mieszczą
        assertEquals(size(2, 2), agenda.nextPreset(size(2, 4), columns = 3))
        assertEquals(size(4, 2), agenda.nextPreset(size(3, 2))) // 6 komórek -> pierwszy większy na liście: 4×2 (8)
        assertEquals(size(2, 2), agenda.nextPreset(size(6, 6))) // nic większego -> pierwszy
        assertEquals(size(1, 1), TileKind.ZONE.nextPreset(size(2, 2)))
        assertNull(TileKind.SWITCH.nextPreset(size(1, 1)))
        assertEquals(size(2, 2), TileKind.SHUTTER.nextPreset(size(2, 1)))
    }

    // Zapis v4 i migracje

    @Test
    fun `przycisk rozmiar dla strefy i rolety cyklicznie S, M, L, S`() {
        listOf(TileKind.ZONE, TileKind.SHUTTER).forEach { kind ->
            assertEquals(size(2, 1), kind.nextPreset(TileSize.S), "$kind")
            assertEquals(size(2, 2), kind.nextPreset(TileSize.M), "$kind")
            assertEquals(TileSize.S, kind.nextPreset(TileSize.L), "$kind")
        }
    }

    @Test
    fun `zapisana pogoda wyzsza niz 2 wiersze wraca jako 2 wiersze`() {
        assertEquals(TileSize(6, 2), DashboardLayout.decode("v4;+ w 6x4;+ a 2x4")?.size(TileId.Weather))
        assertEquals(TileSize(2, 2), DashboardLayout.decode("v4;+ w 2x3;+ a 2x4")?.size(TileId.Weather))
        // niższa i dozwolona zostaje bez zmian, a kalendarz nie jest ruszany
        val kept = DashboardLayout.decode("v4;+ w 3x2;+ a 2x4")
        assertEquals(TileSize(3, 2), kept?.size(TileId.Weather))
        assertEquals(TileSize(2, 4), kept?.size(TileId.Agenda))
        // także ukryty kafelek pogody
        val hidden = DashboardLayout.decode("v4;+ a 2x4;- w 4x4")
        assertEquals(TileSize(4, 2), hidden?.hidden?.single { it.id == TileId.Weather }?.size)
    }

    @Test
    fun `zapis v4 z dowolnymi rozmiarami - w obie strony`() {
        val layout = DashboardLayout(
            tiles = listOf(
                TileEntry(TileId.Weather, size(3, 1)), TileEntry(TileId.Agenda, size(4, 3)),
                TileEntry(TileId.Zone(1, 0), size(2, 1)), TileEntry(TileId.Switch(1, 2), size(1, 1)),
            ),
            hidden = listOf(TileEntry(TileId.Shutter(1, 0), size(2, 1))),
        )
        val text = layout.encode()
        assertEquals("v4;+ w 3x1;+ a 4x3;+ z:1:0 2x1;+ s:1:2 1x1;- r:1:0 2x1", text)
        assertEquals(StoredLayout(layout, alarmMigrationPending = false), DashboardLayout.load(text))
    }

    @Test
    fun `odczyt v4 - przelaczniki 2x1 wracaja do 1x1, a strefa 1x1 zostaje`() {
        val decoded = checkNotNull(DashboardLayout.decode("v4;+ s:1:5 2x1;- s:1:6 2x1;+ z:1:0 1x1;+ r:1:1 4x2"))
        assertEquals(TileSize.S, decoded.size(TileId.Switch(1, 5)))
        assertEquals(TileSize.S, decoded.hidden.single().size)
        assertEquals(TileSize.S, decoded.size(TileId.Zone(1, 0)))
        assertEquals(TileSize.L, decoded.size(TileId.Shutter(1, 1)))
    }

    @Test
    fun `odczyt v4 naprawia niedozwolone i nieczytelne rozmiary`() {
        val decoded = DashboardLayout.decode("v4;+ a 1x9;+ w 12x7;+ z:1:0 4x4;+ s:1:1 7;+ r:1:0 2x2")
        assertEquals(
            DashboardLayout(
                listOf(
                    TileEntry(TileId.Agenda, size(2, 8)), TileEntry(TileId.Weather, size(8, 2)),
                    TileEntry(TileId.Zone(1, 0), size(2, 2)), TileEntry(TileId.Switch(1, 1), TileSize.S), // nieczytelny -> domyślny
                    TileEntry(TileId.Shutter(1, 0), size(2, 2)),
                ),
            ),
            decoded,
        )
    }

    @Test
    fun `migracja v3 - kolumna c staje sie 2x4, roleta L zostaje 2x2, przelacznik 2x1 wraca 1x1, reszta wg dawnych wymiarow`() {
        val stored = checkNotNull(DashboardLayout.load("v3;+ w m;+ a c;+ z:1:0 l;+ z:1:1 m;+ s:1:0 s;+ s:1:1 m;+ r:1:0 l;- r:1:1 m;- a2 xl"))
        assertFalse(stored.alarmMigrationPending)
        assertEquals(
            DashboardLayout(
                tiles = listOf(
                    TileEntry(TileId.Weather, size(2, 1)), TileEntry(TileId.Agenda, size(2, 4)),
                    TileEntry(TileId.Zone(1, 0), size(2, 2)), TileEntry(TileId.Zone(1, 1), size(2, 1)),
                    TileEntry(TileId.Switch(1, 0), size(1, 1)), TileEntry(TileId.Switch(1, 1), size(1, 1)),
                    TileEntry(TileId.Shutter(1, 0), size(2, 2)),
                ),
                hidden = listOf(TileEntry(TileId.Shutter(1, 1), size(2, 1))),
            ),
            stored.layout,
        )
        // Pierwsza zmiana zapisuje już v4.
        assertTrue(stored.layout.encode().startsWith("v4;+ w 2x1;+ a 2x4;"))
    }

    @Test
    fun `migracja v2 - rozmiary jak w v3, a alarmy nadal czekaja na jednorazowe ukrycie`() {
        val stored = checkNotNull(DashboardLayout.load("v2;+ w l;+ a xl;+ r:1:0 l;+ s:1:0 s"))
        assertTrue(stored.alarmMigrationPending)
        assertEquals(
            listOf(
                TileEntry(TileId.Weather, size(2, 2)), TileEntry(TileId.Agenda, size(4, 2)),
                TileEntry(TileId.Shutter(1, 0), size(2, 2)), TileEntry(TileId.Switch(1, 0), size(1, 1)),
            ),
            stored.layout.tiles,
        )
    }

    @Test
    fun `migracja v1 - sama lista kolejnosci dostaje rozmiary domyslne`() {
        val stored = checkNotNull(DashboardLayout.load("a,r:1:0,w"))
        assertTrue(stored.alarmMigrationPending)
        assertEquals(
            listOf(TileEntry(TileId.Agenda, size(4, 2)), TileEntry(TileId.Shutter(1, 0), size(2, 1)), TileEntry(TileId.Weather, size(2, 1))),
            stored.layout.tiles,
        )
    }
}
