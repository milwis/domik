package app.domik.integrations.weather

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WeatherOptionsTest {
    @Test
    fun `domyslnie wlaczone sa godziny dni i szansa opadow a szczegoly wylaczone`() {
        val options = WeatherOptions.DEFAULT
        assertTrue(WeatherOption.Hourly in options)
        assertTrue(WeatherOption.Daily in options)
        assertTrue(WeatherOption.PrecipitationChance in options)
        for (detail in WeatherOptions.DETAIL_OPTIONS) assertFalse(detail in options, detail.name)
        assertTrue(options.details.isEmpty())
    }

    @Test
    fun `with wlacza i wylacza pojedyncza opcje bez zmiany pozostalych`() {
        val on = WeatherOptions.DEFAULT.with(WeatherOption.Wind, true)
        assertTrue(WeatherOption.Wind in on)
        assertTrue(WeatherOption.Hourly in on)
        val off = on.with(WeatherOption.Hourly, false)
        assertFalse(WeatherOption.Hourly in off)
        assertTrue(WeatherOption.Wind in off)
        assertEquals(off, off.with(WeatherOption.Hourly, false))
    }

    @Test
    fun `szczegoly sa w stalej kolejnosci niezaleznie od kolejnosci wlaczania`() {
        val options = WeatherOptions.DEFAULT
            .with(WeatherOption.Sun, true)
            .with(WeatherOption.FeelsLike, true)
            .with(WeatherOption.PrecipitationSum, true)
        assertEquals(listOf(WeatherOption.FeelsLike, WeatherOption.Sun, WeatherOption.PrecipitationSum), options.details)
    }

    @Test
    fun `zapis jest wersjonowany i deterministyczny`() {
        assertEquals("v1;hourly;daily;precip_chance", WeatherOptions.DEFAULT.encode())
        val a = WeatherOptions.DEFAULT.with(WeatherOption.Wind, true).with(WeatherOption.UvIndex, true)
        val b = WeatherOptions.DEFAULT.with(WeatherOption.UvIndex, true).with(WeatherOption.Wind, true)
        assertEquals(a.encode(), b.encode())
        assertEquals("v1;hourly;daily;precip_chance;wind;uv", a.encode())
    }

    @Test
    fun `kazda kombinacja opcji przechodzi zapis i odczyt bez zmian`() {
        val all = WeatherOption.entries
        for (mask in 0 until (1 shl all.size)) {
            val options = WeatherOptions(all.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.toSet())
            assertEquals(options, WeatherOptions.decode(options.encode()), "maska $mask")
        }
    }

    @Test
    fun `wszystko wylaczone to wazny zapis a nie ustawienia domyslne`() {
        val none = WeatherOptions(emptySet())
        assertEquals("v1", none.encode())
        assertEquals(none, WeatherOptions.decode("v1"))
    }

    @Test
    fun `brak uszkodzony albo obcy zapis daje ustawienia domyslne`() {
        assertEquals(WeatherOptions.DEFAULT, WeatherOptions.decode(null))
        assertEquals(WeatherOptions.DEFAULT, WeatherOptions.decode(""))
        assertEquals(WeatherOptions.DEFAULT, WeatherOptions.decode("   "))
        assertEquals(WeatherOptions.DEFAULT, WeatherOptions.decode("to nie jest zapis"))
        assertEquals(WeatherOptions.DEFAULT, WeatherOptions.decode("v2;wind"))
    }

    @Test
    fun `nieznane klucze sa pomijane a znane zostaja`() {
        val decoded = WeatherOptions.decode("v1;wind;nieznana;humidity;;hourly;wind")
        assertEquals(setOf(WeatherOption.Wind, WeatherOption.Humidity, WeatherOption.Hourly), decoded.enabled)
    }

    @Test
    fun `klucze zapisu sa unikalne`() {
        assertEquals(WeatherOption.entries.size, WeatherOption.entries.map { it.key }.toSet().size)
    }
}
