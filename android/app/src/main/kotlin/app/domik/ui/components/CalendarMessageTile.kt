package app.domik.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.domik.R
import app.domik.ui.theme.DomikTheme

/**
 * Kafelek kalendarza bez listy wydarzeń: krótki komunikat ([message]) z opcjonalnym przyciskiem ([actionLabel] +
 * [onAction]) – prośba o zgodę, ponowienie – albo wskaźnik wczytywania ([loading]). Tego samego kształtu co
 * [CalendarAgendaTile], więc pulpit się nie przeskakuje po wczytaniu.
 */
@Composable
fun CalendarMessageTile(
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    loading: Boolean = false,
) {
    val colors = DomikTheme.colors
    TileCard(state = TileState.Neutral, modifier = modifier, mergeDescendants = false) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        ) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp)
            } else {
                Image(
                    painter = painterResource(R.drawable.ic_calendar),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(colors.accent),
                    modifier = Modifier.size(36.dp),
                )
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 360.dp),
            )
            if (actionLabel != null && onAction != null) {
                Button(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}
