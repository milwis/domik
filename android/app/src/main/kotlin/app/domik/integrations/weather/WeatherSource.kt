package app.domik.integrations.weather

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

/**
 * Źródło danych pogodowych: zwraca **surowy JSON** dostawcy (parsowanie robi [OpenMeteoParser], a surowa odpowiedź
 * trafia też do pamięci podręcznej [WeatherCache]). Wywołania blokują – model woła je poza wątkiem UI.
 * Błędy sieci to [IOException].
 */
interface WeatherSource {
    /** Prognoza dla punktu o współrzędnych ([latitude], [longitude]) – tylko one trafiają do dostawcy. */
    suspend fun forecast(latitude: Double, longitude: Double): String

    /** Wyszukiwanie miejscowości po nazwie [query]; [language] = kod języka nazw wyników („pl”, „en”). */
    suspend fun search(query: String, language: String): String
}

/** Brak dostawcy (testy, środowiska bez sieci): każde zapytanie kończy się błędem sieci. */
object NoWeatherSource : WeatherSource {
    override suspend fun forecast(latitude: Double, longitude: Double): String = throw IOException("Brak dostawcy pogody")

    override suspend fun search(query: String, language: String): String = throw IOException("Brak dostawcy pogody")
}

/** Adresy zapytań Open-Meteo (bez klucza API; tylko HTTPS). Czyste funkcje – testowalne bez sieci. */
object OpenMeteoUrls {
    private const val FORECAST = "https://api.open-meteo.com/v1/forecast"
    private const val GEOCODING = "https://geocoding-api.open-meteo.com/v1/search"

    /**
     * Liczba dni prognozy (dziś + kolejne) – pasek dni na kafelku pogody. 14 (Open-Meteo daje do 16), żeby szeroki
     * kafelek nie kończył się na tygodniu; to nadal jedno zapytanie (kilka KB więcej).
     */
    const val FORECAST_DAYS = 14

    /** Ile kolejnych godzin prognozy godzinowej (od bieżącej godziny) – pasek godzin; to jedno zapytanie, ok. 4 KB. */
    const val FORECAST_HOURS = 48
    const val SEARCH_RESULTS = 10

    /** Współrzędne są zaokrąglane do 2 miejsc (≈ 1 km) – dokładniejsze nigdy nie opuszczają urządzenia. */
    fun forecast(latitude: Double, longitude: Double): String = FORECAST + "?" + listOf(
        "latitude" to coordinate(latitude),
        "longitude" to coordinate(longitude),
        "current" to CURRENT_FIELDS,
        "hourly" to HOURLY_FIELDS,
        "daily" to DAILY_FIELDS,
        "timezone" to "auto",
        "forecast_days" to FORECAST_DAYS.toString(),
        "forecast_hours" to FORECAST_HOURS.toString(),
        "temperature_unit" to "celsius",
        "wind_speed_unit" to "kmh",
    ).joinToString("&") { (key, value) -> "$key=$value" }

    const val CURRENT_FIELDS = "temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,wind_gusts_10m,is_day"
    const val HOURLY_FIELDS = "temperature_2m,precipitation_probability,weather_code,is_day"
    const val DAILY_FIELDS = "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max," +
        "precipitation_sum,sunrise,sunset,uv_index_max,wind_speed_10m_max"

    fun geocoding(query: String, language: String): String = GEOCODING + "?" + listOf(
        "name" to encode(query.trim()),
        "language" to encode(language),
        "count" to SEARCH_RESULTS.toString(),
        "format" to "json",
    ).joinToString("&") { (key, value) -> "$key=$value" }

    private fun coordinate(value: Double): String = String.format(Locale.ROOT, "%.2f", WeatherPlace.roundCoordinate(value))

    private fun encode(text: String): String = URLEncoder.encode(text, Charsets.UTF_8.name())
}

/** Pobranie treści pod adresem HTTPS (blokujące). */
fun interface HttpGet {
    @Throws(IOException::class)
    fun get(url: String): String
}

/**
 * [HttpGet] na `HttpsURLConnection` z biblioteki standardowej: tylko HTTPS (inny schemat → błąd), krótkie limity
 * czasu, bez przekierowań, odpowiedź ograniczona do [maxBytes], neutralny `User-Agent` (bez modelu urządzenia).
 *
 * @property allowInsecureForTests pozwala na zwykłe HTTP – wyłącznie testy z lokalnym serwerem
 */
class UrlConnectionHttpGet(
    private val connectTimeoutMillis: Int = CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = READ_TIMEOUT_MILLIS,
    private val maxBytes: Int = MAX_RESPONSE_BYTES,
    private val allowInsecureForTests: Boolean = false,
) : HttpGet {
    override fun get(url: String): String {
        val target = URL(url)
        if (target.protocol != "https" && !(allowInsecureForTests && target.protocol == "http")) {
            throw IOException("Dozwolone jest tylko HTTPS")
        }
        val connection = target.openConnection() as HttpURLConnection
        try {
            check(connection is HttpsURLConnection || allowInsecureForTests)
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) throw IOException("Odpowiedź HTTP $status")
            return connection.inputStream.use { stream ->
                // Ręczna pętla zamiast InputStream.readNBytes (dopiero od Androida 13; minSdk 26).
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    if (out.size() > maxBytes) throw IOException("Odpowiedź za duża")
                }
                out.toString(Charsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val CONNECT_TIMEOUT_MILLIS = 8_000
        const val READ_TIMEOUT_MILLIS = 10_000
        const val MAX_RESPONSE_BYTES = 512 * 1024
        private const val USER_AGENT = "Domik"
    }
}

/** [WeatherSource] dla Open-Meteo na dowolnym [HttpGet]. */
class OpenMeteoSource(private val http: HttpGet = UrlConnectionHttpGet()) : WeatherSource {
    override suspend fun forecast(latitude: Double, longitude: Double): String =
        http.get(OpenMeteoUrls.forecast(latitude, longitude))

    override suspend fun search(query: String, language: String): String =
        http.get(OpenMeteoUrls.geocoding(query, language))
}
