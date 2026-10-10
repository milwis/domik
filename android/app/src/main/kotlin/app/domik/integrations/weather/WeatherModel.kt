package app.domik.integrations.weather

import app.domik.data.AppSettings
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Stan pogody dla kafelka. */
sealed interface WeatherState {
    /** Miejscowość jest wybrana, ale nikt jeszcze nie poprosił o dane. */
    data object Idle : WeatherState

    /** Nie wybrano miejscowości (brak miasta domyślnego) – kafelek pokazuje „Wybierz miejscowość”. */
    data object NoPlace : WeatherState

    /** Pierwsze wczytanie (przy odświeżaniu zostają poprzednie dane – [Ready]). */
    data object Loading : WeatherState

    /**
     * Dane dla [place] pobrane (albo odczytane z pamięci podręcznej) o [fetchedAtMillis]. Dane mogą być
     * nieaktualne – ocenia to [WeatherPolicy.isStale] względem bieżącego czasu.
     */
    data class Ready(val place: WeatherPlace, val snapshot: WeatherSnapshot, val fetchedAtMillis: Long) : WeatherState

    /** Nie udało się pobrać pogody (np. brak internetu) i nie ma żadnych starszych danych. */
    data object Failed : WeatherState
}

/** Stan wyszukiwarki miejscowości w menu. */
sealed interface PlaceSearchState {
    /** Pole puste albo za krótkie. */
    data object Idle : PlaceSearchState

    data object Searching : PlaceSearchState

    /** Pusta lista = brak wyników. */
    data class Results(val places: List<WeatherPlace>) : PlaceSearchState

    /** Nie udało się wyszukać (brak internetu, błąd dostawcy). */
    data object Failed : PlaceSearchState
}

/**
 * Pogoda dla kafelka i menu. Pobiera dane z [source] **poza wątkiem UI** ([ioDispatcher]); zapamiętuje wybraną
 * miejscowość w [AppSettings] (nazwa + współrzędne zaokrąglone do 2 miejsc), a ostatnią prognozę w pamięci i w
 * [cache]. Przy błędzie sieci zostają ostatnie dane (kafelek oznacza je jako nieaktualne).
 *
 * Odświeżanie wywołuje interfejs ([refresh]): co [WeatherPolicy.REFRESH_INTERVAL_MILLIS] na pierwszym planie i po
 * powrocie na pierwszy plan (dane starsze niż [WeatherPolicy.RESUME_MAX_AGE_MILLIS]). Dopóki nie wybrano
 * miejscowości, model **nie wysyła żadnych zapytań**.
 */
