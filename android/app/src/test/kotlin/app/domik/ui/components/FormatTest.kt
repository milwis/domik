package app.domik.ui.components

import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FormatTest {
    private val pl = Locale.forLanguageTag("pl-PL")

    @Test
    fun `temperatura z przecinkiem po polsku`() {
        assertEquals("21,5°", formatTemperature(21.5f, pl))
        assertEquals("22,0°", formatTemperature(22.0f, pl))
        assertEquals("-3,5°", formatTemperature(-3.5f, pl))
    }

    @Test
    fun `temperatura z kropka po angielsku`() {
        assertEquals("21.5°", formatTemperature(21.5f, Locale.US))
    }

    @Test
    fun `brak odczytu`() {
        assertEquals("–°", formatTemperature(null, pl))
    }

    @Test
    fun `godzina w formacie 24h`() {
        assertEquals("09:05", formatClock(9, 5))
        assertEquals("23:59", formatClock(23, 59))
    }
}
