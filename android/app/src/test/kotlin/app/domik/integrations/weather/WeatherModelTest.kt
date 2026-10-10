@file:OptIn(ExperimentalCoroutinesApi::class)

package app.domik.integrations.weather

import app.domik.data.AppSettings
import app.domik.data.FakeCipher
import app.domik.data.InMemoryKeyValueStore
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

private class FakeWeatherSource : WeatherSource {
    var forecastJson: () -> String = { WeatherFixtures.FORECAST }
    var searchJson: () -> String = { WeatherFixtures.GEOCODING }
    var failure: Exception? = null
    var gate: CompletableDeferred<Unit>? = null
    val forecastCalls = mutableListOf<Pair<Double, Double>>()
    val searchCalls = mutableListOf<Pair<String, String>>()

    override suspend fun forecast(latitude: Double, longitude: Double): String {
        forecastCalls += latitude to longitude
        gate?.await()
        failure?.let { throw it }
        return forecastJson()
    }

    override suspend fun search(query: String, language: String): String {
        searchCalls += query to language
        gate?.await()
        failure?.let { throw it }
        return searchJson()
    }
}

private class MemoryCache(var entry: CachedForecast? = null) : WeatherCache {
    var clears = 0

    override fun read(): CachedForecast? = entry

    override fun write(entry: CachedForecast) {
        this.entry = entry
    }

    override fun clear() {
        clears++
        entry = null
    }
}

class WeatherModelTest {
    private val krakow = WeatherPlace("Kraków", "Małopolskie, Polska", 50.06143, 19.93658)
    private val gdansk = WeatherPlace("Gdańsk", "Pomorskie, Polska", 54.352, 18.6466)
    private var now = WeatherFixtures.NOW_MILLIS
    private var language = "pl"
    private val store = InMemoryKeyValueStore()

    private fun TestScope.model(source: FakeWeatherSource, cache: WeatherCache = NoWeatherCache) = WeatherModel(
        source = source,
        settings = AppSettings(store, FakeCipher()),
        scope = backgroundScope,
        ioDispatcher = StandardTestDispatcher(testScheduler),
        cache = cache,
        clock = { now },
        searchDebounceMillis = 400,
        searchLanguage = { language },
    )

    private fun WeatherModel.ready() = state.value as WeatherState.Ready

    // Miejscowość i odświeżanie

    @Test
    fun `bez miejscowosci model nic nie pobiera`() = runTest {
        val source = FakeWeatherSource()
        val model = model(source)
        model.refresh()
        model.retry()
        runCurrent()
        assertSame(WeatherState.NoPlace, model.state.value)
        assertNull(model.place.value)
        assertEquals(0, source.forecastCalls.size)
    }

    @Test
    fun `wybor miejscowosci zapisuje ja zaokraglona i pobiera pogode`() = runTest {
        val source = FakeWeatherSource()
        val model = model(source)
        model.selectPlace(krakow)
        assertSame(WeatherState.Loading, model.state.value)
        runCurrent()

        assertEquals(listOf(50.06 to 19.94), source.forecastCalls)
        assertEquals(WeatherPlace("Kraków", "Małopolskie, Polska", 50.06, 19.94), model.place.value)
        assertEquals(model.place.value, AppSettings(store, FakeCipher()).weatherPlace)
        val ready = model.ready()
        assertEquals(now, ready.fetchedAtMillis)
        assertEquals(13.4, ready.snapshot.current.temperature, 1e-9)
    }

    @Test
    fun `po ponownym uruchomieniu miejscowosc wraca z ustawien i pogoda sie pobiera`() = runTest {
        model(FakeWeatherSource()).selectPlace(krakow)
        val source = FakeWeatherSource()
        val restarted = model(source)
        assertEquals("Kraków", restarted.place.value?.name)
        assertSame(WeatherState.Idle, restarted.state.value)
        restarted.refresh()
        runCurrent()
        assertEquals(1, source.forecastCalls.size)
        assertEquals("Kraków", restarted.ready().place.name)
    }

