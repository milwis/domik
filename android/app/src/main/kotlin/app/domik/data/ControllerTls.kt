package app.domik.data

import app.domik.deimic.client.FrameTransportFactory
import app.domik.deimic.client.TlsSocketTransport
import org.conscrypt.Conscrypt
import java.security.Provider

/**
 * Stos TLS połączeń ze sterownikiem. Sterownik mówi tylko TLS 1.2 z jednym szyfrem (`TLS_RSA_WITH_AES_256_GCM_SHA384`),
 * a stary systemowy Conscrypt (np. Android 8) potrafi się z nim nie dogadać – oficjalna aplikacja ma własny BoringSSL.
 * Dlatego połączenie ze sterownikiem używa dołączonego Conscrypta (jeden stos TLS na wszystkich wersjach Androida).
 *
 * Dostawca jest przekazywany wyłącznie do [TlsSocketTransport] – **nie** jest instalowany globalnie
 * (`Security.insertProviderAt`), więc inne HTTPS w aplikacji (pogoda, eModul) działa na systemowym stosie jak dotąd.
 * Gdy natywna biblioteka się nie załaduje, używany jest dostawca systemowy, a przyczyna trafia do diagnostyki.
 */
object ControllerTls {
    /** Wybrany dostawca ([provider] = null: systemowy) i opis do raportu diagnostycznego. */
    class Choice(val provider: Provider?, val note: String)

    /** Tworzone raz (ładowanie biblioteki natywnej); nigdy nie rzuca. */
    val choice: Choice by lazy {
        try {
            val provider = Conscrypt.newProvider()
            val version = Conscrypt.version()
            Choice(provider, "bundled Conscrypt ${version.major()}.${version.minor()}.${version.patch()}")
        } catch (error: Throwable) {
            Choice(null, fallbackNote(error))
        }
    }

    fun options(): TlsSocketTransport.Options =
        TlsSocketTransport.Options(provider = choice.provider, providerNote = choice.note)

    /** Transport TLS ze sterownikiem używany przez połączenie stałe i test połączenia. */
    val transportFactory: FrameTransportFactory = FrameTransportFactory { TlsSocketTransport(it, options()) }

    internal fun fallbackNote(error: Throwable): String {
        val reason = "${error::class.java.simpleName}: ${error.message.orEmpty()}".trim().take(MAX_REASON)
        return "bundled Conscrypt unavailable, using the system TLS provider ($reason)"
    }

    private const val MAX_REASON = 160
}

/** Wiersze nagłówka raportu diagnostycznego: aplikacja, Android, urządzenie. Bez żadnych danych sterownika. */
fun diagnosticsHeader(
    app: AppVersion?,
    androidRelease: String,
    sdkInt: Int,
    manufacturer: String,
    model: String,
): List<String> = listOf(
    "Domik ${app?.let { "${it.name} (${it.code})" } ?: "?"}",
    "Android $androidRelease (SDK $sdkInt)",
    "Device: ${listOf(manufacturer, model).filter { it.isNotBlank() }.joinToString(" ")}",
)
