package app.domik.integrations.weather

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.roundToLong

/*
 * Model danych pogodowych – bez Androida (testowalny na JVM). Pogoda pochodzi z Open-Meteo; do dostawcy trafiają
 * wyłącznie współrzędne miejscowości wybranej przez użytkownika (zaokrąglone do 2 miejsc ≈ 1 km) i wpisana nazwa
 * przy wyszukiwaniu – nic więcej.
 */

/**
 * Miejscowość, dla której pokazujemy pogodę. [name] to krótka nazwa na kafelku („Kraków”), [detail] – region i kraj
 * do listy wyników („Małopolskie, Polska”, może być puste). Współrzędne zapisujemy zaokrąglone ([rounded]).
 */
data class WeatherPlace(val name: String, val detail: String, val latitude: Double, val longitude: Double) {
    /** Kopia ze współrzędnymi zaokrąglonymi do 2 miejsc po przecinku (prywatność: ok. 1 km). */
    fun rounded(): WeatherPlace = copy(latitude = roundCoordinate(latitude), longitude = roundCoordinate(longitude))

    /** Zapis do `KeyValueStore`: wersja, szerokość, długość, nazwa, opis – oddzielone tabulatorami. */
    fun encode(): String = listOf(
        ENCODING_VERSION,
        String.format(Locale.ROOT, "%.2f", latitude),
        String.format(Locale.ROOT, "%.2f", longitude),
        sanitize(name),
        sanitize(detail),
    ).joinToString("\t")

    companion object {
        private const val ENCODING_VERSION = "1"

        fun roundCoordinate(value: Double): Double = (value * 100.0).roundToLong() / 100.0

        /** Odczyt z [encode]; nieczytelny albo niepoprawny zapis (zła wersja, współrzędne poza zakresem) daje `null`. */
        fun decode(text: String?): WeatherPlace? {
            val parts = text?.split('\t', limit = 5) ?: return null
            if (parts.size < 4 || parts[0] != ENCODING_VERSION) return null
            val latitude = parts[1].toDoubleOrNull()?.takeIf { it in -90.0..90.0 } ?: return null
            val longitude = parts[2].toDoubleOrNull()?.takeIf { it in -180.0..180.0 } ?: return null
            val name = parts[3].trim().takeIf { it.isNotEmpty() } ?: return null
            return WeatherPlace(name, parts.getOrNull(4).orEmpty().trim(), latitude, longitude)
        }

        private fun sanitize(text: String): String = text.replace(Regex("[\\t\\r\\n]+"), " ").trim()
    }
}

/**
 * Pogoda w chwili [time] (czas lokalny miejscowości). [code] to kod WMO ([WeatherKind.fromWmo]), [isDay] – czy
 * w tym miejscu jest dzień (ikona słońca albo księżyca). Pola opcjonalne ([windGustsKmh], [relativeHumidity]) są
 * `null`, gdy dostawca ich nie podał (np. odpowiedź z pamięci podręcznej sprzed rozszerzenia zapytania).
 */
data class CurrentWeather(
    val time: LocalDateTime,
    val temperature: Double,
    val apparentTemperature: Double?,
    val code: Int,
    val isDay: Boolean,
    val windKmh: Double?,
    val windGustsKmh: Double? = null,
    /** Wilgotność względna powietrza w procentach (0–100). */
    val relativeHumidity: Int? = null,
)

/**
 * Prognoza na pełną godzinę [time] (czas lokalny miejscowości). [precipitationProbability] to szansa opadów
 * w procentach (0–100) albo `null`, gdy dostawca jej nie podaje dla tego miejsca.
 */
data class HourlyWeather(
    val time: LocalDateTime,
    val temperature: Double,
    val code: Int,
    val isDay: Boolean,
    val precipitationProbability: Int? = null,
)

