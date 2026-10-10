package app.domik.ui.dashboard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GridPackerTest {
    private val s = PackItem(1, 1)
    private val m = PackItem(2, 1)
    private val l = PackItem(2, 2)
    private val xl = PackItem(4, 2)
    private val tall = PackItem(2, 4)

    private fun rect(col: Int, row: Int, cols: Int, rows: Int) = GridRect(col, row, cols, rows)

    @Test
    fun `kafelki S ukladaja sie wierszami od lewej`() {
        val rects = GridPacker.pack(List(5) { s }, columns = 3)
        assertEquals(
            listOf(rect(0, 0, 1, 1), rect(1, 0, 1, 1), rect(2, 0, 1, 1), rect(0, 1, 1, 1), rect(1, 1, 1, 1)),
            rects,
        )
        assertEquals(2, GridPacker.rowCount(rects))
    }

    @Test
    fun `kafelek 2x2 zajmuje cztery komorki a mniejsze wypelniaja luki`() {
        // M L S S na 4 kolumnach: M (0..1,0), L (2..3,0..1), S wchodzi pod M (0,1), S (1,1).
        val rects = GridPacker.pack(listOf(m, l, s, s), columns = 4)
        assertEquals(
            listOf(rect(0, 0, 2, 1), rect(2, 0, 2, 2), rect(0, 1, 1, 1), rect(1, 1, 1, 1)),
            rects,
        )
    }

    @Test
    fun `liczba kolumn zmienia uklad - ten sam zestaw na 4 i 8 kolumnach`() {
        val items = listOf(l, l, l)
        assertEquals(listOf(rect(0, 0, 2, 2), rect(2, 0, 2, 2), rect(0, 2, 2, 2)), GridPacker.pack(items, 4))
        assertEquals(listOf(rect(0, 0, 2, 2), rect(2, 0, 2, 2), rect(4, 0, 2, 2)), GridPacker.pack(items, 8))
        assertEquals(listOf(rect(0, 0, 2, 2), rect(0, 2, 2, 2), rect(0, 4, 2, 2)), GridPacker.pack(items, 2))
    }

    @Test
    fun `kafelek szerszy niz siatka jest zwezany`() {
        assertEquals(listOf(rect(0, 0, 2, 2)), GridPacker.pack(listOf(xl), columns = 2))
        assertEquals(listOf(rect(0, 0, 1, 1)), GridPacker.pack(listOf(m), columns = 1))
        // Kalendarz 8×3 zapisany na tablecie poziomo, oglądany pionowo (6 kolumn) i na telefonie (3).
        assertEquals(listOf(rect(0, 0, 6, 3)), GridPacker.pack(listOf(PackItem(8, 3)), columns = 6))
        assertEquals(listOf(rect(0, 0, 3, 3)), GridPacker.pack(listOf(PackItem(8, 3)), columns = 3))
    }

    @Test
    fun `pogoda 2x1 nad kalendarzem 2x4 - domyslny uklad tabletu poziomo`() {
        // Pogoda w (0,0); kalendarz 2×4 pod nią (kolumna); strefy na prawo w wierszu 0, światła pod strefami.
        val rects = GridPacker.pack(listOf(m, tall, l, l, l, s, s, s, s, s, s, s), columns = 8)
        assertEquals(rect(0, 0, 2, 1), rects[0])
        assertEquals(rect(0, 1, 2, 4), rects[1])
        assertEquals(rect(2, 0, 2, 2), rects[2])
        assertEquals(rect(4, 0, 2, 2), rects[3])
        assertEquals(rect(6, 0, 2, 2), rects[4])
        assertEquals((2..7).map { rect(it, 2, 1, 1) } + rect(2, 3, 1, 1), rects.drop(5))
        assertEquals(5, GridPacker.rowCount(rects))
    }

    @Test
    fun `wysoki kafelek staje pod poprzednim tej samej szerokosci, inne kafelki nie`() {
        // 2×2 po 2×1 tej samej szerokości – nie jest wysoki, więc zwykłe pierwsze wolne miejsce (obok).
        assertEquals(rect(2, 0, 2, 2), GridPacker.pack(listOf(m, l), columns = 8)[1])
        // Wysoki po kafelku innej szerokości – zwykłe pierwsze wolne miejsce.
        assertEquals(rect(1, 0, 2, 4), GridPacker.pack(listOf(s, tall), columns = 8)[1])
        // Wysoki po 1×1 i 1×3 po 1×1: kolumna 1 szeroka.
        assertEquals(rect(0, 1, 1, 3), GridPacker.pack(listOf(s, PackItem(1, 3)), columns = 4)[1])
        // Kalendarz 2×4 po 2×2 (np. strefa) – pod nią.
        assertEquals(rect(0, 2, 2, 4), GridPacker.pack(listOf(l, tall), columns = 6)[1])
        // Pod poprzednim (2×1 w prawym górnym rogu) wolne – wysoki staje pod nim, a nie przy lewej krawędzi.
        val stacked = GridPacker.pack(listOf(l, m, PackItem(2, 3)), columns = 4)
        assertEquals(rect(2, 0, 2, 1), stacked[1])
        assertEquals(rect(2, 1, 2, 3), stacked[2])
        // Pod poprzednim zajęte (3×1 wcześniej na liście) – pierwsze wolne miejsce.
        val blocked = GridPacker.pack(listOf(m, PackItem(3, 1), m, PackItem(2, 3)), columns = 4)
        assertEquals(listOf(rect(0, 0, 2, 1), rect(0, 1, 3, 1), rect(2, 0, 2, 1), rect(0, 2, 2, 3)), blocked)
        // Szerokość inna niż poprzedniego – pierwsze wolne miejsce.
        val other = GridPacker.pack(listOf(m, PackItem(4, 1), PackItem(2, 3)), columns = 4)
        assertEquals(rect(0, 2, 2, 3), other[2])
    }

    @Test
    fun `dowolne rozmiary - nic nie nachodzi na siebie i nic nie wychodzi poza siatke`() {
        val items = listOf(
            m, tall, l, xl, s, PackItem(3, 5), s, m, PackItem(8, 1), s, l, PackItem(5, 3), PackItem(1, 4), s, PackItem(6, 8),
        )
        for (columns in 1..8) {
            val rects = GridPacker.pack(items, columns)
            val cells = HashSet<Pair<Int, Int>>()
            rects.forEachIndexed { i, r ->
                assertTrue(r.col >= 0 && r.col + r.cols <= columns, "kafelek poza siatką przy $columns kolumnach: $r")
                assertEquals(items[i].cols.coerceAtMost(columns), r.cols, "szerokość zwężona tylko do siatki")
                assertEquals(items[i].rows, r.rows, "wysokość bez zmian")
                for (c in r.col until r.col + r.cols) for (row in r.row until r.bottom) {
                    assertTrue(cells.add(c to row), "nakładanie przy $columns kolumnach: $r")
                }
            }
        }
    }

    @Test
    fun `mniejszy kafelek wpada w luke pod wysokim`() {
        // Kalendarz 2×4 po lewej, potem 4×2 obok, a S trafiają pod 4×2 (nie na koniec).
        val rects = GridPacker.pack(listOf(tall, xl, s, s), columns = 6)
        assertEquals(rect(0, 0, 2, 4), rects[0])
        assertEquals(rect(2, 0, 4, 2), rects[1])
        assertEquals(rect(2, 2, 1, 1), rects[2])
        assertEquals(rect(3, 2, 1, 1), rects[3])
    }

    @Test
    fun `pusta lista nie zajmuje wierszy`() {
        assertEquals(emptyList<GridRect>(), GridPacker.pack(emptyList(), 4))
        assertEquals(0, GridPacker.rowCount(emptyList()))
    }
}
