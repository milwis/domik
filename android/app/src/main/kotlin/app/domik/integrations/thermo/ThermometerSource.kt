package app.domik.integrations.thermo

import app.domik.thermo.AdvertisementParser
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Jeden odebrany pomiar termometru (reklama BLE zdekodowana przez [AdvertisementParser]).
 *
 * @property mac adres z treści reklamy, `AA:BB:CC:DD:EE:FF` (to identyfikator termometru, nie adres radiowy)
 * @property name nazwa z reklamy (np. „ATC_1A2B3C”), jeśli była w odebranym pakiecie; często null
 * @property rssi siła sygnału w odbiorniku (dBm) – do wyboru najlepszego odbiornika / diagnostyki
 * @property counter licznik pomiaru z reklamy (0–255, zawija się) – ta sama wartość = ten sam pomiar powtórzony
 * @property flags surowy bajt flag (tylko format pvvx; null dla ATC1441)
 * @property receivedAtMillis chwila odbioru na tablecie (czas ścienny, `System.currentTimeMillis`)
 */
data class ThermometerReading(
    val mac: String,
    val name: String?,
    val rssi: Int,
    val tempC: Float,
    val humidity: Float,
    val batteryPercent: Int,
    val batteryMv: Int,
    val counter: Int,
    val flags: Int?,
    val receivedAtMillis: Long,
) {
    /** Pełny MAC nigdy nie trafia do logów (jak w [app.domik.thermo.Reading]). */
    override fun toString(): String =
        "ThermometerReading(${AdvertisementParser.maskMac(mac)} $tempC °C $humidity % bat $batteryPercent % " +
            "rssi $rssi #$counter)"
}

/** Stan odbiornika BLE – widoczny w ustawieniach „Termometry” (co zrobić, żeby zadziałał). */
enum class ScannerStatus {
    /** Skanowanie wyłączone ([ScanMode.OFF]). */
    OFF,

    /** Brak zgody (Android ≤ 11: lokalizacja; Android 12+: „Urządzenia w pobliżu”). */
    NO_PERMISSION,

    /** Bluetooth wyłączony w systemie. */
    BLUETOOTH_OFF,

    /** Android ≤ 11 wymaga włączonej lokalizacji systemowej, żeby skan BLE zwracał wyniki. */
    LOCATION_OFF,

    /** Urządzenie bez BLE albo bez skanera (np. emulator). */
    UNSUPPORTED,

    /** Skan trwa. */
    SCANNING,

    /** Skan nie wystartował / został przerwany przez system; odbiornik ponawia próbę (z ograniczeniem częstości). */
    ERROR,
}

/** Jak intensywnie skanować. */
enum class ScanMode {
    OFF,

    /** Praca ciągła: niski pobór energii (reklamy termometrów co kilka sekund wystarczą). */
    BACKGROUND,

    /** Chwilowe wyszukiwanie nowego termometru (dodawanie): szybki skan, nie na stałe. */
    DISCOVERY,
}

/**
 * Źródło odczytów termometrów. W aplikacji: odbiornik BLE ([app.domik.platform.AndroidBleThermometerScanner]),
 * w trybie demo: [DemoThermometers]. Odczyty z wielu termometrów trafiają do jednego strumienia [readings].
 */
interface ThermometerSource {
    val status: StateFlow<ScannerStatus>

    /** Strumień „gorący”, bez powtórek dla spóźnionych odbiorców; przy przepełnieniu odrzuca najstarsze. */
    val readings: SharedFlow<ThermometerReading>

    fun setMode(mode: ScanMode)
}
