package app.domik.integrations.emodul

import app.domik.integrations.weather.MiniJson
import app.domik.integrations.weather.WeatherParseException
import java.util.Base64

/**
 * Klucz API eModul: długoterminowy JWT wklejany przez użytkownika. `user_id` jest odczytywany z payloadu lokalnie
 * (bez sieci). Klucz nigdy nie trafia do logów ani do `toString()`.
 */
data class EmodulKey(val token: String, val userId: Long) {
    /** Do pokazania w ustawieniach po zapisie: nigdy cały klucz, tylko końcówka. */
    val masked: String get() = "…" + token.takeLast(MASK_TAIL)

    override fun toString(): String = "EmodulKey(<ukryty>)"

    companion object {
        const val MIN_LENGTH = 20
        const val MAX_LENGTH = 4096
        private const val MASK_TAIL = 4
        private const val BEARER_PREFIX = "Bearer"
        private val SHAPE = Regex("^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*$")

        /**
         * Waliduje wklejony tekst: przycina i usuwa wszystkie białe znaki (także wewnątrz – łamane wiersze), usuwa
         * przedrostek `Bearer `, sprawdza kształt JWT i czyta `user_id`
         * (liczba całkowita > 0). Gdy payload ma `exp` w przeszłości – [EmodulKeyResult.Expired].
         */
        fun parse(input: String, nowMillis: Long = System.currentTimeMillis()): EmodulKeyResult {
            var text = input.trim()
            if (text.startsWith(BEARER_PREFIX, ignoreCase = true) && text.getOrNull(BEARER_PREFIX.length)?.isWhitespace() == true) {
                text = text.substring(BEARER_PREFIX.length)
            }
            // JWT nie zawiera białych znaków: wklejony klucz łamany na wiersze (\n, \r, spacje, tabulatory) skleja się z powrotem.
            text = text.filterNot { it.isWhitespace() }
            if (text.length !in MIN_LENGTH..MAX_LENGTH || !SHAPE.matches(text)) return EmodulKeyResult.Malformed
            val claims = decodePayload(text.split('.')[1]) ?: return EmodulKeyResult.Malformed
            val userId = (claims["user_id"] as? Double)?.takeIf { it > 0.0 && it == Math.floor(it) && it < MAX_SAFE_ID }
                ?.toLong() ?: return EmodulKeyResult.Malformed
            val exp = claims["exp"] as? Double
            if (exp != null && exp * 1000.0 < nowMillis) return EmodulKeyResult.Expired
            return EmodulKeyResult.Valid(EmodulKey(text, userId))
        }

        private const val MAX_SAFE_ID = 9.0e15

        private fun decodePayload(part: String): Map<*, *>? = try {
            val json = String(Base64.getUrlDecoder().decode(part), Charsets.UTF_8)
            MiniJson.parse(json) as? Map<*, *>
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: WeatherParseException) {
            null
        }
    }
}

/** Wynik walidacji wklejonego klucza ([EmodulKey.parse]). */
sealed interface EmodulKeyResult {
    data class Valid(val key: EmodulKey) : EmodulKeyResult
    data object Malformed : EmodulKeyResult
    data object Expired : EmodulKeyResult
}