/**
 * Prognoza dobowa na dzień [date] (data lokalna miejscowości); ikona dnia – bez rozróżniania dnia i nocy.
 * [precipitationProbability] – największa szansa opadów w ciągu doby (%), [precipitationSum] – suma opadów (mm),
 * [sunrise]/[sunset] – czas lokalny miejscowości, [uvIndexMax] – największy indeks UV, [windMaxKmh] – największa
 * prędkość wiatru. Wszystkie pola poza datą, temperaturami i kodem mogą być `null`.
 */
data class DailyWeather(
    val date: LocalDate,
    val minTemperature: Double?,
    val maxTemperature: Double?,
    val code: Int,
    val precipitationProbability: Int? = null,
    val precipitationSum: Double? = null,
    val sunrise: LocalDateTime? = null,
    val sunset: LocalDateTime? = null,
    val uvIndexMax: Double? = null,
    val windMaxKmh: Double? = null,
)

/**
 * Odpowiedź prognozy w modelu domenowym. Wszystkie czasy są **lokalne dla miejscowości** (`timezone=auto`);
 * [utcOffsetSeconds] przelicza chwilę z zegara urządzenia na czas miejscowości ([localNow]).
 */
data class WeatherSnapshot(
    val utcOffsetSeconds: Int,
    val current: CurrentWeather,
    val hourly: List<HourlyWeather>,
    val daily: List<DailyWeather>,
) {
    /** Czas lokalny miejscowości w chwili [nowMillis] (ms od epoki). */
    fun localNow(nowMillis: Long): LocalDateTime =
        Instant.ofEpochMilli(nowMillis).atOffset(ZoneOffset.ofTotalSeconds(utcOffsetSeconds.coerceIn(-MAX_OFFSET, MAX_OFFSET))).toLocalDateTime()

    /** Najbliższe pełne godziny **po** chwili [nowMillis] (kolejność rosnąca), najwyżej [limit]. */
    fun upcomingHours(nowMillis: Long, limit: Int = Int.MAX_VALUE): List<HourlyWeather> {
        val now = localNow(nowMillis)
        return hourly.filter { it.time.isAfter(now) }.take(limit)
    }

    /** Dni od dzisiaj (w miejscowości) wzwyż, najwyżej [limit]; dni minione (dane nieaktualne) są pomijane. */
    fun upcomingDays(nowMillis: Long, limit: Int = Int.MAX_VALUE): List<DailyWeather> {
        val today = localNow(nowMillis).toLocalDate()
        return daily.filter { !it.date.isBefore(today) }.take(limit)
    }

    private companion object {
        const val MAX_OFFSET = 18 * 3600
    }
}

/** Grupa pogody wg kodu WMO (tabela 4677 uproszczona przez Open-Meteo) – podstawa opisów i ikon. */
enum class WeatherKind {
    Clear,
    MostlyClear,
    PartlyCloudy,
    Overcast,
    Fog,
    Drizzle,
    FreezingDrizzle,
    Rain,
    HeavyRain,
    FreezingRain,
    Snow,
    HeavySnow,
    SnowGrains,
    RainShowers,
    HeavyRainShowers,
    SnowShowers,
    Thunderstorm,
    ThunderstormHail,
    Unknown,
    ;

    companion object {
        /** Kod WMO → grupa; kod nieznany (albo `null`) daje [Unknown]. */
        fun fromWmo(code: Int?): WeatherKind = when (code) {
            0 -> Clear
            1 -> MostlyClear
            2 -> PartlyCloudy
            3 -> Overcast
            45, 48 -> Fog
            51, 53, 55 -> Drizzle
            56, 57 -> FreezingDrizzle
            61, 63 -> Rain
            65 -> HeavyRain
            66, 67 -> FreezingRain
            71, 73 -> Snow
            75 -> HeavySnow
            77 -> SnowGrains
            80, 81 -> RainShowers
            82 -> HeavyRainShowers
            85, 86 -> SnowShowers
            95 -> Thunderstorm
            96, 99 -> ThunderstormHail
            else -> Unknown
        }
    }
}
