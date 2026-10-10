package app.domik.ui.theme

import androidx.annotation.StringRes
import app.domik.R

/** Ustawienie wyglądu urządzenia: tryb jasny/ciemny + paleta akcentu. */
data class AppearanceSettings(
    val mode: ThemeMode = ThemeMode.System,
    val palette: AccentPalette = AccentPalette.Default,
)

/** Nazwa palety w interfejsie (zasób tekstowy). */
@get:StringRes
val AccentPalette.labelRes: Int
    get() = when (this) {
        AccentPalette.Green -> R.string.palette_green
        AccentPalette.Mint -> R.string.palette_mint
        AccentPalette.Blue -> R.string.palette_blue
        AccentPalette.Indigo -> R.string.palette_indigo
        AccentPalette.Violet -> R.string.palette_violet
        AccentPalette.Pink -> R.string.palette_pink
        AccentPalette.Coral -> R.string.palette_coral
        AccentPalette.Amber -> R.string.palette_amber
        AccentPalette.Graphite -> R.string.palette_graphite
        AccentPalette.Dynamic -> R.string.palette_dynamic
    }
