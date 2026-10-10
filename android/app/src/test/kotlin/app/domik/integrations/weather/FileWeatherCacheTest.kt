package app.domik.integrations.weather

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FileWeatherCacheTest {
    @TempDir
    lateinit var dir: File

    private fun cache() = FileWeatherCache(File(dir, "weather/forecast.txt"))

    @Test
    fun `brak pliku to brak wpisu`() {
        assertNull(cache().read())
    }

    @Test
    fun `zapis i odczyt zachowuja miejsce czas i surowa odpowiedz`() {
        val entry = CachedForecast(50.06, 19.94, 1_760_000_000_000L, WeatherFixtures.FORECAST)
        cache().write(entry)
        val read = cache().read()
        assertNotNull(read)
        assertEquals(50.06, read!!.latitude, 1e-9)
        assertEquals(19.94, read.longitude, 1e-9)
        assertEquals(1_760_000_000_000L, read.fetchedAtMillis)
        assertEquals(WeatherFixtures.FORECAST, read.raw)
        // odpowiedź z pliku da się sparsować tak jak świeżą
        assertEquals(13.4, OpenMeteoParser.parseForecast(read.raw).current.temperature, 1e-9)
    }

    @Test
    fun `wpis dotyczy tylko swojej miejscowosci`() {
        val entry = CachedForecast(50.06, 19.94, 1L, "{}")
        assertTrue(entry.isFor(WeatherPlace("Kraków", "", 50.06, 19.94)))
        assertFalse(entry.isFor(WeatherPlace("Gdańsk", "", 54.35, 18.65)))
    }

    @Test
    fun `kolejny zapis zastepuje poprzedni i nie zostawia plikow tymczasowych`() {
        val cache = cache()
        cache.write(CachedForecast(50.06, 19.94, 1L, "pierwszy"))
        cache.write(CachedForecast(54.35, 18.65, 2L, "drugi"))
        assertEquals("drugi", cache.read()?.raw)
        assertEquals(listOf("forecast.txt"), File(dir, "weather").list()!!.toList())
    }

    @Test
    fun `uszkodzony plik jest ignorowany`() {
        val file = File(dir, "weather/forecast.txt")
        file.parentFile.mkdirs()
        for (content in listOf("", "bez naglowka", "2 1 50.00 19.00\n{}", "1 x 50.00 19.00\n{}", "1 1 50.00\n{}", "1 1 50.00 19.00")) {
            file.writeText(content)
            assertNull(FileWeatherCache(file).read(), "zawartość: $content")
        }
    }

    @Test
    fun `czyszczenie usuwa plik i jest bezpieczne bez pliku`() {
        val cache = cache()
        cache.clear()
        cache.write(CachedForecast(50.06, 19.94, 1L, "{}"))
        cache.clear()
        assertNull(cache.read())
    }

    @Test
    fun `blad zapisu nie przerywa pracy`() {
        // Ścieżka, której katalogiem nadrzędnym jest zwykły plik: nie da się utworzyć katalogu ani pliku.
        val blocker = File(dir, "plik").apply { writeText("x") }
        val cache = FileWeatherCache(File(blocker, "forecast.txt"))
        cache.write(CachedForecast(50.06, 19.94, 1L, "{}"))
        assertNull(cache.read())
    }
}