    @Test
    fun `swiezych danych nie pobiera sie ponownie ale po 15 minutach tak`() = runTest {
        val source = FakeWeatherSource()
        val model = model(source)
        model.selectPlace(krakow)
        runCurrent()

        now += 10 * 60_000L
        model.refresh()
        runCurrent()
        assertEquals(1, source.forecastCalls.size)

        now += 6 * 60_000L
        model.refresh()
        runCurrent()
        assertEquals(2, source.forecastCalls.size)
        assertEquals(now, model.ready().fetchedAtMillis)
    }

    @Test
    fun `odswiezanie w trakcie pobierania nie dubluje zapytania`() = runTest {
        val source = FakeWeatherSource().apply { gate = CompletableDeferred() }
        val model = model(source)
        model.selectPlace(krakow)
        runCurrent()
        now += 60 * 60_000L
        model.refresh()
        model.refresh()
        runCurrent()
        assertEquals(1, source.forecastCalls.size)
        source.gate!!.complete(Unit)
        runCurrent()
        assertEquals(1, source.forecastCalls.size)
        assertInstanceOf(WeatherState.Ready::class.java, model.state.value)
    }

    @Test
    fun `wymuszone odswiezanie pobiera zawsze`() = runTest {
        val source = FakeWeatherSource()
        val model = model(source)
        model.selectPlace(krakow)
        runCurrent()
        model.retry()
        runCurrent()
        assertEquals(2, source.forecastCalls.size)
    }

    // Błędy

    @Test
    fun `brak internetu bez starszych danych to stan Failed a ponowienie dziala`() = runTest {
        val source = FakeWeatherSource().apply { failure = IOException("brak sieci") }
        val model = model(source)
        model.selectPlace(krakow)
        runCurrent()
        assertSame(WeatherState.Failed, model.state.value)

        source.failure = null
        model.retry()
        runCurrent()
        assertInstanceOf(WeatherState.Ready::class.java, model.state.value)
    }

    @Test
    fun `brak internetu zostawia ostatnie dane z ich czasem pobrania`() = runTest {
        val source = FakeWeatherSource()
        val model = model(source)
        model.selectPlace(krakow)
        runCurrent()
        val fetchedAt = model.ready().fetchedAtMillis

        source.failure = IOException("brak sieci")
        now += 2 * 3_600_000L
        model.refresh()
        runCurrent()

        assertEquals(2, source.forecastCalls.size)
        val ready = model.ready()
        assertEquals(fetchedAt, ready.fetchedAtMillis)
        // dane są przez to nieaktualne w rozumieniu zasad odświeżania (kafelek oznaczy je godziną)
        assertEquals(true, WeatherPolicy.isStale(ready.fetchedAtMillis, now))
    }

    @Test
    fun `odpowiedz ktora nie jest prognoza to Failed a nie awaria`() = runTest {
        val source = FakeWeatherSource().apply { forecastJson = { "<html>Bad Gateway</html>" } }
        val model = model(source)
        model.selectPlace(krakow)
        runCurrent()
        assertSame(WeatherState.Failed, model.state.value)
    }

    @Test
    fun `blad dostawcy w polu error to Failed`() = runTest {
        val source = FakeWeatherSource().apply { forecastJson = { """{"error": true, "reason": "Nope"}""" } }
        val model = model(source)
        model.selectPlace(krakow)
        runCurrent()
        assertSame(WeatherState.Failed, model.state.value)
    }

    // Pamięć podręczna

    @Test
    fun `udane pobranie zapisuje surowa odpowiedz w pamieci podrecznej`() = runTest {
        val cache = MemoryCache()
        val model = model(FakeWeatherSource(), cache)
        model.selectPlace(krakow)
        runCurrent()
        val saved = cache.entry!!
        assertEquals(now, saved.fetchedAtMillis)
        assertEquals(50.06, saved.latitude, 1e-9)
        assertEquals(WeatherFixtures.FORECAST, saved.raw)
    }

    @Test
    fun `swieza pamiec podreczna pokazuje pogode bez zapytania do sieci`() = runTest {
        store.edit { putString("weather.place", krakow.rounded().encode()) }
        val cache = MemoryCache(CachedForecast(50.06, 19.94, now - 5 * 60_000L, WeatherFixtures.FORECAST))
        val source = FakeWeatherSource()
        val model = model(source, cache)
        model.refresh()
        runCurrent()
        assertEquals(0, source.forecastCalls.size)
        assertEquals(now - 5 * 60_000L, model.ready().fetchedAtMillis)
    }

