package app.domik.integrations.emodul

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Pobranie treści pod adresem HTTPS (blokujące). **Tylko GET** z nagłówkiem `Authorization: Bearer` – w tym
 * interfejsie nie ma i nie będzie metody zapisu (integracja eModul jest tylko do odczytu).
 */
fun interface EmodulHttp {
    /** Zwraca treść odpowiedzi 200 (UTF-8, najwyżej [maxBytes] bajtów). Inne błędy to [IOException]. */
    @Throws(IOException::class)
    fun get(url: String, bearer: String, maxBytes: Int): String
}

/**
 * Odpowiedź inna niż 200. [retryAfterSeconds] – nagłówek `Retry-After`, o ile jest liczbą sekund. Komunikat zawiera
 * wyłącznie kod statusu (bez adresu, klucza i treści odpowiedzi).
 */
class EmodulHttpException(val status: Int, val retryAfterSeconds: Long? = null) : IOException("eModul HTTP $status")

/** Odpowiedź większa niż dopuszczony limit (przerwane czytanie). */
class EmodulResponseTooLargeException : IOException("Odpowiedź za duża")

/**
 * [EmodulHttp] na `HttpsURLConnection` z biblioteki standardowej (wzorzec `UrlConnectionHttpGet`): tylko HTTPS,
 * bez przekierowań i pamięci podręcznej, limity czasu, odpowiedź ograniczona do `maxBytes`. Metoda żądania zostaje
 * domyślna (GET): metoda żądania zostaje nietknięta, a wysyłanie treści nie jest włączane. Komunikaty wyjątków nie
 * zawierają adresu ani klucza.
 *
 * @property allowInsecureForTests pozwala na zwykłe HTTP – wyłącznie testy z lokalnym serwerem
 */
class UrlConnectionEmodulHttp(
    private val connectTimeoutMillis: Int = CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = READ_TIMEOUT_MILLIS,
    private val allowInsecureForTests: Boolean = false,
) : EmodulHttp {
    override fun get(url: String, bearer: String, maxBytes: Int): String {
        require(bearer.isNotEmpty() && bearer.all { it in ALLOWED_TOKEN_CHARS }) { "Niepoprawny klucz" }
        val target = try {
            URL(url)
        } catch (_: MalformedURLException) {
            throw IOException("Niepoprawny adres")
        }
        if (target.protocol != "https" && !(allowInsecureForTests && target.protocol == "http")) {
            throw IOException("Dozwolone jest tylko HTTPS")
        }
        try {
            return request(target, bearer, maxBytes)
        } catch (e: EmodulHttpException) {
            throw e
        } catch (e: EmodulResponseTooLargeException) {
            throw e
        } catch (e: IOException) {
            // Komunikaty JDK potrafią zawierać adres; zostawiamy tylko rodzaj błędu (bez przyczyny).
            throw IOException("Błąd połączenia z eModul (${e.javaClass.simpleName})")
        }
    }

    private fun request(target: URL, bearer: String, maxBytes: Int): String {
        val connection = target.openConnection() as HttpURLConnection
        try {
            check(connection is HttpsURLConnection || allowInsecureForTests)
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $bearer")
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                val retryAfter = connection.getHeaderField("Retry-After")?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
                // Treść błędu odczytujemy najwyżej do ERROR_BODY_LIMIT i odrzucamy.
                runCatching { connection.errorStream?.use { drain(it, ERROR_BODY_LIMIT) } }
                throw EmodulHttpException(status, retryAfter)
            }
            return connection.inputStream.use { stream ->
                String(drain(stream, maxBytes), Charsets.UTF_8)
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Czyta strumień pętlą (bez `readNBytes` – dopiero od Androida 13); przy przekroczeniu [limit] bajtów przerywa. */
    private fun drain(stream: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > limit) throw EmodulResponseTooLargeException()
        }
        return out.toByteArray()
    }

    companion object {
        const val CONNECT_TIMEOUT_MILLIS = 10_000
        const val READ_TIMEOUT_MILLIS = 20_000
        private const val ERROR_BODY_LIMIT = 4 * 1024
        private const val USER_AGENT = "Domik"
        private val ALLOWED_TOKEN_CHARS = ('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('-', '_', '.')
    }
}
