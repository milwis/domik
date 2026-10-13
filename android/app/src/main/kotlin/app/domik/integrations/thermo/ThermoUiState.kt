package app.domik.integrations.thermo

import app.domik.data.InputKey
import app.domik.deimic.client.commands.ZoneKey
import app.domik.deimic.protocol.InputCalibration
import app.domik.thermo.AdvertisementParser
import app.domik.thermo.CalibrationIdleDetail
import app.domik.thermo.CalibrationIdleReason

/** Stan strefy z przypisanym termometrem. */
enum class ThermoZoneStatus {
    /** Konfiguracja domu jeszcze niewczytana. */
    WAITING_FOR_CONFIG,

    /** Strefy z przypisania nie ma w konfiguracji sterownika (np. usunięta w Configuratorze). */
    ZONE_NOT_FOUND,

    /** Pomieszczenie strefy ma zero albo kilka wejść temperatury – użytkownik musi wskazać wejście. */
    NEEDS_INPUT,

    /** Wejście strefy jest już korygowane w innej strefie (dwie strefy w jednym pomieszczeniu). */
    INPUT_IN_USE,

    /** Korekta działa (w trybie próbnym albo rzeczywistym). */
    ACTIVE,

    /** Kalibracja zmieniona poza Domikiem: zapisy wstrzymane do potwierdzenia ([ThermoNotice.EXTERNAL_CHANGE]). */
    PAUSED,
}

/** Komunikaty strefy dla UI. */
enum class ThermoNotice {
    /** Kalibracja wejścia zmieniona poza Domikiem (Configurator) – przyjąć jako bazową? */
    EXTERNAL_CHANGE,

    /** Pomieszczenie strefy nie ma wejścia temperatury. */
    NO_INPUT,

    /** Pomieszczenie strefy ma kilka wejść temperatury. */
    AMBIGUOUS_INPUT,

    /** Wejście jest już przypisane do innej strefy. */
    INPUT_IN_USE,

    /** Termometr nie został jeszcze odebrany w tej sesji. */
    THERMOMETER_NOT_SEEN,

    /** Ostatni odczyt termometru jest za stary (albo korektor uznał termometr za utracony). */
    THERMOMETER_STALE,

    /** Bateria termometru na wyczerpaniu (≤ 10 %). */
    BATTERY_LOW,

    /** Ostatni odczyt kalibracji (0x131) się nie udał. */
    CALIBRATION_READ_FAILED,

    /** Ostatni zapis kalibracji (0x132) się nie udał. */
    WRITE_FAILED,
}

/**
 * Dodatkowe stany strefy po przeglądzie kodu 2026-10-12. Osobny typ (zamiast nowych wartości [ThermoZoneStatus] /
 * [ThermoNotice]), żeby starszy kod UI z wyczerpującym `when` dalej się kompilował; status strefy przyjmuje wtedy
 * najbliższą starą wartość (podaną przy każdej pozycji).
 */
enum class ThermoZoneIssue {
    /**
     * Ręcznie wybrane wejście ≠ jedyne wejście temperatury pomieszczenia strefy: korekta wyłączona do czasu
     * poprawienia przypisania (status [ThermoZoneStatus.NEEDS_INPUT]).
     */
    INPUT_CONFLICT,

    /**
     * Ręcznie wybrane wejście spoza dozwolonych ([ThermoZoneUi.inputCandidates]: wejścia pomieszczenia, a gdy go nie
     * ma – wejścia temperatury modułu); status [ThermoZoneStatus.NEEDS_INPUT].
     */
    INPUT_NOT_ALLOWED,

    /**
     * Kalibracja instalatora przywrócona ręcznie: korekta strefy wstrzymana (inaczej cofnęłaby przywrócenie po
     * kilku minutach). Status [ThermoZoneStatus.ACTIVE], `idleReason` = DISABLED; wznowienie:
     * `AppModel.resumeCalibration(zone)`.
     */
    MANUAL_RESTORE,

    /** Po nieudanym zapisie korekta czeka na udany odczyt kalibracji (0x131); nic nie jest zapisywane. */
    READ_REQUIRED,

    /** Nie udało się zapisać stanu kalibracji w pamięci urządzenia – zapisy do sterownika wstrzymane. */
    STATE_NOT_SAVED,

    /**
     * Na sterowniku może zostać kalibracja ustawiona przez Domik, której nic nie nadzoruje (np. przy przejściu
     * na tryb próbny nie było połączenia, więc kalibracji instalatora nie dało się przywrócić).
     */
    CALIBRATION_NOT_RESTORED,
}

/** Przypisany termometr strefy (ostatni odczyt). [ageMillis] liczony w chwili [ThermoUiState.updatedAt]. */
data class AssignedThermometerUi(
    val mac: String,
    val label: String,
    val tempC: Float?,
    val humidity: Float?,
    val batteryPercent: Int?,
    val rssi: Int?,
    val receivedAt: Long?,
    val ageMillis: Long?,
    val fresh: Boolean,
) {
    override fun toString(): String = "AssignedThermometerUi(${AdvertisementParser.maskMac(mac)}, $tempC °C, age=$ageMillis)"
}

/**
 * Ile kalibracji (rzeczywistych albo symulowanych) dał próg [threshold] – z dziennika decyzji (przeżywa restart).
 *
 * @property primary próg wybrany w ustawieniach (jego decyzje są wykonywane)
 * @property since najstarszy wpis dziennika strefy (null = pusty)
 */
data class ThermoFrequency(
    val threshold: Float,
    val primary: Boolean,
    val calibrationsLast24h: Int,
    val calibrationsTotal: Int,
    val restoresTotal: Int,
    val since: Long?,
)

