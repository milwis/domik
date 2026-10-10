package app.domik.ui.components

/**
 * Temperatura pogody w pełnych stopniach: „14°” (zaokrąglenie do najbliższej liczby całkowitej, więc „-0°” nie
 * występuje). `null` = brak odczytu → „–°”.
 */
fun formatWholeTemperature(value: Float?): String = if (value == null) "–°" else "${Math.round(value)}°"

/**
 * Suma opadów w milimetrach: poniżej 0,05 mm „0”, do 10 mm z jedną cyfrą po przecinku („2,4” po polsku, „2.4” po
 * angielsku – wg [locale]), od 10 mm pełne milimetry.
 */
fun formatPrecipitation(mm: Float, locale: java.util.Locale = java.util.Locale.getDefault()): String = when {
    mm < 0.05f -> "0"
    mm < 10f -> String.format(locale, "%.1f", mm)
    else -> String.format(locale, "%.0f", mm)
}
