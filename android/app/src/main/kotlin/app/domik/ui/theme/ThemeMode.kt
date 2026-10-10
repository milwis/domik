package app.domik.ui.theme

import java.time.LocalTime

/**
 * Tryb jasny/ciemny. Niezależny od wyboru koloru ([AccentPalette]).
 * Zapis ustawienia: [encode] / [decode] (np. do DataStore).
 */
sealed interface ThemeMode {

    /** Zgodnie z ustawieniem systemu (domyślnie). */
    data object System : ThemeMode

    data object Light : ThemeMode

    data object Dark : ThemeMode

    /**
     * Ciemny w godzinach [darkFrom]..[darkUntil] (przedział półotwarty, może przechodzić przez północ),
     * poza nimi jasny. Dla tabletu na ścianie, który nie zawsze ma systemowy tryb nocny.
     * Przedział pusty ([darkFrom] == [darkUntil]) = nigdy ciemny.
     */
    data class Scheduled(
        val darkFrom: LocalTime = DEFAULT_DARK_FROM,
        val darkUntil: LocalTime = DEFAULT_DARK_UNTIL,
    ) : ThemeMode

    /** Czy motyw ma być teraz ciemny, przy bieżącej godzinie [now] i ustawieniu systemu [systemDark]. */
    fun isDark(now: LocalTime, systemDark: Boolean): Boolean = when (this) {
        System -> systemDark
        Light -> false
        Dark -> true
        is Scheduled -> when {
            darkFrom == darkUntil -> false
            darkFrom < darkUntil -> now >= darkFrom && now < darkUntil
            else -> now >= darkFrom || now < darkUntil // przez północ, np. 21:00–06:30
        }
    }

    /** Postać tekstowa do zapisu: `system`, `light`, `dark`, `scheduled;21:00;06:30`. */
    fun encode(): String = when (this) {
        System -> "system"
        Light -> "light"
        Dark -> "dark"
        is Scheduled -> "scheduled;$darkFrom;$darkUntil"
    }

    companion object {
        val DEFAULT_DARK_FROM: LocalTime = LocalTime.of(21, 0)
        val DEFAULT_DARK_UNTIL: LocalTime = LocalTime.of(6, 30)

        /** Odczyt zapisu z [encode]; przy braku/uszkodzeniu – [System]. */
        fun decode(text: String?): ThemeMode {
            if (text == null) return System
            return when (text) {
                "system" -> System
                "light" -> Light
                "dark" -> Dark
                else -> decodeScheduled(text) ?: System
            }
        }

        private fun decodeScheduled(text: String): Scheduled? {
            val parts = text.split(';')
            if (parts.size != 3 || parts[0] != "scheduled") return null
            return try {
                Scheduled(LocalTime.parse(parts[1]), LocalTime.parse(parts[2]))
            } catch (_: java.time.format.DateTimeParseException) {
                null
            }
        }
    }
}
