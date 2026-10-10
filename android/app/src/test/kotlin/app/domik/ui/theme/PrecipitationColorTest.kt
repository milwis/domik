package app.domik.ui.theme

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Kolor szansy opadów (kafelek pogody) to niebieski [BaseTokens.cool] z domieszką tekstu – ma być czytelny jak tekst. */
class PrecipitationColorTest {
    @Test
    fun `szansa opadow na karcie ma kontrast co najmniej 4,5`() {
        for (dark in listOf(false, true)) {
            val tokens = DomikTokens.of(dark)
            val ratio = contrastRatio(tokens.precipitationText, tokens.card)
            assertTrue(ratio >= 4.5, "kontrast $ratio (dark=$dark)")
        }
    }

    @Test
    fun `szansa opadow jest niebieska a nie szara`() {
        for (dark in listOf(false, true)) {
            val color = DomikTokens.of(dark).precipitationText
            val red = (color shr 16) and 0xFF
            val blue = color and 0xFF
            assertTrue(blue - red >= 40, "dark=$dark: ${Integer.toHexString(color)}")
        }
    }
}
