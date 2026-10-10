package app.domik.ui.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.max

/** Rozmieszczenie nagłówka trybu edycji: wszystko w jednym rzędzie albo tytuł nad rzędem przycisków. */
internal enum class EditHeaderMode { SINGLE_ROW, TWO_ROWS }

/**
 * Decyzje o układzie nagłówków pulpitu. Czysty Kotlin: wszystkie wartości w tej samej jednostce (piksele albo dp),
 * patrz `HeaderLayoutTest`. Mierzymy zawartość, a nie zgadujemy po szerokości ekranu – dzięki temu działa też przy
 * powiększonej czcionce systemowej, w angielskim, w trybie dzielonego ekranu i na każdym telefonie.
 */
internal object HeaderLayout {
    /**
     * Tytuł i przyciski w jednym rzędzie tylko wtedy, gdy mieszczą się obok siebie w całości ([titleWidth] to
     * szerokość tytułu w jednej linii, [gap] – minimalny odstęp). Inaczej przyciski schodzą do drugiego rzędu, a tytuł
     * dostaje całą szerokość (nigdy nie zawija się w pionowy słupek).
     */
    fun editMode(available: Int, titleWidth: Int, actionsWidth: Int, gap: Int): EditHeaderMode =
        if (titleWidth + gap + actionsWidth <= available) EditHeaderMode.SINGLE_ROW else EditHeaderMode.TWO_ROWS

    /**
     * Czy obok tytułu mieści się plakietka (np. „Demo”). Tytuł ma pierwszeństwo: gdy nie mieszczą się razem,
     * plakietka znika, a tytuł dostaje całą szerokość (w ostateczności z wielokropkiem).
     */
    fun showBadge(available: Int, titleWidth: Int, badgeWidth: Int, gap: Int): Boolean =
        titleWidth + gap + badgeWidth <= available
}

// Wysokość „podglądu” do pytań o szerokość naturalną (tytuły są jednoliniowe, więc wartość nie ma znaczenia, ale
// musi być skończona – niektóre modyfikatory układu budują z niej ograniczenia).
private val PROBE_HEIGHT = 56.dp

/**
 * Nagłówek trybu edycji: [title] (ikona + tytuł, jedna linia), [reset] (przycisk „przywróć układ”) i [primary]
 * (przyciski „Dodaj” i „Gotowe”).
 *
 * Mieszczą się w jednym rzędzie (tablet) – tytuł z lewej, przyciski przy prawej krawędzi. Jeśli nie (telefon, duża
 * czcionka) – tytuł w pierwszym rzędzie na całą szerokość, a w drugim „reset” z lewej i „Dodaj”/„Gotowe” z prawej.
 */
@Composable
internal fun EditHeaderLayout(
    modifier: Modifier = Modifier,
    title: @Composable () -> Unit,
    reset: @Composable () -> Unit,
    primary: @Composable () -> Unit,
) {
    Layout(content = { title(); reset(); primary() }, modifier = modifier) { measurables, constraints ->
        val titleItem = measurables[0]
        val resetItem = measurables[1]
        val primaryItem = measurables[2]
        val width = constraints.maxWidth
        val gap = 8.dp.roundToPx()
        val probe = PROBE_HEIGHT.roundToPx()
        val mode = HeaderLayout.editMode(
            available = width,
            titleWidth = titleItem.maxIntrinsicWidth(probe),
            actionsWidth = resetItem.maxIntrinsicWidth(probe) + primaryItem.maxIntrinsicWidth(probe),
            gap = gap,
        )
        when (mode) {
            EditHeaderMode.SINGLE_ROW -> {
                val primaryP = primaryItem.measure(Constraints(maxWidth = width))
                val resetP = resetItem.measure(Constraints(maxWidth = width))
                val titleP = titleItem.measure(
                    Constraints(maxWidth = (width - primaryP.width - resetP.width - gap).coerceAtLeast(0)),
                )
                val height = maxOf(titleP.height, resetP.height, primaryP.height)
                layout(width, height) {
                    titleP.placeRelative(0, (height - titleP.height) / 2)
                    primaryP.placeRelative(width - primaryP.width, (height - primaryP.height) / 2)
                    resetP.placeRelative(width - primaryP.width - resetP.width, (height - resetP.height) / 2)
                }
            }
            EditHeaderMode.TWO_ROWS -> {
                val titleP = titleItem.measure(Constraints(maxWidth = width))
                val resetP = resetItem.measure(Constraints(maxWidth = width))
                val primaryP = primaryItem.measure(Constraints(maxWidth = (width - resetP.width - gap).coerceAtLeast(0)))
                val secondRow = max(resetP.height, primaryP.height)
                layout(width, titleP.height + secondRow) {
                    titleP.placeRelative(0, 0)
                    resetP.placeRelative(0, titleP.height + (secondRow - resetP.height) / 2)
                    primaryP.placeRelative(width - primaryP.width, titleP.height + (secondRow - primaryP.height) / 2)
                }
            }
        }
    }
}

/**
 * Tytuł z plakietką (np. nazwa domu i „Demo”) w jednym rzędzie, w całej oferowanej szerokości. Tytuł ma
 * pierwszeństwo: plakietka jest pokazana tylko wtedy, gdy mieści się obok niezawiniętego tytułu; inaczej
 * znika, a tytuł (jedna linia) kończy się wielokropkiem dopiero wtedy, gdy nie mieści się sam.
 */
@Composable
internal fun TitleWithBadge(
    modifier: Modifier = Modifier,
    title: @Composable () -> Unit,
    badge: @Composable (() -> Unit)? = null,
) {
    Layout(content = { title(); badge?.invoke() }, modifier = modifier) { measurables, constraints ->
        val titleItem = measurables[0]
        val badgeItem = measurables.getOrNull(1)
        val width = constraints.maxWidth
        val gap = 12.dp.roundToPx()
        val probe = PROBE_HEIGHT.roundToPx()
        val showBadge = badgeItem != null && HeaderLayout.showBadge(
            available = width,
            titleWidth = titleItem.maxIntrinsicWidth(probe),
            badgeWidth = badgeItem.maxIntrinsicWidth(probe),
            gap = gap,
        )
        val badgeP = if (showBadge) badgeItem?.measure(Constraints(maxWidth = width)) else null
        val titleP = titleItem.measure(Constraints(maxWidth = (width - (badgeP?.let { it.width + gap } ?: 0)).coerceAtLeast(0)))
        val height = max(titleP.height, badgeP?.height ?: 0)
        layout(width, height) {
            titleP.placeRelative(0, (height - titleP.height) / 2)
            badgeP?.placeRelative(titleP.width + gap, (height - badgeP.height) / 2)
        }
    }
}
