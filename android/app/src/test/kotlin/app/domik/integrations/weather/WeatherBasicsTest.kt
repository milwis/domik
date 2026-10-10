package app.domik.integrations.weather

import app.domik.data.AppSettings
import app.domik.data.FakeCipher
import app.domik.data.InMemoryKeyValueStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Kody WMO → grupy, zasady odświeżania, zapis miejscowości. */
class WeatherBasicsTest {
    @Test
    fun `kody WMO sa mapowane na grupy pogody`() {
        val expected = mapOf(
            0 to WeatherKind.Clear,
            1 to WeatherKind.MostlyClear,
            2 to WeatherKind.PartlyCloudy,
            3 to WeatherKind.Overcast,
            45 to WeatherKind.Fog,
            48 to WeatherKind.Fog,
            51 to WeatherKind.Drizzle,
            53 to WeatherKind.Drizzle,
            55 to WeatherKind.Drizzle,
            56 to WeatherKind.FreezingDrizzle,
            57 to WeatherKind.FreezingDrizzle,
            61 to WeatherKind.Rain,
            63 to WeatherKind.Rain,
            65 to WeatherKind.HeavyRain,
            66 to WeatherKind.FreezingRain,
            67 to WeatherKind.FreezingRain,
            71 to WeatherKind.Snow,
            73 to WeatherKind.Snow,
            75 to WeatherKind.HeavySnow,
            77 to WeatherKind.SnowGrains,
            80 to WeatherKind.RainShowers,
            81 to WeatherKind.RainShowers,
            82 to WeatherKind.HeavyRainShowers,
            85 to WeatherKind.SnowShowers,
            86 to WeatherKind.SnowShowers,
            95 to WeatherKind.Thunderstorm,
            96 to WeatherKind.ThunderstormHail,
            99 to WeatherKind.ThunderstormHail,
        )
        for ((code, kind) in expected) assertEquals(kind, WeatherKind.fromWmo(code), "kod $code")
    }

    @Test
    fun `nieznany kod WMO daje grupe Unknown`() {
        for (code in listOf(null, -1, 4, 44, 100, 1000)) assertEquals(WeatherKind.Unknown, WeatherKind.fromWmo(code), "kod $code")
    }

    @Test
    fun `odswiezanie jest potrzebne bez danych i po przekroczeniu wieku`() {
        val now = 10_000_000L
        assertTrue(WeatherPolicy.needsRefresh(null, now))
        assertFalse(WeatherPolicy.needsRefresh(now - WeatherPolicy.RESUME_MAX_AGE_MILLIS, now))
        assertTrue(WeatherPolicy.needsRefresh(now - WeatherPolicy.RESUME_MAX_AGE_MILLIS - 1, now))
        assertFalse(WeatherPolicy.needsRefresh(now - 20 * 60_000L, now, maxAgeMillis = WeatherPolicy.REFRESH_INTERVAL_MILLIS))
    }

    @Test
    fun `dane sa nieaktualne po 45 minutach`() {
        val now = 100_000_000L
        assertFalse(WeatherPolicy.isStale(now, now))
        assertFalse(WeatherPolicy.isStale(now - WeatherPolicy.STALE_AFTER_MILLIS, now))
        assertTrue(WeatherPolicy.isStale(now - WeatherPolicy.STALE_AFTER_MILLIS - 1, now))
    }

    @Test
    fun `niewielka niedokladnosc zegara nie oznacza danych jako nieaktualnych`() {
        val now = 100_000_000L
        // interfejs liczy „teraz” z dokładnością do minuty, więc czas pobrania bywa o kilkadziesiąt sekund „w przyszłości”
        assertFalse(WeatherPolicy.needsRefresh(now + 59_000L, now))
        assertFalse(WeatherPolicy.isStale(now + 59_000L, now))
        assertFalse(WeatherPolicy.isStale(now + WeatherPolicy.CLOCK_SKEW_TOLERANCE_MILLIS, now))
    }

    @Test
    fun `mocno cofniety zegar nie zamraza danych`() {
        val now = 100_000_000L
        // dane „z przyszłości” poza tolerancją = bardzo stare: trzeba je odświeżyć i oznaczyć
        val future = now + WeatherPolicy.CLOCK_SKEW_TOLERANCE_MILLIS + 1
        assertTrue(WeatherPolicy.needsRefresh(future, now))
        assertTrue(WeatherPolicy.isStale(future, now))
    }

