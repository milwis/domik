package app.domik.ui.theme

import kotlin.math.abs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ColorMathTest {
    @Test
    fun `kontrast czerni i bieli to 21`() {
        assertEquals(21.0, contrastRatio(rgb(0x000000), rgb(0xFFFFFF)), 1e-9)
        assertEquals(1.0, contrastRatio(rgb(0x7B828D), rgb(0x7B828D)), 1e-9)
    }

    @Test
    fun `kontrast jest symetryczny`() {
        assertEquals(
            contrastRatio(rgb(0x1E9E5A), rgb(0xE2F6EA)),
            contrastRatio(rgb(0xE2F6EA), rgb(0x1E9E5A)),
            1e-12,
        )
    }

    @Test
    fun `skladanie polprzezroczystego koloru na tle`() {
        // 50 % bieli na czerni = szary ~#808080
        val result = compositeOver(rgba(0xFFFFFF, 0.5f), rgb(0x000000))
        assertEquals(0x80, result and 0xFF)
        assertEquals(0xFF, result ushr 24)
    }

    @Test
    fun `mieszanie krancowe`() {
        assertEquals(rgb(0x102030), blendArgb(rgb(0x102030), rgb(0xFFFFFF), 0f))
        assertEquals(rgb(0xFFFFFF), blendArgb(rgb(0x102030), rgb(0xFFFFFF), 1f))
        val mid = blendArgb(rgb(0x000000), rgb(0xFFFFFF), 0.5f)
        assertEquals(true, abs((mid and 0xFF) - 128) <= 1)
    }

    @Test
    fun `czytelny kolor treści wybiera ciemny na jasnym i jasny na ciemnym`() {
        assertEquals(0xFF10141B.toInt(), onColorFor(rgb(0xFFF4D6)))
        assertEquals(0xFFFFFFFF.toInt(), onColorFor(rgb(0x1A4A33)))
    }
}
