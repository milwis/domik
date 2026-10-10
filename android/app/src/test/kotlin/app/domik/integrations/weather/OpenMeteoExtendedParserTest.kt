package app.domik.integrations.weather

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Rozszerzona odpowiedź Open-Meteo (szansa opadów, wilgotność, porywy, wschód/zachód, UV, suma opadów). Plik
 * `weather/open_meteo_forecast_extended.json` to prawdziwa odpowiedź publicznego API zapisana raz (Kraków,
 * 2026-10-10 16:00) – testy nie łączą się z siecią.
 */
class OpenMeteoExtendedParserTest {
    private val json = javaClass.getResource("/weather/open_meteo_forecast_extended.json")!!.readText(Charsets.UTF_8)
    private val snapshot = OpenMeteoParser.parseForecast(json)

    @Test
    fun `biezaca pogoda ma wilgotnosc i porywy`() {
        val current = snapshot.current
        assertEquals(19.2, current.temperature, 1e-9)
        assertEquals(17.1, current.apparentTemperature!!, 1e-9)
        assertEquals(53, current.relativeHumidity)
        assertEquals(12.6, current.windKmh!!, 1e-9)
        assertEquals(28.1, current.windGustsKmh!!, 1e-9)
        assertEquals(3, current.code)
        assertTrue(current.isDay)
    }

    @Test
    fun `godziny maja szanse opadow`() {
        assertEquals(48, snapshot.hourly.size)
        assertEquals(LocalDateTime.of(2026, 10, 10, 16, 0), snapshot.hourly.first().time)
        val eighteen = snapshot.hourly.single { it.time == LocalDateTime.of(2026, 10, 10, 18, 0) }
        assertEquals(11, eighteen.precipitationProbability)
        assertEquals(51, eighteen.code)
        assertEquals(false, eighteen.isDay)
        assertEquals(65, snapshot.hourly.maxOf { it.precipitationProbability ?: 0 })
    }

    @Test
    fun `dni maja komplet pol prognozy dobowej`() {
        assertEquals(14, snapshot.daily.size)
        val today = snapshot.daily.first()
        assertEquals(LocalDate.of(2026, 10, 10), today.date)
        assertEquals(19.8, today.maxTemperature!!, 1e-9)
        assertEquals(11.7, today.minTemperature!!, 1e-9)
        assertEquals(35, today.precipitationProbability)
        assertEquals(0.4, today.precipitationSum!!, 1e-9)
        assertEquals(LocalDateTime.of(2026, 10, 10, 6, 53), today.sunrise)
        assertEquals(LocalDateTime.of(2026, 10, 10, 17, 59), today.sunset)
        assertEquals(2.85, today.uvIndexMax!!, 1e-9)
        assertEquals(13.0, today.windMaxKmh!!, 1e-9)
        assertEquals(65, snapshot.daily[1].precipitationProbability)
        assertEquals(0.0, snapshot.daily[1].precipitationSum!!, 1e-9)
    }

    @Test
    fun `najblizsze godziny i dni z rozszerzonej odpowiedzi`() {
        // 16:20 czasu lokalnego (UTC+2) = 14:20 UTC
        val now = Instant.parse("2026-10-10T14:20:00Z").toEpochMilli()
        assertEquals(17, snapshot.upcomingHours(now).first().time.hour)
        assertEquals(47, snapshot.upcomingHours(now).size)
        assertEquals(14, snapshot.upcomingDays(now).size)
    }

    @Test
    fun `starsza odpowiedz bez nowych pol nadal sie parsuje a nowe pola sa puste`() {
        val old = OpenMeteoParser.parseForecast(WeatherFixtures.FORECAST)
        assertNull(old.current.relativeHumidity)
        assertNull(old.current.windGustsKmh)
        assertTrue(old.hourly.all { it.precipitationProbability == null })
        assertTrue(old.daily.all { it.sunrise == null && it.sunset == null && it.uvIndexMax == null && it.precipitationSum == null })
    }

    @Test
    fun `puste i bledne wartosci nowych pol sa pomijane pojedynczo`() {
        val sparse = OpenMeteoParser.parseForecast(
            """
            {
              "utc_offset_seconds": 0,
              "current": {"time": "2026-10-10T10:00", "temperature_2m": 5.0, "weather_code": 1,
                          "relative_humidity_2m": 140.0, "wind_gusts_10m": null},
              "hourly": {
                "time": ["2026-10-10T11:00", "2026-10-10T12:00", "2026-10-10T13:00"],
                "temperature_2m": [5.0, 6.0, 7.0], "weather_code": [1, 1, 1],
                "precipitation_probability": [null, 30.0, "x"]
              },
              "daily": {
                "time": ["2026-10-10", "2026-10-11"], "weather_code": [1, 2],
                "sunrise": ["2026-10-10T06:00", "to nie data"], "sunset": [null, "2026-10-11T17:00"],
                "uv_index_max": [-1.0, 2.0], "precipitation_sum": [-3.0, 1.5],
                "precipitation_probability_max": [105.0, null]
              }
            }
            """.trimIndent(),
        )
        assertEquals(100, sparse.current.relativeHumidity)
        assertNull(sparse.current.windGustsKmh)
        assertEquals(listOf(null, 30, null), sparse.hourly.map { it.precipitationProbability })
        val first = sparse.daily[0]
        val second = sparse.daily[1]
        assertEquals(LocalDateTime.of(2026, 10, 10, 6, 0), first.sunrise)
        assertNull(first.sunset)
        assertNull(second.sunrise)
        assertEquals(LocalDateTime.of(2026, 10, 11, 17, 0), second.sunset)
        assertNull(first.uvIndexMax)
        assertEquals(2.0, second.uvIndexMax!!, 1e-9)
        assertNull(first.precipitationSum)
        assertEquals(1.5, second.precipitationSum!!, 1e-9)
        assertEquals(100, first.precipitationProbability)
        assertNull(second.precipitationProbability)
    }
}
