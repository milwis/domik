package app.domik.ui.components

import android.widget.Toast
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import app.domik.R
import app.domik.data.ProjectLinks
import app.domik.data.findLinkRanges
import app.domik.platform.openWebUrl
import app.domik.ui.theme.DomikTheme

/**
 * Funkcja otwierająca adres https w przeglądarce. Gdy się nie uda (brak przeglądarki, system blokuje start innej
 * aplikacji, np. przy przypiętym Domiku), pokazuje krótki komunikat z adresem, który można przepisać.
 */
@Composable
fun rememberLinkOpener(): (String) -> Unit {
    val context = LocalContext.current
    val resources = LocalResources.current
    return remember(context, resources) {
        { url ->
            if (!openWebUrl(context, url)) {
                val message = resources.getString(R.string.link_open_failed, ProjectLinks.displayAddress(url))
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }
}

/**
 * Tekst, w którym adresy z listy [links] (rozpoznawane po postaci wyświetlanej, np. `github.com/milwis/domik/issues`)
 * są klikalne – tekst się nie zmienia, adresy tylko się wyróżniają (pogrubienie i podkreślenie, kolor jak reszta
 * tekstu, żeby kontrast był taki sam). Kliknięcie otwiera pełny adres https przez [rememberLinkOpener].
 */
@Composable
fun LinkedText(
    text: String,
    links: List<String>,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = DomikTheme.colors.textPrimary,
) {
    val open = rememberLinkOpener()
    val linkStyles = TextLinkStyles(SpanStyle(fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline))
    val annotated = remember(text, links, linkStyles, open) {
        val listener = LinkInteractionListener { link -> (link as? LinkAnnotation.Url)?.let { open(it.url) } }
        buildAnnotatedString {
            append(text)
            for (range in findLinkRanges(text, links)) {
                addLink(LinkAnnotation.Url(range.url, linkStyles, listener), range.start, range.endExclusive)
            }
        }
    }
    Text(text = annotated, style = style, color = color, modifier = modifier)
}