class WeatherModel(
    private val source: WeatherSource,
    private val settings: AppSettings,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val cache: WeatherCache = NoWeatherCache,
    private val clock: () -> Long = System::currentTimeMillis,
    private val searchDebounceMillis: Long = SEARCH_DEBOUNCE_MILLIS,
    private val searchLanguage: () -> String = { defaultSearchLanguage() },
) {
    private val _place = MutableStateFlow(settings.weatherPlace)
    private val _state = MutableStateFlow<WeatherState>(if (_place.value == null) WeatherState.NoPlace else WeatherState.Idle)
    private val _search = MutableStateFlow<PlaceSearchState>(PlaceSearchState.Idle)
    private var loadJob: Job? = null
    private var searchJob: Job? = null

    /** Wybrana miejscowość albo null. */
    val place: StateFlow<WeatherPlace?> = _place.asStateFlow()

    val state: StateFlow<WeatherState> = _state.asStateFlow()

    val search: StateFlow<PlaceSearchState> = _search.asStateFlow()

    /**
     * Odświeża pogodę, jeśli to potrzebne: brak danych dla bieżącej miejscowości albo dane starsze niż
     * [maxAgeMillis]. Trwające pobieranie nie jest dublowane. [force] pobiera zawsze (przycisk „Spróbuj ponownie”).
     */
    fun refresh(force: Boolean = false, maxAgeMillis: Long = WeatherPolicy.RESUME_MAX_AGE_MILLIS) {
        val place = _place.value
        if (place == null) {
            _state.value = WeatherState.NoPlace
            return
        }
        if (force) {
            loadJob?.cancel()
        } else {
            if (loadJob?.isActive == true) return
            val current = _state.value
            if (current is WeatherState.Ready && current.place == place &&
                !WeatherPolicy.needsRefresh(current.fetchedAtMillis, clock(), maxAgeMillis)
            ) {
                return
            }
        }
        loadJob = scope.launch { load(place, force, maxAgeMillis) }
    }

    /** Ponawia pobieranie niezależnie od wieku danych. */
    fun retry() = refresh(force = true)

    /** Zmiana tekstu w polu wyszukiwania: po krótkiej przerwie w pisaniu ([searchDebounceMillis]) szuka miejscowości. */
    fun onQueryChange(text: String) {
        val query = text.trim()
        searchJob?.cancel()
        if (query.length < MIN_QUERY_LENGTH) {
            _search.value = PlaceSearchState.Idle
            return
        }
        searchJob = scope.launch {
            delay(searchDebounceMillis)
            // Poprzednie wyniki zostają na ekranie do czasu nowych (lista nie migocze przy pisaniu).
            if (_search.value !is PlaceSearchState.Results) _search.value = PlaceSearchState.Searching
            _search.value = try {
                val json = withContext(ioDispatcher) { source.search(query, searchLanguage()) }
                PlaceSearchState.Results(OpenMeteoParser.parseGeocoding(json))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                PlaceSearchState.Failed
            }
        }
    }

    /** Zapamiętuje wybraną miejscowość (współrzędne zaokrąglone do 2 miejsc) i od razu pobiera jej pogodę. */
    fun selectPlace(place: WeatherPlace) {
        val rounded = place.rounded()
        settings.weatherPlace = rounded
        _place.value = rounded
        resetSearch()
        loadJob?.cancel()
        _state.value = WeatherState.Loading
        refresh(force = true)
    }

    /** Zapomina miejscowość i usuwa zapisaną prognozę – kafelek wraca do „Wybierz miejscowość”. */
    fun clearPlace() {
        settings.weatherPlace = null
        _place.value = null
        resetSearch()
        loadJob?.cancel()
        _state.value = WeatherState.NoPlace
        scope.launch(ioDispatcher) { cache.clear() }
    }

    /** Czyści wyszukiwarkę (zamknięcie menu, wybór miejscowości). */
    fun resetSearch() {
        searchJob?.cancel()
        _search.value = PlaceSearchState.Idle
    }

    private suspend fun load(place: WeatherPlace, force: Boolean, maxAgeMillis: Long) {
        var fresh = false
        val shown = _state.value
        if (shown !is WeatherState.Ready || shown.place != place) {
            // Najpierw ostatnia zapisana prognoza (np. po restarcie bez internetu), potem sieć.
            val cached = withContext(ioDispatcher) { readCache(place) }
            if (cached != null) {
                _state.value = cached
                fresh = !force && !WeatherPolicy.needsRefresh(cached.fetchedAtMillis, clock(), maxAgeMillis)
            } else {
                _state.value = WeatherState.Loading
            }
        }
        if (fresh) return
        try {
            val (snapshot, raw) = withContext(ioDispatcher) {
                val raw = source.forecast(place.latitude, place.longitude)
                OpenMeteoParser.parseForecast(raw) to raw
            }
            if (_place.value != place) return
            val now = clock()
            _state.value = WeatherState.Ready(place, snapshot, now)
            withContext(ioDispatcher) { cache.write(CachedForecast(place.latitude, place.longitude, now, raw)) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Sieć niedostępna albo zła odpowiedź: starsze dane lepsze niż pusty kafelek.
            if (_place.value == place && _state.value !is WeatherState.Ready) _state.value = WeatherState.Failed
        }
    }

    private fun readCache(place: WeatherPlace): WeatherState.Ready? {
        val entry = cache.read()?.takeIf { it.isFor(place) } ?: return null
        val snapshot = try {
            OpenMeteoParser.parseForecast(entry.raw)
        } catch (_: WeatherParseException) {
            return null
        }
        return WeatherState.Ready(place, snapshot, entry.fetchedAtMillis)
    }

    companion object {
        const val SEARCH_DEBOUNCE_MILLIS = 400L
        const val MIN_QUERY_LENGTH = 2

        /**
         * Domyślny język nazw w wynikach wyszukiwania, gdy nie podano go z zewnątrz: angielski dla angielskiego
         * ustawienia języka, w pozostałych przypadkach polski (język podstawowy aplikacji). Aplikacja podaje tu
         * własny język z zasobów (`weather_search_language`), żeby zgadzał się z językiem interfejsu.
         */
        fun defaultSearchLanguage(): String = if (Locale.getDefault().language == "en") "en" else "pl"
    }
}
