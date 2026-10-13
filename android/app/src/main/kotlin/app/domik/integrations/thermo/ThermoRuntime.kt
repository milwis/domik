package app.domik.integrations.thermo

import app.domik.data.Features
import app.domik.data.HomeState
import app.domik.data.InputKey
import app.domik.deimic.client.ConnectionState
import app.domik.deimic.client.commands.CalibrationReadout
import app.domik.deimic.client.commands.CommandOutcome
import app.domik.deimic.client.commands.ZoneKey
import app.domik.deimic.config.HeatingZone
import app.domik.deimic.config.HomeConfig
import app.domik.deimic.protocol.InputCalibration
import app.domik.thermo.BaselineEvent
import app.domik.thermo.CalibrationCorrector
import app.domik.thermo.CalibrationDecision
import app.domik.thermo.CalibrationIdleDetail
import app.domik.thermo.CalibrationIdleReason
import app.domik.thermo.CalibrationMath
import app.domik.thermo.CalibrationParams
import app.domik.thermo.CalibrationReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Rytm pracy [ThermoRuntime] i reguły korektora. [params] daje okna i odstępy; próg i tryb próbny pochodzą z ustawień.
 *
 * @property staleReadingAfter po takim czasie bez nowego pomiaru UI pokazuje „termometr nieaktualny”; po `params.staleTimeout`
 *   korektor wstrzymuje korektę (bez zapisów), a ostatnia kalibracja zostaje na sterowniku – bez przywracania bazowej)
 */
data class ThermoTiming(
    val evaluateInterval: Duration = 60.seconds,
    val deimicSampleInterval: Duration = 30.seconds,
    val calibrationRefreshInterval: Duration = 6.hours,
    val staleReadingAfter: Duration = 10.minutes,
    val params: CalibrationParams = CalibrationParams(),
) {
    companion object {
        val DEFAULT = ThermoTiming()

        /** Tryb demo: wszystko w sekundach–minutach, żeby zbieżność była widać od razu. */
        val DEMO = ThermoTiming(
            evaluateInterval = 5.seconds,
            deimicSampleInterval = 5.seconds,
            calibrationRefreshInterval = 30.minutes,
            staleReadingAfter = 1.minutes,
            params = CalibrationParams(
                averagingWindow = 30.seconds,
                minWriteInterval = 1.minutes,
                settleTime = 10.seconds,
                staleTimeout = 2.minutes,
                writeTimeout = 30.seconds,
            ),
        )
    }
}

/**
 * Łańcuch termometrów jednej sesji: termometry Xiaomi ([source]) → porównanie z temperaturą wejścia analogowego
 * sterownika ([home], `HomeState.analogInputs`) → przy różnicy ponad próg nowa kalibracja wejścia (0x132 przez [port]).
 * Logikę decyzji ma [CalibrationCorrector] (jeden na wejście); tu jest tylko sterowanie, pliki i stan dla UI.
 *
 * **Bezpieczeństwo zapisów.** Korektor strefy działa w trybie rzeczywistym tylko wtedy, gdy jednocześnie: to
 * urządzenie jest hubem (w demo zawsze), w ustawieniach nie ma trybu próbnego, zapis nie jest zablokowany flagą
 * [Features.SENSOR_CALIBRATION_WRITES] (demo – zapis do symulatora jest dozwolony) i strefa nie jest wstrzymana po
 * zmianie kalibracji poza Domikiem. W każdym innym przypadku korektor powstaje z `dryRun = true`, więc jego decyzje
 * to `WouldWrite` – tylko dziennik, nic nie jest wysyłane. Wyłączenie funkcji ([ThermoProfileSettings.enabled])
 * w trybie rzeczywistym oznacza jeszcze jeden zapis: przywrócenie kalibracji bazowej (jeśli sterownik ma inną).
 * To samo przy każdym wyjściu strefy z trybu rzeczywistego (tryb próbny, nie-hub, zmiana lub usunięcie przypisania):
 * przywrócenie wykonuje jeszcze korektor rzeczywisty, zanim powstanie próbny. Ręczne przywrócenie wstrzymuje korektę
 * strefy (do [resumeCalibration]), żeby reguła go nie cofnęła.
 *
 * **Każdy zapis 0x132** (kalibracja, przywrócenie automatyczne i ręczne) poprzedza świeży odczyt 0x131 modułu: gdy
 * odczyt się nie uda albo sterownik ma inny rekord niż ten, z którego zapis wyliczono, nic nie jest wysyłane (zmiana
 * poza Domikiem wstrzymuje strefę).
 *
 * Kalibracje są czytane (0x131) po każdym połączeniu z pełną synchronizacją, co [ThermoTiming.calibrationRefreshInterval],
 * po każdym zapisie (weryfikacja, do dziennika) i na życzenie. Próbki Deimic są brane z [home] tylko przy gotowym
 * i zsynchronizowanym połączeniu. Bez `android.*`: czas z [clock], korutyny w [scope] (na wyłączność – [close] go anuluje).
 *
 * @param writesFeatureEnabled wartość [Features.SENSOR_CALIBRATION_WRITES] (parametr tylko dla testów)
 */
