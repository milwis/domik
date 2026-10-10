package app.domik.ui.dashboard

import kotlin.math.floor

/**
 * Wymiary siatki pulpitu w jednym miejscu. Wszystko w dp (liczby zmiennoprzecinkowe),
 * czysty Kotlin – patrz `GridSpecTest`.
 */
object GridSpec {
    /** Tablet poziomo. */
    const val COLUMNS_TABLET_LANDSCAPE = 8

    /** Tablet pionowo. */
    const val COLUMNS_TABLET_PORTRAIT = 6

    /** Telefon. */
    const val COLUMNS_PHONE = 4

    /** Najmniej kolumn, jakie siatka dopuszcza. */
    const val MIN_COLUMNS = 2

    /** Od tej szerokości ekran to tablet (dotychczasowy próg układu tabletowego). */
    const val TABLET_MIN_WIDTH_DP = 600f

    /** Od tej szerokości (i w poziomie) ekran dostaje siatkę poziomą (dotychczasowy próg „wide”). */
    const val LANDSCAPE_MIN_WIDTH_DP = 700f

    /** Odstęp między kafelkami: tablet / telefon. */
    const val GAP_TABLET_DP = 20f
    const val GAP_PHONE_DP = 16f

    /**
     * Najmniejsza komórka, przy której kafelek S (ikona + dwie linie tekstu) jeszcze się mieści. Gdy ekran jest
     * wąski (np. telefon), liczba kolumn jest zmniejszana, aż komórki mają co najmniej tyle.
     */
    const val MIN_CELL_DP = 104f

    /** Najwyżej o tyle wiersze mogą być wyższe od szerokości komórki, gdy rozciągamy je do wypełnienia ekranu. */
    const val MAX_ROW_STRETCH = 1.15f

    fun gap(widthDp: Float): Float = if (widthDp >= TABLET_MIN_WIDTH_DP) GAP_TABLET_DP else GAP_PHONE_DP

    /**
     * Liczba kolumn dla okna [widthDp] × [heightDp]: tablet poziomo 8, tablet pionowo 6, telefon 4 – ale nie
     * więcej, niż się mieści przy komórkach nie mniejszych niż [MIN_CELL_DP] (typowy telefon pionowo: 2).
     * [gapDp] i [sidePaddingDp] jak w [geometry].
     */
    fun columns(widthDp: Float, heightDp: Float, gapDp: Float = gap(widthDp), sidePaddingDp: Float = gapDp): Int {
        val nominal = when {
            widthDp >= LANDSCAPE_MIN_WIDTH_DP && widthDp > heightDp -> COLUMNS_TABLET_LANDSCAPE
            widthDp >= TABLET_MIN_WIDTH_DP -> COLUMNS_TABLET_PORTRAIT
            else -> COLUMNS_PHONE
        }
        val fitting = floor((widthDp - 2 * sidePaddingDp + gapDp) / (MIN_CELL_DP + gapDp)).toInt()
        return minOf(nominal, fitting).coerceAtLeast(MIN_COLUMNS)
    }

    /**
     * Geometria siatki: [columns] kolumn w szerokości [availableWidth] (bez marginesów), widoczny pulpit wysoki na
     * [viewportHeight]. Komórka jest kwadratowa; jeśli wiersze mieszczące się na ekranie wypełniają go z zapasem
     * do [MAX_ROW_STRETCH], wiersze są rozciągane tak, by pełne wiersze sięgały dokładnie do dołu (np. pogoda 2×1
     * nad kalendarzem 2×4 na tablecie poziomo).
     */
    fun geometry(availableWidth: Float, viewportHeight: Float, columns: Int, gap: Float): GridGeometry {
        val cell = (availableWidth - (columns - 1) * gap) / columns
        val rows = floor((viewportHeight + gap) / (cell + gap)).toInt().coerceAtLeast(1)
        val exact = (viewportHeight - (rows - 1) * gap) / rows
        val rowHeight = if (exact >= cell && exact <= cell * MAX_ROW_STRETCH) exact else cell
        return GridGeometry(columns, cell, rowHeight, gap, rows)
    }
}

/** Wymiary komórek siatki (dp): [viewportRows] = ile wierszy mieści się na widocznym pulpicie. */
data class GridGeometry(
    val columns: Int,
    val cellWidth: Float,
    val rowHeight: Float,
    val gap: Float,
    val viewportRows: Int,
) {
    fun x(col: Int): Float = col * (cellWidth + gap)

    fun y(row: Int): Float = row * (rowHeight + gap)

    fun width(cols: Int): Float = cols * cellWidth + (cols - 1) * gap

    fun height(rows: Int): Float = rows * rowHeight + (rows - 1) * gap
}