    @Test
    fun `domyslny jezyk wyszukiwania to polski poza angielskim`() {
        val saved = java.util.Locale.getDefault()
        try {
            for ((tag, expected) in listOf("pl-PL" to "pl", "en-GB" to "en", "de-DE" to "pl", "uk-UA" to "pl")) {
                java.util.Locale.setDefault(java.util.Locale.forLanguageTag(tag))
                assertEquals(expected, WeatherModel.defaultSearchLanguage(), tag)
            }
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test
    fun `okresy odswiezania sa spojne`() {
        assertTrue(WeatherPolicy.RESUME_MAX_AGE_MILLIS < WeatherPolicy.REFRESH_INTERVAL_MILLIS)
        assertTrue(WeatherPolicy.REFRESH_INTERVAL_MILLIS < WeatherPolicy.STALE_AFTER_MILLIS)
    }

    @Test
    fun `wspolrzedne sa zaokraglane do dwoch miejsc`() {
        assertEquals(50.06, WeatherPlace.roundCoordinate(50.06143), 1e-9)
        assertEquals(19.94, WeatherPlace.roundCoordinate(19.9366), 1e-9)
        assertEquals(-33.87, WeatherPlace.roundCoordinate(-33.8688), 1e-9)
        val rounded = WeatherPlace("Kraków", "Małopolskie, Polska", 50.06143, 19.93658).rounded()
        assertEquals(50.06, rounded.latitude, 1e-9)
        assertEquals(19.94, rounded.longitude, 1e-9)
    }

    @Test
    fun `zapis miejscowosci przechodzi tam i z powrotem`() {
        val place = WeatherPlace("Gdańsk", "Pomorskie, Polska", 54.35, 18.65)
        assertEquals(place, WeatherPlace.decode(place.encode()))
        assertEquals(place.copy(detail = ""), WeatherPlace.decode(place.copy(detail = "").encode()))
    }

    @Test
    fun `zapis nie zawiera znakow rozdzielajacych w nazwie`() {
        val place = WeatherPlace("Nowy\tSącz\n", "Małopolskie\r\nPolska", -12.5, 100.0)
        val decoded = WeatherPlace.decode(place.encode())
        assertEquals("Nowy Sącz", decoded?.name)
        assertEquals("Małopolskie Polska", decoded?.detail)
        assertEquals(-12.5, decoded?.latitude)
    }

    @Test
    fun `nieczytelny zapis miejscowosci daje null`() {
        val broken = listOf(
            null, "", "1", "2\t50.00\t19.00\tKraków\t", "1\tx\t19.00\tKraków\t", "1\t95.00\t19.00\tKraków\t",
            "1\t50.00\t190.00\tKraków\t", "1\t50.00\t19.00\t \t", "1\t50.00\t19.00",
        )
        for (text in broken) assertNull(WeatherPlace.decode(text), "zapis: $text")
    }

    @Test
    fun `ustawienia pamietaja miejscowosc zaokraglona i pozwalaja ja usunac`() {
        val store = InMemoryKeyValueStore()
        val settings = AppSettings(store, FakeCipher())
        assertNull(settings.weatherPlace)

        settings.weatherPlace = WeatherPlace("Kraków", "Małopolskie, Polska", 50.06143, 19.93658)
        // po ponownym uruchomieniu (nowy obiekt na tej samej pamięci) miejscowość wraca, ze współrzędnymi do 2 miejsc
        val restored = AppSettings(store, FakeCipher()).weatherPlace
        assertEquals(WeatherPlace("Kraków", "Małopolskie, Polska", 50.06, 19.94), restored)

        settings.weatherPlace = null
        assertNull(AppSettings(store, FakeCipher()).weatherPlace)
    }

    @Test
    fun `w ustawieniach nie ma dokladnych wspolrzednych`() {
        val store = InMemoryKeyValueStore()
        AppSettings(store, FakeCipher()).weatherPlace = WeatherPlace("Kraków", "", 50.0614312, 19.9365812)
        val stored = store.getString("weather.place").orEmpty()
        assertTrue("50.06" in stored && "19.94" in stored, stored)
        assertFalse("50.0614" in stored || "19.9365" in stored, stored)
    }
}
