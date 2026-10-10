package app.domik.ui.components

import java.util.Locale

/**
 * Temperatura z jedną cyfrą po przecinku i znakiem stopnia: „21,5°” (przecinek dziesiętny wg języka –
 * po polsku przecinek, po angielsku kropka). `null` = brak odczytu → „–°”.
 */
fun formatTemperature(value: Float?, locale: Locale = Locale.getDefault()): String =
    if (value == null) "–°" else String.format(locale, "%.1f°", value)

/** Czas wydarzenia „HH:mm” (24 h, niezależnie od ustawień regionalnych). */
fun formatClock(hour: Int, minute: Int): String = String.format(Locale.ROOT, "%02d:%02d", hour, minute)
