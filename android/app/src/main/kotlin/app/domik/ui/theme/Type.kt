package app.domik.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/*
 * Typografia: czcionka systemowa (zero plików czcionek),
 * temperatura 40–56 sp (Light), nazwa kafelka 17–20 sp SemiBold, pomieszczenie 14–16 sp Regular,
 * nazwa domu w nagłówku 20–24 sp SemiBold. Czytelność z 2–3 m: nazwy ≥ 18 sp.
 */
private val SystemFont = FontFamily.Default

internal val DomikTypography = Typography(
    // Temperatura aktualna (kafelek ogrzewania L/M).
    displayLarge = TextStyle(fontFamily = SystemFont, fontWeight = FontWeight.Light, fontSize = 56.sp, lineHeight = 60.sp),
    displayMedium = TextStyle(fontFamily = SystemFont, fontWeight = FontWeight.Light, fontSize = 48.sp, lineHeight = 52.sp),
    displaySmall = TextStyle(fontFamily = SystemFont, fontWeight = FontWeight.Light, fontSize = 40.sp, lineHeight = 44.sp),
    headlineLarge = TextStyle(fontFamily = SystemFont, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 38.sp),
    headlineMedium = TextStyle(fontFamily = SystemFont, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp),
    headlineSmall = TextStyle(fontFamily = SystemFont, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
    // Nazwa domu w nagłówku.
    titleLarge = TextStyle(fontFamily = SystemFont, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    // Nazwa kafelka.
    titleMedium = TextStyle(fontFamily = SystemFont, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = SystemFont, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = SystemFont, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 24.sp),
    // Pomieszczenie / opis.
    bodyMedium = TextStyle(fontFamily = SystemFont, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = SystemFont, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = SystemFont, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = SystemFont, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontFamily = SystemFont, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
)
