package app.domik.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.domik.R

/** Komunikaty kafelka pogody bez prognozy. */
enum class WeatherMessage(val titleRes: Int, val hintRes: Int?) {
    /** Nie wybrano miejscowości – dotknięcie otwiera menu z wyszukiwarką. */
    ChoosePlace(R.string.weather_choose_place, R.string.weather_choose_place_hint),

    /** Pierwsze pobieranie. */
    Loading(R.string.weather_loading, null),

    /** Nie udało się pobrać (brak internetu) i nie ma starszych danych – dotknięcie ponawia. */
    Failed(R.string.weather_failed, R.string.weather_failed_hint),
}

/**
 * Kafelek pogody bez prognozy: karta „Wybierz miejscowość”, wskaźnik wczytywania albo błąd pobierania. Wariant
 * (mały / średni / duży – [WeatherLayout.messageVariant]) dobiera się do **zmierzonego** rozmiaru kafelka, tak jak
 * w [WeatherTile], więc po wczytaniu pulpit się nie przeskakuje. [onClick] (null = kafelek nieinteraktywny) obejmuje
 * cały kafelek.
 */
@Composable
fun WeatherMessageTile(
    message: WeatherMessage,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val title = stringResource(message.titleRes)
    val hint = message.hintRes?.let { stringResource(it) }
    // Wysokość komórki decyduje o paddingu i czcionkach (jak w LightTile), żeby tytuł M nigdy nie był ucinany.
    BoxWithConstraints(modifier) {
        val density = TileDensity.forHeight(maxHeight)
        val variant = WeatherLayout.messageVariant(maxWidth.value, maxHeight.value)
        val textWidth = maxWidth - density.padding * 2 - MEDIUM_ICON_SIZE - MEDIUM_ICON_GAP
        val innerHeight = maxHeight - density.padding * 2
        TileCard(
            state = TileState.Neutral,
            modifier = Modifier.fillMaxSize(),
            onClick = onClick,
            role = if (onClick != null) Role.Button else null,
            contentDescription = if (hint != null) "$title. $hint" else title,
            contentPadding = PaddingValues(density.padding),
        ) { tile ->
            when (variant) {
                WeatherMessageVariant.Small -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                    MessageIcon(message, tile, 36)
                    Text(
                        text = title,
                        style = density.bodyStyle,
                        color = tile.title,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                WeatherMessageVariant.Medium -> MediumMessage(
                    message, title, hint, tile, density,
                    textWidth = textWidth,
                    innerHeight = innerHeight,
                )
                WeatherMessageVariant.Large -> Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                ) {
                    MessageIcon(message, tile, 56)
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = tile.title,
                        textAlign = TextAlign.Center,
                    )
                    if (hint != null) {
                        Text(
                            text = hint,
                            style = MaterialTheme.typography.bodyLarge,
                            color = tile.subtitle,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

private val MEDIUM_ICON_SIZE = 40.dp
private val MEDIUM_ICON_GAP = 14.dp

/**
 * Karta M: ikona, **tytuł do dwóch linii** (nigdy ucinany – „Wybierz miejscowość” w wąskiej komórce zawija się) i pod nim
 * podpowiedź, której linie (0–3) dobiera zapas wysokości po tytule: najpierw liczymy, ile linii zajmie tytuł
 * przy tej szerokości, potem wypełniamy resztę wysokości podpowiedzią.
 */
@Composable
private fun MediumMessage(
    message: WeatherMessage,
    title: String,
    hint: String?,
    tile: TileColors,
    density: TileDensity,
    textWidth: Dp,
    innerHeight: Dp,
) {
    val titleStyle = if (density == TileDensity.Tight) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium
    val titleLines = rememberLineCount(title, titleStyle, textWidth, maxLines = 2)
    val hintStyle = density.bodyStyle
    val hintLines = with(LocalDensity.current) {
        ((innerHeight - titleStyle.lineHeight.toDp() * titleLines) / hintStyle.lineHeight.toDp()).toInt().coerceIn(0, 3)
    }
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        MessageIcon(message, tile, MEDIUM_ICON_SIZE.value.toInt())
        Spacer(Modifier.width(MEDIUM_ICON_GAP))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                text = title,
                style = titleStyle,
                color = tile.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (hint != null && hintLines > 0) {
                Text(
                    text = hint,
                    style = hintStyle,
                    color = tile.subtitle,
                    maxLines = hintLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MessageIcon(message: WeatherMessage, tile: TileColors, sizeDp: Int) {
    if (message == WeatherMessage.Loading) {
        CircularProgressIndicator(Modifier.size((sizeDp * 0.8f).dp), strokeWidth = 3.dp, color = tile.icon)
    } else {
        Image(
            painter = painterResource(if (message == WeatherMessage.Failed) R.drawable.ic_cloud else R.drawable.ic_cloud_sun),
            contentDescription = null,
            colorFilter = ColorFilter.tint(tile.icon),
            modifier = Modifier.size(sizeDp.dp),
        )
    }
}
