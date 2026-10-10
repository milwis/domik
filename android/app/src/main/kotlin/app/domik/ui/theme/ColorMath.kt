package app.domik.ui.theme

import kotlin.math.pow
import kotlin.math.roundToInt

/*
 * Czysta matematyka kolorów na liczbach ARGB (Int) – bez zależności od Compose/Androida, dzięki czemu
 * tokeny kolorów i palet da się sprawdzać zwykłymi testami JVM (kontrast WCAG).
 */

/** Kolor 0xRRGGBB → ARGB z pełną nieprzezroczystością. */
internal fun rgb(hex: Int): Int = (0xFF shl 24) or (hex and 0xFFFFFF)

/** Kolor 0xRRGGBB + przezroczystość 0..1 → ARGB. */
internal fun rgba(hex: Int, alpha: Float): Int =
    ((alpha.coerceIn(0f, 1f) * 255f).roundToInt() shl 24) or (hex and 0xFFFFFF)

private fun channel(argb: Int, shift: Int): Int = (argb ushr shift) and 0xFF

/** Mieszanie liniowe w sRGB (jak `lerp` z Compose, bez korekcji gamma): `t` = udział [to]. */
internal fun blendArgb(from: Int, to: Int, t: Float): Int {
    val k = t.coerceIn(0f, 1f)
    fun mix(shift: Int): Int =
        (channel(from, shift) + (channel(to, shift) - channel(from, shift)) * k).roundToInt()
    return (mix(24) shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
}

/** Kolor [fg] (może być półprzezroczysty) nałożony na nieprzezroczyste tło [bg] → nieprzezroczysty wynik. */
internal fun compositeOver(fg: Int, bg: Int): Int {
    val a = channel(fg, 24) / 255f
    return blendArgb(bg or (0xFF shl 24), fg or (0xFF shl 24), a) or (0xFF shl 24)
}

/** Luminancja względna wg WCAG 2.x (kanał alfa ignorowany). */
internal fun relativeLuminance(argb: Int): Double {
    fun lin(shift: Int): Double {
        val c = channel(argb, shift) / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * lin(16) + 0.7152 * lin(8) + 0.0722 * lin(0)
}

/** Współczynnik kontrastu WCAG 2.x (1..21) dla dwóch nieprzezroczystych kolorów. */
internal fun contrastRatio(a: Int, b: Int): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    val hi = maxOf(la, lb)
    val lo = minOf(la, lb)
    return (hi + 0.05) / (lo + 0.05)
}

private const val NEAR_BLACK = 0xFF10141B.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()

/** Czytelny kolor treści (biały albo prawie czarny) na tle [background] – ten o większym kontraście. */
internal fun onColorFor(background: Int): Int =
    if (contrastRatio(WHITE, background) >= contrastRatio(NEAR_BLACK, background)) WHITE else NEAR_BLACK
