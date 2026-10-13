package app.domik.integrations.thermo

/**
 * Ostatni odczyt termometru (dla UI i dla korektora).
 *
 * @property receivedAt chwila odbioru *nowego* pomiaru (zegar środowiska termometrów, nie z reklamy)
 * @property lastSeenAt chwila ostatniej reklamy (także powtórzonego pomiaru)
 */
data class ThermometerEntry(
    val reading: ThermometerReading,
    val receivedAt: Long,
    val lastSeenAt: Long,
) {
    val mac: String get() = reading.mac
}

/**
 * Ostatni odczyt każdego termometru, po adresie MAC. Termometr powtarza ten sam pomiar w kolejnych reklamach (ten
 * sam [ThermometerReading.counter]) – taki powtórzony pomiar nie jest nowym odczytem ([onReading] zwraca false), tylko
 * odświeża siłę sygnału i [ThermometerEntry.lastSeenAt]. Licznik się zawija (0–255), więc ta sama wartość po
 * [duplicateWindowMillis] liczy się jako nowy pomiar.
 *
 * Trzyma najwyżej [capacity] termometrów (najdawniej widziane wypadają). Bezpieczna wątkowo.
 */
class ThermometerDirectory(
    private val duplicateWindowMillis: Long = DUPLICATE_WINDOW_MILLIS,
    private val capacity: Int = CAPACITY,
) {
    private val entries = LinkedHashMap<String, ThermometerEntry>()

    /** Zapisuje reklamę odebraną w chwili [at]; true = nowy pomiar (do uśredniania), false = powtórzenie. */
    @Synchronized
    fun onReading(reading: ThermometerReading, at: Long): Boolean {
        val mac = reading.mac.uppercase()
        val previous = entries[mac]
        val duplicate = previous != null && previous.reading.counter == reading.counter &&
            at - previous.receivedAt in 0 until duplicateWindowMillis
        entries.remove(mac)
        entries[mac] = if (duplicate) {
            previous!!.copy(reading = previous.reading.copy(rssi = reading.rssi), lastSeenAt = at)
        } else {
            ThermometerEntry(reading.copy(mac = mac), receivedAt = at, lastSeenAt = at)
        }
        while (entries.size > capacity) entries.remove(entries.keys.first())
        return !duplicate
    }

    @Synchronized
    fun entry(mac: String): ThermometerEntry? = entries[mac.uppercase()]

    /** Wszystkie widziane termometry, najsilniejszy sygnał pierwszy (lista do przypisania). */
    @Synchronized
    fun all(): List<ThermometerEntry> = entries.values.sortedByDescending { it.reading.rssi }

    @Synchronized
    fun clear() = entries.clear()

    companion object {
        /** pvvx mierzy co ~10 s i zwiększa licznik; 256 pomiarów to ponad 40 min, więc 5 min bezpiecznie odróżnia powtórkę. */
        const val DUPLICATE_WINDOW_MILLIS = 5L * 60_000
        const val CAPACITY = 64
    }
}
