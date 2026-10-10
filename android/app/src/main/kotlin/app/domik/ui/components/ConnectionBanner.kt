package app.domik.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.domik.R
import app.domik.ui.theme.DomikTheme

/**
 * Cienki pasek u góry ekranu – pokazywany **tylko gdy jest problem** z połączeniem;
 * gdy wszystko działa, nie zajmuje miejsca. Kolory z tokenów `error` / `onError`.
 *
 * @param visible czy pokazać pasek
 * @param message tekst; domyślnie „Brak połączenia ze sterownikiem – ponawiam…”
 * @param actionLabel podpis przycisku po prawej (np. „Ponów”), gdy aplikacja przestała próbować sama
 * @param onAction akcja przycisku; przycisk widać tylko przy podanych [actionLabel] i [onAction]
 */
@Composable
fun ConnectionBanner(
    visible: Boolean,
    modifier: Modifier = Modifier,
    message: String = stringResource(R.string.connection_lost),
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = DomikTheme.colors
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.error)
                .padding(horizontal = 20.dp, vertical = 10.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_wifi_off),
                contentDescription = null,
                colorFilter = ColorFilter.tint(colors.onError),
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.labelLarge,
                color = colors.onError,
            )
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) {
                    Text(text = actionLabel, style = MaterialTheme.typography.labelLarge, color = colors.onError)
                }
            }
        }
    }
}
