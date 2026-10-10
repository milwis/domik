package app.domik.integrations.weather

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OpenMeteoParserTest {
    private val snapshot = OpenMeteoParser.parseForecast(WeatherFixtures.FORECAST)

    @Test
    fun `parsuje biezaca pogode`() {
        val current = snapshot.current
        assertEquals(7200, snapshot.utcOffsetSeconds)
        assertEquals(LocalDateTime.of(2026, 10, 10, 14, 0), current.time)
        assertEquals(13.4, current.temperature, 1e-9)
        assertEquals(11.9, current.apparentTemperature!!, 1e-9)
        assertEquals(2, current.code)
        assertTrue(current.isDay)
        assertEquals(14.6, current.windKmh!!, 1e-9)
    }

    @Test
    fun `godziny z brakujaca wartoscia sa pomijane pojedynczo`() {
        assertEquals(
            listOf(13, 14, 15, 17, 18),
            snapshot.hourly.map { it.time.hour },
        )
        assertEquals(61, snapshot.hourly.single { it.time.hour == 17 }.code)
        assertFalse(snapshot.hourly.single { it.time.hour == 18 }.isDay)
    }

    @Test
    fun `dni maja puste wartosci tam gdzie dostawca ich nie podal`() {
        assertEquals(3, snapshot.daily.size)
        val last = snapshot.daily.last()
        assertEquals(LocalDate.of(2026, 10, 12), last.date)
        assertNull(last.maxTemperature)
        assertEquals(5.0, last.minTemperature!!, 1e-9)
        assertEquals(95, last.code)
    }

    @Test
    fun `czas lokalny miejscowosci wynika z przesuniecia UTC`() {
        assertEquals(LocalDateTime.of(2026, 10, 10, 14, 20), snapshot.localNow(WeatherFixtures.NOW_MILLIS))
    }

    @Test
    fun `najblizsze godziny sa scisle po biezacej chwili`() {
        assertEquals(listOf(15, 17, 18), snapshot.upcomingHours(WeatherFixtures.NOW_MILLIS).map { it.time.hour })
        assertEquals(listOf(15), snapshot.upcomingHours(WeatherFixtures.NOW_MILLIS, limit = 1).map { it.time.hour })
    }

    @Test
    fun `najblizsze dni zaczynaja sie od dzisiaj w miejscowosci i pomijaja miniete`() {
        assertEquals(3, snapshot.upcomingDays(WeatherFixtures.NOW_MILLIS).size)
        // półtora doby później: dzisiejsza prognoza jest już przeszłością (dane nieaktualne)
        val later = WeatherFixtures.NOW_MILLIS + 36 * 3_600_000L
        assertEquals(listOf(LocalDate.of(2026, 10, 12)), snapshot.upcomingDays(later).map { it.date })
    }

    @Test
    fun `brak wymaganych pol to blad parsowania`() {
        val broken = listOf(
            "{}",
            """{"current": {"time": "2026-10-10T14:00", "weather_code": 2}}""",
            """{"current": {"time": "2026-10-10T14:00", "temperature_2m": 1.0}}""",
            """{"current": {"temperature_2m": 1.0, "weather_code": 2}}""",
            """{"current": {"time": "wczoraj", "temperature_2m": 1.0, "weather_code": 2}}""",
            """{"error": true, "reason": "Latitude must be in range of -90 to 90"}""",
            "<html>Bad Gateway</html>",
            "[]",
        )
        for (json in broken) {
            assertThrows(WeatherParseException::class.java, { OpenMeteoParser.parseForecast(json) }, "json: $json")
        }
    }

    @Test
    fun `tylko biezaca pogoda wystarcza - reszta jest opcjonalna`() {
        val minimal = OpenMeteoParser.parseForecast(
            """{"current": {"time": "2026-10-10T14:00", "temperature_2m": -3.5, "weather_code": 71}}""",
        )
        assertEquals(0, minimal.utcOffsetSeconds)
        assertNull(minimal.current.apparentTemperature)
        assertNull(minimal.current.windKmh)
        assertTrue(minimal.current.isDay)
        assertTrue(minimal.hourly.isEmpty())
        assertTrue(minimal.daily.isEmpty())
    }

    @Test
    fun `geokodowanie - opis miejscowosci bez powtorzen i bez duplikatow`() {
        val places = OpenMeteoParser.parseGeocoding(WeatherFixtures.GEOCODING)
        assertEquals(listOf("Kraków", "Krakówek"), places.map { it.name })
        // admin2 równe nazwie nie powtarza się w opisie
        assertEquals("Małopolskie, Polska", places[0].detail)
        assertEquals("Wrocław, Dolnośląskie, Polska", places[1].detail)
        assertEquals(50.06143, places[0].latitude, 1e-9)
    }

    @Test
    fun `geokodowanie - brak wynikow to pusta lista a nie blad`() {
        assertTrue(OpenMeteoParser.parseGeocoding(WeatherFixtures.GEOCODING_EMPTY).isEmpty())
        assertTrue(OpenMeteoParser.parseGeocoding("""{"results": []}""").isEmpty())
    }

    @Test
    fun `geokodowanie - smieci to blad parsowania`() {
        assertThrows(WeatherParseException::class.java) { OpenMeteoParser.parseGeocoding("nie json") }
        assertThrows(WeatherParseException::class.java) { OpenMeteoParser.parseGeocoding("[1]") }
    }
}