/**
 * Strefa z przypisanym termometrem.
 *
 * @property deimicC bieżąca wartość wejścia analogowego wg sterownika (po kalibracji, 0x130/0x72)
 * @property averageXiaomiC / averageDeimicC średnie z okna korektora; [differenceC] = Xiaomi − Deimic
 * @property calibrationOffsetC przesunięcie odczytu przez kalibrację na sterowniku w bieżącym punkcie (°C; ujemne = obniża)
 * @property baselineOffsetC to samo dla kalibracji bazowej (instalatora)
 * @property plannedOffsetC tryb próbny: przesunięcie symulowanej kalibracji progu głównego (rzeczywisty: = bieżące)
 * @property shiftFromBaselineC o ile kalibracja Domika różni się od bazowej (°C)
 * @property idleReason dlaczego ostatnie sprawdzenie nic nie zmieniło (null = coś zmieniło albo jeszcze nie sprawdzano)
 * @property lastDecision ostatni wpis dziennika strefy dla progu głównego (kalibracja, przywrócenie, zdarzenie)
 * @property writesActive zapisy tej strefy naprawdę trafiają do sterownika (poza tym tylko dziennik)
 * @property issues dodatkowe stany strefy ([ThermoZoneIssue]; pole dodane później – domyślnie puste)
 * @property manuallyPaused korekta strefy wstrzymana po ręcznym przywróceniu kalibracji instalatora
 * @property idleDetail dokładniejsza przyczyna [idleReason], gdy zapis jest wstrzymany ([CalibrationIdleDetail])
 */
data class ThermoZoneUi(
    val zone: ZoneKey,
    val name: String,
    val roomName: String?,
    val status: ThermoZoneStatus,
    val inputNr: Int?,
    val inputCandidates: List<Int>,
    val thermometer: AssignedThermometerUi,
    val deimicC: Float?,
    val averageXiaomiC: Float?,
    val averageDeimicC: Float?,
    val differenceC: Float?,
    val calibrationOffsetC: Float?,
    val baselineOffsetC: Float?,
    val plannedOffsetC: Float?,
    val shiftFromBaselineC: Float?,
    val controllerRecord: InputCalibration?,
    val baselineRecord: InputCalibration?,
    val plannedRecord: InputCalibration?,
    val idleReason: CalibrationIdleReason?,
    val lastDecision: ThermoLogEntry?,
    val frequency: List<ThermoFrequency>,
    val notices: Set<ThermoNotice>,
    val writesActive: Boolean,
    val writeInFlight: Boolean,
    val issues: Set<ThermoZoneIssue> = emptySet(),
    val manuallyPaused: Boolean = false,
    val idleDetail: CalibrationIdleDetail? = null,
)

/** Termometr widziany przez odbiornik (lista do przypisania); [assignedTo] = strefa, jeśli już przypisany. */
data class DiscoveredThermometer(
    val mac: String,
    val name: String?,
    val rssi: Int,
    val tempC: Float,
    val humidity: Float,
    val batteryPercent: Int,
    val receivedAt: Long,
    val ageMillis: Long,
    val assignedTo: ZoneKey?,
) {
    override fun toString(): String = "DiscoveredThermometer(${AdvertisementParser.maskMac(mac)}, $tempC °C, rssi=$rssi)"
}

/**
 * Wszystko, co ekran „Termometry” pokazuje. [active] false = brak sesji (pozostałe pola domyślne).
 *
 * @property dryRun ustawienie „tryb próbny”
 * @property writesBlockedByFlag tryb rzeczywisty niedostępny w tej wersji (`Features.SENSOR_CALIBRATION_WRITES`);
 *   w trybie demo zawsze false (zapis trafia do symulatora)
 * @property writesActive kalibracje naprawdę są wysyłane (hub, nie próbny, nie zablokowane); strefa może je mieć
 *   wstrzymane ([ThermoZoneUi.writesActive])
 * @property updatedAt chwila zbudowania stanu (zegar środowiska; do liczenia wieku odczytów)
 * @property leftoverInputs wejścia tego sterownika, na których może zostać kalibracja Domika bez nadzoru (żaden
 *   korektor w trybie rzeczywistym ich nie prowadzi); strefa takiego wejścia ma też [ThermoZoneIssue.CALIBRATION_NOT_RESTORED]
 * @property previousControllerNotRestored przy zmianie / zapomnieniu sterownika nie udało się przywrócić kalibracji
 *   instalatora (brak połączenia albo błąd) – na tamtym sterowniku mogą zostać kalibracje Domika. Stan zachowany
 *   w plikach; ostrzeżenie trwa do `AppModel.dismissCalibrationLeaveWarning()`.
 */
data class ThermoUiState(
    val active: Boolean = false,
    val demo: Boolean = false,
    val scanner: ScannerStatus = ScannerStatus.OFF,
    val scanMode: ScanMode = ScanMode.OFF,
    val hub: Boolean = false,
    val enabled: Boolean = false,
    val dryRun: Boolean = true,
    val threshold: Float = ThermoProfileSettings.DEFAULT_THRESHOLD,
    val thresholds: List<Float> = ThermoProfileSettings.THRESHOLDS,
    val writesBlockedByFlag: Boolean = true,
    val writesActive: Boolean = false,
    val zones: List<ThermoZoneUi> = emptyList(),
    val discovered: List<DiscoveredThermometer> = emptyList(),
    val lastCalibrationReadAt: Long? = null,
    val updatedAt: Long = 0L,
    val leftoverInputs: List<InputKey> = emptyList(),
    val previousControllerNotRestored: Boolean = false,
) {
    companion object {
        val INACTIVE = ThermoUiState()
    }
}
