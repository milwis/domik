package app.domik.ui.menu

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.domik.R
import app.domik.integrations.weather.PlaceSearchState
import app.domik.integrations.weather.WeatherPlace
import app.domik.ui.theme.DomikTheme

/**
 * Co menu potrzebuje, by pokazać sekcję „Pogoda” (null w trybie demo – tam pogoda jest przykładowa i nic nie
 * łączy się z siecią). [onQueryChange] uruchamia wyszukiwanie (z przerwą w pisaniu – patrz `WeatherModel`),
 * [onSelect] zapisuje wybraną miejscowość, [onClear] ją usuwa, a [onSearchReset] czyści wyniki po zamknięciu menu.
 */
class WeatherMenu(
    val place: WeatherPlace?,
    val search: PlaceSearchState,
    val onQueryChange: (String) -> Unit,
    val onSelect: (WeatherPlace) -> Unit,
    val onClear: () -> Unit,
    val onSearchReset: () -> Unit,
)

/**
 * Sekcja „Pogoda” w menu: wybrana miejscowość, pole wyszukiwania (wyniki pojawiają się podczas pisania) i lista
 * wyników (nazwa, region, kraj). Wybór zamyka menu ([close]) – kafelek od razu zaczyna wczytywać pogodę.
 * [focusSearch] ustawia kursor w polu od razu po otwarciu (menu otwarte z karty „Wybierz miejscowość”).
 * [onOptions] (null = bez przycisku) otwiera okno „Pogoda – co pokazywać”.
 * Na dole podpis dostawcy danych – wymaganie licencji CC BY 4.0 Open-Meteo.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WeatherSection(menu: WeatherMenu, focusSearch: Boolean, close: () -> Unit, onOptions: (() -> Unit)? = null) {
    val colors = DomikTheme.colors
    var query by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val bringIntoView = remember { BringIntoViewRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    DisposableEffect(Unit) { onDispose(menu.onSearchReset) }
    // Po obrocie ekranu tekst w polu wraca, a wyniki w modelu zostały wyczyszczone – szukamy ponownie.
    LaunchedEffect(Unit) { if (query.isNotBlank()) menu.onQueryChange(query) }
    LaunchedEffect(focusSearch) {
        if (focusSearch) {
            bringIntoView.bringIntoView()
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.menu_weather), style = MaterialTheme.typography.headlineSmall)
        val place = menu.place
        if (place == null) {
            SectionText(stringResource(R.string.menu_weather_none))
        } else {
            val label = if (place.detail.isBlank()) place.name else "${place.name} (${place.detail})"
            SectionText(stringResource(R.string.menu_weather_current, label))
            OutlinedButton(onClick = { query = ""; menu.onClear() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.menu_weather_clear))
            }
        }
        SectionText(stringResource(R.string.menu_weather_hint))
        if (onOptions != null) {
            OutlinedButton(onClick = onOptions, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.menu_weather_options))
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                menu.onQueryChange(it)
            },
            label = { Text(stringResource(R.string.menu_weather_search_label)) },
            placeholder = { Text(stringResource(R.string.menu_weather_search_placeholder)) },
            trailingIcon = if (query.isNotEmpty()) {
                {
                    IconButton(onClick = { query = ""; menu.onQueryChange("") }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = stringResource(R.string.menu_weather_clear_query),
                        )
                    }
                }
            } else {
                null
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Search,
            ),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            modifier = Modifier
                .fillMaxWidth()
                .bringIntoViewRequester(bringIntoView)
                .focusRequester(focusRequester),
        )

        // Wyniki w „regionie na żywo”, żeby czytnik ekranu zapowiedział zmianę listy.
        Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
            when (val search = menu.search) {
                PlaceSearchState.Idle -> Unit
                PlaceSearchState.Searching -> Row(
                    Modifier.heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    SectionText(stringResource(R.string.menu_weather_searching))
                }
                PlaceSearchState.Failed -> SectionText(stringResource(R.string.menu_weather_search_failed))
                is PlaceSearchState.Results ->
                    if (search.places.isEmpty()) {
                        SectionText(stringResource(R.string.menu_weather_no_results))
                    } else {
                        search.places.forEach { result ->
                            PlaceRow(result) {
                                keyboard?.hide()
                                menu.onSelect(result)
                                close()
                            }
                        }
                    }
            }
        }

        Text(
            text = stringResource(R.string.menu_weather_attribution),
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
        )
    }
}

/** Sekcja „Pogoda” w trybie demo (pogoda przykładowa, bez wyboru miejscowości): tylko wybór elementów kafelka. */
@Composable
fun WeatherOptionsOnlySection(onOptions: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.menu_weather), style = MaterialTheme.typography.headlineSmall)
        SectionText(stringResource(R.string.menu_weather_demo_hint))
        OutlinedButton(onClick = onOptions, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.menu_weather_options))
        }
    }
}

@Composable
private fun SectionText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = DomikTheme.colors.textSecondary)
}

@Composable
private fun PlaceRow(place: WeatherPlace, onClick: () -> Unit) {
    val colors = DomikTheme.colors
    val description = if (place.detail.isBlank()) place.name else stringResource(R.string.menu_weather_pick, place.name, place.detail)
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .wrapContentHeight(Alignment.CenterVertically)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = place.name,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (place.detail.isNotBlank()) {
            Text(
                text = place.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
