package app.domik.ui.theme

/**
 * Wariant palety akcentu dla jednego motywu (jasnego albo ciemnego) – kolory jako ARGB (Int),
 * żeby dane były czystym Kotlinem (testy kontrastu bez Androida).
 *
 * @property accent ikona na włączonym kafelku, przełączniki, wybór
 * @property tileOnStart początek gradientu włączonego kafelka
 * @property tileOnEnd koniec gradientu włączonego kafelka
 * @property textOnTile nazwa/pomieszczenie na włączonym kafelku
 */
data class PaletteVariant(
    val accent: Int,
    val tileOnStart: Int,
    val tileOnEnd: Int,
    val textOnTile: Int,
)

/**
 * Paleta koloru akcentu (wybór użytkownika). Motyw jasny/ciemny jest niezależny od palety.
 * [Dynamic] = „kolory z tapety” (Android 12+, Material You); poniżej API 31 działa jak [Default].
 *
 * Wartości `accent` w motywie jasnym są o kilka % ciemniejsze niż w pierwotnym projekcie palety –
 * korekta, by ikona w kolorze akcentu miała kontrast ≥ 3:1 na całym gradiencie włączonego kafelka
 * (test `PaletteContrastTest`).
 */
enum class AccentPalette(val light: PaletteVariant?, val dark: PaletteVariant?) {
    Green(
        light = PaletteVariant(rgb(0x1C9253), rgb(0xE2F6EA), rgb(0xBFEBD1), rgb(0x0F3D24)),
        dark = PaletteVariant(rgb(0x3DD68C), rgb(0x1A4A33), rgb(0x123726), rgb(0xDDF7E8)),
    ),
    Mint(
        light = PaletteVariant(rgb(0x108F7C), rgb(0xDDF5F0), rgb(0xB8EBE1), rgb(0x0B3D35)),
        dark = PaletteVariant(rgb(0x34D1B6), rgb(0x15473F), rgb(0x10342F), rgb(0xD9F7F1)),
    ),
    Blue(
        light = PaletteVariant(rgb(0x2078DD), rgb(0xE1EEFD), rgb(0xBFDAFB), rgb(0x0E2E57)),
        dark = PaletteVariant(rgb(0x5AA2FF), rgb(0x1A3A63), rgb(0x132B4A), rgb(0xDCEBFF)),
    ),
    Indigo(
        light = PaletteVariant(rgb(0x4F5BD5), rgb(0xE7E9FC), rgb(0xC9CEF8), rgb(0x1E2466)),
        dark = PaletteVariant(rgb(0x8B95FF), rgb(0x262C63), rgb(0x1C204A), rgb(0xE3E6FF)),
    ),
    Violet(
        light = PaletteVariant(rgb(0x8A4FD8), rgb(0xF0E6FC), rgb(0xDCC8F8), rgb(0x341A5C)),
        dark = PaletteVariant(rgb(0xB98BFF), rgb(0x3A2560), rgb(0x2A1B47), rgb(0xEEE3FF)),
    ),
    Pink(
        light = PaletteVariant(rgb(0xCF3C77), rgb(0xFCE6EE), rgb(0xF7C5D7), rgb(0x5C1532)),
        dark = PaletteVariant(rgb(0xFF85B3), rgb(0x5A1F38), rgb(0x42172A), rgb(0xFFE3EE)),
    ),
    Coral(
        light = PaletteVariant(rgb(0xD34B21), rgb(0xFDE9E2), rgb(0xF9CDBD), rgb(0x5A200D)),
        dark = PaletteVariant(rgb(0xFF8E66), rgb(0x5A2617), rgb(0x421C11), rgb(0xFFE6DC)),
    ),
    Amber(
        light = PaletteVariant(rgb(0xB57500), rgb(0xFFF4D6), rgb(0xFFE3A3), rgb(0x3A2A00)),
        dark = PaletteVariant(rgb(0xFFC24D), rgb(0x5A4417), rgb(0x3D2F14), rgb(0xFFF1CF)),
    ),
    Graphite(
        light = PaletteVariant(rgb(0x4A5361), rgb(0xF3F5F8), rgb(0xDDE2EA), rgb(0x1F242C)),
        dark = PaletteVariant(rgb(0xC3CAD5), rgb(0x3A4252), rgb(0x2B3240), rgb(0xEEF1F5)),
    ),

    /** Kolory z tapety – wartości liczone w czasie działania ([light]/[dark] = null). Android 12+. */
    Dynamic(light = null, dark = null);

    /** Wariant dla motywu; `null` dla [Dynamic] (wyliczany z `dynamic*ColorScheme`). */
    fun variant(dark: Boolean): PaletteVariant? = if (dark) this.dark else this.light

    companion object {
        /** Domyślna paleta (decyzja użytkownika 2026-10-10). */
        val Default: AccentPalette = Green

        /** Pierwsze API z dynamicznymi kolorami (Android 12). */
        const val DYNAMIC_MIN_SDK = 31

        /** Palety do wyboru na urządzeniu z danym API: [Dynamic] tylko od Androida 12. */
        fun availableOn(sdkInt: Int): List<AccentPalette> =
            if (sdkInt >= DYNAMIC_MIN_SDK) entries else entries.filter { it != Dynamic }

        /** Odczyt zapisanej nazwy; przy braku/nieznanej wartości – [Default]. */
        fun fromName(name: String?): AccentPalette = entries.firstOrNull { it.name == name } ?: Default
    }
}
