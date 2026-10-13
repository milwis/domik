package app.domik.ui.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.domik.R
import app.domik.data.ProjectLinks
import app.domik.integrations.weather.WeatherModel
import app.domik.integrations.weather.WeatherOptions
import app.domik.integrations.weather.WeatherPolicy
import app.domik.integrations.weather.WeatherState
import app.domik.ui.components.WeatherMessage
import app.domik.ui.components.WeatherMessageTile
import app.domik.ui.components.WeatherTile
import app.domik.ui.components.rememberLinkOpener
import app.domik.ui.time.rememberSystemZone
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.delay

/**
 * Kafelek pogody na pulpicie.
 *  - tryb demo: pogoda przykładowa ([DemoWeather]), bez internetu;
 *  - tryb sterownika: prawdziwa pogoda dla miejscowości wybranej w menu ([WeatherModel]). Bez miejscowości kafelek
 *    pokazuje kartę „Wybierz miejscowość” ([onChoosePlace] otwiera menu z wyszukiwarką); nie ma miejscowości
 *    domyślnej i bez wyboru nic nie jest wysyłane do sieci.
 *
 * Zawartość kafelka dobiera się do jego zmierzonego rozmiaru, a [options] (co użytkownik chce widzieć) pochodzą
 * z ustawień profilu – patrz `WeatherTile`.
 *
 * Atrybucja „Dane: Open-Meteo” (licencja CC BY 4.0) jest zawsze widoczna na kafelku z pogodą (także w trybie demo);
 * poza edycją klika się ją do strony dostawcy ([ProjectLinks.OPEN_METEO]).
 *
 * Odświeżanie: gdy aplikacja jest na pierwszym planie – od razu po powrocie (jeśli dane starsze niż
 * [WeatherPolicy.RESUME_MAX_AGE_MILLIS]) i potem co [WeatherPolicy.REFRESH_INTERVAL_MILLIS]. Pętla żyje tylko, gdy
 * kafelek jest na ekranie (ukryty kafelek niczego nie pobiera).
 */
@Composable
fun WeatherTileHost(
    weather: WeatherModel,
    isDemo: Boolean,
    options: WeatherOptions,
    now: LocalDateTime,
    onChoosePlace: () -> Unit,
    modifier: Modifier = Modifier,
    editing: Boolean = false,
) {
    val openLink = rememberLinkOpener()
    // Atrybucja Open-Meteo (CC BY 4.0) przy kafelku: klikalna poza trybem edycji (tam przytrzymanie przenosi kafelek).
    val onAttributionClick: (() -> Unit)? = if (editing) null else ({ openLink(ProjectLinks.OPEN_METEO) })
    val resources = LocalResources.current
    val configuration = LocalConfiguration.current
    // Wszystkie teksty z zasobów (values = polski, values-en = angielski); zmiana języka aplikacji przebudowuje je.
    val texts = remember(resources, configuration) {
        val days = resources.getStringArray(R.array.weather_weekdays)
        WeatherTileTexts(
            today = resources.getString(R.string.agenda_today),
            stale = { time -> resources.getString(R.string.weather_stale, time) },
            dayShort = { day -> days[day.ordinal] },
        )
    }

    if (isDemo) {
        val demoCity = stringResource(R.string.weather_demo_city)
        val demo = remember(now, texts, demoCity) {
            val millis = DemoWeather.nowMillis(now)
            WeatherTileMapper.map(demoCity, DemoWeather.snapshot(now), millis, millis, ZoneOffset.UTC, texts)
        }
        WeatherTile(demo, modifier, options, onAttributionClick)
        return
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(weather, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                weather.refresh()
                delay(WeatherPolicy.REFRESH_INTERVAL_MILLIS)
            }
        }
    }

    val zone by rememberSystemZone()
    val state by weather.state.collectAsStateWithLifecycle()
    when (val current = state) {
        WeatherState.NoPlace -> WeatherMessageTile(WeatherMessage.ChoosePlace, modifier, onClick = onChoosePlace)
        WeatherState.Idle, WeatherState.Loading -> WeatherMessageTile(WeatherMessage.Loading, modifier)
        WeatherState.Failed -> WeatherMessageTile(WeatherMessage.Failed, modifier, onClick = weather::retry)
        is WeatherState.Ready -> {
            val nowMillis = remember(now, zone) { now.atZone(zone).toInstant().toEpochMilli() }
            val tile = remember(current, nowMillis, zone, texts) {
                WeatherTileMapper.map(current.place.name, current.snapshot, current.fetchedAtMillis, nowMillis, zone, texts)
            }
            WeatherTile(tile, modifier, options, onAttributionClick)
        }
    }
}
