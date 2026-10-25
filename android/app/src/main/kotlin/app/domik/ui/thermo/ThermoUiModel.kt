package app.domik.ui.thermo

import android.os.Build
import androidx.annotation.StringRes
import app.domik.R
import app.domik.deimic.client.commands.ZoneKey
import app.domik.deimic.config.HomeConfig
import app.domik.integrations.thermo.AssignedThermometerUi
import app.domik.integrations.thermo.DiscoveredThermometer
import app.domik.integrations.thermo.ScanMode
import app.domik.integrations.thermo.ScannerStatus
import app.domik.integrations.thermo.ThermoFrequency
import app.domik.integrations.thermo.ThermoLogDetail
import app.domik.integrations.thermo.ThermoLogEntry
import app.domik.integrations.thermo.ThermoLogKind
import app.domik.integrations.thermo.ThermoLogResult
import app.domik.integrations.thermo.ThermoNotice
import app.domik.integrations.thermo.ThermoRuntime
import app.domik.integrations.thermo.ThermoUiState
import app.domik.integrations.thermo.ThermoZoneIssue
import app.domik.integrations.thermo.ThermoZoneStatus
import app.domik.integrations.thermo.ThermoZoneUi
import app.domik.thermo.CalibrationIdleDetail
import app.domik.thermo.CalibrationIdleReason
import app.domik.thermo.CalibrationReason
import app.domik.ui.dashboard.placeLabel
import java.text.Collator
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Czyste mapowania ekranu „Termometry pokojowe”, sekcji w oknie strefy i kafelka: formatowanie liczb, teksty
 * statusów jako identyfikatory zasobów, liczniki. Bez Compose – testowalne w JVM (ThermoUiModelTest).
 */

private const val NBSP = ' '
private const val MINUS = '−'
private const val DASH = "–"

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS

/** Tekst z zasobu z argumentami (rozwiązywany w Compose przez `resolve()`). */
data class ResText(@StringRes val res: Int, val args: List<Any> = emptyList()) {
    companion object {
        /** Gotowy napis (np. liczba z jednostką) – bez tłumaczenia. */
        fun plain(text: String) = ResText(R.string.thermo_value_plain, listOf(text))
    }
}

/** Podpisana wartość w wierszu (temperatura, wilgotność, bateria…). */
data class ThermoValue(
    @StringRes val label: Int,
    val value: ResText,
    val warning: Boolean = false,
)

// ---------------------------------------------------------------------------------------------------------------
// Formatowanie

/** „21,5 °C”; null albo wartość spoza liczb = „–”. */
fun formatDegrees(value: Float?, locale: Locale = Locale.getDefault()): String =
    if (value == null || !value.isFinite()) DASH else String.format(locale, "%.1f${NBSP}°C", if (abs(value) < 0.05f) 0f else value)

/** Przesunięcie ze znakiem: „+0,5 °C”, „−2,4 °C” (prawdziwy minus), „0,0 °C”; null = „–”. */
fun formatOffset(value: Float?, locale: Locale = Locale.getDefault()): String {
    if (value == null || !value.isFinite()) return DASH
    val tenths = (value * 10f).roundToInt()
    if (tenths == 0) return String.format(locale, "%.1f${NBSP}°C", 0f)
    val sign = if (tenths > 0) "+" else MINUS.toString()
    return sign + String.format(locale, "%.1f${NBSP}°C", abs(tenths) / 10f)
}

/** „47 %”; null = „–”. */
fun formatPercent(value: Float?, locale: Locale = Locale.getDefault()): String =
    if (value == null || !value.isFinite()) DASH else String.format(locale, "%d${NBSP}%%", value.roundToInt())

fun formatPercent(value: Int?): String = if (value == null) DASH else "$value${NBSP}%"

/** „−67 dBm”. */
fun formatRssi(rssi: Int?): String = if (rssi == null) DASH else "${if (rssi < 0) MINUS else '+'}${abs(rssi)}${NBSP}dBm"

/** Wiek odczytu: „przed chwilą”, „5 min temu”, „3 h temu”, „2 d temu”; null = „brak odczytu”. */
fun ageText(ageMillis: Long?): ResText {
    if (ageMillis == null) return ResText(R.string.thermo_age_none)
    val age = ageMillis.coerceAtLeast(0)
    return when {
        age < MINUTE_MS -> ResText(R.string.thermo_age_now)
        age < HOUR_MS -> ResText(R.string.thermo_age_minutes, listOf((age / MINUTE_MS).toInt()))
        age < DAY_MS -> ResText(R.string.thermo_age_hours, listOf((age / HOUR_MS).toInt()))
        else -> ResText(R.string.thermo_age_days, listOf((age / DAY_MS).toInt()))
    }
}

