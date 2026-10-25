package app.domik.ui.thermo

import app.domik.R
import app.domik.deimic.client.commands.ZoneKey
import app.domik.deimic.config.AnalogInput
import app.domik.deimic.config.HeatingZone
import app.domik.deimic.config.HomeConfig
import app.domik.deimic.config.Level
import app.domik.deimic.config.Room
import app.domik.deimic.protocol.InputCalibration
import app.domik.integrations.thermo.AssignedThermometerUi
import app.domik.integrations.thermo.DiscoveredThermometer
import app.domik.integrations.thermo.ScanMode
import app.domik.integrations.thermo.ScannerStatus
import app.domik.integrations.thermo.ThermoFrequency
import app.domik.data.InputKey
import app.domik.integrations.thermo.ThermoLogDetail
import app.domik.integrations.thermo.ThermoLogEntry
import app.domik.integrations.thermo.ThermoLogKind
import app.domik.integrations.thermo.ThermoLogResult
import app.domik.integrations.thermo.ThermoNotice
import app.domik.integrations.thermo.ThermoUiState
import app.domik.integrations.thermo.ThermoZoneIssue
import app.domik.integrations.thermo.ThermoZoneStatus
import app.domik.integrations.thermo.ThermoZoneUi
import app.domik.thermo.CalibrationIdleDetail
import app.domik.thermo.CalibrationIdleReason
import app.domik.thermo.CalibrationReason
import java.time.ZoneId
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ThermoUiModelTest {
    private val pl = Locale.forLanguageTag("pl-PL")
    private val en = Locale.US
    private val nb = ' '
    private val minus = '−'

    private val key = ZoneKey(1, 5)

    private fun thermometer(
        temp: Float? = 21.3f,
        fresh: Boolean = true,
        battery: Int? = 87,
        label: String = "",
        age: Long? = 90_000L,
    ) = AssignedThermometerUi("02:AA:BB:CC:DD:01", label, temp, 47f, battery, -62, 1_000L, age, fresh)

    private fun zone(
        status: ThermoZoneStatus = ThermoZoneStatus.ACTIVE,
        thermometer: AssignedThermometerUi = thermometer(),
        notices: Set<ThermoNotice> = emptySet(),
        writesActive: Boolean = false,
        frequency: List<ThermoFrequency> = emptyList(),
        baseline: InputCalibration? = InputCalibration(1u, 0u, 200f, 0f, 176f, 0f),
        inputNr: Int? = 4,
        candidates: List<Int> = listOf(4),
        deimic: Float? = 20.6f,
        difference: Float? = 0.7f,
        idle: CalibrationIdleReason? = null,
        last: ThermoLogEntry? = null,
        writeInFlight: Boolean = false,
    ) = ThermoZoneUi(
        zone = key, name = "Pokój 3", roomName = "Sypialnia", status = status, inputNr = inputNr, inputCandidates = candidates,
        thermometer = thermometer, deimicC = deimic, averageXiaomiC = 21.3f, averageDeimicC = 20.6f, differenceC = difference,
        calibrationOffsetC = -2.4f, baselineOffsetC = -2.4f, plannedOffsetC = -1.7f, shiftFromBaselineC = 0.7f,
        controllerRecord = baseline, baselineRecord = baseline, plannedRecord = null, idleReason = idle, lastDecision = last,
        frequency = frequency, notices = notices, writesActive = writesActive, writeInFlight = writeInFlight,
    )

    private fun state(
        zones: List<ThermoZoneUi> = listOf(zone()),
        hub: Boolean = true,
        enabled: Boolean = true,
        demo: Boolean = false,
        active: Boolean = true,
        scanner: ScannerStatus = ScannerStatus.SCANNING,
        scanMode: ScanMode = ScanMode.BACKGROUND,
        discovered: List<DiscoveredThermometer> = emptyList(),
    ) = ThermoUiState(
        active = active, demo = demo, scanner = scanner, scanMode = scanMode, hub = hub, enabled = enabled, zones = zones,
        discovered = discovered, updatedAt = 10_000L,
    )

    // formatowanie

    @Test
    fun `temperatury i przesuniecia sa formatowane po polsku z przecinkiem`() {
        assertEquals("21,5${nb}°C", formatDegrees(21.54f, pl))
        assertEquals("21.5${nb}°C", formatDegrees(21.54f, en))
        assertEquals("–", formatDegrees(null, pl))
        assertEquals("–", formatDegrees(Float.NaN, pl))
        assertEquals("+0,5${nb}°C", formatOffset(0.5f, pl))
        assertEquals("${minus}2,4${nb}°C", formatOffset(-2.4f, pl))
        assertEquals("0,0${nb}°C", formatOffset(0.04f, pl))
        assertEquals("0,0${nb}°C", formatOffset(-0.04f, pl))
        assertEquals("–", formatOffset(null, pl))
    }

    @Test
    fun `wilgotnosc, bateria i sygnal`() {
        assertEquals("47${nb}%", formatPercent(46.6f, pl))
        assertEquals("87${nb}%", formatPercent(87))
        assertEquals("–", formatPercent(null as Int?))
        assertEquals("${minus}67${nb}dBm", formatRssi(-67))
    }

    @Test
    fun `wiek odczytu`() {
        assertEquals(ResText(R.string.thermo_age_none), ageText(null))
        assertEquals(ResText(R.string.thermo_age_now), ageText(59_999))
        assertEquals(ResText(R.string.thermo_age_now), ageText(-5))
        assertEquals(ResText(R.string.thermo_age_minutes, listOf(1)), ageText(60_000))
        assertEquals(ResText(R.string.thermo_age_minutes, listOf(59)), ageText(59 * 60_000L + 59_000))
        assertEquals(ResText(R.string.thermo_age_hours, listOf(3)), ageText(3 * 3_600_000L))
        assertEquals(ResText(R.string.thermo_age_days, listOf(2)), ageText(2 * 86_400_000L + 1))
    }

    @Test
    fun `wiek rosnie razem z czasem od zbudowania stanu`() {
        assertEquals(90_000L + 20_000L, ageNow(90_000L, updatedAt = 10_000L, nowMillis = 30_000L))
        assertEquals(90_000L, ageNow(90_000L, updatedAt = 10_000L, nowMillis = 5_000L)) // zegar cofnięty
        assertNull(ageNow(null, 0, 1))
    }

    // kafelek

    @Test
    fun `tylko swieze odczyty termometrow trafiaja na kafelek`() {
        val other = ZoneKey(1, 6)
        val stale = zone(thermometer = thermometer(fresh = false)).copy(zone = other)
        val nan = zone(thermometer = thermometer(temp = Float.NaN)).copy(zone = ZoneKey(1, 7))
        val none = zone(thermometer = thermometer(temp = null)).copy(zone = ZoneKey(1, 8))
        val temps = freshThermometerTemps(state(zones = listOf(zone(), stale, nan, none)))
        assertEquals(mapOf(key to 21.3f), temps)
        assertTrue(freshThermometerTemps(ThermoUiState.INACTIVE).isEmpty())
    }

    // skanowanie

    @Test
    fun `plan skanowania - tlo tylko dla huba z wlaczona funkcja`() {
        assertEquals(ThermoScanPlan(ScanMode.BACKGROUND, service = true), thermoScanPlan(state(), discoveryOpen = false))
        assertEquals(ThermoScanPlan(ScanMode.OFF, false), thermoScanPlan(state(hub = false), false))
        assertEquals(ThermoScanPlan(ScanMode.OFF, false), thermoScanPlan(state(enabled = false), false))
        assertEquals(ThermoScanPlan(ScanMode.OFF, false), thermoScanPlan(ThermoUiState.INACTIVE, false))
    }

    @Test
    fun `otwarty ekran termometrow wymusza wyszukiwanie, ale usluga zalezy od tla`() {
        assertEquals(ThermoScanPlan(ScanMode.DISCOVERY, service = true), thermoScanPlan(state(), discoveryOpen = true))
        assertEquals(ThermoScanPlan(ScanMode.DISCOVERY, service = false), thermoScanPlan(state(hub = false), discoveryOpen = true))
        assertEquals(ThermoScanPlan(ScanMode.OFF, false), thermoScanPlan(ThermoUiState.INACTIVE, discoveryOpen = true))
    }

    @Test
    fun `w demo skan dziala zawsze, ale bez uslugi`() {
        val demo = state(demo = true, hub = false, enabled = false)
        assertEquals(ThermoScanPlan(ScanMode.BACKGROUND, service = false), thermoScanPlan(demo, discoveryOpen = false))
        assertEquals(ThermoScanPlan(ScanMode.DISCOVERY, service = false), thermoScanPlan(demo, discoveryOpen = true))
    }

    @Test
    fun `stan odbiornika - akcje i teksty`() {
        fun ui(status: ScannerStatus, blocked: Boolean = false, sdk: Int = 34, demo: Boolean = false) =
            scannerStatusUi(state(scanner = status, demo = demo), blocked, sdk)
        assertEquals(ScannerAction.REQUEST_PERMISSION, ui(ScannerStatus.NO_PERMISSION).action)
        assertEquals(ScannerAction.OPEN_APP_SETTINGS, ui(ScannerStatus.NO_PERMISSION, blocked = true).action)
        assertEquals(R.string.thermo_scanner_no_permission, ui(ScannerStatus.NO_PERMISSION).text.res)
        assertEquals(R.string.thermo_scanner_no_permission_legacy, ui(ScannerStatus.NO_PERMISSION, sdk = 30).text.res)
        assertEquals(ScannerAction.ENABLE_BLUETOOTH, ui(ScannerStatus.BLUETOOTH_OFF).action)
        assertEquals(ScannerAction.ENABLE_LOCATION, ui(ScannerStatus.LOCATION_OFF).action)
        assertTrue(ui(ScannerStatus.LOCATION_OFF).problem)
        assertEquals(ScannerAction.NONE, ui(ScannerStatus.UNSUPPORTED).action)
        assertTrue(ui(ScannerStatus.ERROR).problem)
        assertFalse(ui(ScannerStatus.SCANNING).problem)
        assertEquals(R.string.thermo_scanner_background, ui(ScannerStatus.SCANNING).text.res)
        assertEquals(
            R.string.thermo_scanner_discovery,
            scannerStatusUi(state(scanner = ScannerStatus.SCANNING, scanMode = ScanMode.DISCOVERY), false, 34).text.res,
        )
        assertEquals(R.string.thermo_scanner_demo, ui(ScannerStatus.NO_PERMISSION, demo = true).text.res)
        assertFalse(ui(ScannerStatus.NO_PERMISSION, demo = true).problem)
    }

    @Test
    fun `trwala odmowa zgody`() {
        assertTrue(isPermissionBlocked(granted = false, shouldShowRationale = false))
        assertFalse(isPermissionBlocked(granted = false, shouldShowRationale = true))
        assertFalse(isPermissionBlocked(granted = true, shouldShowRationale = false))
    }

    // statusy i komunikaty

    @Test
    fun `kazdy status, komunikat i powod bezczynnosci ma osobny tekst`() {
        val notices = ThermoNotice.entries.map { it.textRes() }
        assertEquals(notices.size, notices.toSet().size)
        assertTrue(notices.all { it != 0 })
        val idle = CalibrationIdleReason.entries.map { it.textRes() }
        assertEquals(idle.size, idle.toSet().size)
        val kinds = ThermoLogKind.entries.map { it.textRes() }
        assertEquals(kinds.size, kinds.toSet().size)
        val reasons = CalibrationReason.entries.map { it.textRes() }
        assertEquals(reasons.size, reasons.toSet().size)
        val results = ThermoLogResult.entries.mapNotNull { it.textRes() }
        assertEquals(ThermoLogResult.entries.size - 1, results.size) // NONE bez tekstu
        assertNull(ThermoLogResult.NONE.textRes())
        val details = CalibrationIdleDetail.entries.map { it.textRes() }
        assertEquals(details.size, details.toSet().size)
        val issues = ThermoZoneIssue.entries.map { it.textRes() }
        assertEquals(issues.size, issues.toSet().size)
        assertTrue(issues.all { it != 0 })
        val logDetails = ThermoLogDetail.entries.map { it.textRes() }
        assertEquals(logDetails.size, logDetails.toSet().size)
        val statuses = ThermoZoneStatus.entries.map { zoneStatusText(zone(status = it), state()).res }
        assertEquals(statuses.size, statuses.toSet().size)
    }

    @Test
    fun `status aktywnej strefy zalezy od trybu pracy`() {
        val active = zone(writesActive = true)
        assertEquals(R.string.thermo_status_live, zoneStatusText(active, state()).res)
        assertEquals(R.string.thermo_status_dry_run, zoneStatusText(zone(), state()).res)
        assertEquals(R.string.thermo_status_disabled, zoneStatusText(active, state(enabled = false)).res)
        assertEquals(R.string.thermo_status_not_hub, zoneStatusText(zone(), state(hub = false)).res)
        // W demo hub nie jest wymagany.
        assertEquals(R.string.thermo_status_dry_run, zoneStatusText(zone(), state(hub = false, demo = true)).res)
        assertEquals(R.string.thermo_status_paused, zoneStatusText(zone(status = ThermoZoneStatus.PAUSED), state()).res)
    }

    // częstość

    private val frequency = listOf(
        ThermoFrequency(1.0f, primary = false, calibrationsLast24h = 2, calibrationsTotal = 5, restoresTotal = 1, since = 1_000L),
        ThermoFrequency(0.5f, primary = true, calibrationsLast24h = 7, calibrationsTotal = 20, restoresTotal = 0, since = 1_000L),
    )

    @Test
    fun `licznik fazy 1 w trybie probnym`() {
        val line = checkNotNull(frequencyLine(zone(frequency = frequency), pl))
        assertEquals(R.string.thermo_frequency_would, line.prefix.res)
        // Progi rosnąco: najpierw 0,5, potem 1,0.
        assertEquals(
            listOf(
                ResText(R.string.thermo_frequency_part, listOf(7, "0,5${nb}°C")),
                ResText(R.string.thermo_frequency_part, listOf(2, "1,0${nb}°C")),
            ),
            line.parts,
        )
    }

    @Test
    fun `licznik w trybie rzeczywistym oznacza drugi prog jako symulacje`() {
        val line = checkNotNull(frequencyLine(zone(frequency = frequency, writesActive = true), pl))
        assertEquals(R.string.thermo_frequency_did, line.prefix.res)
        assertEquals(R.string.thermo_frequency_part, line.parts[0].res)
        assertEquals(R.string.thermo_frequency_part_simulated, line.parts[1].res)
        assertNull(frequencyLine(zone(), pl))
    }

    // sekcja w oknie strefy

    @Test
    fun `sekcja okna strefy zbiera odczyt termometru, czujnik Deimic i kalibracje`() {
        val section = thermoZoneSection(state(), zone(), nowMillis = 10_000L, locale = pl)
        val rows = section.rows.associate { it.label to it.value }
        assertEquals(ResText.plain("21,3${nb}°C"), rows[R.string.thermo_row_temperature])
        assertEquals(ResText.plain("47${nb}%"), rows[R.string.thermo_row_humidity])
        assertEquals(ResText.plain("87${nb}%"), rows[R.string.thermo_row_battery])
        assertEquals(ResText(R.string.thermo_age_minutes, listOf(1)), rows[R.string.thermo_row_reading])
        assertEquals(ResText.plain("20,6${nb}°C"), rows[R.string.thermo_row_deimic])
        assertEquals(ResText.plain("+0,7${nb}°C"), rows[R.string.thermo_row_difference])
        assertEquals(ResText.plain("${minus}2,4${nb}°C"), rows[R.string.thermo_row_calibration_now])
        assertEquals(ResText.plain("${minus}2,4${nb}°C"), rows[R.string.thermo_row_calibration_baseline])
        assertEquals(ResText.plain("${minus}1,7${nb}°C"), rows[R.string.thermo_row_calibration_planned])
        assertTrue(section.canRestore)
        assertFalse(section.canAcknowledge)
        assertEquals(ResText(R.string.thermo_thermometer_default), section.title)
    }

    @Test
    fun `sekcja pokazuje etykiete termometru, wiek uplywa, a stary odczyt jest ostrzezeniem`() {
        val stale = zone(thermometer = thermometer(fresh = false, label = "Narożnik", age = 3_600_000L), notices = setOf(ThermoNotice.THERMOMETER_STALE))
        val section = thermoZoneSection(state(zones = listOf(stale)), stale, nowMillis = 10_000L + 3_600_000L, locale = pl)
        assertEquals(ResText.plain("Narożnik"), section.title)
        val reading = section.rows.first { it.label == R.string.thermo_row_reading }
        assertEquals(ResText(R.string.thermo_age_hours, listOf(2)), reading.value)
        assertTrue(reading.warning)
        assertEquals(listOf(ResText(R.string.thermo_notice_stale)), section.notices)
    }

    @Test
    fun `zmiana kalibracji poza Domikiem pokazuje przycisk przyjecia`() {
        val paused = zone(status = ThermoZoneStatus.PAUSED, notices = setOf(ThermoNotice.EXTERNAL_CHANGE))
        val section = thermoZoneSection(state(zones = listOf(paused)), paused, locale = pl)
        assertTrue(section.canAcknowledge)
        assertEquals(listOf(ResText(R.string.thermo_notice_external_change)), section.notices)
        assertNull(section.idle) // powód bezczynności tylko dla działającej strefy
    }

    @Test
    fun `przywracanie niedostepne bez kalibracji bazowej i w trakcie zapisu`() {
        assertFalse(thermoZoneSection(state(), zone(baseline = null), locale = pl).canRestore)
        assertFalse(thermoZoneSection(state(), zone(writeInFlight = true), locale = pl).canRestore)
        assertFalse(thermoZoneSection(state(), zone(status = ThermoZoneStatus.ZONE_NOT_FOUND), locale = pl).canRestore)
    }

    @Test
    fun `brak czujnika Deimic liczy roznice ze swiezego termometru`() {
        val noAverage = zone(difference = null, deimic = 20.0f)
        val rows = thermoZoneSection(state(zones = listOf(noAverage)), noAverage, locale = pl).rows
        assertEquals(ResText.plain("+1,3${nb}°C"), rows.first { it.label == R.string.thermo_row_difference }.value)
        val none = zone(difference = null, deimic = null)
        assertEquals(
            ResText.plain("–"),
            thermoZoneSection(state(zones = listOf(none)), none, locale = pl).rows.first { it.label == R.string.thermo_row_difference }.value,
        )
    }

    @Test
    fun `powod bezczynnosci tylko dla aktywnej strefy`() {
        val idle = zone(idle = CalibrationIdleReason.WITHIN_THRESHOLD)
        assertEquals(ResText(R.string.thermo_idle_within_threshold), thermoZoneSection(state(zones = listOf(idle)), idle, locale = pl).idle)
    }

    // wykryte termometry i strefy

    private fun found(mac: String, rssi: Int, assigned: ZoneKey? = null, name: String? = null) =
        DiscoveredThermometer(mac, name, rssi, 21.0f, 45f, 90, 5_000L, 5_000L, assigned)

    @Test
    fun `wykryte termometry - przypisane alfabetycznie po strefie, potem nieprzypisane, niezaleznie od sygnalu`() {
        val zoneZ = ZoneKey(1, 7)
        val zoneL = ZoneKey(1, 8)
        val names = mapOf(key to "Pokój 3", zoneZ to "Żółta", zoneL to "Łazienka")
        val rows = discoveredRows(
            state(
                discovered = listOf(
                    found("02:00:00:00:00:01", -40, name = "ATC_1"),
                    found("02:00:00:00:00:02", -90, assigned = zoneZ),
                    found("02:00:00:00:00:03", -30, assigned = key),
                    found("02:00:00:00:00:04", -60, assigned = zoneL),
                ),
            ),
            zoneNames = { names[it] },
            locale = pl,
        )
        assertEquals(listOf("02:00:00:00:00:04", "02:00:00:00:00:03", "02:00:00:00:00:02", "02:00:00:00:00:01"), rows.map { it.mac })
        assertEquals(listOf("Łazienka", "Pokój 3", "Żółta", null), rows.map { it.assignedZoneName })
        assertEquals("ATC_1", rows[3].title)
        val signal = rows[3].values.first { it.label == R.string.thermo_row_signal }
        assertEquals(ResText.plain("${minus}40${nb}dBm"), signal.value)
    }

    private val config = HomeConfig(
        levels = listOf(Level(1, "Parter")),
        rooms = listOf(Room(1, 1, "Salon"), Room(2, 1, "Biuro")),
        modules = listOf(1),
        outputs = emptyList(),
        heatingZones = listOf(
            HeatingZone(100, 1, 5, "Strefa A", roomId = 1, levelId = 1),
            HeatingZone(101, 1, 6, "Strefa B", roomId = 2, levelId = 1),
        ),
        analogInputs = listOf(
            AnalogInput(1, 1, 4, 1, 1, AnalogInput.TEMPERATURE_INPUT_TYPE, "T salon"),
            AnalogInput(2, 1, 7, 2, 1, AnalogInput.TEMPERATURE_INPUT_TYPE, "T biuro 1"),
            AnalogInput(3, 1, 9, 2, 1, AnalogInput.TEMPERATURE_INPUT_TYPE, "T biuro 2"),
            AnalogInput(4, 1, 11, 2, 1, 3, "Nie temperatura"),
        ),
    )

    @Test
    fun `wybor strefy zawiera pomieszczenie i znacznik istniejacego termometru`() {
        val choices = zoneChoices(config, state())
        assertEquals(listOf("Strefa A", "Strefa B"), choices.map { it.name })
        assertEquals(listOf("Salon", "Biuro"), choices.map { it.room })
        assertEquals(listOf(true, false), choices.map { it.hasThermometer }) // zone() ma klucz 1/5 = Strefa A
        assertTrue(zoneChoices(null, state()).isEmpty())
    }

    @Test
    fun `nazwa strefy z konfiguracji, a bez niej z przypisania`() {
        assertEquals("Strefa A", zoneName(config, state(), key))
        assertEquals("Pokój 3", zoneName(null, state(), key))
        assertNull(zoneName(config, state(), ZoneKey(9, 9)))
    }

    @Test
    fun `wejscia do wyboru - kandydaci albo wszystkie czujniki temperatury modulu`() {
        assertEquals(listOf(4, 9), inputChoices(config, zone(candidates = listOf(4, 9))))
        assertEquals(listOf(4, 7, 9), inputChoices(config, zone(candidates = emptyList(), status = ThermoZoneStatus.NEEDS_INPUT)))
        assertTrue(inputChoices(null, zone(candidates = emptyList())).isEmpty())
    }

    // tabela kalibracji i dziennik

    @Test
    fun `wiersz tabeli kalibracji`() {
        val row = calibrationRows(state(), pl).single()
        assertEquals("Pokój 3", row.zoneName)
        assertEquals("Sypialnia", row.room)
        assertEquals(ResText(R.string.thermo_input_label, listOf(4)), row.input)
        assertEquals("${minus}2,4${nb}°C", row.baseline)
        assertEquals("${minus}2,4${nb}°C", row.current)
        assertEquals("${minus}1,7${nb}°C", row.planned)
        val unknown = calibrationRows(state(zones = listOf(zone(inputNr = null)).map { it.copy(plannedOffsetC = null) }), pl).single()
        assertEquals(ResText(R.string.thermo_input_none), unknown.input)
        assertEquals("–", unknown.planned)
    }

    private val entry = ThermoLogEntry(
        at = 1_760_000_000_000L, module = 1, outputNr = 5, inputNr = 4, threshold = 0.5f, kind = ThermoLogKind.CALIBRATION,
        reason = CalibrationReason.DIFFERENCE, dryRun = true, result = ThermoLogResult.DRY_RUN,
        xiaomiC = 21.3f, deimicC = 20.6f, differenceC = 0.7f,
    )

    @Test
    fun `ostatnia decyzja w dzienniku`() {
        val decision = logDecision(entry, pl, ZoneId.of("UTC"))
        assertEquals(ResText(R.string.thermo_log_kind_calibration), decision.kind)
        assertEquals(ResText(R.string.thermo_log_result_dry_run), decision.result)
        assertEquals(ResText(R.string.thermo_log_reason_difference), decision.reason)
        assertEquals(ResText(R.string.thermo_log_values, listOf("21,3${nb}°C", "20,6${nb}°C", "+0,7${nb}°C")), decision.values)
        assertTrue(decision.whenText.isNotBlank())
        val info = logDecision(entry.copy(kind = ThermoLogKind.BASELINE_CAPTURED, result = ThermoLogResult.NONE, reason = null, xiaomiC = null, deimicC = null, differenceC = null), pl)
        assertNull(info.result)
        assertNull(info.reason)
        assertNull(info.values)
    }

    @Test
    fun `dziennik strefy - liczniki obu progow i poczatek dziennika`() {
        val z = zone(frequency = frequency, last = entry, idle = CalibrationIdleReason.RATE_LIMITED)
        val log = logZones(state(zones = listOf(z)), pl, ZoneId.of("UTC")).single()
        assertNotNull(log.decision)
        assertEquals(ResText(R.string.thermo_idle_rate_limited), log.idle)
        assertEquals(
            listOf(FrequencyRow("0,5${nb}°C", true, 7, 20, 0), FrequencyRow("1,0${nb}°C", false, 2, 5, 1)),
            log.frequency,
        )
        assertNotNull(log.sinceText)
        assertFalse(log.live)
        val empty = logZones(state(zones = listOf(zone())), pl).single()
        assertNull(empty.decision)
        assertNull(empty.sinceText)
        assertTrue(empty.frequency.isEmpty())
    }

    // problemy strefy, wstrzymanie, ostrzeżenie, pełny dziennik

    @Test
    fun `problemy strefy trafiaja do sekcji i tabeli w stalej kolejnosci`() {
        val z = zone().copy(issues = setOf(ThermoZoneIssue.STATE_NOT_SAVED, ThermoZoneIssue.CALIBRATION_NOT_RESTORED, ThermoZoneIssue.READ_REQUIRED))
        val expected = listOf(
            ResText(R.string.thermo_issue_read_required),
            ResText(R.string.thermo_issue_state_not_saved),
            ResText(R.string.thermo_issue_not_restored),
        )
        assertEquals(expected, thermoZoneSection(state(zones = listOf(z)), z, locale = pl).issues)
        assertEquals(expected, calibrationRows(state(zones = listOf(z)), pl).single().issues)
        assertTrue(thermoZoneSection(state(), zone(), locale = pl).issues.isEmpty())
        val input = zone().copy(issues = setOf(ThermoZoneIssue.INPUT_NOT_ALLOWED, ThermoZoneIssue.INPUT_CONFLICT))
        assertEquals(
            listOf(ResText(R.string.thermo_issue_input_conflict), ResText(R.string.thermo_issue_input_not_allowed)),
            zoneIssueTexts(input),
        )
    }

    @Test
    fun `dokladna przyczyna bezczynnosci ma pierwszenstwo, ale nie powtarza problemu`() {
        val base = zone(idle = CalibrationIdleReason.RATE_LIMITED)
        val detailOnly = base.copy(idleDetail = CalibrationIdleDetail.READ_REQUIRED)
        assertEquals(ResText(R.string.thermo_idle_read_required), thermoZoneSection(state(zones = listOf(detailOnly)), detailOnly, locale = pl).idle)
        assertEquals(ResText(R.string.thermo_idle_read_required), calibrationRows(state(zones = listOf(detailOnly)), pl).single().idle)
        val covered = detailOnly.copy(issues = setOf(ThermoZoneIssue.READ_REQUIRED))
        assertNull(thermoZoneSection(state(zones = listOf(covered)), covered, locale = pl).idle)
        val unsaved = base.copy(idleDetail = CalibrationIdleDetail.STATE_NOT_SAVED)
        assertEquals(ResText(R.string.thermo_idle_state_not_saved), zoneIdleText(unsaved))
        assertEquals(ResText(R.string.thermo_idle_rate_limited), zoneIdleText(base))
        assertEquals(ResText(R.string.thermo_idle_rate_limited), logZones(state(zones = listOf(base)), pl).single().idle)
    }

    @Test
    fun `reczne przywrocenie wstrzymuje korekte i pokazuje przycisk wznowienia`() {
        val paused = zone(idle = CalibrationIdleReason.DISABLED).copy(manuallyPaused = true)
        val section = thermoZoneSection(state(zones = listOf(paused)), paused, locale = pl)
        assertEquals(listOf(ResText(R.string.thermo_issue_manual_restore)), section.issues)
        assertTrue(section.canResume)
        assertNull(section.idle) // „funkcja jest wyłączona” byłoby mylące
        val row = calibrationRows(state(zones = listOf(paused)), pl).single()
        assertTrue(row.canResume)
        assertEquals(key, row.zone)
        assertNull(row.idle)
        // sama odmiana problemu bez flagi też wstrzymuje
        val byIssue = zone().copy(issues = setOf(ThermoZoneIssue.MANUAL_RESTORE))
        assertTrue(thermoZoneSection(state(zones = listOf(byIssue)), byIssue, locale = pl).canResume)
        // w trakcie zapisu przycisku nie ma
        assertFalse(thermoZoneSection(state(zones = listOf(paused.copy(writeInFlight = true))), paused.copy(writeInFlight = true), locale = pl).canResume)
        // zwykła strefa nie ma przycisku
        assertFalse(thermoZoneSection(state(), zone(), locale = pl).canResume)
        assertFalse(calibrationRows(state(), pl).single().canResume)
    }

    @Test
    fun `ostrzezenie o kalibracji pozostawionej na sterowniku`() {
        assertNull(leaveWarning(state()))
        val inputs = state().copy(leftoverInputs = listOf(InputKey(1, 5), InputKey(1, 4)))
        val warning = leaveWarning(inputs)!!
        assertEquals(listOf("1/4", "1/5"), warning.inputs)
        assertFalse(warning.previousController)
        assertEquals(listOf(ResText(R.string.thermo_leave_warning_inputs, listOf("1/4, 1/5"))), warning.texts())
        val previous = leaveWarning(state().copy(previousControllerNotRestored = true))!!
        assertTrue(previous.inputs.isEmpty())
        assertEquals(listOf(ResText(R.string.thermo_leave_warning_previous)), previous.texts())
        val both = leaveWarning(inputs.copy(previousControllerNotRestored = true))!!
        assertEquals(2, both.texts().size)
    }

    @Test
    fun `pelny dziennik - najnowsze na gorze, limit, nazwa strefy i zmiana kalibracji`() {
        val before = InputCalibration(1u, 0u, 200f, 0f, 176f, 0f) // −2,4 °C
        val after = InputCalibration(1u, 0u, 200f, 0f, 181f, 0f) // −1,9 °C
        val older = entry.copy(at = 1_000L, previous = before, next = after)
        val newer = entry.copy(
            at = 2_000L, outputNr = 9, dryRun = false, result = ThermoLogResult.FAILED, threshold = null, differenceC = null,
            detail = ThermoLogDetail.NOT_SENT_READ_FAILED, previous = null, next = null,
        )
        val rows = logEntryRows(listOf(older, newer), state(), locale = pl, zoneId = ZoneId.of("UTC"))
        assertEquals(2, rows.size)
        assertEquals(ResText(R.string.thermo_log_output_unknown, listOf(9)), rows[0].zoneName)
        assertEquals(ResText.plain("Pokój 3"), rows[1].zoneName)
        assertEquals(ResText(R.string.thermo_log_detail_read_failed), rows[0].detail)
        assertEquals(ResText(R.string.thermo_log_mode_not_sent), rows[0].mode)
        assertEquals(ResText(R.string.thermo_log_result_failed), rows[0].result)
        assertNull(rows[0].threshold)
        assertNull(rows[0].change)
        assertEquals(ResText(R.string.thermo_log_mode_dry), rows[1].mode)
        assertEquals("0,5${nb}°C", rows[1].threshold)
        assertEquals("+0,7${nb}°C", rows[1].difference)
        assertEquals(ResText(R.string.thermo_log_change, listOf("${minus}2,4${nb}°C", "${minus}1,9${nb}°C")), rows[1].change)
        assertNull(rows[1].detail)
        assertEquals(ResText(R.string.thermo_input_label, listOf(4)), rows[1].input)
        // wysłano: zapis na żywo bez opisu
        val sent = logEntryRows(listOf(entry.copy(dryRun = false, result = ThermoLogResult.CONFIRMED)), state(), locale = pl).single()
        assertEquals(ResText(R.string.thermo_log_mode_sent), sent.mode)
        // zdarzenia bez zapisu nie mają trybu
        assertNull(logEntryRows(listOf(entry.copy(kind = ThermoLogKind.BASELINE_CAPTURED)), state(), locale = pl).single().mode)
        // limit
        val many = (1..LOG_LIMIT + 50).map { entry.copy(at = it.toLong()) }
        val limited = logEntryRows(many, state(), locale = pl)
        assertEquals(LOG_LIMIT, limited.size)
        assertEquals(logEntryRows(listOf(many.last()), state(), locale = pl).single().whenText, limited.first().whenText)
        assertEquals(1, logEntryRows(many, state(), limit = 1, locale = pl).size)
        // detale wpisów przywracania
        for (detail in listOf(ThermoLogDetail.LEAVING_LIVE, ThermoLogDetail.LEAVING_CONTROLLER)) {
            val row = logEntryRows(listOf(entry.copy(kind = ThermoLogKind.RESTORE, detail = detail)), state(), locale = pl).single()
            assertEquals(ResText(detail.textRes()), row.detail)
        }
    }

    @Test
    fun `teksty zasobow nie sa zerem`() {
        assertNotEquals(0, ScannerAction.REQUEST_PERMISSION.labelRes)
        assertTrue(ScannerAction.entries.filter { it != ScannerAction.NONE }.all { it.labelRes != 0 })
    }
}
