package app.domik.integrations.emodul

import java.util.Locale

/** Które menu sterownika wolno czytać. `MS` i `MP` (serwis/producent) są poza zakresem aplikacji. */
enum class MenuType { MU, MI }

/**
 * Budowanie adresów API eModul (tylko odczyt). Składniki ścieżki są walidowane, żeby `udid` z odpowiedzi serwera
 * nie mógł zmienić ścieżki ani dopisać zapytania. Testy podają własną [base] (lokalny serwer).
 */
class EmodulUrls(private val base: String = BASE) {
    init {
        require(base.endsWith("/")) { "Adres bazowy musi kończyć się ukośnikiem" }
    }

    fun modules(userId: Long): String = "${user(userId)}/modules"

    fun module(userId: Long, udid: String): String = "${user(userId)}/modules/${checkedUdid(udid)}"

    fun menu(userId: Long, udid: String, type: MenuType): String =
        "${user(userId)}/modules/${checkedUdid(udid)}/menu/${type.name}/"

    /** Słownik tekstów; język spoza `pl`/`en` zamieniany na `pl`. */
    fun i18n(language: String): String {
        val lang = language.lowercase(Locale.ROOT).takeIf { it in LANGUAGES } ?: DEFAULT_LANGUAGE
        return "${base}i18n/$lang"
    }

    private fun user(userId: Long): String {
        require(userId > 0) { "Niepoprawny identyfikator użytkownika" }
        return "${base}users/$userId"
    }

    private fun checkedUdid(udid: String): String {
        require(isValidUdid(udid)) { "Niepoprawny identyfikator modułu" }
        return udid
    }

    companion object {
        const val BASE = "https://emodul.eu/api/v1/"
        const val DEFAULT_LANGUAGE = "pl"
        private val LANGUAGES = setOf("pl", "en")
        private val UDID = Regex("^[A-Za-z0-9]{8,64}$")

        fun isValidUdid(udid: String): Boolean = UDID.matches(udid)
    }
}
