package app.domik.ui.theme

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Kontrast WCAG 2.x tokenów kolorów i palet akcentu – dla każdej palety, w obu motywach:
 *  - tekst na włączonym kafelku (cały gradient) ≥ 4,5:1, także druga linia (pomieszczenie) z przezroczystością,
 *  - ikona w kolorze akcentu na włączonym kafelku ≥ 3:1 (i na zwykłej karcie – ikony kafelków bez stanu),
 *  - tekst na karcie / na przygaszonej karcie ≥ 4,5:1.
 */
class PaletteContrastTest {

    private data class Case(val palette: AccentPalette, val dark: Boolean, val variant: PaletteVariant)

    private val cases: List<Case> = AccentPalette.entries.flatMap { palette ->
        listOf(false, true).mapNotNull { dark -> palette.variant(dark)?.let { Case(palette, dark, it) } }
    }

    private fun label(case: Case) = "${case.palette}/${if (case.dark) "ciemny" else "jasny"}"

    private fun check(name: String, minimum: Double, actual: Double, case: Case) {
        assertTrue(actual >= minimum, "${label(case)}: $name = ${"%.2f".format(actual)} < $minimum")
    }

    @Test
    fun `tekst na wlaczonym kafelku co najmniej 4,5`() {
        for (c in cases) {
            val v = c.variant
            check("textOnTile/start", 4.5, contrastRatio(v.textOnTile, v.tileOnStart), c)
            check("textOnTile/end", 4.5, contrastRatio(v.textOnTile, v.tileOnEnd), c)
        }
    }

    @Test
    fun `pomieszczenie na wlaczonym kafelku (tekst z przezroczystoscia) co najmniej 4,5`() {
        for (c in cases) {
            val v = c.variant
            val withAlpha = rgba(v.textOnTile, DomikTokens.SUBTITLE_ON_TILE_ALPHA)
            for (stop in listOf(v.tileOnStart, v.tileOnEnd)) {
                check("subtitle/$stop", 4.5, contrastRatio(compositeOver(withAlpha, stop), stop), c)
            }
        }
    }

    @Test
    fun `ikona w kolorze akcentu na wlaczonym kafelku co najmniej 3`() {
        for (c in cases) {
            val v = c.variant
            check("accent/start", 3.0, contrastRatio(v.accent, v.tileOnStart), c)
            check("accent/end", 3.0, contrastRatio(v.accent, v.tileOnEnd), c)
        }
    }

    @Test
    fun `ikona w kolorze akcentu na zwyklej karcie co najmniej 3`() {
        for (c in cases) {
            check("accent/card", 3.0, contrastRatio(c.variant.accent, DomikTokens.of(c.dark).card), c)
        }
    }

    @Test
    fun `tekst na karcie co najmniej 4,5`() {
        for (dark in listOf(false, true)) {
            val tokens = DomikTokens.of(dark)
            for ((name, color) in listOf(
                "textPrimary" to tokens.textPrimary,
                "textSecondary" to tokens.textSecondary,
                "textTileOff" to tokens.textTileOff,
            )) {
                assertTrue(
                    contrastRatio(color, tokens.card) >= 4.5,
                    "$name na karcie (dark=$dark): ${contrastRatio(color, tokens.card)}",
                )
            }
        }
    }

    @Test
    fun `tekst na przygaszonej polprzezroczystej karcie co najmniej 4,5 na calym tle`() {
        for (c in cases) {
            val tokens = DomikTokens.of(c.dark)
            val tint = DomikTokens.BACKGROUND_ACCENT_TINT
            for (bg in listOf(tokens.backgroundTop, tokens.backgroundBottom)) {
                val background = tintedBackground(bg, c.variant.accent, tint)
                val card = compositeOver(tokens.cardOff, background)
                for ((name, color) in listOf(
                    "textPrimary" to tokens.textPrimary,
                    "textSecondary" to tokens.textSecondary,
                    "textTileOff" to tokens.textTileOff,
                )) {
                    check("$name/cardOff", 4.5, contrastRatio(color, card), c)
                }
            }
        }
    }

    @Test
    fun `tekst przycisku Dzis i znacznika teraz (textOnTile na tileOnEnd) co najmniej 4,5`() {
        for (c in cases) {
            check("textOnTile/tileOnEnd", 4.5, contrastRatio(c.variant.textOnTile, c.variant.tileOnEnd), c)
        }
    }

    @Test
    fun `tekst na pasku bledu co najmniej 4,5`() {
        for (dark in listOf(false, true)) {
            val tokens = DomikTokens.of(dark)
            val onError = onColorFor(tokens.error)
            assertTrue(contrastRatio(onError, tokens.error) >= 4.5, "error (dark=$dark)")
        }
    }

    @Test
    fun `tekst na pasku ostrzezenia co najmniej 4,5`() {
        for (dark in listOf(false, true)) {
            val tokens = DomikTokens.of(dark)
            val onWarning = onColorFor(tokens.warning)
            assertTrue(contrastRatio(onWarning, tokens.warning) >= 4.5, "warning (dark=$dark)")
        }
    }

    @Test
    fun `tresc na kolorze akcentu co najmniej 3`() {
        for (c in cases) {
            check("onAccent", 3.0, contrastRatio(onColorFor(c.variant.accent), c.variant.accent), c)
        }
    }
}