    @Test
    fun `starsza pamiec podreczna jest pokazana i odswiezona`() = runTest {
        store.edit { putString("weather.place", krakow.rounded().encode()) }
        val cache = MemoryCache(CachedForecast(50.06, 19.94, now - 50 * 60_000L, WeatherFixtures.FORECAST))
        val source = FakeWeatherSource()
        val model = model(source, cache)
        model.refresh()
        runCurrent()
        assertEquals(1, source.forecastCalls.size)
        assertEquals(now, model.ready().fetchedAtMillis)
    }

    @Test
    fun `bez internetu po restarcie kafelek pokazuje ostatnia zapisana pogode`() = runTest {
        store.edit { putString("weather.place", krakow.rounded().encode()) }
        val oldFetch = now - 3 * 3_600_000L
        val cache = MemoryCache(CachedForecast(50.06, 19.94, oldFetch, WeatherFixtures.FORECAST))
        val source = FakeWeatherSource().apply { failure = IOException("brak sieci") }
        val model = model(source, cache)
        model.refresh()
        runCurrent()
        assertEquals(oldFetch, model.ready().fetchedAtMillis)
    }

    @Test
    fun `pamiec podreczna innej miejscowosci jest ignorowana`() = runTest {
        store.edit { putString("weather.place", gdansk.rounded().encode()) }
        val cache = MemoryCache(CachedForecast(50.06, 19.94, now, WeatherFixtures.FORECAST))
        val source = FakeWeatherSource().apply { failure = IOException("brak sieci") }
        val model = model(source, cache)
        model.refresh()
        runCurrent()
        assertSame(WeatherState.Failed, model.state.value)
    }

    @Test
    fun `uszkodzona pamiec podreczna jest ignorowana`() = runTest {
        store.edit { putString("weather.place", krakow.rounded().encode()) }
        val cache = MemoryCache(CachedForecast(50.06, 19.94, now, "nie json"))
        val source = FakeWeatherSource()
        val model = model(source, cache)
        model.refresh()
        runCurrent()
        assertEquals(1, source.forecastCalls.size)
        assertInstanceOf(WeatherState.Ready::class.java, model.state.value)
    }

    // Zmiana i usuwanie miejscowości

    @Test
    fun `usuniecie miejscowosci czysci ustawienia i pamiec i konczy zapytania`() = runTest {
        val cache = MemoryCache()
        val source = FakeWeatherSource()
        val model = model(source, cache)
        model.selectPlace(krakow)
        runCurrent()

        model.clearPlace()
        runCurrent()
        assertSame(WeatherState.NoPlace, model.state.value)
        assertNull(model.place.value)
        assertNull(AppSettings(store, FakeCipher()).weatherPlace)
        assertNull(cache.entry)
        assertEquals(1, cache.clears)

        now += 3_600_000L
        model.refresh()
        runCurrent()
        assertEquals(1, source.forecastCalls.size)
    }

    @Test
    fun `wynik dla poprzedniej miejscowosci nie nadpisuje nowej`() = runTest {
        val source = FakeWeatherSource()
        val slow = CompletableDeferred<Unit>()
        source.gate = slow
        val model = model(source)
        model.selectPlace(krakow)
        runCurrent()

        source.gate = null
        model.selectPlace(gdansk)
        runCurrent()
        slow.complete(Unit)
        runCurrent()

        assertEquals(2, source.forecastCalls.size)
        assertEquals("Gdańsk", model.ready().place.name)
        assertEquals(54.35, source.forecastCalls.last().first, 1e-9)
    }

    // Wyszukiwanie

