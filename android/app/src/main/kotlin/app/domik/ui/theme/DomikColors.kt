package app.domik.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Tokeny kolorów Domika: wartość = f(paleta akcentu, jasny/ciemny). Dostęp: [DomikTheme.colors].
 * Komponenty nie zawierają żadnych zahardkodowanych kolorów – tylko te tokeny.
 */
@Immutable
data class DomikColors(
    val isDark: Boolean,
    /** Ikona na włączonym kafelku, przełączniki, wybór. */
    val accent: Color,
    /** Treść (tekst/ikona) na tle [accent] – np. przycisk „Dziś”. */
    val onAccent: Color,
    /** Gradient włączonego kafelka: początek i koniec. */
    val tileOnStart: Color,
    val tileOnEnd: Color,
    /** Nazwa/pomieszczenie na włączonym kafelku. */
    val textOnTile: Color,
    /** Karta kafelków bez stanu (ogrzewanie, pogoda, kalendarz) i arkuszy. */
    val card: Color,
    /** Karta wyłączonego kafelka (półprzezroczysta). */
    val cardOff: Color,
    val cardBorder: Color,
    /** Kolor cienia karty (przezroczystość nakłada komponent). */
    val shadow: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    /** Tytuł wyłączonego kafelka (nieco słabszy niż [textPrimary]). */
    val textTileOff: Color,
    val iconOff: Color,
    /** „Grzeje” – semantyczny, nie zależy od palety. */
    val heating: Color,
    /** Profil eco / chłodniej – semantyczny. */
    val cool: Color,
    /** Szansa opadów (tekst na karcie pogody) – [cool] z domieszką koloru tekstu dla kontrastu. */
    val precipitation: Color,
    val error: Color,
    val onError: Color,
    /** Pasek ostrzeżenia (nieaktualne dane eModul) i tekst na nim. */
    val warning: Color,
    val onWarning: Color,
    val backgroundTop: Color,
    val backgroundBottom: Color,
) {
    /** Gradient włączonego kafelka. */
    val tileOn: Brush get() = Brush.linearGradient(listOf(tileOnStart, tileOnEnd))

    /** Tło ekranu: chłodny gradient, lekko zabarwiony akcentem. */
    val background: Brush get() = Brush.verticalGradient(listOf(backgroundTop, backgroundBottom))

    companion object {
        /** Składa tokeny z [BaseTokens] motywu i [PaletteVariant] wybranej palety. */
        fun of(dark: Boolean, variant: PaletteVariant): DomikColors {
            val base = DomikTokens.of(dark)
            val tint = DomikTokens.BACKGROUND_ACCENT_TINT
            return DomikColors(
                isDark = dark,
                accent = Color(variant.accent),
                onAccent = Color(onColorFor(variant.accent)),
                tileOnStart = Color(variant.tileOnStart),
                tileOnEnd = Color(variant.tileOnEnd),
                textOnTile = Color(variant.textOnTile),
                card = Color(base.card),
                cardOff = Color(base.cardOff),
                cardBorder = Color(base.cardBorder),
                shadow = Color(base.shadow),
                textPrimary = Color(base.textPrimary),
                textSecondary = Color(base.textSecondary),
                textTileOff = Color(base.textTileOff),
                iconOff = Color(base.iconOff),
                heating = Color(base.heating),
                cool = Color(base.cool),
                precipitation = Color(base.precipitationText),
                error = Color(base.error),
                onError = Color(onColorFor(base.error)),
                warning = Color(base.warning),
                onWarning = Color(onColorFor(base.warning)),
                backgroundTop = Color(tintedBackground(base.backgroundTop, variant.accent, tint)),
                backgroundBottom = Color(tintedBackground(base.backgroundBottom, variant.accent, tint)),
            )
        }
    }
}

/** Tło ekranu zabarwione akcentem (udział [tint]); wspólne dla motywu i testów kontrastu. */
internal fun tintedBackground(base: Int, accent: Int, tint: Float): Int = blendArgb(base, accent, tint)

/** Czas animacji zmiany koloru/palety/motywu. */
private const val COLOR_ANIMATION_MS = 300

/** Zwraca kolory płynnie dochodzące do wartości docelowych (zmiana palety/motywu bez restartu aktywności). */
@Composable
internal fun DomikColors.animated(): DomikColors {
    val spec = tween<Color>(COLOR_ANIMATION_MS)

    @Composable
    fun Color.a(label: String): Color = animateColorAsState(this, spec, label = label).value
    return DomikColors(
        isDark = isDark,
        accent = accent.a("accent"),
        onAccent = onAccent.a("onAccent"),
        tileOnStart = tileOnStart.a("tileOnStart"),
        tileOnEnd = tileOnEnd.a("tileOnEnd"),
        textOnTile = textOnTile.a("textOnTile"),
        card = card.a("card"),
        cardOff = cardOff.a("cardOff"),
        cardBorder = cardBorder.a("cardBorder"),
        shadow = shadow.a("shadow"),
        textPrimary = textPrimary.a("textPrimary"),
        textSecondary = textSecondary.a("textSecondary"),
        textTileOff = textTileOff.a("textTileOff"),
        iconOff = iconOff.a("iconOff"),
        heating = heating.a("heating"),
        cool = cool.a("cool"),
        precipitation = precipitation.a("precipitation"),
        error = error.a("error"),
        onError = onError.a("onError"),
        warning = warning.a("warning"),
        onWarning = onWarning.a("onWarning"),
        backgroundTop = backgroundTop.a("backgroundTop"),
        backgroundBottom = backgroundBottom.a("backgroundBottom"),
    )
}

internal val LocalDomikColors = compositionLocalOf {
    DomikColors.of(dark = false, variant = requireNotNull(AccentPalette.Default.light))
}
