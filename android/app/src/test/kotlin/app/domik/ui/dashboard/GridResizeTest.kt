package app.domik.ui.dashboard

import app.domik.data.TileKind
import app.domik.data.TileSize
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Przeciąganie prawego-dolnego rogu kafelka: przeliczenie na komórki, przyciąganie i podgląd miejsca. */
class GridResizeTest {
    // Siatka 8 kolumn: komórka 140 dp, odstęp 20 dp (krok 160 dp).
    private val geometry = GridGeometry(columns = 8, cellWidth = 140f, rowHeight = 140f, gap = 20f, viewportRows = 4)

    @Test
    fun `dlugosc w dp na ulamek komorek`() {
        assertEquals(1f, GridResize.cells(140f, 140f, 20f), 1e-4f)
        assertEquals(2f, GridResize.cells(300f, 140f, 20f), 1e-4f) // 2 komórki + odstęp
        assertEquals(4f, GridResize.cells(geometry.height(4), 140f, 20f), 1e-4f)
        assertEquals(1.5f, GridResize.cells(220f, 140f, 20f), 1e-4f)
    }

    @Test
    fun `kalendarz przeciagniety z 2x4 do okolo 4x3 przyciaga sie do 4x3`() {
        val width = geometry.width(4) - 30f // trochę mniej niż 4 komórki
        val height = geometry.height(3) + 50f // trochę więcej niż 3 wiersze
        assertEquals(TileSize(4, 3), GridResize.snap(TileKind.AGENDA, geometry, width, height))
    }

    @Test
    fun `przyciaganie respektuje rodzaj i liczbe kolumn`() {
        // Ogrzewanie: 1×1, 2×1 albo 2×2, nawet gdy palec pokazuje 5×3.
        assertEquals(TileSize(2, 2), GridResize.snap(TileKind.ZONE, geometry, geometry.width(5), geometry.height(3)))
        assertEquals(TileSize(1, 1), GridResize.snap(TileKind.ZONE, geometry, geometry.width(1), geometry.height(1)))
        // Światło: zawsze 1×1.
        assertEquals(TileSize(1, 1), GridResize.snap(TileKind.SWITCH, geometry, geometry.width(3), geometry.height(1)))
        // Roleta: 1×1, 2×1 albo 2×2.
        assertEquals(TileSize(2, 2), GridResize.snap(TileKind.SHUTTER, geometry, geometry.width(1), geometry.height(3)))
        // Kalendarz szerszy niż siatka 6 kolumn – do 6, wysokość do 8 wierszy.
        val portrait = geometry.copy(columns = 6)
        assertEquals(TileSize(6, 8), GridResize.snap(TileKind.AGENDA, portrait, 5000f, 5000f))
        // Za mały – najmniejszy dozwolony.
        assertEquals(TileSize(2, 2), GridResize.snap(TileKind.AGENDA, geometry, 10f, 10f))
        assertEquals(TileSize(1, 1), GridResize.snap(TileKind.WEATHER, geometry, 10f, 10f))
        // Pogoda: najwyżej 2 wiersze, nawet gdy palec pokazuje 4 (szerokość dowolna).
        assertEquals(TileSize(3, 2), GridResize.snap(TileKind.WEATHER, geometry, geometry.width(3), geometry.height(4)))
        assertEquals(TileSize(5, 2), GridResize.snap(TileKind.WEATHER, geometry, geometry.width(5), 5000f))
    }

    @Test
    fun `podglad pokazuje miejsce kafelka po zmianie rozmiaru`() {
        // Pogoda 2×1, kalendarz 2×4 (pod nią), strefa 2×2 obok.
        val items = listOf(PackItem(2, 1), PackItem(2, 4), PackItem(2, 2))
        // Kalendarz 4×3: pod pogodą nie jest już „kolumną” (szerszy niż pogoda), więc pierwsze wolne miejsce obok niej.
        assertEquals(GridRect(2, 0, 4, 3), GridResize.preview(items, 1, TileSize(4, 3), columns = 8))
        // Kalendarz 2×6: dalej pod pogodą.
        assertEquals(GridRect(0, 1, 2, 6), GridResize.preview(items, 1, TileSize(2, 6), columns = 8))
        // Strefa powiększona do 2×2 -> bez zmian miejsca; kafelki dalej na liście nie mają wpływu.
        assertEquals(GridRect(2, 0, 2, 2), GridResize.preview(items, 2, TileSize(2, 2), columns = 8))
        // Szerokość zwężona do siatki.
        assertEquals(GridRect(0, 1, 3, 2), GridResize.preview(items, 1, TileSize(8, 2), columns = 3))
    }
}
