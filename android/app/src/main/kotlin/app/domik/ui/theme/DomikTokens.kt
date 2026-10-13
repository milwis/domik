package app.domik.ui.theme

/**
 * Tokeny niezależne od palety akcentu, osobno dla motywu jasnego i ciemnego.
 * Czysty Kotlin (ARGB jako Int) – sprawdzane testem `PaletteContrastTest`.
 *
 * Wartości skorygowane względem pierwotnego projektu ze względu na kontrast WCAG (test `PaletteContrastTest`):
 *  - `textSecondary` jasny `#7B828D` → `#676D78` (tekst pomieszczenia ≥ 4,5:1 na białej i półprzezroczystej karcie),
 *  - `error` jasny `#D9434B` → `#D73B44` (biały tekst paska połączenia ≥ 4,5:1).
 */
data class BaseTokens(
    val backgroundTop: Int,
    val backgroundBottom: Int,
    val card: Int,
    /** Karta wyłączonego kafelka – półprzezroczysta (kanał alfa w wartości). */
    val cardOff: Int,
    /** Obrys karty (ciemny motyw zamiast cienia); jasny: brak. */
    val cardBorder: Int,
    /** Kolor cienia karty (jasny motyw); ciemny: bez cieni. */
    val shadow: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val iconOff: Int,
    val heating: Int,
    val cool: Int,
    val error: Int,
    /** Pasek ostrzeżenia (np. nieaktualne dane eModul) – bursztyn, łagodniejszy niż [error]. */
    val warning: Int,
) {
    /** Karta wyłączonego kafelka złożona na dolnym (ciemniejszym w jasnym motywie) końcu tła. */
    val cardOffOnBackground: Int get() = compositeOver(cardOff, backgroundBottom)

    /** Tytuł wyłączonego kafelka – „lekko mniejszy kontrast” niż [textPrimary]. */
    val textTileOff: Int get() = blendArgb(textPrimary, textSecondary, TEXT_OFF_BLEND)

    /** Szansa opadów w kafelku pogody: niebieski [cool] przyciemniony (jasny motyw) lub rozjaśniony (ciemny) o [textPrimary] – tekst ≥ 4,5:1 na karcie. */
    val precipitationText: Int get() = blendArgb(cool, textPrimary, PRECIPITATION_BLEND)

    companion object {
        const val TEXT_OFF_BLEND = 0.35f
        const val PRECIPITATION_BLEND = 0.35f
    }
}

object DomikTokens {
    val Light = BaseTokens(
        backgroundTop = rgb(0xEEF4FD),
        backgroundBottom = rgb(0xD6E5FB),
        card = rgb(0xFFFFFF),
        cardOff = rgba(0xFFFFFF, 0.60f),
        cardBorder = rgba(0xFFFFFF, 0f), // brak obrysu
        shadow = rgba(0x1C2B4A, 1f), // nakładany z przezroczystością ~6 % w TileCard
        textPrimary = rgb(0x1B1F26),
        textSecondary = rgb(0x676D78),
        iconOff = rgb(0x9AA1AC),
        heating = rgb(0xF08A3C),
        cool = rgb(0x4C8DF6),
        error = rgb(0xD73B44),
        warning = rgb(0xF5B83D),
    )

    val Dark = BaseTokens(
        backgroundTop = rgb(0x0E131C),
        backgroundBottom = rgb(0x141C2B),
        card = rgb(0x1B2230),
        cardOff = rgba(0x1B2230, 0.70f),
        cardBorder = rgba(0xFFFFFF, 0.06f),
        shadow = rgba(0x000000, 0f), // bez cieni
        textPrimary = rgb(0xE9EDF3),
        textSecondary = rgb(0x8E97A5),
        iconOff = rgb(0x6E7787),
        heating = rgb(0xFFA45C),
        cool = rgb(0x6FA5FF),
        error = rgb(0xFF6B70),
        warning = rgb(0xF5B83D),
    )

    fun of(dark: Boolean): BaseTokens = if (dark) Dark else Light

    /** Udział koloru akcentu w tle ekranu (ok. 4 %). */
    const val BACKGROUND_ACCENT_TINT = 0.04f

    /** Przezroczystość tekstu pomieszczenia na włączonym kafelku (kontrast sprawdzany w `PaletteContrastTest`). */
    const val SUBTITLE_ON_TILE_ALPHA = 0.85f
}
