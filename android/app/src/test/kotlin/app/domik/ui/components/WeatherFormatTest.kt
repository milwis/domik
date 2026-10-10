package app.domik.ui.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WeatherFormatTest {
    @Test
    fun `temperatura jest zaokraglana do pelnych stopni`() {
        assertEquals("14°", formatWholeTemperature(14.4f))
        assertEquals("15°", formatWholeTemperature(14.5f))
        assertEquals("-2°", formatWholeTemperature(-1.6f))
        assertEquals("0°", formatWholeTemperature(0f))
    }

    @Test
    fun `male wartosci ujemne nie daja minus zera`() {
        assertEquals("0°", formatWholeTemperature(-0.4f))
        assertEquals("0°", formatWholeTemperature(-0.0f))
    }

    @Test
    fun `brak odczytu to kreska`() {
        assertEquals("–°", formatWholeTemperature(null))
    }

    @Test
    fun `suma opadow ma przecinek wg jezyka i pelne milimetry od dziesieciu`() {
        val pl = java.util.Locale.forLanguageTag("pl-PL")
        assertEquals("0", formatPrecipitation(0f, pl))
        assertEquals("0", formatPrecipitation(0.04f, pl))
        assertEquals("0,4", formatPrecipitation(0.4f, pl))
        assertEquals("2,4", formatPrecipitation(2.44f, pl))
        assertEquals("2.4", formatPrecipitation(2.44f, java.util.Locale.ENGLISH))
        assertEquals("12", formatPrecipitation(12.3f, pl))
    }
}