/** Wiek odczytu policzony w chwili [nowMillis] (stan jest zbudowany w [updatedAt], a ekran żyje dłużej). */
fun ageNow(ageMillis: Long?, updatedAt: Long, nowMillis: Long): Long? =
    ageMillis?.let { it + (nowMillis - updatedAt).coerceAtLeast(0) }

/** Data i godzina dla wpisów dziennika: „12.10.2026, 14:32” (wg języka i strefy). */
fun formatDateTime(millis: Long, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale).format(Instant.ofEpochMilli(millis).atZone(zone))

fun formatTime(millis: Long, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(Instant.ofEpochMilli(millis).atZone(zone))

// ---------------------------------------------------------------------------------------------------------------
// Kafelek strefy: temperatura z termometru

/** Strefy ze świeżym odczytem termometru → temperatura (kafelek pokazuje ją zamiast odczytu Deimic). */
fun freshThermometerTemps(state: ThermoUiState): Map<ZoneKey, Float> = buildMap {
    for (zone in state.zones) {
        val temp = zone.thermometer.tempC
        if (zone.thermometer.fresh && temp != null && temp.isFinite()) put(zone.zone, temp)
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Skanowanie w tle (jedno miejsce decyzji – patrz ThermoBackgroundScan)

/**
 * Jak ma pracować odbiornik: [mode] i czy trzymać usługę pierwszoplanową ([service]).
 * Tło ([ScanMode.BACKGROUND]) = to urządzenie jest hubem i funkcja włączona (w demo zawsze); otwarty ekran
 * termometrów wymusza szybkie wyszukiwanie ([ScanMode.DISCOVERY]); poza tym skan jest wyłączony.
 * Usługa tylko w prawdziwym trybie tła (nie w demo – tam nie ma Bluetooth).
 */
data class ThermoScanPlan(val mode: ScanMode, val service: Boolean)

fun thermoScanPlan(state: ThermoUiState, discoveryOpen: Boolean): ThermoScanPlan {
    if (!state.active) return ThermoScanPlan(ScanMode.OFF, service = false)
    val background = state.demo || (state.hub && state.enabled)
    val mode = when {
        discoveryOpen -> ScanMode.DISCOVERY
        background -> ScanMode.BACKGROUND
        else -> ScanMode.OFF
    }
    return ThermoScanPlan(mode, service = background && !state.demo)
}

// ---------------------------------------------------------------------------------------------------------------
// Stan odbiornika i uprawnienia

/** Akcja przy problemie z odbiornikiem. */
enum class ScannerAction(@StringRes val labelRes: Int) {
    NONE(0),
    REQUEST_PERMISSION(R.string.thermo_action_allow),
    OPEN_APP_SETTINGS(R.string.thermo_action_app_settings),
    ENABLE_BLUETOOTH(R.string.thermo_action_bluetooth),
    ENABLE_LOCATION(R.string.thermo_action_location),
}

/** Stan odbiornika dla UI: [problem] = coś trzeba naprawić, [action] to przycisk obok tekstu. */
data class ScannerStatusUi(val text: ResText, val action: ScannerAction, val problem: Boolean)

/**
 * @param permissionBlocked użytkownik odmówił zgody na stałe (system już nie pokaże okna – tylko ustawienia aplikacji)
 */
fun scannerStatusUi(
    state: ThermoUiState,
    permissionBlocked: Boolean,
    sdkInt: Int = Build.VERSION.SDK_INT,
): ScannerStatusUi {
    if (state.demo) return ScannerStatusUi(ResText(R.string.thermo_scanner_demo), ScannerAction.NONE, problem = false)
    return when (state.scanner) {
        ScannerStatus.OFF -> ScannerStatusUi(ResText(R.string.thermo_scanner_off), ScannerAction.NONE, problem = false)
        ScannerStatus.SCANNING -> ScannerStatusUi(
            ResText(if (state.scanMode == ScanMode.DISCOVERY) R.string.thermo_scanner_discovery else R.string.thermo_scanner_background),
            ScannerAction.NONE,
            problem = false,
        )
        ScannerStatus.NO_PERMISSION -> ScannerStatusUi(
            ResText(if (sdkInt >= Build.VERSION_CODES.S) R.string.thermo_scanner_no_permission else R.string.thermo_scanner_no_permission_legacy),
            if (permissionBlocked) ScannerAction.OPEN_APP_SETTINGS else ScannerAction.REQUEST_PERMISSION,
            problem = true,
        )
        ScannerStatus.BLUETOOTH_OFF ->
            ScannerStatusUi(ResText(R.string.thermo_scanner_bluetooth_off), ScannerAction.ENABLE_BLUETOOTH, problem = true)
        ScannerStatus.LOCATION_OFF ->
            ScannerStatusUi(ResText(R.string.thermo_scanner_location_off), ScannerAction.ENABLE_LOCATION, problem = true)
        ScannerStatus.UNSUPPORTED ->
            ScannerStatusUi(ResText(R.string.thermo_scanner_unsupported), ScannerAction.NONE, problem = true)
        ScannerStatus.ERROR -> ScannerStatusUi(ResText(R.string.thermo_scanner_error), ScannerAction.NONE, problem = true)
    }
}

/** Zgoda odrzucona na stałe: nie przyznana, a system nie chce już pytać (jak w `isCalendarAccessBlocked`). */
fun isPermissionBlocked(granted: Boolean, shouldShowRationale: Boolean): Boolean = !granted && !shouldShowRationale

// ---------------------------------------------------------------------------------------------------------------
// Statusy i komunikaty

@StringRes
fun ThermoNotice.textRes(): Int = when (this) {
    ThermoNotice.EXTERNAL_CHANGE -> R.string.thermo_notice_external_change
    ThermoNotice.NO_INPUT -> R.string.thermo_notice_no_input
    ThermoNotice.AMBIGUOUS_INPUT -> R.string.thermo_notice_ambiguous_input
    ThermoNotice.INPUT_IN_USE -> R.string.thermo_notice_input_in_use
    ThermoNotice.THERMOMETER_NOT_SEEN -> R.string.thermo_notice_not_seen
    ThermoNotice.THERMOMETER_STALE -> R.string.thermo_notice_stale
    ThermoNotice.BATTERY_LOW -> R.string.thermo_notice_battery_low
    ThermoNotice.CALIBRATION_READ_FAILED -> R.string.thermo_notice_read_failed
    ThermoNotice.WRITE_FAILED -> R.string.thermo_notice_write_failed
}

@StringRes
fun CalibrationIdleReason.textRes(): Int = when (this) {
    CalibrationIdleReason.NO_CALIBRATION -> R.string.thermo_idle_no_calibration
    CalibrationIdleReason.CALIBRATION_UNUSABLE -> R.string.thermo_idle_unusable
    CalibrationIdleReason.DISABLED -> R.string.thermo_idle_disabled
    CalibrationIdleReason.SENSOR_LOST -> R.string.thermo_idle_sensor_lost
    CalibrationIdleReason.NO_XIAOMI_DATA -> R.string.thermo_idle_no_thermometer_data
    CalibrationIdleReason.NO_DEIMIC_DATA -> R.string.thermo_idle_no_controller_data
    CalibrationIdleReason.WITHIN_THRESHOLD -> R.string.thermo_idle_within_threshold
    CalibrationIdleReason.RATE_LIMITED -> R.string.thermo_idle_rate_limited
    CalibrationIdleReason.AT_LIMIT -> R.string.thermo_idle_at_limit
    CalibrationIdleReason.WRITE_IN_FLIGHT -> R.string.thermo_idle_write_in_flight
}

@StringRes
fun CalibrationIdleDetail.textRes(): Int = when (this) {
    CalibrationIdleDetail.READ_REQUIRED -> R.string.thermo_idle_read_required
    CalibrationIdleDetail.STATE_NOT_SAVED -> R.string.thermo_idle_state_not_saved
}

@StringRes
fun ThermoZoneIssue.textRes(): Int = when (this) {
    ThermoZoneIssue.INPUT_CONFLICT -> R.string.thermo_issue_input_conflict
    ThermoZoneIssue.INPUT_NOT_ALLOWED -> R.string.thermo_issue_input_not_allowed
    ThermoZoneIssue.MANUAL_RESTORE -> R.string.thermo_issue_manual_restore
    ThermoZoneIssue.READ_REQUIRED -> R.string.thermo_issue_read_required
    ThermoZoneIssue.STATE_NOT_SAVED -> R.string.thermo_issue_state_not_saved
    ThermoZoneIssue.CALIBRATION_NOT_RESTORED -> R.string.thermo_issue_not_restored
}

/** Strefa z korektą wstrzymaną po ręcznym przywróceniu kalibracji instalatora (przycisk „Wznów korektę”). */
fun ThermoZoneUi.isManuallyPaused(): Boolean = manuallyPaused || ThermoZoneIssue.MANUAL_RESTORE in issues

/**
 * Dodatkowe problemy strefy ([ThermoZoneUi.issues] + wstrzymanie po ręcznym przywróceniu) jako teksty, w stałej
 * kolejności.
 */
fun zoneIssueTexts(zone: ThermoZoneUi): List<ResText> {
    val all = buildSet {
        addAll(zone.issues)
        if (zone.manuallyPaused) add(ThermoZoneIssue.MANUAL_RESTORE)
    }
    return all.sortedBy { it.ordinal }.map { ResText(it.textRes()) }
}

/**
 * Dlaczego ostatnie sprawdzenie niczego nie zmieniło: dokładniejsza przyczyna ([ThermoZoneUi.idleDetail]) ma
 * pierwszeństwo przed [ThermoZoneUi.idleReason], ale gdy opisuje ją już problem z [ThermoZoneUi.issues], wiersza nie ma
 * (żeby nie powtarzać); po ręcznym przywróceniu kalibracji wiersza nie ma (mówi o tym komunikat
 * z przyciskiem „Wznów korektę”). Tylko dla aktywnej strefy.
 */
fun zoneIdleText(zone: ThermoZoneUi): ResText? {
    if (zone.status != ThermoZoneStatus.ACTIVE || zone.isManuallyPaused()) return null
    zone.idleDetail?.let { detail ->
        val covered = when (detail) {
            CalibrationIdleDetail.READ_REQUIRED -> ThermoZoneIssue.READ_REQUIRED
            CalibrationIdleDetail.STATE_NOT_SAVED -> ThermoZoneIssue.STATE_NOT_SAVED
        } in zone.issues
        return if (covered) null else ResText(detail.textRes())
    }
    return zone.idleReason?.let { ResText(it.textRes()) }
}

/** Stan strefy jednym zdaniem (z uwzględnieniem trybu pracy: wyłączone / nie hub / próbny / rzeczywisty). */
fun zoneStatusText(zone: ThermoZoneUi, state: ThermoUiState): ResText = ResText(
    when (zone.status) {
        ThermoZoneStatus.WAITING_FOR_CONFIG -> R.string.thermo_status_waiting
        ThermoZoneStatus.ZONE_NOT_FOUND -> R.string.thermo_status_zone_not_found
        ThermoZoneStatus.NEEDS_INPUT -> R.string.thermo_status_needs_input
        ThermoZoneStatus.INPUT_IN_USE -> R.string.thermo_status_input_in_use
        ThermoZoneStatus.PAUSED -> R.string.thermo_status_paused
        ThermoZoneStatus.ACTIVE -> when {
            !state.enabled -> R.string.thermo_status_disabled
            !state.hub && !state.demo -> R.string.thermo_status_not_hub
            zone.writesActive -> R.string.thermo_status_live
            else -> R.string.thermo_status_dry_run
        }
    },
)

/** Tytuł termometru strefy: etykieta użytkownika albo „Termometr”. */
fun thermometerTitle(thermometer: AssignedThermometerUi): ResText =
    if (thermometer.label.isBlank()) ResText(R.string.thermo_thermometer_default) else ResText.plain(thermometer.label)

fun inputLabel(inputNr: Int?): ResText =
    if (inputNr == null) ResText(R.string.thermo_input_none) else ResText(R.string.thermo_input_label, listOf(inputNr))

// ---------------------------------------------------------------------------------------------------------------
// Częstość kalibracji (faza 1)

/** „Kalibracja byłaby zmieniona w ostatniej dobie:” + części „N× przy 0,5 °C”. */
data class FrequencyLine(val prefix: ResText, val parts: List<ResText>)

/**
 * Liczniki z ostatniej doby dla obu progów. W trybie rzeczywistym ([ThermoZoneUi.writesActive]) „została zmieniona”,
 * a progi inne niż wybrany są oznaczone jako symulacja; inaczej „byłaby zmieniona”. Null, gdy nie ma liczników.
 */
fun frequencyLine(zone: ThermoZoneUi, locale: Locale = Locale.getDefault()): FrequencyLine? {
    if (zone.frequency.isEmpty()) return null
    val live = zone.writesActive
    val parts = zone.frequency.sortedBy { it.threshold }.map { frequency ->
        val simulated = live && !frequency.primary
        ResText(
            if (simulated) R.string.thermo_frequency_part_simulated else R.string.thermo_frequency_part,
            listOf(frequency.calibrationsLast24h, formatDegrees(frequency.threshold, locale)),
        )
    }
    return FrequencyLine(ResText(if (live) R.string.thermo_frequency_did else R.string.thermo_frequency_would), parts)
}

// ---------------------------------------------------------------------------------------------------------------
// Sekcja „Termometr w pokoju” w oknie strefy

/**
 * Zawartość sekcji w oknie strefy.
 *
 * @property rows termometr (temperatura, wilgotność, bateria, odczyt), czujnik Deimic, różnica i kalibracje
 * @property notices komunikaty strefy (ThermoNotice); [idle] – dlaczego ostatnie sprawdzenie niczego nie zmieniło
 * @property issues dodatkowe problemy strefy ([ThermoZoneIssue]); [canResume] – przycisk „Wznów korektę”
 * @property canRestore przycisk „Przywróć kalibrację instalatora”; [canAcknowledge] – „Przyjmij nową kalibrację”
 * @property writesActive zapisy tej strefy trafiają do sterownika (opis skutków w oknie potwierdzenia)
 */
data class ThermoZoneSection(
    val title: ResText,
    val rows: List<ThermoValue>,
    val status: ResText,
    val notices: List<ResText>,
    val idle: ResText?,
    val frequency: FrequencyLine?,
    val canRestore: Boolean,
    val canAcknowledge: Boolean,
    val writesActive: Boolean,
    val issues: List<ResText> = emptyList(),
    val canResume: Boolean = false,
)

fun thermoZoneSection(
    state: ThermoUiState,
    zone: ThermoZoneUi,
    nowMillis: Long = state.updatedAt,
    locale: Locale = Locale.getDefault(),
): ThermoZoneSection {
    val rows = ArrayList<ThermoValue>()
    rows += assignedValues(zone.thermometer, state.updatedAt, nowMillis, locale)
    rows += ThermoValue(R.string.thermo_row_deimic, ResText.plain(formatDegrees(zone.deimicC, locale)))
    val difference = zone.differenceC ?: run {
        val xiaomi = zone.thermometer.tempC
        val deimic = zone.deimicC
        if (zone.thermometer.fresh && xiaomi != null && deimic != null) xiaomi - deimic else null
    }
    rows += ThermoValue(R.string.thermo_row_difference, ResText.plain(formatOffset(difference, locale)))
    rows += ThermoValue(R.string.thermo_row_calibration_now, ResText.plain(formatOffset(zone.calibrationOffsetC, locale)))
    rows += ThermoValue(R.string.thermo_row_calibration_baseline, ResText.plain(formatOffset(zone.baselineOffsetC, locale)))
    val planned = zone.plannedOffsetC
    if (planned != null && planned != zone.calibrationOffsetC) {
        rows += ThermoValue(R.string.thermo_row_calibration_planned, ResText.plain(formatOffset(planned, locale)))
    }
    return ThermoZoneSection(
        title = thermometerTitle(zone.thermometer),
        rows = rows,
        status = zoneStatusText(zone, state),
        notices = zone.notices.sortedBy { it.ordinal }.map { ResText(it.textRes()) },
        idle = zoneIdleText(zone),
        frequency = frequencyLine(zone, locale),
        canRestore = zone.baselineRecord != null && !zone.writeInFlight && zone.status != ThermoZoneStatus.ZONE_NOT_FOUND,
        canAcknowledge = ThermoNotice.EXTERNAL_CHANGE in zone.notices || zone.status == ThermoZoneStatus.PAUSED,
        writesActive = zone.writesActive,
        issues = zoneIssueTexts(zone),
        canResume = zone.isManuallyPaused() && !zone.writeInFlight,
    )
}

/** Odczyt przypisanego termometru: temperatura, wilgotność, bateria, wiek. */
fun assignedValues(
    thermometer: AssignedThermometerUi,
    updatedAt: Long,
    nowMillis: Long,
    locale: Locale = Locale.getDefault(),
): List<ThermoValue> = listOf(
    ThermoValue(R.string.thermo_row_temperature, ResText.plain(formatDegrees(thermometer.tempC, locale)), warning = !thermometer.fresh),
    ThermoValue(R.string.thermo_row_humidity, ResText.plain(formatPercent(thermometer.humidity, locale))),
    ThermoValue(
        R.string.thermo_row_battery,
        ResText.plain(formatPercent(thermometer.batteryPercent)),
        warning = (thermometer.batteryPercent ?: 100) <= LOW_BATTERY_PERCENT,
    ),
    ThermoValue(
        R.string.thermo_row_reading,
        ageText(ageNow(thermometer.ageMillis, updatedAt, nowMillis)),
        warning = !thermometer.fresh,
    ),
)

/** Próg „bateria na wyczerpaniu” – jak w runtime (`LOW_BATTERY_PERCENT`). */
const val LOW_BATTERY_PERCENT = 10

// ---------------------------------------------------------------------------------------------------------------
// Karta „Termometry”

/** Wykryty termometr: wartości i – jeśli przypisany – nazwa strefy. */
data class DiscoveredRow(
    val mac: String,
    val title: String,
    val values: List<ThermoValue>,
    val assignedZoneName: String?,
)

/**
 * Wykryte termometry w stałej kolejności (nie zależnej od ostatnio odebranej reklamy ani sygnału): najpierw przypisane,
 * alfabetycznie po nazwie strefy, potem nieprzypisane wg nazwy urządzenia i adresu MAC.
 * [zoneNames] – nazwy stref z przypisań (klucz = strefa).
 */
fun discoveredRows(
    state: ThermoUiState,
    zoneNames: (ZoneKey) -> String?,
    nowMillis: Long = state.updatedAt,
    locale: Locale = Locale.getDefault(),
): List<DiscoveredRow> {
    val collator = Collator.getInstance(locale)
    val rows = state.discovered.map { found ->
        DiscoveredRow(
            mac = found.mac,
            title = found.name.orEmpty(),
            values = discoveredValues(found, state.updatedAt, nowMillis, locale),
            assignedZoneName = found.assignedTo?.let(zoneNames),
        )
    }
    return rows.sortedWith(
        compareBy<DiscoveredRow> { it.assignedZoneName == null }
            .thenComparator { a, b -> collator.compare(a.assignedZoneName.orEmpty(), b.assignedZoneName.orEmpty()) }
            .thenComparator { a, b -> collator.compare(a.title, b.title) }
            .thenBy { it.mac },
    )
}

fun discoveredValues(
    found: DiscoveredThermometer,
    updatedAt: Long,
    nowMillis: Long,
    locale: Locale = Locale.getDefault(),
): List<ThermoValue> = listOf(
    ThermoValue(R.string.thermo_row_temperature, ResText.plain(formatDegrees(found.tempC, locale))),
    ThermoValue(R.string.thermo_row_humidity, ResText.plain(formatPercent(found.humidity, locale))),
    ThermoValue(R.string.thermo_row_battery, ResText.plain(formatPercent(found.batteryPercent)), warning = found.batteryPercent <= LOW_BATTERY_PERCENT),
    ThermoValue(R.string.thermo_row_signal, ResText.plain(formatRssi(found.rssi))),
    ThermoValue(R.string.thermo_row_reading, ageText(ageNow(found.ageMillis, updatedAt, nowMillis))),
)

/** Strefa do wyboru przy przypisywaniu termometru. */
data class ZoneChoice(val key: ZoneKey, val name: String, val room: String, val hasThermometer: Boolean)

/** Strefy z konfiguracji sterownika, w kolejności z bazy; [ZoneChoice.hasThermometer] = strefa ma już termometr. */
fun zoneChoices(config: HomeConfig?, state: ThermoUiState): List<ZoneChoice> {
    if (config == null) return emptyList()
    val assigned = state.zones.map { it.zone }.toSet()
    return config.heatingZones.distinctBy { it.module to it.outputNr }.map { zone ->
        val key = ZoneKey(zone.module, zone.outputNr)
        ZoneChoice(key, zone.name, placeLabel(config, zone.name, zone.roomId, zone.levelId), key in assigned)
    }
}

/** Nazwa strefy z konfiguracji (albo z przypisania, gdy strefy w konfiguracji nie ma). */
fun zoneName(config: HomeConfig?, state: ThermoUiState, key: ZoneKey): String? =
    config?.heatingZone(key.module, key.outputNr)?.name ?: state.zones.firstOrNull { it.zone == key }?.name?.takeIf { it.isNotBlank() }

/**
 * Wejścia temperatury do wyboru dla strefy w stanie „wskaż wejście”: kandydaci z pomieszczenia strefy, a gdy
 * pomieszczenie nie ma żadnego – wszystkie wejścia temperatury modułu strefy.
 */
fun inputChoices(config: HomeConfig?, zone: ThermoZoneUi): List<Int> {
    if (zone.inputCandidates.isNotEmpty()) return zone.inputCandidates
    return config?.analogInputs.orEmpty()
        .filter { it.isTemperature && it.module == zone.zone.module }
        .map { it.inputNr }
        .distinct()
        .sorted()
}

// ---------------------------------------------------------------------------------------------------------------
// Karta „Kalibracja”

/** Wiersz tabeli stref: wejście, kalibracja instalatora / obecna / planowana, status. */
data class CalibrationRow(
    val zone: ZoneKey,
    val zoneName: String,
    val room: String?,
    val input: ResText,
    val baseline: String,
    val current: String,
    val planned: String,
    val status: ResText,
    val notices: List<ResText>,
    val issues: List<ResText> = emptyList(),
    val idle: ResText? = null,
    val canResume: Boolean = false,
)

fun calibrationRows(state: ThermoUiState, locale: Locale = Locale.getDefault()): List<CalibrationRow> = state.zones.map { zone ->
    CalibrationRow(
        zone = zone.zone,
        zoneName = zone.name,
        room = zone.roomName?.takeIf { it.isNotBlank() && !it.equals(zone.name, ignoreCase = true) },
        input = inputLabel(zone.inputNr),
        baseline = formatOffset(zone.baselineOffsetC, locale),
        current = formatOffset(zone.calibrationOffsetC, locale),
        planned = formatOffset(zone.plannedOffsetC, locale),
        status = zoneStatusText(zone, state),
        notices = zone.notices.sortedBy { it.ordinal }.map { ResText(it.textRes()) },
        issues = zoneIssueTexts(zone),
        idle = zoneIdleText(zone),
        canResume = zone.isManuallyPaused() && !zone.writeInFlight,
    )
}

/** Próg do wyboru: „0,5 °C”. */
fun thresholdLabel(threshold: Float, locale: Locale = Locale.getDefault()): String = formatDegrees(threshold, locale)

// ---------------------------------------------------------------------------------------------------------------
// Karta „Dziennik”

/** Ostatni wpis dziennika strefy: kiedy, co, z jakim skutkiem i dlaczego. */
data class LogDecision(
    val whenText: String,
    val kind: ResText,
    val result: ResText?,
    val reason: ResText?,
    val values: ResText?,
)

/** Liczniki jednego progu. */
data class FrequencyRow(val threshold: String, val primary: Boolean, val last24h: Int, val total: Int, val restores: Int)

data class LogZone(
    val zoneName: String,
    val room: String?,
    val status: ResText,
    val decision: LogDecision?,
    val idle: ResText?,
    val frequency: List<FrequencyRow>,
    val sinceText: String?,
    val live: Boolean,
)

fun logZones(
    state: ThermoUiState,
    locale: Locale = Locale.getDefault(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): List<LogZone> = state.zones.map { zone ->
    LogZone(
        zoneName = zone.name,
        room = zone.roomName?.takeIf { it.isNotBlank() && !it.equals(zone.name, ignoreCase = true) },
        status = zoneStatusText(zone, state),
        decision = zone.lastDecision?.let { logDecision(it, locale, zoneId) },
        idle = zoneIdleText(zone),
        frequency = zone.frequency.sortedBy { it.threshold }.map { frequencyRow(it, locale) },
        sinceText = zone.frequency.firstNotNullOfOrNull { it.since }?.let { formatDateTime(it, locale, zoneId) },
        live = zone.writesActive,
    )
}

fun frequencyRow(frequency: ThermoFrequency, locale: Locale = Locale.getDefault()) = FrequencyRow(
    threshold = thresholdLabel(frequency.threshold, locale),
    primary = frequency.primary,
    last24h = frequency.calibrationsLast24h,
    total = frequency.calibrationsTotal,
    restores = frequency.restoresTotal,
)

fun logDecision(entry: ThermoLogEntry, locale: Locale = Locale.getDefault(), zoneId: ZoneId = ZoneId.systemDefault()): LogDecision {
    val hasValues = entry.xiaomiC != null || entry.deimicC != null || entry.differenceC != null
    return LogDecision(
        whenText = formatDateTime(entry.at, locale, zoneId),
        kind = ResText(entry.kind.textRes()),
        result = entry.result.textRes()?.let { ResText(it) },
        reason = entry.reason?.let { ResText(it.textRes()) },
        values = if (hasValues) {
            ResText(
                R.string.thermo_log_values,
                listOf(formatDegrees(entry.xiaomiC, locale), formatDegrees(entry.deimicC, locale), formatOffset(entry.differenceC, locale)),
            )
        } else {
            null
        },
    )
}

@StringRes
fun ThermoLogKind.textRes(): Int = when (this) {
    ThermoLogKind.CALIBRATION -> R.string.thermo_log_kind_calibration
    ThermoLogKind.RESTORE -> R.string.thermo_log_kind_restore
    ThermoLogKind.MANUAL_RESTORE -> R.string.thermo_log_kind_manual_restore
    ThermoLogKind.READBACK -> R.string.thermo_log_kind_readback
    ThermoLogKind.BASELINE_CAPTURED -> R.string.thermo_log_kind_baseline
    ThermoLogKind.EXTERNAL_CHANGE -> R.string.thermo_log_kind_external
    ThermoLogKind.EXTERNAL_CHANGE_ACCEPTED -> R.string.thermo_log_kind_external_accepted
}

/** Tekst wyniku wpisu; null dla [ThermoLogResult.NONE] (wpis informacyjny). */
@StringRes
fun ThermoLogResult.textRes(): Int? = when (this) {
    ThermoLogResult.DRY_RUN -> R.string.thermo_log_result_dry_run
    ThermoLogResult.CONFIRMED -> R.string.thermo_log_result_confirmed
    ThermoLogResult.FAILED -> R.string.thermo_log_result_failed
    ThermoLogResult.VERIFIED -> R.string.thermo_log_result_verified
    ThermoLogResult.MISMATCH -> R.string.thermo_log_result_mismatch
    ThermoLogResult.NONE -> null
}

@StringRes
fun CalibrationReason.textRes(): Int = when (this) {
    CalibrationReason.DIFFERENCE -> R.string.thermo_log_reason_difference
    CalibrationReason.SENSOR_LOST -> R.string.thermo_log_reason_sensor_lost
    CalibrationReason.DISABLED -> R.string.thermo_log_reason_disabled
}

@StringRes
fun ThermoLogDetail.textRes(): Int = when (this) {
    ThermoLogDetail.NOT_SENT_READ_FAILED -> R.string.thermo_log_detail_read_failed
    ThermoLogDetail.NOT_SENT_RECORD_CHANGED -> R.string.thermo_log_detail_record_changed
    ThermoLogDetail.LEAVING_LIVE -> R.string.thermo_log_detail_leaving_live
    ThermoLogDetail.LEAVING_CONTROLLER -> R.string.thermo_log_detail_leaving_controller
}

/** Ile ostatnich wpisów dziennika pokazuje karta „Dziennik”. */
const val LOG_LIMIT = 200

/**
 * Wpis pełnego dziennika: kiedy, której strefy, jaki próg, co się stało, jaka różnica, jak zmieniła się kalibracja
 * (przesunięcie w °C przy 20 °C), tryb (próbny / wysłano / nie wysłano), wynik i dodatkowy opis.
 */
data class LogEntryRow(
    val whenText: String,
    val zoneName: ResText,
    val input: ResText,
    val threshold: String?,
    val kind: ResText,
    val difference: String?,
    val change: ResText?,
    val mode: ResText?,
    val result: ResText?,
    val reason: ResText?,
    val detail: ResText?,
)

/** Rodzaje wpisów, które dotyczą zapisu kalibracji (tylko one mają tryb „próbny / wysłano”). */
private val WRITE_KINDS = setOf(ThermoLogKind.CALIBRATION, ThermoLogKind.RESTORE, ThermoLogKind.MANUAL_RESTORE)

/** Najnowsze [limit] wpisów, najnowszy pierwszy. Nazwa strefy z przypisań; bez niej „Wyjście N”. */
fun logEntryRows(
    entries: List<ThermoLogEntry>,
    state: ThermoUiState,
    limit: Int = LOG_LIMIT,
    locale: Locale = Locale.getDefault(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): List<LogEntryRow> = entries.sortedByDescending { it.at }.take(limit).map { entry ->
    val name = state.zones.firstOrNull { it.zone.module == entry.module && it.zone.outputNr == entry.outputNr }?.name
    val before = entry.previous?.let { ThermoRuntime.offsetC(it, null) }
    val after = entry.next?.let { ThermoRuntime.offsetC(it, null) }
    val notSent = entry.detail == ThermoLogDetail.NOT_SENT_READ_FAILED || entry.detail == ThermoLogDetail.NOT_SENT_RECORD_CHANGED
    LogEntryRow(
        whenText = formatDateTime(entry.at, locale, zoneId),
        zoneName = if (name.isNullOrBlank()) ResText(R.string.thermo_log_output_unknown, listOf(entry.outputNr)) else ResText.plain(name),
        input = inputLabel(entry.inputNr),
        threshold = entry.threshold?.let { thresholdLabel(it, locale) },
        kind = ResText(entry.kind.textRes()),
        difference = entry.differenceC?.let { formatOffset(it, locale) },
        change = when {
            before != null && after != null ->
                ResText(R.string.thermo_log_change, listOf(formatOffset(before, locale), formatOffset(after, locale)))
            after != null -> ResText(R.string.thermo_log_change_to, listOf(formatOffset(after, locale)))
            else -> null
        },
        mode = if (entry.kind in WRITE_KINDS) {
            ResText(
                when {
                    entry.dryRun -> R.string.thermo_log_mode_dry
                    notSent -> R.string.thermo_log_mode_not_sent
                    else -> R.string.thermo_log_mode_sent
                },
            )
        } else {
            null
        },
        result = entry.result.textRes()?.let { ResText(it) },
        reason = entry.reason?.let { ResText(it.textRes()) },
        detail = entry.detail?.let { ResText(it.textRes()) },
    )
}

// ---------------------------------------------------------------------------------------------------------------
// Ostrzeżenie o kalibracji, która mogła zostać na sterowniku

/**
 * Ostrzeżenie na ekranie „Termometry pokojowe” i w menu: [inputs] – wejścia tego sterownika (np. „moduł 1, wejście 4”),
 * na których może zostać kalibracja Domika; [previousController] – to samo na poprzednio używanym sterowniku.
 */
data class LeaveWarning(val inputs: List<String>, val previousController: Boolean)

/** Null, gdy nic nie trzeba ostrzegać. */
fun leaveWarning(state: ThermoUiState): LeaveWarning? {
    if (state.leftoverInputs.isEmpty() && !state.previousControllerNotRestored) return null
    return LeaveWarning(
        inputs = state.leftoverInputs.sortedWith(compareBy({ it.module }, { it.inputNr })).map { "${it.module}/${it.inputNr}" },
        previousController = state.previousControllerNotRestored,
    )
}

/** Teksty ostrzeżenia (bez przycisku): jeden akapit na sterownik. */
fun LeaveWarning.texts(): List<ResText> = buildList {
    if (inputs.isNotEmpty()) add(ResText(R.string.thermo_leave_warning_inputs, listOf(inputs.joinToString(", "))))
    if (previousController) add(ResText(R.string.thermo_leave_warning_previous))
}
