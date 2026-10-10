package app.domik.integrations.weather

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeParseException

/**
 * Parsowanie odpowiedzi Open-Meteo na model domenowy. Wymagane są tylko pola bieżącej pogody; brakujące
 * pojedyncze wartości godzinowe i dobowe (`null` w tablicach) pomijają dany punkt, a nie całą prognozę.
 */
object OpenMeteoParser {
    /** @throws WeatherParseException odpowiedź nie jest prognozą Open-Meteo (np. komunikat błędu, śmieci) */
    fun parseForecast(json: String): WeatherSnapshot {
        val root = MiniJson.parse(json).asObject("odpowiedź")
        if (root["error"] == true) throw WeatherParseException("Dostawca zgłosił błąd: ${root["reason"] ?: "nieznany"}")
        val current = root["current"].asObject("current")
        val currentTime = current.dateTime("time")
        val currentCode = current.number("weather_code")?.toInt() ?: throw WeatherParseException("Brak weather_code")
        val temperature = current.number("temperature_2m") ?: throw WeatherParseException("Brak temperature_2m")
        return WeatherSnapshot(
            utcOffsetSeconds = root.number("utc_offset_seconds")?.toInt() ?: 0,
            current = CurrentWeather(
                time = currentTime,
                temperature = temperature,
                apparentTemperature = current.number("apparent_temperature"),
                code = currentCode,
                isDay = current.number("is_day")?.let { it != 0.0 } ?: true,
                windKmh = current.number("wind_speed_10m"),
                windGustsKmh = current.number("wind_gusts_10m"),
                relativeHumidity = current.number("relative_humidity_2m")?.let(::toPercent),
            ),
            hourly = parseHourly(root["hourly"]),
            daily = parseDaily(root["daily"]),
        )
    }

    /**
     * Wyniki wyszukiwania miejscowości (geocoding-api): brak pola `results` = brak wyników (pusta lista).
     * Współrzędne nie są tu zaokrąglane – robi to zapis wybranej miejscowości ([WeatherPlace.rounded]).
     */
    fun parseGeocoding(json: String): List<WeatherPlace> {
        val root = MiniJson.parse(json).asObject("odpowiedź")
        val results = root["results"] as? List<*> ?: return emptyList()
        return results.mapNotNull { item ->
            val entry = item as? Map<*, *> ?: return@mapNotNull null
            val name = (entry["name"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val latitude = (entry["latitude"] as? Double)?.takeIf { it in -90.0..90.0 } ?: return@mapNotNull null
            val longitude = (entry["longitude"] as? Double)?.takeIf { it in -180.0..180.0 } ?: return@mapNotNull null
            val admin1 = (entry["admin1"] as? String)?.trim().orEmpty()
            val admin2 = (entry["admin2"] as? String)?.trim().orEmpty()
            val country = (entry["country"] as? String)?.trim().orEmpty()
            val detail = listOf(admin2.takeIf { it != name && it != admin1 }.orEmpty(), admin1, country)
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(", ")
            WeatherPlace(name, detail, latitude, longitude)
        }.distinctBy { listOf(it.name, it.detail, it.rounded().latitude, it.rounded().longitude) }
    }

    private fun parseHourly(value: Any?): List<HourlyWeather> {
        val block = value as? Map<*, *> ?: return emptyList()
        val times = block["time"] as? List<*> ?: return emptyList()
        val temperatures = block["temperature_2m"] as? List<*> ?: return emptyList()
        val codes = block["weather_code"] as? List<*> ?: return emptyList()
        val days = block["is_day"] as? List<*>
        val precipitation = block["precipitation_probability"] as? List<*>
        return times.indices.mapNotNull { i ->
            val time = (times[i] as? String)?.let(::parseDateTime) ?: return@mapNotNull null
            val temperature = temperatures.getOrNull(i) as? Double ?: return@mapNotNull null
            val code = (codes.getOrNull(i) as? Double)?.toInt() ?: return@mapNotNull null
            HourlyWeather(
                time = time,
                temperature = temperature,
                code = code,
                isDay = (days?.getOrNull(i) as? Double)?.let { it != 0.0 } ?: true,
                precipitationProbability = (precipitation?.getOrNull(i) as? Double)?.let(::toPercent),
            )
        }
    }

    private fun parseDaily(value: Any?): List<DailyWeather> {
        val block = value as? Map<*, *> ?: return emptyList()
        val times = block["time"] as? List<*> ?: return emptyList()
        val codes = block["weather_code"] as? List<*> ?: return emptyList()
        val maxima = block["temperature_2m_max"] as? List<*>
        val minima = block["temperature_2m_min"] as? List<*>
        val precipitation = block["precipitation_probability_max"] as? List<*>
        val precipitationSum = block["precipitation_sum"] as? List<*>
        val sunrise = block["sunrise"] as? List<*>
        val sunset = block["sunset"] as? List<*>
        val uv = block["uv_index_max"] as? List<*>
        val wind = block["wind_speed_10m_max"] as? List<*>
        return times.indices.mapNotNull { i ->
            val date = (times[i] as? String)?.let(::parseDate) ?: return@mapNotNull null
            val code = (codes.getOrNull(i) as? Double)?.toInt() ?: return@mapNotNull null
            DailyWeather(
                date = date,
                minTemperature = minima?.getOrNull(i) as? Double,
                maxTemperature = maxima?.getOrNull(i) as? Double,
                code = code,
                precipitationProbability = (precipitation?.getOrNull(i) as? Double)?.let(::toPercent),
                precipitationSum = (precipitationSum?.getOrNull(i) as? Double)?.takeIf { it >= 0.0 },
                sunrise = (sunrise?.getOrNull(i) as? String)?.let(::parseDateTime),
                sunset = (sunset?.getOrNull(i) as? String)?.let(::parseDateTime),
                uvIndexMax = (uv?.getOrNull(i) as? Double)?.takeIf { it >= 0.0 },
                windMaxKmh = (wind?.getOrNull(i) as? Double)?.takeIf { it >= 0.0 },
            )
        }
    }

    /** Wartość procentowa z odpowiedzi (liczba zmiennoprzecinkowa) → liczba całkowita 0–100. */
    private fun toPercent(value: Double): Int = Math.round(value).toInt().coerceIn(0, 100)

    private fun Any?.asObject(what: String): Map<*, *> = this as? Map<*, *> ?: throw WeatherParseException("Brak obiektu: $what")

    private fun Map<*, *>.number(key: String): Double? = this[key] as? Double

    private fun Map<*, *>.dateTime(key: String): LocalDateTime =
        (this[key] as? String)?.let(::parseDateTime) ?: throw WeatherParseException("Brak lub zły czas: $key")

    private fun parseDateTime(text: String): LocalDateTime? = try {
        LocalDateTime.parse(text)
    } catch (_: DateTimeParseException) {
        null
    }

    private fun parseDate(text: String): LocalDate? = try {
        LocalDate.parse(text)
    } catch (_: DateTimeParseException) {
        null
    }
}
