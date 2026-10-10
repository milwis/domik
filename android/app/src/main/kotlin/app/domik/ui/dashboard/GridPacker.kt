package app.domik.ui.dashboard

/** Kafelek do ułożenia w siatce: szerokość [cols] i wysokość [rows] w komórkach. */
data class PackItem(val cols: Int, val rows: Int)

/** Położenie kafelka w siatce: komórka lewa-górna ([col], [row]) i rozmiar w komórkach. */
data class GridRect(val col: Int, val row: Int, val cols: Int, val rows: Int) {
    val bottom: Int get() = row + rows
}

/**
 * Układanie kafelków w siatce „pierwsze wolne miejsce” (jak ekran startowy): kolejno, każdy kafelek trafia na
 * pierwsze miejsce, w którym się mieści, przeszukując wiersze od góry, a w wierszu kolumny od lewej. Mniejsze
 * kafelki wypełniają więc luki zostawione przez większe. Rozmiary są dowolne (cols × rows); kafelek szerszy niż
 * [columns] jest zwężany do [columns] (np. kalendarz 6×3 zapisany na tablecie poziomo, oglądany na telefonie).
 *
 * **Kolumna:** wysoki kafelek (więcej wierszy niż kolumn, np. kalendarz 2×4) po kafelku tej samej szerokości staje
 * bezpośrednio pod nim, jeśli jest tam miejsce – tak pogoda 2×1 i kalendarz 2×4 tworzą lewą kolumnę tabletu
 * (samo „pierwsze wolne miejsce” postawiłoby kalendarz obok pogody, w tym samym wierszu).
 */
object GridPacker {
    fun pack(items: List<PackItem>, columns: Int): List<GridRect> {
        require(columns >= 1) { "columns must be at least 1" }
        val occupied = ArrayList<BooleanArray>()

        fun free(col: Int, row: Int, cols: Int, rows: Int): Boolean {
            for (r in row until minOf(row + rows, occupied.size)) {
                for (c in col until col + cols) if (occupied[r][c]) return false
            }
            return true
        }

        fun take(rect: GridRect) {
            while (occupied.size < rect.bottom) occupied.add(BooleanArray(columns))
            for (r in rect.row until rect.bottom) for (c in rect.col until rect.col + rect.cols) occupied[r][c] = true
        }

        var previous: GridRect? = null
        return items.map { item ->
            val cols = item.cols.coerceIn(1, columns)
            val rows = item.rows.coerceAtLeast(1)
            var row = 0
            val above = previous
            var placed: GridRect? = if (rows > cols && above != null && above.cols == cols && free(above.col, above.bottom, cols, rows)) {
                GridRect(above.col, above.bottom, cols, rows)
            } else {
                null
            }
            while (placed == null) {
                for (col in 0..columns - cols) {
                    if (free(col, row, cols, rows)) {
                        placed = GridRect(col, row, cols, rows)
                        break
                    }
                }
                row++
            }
            take(placed)
            previous = placed
            placed
        }
    }

    /** Liczba wierszy zajętych przez [rects] (0 dla pustej listy). */
    fun rowCount(rects: List<GridRect>): Int = rects.maxOfOrNull { it.bottom } ?: 0
}
