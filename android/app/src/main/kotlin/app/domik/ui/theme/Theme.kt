package app.domik.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import app.domik.ui.time.rememberNow

/** Dostęp do tokenów Domika w kompozycji: `DomikTheme.colors.accent`. */
object DomikTheme {
    val colors: DomikColors
        @Composable get() = LocalDomikColors.current
}

/**
 * Motyw aplikacji: tokeny [DomikColors] (CompositionLocal) + Material 3 `ColorScheme`
 * zmapowany z tych tokenów, do tego kształty ([DomikShapes]) i typografia. Zmiana palety lub trybu jest animowana.
 *
 * @param mode tryb jasny/ciemny ([ThemeMode.System] domyślnie)
 * @param palette kolor akcentu; [AccentPalette.Dynamic] („kolory z tapety”) tylko na API 31+, niżej Zieleń.
 *   Dynamic color jest domyślnie wyłączony – domyślna paleta to [AccentPalette.Default].
 */
@Composable
fun DomikTheme(
    mode: ThemeMode = ThemeMode.System,
    palette: AccentPalette = AccentPalette.Default,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val now by rememberNow(enabled = mode is ThemeMode.Scheduled)
    val dark = mode.isDark(now.toLocalTime(), systemDark)

    val context = LocalContext.current
    val variant = resolveVariant(palette, dark, context)
    val target = remember(dark, variant) { DomikColors.of(dark, variant) }
    val colors = target.animated()

    val materialScheme = remember(colors) { colors.toMaterialColorScheme() }

    SystemBarsAppearance(dark)

    CompositionLocalProvider(LocalDomikColors provides colors) {
        MaterialTheme(
            colorScheme = materialScheme,
            shapes = DomikMaterialShapes,
            typography = DomikTypography,
            content = content,
        )
    }
}

/** Tło ekranu – gradient z tokenów (lekko zabarwiony akcentem). */
@Composable
fun DomikBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.background(DomikTheme.colors.background), content = content)
}

/** Wariant palety: z wbudowanej tabeli palet albo z `dynamic*ColorScheme` (API 31+); inaczej Zieleń. */
@Composable
private fun resolveVariant(palette: AccentPalette, dark: Boolean, context: Context): PaletteVariant {
    if (palette == AccentPalette.Dynamic && Build.VERSION.SDK_INT >= AccentPalette.DYNAMIC_MIN_SDK) {
        val scheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        return remember(scheme) {
            PaletteVariant(
                accent = scheme.primary.toArgb(),
                tileOnStart = scheme.primaryContainer.toArgb(),
                tileOnEnd = scheme.primaryContainer.toArgb(),
                textOnTile = scheme.onPrimaryContainer.toArgb(),
            )
        }
    }
    val fixed = if (palette == AccentPalette.Dynamic) AccentPalette.Default else palette
    return requireNotNull(fixed.variant(dark))
}

/** Ikony pasków systemowych dopasowane do motywu aplikacji (a nie do motywu systemu). */
@Composable
private fun SystemBarsAppearance(dark: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Mapowanie tokenów na role Material 3 (komponenty M3 w arkuszach, przełącznikach itd.). */
internal fun DomikColors.toMaterialColorScheme(): ColorScheme {
    val secondaryContainer = lerp(card, accent, 0.16f)
    val surfaceVariant = lerp(card, backgroundBottom, 0.5f)
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = tileOnStart,
        onPrimaryContainer = textOnTile,
        inversePrimary = accent,
        secondary = accent,
        onSecondary = onAccent,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = textPrimary,
        tertiary = heating,
        onTertiary = Color(onColorFor(heating.toArgb())),
        tertiaryContainer = lerp(card, heating, 0.18f),
        onTertiaryContainer = textPrimary,
        error = error,
        onError = onError,
        errorContainer = lerp(card, error, 0.16f),
        onErrorContainer = textPrimary,
        background = lerp(backgroundTop, backgroundBottom, 0.5f),
        onBackground = textPrimary,
        surface = card,
        onSurface = textPrimary,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = textSecondary,
        surfaceTint = accent,
        inverseSurface = textPrimary,
        inverseOnSurface = card,
        outline = textSecondary.copy(alpha = 0.55f),
        outlineVariant = textSecondary.copy(alpha = 0.25f),
        scrim = Color.Black,
        surfaceBright = card,
        surfaceDim = surfaceVariant,
        surfaceContainerLowest = card,
        surfaceContainerLow = card,
        surfaceContainer = card,
        surfaceContainerHigh = card,
        surfaceContainerHighest = surfaceVariant,
    )
}