    @Test
    fun `wyszukiwanie czeka na przerwe w pisaniu i wysyla ostatni tekst`() = runTest {
        val source = FakeWeatherSource()
        val model = model(source)
        model.onQueryChange("Kr")
        advanceTimeBy(100)
        model.onQueryChange("  Kra ")
        advanceTimeBy(399)
        runCurrent()
        assertEquals(0, source.searchCalls.size)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("Kra" to "pl"), source.searchCalls)
        val results = model.search.value as PlaceSearchState.Results
        assertEquals(listOf("Kraków", "Krakówek"), results.places.map { it.name })
    }

    @Test
    fun `jezyk wyszukiwania jest czytany przy kazdym zapytaniu`() = runTest {
        val source = FakeWeatherSource()
        val model = model(source)
        model.onQueryChange("Gdansk")
        advanceTimeBy(401)
        runCurrent()
        language = "en" // użytkownik zmienił język aplikacji
        model.onQueryChange("Krakow")
        advanceTimeBy(401)
        runCurrent()
        assertEquals(listOf("Gdansk" to "pl", "Krakow" to "en"), source.searchCalls)
    }

    @Test
    fun `za krotki tekst nie wysyla zapytania i czysci wyniki`() = runTest {
        val source = FakeWeatherSource()
        val model = model(source)
        model.onQueryChange("Kra")
        advanceTimeBy(500)
        runCurrent()
        assertInstanceOf(PlaceSearchState.Results::class.java, model.search.value)

        model.onQueryChange("K")
        advanceTimeBy(500)
        runCurrent()
        assertSame(PlaceSearchState.Idle, model.search.value)
        model.onQueryChange("   ")
        advanceTimeBy(500)
        runCurrent()
        assertEquals(1, source.searchCalls.size)
    }

    @Test
    fun `brak wynikow to pusta lista a blad sieci to Failed`() = runTest {
        val source = FakeWeatherSource().apply { searchJson = { WeatherFixtures.GEOCODING_EMPTY } }
        val model = model(source)
        model.onQueryChange("Xyzzy")
        advanceTimeBy(401)
        runCurrent()
        assertEquals(PlaceSearchState.Results(emptyList()), model.search.value)

        source.failure = IOException("brak sieci")
        model.onQueryChange("Xyzzyx")
        advanceTimeBy(401)
        runCurrent()
        assertSame(PlaceSearchState.Failed, model.search.value)

        source.failure = null
        source.searchJson = { WeatherFixtures.GEOCODING }
        model.onQueryChange("Kraków")
        advanceTimeBy(401)
        runCurrent()
        assertInstanceOf(PlaceSearchState.Results::class.java, model.search.value)
    }

    @Test
    fun `podczas pisania poprzednie wyniki zostaja do czasu nowych`() = runTest {
        val source = FakeWeatherSource()
        val model = model(source)
        model.onQueryChange("Kra")
        advanceTimeBy(401)
        runCurrent()
        val first = model.search.value

        val gate = CompletableDeferred<Unit>()
        source.gate = gate
        model.onQueryChange("Krak")
        advanceTimeBy(401)
        runCurrent()
        assertSame(first, model.search.value)

        gate.complete(Unit)
        runCurrent()
        assertInstanceOf(PlaceSearchState.Results::class.java, model.search.value)
    }

    @Test
    fun `pierwsze wyszukiwanie pokazuje stan Searching`() = runTest {
        val source = FakeWeatherSource().apply { gate = CompletableDeferred() }
        val model = model(source)
        model.onQueryChange("Kra")
        advanceTimeBy(401)
        runCurrent()
        assertSame(PlaceSearchState.Searching, model.search.value)
    }

    @Test
    fun `wybor miejscowosci i reset czyszcza wyszukiwarke`() = runTest {
        val source = FakeWeatherSource()
        val model = model(source)
        model.onQueryChange("Kra")
        advanceTimeBy(401)
        runCurrent()
        model.selectPlace(krakow)
        assertSame(PlaceSearchState.Idle, model.search.value)

        model.onQueryChange("Gda")
        advanceTimeBy(401)
        runCurrent()
        model.resetSearch()
        assertSame(PlaceSearchState.Idle, model.search.value)
    }

    @Test
    fun `zamkniecie wyszukiwarki w trakcie oczekiwania anuluje zapytanie`() = runTest {
        val source = FakeWeatherSource()
        val model = model(source)
        model.onQueryChange("Kra")
        advanceTimeBy(100)
        model.resetSearch()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(0, source.searchCalls.size)
        assertSame(PlaceSearchState.Idle, model.search.value)
    }
}
