package app.domik.integrations.weather

/**
 * Zasady odświeżania i „nieaktualności” pogody (czyste funkcje, czas przekazywany jawnie).
 *
 *  - co [REFRESH_INTERVAL_MILLIS] (30 min) gdy aplikacja jest na pierwszym planie,
 *  - po powrocie na pierwszy plan, jeśli dane są starsze niż [RESUME_MAX_AGE_MILLIS] (15 min),
 *  - dane starsze niż [STALE_AFTER_MILLIS] (45 min = okres odświeżania + zapas) uznajemy za nieaktualne –
 *    kafelek pokazuje wtedy godzinę ostatniej aktualizacji.
 */
object WeatherPolicy {
    const val REFRESH_INTERVAL_MILLIS = 30L * 60_000L
    const val RESUME_MAX_AGE_MILLIS = 15L * 60_000L
    const val STALE_AFTER_MILLIS = 45L * 60_000L

    /**
     * Ile „z przyszłości” mogą być dane, żeby uznać to za zwykłą niedokładność zegara (np. interfejs liczy „teraz”
     * z dokładnością do minuty, a czas pobrania jest co do milisekundy) i nie oznaczać ich jako nieaktualne.
     */
    const val CLOCK_SKEW_TOLERANCE_MILLIS = 2L * 60_000L

    /**
     * Wiek danych. Niewielki ujemny wiek (do [CLOCK_SKEW_TOLERANCE_MILLIS]) to 0; większy (zegar cofnięty) traktujemy
     * jak „bardzo stary”, żeby dane nie zamarzły na zawsze.
     */
    private fun age(fetchedAtMillis: Long, nowMillis: Long): Long {
        val age = nowMillis - fetchedAtMillis
        return when {
            age >= 0 -> age
            age >= -CLOCK_SKEW_TOLERANCE_MILLIS -> 0L
            else -> Long.MAX_VALUE
        }
    }

    /** Czy trzeba pobrać dane od nowa (brak danych albo starsze niż [maxAgeMillis]). */
    fun needsRefresh(fetchedAtMillis: Long?, nowMillis: Long, maxAgeMillis: Long = RESUME_MAX_AGE_MILLIS): Boolean =
        fetchedAtMillis == null || age(fetchedAtMillis, nowMillis) > maxAgeMillis

    /** Czy dane są na tyle stare, że trzeba je oznaczyć jako nieaktualne. */
    fun isStale(fetchedAtMillis: Long, nowMillis: Long): Boolean = age(fetchedAtMillis, nowMillis) > STALE_AFTER_MILLIS
}
