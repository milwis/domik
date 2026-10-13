package app.domik.integrations.emodul

import java.time.Instant

/** Rodzaj niepowodzenia odczytu (po sklasyfikowaniu wyjątku przez [EmodulPolicy.classify]). */
sealed interface EmodulFailure {
    /** 401/403: klucz odrzucony – odpytywanie stoi do zmiany klucza albo ręcznej ponownej próby. */
    data object KeyRejected : EmodulFailure

    /** 429: [retryAfterSeconds] z nagłówka `Retry-After` (jeśli był). */
    data class RateLimited(val retryAfterSeconds: Long?) : EmodulFailure

    /** Brak połączenia, przekroczenie czasu, 5xx i inne statusy – ponowienie z wykładniczym odstępem. */
    data object Network : EmodulFailure

    /** Odpowiedź większa niż limit. */
    data object TooLarge : EmodulFailure

    /** Odpowiedź, której nie da się sparsować. */
    data object BadResponse : EmodulFailure
}

/**
 * Zasady odświeżania, ponawiania i „nieaktualności” danych eModul (czyste funkcje, czas przekazywany jawnie).
 *
 *  - lista modułów: co [MODULES_INTERVAL_MILLIS] (6 h),
 *  - moduł: co [MODULE_INTERVAL_MILLIS] (60 s) przy otwartym pulpicie; po powrocie na pierwszy plan, jeśli dane
 *    starsze niż [RESUME_MAX_AGE_MILLIS] (30 s),
 *  - menu MU/MI: co [MENU_INTERVAL_MILLIS] (15 min),
 *  - słownik i18n: [needsFullI18n] (≤ raz na [I18N_INTERVAL_MILLIS] = 24 h),
 *  - błąd sieci: [backoffMillis] (60 s × 2^(n−1), najwyżej 15 min); 401/403 – stop; 429 – `Retry-After` albo 15 min,
 *  - nieaktualne: [STALE_AFTER_MILLIS] (15 min), tolerancja zegara [CLOCK_SKEW_TOLERANCE_MILLIS] (2 min).
 */
object EmodulPolicy {
    const val MODULES_INTERVAL_MILLIS = 6L * 60L * 60_000L
    const val MODULE_INTERVAL_MILLIS = 60_000L
    const val MENU_INTERVAL_MILLIS = 15L * 60_000L
    const val I18N_INTERVAL_MILLIS = 24L * 60L * 60_000L
    const val RESUME_MAX_AGE_MILLIS = 30_000L
    const val STALE_AFTER_MILLIS = 15L * 60_000L
    const val CLOCK_SKEW_TOLERANCE_MILLIS = 2L * 60_000L
    const val BACKOFF_BASE_MILLIS = 60_000L
    const val BACKOFF_MAX_MILLIS = 15L * 60_000L
    const val RATE_LIMIT_DEFAULT_MILLIS = 15L * 60_000L

    /** Górna granica `Retry-After` (serwer nie zatrzyma odczytów na dłużej niż doba; „Spróbuj ponownie” i tak zeruje). */
    const val RETRY_AFTER_MAX_MILLIS = 24L * 60L * 60_000L

    /**
     * Wiek danych. Niewielki ujemny wiek (do [CLOCK_SKEW_TOLERANCE_MILLIS]) to 0; większy (zegar cofnięty) traktujemy
     * jak „bardzo stary”, żeby dane nie zamarzły na zawsze.
     */
    private fun age(timeMillis: Long, nowMillis: Long): Long {
        val age = nowMillis - timeMillis
        return when {
            age >= 0 -> age
            age >= -CLOCK_SKEW_TOLERANCE_MILLIS -> 0L
            else -> Long.MAX_VALUE
        }
    }

    /** Czy dane pobrane o [fetchedAtMillis] (`null` = brak) są starsze niż [maxAgeMillis]. */
    fun needsRefresh(fetchedAtMillis: Long?, nowMillis: Long, maxAgeMillis: Long): Boolean =
        fetchedAtMillis == null || age(fetchedAtMillis, nowMillis) > maxAgeMillis

    /** Po ilu ms odczyt o okresie [intervalMillis] będzie zaległy (0 = już teraz: brak danych lub okres minął). */
    fun delayUntilDue(fetchedAtMillis: Long?, nowMillis: Long, intervalMillis: Long): Long {
        if (fetchedAtMillis == null) return 0L
        val age = age(fetchedAtMillis, nowMillis)
        return if (age >= intervalMillis) 0L else intervalMillis - age
    }

    /**
     * Czy pobrać pełny słownik: brak zapisanego podzbioru, zmiana języka, albo pojawiło się id spoza podzbioru
     * ([hasMissingIds]) i ostatnie pobranie jest starsze niż [I18N_INTERVAL_MILLIS].
     */
    fun needsFullI18n(
        storedFetchedAtMillis: Long?,
        storedForSameLanguage: Boolean,
        hasMissingIds: Boolean,
        nowMillis: Long,
    ): Boolean = storedFetchedAtMillis == null || !storedForSameLanguage ||
        (hasMissingIds && age(storedFetchedAtMillis, nowMillis) > I18N_INTERVAL_MILLIS)

    /** Odstęp przed kolejną próbą po [consecutiveFailures] błędach z rzędu: 60, 120, 240 … najwyżej 900 s. */
    fun backoffMillis(consecutiveFailures: Int): Long {
        if (consecutiveFailures <= 0) return 0L
        val shift = (consecutiveFailures - 1).coerceAtMost(10)
        return (BACKOFF_BASE_MILLIS shl shift).coerceAtMost(BACKOFF_MAX_MILLIS)
    }

