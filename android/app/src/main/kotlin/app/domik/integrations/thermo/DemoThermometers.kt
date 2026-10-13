package app.domik.integrations.thermo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Termometry w trybie demo: bez Bluetooth i bez uprawnień. Co [intervalMillis] (domyślnie 10 s) wysyła odczyt każdego
 * z [count] termometrów o adresach lokalnych `02:00:00:00:00:01`, `…:02`, … ([macFor]).
 *
 * Temperaturę podaje [temperatureOf] (adres → °C); wynik null = termometr milczy w tym cyklu. Dostarcza ją
 * integracja z symulacją trybu demo (FakeController), tak aby odczyt „z termometru” był spójny z symulowanym domem.
 *
 * @param scope zakres, w którym działa generator (w aplikacji: zakres sesji demo; w testach: `backgroundScope`)
 * @param clock czas ścienny dla [ThermometerReading.receivedAtMillis]
 */
class DemoThermometers(
    private val scope: CoroutineScope,
    private val temperatureOf: (mac: String) -> Float?,
    private val count: Int = DEFAULT_COUNT,
    private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) : ThermometerSource {
    private val _status = MutableStateFlow(ScannerStatus.OFF)
    override val status: StateFlow<ScannerStatus> = _status.asStateFlow()

    private val _readings = MutableSharedFlow<ThermometerReading>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val readings: SharedFlow<ThermometerReading> = _readings.asSharedFlow()

    private var job: Job? = null
    private val counters = IntArray(count)

    init {
        require(count in 1..MAX_COUNT) { "count must be in 1..$MAX_COUNT" }
        require(intervalMillis > 0) { "intervalMillis must be positive" }
    }

    /** Dowolny tryb inny niż [ScanMode.OFF] uruchamia generator (w demo [ScanMode.DISCOVERY] = to samo co tło). */
    override fun setMode(mode: ScanMode) {
        if (mode == ScanMode.OFF) {
            job?.cancel()
            job = null
            _status.value = ScannerStatus.OFF
            return
        }
        if (job?.isActive == true) return
        _status.value = ScannerStatus.SCANNING
        job = scope.launch {
            while (true) {
                emitAll()
                delay(intervalMillis)
            }
        }
    }

    private fun emitAll() {
        val now = clock()
        for (index in 0 until count) {
            val mac = macFor(index + 1)
            val temp = temperatureOf(mac) ?: continue
            counters[index] = (counters[index] + 1) and 0xFF
            _readings.tryEmit(
                ThermometerReading(
                    mac = mac,
                    name = "Demo ${index + 1}",
                    rssi = -55 - 3 * index,
                    tempC = temp,
                    humidity = 45f + index,
                    batteryPercent = 100 - index,
                    batteryMv = 2_900 - 10 * index,
                    counter = counters[index],
                    flags = null,
                    receivedAtMillis = now,
                ),
            )
        }
    }

    companion object {
        const val DEFAULT_COUNT = 7
        const val DEFAULT_INTERVAL_MILLIS = 10_000L
        private const val MAX_COUNT = 255

        /** Adres lokalnie administrowany (bit 0x02 w pierwszym bajcie), na pewno nie należy do prawdziwego urządzenia. */
        fun macFor(number: Int): String = "02:00:00:00:00:%02X".format(number)
    }
}
