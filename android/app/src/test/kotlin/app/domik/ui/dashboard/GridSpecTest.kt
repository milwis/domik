package app.domik.ui.dashboard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GridSpecTest {
    @Test
    fun `tablet poziomo ma 8 kolumn, pionowo 6, telefon 4 - o ile komorki sa wystarczajaco duze`() {
        assertEquals(8, GridSpec.columns(widthDp = 1280f, heightDp = 800f))
        assertEquals(7, GridSpec.columns(widthDp = 960f, heightDp = 600f)) // 8 kolumn zmieściłoby się dopiero od ~1012 dp
        assertEquals(6, GridSpec.columns(widthDp = 800f, heightDp = 1280f))
        assertEquals(5, GridSpec.columns(widthDp = 700f, heightDp = 700f)) // kwadrat – nie „poziomo”; 6 komórek byłoby za małych
        assertEquals(4, GridSpec.columns(widthDp = 520f, heightDp = 360f)) // telefon poziomo: 4 kolumny po ~118 dp
    }

    @Test
    fun `waski ekran zmniejsza liczbe kolumn tak by komorka miala co najmniej minimum`() {
        assertEquals(3, GridSpec.columns(widthDp = 393f, heightDp = 851f)) // typowy telefon pionowo: 3 komórki po ~110 dp
        assertEquals(2, GridSpec.columns(widthDp = 360f, heightDp = 780f)) // wąski telefon: 3 komórki byłyby po ~99 dp
        listOf(320f, 360f, 393f, 412f, 480f, 600f, 800f, 1280f).forEach { width ->
            val gap = GridSpec.gap(width)
            val columns = GridSpec.columns(width, heightDp = 900f)
            assertTrue(columns >= GridSpec.MIN_COLUMNS)
            val geometry = GridSpec.geometry(width - 2 * gap, 700f, columns, gap)
            assertTrue(columns == GridSpec.MIN_COLUMNS || geometry.cellWidth >= GridSpec.MIN_CELL_DP - 0.01f) {
                "komórka ${geometry.cellWidth} dp przy szerokości $width dp i $columns kolumnach"
            }
        }
    }

    @Test
    fun `odstep miedzy kafelkami - tablet 20 dp, telefon 16 dp`() {
        assertEquals(20f, GridSpec.gap(800f))
        assertEquals(20f, GridSpec.gap(600f))
        assertEquals(16f, GridSpec.gap(599f))
    }

    @Test
    fun `geometria - kwadratowe komorki, szerokosc kafelka obejmuje odstepy`() {
        val g = GridSpec.geometry(availableWidth = 1000f, viewportHeight = 330f, columns = 8, gap = 20f)
        assertEquals((1000f - 7 * 20f) / 8f, g.cellWidth, 0.001f)
        assertEquals(2 * g.cellWidth + 20f, g.width(2), 0.001f)
        assertEquals(g.cellWidth, g.width(1), 0.001f)
        assertEquals(3 * (g.cellWidth + 20f), g.x(3), 0.001f)
    }

    @Test
    fun `wiersze sa rozciagane tak by kolumna kalendarza siegala dokladnie do dolu`() {
        // komórka 100 dp, odstęp 20: 3 wiersze to 340 dp; widoczne 360 -> rozciągnięcie do 106,67 (≤ 1,15 × 100)
        val g = GridSpec.geometry(availableWidth = 8 * 100f + 7 * 20f, viewportHeight = 360f, columns = 8, gap = 20f)
        assertEquals(3, g.viewportRows)
        assertEquals(360f, g.height(g.viewportRows), 0.01f)
        assertTrue(g.rowHeight >= g.cellWidth)
    }

    @Test
    fun `zbyt duza roznica nie rozciaga wierszy ponad limit - wtedy zostaja kwadratowe`() {
        // Zostałoby 3 × 100 + 2 × 20 = 340 dp z 399 dp → rozciągnięcie do 119,7 (> 1,15 × 100): komórki zostają kwadratowe.
        val g = GridSpec.geometry(availableWidth = 8 * 100f + 7 * 20f, viewportHeight = 399f, columns = 8, gap = 20f)
        assertEquals(g.cellWidth, g.rowHeight, 0.001f)
        assertEquals(3, g.viewportRows)
    }

    @Test
    fun `co najmniej jeden wiersz widoczny nawet na bardzo niskim ekranie`() {
        val g = GridSpec.geometry(availableWidth = 800f, viewportHeight = 40f, columns = 4, gap = 20f)
        assertEquals(1, g.viewportRows)
    }
}
