package app.domik.integrations.thermo

/** Parametr skanu BLE, jaki odpowiada trybowi [ScanMode] (mapowanie na `ScanSettings` robi warstwa Androida). */
enum class ScanIntensity { NONE, LOW_POWER, LOW_LATENCY }

/**
 * Zasady uruchamiania skanu BLE (czysta logika, czas przekazywany jawnie – testy z czasem wirtualnym).
 *
 * Android ogranicza częstość startów skanu: **najwyżej 5 startów na 30 s** na aplikację (dalsze są cicho ignorowane
 * albo kończą się `SCAN_FAILED_SCANNING_TOO_FREQUENTLY`), a skan trwający dłużej niż 30 min jest przestawiany na tryb
 * niskiego priorytetu („opportunistic”) i przestaje przekazywać wyniki w ciągłym tempie. Dlatego:
 *  - kolejne starty dzieli co najmniej [minStartIntervalMillis] (30 s / 5 = 6 s plus zapas = 7 s) *oraz* działa
 *    okno przesuwne [maxStartsInWindow] startów na [windowMillis],
 *  - ciągły skan jest planowo zatrzymywany i uruchamiany od nowa co [restartAfterMillis] (25 min).
 *
 * Klasa nie jest bezpieczna wątkowo – używać z jednej korutyny.
 */
class BleScanPolicy(
    private val minStartIntervalMillis: Long = MIN_START_INTERVAL_MILLIS,
    private val maxStartsInWindow: Int = MAX_STARTS_IN_WINDOW,
    private val windowMillis: Long = WINDOW_MILLIS,
    private val restartAfterMillis: Long = RESTART_AFTER_MILLIS,
) {
    private val startTimes = ArrayDeque<Long>()
    private var scanStartedAtMillis: Long? = null

    /** Ile jeszcze trzeba poczekać, zanim wolno wystartować skan (0 = można od razu). */
    fun millisUntilStartAllowed(nowMillis: Long): Long {
        pruneOld(nowMillis)
        var wait = 0L
        startTimes.lastOrNull()?.let { wait = maxOf(wait, it + minStartIntervalMillis - nowMillis) }
        if (startTimes.size >= maxStartsInWindow) {
            // Okno jest pełne: następny start dopiero, gdy najstarszy z ostatnich N wypadnie z okna.
            val oldestBlocking = startTimes[startTimes.size - maxStartsInWindow]
            wait = maxOf(wait, oldestBlocking + windowMillis - nowMillis)
        }
        return maxOf(0L, wait)
    }

    /** Zapisuje, że skan wystartował w chwili [nowMillis]. */
    fun recordStart(nowMillis: Long) {
        startTimes.addLast(nowMillis)
        scanStartedAtMillis = nowMillis
        pruneOld(nowMillis)
    }

    /** Zapisuje, że skan został zatrzymany (historia startów zostaje – limit systemu liczy się dalej). */
    fun recordStop() {
        scanStartedAtMillis = null
    }

    /** Czy trwający skan trzeba planowo zrestartować (starszy niż [restartAfterMillis]). */
    fun isRestartDue(nowMillis: Long): Boolean = millisUntilRestart(nowMillis) == 0L

    /** Za ile trzeba zrestartować trwający skan; null, gdy żaden skan nie trwa. */
    fun millisUntilRestart(nowMillis: Long): Long? =
        scanStartedAtMillis?.let { maxOf(0L, it + restartAfterMillis - nowMillis) }

    /** Intensywność skanu dla trybu: ciągły = oszczędny, wyszukiwanie = szybki, wyłączony = brak. */
    fun intensityFor(mode: ScanMode): ScanIntensity = when (mode) {
        ScanMode.OFF -> ScanIntensity.NONE
        ScanMode.BACKGROUND -> ScanIntensity.LOW_POWER
        ScanMode.DISCOVERY -> ScanIntensity.LOW_LATENCY
    }

    private fun pruneOld(nowMillis: Long) {
        // Zostaje tylko tyle wpisów, ile potrzeba do reguły okna (starsze niż okno nie mają znaczenia).
        while (startTimes.isNotEmpty() && nowMillis - startTimes.first() >= windowMillis) startTimes.removeFirst()
        // Cofnięcie zegara nie może zablokować skanu na zawsze: wpisy „z przyszłości” odrzucamy.
        while (startTimes.isNotEmpty() && startTimes.last() > nowMillis) startTimes.removeLast()
    }

    companion object {
        const val MAX_STARTS_IN_WINDOW = 5
        const val WINDOW_MILLIS = 30_000L
        const val MIN_START_INTERVAL_MILLIS = 7_000L
        const val RESTART_AFTER_MILLIS = 25L * 60_000L
    }
}