class ThermoRuntime(
    private val scope: CoroutineScope,
    private val home: StateFlow<HomeState>,
    private val connection: StateFlow<ConnectionState>,
    private val port: CalibrationPort,
    private val source: ThermometerSource?,
    private val files: ThermoFiles,
    settings: ThermoProfileSettings,
    hub: Boolean,
    val demo: Boolean,
    private val clock: () -> Long,
    private val timing: ThermoTiming = if (demo) ThermoTiming.DEMO else ThermoTiming.DEFAULT,
    private val writesFeatureEnabled: Boolean = Features.SENSOR_CALIBRATION_WRITES,
) {
    /** Korektor strefy i to, co o nim trzeba pamiętać między przebudowami. */
    private class Track(val corrector: CalibrationCorrector, val module: Int, val live: Boolean) {
        var lastLoggedAt = Long.MIN_VALUE

        /** Najbliższe przywrócenie (powód DISABLED) to ręczne przywrócenie użytkownika – do dziennika. */
        var manualRestore = false
    }

    private class Binding(
        val assignment: ThermoAssignment,
        val zone: HeatingZone?,
        val roomName: String?,
        val status: ThermoZoneStatus,
        val inputNr: Int?,
        val candidates: List<Int>,
        val staticNotices: Set<ThermoNotice>,
        val track: Track?,
        val staticIssues: Set<ThermoZoneIssue> = emptySet(),
    ) {
        val key: ZoneKey get() = assignment.zone
        var idleReason: CalibrationIdleReason? = null
        var idleDetail: CalibrationIdleDetail? = null
        var readFailed = false
        var writeFailed = false
    }

    /** Co przebudowa zrobi z przypisaniem (wspólne dla [rebuildOnce] i wykrywania wyjścia z trybu rzeczywistego). */
    private class Plan(
        val assignment: ThermoAssignment,
        val zone: HeatingZone?,
        val status: ThermoZoneStatus,
        val inputNr: Int?,
        val candidates: List<Int>,
        val notices: Set<ThermoNotice>,
        val issues: Set<ThermoZoneIssue>,
        val live: Boolean,
    ) {
        val hasTrack: Boolean get() = status == ThermoZoneStatus.ACTIVE || status == ThermoZoneStatus.PAUSED
    }

    private class Sample(val at: Long, val value: Float)

    private val lock = Any()
    private val portMutex = Mutex()
    private val directory = ThermometerDirectory()
    private val _state = MutableStateFlow(ThermoUiState.INACTIVE)

    /** Stan dla UI; odświeżany po każdym nowym pomiarze, sprawdzeniu, odczycie kalibracji i zmianie ustawień. */
    val state: StateFlow<ThermoUiState> = _state.asStateFlow()

    private var settings = settings
    private var hub = hub
    private var scanMode = ScanMode.OFF
    private var scannerStatus = source?.status?.value ?: ScannerStatus.UNSUPPORTED
    private var bindings: List<Binding> = emptyList()
    private var lastConfig: HomeConfig? = null
    private var wasReady = false
    private var lastReadAt: Long? = null
    private val lastRecords = HashMap<Int, List<InputCalibration>>()
    private val xiaomiSamples = HashMap<String, ArrayDeque<Sample>>()
    private val deimicSamples = HashMap<InputKey, ArrayDeque<Sample>>()
    private var rebuildRequested = false
    private var stats: Map<ZoneKey, Pair<List<ThermoFrequency>, ThermoLogEntry?>> = emptyMap()
    private var statsAt = Long.MIN_VALUE
    private var statsDirty = true

    private val writesAllowed: Boolean get() = writesFeatureEnabled || demo

    /** Ustawienia pozwalają na zapisy (bez uwzględnienia wstrzymanych stref). */
    private val liveMode: Boolean get() = (hub || demo) && !settings.dryRun && writesAllowed

    /** Uruchamia nasłuch i zegary; w demo także generator termometrów. */
    fun start() {
        scope.launch { source?.readings?.collect(::onReading) }
        source?.let { src -> scope.launch { src.status.collect { status -> locked { scannerStatus = status }; publish() } } }
        scope.launch { combine(home, connection) { h, c -> h to c }.collect { (h, c) -> onHome(h, c) } }
        scope.launch {
            while (true) {
                delay(timing.deimicSampleInterval)
                sampleDeimic()
            }
        }
        scope.launch {
            while (true) {
                delay(timing.evaluateInterval)
                evaluateAll()
            }
        }
        scope.launch {
            while (true) {
                delay(timing.calibrationRefreshInterval)
                if (isReady()) refreshCalibrationsNow()
            }
        }
        if (demo) setScanMode(ScanMode.BACKGROUND)
        scope.launch { portMutex.withLock { rebuild() }; publish() }
    }

    /** Kończy pracę (koniec sesji): zatrzymuje zegary i nasłuch. Kalibracje na sterowniku zostają, jakie są. */
    fun close() {
        if (demo) source?.setMode(ScanMode.OFF)
        scope.cancel()
    }

    // Akcje (wracają od razu; praca w [scope])

    /** Nowe ustawienia profilu i huba (po zmianie przez użytkownika). */
    fun updateSettings(settings: ThermoProfileSettings, hub: Boolean) {
        scope.launch {
            portMutex.withLock {
                locked {
                    this@ThermoRuntime.settings = settings
                    this@ThermoRuntime.hub = hub
                    statsDirty = true
                }
                rebuild()
                if (isReady() && needsCalibrationRead()) refreshLocked(modulesInUse())
            }
            publish()
        }
    }

    /** Tryb skanu odbiornika (wyszukiwanie nowych termometrów / praca ciągła / wyłączony). */
    fun setScanMode(mode: ScanMode) {
        locked { scanMode = mode }
        source?.setMode(mode)
        publish()
    }

    /** Odczytuje kalibracje (0x131) modułów z przypisanymi strefami (tylko odczyt). */
    fun refreshCalibrations() {
        scope.launch { refreshCalibrationsNow() }
    }

    /**
     * Przywraca kalibrację bazową wejścia strefy. Podlega tym samym warunkom co automatyczny zapis: poza trybem
     * rzeczywistym (albo przy wstrzymanej strefie) tylko wpis w dzienniku. W trybie rzeczywistym korekta strefy zostaje
     * wstrzymana ([ThermoZoneIssue.MANUAL_RESTORE]) do [resumeCalibration] – inaczej reguła cofnęłaby przywrócenie.
     */
    fun restoreBaseline(zone: ZoneKey) {
        scope.launch {
            portMutex.withLock { restoreLocked(zone) }
            publish()
        }
    }

    /** Wznawia korektę strefy wstrzymaną po ręcznym przywróceniu kalibracji instalatora. */
    fun resumeCalibration(zone: ZoneKey) {
        scope.launch {
            portMutex.withLock {
                val binding = locked { bindings.firstOrNull { it.key == zone } } ?: return@withLock
                val input = binding.inputNr ?: return@withLock
                files.setManuallyRestored(binding.assignment.module, input, false)
                rebuild()
            }
            publish()
        }
    }

    /**
     * Przed zmianą albo zapomnieniem sterownika: przywraca kalibrację instalatora na wszystkich wejściach, które
     * prowadzi korektor w trybie rzeczywistym (z odczytem 0x131 przed każdym zapisem), i wyłącza korektę. Zwraca
     * true, gdy na żadnym wejściu sterownika nie może zostać kalibracja Domika (wg plików i ostatnich odczytów);
     * false – brak połączenia, błąd albo wejście bez korektora rzeczywistego z przesunięciem Domika (pliki zostają).
     * W demo nic nie robi (true).
     */
    suspend fun restoreBeforeLeaving(): Boolean {
        if (demo) return true
        val clean = portMutex.withLock {
            for (binding in locked { bindings.filter { it.track?.live == true } }) {
                restoreLive(binding, binding.track!!, ThermoLogKind.RESTORE, ThermoLogDetail.LEAVING_CONTROLLER)
            }
            if (locked { rebuildRequested }) rebuild()
            locked { leftovers(includeLive = true).isEmpty() }
        }
        publish()
        return clean
    }

    /** Przyjmuje kalibrację zmienioną poza Domikiem jako bazową (już nią jest) i wznawia zapisy strefy. */
    fun acknowledgeExternalChange(zone: ZoneKey) {
        scope.launch {
            portMutex.withLock {
                val binding = locked { bindings.firstOrNull { it.key == zone } } ?: return@withLock
                val input = binding.inputNr ?: return@withLock
                if ((binding.assignment.module to input) !in files.pausedInputs()) return@withLock
                files.setPaused(binding.assignment.module, input, false)
                log(
                    binding, ThermoLogKind.EXTERNAL_CHANGE_ACCEPTED, ThermoLogResult.NONE, dryRun = !liveMode,
                    next = binding.track?.corrector?.baseline?.baseline,
                )
                rebuild()
            }
            publish()
        }
    }

    // Wejścia

    private fun onReading(reading: ThermometerReading) {
        val now = clock()
        val fresh = directory.onReading(reading, now)
        if (!fresh) return
        val mac = reading.mac.uppercase()
        locked {
            val binding = bindings.firstOrNull { it.assignment.mac == mac }
            if (binding != null) {
                addSample(xiaomiSamples.getOrPut(mac) { ArrayDeque() }, Sample(now, reading.tempC), now)
                binding.track?.corrector?.onXiaomiSample(reading.tempC, reading.batteryPercent, now)
            }
        }
        publish()
    }

    private fun onHome(state: HomeState, connectionState: ConnectionState) {
        val ready = connectionState == ConnectionState.Ready && state.synced
        val (configChanged, becameReady) = locked {
            val changed = state.config !== lastConfig
            lastConfig = state.config
            val became = ready && !wasReady
            wasReady = ready
            changed to became
        }
        if (configChanged || becameReady) {
            scope.launch {
                portMutex.withLock {
                    if (configChanged) rebuild()
                    if (becameReady || (isReady() && needsCalibrationRead())) refreshLocked(modulesInUse())
                }
                publish()
            }
        }
    }

    private fun sampleDeimic() {
        if (!isReady()) return
        val now = clock()
        val values = home.value.analogInputs
        locked {
            for (binding in bindings) {
                val input = binding.inputNr ?: continue
                val key = InputKey(binding.assignment.module, input)
                val value = values[key] ?: continue
                addSample(deimicSamples.getOrPut(key) { ArrayDeque() }, Sample(now, value), now)
                binding.track?.corrector?.onDeimicSample(value, now)
            }
        }
    }

    private fun isReady(): Boolean = connection.value == ConnectionState.Ready && home.value.synced

    // Sprawdzanie i zapis

    private suspend fun evaluateAll() {
        portMutex.withLock {
            val ready = isReady()
            if (ready) {
                // Po nieudanym zapisie korektor nic nie zapisze bez udanego odczytu: czytamy od razu.
                val needRead = locked {
                    bindings.filter { it.track?.live == true && it.track.corrector.state().readRequired }.map { it.assignment.module }
                }
                if (needRead.isNotEmpty()) refreshLocked(needRead)
            }
            for (binding in locked { bindings.toList() }) {
                val track = binding.track ?: continue
                if (track.live && !ready) continue // bez połączenia nie ma czego wysyłać; korektor poczeka
                val before = clock()
                val decision = track.corrector.evaluate()
                locked {
                    binding.idleReason = (decision as? CalibrationDecision.Idle)?.reason
                    binding.idleDetail = (decision as? CalibrationDecision.Idle)?.detail
                    if (!track.live) logSimulated(binding, track, before)
                }
                if (decision is CalibrationDecision.Write) {
                    if (track.live) {
                        val manual = track.manualRestore && decision.reason == CalibrationReason.DISABLED
                        val kind = when {
                            manual -> ThermoLogKind.MANUAL_RESTORE
                            decision.reason == CalibrationReason.DIFFERENCE -> ThermoLogKind.CALIBRATION
                            else -> ThermoLogKind.RESTORE
                        }
                        if (guardedWrite(binding, track, decision, kind, detail = null, before = before) && manual) track.manualRestore = false
                    } else {
                        track.corrector.abortWrite(decision.writeId)
                    }
                }
            }
            if (locked { rebuildRequested }) rebuild()
        }
        publish()
    }

    /**
     * Wysyła decyzję korektora z zabezpieczeniem: najpierw świeży odczyt 0x131 modułu (wynik trafia do korektorów);
     * gdy się nie uda albo sterownik ma inny rekord niż oczekiwany (korektor porzucił zapis) – nic nie jest wysyłane.
     * Potem zapis, zgłoszenie wyniku, dziennik i odczyt kontrolny. Zwraca true dla potwierdzonego zapisu.
     * Wywoływać pod [portMutex]; nie przebudowuje stref (to robi wywołujący, gdy [rebuildRequested]).
     */
    private suspend fun guardedWrite(
        binding: Binding,
        track: Track,
        decision: CalibrationDecision.Write,
        kind: ThermoLogKind,
        detail: ThermoLogDetail?,
        before: Long,
    ): Boolean {
        val module = binding.assignment.module
        val records = readModule(module)
        val current = records?.getOrNull(decision.inputNr)
        if (current == null) {
            track.corrector.abortWrite(decision.writeId)
            locked { binding.writeFailed = true }
            log(
                binding, kind, ThermoLogResult.FAILED, dryRun = false, threshold = track.corrector.params.threshold,
                reason = decision.reason, differenceC = decision.differenceC, previous = decision.previous, next = decision.record,
                clamped = decision.clamped, detail = ThermoLogDetail.NOT_SENT_READ_FAILED,
            )
            return false
        }
        if (!track.corrector.isPending(decision.writeId)) {
            log(
                binding, kind, ThermoLogResult.FAILED, dryRun = false, threshold = track.corrector.params.threshold,
                reason = decision.reason, differenceC = decision.differenceC, previous = current, next = decision.record,
                clamped = decision.clamped, detail = ThermoLogDetail.NOT_SENT_RECORD_CHANGED,
            )
            return false
        }
        val outcome = port.write(module, decision.inputNr, decision.record)
        val ok = outcome == CommandOutcome.Confirmed
        if (ok) track.corrector.onWritten(decision.writeId) else track.corrector.onWriteFailed(decision.writeId)
        val seen = track.corrector.log().lastOrNull { it.at >= before && !it.dryRun }
        locked { binding.writeFailed = !ok }
        log(
            binding,
            kind,
            if (ok) ThermoLogResult.CONFIRMED else ThermoLogResult.FAILED,
            dryRun = false,
            threshold = track.corrector.params.threshold,
            reason = decision.reason,
            xiaomiC = seen?.xiaomiC,
            deimicC = seen?.deimicC,
            differenceC = decision.differenceC,
            previous = decision.previous,
            next = decision.record,
            clamped = decision.clamped,
            detail = detail,
        )
        // Także po niepowodzeniu: ramka mogła dotrzeć, a korektor musi znać prawdziwy stan przed ponowieniem.
        val read = readModule(module)?.getOrNull(decision.inputNr)
        val result = when {
            read == null -> ThermoLogResult.FAILED
            CalibrationMath.same(read, decision.record) -> ThermoLogResult.VERIFIED
            else -> ThermoLogResult.MISMATCH
        }
        log(binding, ThermoLogKind.READBACK, result, dryRun = false, previous = decision.record, next = read)
        return ok
    }

    private enum class RestoreOutcome { RESTORED, NOTHING_TO_RESTORE, FAILED }

    /**
     * Wyłącza korektor rzeczywisty strefy i przywraca kalibrację bazową (jeśli sterownik ma inną), przez [guardedWrite];
     * bez odstępu minimalnego. Najwyżej dwie próby (druga, gdy odczyt przed zapisem pokazał inny rekord).
     * [RestoreOutcome.FAILED] = na wejściu może zostać kalibracja Domika. Wywoływać pod [portMutex].
     */
    private suspend fun restoreLive(binding: Binding, track: Track, kind: ThermoLogKind, detail: ThermoLogDetail?): RestoreOutcome {
        track.corrector.setEnabled(false)
        if (!isReady()) return if (mayHoldShift(binding, track)) RestoreOutcome.FAILED else RestoreOutcome.NOTHING_TO_RESTORE
        repeat(RESTORE_ATTEMPTS) {
            val before = clock()
            when (val decision = track.corrector.restoreNow()) {
                is CalibrationDecision.Write -> if (guardedWrite(binding, track, decision, kind, detail, before)) return RestoreOutcome.RESTORED
                else -> return if (mayHoldShift(binding, track)) RestoreOutcome.FAILED else RestoreOutcome.NOTHING_TO_RESTORE
            }
        }
        return if (mayHoldShift(binding, track)) RestoreOutcome.FAILED else RestoreOutcome.RESTORED
    }

    private fun mayHoldShift(binding: Binding, track: Track): Boolean {
        val state = track.corrector.baseline ?: return false
        val known = binding.inputNr?.let { lastRecords[binding.assignment.module]?.getOrNull(it) }
        return state.mayHoldDomikShift && !(known != null && CalibrationMath.same(known, state.baseline) && !track.corrector.state().readRequired)
    }

    private suspend fun restoreLocked(zone: ZoneKey) {
        val binding = locked { bindings.firstOrNull { it.key == zone } } ?: return
        val track = binding.track ?: return
        val state = track.corrector.state()
        val baseline = state.baseline ?: return
        val input = binding.inputNr ?: return
        if (!track.live) {
            log(binding, ThermoLogKind.MANUAL_RESTORE, ThermoLogResult.DRY_RUN, dryRun = true, previous = state.controllerRecord, next = baseline)
            return
        }
        // Korekta strefy wstrzymana do wznowienia – inaczej reguła cofnęłaby przywrócenie przy najbliższym sprawdzeniu.
        files.setManuallyRestored(binding.assignment.module, input, true)
        track.manualRestore = true
        if (!isReady()) {
            track.corrector.setEnabled(false) // przywrócenie wykona najbliższe sprawdzenie po połączeniu
            locked { binding.writeFailed = true }
            log(binding, ThermoLogKind.MANUAL_RESTORE, ThermoLogResult.FAILED, dryRun = false, previous = state.controllerRecord, next = baseline)
            return
        }
        when (restoreLive(binding, track, ThermoLogKind.MANUAL_RESTORE, detail = null)) {
            RestoreOutcome.RESTORED -> track.manualRestore = false
            RestoreOutcome.NOTHING_TO_RESTORE -> {
                track.manualRestore = false
                log(binding, ThermoLogKind.MANUAL_RESTORE, ThermoLogResult.NONE, dryRun = false, previous = state.controllerRecord, next = baseline)
            }
            RestoreOutcome.FAILED -> Unit // korektor zostaje wyłączony: przywrócenie ponowi najbliższe sprawdzenie
        }
        if (locked { rebuildRequested }) rebuild()
    }

    private suspend fun refreshCalibrationsNow() {
        portMutex.withLock { refreshLocked(modulesInUse()) }
        publish()
    }

    /** Czyta 0x131 modułów [modules] i przekazuje rekordy korektorom. Wywoływać pod [portMutex]. */
    private suspend fun refreshLocked(modules: Collection<Int>) {
        for (module in modules.distinct()) readModule(module)
        if (locked { rebuildRequested }) rebuild()
    }

    /** Odczyt 0x131 jednego modułu → [lastRecords] i korektory stref tego modułu; null przy błędzie. Pod [portMutex]. */
    private suspend fun readModule(module: Int): List<InputCalibration>? {
        val readout = port.read(module)
        val records = (readout as? CalibrationReadout.Success)?.records
        locked {
            if (records != null) {
                lastRecords[module] = records
                lastReadAt = clock()
            }
            for (binding in bindings.filter { it.assignment.module == module }) {
                binding.readFailed = records == null
                val input = binding.inputNr ?: continue
                val record = records?.getOrNull(input) ?: continue
                binding.track?.let { onRecord(binding, it, record) }
            }
        }
        return records
    }

    /** Rekord wejścia przeczytany ze sterownika → korektor (uzgodnienie kalibracji bazowej) i zdarzenia do dziennika. */
    private fun onRecord(binding: Binding, track: Track, record: InputCalibration) {
        val previousBaseline = track.corrector.baseline?.baseline
        when (track.corrector.onCalibrationRead(record)) {
            BaselineEvent.CAPTURED -> log(binding, ThermoLogKind.BASELINE_CAPTURED, ThermoLogResult.NONE, dryRun = !track.live, next = record)
            BaselineEvent.EXTERNAL_CHANGE -> {
                val input = binding.inputNr ?: return
                files.setPaused(binding.assignment.module, input, true)
                log(binding, ThermoLogKind.EXTERNAL_CHANGE, ThermoLogResult.NONE, dryRun = !track.live, previous = previousBaseline, next = record)
                if (track.live) rebuildRequested = true
            }
            BaselineEvent.MATCHES_BASELINE, BaselineEvent.MATCHES_DOMIK_WRITE -> Unit
        }
    }

    // Przypisania → korektory

    /**
     * Uzgadnia strefy z ustawieniami i konfiguracją domu: wejście z pomieszczenia (albo ręczne, jeśli dozwolone –
     * [resolveInput]), korektor tworzony od nowa tylko przy zmianie wejścia lub trybu (próbny / rzeczywisty) albo progu.
     * Strefa, która wychodzi z trybu rzeczywistego, najpierw przywraca kalibrację bazową (jeszcze jako rzeczywista).
     * Nowy korektor dostaje próbki z ostatniego okna i ostatnio przeczytany rekord. Wywoływać pod [portMutex].
     */
    private suspend fun rebuild() {
        repeat(MAX_REBUILD_PASSES) {
            retireLeavingLive()
            locked {
                rebuildRequested = false
                rebuildOnce()
            }
            if (!locked { rebuildRequested }) return
        }
    }

    /**
     * Strefy z korektorem rzeczywistym, które po przebudowie go nie będą miały (tryb próbny, nie-hub, wstrzymanie,
     * zmiana lub usunięcie przypisania / wejścia): jedno przywrócenie bazowej na zasadach wyłączenia funkcji.
     */
    private suspend fun retireLeavingLive() {
        val leaving = locked {
            val planned = planAll().associateBy { it.assignment.zone }
            bindings.filter { binding ->
                val track = binding.track ?: return@filter false
                val plan = planned[binding.key]
                track.live && (plan == null || !plan.live || plan.inputNr != binding.inputNr || plan.assignment.module != binding.assignment.module)
            }
        }
        for (binding in leaving) {
            val track = binding.track ?: continue
            restoreLive(binding, track, ThermoLogKind.RESTORE, ThermoLogDetail.LEAVING_LIVE)
        }
    }

    /** Plan wszystkich przypisań (kolejność ustawień: pierwsze przypisanie wejścia wygrywa). Pod [lock]. */
    private fun planAll(): List<Plan> {
        val config = lastConfig
        val paused = files.pausedInputs()
        val usedInputs = HashSet<Pair<Int, Int>>()
        return settings.assignments.map { assignment ->
            val zone = config?.heatingZones?.firstOrNull { it.module == assignment.module && it.outputNr == assignment.outputNr }
            val moduleInputs = config?.analogInputs.orEmpty().filter { it.isTemperature && it.module == assignment.module }
                .map { it.inputNr }.distinct().sorted()
            val fromRoom = zone?.let { config?.temperatureInputsFor(it) }.orEmpty().map { it.inputNr }.distinct().sorted()
            val resolution = resolveInput(assignment.inputNr, fromRoom, moduleInputs)
            val input = resolution.inputNr
            val notices = HashSet<ThermoNotice>()
            val issues = HashSet<ThermoZoneIssue>()
            resolution.issue?.let(issues::add)
            val status = when {
                config == null -> ThermoZoneStatus.WAITING_FOR_CONFIG
                zone == null -> ThermoZoneStatus.ZONE_NOT_FOUND
                input == null -> {
                    when {
                        resolution.issue == ThermoZoneIssue.INPUT_CONFLICT -> Unit
                        fromRoom.isEmpty() -> notices += ThermoNotice.NO_INPUT
                        else -> notices += ThermoNotice.AMBIGUOUS_INPUT
                    }
                    ThermoZoneStatus.NEEDS_INPUT
                }
                !usedInputs.add(assignment.module to input) -> {
                    notices += ThermoNotice.INPUT_IN_USE
                    ThermoZoneStatus.INPUT_IN_USE
                }
                (assignment.module to input) in paused -> ThermoZoneStatus.PAUSED
                else -> ThermoZoneStatus.ACTIVE
            }
            val candidates = if (zone == null) moduleInputs else allowedInputs(fromRoom, moduleInputs)
            Plan(assignment, zone, status, input, candidates, notices, issues, live = liveMode && status == ThermoZoneStatus.ACTIVE)
        }
    }

    private fun rebuildOnce() {
        val config = lastConfig
        val old = bindings.associateBy { it.key }
        val manual = files.manuallyRestoredInputs()
        val now = clock()
        val plans = planAll()
        // Ręczne wstrzymanie znika razem z przypisaniem strefy albo zmianą jej wejścia.
        val kept = plans.mapNotNull { p -> p.inputNr?.let { p.assignment.module to it } }.toSet()
        if (config != null) {
            for (binding in old.values) {
                val key = binding.inputNr?.let { binding.assignment.module to it } ?: continue
                if (key in manual && key !in kept) files.setManuallyRestored(key.first, key.second, false)
            }
        }
        val manualNow = files.manuallyRestoredInputs()
        bindings = plans.map { plan ->
            val assignment = plan.assignment
            val previous = old[assignment.zone]
            val input = plan.inputNr
            val track = if (plan.hasTrack) {
                val reusable = previous?.track?.takeIf {
                    previous.inputNr == input && previous.assignment.module == assignment.module && it.live == plan.live &&
                        it.corrector.params.threshold == settings.threshold
                }
                reusable ?: newTrack(previous, assignment, input!!, plan.live, now)
            } else {
                null
            }
            val pausedByUser = input != null && (assignment.module to input) in manualNow
            track?.corrector?.setEnabled(settings.enabled && !pausedByUser)
            Binding(
                assignment, plan.zone, plan.zone?.roomId?.let { config?.room(it)?.name }, plan.status, input, plan.candidates,
                plan.notices, track, plan.issues,
            ).also {
                if (previous != null && previous.track === track) {
                    it.idleReason = previous.idleReason
                    it.idleDetail = previous.idleDetail
                    it.readFailed = previous.readFailed
                    it.writeFailed = previous.writeFailed
                }
            }
        }
        statsDirty = true
        // Bufory próbek tylko dla tego, co jest przypisane.
        val macs = settings.assignments.map { it.mac }.toSet()
        xiaomiSamples.keys.retainAll(macs)
        val inputs = bindings.mapNotNull { b -> b.inputNr?.let { InputKey(b.assignment.module, it) } }.toSet()
        deimicSamples.keys.retainAll(inputs)
    }

    private fun newTrack(previous: Binding?, assignment: ThermoAssignment, input: Int, live: Boolean, now: Long): Track {
        val params = timing.params.copy(
            threshold = settings.threshold,
            dryRun = !live,
            comparisonThresholds = ThermoProfileSettings.THRESHOLDS,
        )
        val corrector = CalibrationCorrector(input, params, files.baselineStore(assignment.module), clock, settings.enabled)
        val track = Track(corrector, assignment.module, live)
        val window = params.averagingWindow.inWholeMilliseconds
        val battery = directory.entry(assignment.mac)?.reading?.batteryPercent
        xiaomiSamples[assignment.mac]?.filter { it.at >= now - window }?.forEach { corrector.onXiaomiSample(it.value, battery, it.at) }
        deimicSamples[InputKey(assignment.module, input)]?.filter { it.at >= now - window }?.forEach { corrector.onDeimicSample(it.value, it.at) }
        lastRecords[assignment.module]?.getOrNull(input)?.let { record ->
            // Binding jeszcze nie istnieje – zdarzenia uzgodnienia zapisujemy na tymczasowym opisie strefy.
            val shell = Binding(assignment, previous?.zone, null, ThermoZoneStatus.ACTIVE, input, emptyList(), emptySet(), track)
            onRecord(shell, track, record)
        }
        return track
    }

    /**
     * Wejścia sterownika, na których może zostać kalibracja Domika bez nadzoru: stan w plikach na to wskazuje
     * ([app.domik.thermo.CalibrationBaselineState.mayHoldDomikShift]), a ostatni odczyt nie pokazał kalibracji bazowej.
     * [includeLive] false – bez wejść prowadzonych przez korektor rzeczywisty (one same przywrócą bazową). Pod [lock].
     */
    private fun leftovers(includeLive: Boolean): List<InputKey> {
        val live = if (includeLive) emptySet() else bindings.filter { it.track?.live == true }.mapNotNull { b -> b.inputNr?.let { b.assignment.module to it } }.toSet()
        return files.allBaselines().filter { (key, state) ->
            val known = lastRecords[key.first]?.getOrNull(key.second)
            key !in live && state.mayHoldDomikShift && !(known != null && CalibrationMath.same(known, state.baseline))
        }.keys.sortedWith(compareBy({ it.first }, { it.second })).map { InputKey(it.first, it.second) }
    }

    private fun modulesInUse(): List<Int> = locked { bindings.filter { it.track != null }.map { it.assignment.module }.distinct() }

    private fun needsCalibrationRead(): Boolean = locked { bindings.any { it.track != null && it.track.corrector.state().controllerRecord == null } }

    // Dziennik

    /** Decyzje trybu próbnego (wszystkie porównywane progi) z dziennika korektora → plik. Pod [lock]. */
    private fun logSimulated(binding: Binding, track: Track, since: Long) {
        val fresh = track.corrector.log().filter { it.at >= since && it.at > track.lastLoggedAt }
        if (fresh.isEmpty()) return
        track.lastLoggedAt = fresh.maxOf { it.at }
        files.append(
            fresh.map {
                ThermoLogEntry(
                    at = it.at,
                    module = binding.assignment.module,
                    outputNr = binding.assignment.outputNr,
                    inputNr = it.inputNr,
                    threshold = it.threshold,
                    kind = if (it.reason == CalibrationReason.DIFFERENCE) ThermoLogKind.CALIBRATION else ThermoLogKind.RESTORE,
                    reason = it.reason,
                    dryRun = true,
                    result = ThermoLogResult.DRY_RUN,
                    xiaomiC = it.xiaomiC,
                    deimicC = it.deimicC,
                    differenceC = it.differenceC,
                    previous = it.previous,
                    next = it.next,
                    clamped = it.clamped,
                )
            },
        )
        statsDirty = true
    }

    private fun log(
        binding: Binding,
        kind: ThermoLogKind,
        result: ThermoLogResult,
        dryRun: Boolean,
        threshold: Float? = null,
        reason: CalibrationReason? = null,
        xiaomiC: Float? = null,
        deimicC: Float? = null,
        differenceC: Float? = null,
        previous: InputCalibration? = null,
        next: InputCalibration? = null,
        clamped: Boolean = false,
        detail: ThermoLogDetail? = null,
    ) {
        val entry = ThermoLogEntry(
            at = clock(),
            module = binding.assignment.module,
            outputNr = binding.assignment.outputNr,
            inputNr = binding.inputNr ?: -1,
            threshold = threshold,
            kind = kind,
            reason = reason,
            dryRun = dryRun,
            result = result,
            xiaomiC = xiaomiC,
            deimicC = deimicC,
            differenceC = differenceC,
            previous = previous,
            next = next,
            clamped = clamped,
            detail = detail,
        )
        files.append(listOf(entry))
        locked { statsDirty = true }
    }

    /** Dziennik decyzji profilu (np. do eksportu / ekranu szczegółów), najstarsze pierwsze. */
    fun decisionLog(): List<ThermoLogEntry> = files.log(clock())

    // Stan dla UI

    private fun publish() {
        _state.value = locked { buildState() }
    }

    private fun buildState(): ThermoUiState {
        val now = clock()
        val homeState = home.value
        refreshStats(now)
        val leftovers = leftovers(includeLive = false)
        val zones = bindings.map { binding -> zoneUi(binding, homeState, now, leftovers) }
        val assignedTo = settings.assignments.associate { it.mac to it.zone }
        val discovered = directory.all().map { entry ->
            val r = entry.reading
            DiscoveredThermometer(r.mac, r.name, r.rssi, r.tempC, r.humidity, r.batteryPercent, entry.receivedAt, now - entry.receivedAt, assignedTo[r.mac])
        }
        return ThermoUiState(
            active = true,
            demo = demo,
            scanner = scannerStatus,
            scanMode = scanMode,
            hub = hub,
            enabled = settings.enabled,
            dryRun = settings.dryRun,
            threshold = settings.threshold,
            writesBlockedByFlag = !writesAllowed,
            writesActive = liveMode,
            zones = zones,
            discovered = discovered,
            lastCalibrationReadAt = lastReadAt,
            updatedAt = now,
            leftoverInputs = leftovers,
        )
    }

    private fun zoneUi(binding: Binding, homeState: HomeState, now: Long, leftovers: List<InputKey>): ThermoZoneUi {
        val assignment = binding.assignment
        val entry = directory.entry(assignment.mac)
        val corrector = binding.track?.corrector
        val cs = corrector?.state()
        val deimicC = binding.inputNr?.let { homeState.analogInputs[InputKey(assignment.module, it)] }
        val age = entry?.let { now - it.receivedAt }
        val fresh = age != null && age <= timing.staleReadingAfter.inWholeMilliseconds && (cs?.sensorFresh ?: true)
        val notices = HashSet(binding.staticNotices)
        if (binding.status == ThermoZoneStatus.PAUSED) notices += ThermoNotice.EXTERNAL_CHANGE
        if (entry == null) notices += ThermoNotice.THERMOMETER_NOT_SEEN else if (!fresh) notices += ThermoNotice.THERMOMETER_STALE
        if ((entry?.reading?.batteryPercent ?: 100) <= LOW_BATTERY_PERCENT) notices += ThermoNotice.BATTERY_LOW
        if (binding.readFailed) notices += ThermoNotice.CALIBRATION_READ_FAILED
        if (binding.writeFailed) notices += ThermoNotice.WRITE_FAILED
        val issues = HashSet(binding.staticIssues)
        val manuallyPaused = binding.inputNr?.let { (assignment.module to it) in files.manuallyRestoredInputs() } == true &&
            binding.track != null
        if (manuallyPaused) issues += ThermoZoneIssue.MANUAL_RESTORE
        if (cs?.readRequired == true) issues += ThermoZoneIssue.READ_REQUIRED
        if (cs?.stateNotSaved == true) issues += ThermoZoneIssue.STATE_NOT_SAVED
        if (binding.inputNr?.let { InputKey(assignment.module, it) } in leftovers) issues += ThermoZoneIssue.CALIBRATION_NOT_RESTORED
        val raw = cs?.controllerRecord?.let { record -> (deimicC ?: cs.deimicC)?.let { CalibrationMath.invert(record, it * 10.0) } }
        val (frequency, lastDecision) = stats[assignment.zone] ?: (emptyList<ThermoFrequency>() to null)
        return ThermoZoneUi(
            zone = assignment.zone,
            name = binding.zone?.name ?: assignment.label,
            roomName = binding.roomName,
            status = binding.status,
            inputNr = binding.inputNr,
            inputCandidates = binding.candidates,
            thermometer = AssignedThermometerUi(
                mac = assignment.mac,
                label = assignment.label,
                tempC = entry?.reading?.tempC,
                humidity = entry?.reading?.humidity,
                batteryPercent = entry?.reading?.batteryPercent,
                rssi = entry?.reading?.rssi,
                receivedAt = entry?.receivedAt,
                ageMillis = age,
                fresh = fresh,
            ),
            deimicC = deimicC,
            averageXiaomiC = cs?.xiaomiC,
            averageDeimicC = cs?.deimicC,
            differenceC = cs?.differenceC,
            calibrationOffsetC = cs?.controllerRecord?.let { offsetC(it, raw) },
            baselineOffsetC = cs?.baseline?.let { offsetC(it, raw) },
            plannedOffsetC = cs?.plannedRecord?.let { offsetC(it, raw) },
            shiftFromBaselineC = cs?.shiftFromBaselineC,
            controllerRecord = cs?.controllerRecord,
            baselineRecord = cs?.baseline,
            plannedRecord = cs?.plannedRecord,
            idleReason = binding.idleReason,
            lastDecision = lastDecision,
            frequency = frequency,
            notices = notices,
            writesActive = binding.track?.live == true,
            writeInFlight = cs?.writeInFlight == true,
            issues = issues,
            manuallyPaused = manuallyPaused,
            idleDetail = binding.idleDetail,
        )
    }

    /** Liczniki kalibracji i ostatnia decyzja stref – z dziennika (przeżywają restart), przeliczane najwyżej co minutę. */
    private fun refreshStats(now: Long) {
        if (!statsDirty && now - statsAt < STATS_REFRESH_MILLIS) return
        statsDirty = false
        statsAt = now
        val log = files.log(now)
        stats = settings.assignments.associate { assignment ->
            val entries = log.filter { it.module == assignment.module && it.outputNr == assignment.outputNr }
            val thresholds = (ThermoProfileSettings.THRESHOLDS + settings.threshold).distinct().sorted()
            val since = entries.firstOrNull()?.at
            val frequency = thresholds.map { threshold ->
                val mine = entries.filter { it.threshold == threshold }
                ThermoFrequency(
                    threshold = threshold,
                    primary = threshold == settings.threshold,
                    calibrationsLast24h = mine.count { it.kind == ThermoLogKind.CALIBRATION && it.at > now - DAY_MILLIS },
                    calibrationsTotal = mine.count { it.kind == ThermoLogKind.CALIBRATION },
                    restoresTotal = mine.count { it.kind == ThermoLogKind.RESTORE },
                    since = since,
                )
            }
            val last = entries.lastOrNull { it.threshold == null || it.threshold == settings.threshold }
            assignment.zone to (frequency to last)
        }
    }

    private fun addSample(samples: ArrayDeque<Sample>, sample: Sample, now: Long) {
        val window = timing.params.averagingWindow.inWholeMilliseconds
        while (samples.isNotEmpty() && samples.first().at < now - window) samples.removeFirst()
        if (samples.size >= MAX_BUFFERED_SAMPLES) samples.removeFirst()
        samples.addLast(sample)
    }

    private inline fun <T> locked(block: () -> T): T = synchronized(lock, block)

    /** Wynik [resolveInput]: wejście korygowane (null = żadne) i ewentualny problem z ręcznym wyborem. */
    data class InputResolution(val inputNr: Int?, val issue: ThermoZoneIssue? = null)

    companion object {
        private const val MAX_REBUILD_PASSES = 3
        private const val RESTORE_ATTEMPTS = 2

        /**
         * Które wejście korygować. Bez ręcznego wyboru: jedyne wejście temperatury pomieszczenia strefy. Ręczny
         * [manual] jest przyjmowany tylko, gdy pomieszczenie ma kilka wejść i [manual] jest jednym z nich, albo gdy nie
         * ma żadnego i [manual] jest wejściem temperatury modułu ([moduleInputs]). Przy dokładnie jednym wejściu
         * pomieszczenia inny ręczny wybór to konflikt ([ThermoZoneIssue.INPUT_CONFLICT]) – bez korekty.
         */
        fun resolveInput(manual: Int?, fromRoom: List<Int>, moduleInputs: List<Int>): InputResolution = when {
            manual == null -> InputResolution(fromRoom.singleOrNull())
            fromRoom.size == 1 -> if (manual == fromRoom[0]) InputResolution(manual) else InputResolution(null, ThermoZoneIssue.INPUT_CONFLICT)
            fromRoom.size > 1 -> if (manual in fromRoom) InputResolution(manual) else InputResolution(null, ThermoZoneIssue.INPUT_NOT_ALLOWED)
            else -> if (manual in moduleInputs) InputResolution(manual) else InputResolution(null, ThermoZoneIssue.INPUT_NOT_ALLOWED)
        }

        /** Wejścia, które wolno wybrać ręcznie: wejścia pomieszczenia, a gdy go nie ma – wejścia temperatury modułu. */
        fun allowedInputs(fromRoom: List<Int>, moduleInputs: List<Int>): List<Int> = fromRoom.ifEmpty { moduleInputs }

        /**
         * [allowedInputs] strefy ([module], [outputNr]) wg konfiguracji; null, gdy strefy nie ma w [config].
         */
        fun allowedInputs(config: HomeConfig, module: Int, outputNr: Int): List<Int>? {
            val zone = config.heatingZones.firstOrNull { it.module == module && it.outputNr == outputNr } ?: return null
            val moduleInputs = config.analogInputs.filter { it.isTemperature && it.module == module }.map { it.inputNr }.distinct().sorted()
            return allowedInputs(config.temperatureInputsFor(zone).map { it.inputNr }.distinct().sorted(), moduleInputs)
        }
        private const val MAX_BUFFERED_SAMPLES = 512
        private const val LOW_BATTERY_PERCENT = 10
        private const val STATS_REFRESH_MILLIS = 60_000L
        private const val DAY_MILLIS = 24L * 3_600_000

        /** Przesunięcie odczytu przez [record] w punkcie [rawTenths] (°C); bez punktu – przy 20 °C. */
        fun offsetC(record: InputCalibration, rawTenths: Double?): Float {
            val raw = rawTenths ?: DEFAULT_RAW_TENTHS
            return ((CalibrationMath.apply(record, raw) - raw) / 10).toFloat()
        }

        private const val DEFAULT_RAW_TENTHS = 200.0
    }
}
