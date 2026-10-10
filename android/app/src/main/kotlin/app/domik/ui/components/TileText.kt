package app.domik.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Gęstość układu małych kafelków (światło S/M, ogrzewanie M, komunikaty pogody) wg **wysokości komórki** –
 * siatka daje komórkę od 104 dp (najwęższy telefon, tablet pionowo w 6 kolumnach) do ok. 160 dp (tablet poziomo),
 * a w każdej z nich ikona, nazwa (do dwóch linii) i pomieszczenie muszą się zmieścić bez ucinania i bez nachodzenia.
 * Budżet wysokości (wnętrze = komórka − 2 × [padding]) dla kafelka S z nazwą w dwóch liniach:
 * ikona + 2 × linia nazwy + linia pomieszczenia ≤ wnętrze (patrz [fitNameLines]).
 *
 * @property padding wewnętrzny padding kafelka
 * @property icon rozmiar ikony urządzenia
 */
internal enum class TileDensity(val padding: Dp, val icon: Dp) {
    /** Od [ROOMY_MIN_HEIGHT]: padding 20 dp, nazwa 18 sp. */
    Roomy(20.dp, 36.dp),

    /** Od [REGULAR_MIN_HEIGHT] (np. 125 dp – tablet w 4 kolumnach): padding 14 dp, nazwa 16 sp. */
    Regular(14.dp, 32.dp),

    /** Najmniejsze komórki (104–124 dp): padding 12 dp, nazwa 14 sp. */
    Tight(12.dp, 28.dp),
    ;

    /** Nazwa kafelka (do dwóch linii). */
    val nameStyle: TextStyle
        @Composable get() = when (this) {
            Roomy -> MaterialTheme.typography.titleMedium
            Regular -> MaterialTheme.typography.titleSmall
            Tight -> MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp, lineHeight = 18.sp)
        }

    /** Pomieszczenie / opis (jedna linia). */
    val roomStyle: TextStyle
        @Composable get() = when (this) {
            Roomy -> MaterialTheme.typography.bodyMedium
            Regular -> MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 18.sp)
            Tight -> MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp)
        }

    /** Zwykły tekst w kafelku z komunikatem (w ostatniej gęstości nieco mniejszy). */
    val bodyStyle: TextStyle
        @Composable get() = when (this) {
            Roomy, Regular -> MaterialTheme.typography.bodyMedium
            Tight -> MaterialTheme.typography.bodySmall
        }

    companion object {
        /** Od tej wysokości komórki: [Roomy]. */
        val ROOMY_MIN_HEIGHT = 150.dp

        /** Od tej wysokości komórki: [Regular]. */
        val REGULAR_MIN_HEIGHT = 124.dp

        fun forHeight(height: Dp): TileDensity = when {
            height >= ROOMY_MIN_HEIGHT -> Roomy
            height >= REGULAR_MIN_HEIGHT -> Regular
            else -> Tight
        }
    }
}

/**
 * Na ile linii można rozwinąć nazwę (2, jeśli się mieści, inaczej 1): [fixed] to wysokość reszty zawartości
 * kolumny (np. ikona nad tekstem), [available] – całe wnętrze kafelka. Wysokości linii liczone w bieżącej
 * skali czcionki, więc przy dużej czcionce systemowej nazwa wraca do jednej linii zamiast nachodzić na ikonę.
 */
@Composable
internal fun fitNameLines(available: Dp, fixed: Dp, nameStyle: TextStyle, roomStyle: TextStyle?, maxLines: Int = 2): Int {
    val density = LocalDensity.current
    return with(density) {
        val name = nameStyle.lineHeight.toDp()
        val room = roomStyle?.lineHeight?.toDp() ?: 0.dp
        (maxLines downTo 2).firstOrNull { lines -> fixed + name * lines + room <= available } ?: 1
    }
}

/**
 * Nazwa (do [nameLines] linii) i pod nią pomieszczenie (jedna linia; pomijane, gdy puste) – wspólne dla kafelków
 * światła i ogrzewania. Kolory ze stanu kafelka ([tile]), style z [density].
 */
@Composable
internal fun TileNameAndRoom(name: String, room: String, tile: TileColors, density: TileDensity, nameLines: Int) {
    Column {
        Text(
            text = name,
            style = density.nameStyle,
            color = tile.title,
            maxLines = nameLines,
            overflow = TextOverflow.Ellipsis,
        )
        if (room.isNotBlank()) {
            Text(
                text = room,
                style = density.roomStyle,
                color = tile.subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Ile linii (do [maxLines]) zajmie [text] w stylu [style] przy szerokości [maxWidth] – żeby zarezerwować miejsce z góry. */
@Composable
internal fun rememberLineCount(text: String, style: TextStyle, maxWidth: Dp, maxLines: Int): Int {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val widthPx = with(density) { maxWidth.coerceAtLeast(0.dp).roundToPx() }
    return remember(text, style, widthPx, maxLines, density) {
        measurer.measure(text, style, maxLines = maxLines, constraints = Constraints(maxWidth = widthPx)).lineCount
    }
}
