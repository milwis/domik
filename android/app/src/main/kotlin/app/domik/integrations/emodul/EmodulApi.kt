package app.domik.integrations.emodul

import java.io.IOException

/**
 * Dostęp do API eModul – wyłącznie odczyt. Metody blokują (wołać poza wątkiem UI) i zwracają **surowy JSON**
 * (parsowanie robi [EmodulParser], a surowe odpowiedzi trafiają też do [EmodulStore]). Błędy sieci to [IOException],
 * w tym [EmodulHttpException] (status ≠ 200) i [EmodulResponseTooLargeException].
 */
interface EmodulApi {
    /** Lista modułów konta. */
    fun modules(key: EmodulKey): String

    /** Dane modułu: kafelki, strefy. */
    fun module(key: EmodulKey, udid: String): String

    /** Menu użytkownika ([MenuType.MU]) albo instalatora ([MenuType.MI]) – tylko do wyświetlenia. */
    fun menu(key: EmodulKey, udid: String, type: MenuType): String

    /** Słownik tekstów (`pl`/`en`); wymaga tego samego nagłówka `Bearer` co reszta. */
    fun i18n(key: EmodulKey, language: String): String

    /** Limity rozmiaru odpowiedzi (bajty). */
    object Limits {
        const val MODULES = 1024 * 1024
        const val MODULE = 1024 * 1024
        const val MENU = 1024 * 1024
        const val I18N = 3 * 1024 * 1024
    }
}

/** Brak API (testy, środowiska bez sieci): każde wywołanie kończy się błędem sieci. */
object NoEmodulApi : EmodulApi {
    override fun modules(key: EmodulKey): String = unavailable()

    override fun module(key: EmodulKey, udid: String): String = unavailable()

    override fun menu(key: EmodulKey, udid: String, type: MenuType): String = unavailable()

    override fun i18n(key: EmodulKey, language: String): String = unavailable()

    private fun unavailable(): Nothing = throw IOException("Brak dostępu do eModul")
}

/** [EmodulApi] na prawdziwym (lub podstawionym w testach) [EmodulHttp]; [base] – adres bazowy API z ukośnikiem. */
class EmodulCloudApi(
    private val http: EmodulHttp = UrlConnectionEmodulHttp(),
    base: String = EmodulUrls.BASE,
) : EmodulApi {
    private val urls = EmodulUrls(base)

    override fun modules(key: EmodulKey): String =
        http.get(urls.modules(key.userId), key.token, EmodulApi.Limits.MODULES)

    override fun module(key: EmodulKey, udid: String): String =
        http.get(urls.module(key.userId, udid), key.token, EmodulApi.Limits.MODULE)

    override fun menu(key: EmodulKey, udid: String, type: MenuType): String =
        http.get(urls.menu(key.userId, udid, type), key.token, EmodulApi.Limits.MENU)

    override fun i18n(key: EmodulKey, language: String): String =
        http.get(urls.i18n(language), key.token, EmodulApi.Limits.I18N)
}