    /** Zamiana wyjątku z [EmodulApi] / [EmodulParser] na rodzaj niepowodzenia. */
    fun classify(error: Throwable): EmodulFailure = when {
        error is EmodulHttpException -> when (error.status) {
            401, 403 -> EmodulFailure.KeyRejected
            429 -> EmodulFailure.RateLimited(error.retryAfterSeconds)
            else -> EmodulFailure.Network
        }
        error is EmodulResponseTooLargeException -> EmodulFailure.TooLarge
        error is EmodulParseException -> EmodulFailure.BadResponse
        else -> EmodulFailure.Network
    }

    /**
     * Za ile ms spróbować ponownie po [failure] (to było [consecutiveFailures]-te niepowodzenie z rzędu);
     * `null` = nie ponawiać automatycznie ([EmodulFailure.KeyRejected]).
     */
    fun retryDelayMillis(failure: EmodulFailure, consecutiveFailures: Int): Long? = when (failure) {
        EmodulFailure.KeyRejected -> null
        is EmodulFailure.RateLimited -> failure.retryAfterSeconds
            ?.let { (it.coerceAtMost(RETRY_AFTER_MAX_MILLIS / 1000L) * 1000L) }
            ?: RATE_LIMIT_DEFAULT_MILLIS
        else -> backoffMillis(consecutiveFailures.coerceAtLeast(1))
    }

    /**
     * Czy dane są nieaktualne: stan sterownika ([lastUpdate], `tilesLastUpdate`) starszy niż [STALE_AFTER_MILLIS]
     * albo ostatni udany odczyt ([fetchedAtMillis]) starszy niż [STALE_AFTER_MILLIS]. Brak obu = nieaktualne.
     */
    fun isStale(lastUpdate: Instant?, fetchedAtMillis: Long?, nowMillis: Long): Boolean {
        if (fetchedAtMillis == null || age(fetchedAtMillis, nowMillis) > STALE_AFTER_MILLIS) return true
        return lastUpdate != null && age(lastUpdate.toEpochMilli(), nowMillis) > STALE_AFTER_MILLIS
    }

    /**
     * Dlaczego dane modułu są (nie)aktualne, w czasie [nowMillis]:
     *  - [EmodulStaleness.CloudUnreachable] – ostatni udany odczyt z chmury ([fetchedAtMillis]) starszy niż
     *    [STALE_AFTER_MILLIS] albo go nie było (dane z pliku): to tablet bez internetu / serwer niedostępny;
     *  - [EmodulStaleness.ControllerSilent] – chmura odpowiada, ale stan sterownika ([lastUpdate], `tilesLastUpdate`)
     *    jest starszy niż [STALE_AFTER_MILLIS] albo moduł jest offline ([online] = false);
     *  - [EmodulStaleness.Fresh] w pozostałych przypadkach (brak [lastUpdate] u modułu online = świeże).
     */
    fun staleness(lastUpdate: Instant?, fetchedAtMillis: Long?, online: Boolean, nowMillis: Long): EmodulStaleness {
        if (fetchedAtMillis == null || age(fetchedAtMillis, nowMillis) > STALE_AFTER_MILLIS) {
            return EmodulStaleness.CloudUnreachable(fetchedAtMillis?.let(Instant::ofEpochMilli))
        }
        val controllerOld = lastUpdate != null && age(lastUpdate.toEpochMilli(), nowMillis) > STALE_AFTER_MILLIS
        return if (controllerOld || !online) EmodulStaleness.ControllerSilent(lastUpdate) else EmodulStaleness.Fresh
    }
}

/**
 * Stan aktualności danych jednego modułu (czas [since] = od kiedy brak świeżych danych: `tilesLastUpdate` albo
 * ostatni udany odczyt; `null` = nieznany).
 */
sealed interface EmodulStaleness {
    data object Fresh : EmodulStaleness

    /** Chmura odpowiada, ale sterownik nie wysyła danych (albo jest offline). */
    data class ControllerSilent(val since: Instant?) : EmodulStaleness

    /** Brak udanego odczytu z chmury eModul od [since]. */
    data class CloudUnreachable(val since: Instant?) : EmodulStaleness
}

/**
 * Co pokazać w pasku uwagi na górze pulpitu (jeden na wszystkie moduły). Kolejność ważności: klucz odrzucony,
 * brak połączenia z chmurą, cisza sterownika; przy kilku modułach w tym samym stanie – najstarsze [since].
 */
sealed interface EmodulAttention {
    data object None : EmodulAttention
    data object KeyRejected : EmodulAttention
    data class CloudUnreachable(val since: Instant?) : EmodulAttention
    data class ControllerSilent(val since: Instant?) : EmodulAttention

    companion object {
        /** Najgorszy stan z [staleness] (`Fresh` → [None]). */
        fun worst(staleness: Collection<EmodulStaleness>): EmodulAttention {
            fun oldest(times: List<Instant?>): Instant? = if (times.any { it == null }) null else times.filterNotNull().minOrNull()
            val cloud = staleness.filterIsInstance<EmodulStaleness.CloudUnreachable>()
            if (cloud.isNotEmpty()) return CloudUnreachable(oldest(cloud.map { it.since }))
            val silent = staleness.filterIsInstance<EmodulStaleness.ControllerSilent>()
            if (silent.isNotEmpty()) return ControllerSilent(oldest(silent.map { it.since }))
            return None
        }
    }
}
