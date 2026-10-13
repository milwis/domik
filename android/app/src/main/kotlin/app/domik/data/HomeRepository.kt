package app.domik.data

import app.domik.deimic.client.Connection
import app.domik.deimic.client.ConnectionState
import app.domik.deimic.client.commands.CommandOutcome
import app.domik.deimic.client.commands.DeviceCommands
import app.domik.deimic.client.commands.SetpointDebouncer
import app.domik.deimic.client.commands.SetpointResult
import app.domik.deimic.client.commands.ZoneKey
import app.domik.deimic.config.ConfigException
import app.domik.deimic.config.ConfigStore
import app.domik.deimic.protocol.AnalogValueChanged
import app.domik.deimic.protocol.CommandCode
import app.domik.deimic.protocol.ControllerMessage
import app.domik.deimic.protocol.HeaterFlag
import app.domik.deimic.protocol.HeaterState
import app.domik.deimic.protocol.HeatingProfile
import app.domik.deimic.protocol.OutputEvent
import app.domik.deimic.protocol.Request
import app.domik.deimic.protocol.ShutterState
import app.domik.deimic.protocol.StateSnapshot
import app.domik.deimic.protocol.TemperatureReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.time.Duration
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Czasy i limity [HomeRepository] (testy skracają je lub wydłużają). */
data class HomeRepositoryConfig(
    /** Czas ciszy po ostatnim dotknięciu ±, zanim temperatura zadana pójdzie do sterownika. */
    val setpointQuietPeriod: Duration = SetpointDebouncer.DEFAULT_QUIET_PERIOD,
    /** Limit czasu odczytów (stan zbiorczy, strefa). */
    val readTimeout: Duration = 3.seconds,
    /** Co ile odświeżać strefy, gdy sterownik sam nic nie zgłasza. */
    val periodicRefresh: Duration = 60.seconds,
    /** Po raporcie temperatury odczekaj tyle (zbiera kolejne raporty), zanim odświeżysz strefy. */
    val refreshDebounce: Duration = 1.seconds,
    /** Najkrótszy odstęp między dwoma odświeżeniami stref. */
    val refreshMinGap: Duration = 5.seconds,
    /** Jak długo po pierwszym raporcie temperatury zbierać raporty pozostałych czujników (odczyt 0x72 przy synchronizacji). */
    val temperatureCollectWindow: Duration = 300.milliseconds,
    /** Odstęp między próbami pełnej synchronizacji po połączeniu, gdy któraś część się nie udała. */
    val syncRetryDelay: Duration = 5.seconds,
    val maxSyncAttempts: Int = 5,
) {
    init {
        require(maxSyncAttempts >= 1) { "maxSyncAttempts must be at least 1" }
    }
}

/**
 * Dane domu dla UI: łączy [Connection], [ConfigStore] i [DeviceCommands] w jeden strumień stanu ([state]).
 *
 * - **Start** ([start]): konfiguracja z pamięci podręcznej (od razu), połączenie, a po każdym połączeniu (także
 *   ponownym) synchronizacja: konfiguracja (pobierana tylko, gdy się zmieniła), stan wyjść (0x202 `[01]` → 0x21E)
 *   i odczyt wszystkich stref (0x6B). Potem żywe aktualizacje: zdarzenia wyjść 0x109/0x108, stan strefy 0x6B.
 * - **Temperatury**: raport 0x72/0x130 nie niesie numeru strefy (zależność strefa ↔ czujnik nie jest potwierdzona),
 *   więc służy tylko jako sygnał do odświeżenia stref przez 0x6B (z odstępem [HomeRepositoryConfig.refreshMinGap]);
 *   dodatkowo odświeżenie co [HomeRepositoryConfig.periodicRefresh]. Wartości wejść analogowych (0x130 = temperatura po
 *   kalibracji, numer wejścia, nie strefy) trafiają do [HomeState.analogInputs]; przy synchronizacji idzie jeszcze jeden
 *   odczyt temperatur (0x72).
 * - **Polecenia** ([toggleOutput], [adjustSetpoint], [stepProfile]): kafelek od razu pokazuje stan docelowy
 *   („oczekiwanie”); po odpowiedzi sterownika stan jest potwierdzony albo wraca do poprzedniego, a [events] niesie
 *   komunikat o błędzie. Temperatura zadana idzie przez [SetpointDebouncer] (jedno polecenie na serię dotknięć).
 * - **Rolety** ([pressShutter]): stan rolety wynika wyłącznie ze zdarzeń 0x8B sterownika (nie ma odczytu stanu), więc
 *   aplikacja niczego nie zakłada z góry – wysyła to, co wciśnięto, a kafelek pokazuje to, co sterownik zgłosił.
 *   Ruch rolety kończy się zdarzeniem „na górze / na dole”; po utracie połączenia „jedzie” przechodzi w „nieznany”
 *   (zdarzenie o dojechaniu mogło zginąć).
 *
 * Wszystko działa w [scope]; stan jest bezpieczny wątkowo. Repozytorium nie wie, czy za [connection] stoi prawdziwy
 * sterownik, czy symulowany.
 */
class HomeRepository(
    private val scope: CoroutineScope,
    private val connection: Connection,
    private val commands: DeviceCommands,
    private val configStore: ConfigStore,
    private val settings: HomeRepositoryConfig = HomeRepositoryConfig(),
) {
    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    /** Stan połączenia (dla paska „brak połączenia”). */
    val connectionState: StateFlow<ConnectionState> get() = connection.state

    private val _events = MutableSharedFlow<HomeEvent>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<HomeEvent> = _events.asSharedFlow()

    private val refreshRequests = Channel<Unit>(Channel.CONFLATED)
    private val setpointDebouncer = SetpointDebouncer(scope, settings.setpointQuietPeriod, ::sendSetpoint)
    private val lock = Any()
    private var started = false

    @Volatile
    private var readingTemperatures = false

    /** Rusza: wczytuje konfigurację z pamięci podręcznej i łączy się ze sterownikiem. Kolejne wywołania nic nie robią. */
    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
        }
        // Subskrypcja przed connect(): strumień wiadomości nie ma bufora dla spóźnionych.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { connection.messages.collect(::onMessage) }
        scope.launch { setpointDebouncer.pending.collect { pending -> _state.update { it.copy(pendingSetpoints = pending) } } }
        scope.launch { setpointDebouncer.results.collect(::onSetpointResult) }
        scope.launch { refreshLoop() }
        scope.launch { loadCachedConfig() }
        scope.launch { connection.state.collectLatest(::onConnectionState) }
        connection.connect()
    }

    /** Zamyka połączenie. Zakończenie pracy korutyn to sprawa zakresu ([scope]). */
    suspend fun stop() {
        connection.disconnect()
    }

    /** Ręczne ponowienie po poddaniu się (stan Failed) – np. po powrocie do aplikacji lub po dotknięciu „Ponów”. */
    fun retry() {
        when (connection.state.value) {
            is ConnectionState.Failed, ConnectionState.Idle -> connection.connect()
            else -> Unit
        }
        refreshRequests.trySend(Unit)
    }

    // Polecenia

    /** Dotknięcie kafelka światła: przełącza wyjście na przeciwne do ostatnio znanego. */
    fun toggleOutput(module: Int, relay: Int) {
        val key = OutputKey(module, relay)
        val current = _state.value
        if (key in current.pendingOutputs) return
        val desired = key !in current.outputsOn
        val output = current.config?.output(module, relay)
        if (desired && output != null && !output.canOn) {
            _events.tryEmit(HomeEvent.CannotSwitchOn(output.name))
            return
        }
        _state.update { it.copy(pendingOutputs = it.pendingOutputs + (key to desired)) }
        scope.launch {
            val outcome = commands.setOutput(module, relay, desired)
            _state.update { s ->
                val on = when (outcome) {
                    CommandOutcome.Confirmed, CommandOutcome.AckOnly -> desired
                    is CommandOutcome.OutputMismatch -> outcome.actualOn
                    else -> null
                }
                s.copy(
                    pendingOutputs = s.pendingOutputs - key,
                    outputsOn = when (on) {
                        null -> s.outputsOn
                        true -> s.outputsOn + key
                        false -> s.outputsOn - key
                    },
                )
            }
            if (outcome == CommandOutcome.AckOnly) readSnapshot()
            outcome.failureKind()?.let { _events.tryEmit(HomeEvent.CommandFailed(outputName(key), it)) }
        }
    }

    /**
     * Dotknięcie − lub + przy temperaturze zadanej ([steps] × 0,5 °C). Na strefie wyłączonej „+” ją włącza
     * (profil Komfort) zamiast zapisywać temperaturę – patrz [DeviceCommands.adjustSetpointOrSwitchOn].
     */
    fun adjustSetpoint(module: Int, outputNr: Int, steps: Int) {
        val key = ZoneKey(module, outputNr)
        val current = _state.value
        val zone = current.zones[key] ?: return
        if (key in current.pendingProfiles || !zone.setpoint.isFinite()) return
        if (zone.profile == HeatingProfile.Off && (steps <= 0 || !Features.PROFILE_SWITCHING)) return
        setpointDebouncer.adjust(key, steps, zone.setpoint)
    }

    /** Strzałki ‹ › kafelka: następny / poprzedni profil w kolejności Wył. → Eko → Komfort → Maks. (0x66). */
    fun stepProfile(module: Int, outputNr: Int, forward: Boolean) {
        val zone = _state.value.zones[ZoneKey(module, outputNr)] ?: return
        setProfile(module, outputNr, adjacentProfile(zone.profile, forward))
    }

    /**
     * Ustawia wybrany profil strefy wprost (okno strefy: Wył. / Eko / Komfort / Maks.) tym samym poleceniem 0x66 co
     * [stepProfile] – bez przechodzenia przez profile pośrednie. Nic nie robi, gdy strefa jest już w tym profilu,
     * nie jest odczytana albo trwa zmiana profilu / temperatury zadanej.
     */
    fun setProfile(module: Int, outputNr: Int, target: HeatingProfile) {
        val key = ZoneKey(module, outputNr)
        val current = _state.value
        val zone = current.zones[key] ?: return
        if (key in current.pendingProfiles || key in current.pendingSetpoints) return
        if (zone.profile == target || target is HeatingProfile.Unknown) return
        _state.update { it.copy(pendingProfiles = it.pendingProfiles + (key to target)) }
        scope.launch {
            val outcome = commands.setProfile(module, outputNr, target)
            when {
                // Świeży odczyt strefy: nowy profil ma własną temperaturę zadaną.
                outcome.isAccepted -> readZone(module, outputNr)
                outcome is CommandOutcome.HeaterMismatch -> putZone(key, outcome.actual)
            }
            _state.update { it.copy(pendingProfiles = it.pendingProfiles - key) }
            outcome.failureKind()?.let { _events.tryEmit(HomeEvent.CommandFailed(zoneName(key), it)) }
        }
    }

    /**
     * Przycisk ↑ ■ ↓ kafelka rolety. Wysyła dokładnie to polecenie, które wskazuje przycisk (0x8D / 0x82 / 0x8E).
     *
     * Firmware działa jak pilot jednoprzyciskowy: kierunek wysłany **w czasie ruchu** (ten sam albo przeciwny) tylko
     * zatrzymuje roletę, a zmiana kierunku to dwa dotknięcia (stop, potem drugi kierunek). Dlatego aplikacja nie
     * przewiduje wyniku – nowy stan przychodzi zdarzeniem, a do tego czasu kafelek pokazuje wciśnięty przycisk jako
     * oczekujący. Drugie dotknięcie przed odpowiedzią jest ignorowane (podwójne dotknięcie zatrzymałoby roletę
     * od razu po starcie).
     */
    fun pressShutter(module: Int, outputNr: Int, button: ShutterButton) {
        val key = ShutterKey(module, outputNr)
        if (key in _state.value.pendingShutters) return
        _state.update { it.copy(pendingShutters = it.pendingShutters + (key to button)) }
        scope.launch {
            val outcome = when (button) {
                ShutterButton.UP -> commands.shutterUp(module, outputNr)
                ShutterButton.STOP -> commands.shutterStop(module, outputNr)
                ShutterButton.DOWN -> commands.shutterDown(module, outputNr)
            }
            _state.update { s ->
                s.copy(
                    pendingShutters = s.pendingShutters - key,
                    // Potwierdzenie bez zdarzenia: polecenie przyjęte, ale co roleta robi – nie wiadomo.
                    shutters = if (outcome == CommandOutcome.AckOnly) s.shutters - key else s.shutters,
                )
            }
            outcome.failureKind()?.let { _events.tryEmit(HomeEvent.CommandFailed(shutterName(key), it)) }
        }
    }

    // Temperatura zadana (SetpointDebouncer)

    private suspend fun sendSetpoint(zone: ZoneKey, celsius: Float): CommandOutcome {
        val outcome = if (commands.experimentalProfileSwitching) {
            commands.adjustSetpointOrSwitchOn(zone.module, zone.outputNr, celsius)
        } else {
            commands.setSetpoint(zone.module, zone.outputNr, celsius)
        }
        // Stan strefy aktualizujemy, zanim SetpointDebouncer przestanie pokazywać wartość lokalną – bez mignięcia.
        when (outcome) {
            CommandOutcome.Confirmed -> patchZone(zone) { it.copy(setpoint = celsius) }
            CommandOutcome.AckOnly -> refreshRequests.trySend(Unit)
            is CommandOutcome.SwitchedToComfort ->
                outcome.state?.let { putZone(zone, it) } ?: refreshRequests.trySend(Unit)
            is CommandOutcome.HeaterMismatch -> putZone(zone, outcome.actual)
            else -> Unit
        }
        return outcome
    }

    private fun onSetpointResult(result: SetpointResult) {
        result.outcome.failureKind()?.let { _events.tryEmit(HomeEvent.CommandFailed(zoneName(result.zone), it)) }
    }

    // Wiadomości ze sterownika

    private fun onMessage(message: ControllerMessage) {
        when (message) {
            is OutputEvent -> {
                val key = OutputKey(message.module.toInt(), message.relay.toInt())
                _state.update {
                    it.copy(
                        outputsOn = if (message.isOn) it.outputsOn + key else it.outputsOn - key,
                        pendingOutputs = it.pendingOutputs - key,
                    )
                }
            }
            is HeaterState -> putZone(ZoneKey(message.module.toInt(), message.outputNr.toInt()), message)
            is ShutterState -> {
                val key = ShutterKey(message.module.toInt(), message.outputNr.toInt())
                _state.update { it.copy(shutters = it.shutters + (key to message.direction)) }
            }
            is AnalogValueChanged -> {
                putAnalogInput(InputKey(message.module.toInt(), message.input.toInt()), message.value)
                if (!readingTemperatures) refreshRequests.trySend(Unit)
            }
            is TemperatureReport -> if (!readingTemperatures) refreshRequests.trySend(Unit)
            else -> Unit
        }
    }

    /** Zapisuje wartość wejścia tylko przy zmianie o [HomeState.ANALOG_INPUT_STEP]; NaN (czujnik odłączony) usuwa wpis. */
    private fun putAnalogInput(key: InputKey, value: Float) {
        _state.update { s ->
            val old = s.analogInputs[key]
            when {
                !value.isFinite() -> if (old == null) s else s.copy(analogInputs = s.analogInputs - key)
                old != null && abs(value - old) < HomeState.ANALOG_INPUT_STEP - EPSILON -> s
                else -> s.copy(analogInputs = s.analogInputs + (key to value))
            }
        }
    }

    private fun putZone(key: ZoneKey, heater: HeaterState) {
        _state.update {
            it.copy(
                zones = it.zones + (key to heater),
                pendingProfiles = if (it.pendingProfiles[key] == heater.profile) it.pendingProfiles - key else it.pendingProfiles,
            )
        }
    }

    private fun patchZone(key: ZoneKey, change: (HeaterState) -> HeaterState) {
        _state.update { s -> s.zones[key]?.let { s.copy(zones = s.zones + (key to change(it))) } ?: s }
    }

    // Połączenie i synchronizacja

    private suspend fun onConnectionState(state: ConnectionState) {
        if (state != ConnectionState.Ready) {
            // Zdarzenie „roleta dojechała” mogło przepaść razem z połączeniem – „jedzie” staje się „nieznany”.
            _state.update { it.copy(synced = false, shutters = it.shutters.filterValues { direction -> !direction.isMoving }) }
            return
        }
        syncWithRetries()
        while (true) {
            delay(settings.periodicRefresh)
            refreshRequests.trySend(Unit)
        }
    }

    private suspend fun syncWithRetries() {
        repeat(settings.maxSyncAttempts) { attempt ->
            if (sync()) return
            if (attempt < settings.maxSyncAttempts - 1) delay(settings.syncRetryDelay)
        }
    }

    /** True, gdy nie ma czego ponawiać. */
    private suspend fun sync(): Boolean {
        val configResult = loadConfig()
        val snapshotRead = readSnapshot()
        var zonesRead = true
        _state.value.config?.heatingZones?.forEach { zone ->
            if (!readZone(zone.module, zone.outputNr)) zonesRead = false
        }
        _state.update { it.copy(synced = snapshotRead) }
        readTemperatures()
        return configResult != ConfigResult.RETRY && snapshotRead && zonesRead
    }

    private enum class ConfigResult { OK, RETRY, GIVE_UP }

    private suspend fun loadCachedConfig() {
        val cached = try {
            configStore.loadCached()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return
        _state.update { if (it.config == null) it.copy(config = cached.config, configStatus = ConfigStatus.Ready) else it }
    }

    private suspend fun loadConfig(): ConfigResult =
        try {
            val loaded = configStore.load()
            _state.update { it.copy(config = loaded.config, configStatus = ConfigStatus.Ready) }
            ConfigResult.OK
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ConfigException) {
            failConfig(ConfigFailure.UNREADABLE)
            ConfigResult.GIVE_UP
        } catch (_: IOException) {
            failConfig(ConfigFailure.NO_CONNECTION)
            ConfigResult.RETRY
        } catch (_: Exception) {
            failConfig(ConfigFailure.OTHER)
            ConfigResult.RETRY
        }

    /** Błąd konfiguracji jest widoczny dla użytkownika tylko wtedy, gdy nie ma żadnej (nawet starej) kopii. */
    private fun failConfig(reason: ConfigFailure) {
        _state.update { if (it.config == null) it.copy(configStatus = ConfigStatus.Failed(reason)) else it }
    }

    private suspend fun readSnapshot(): Boolean =
        try {
            val result = connection.request(
                Request.getState(connection.serial), setOf(CommandCode.STATE_NEW), settings.readTimeout,
            )
            val snapshot = ControllerMessage.decode(result.responses.first()) as? StateSnapshot
            if (snapshot == null || snapshot.modules.isEmpty()) {
                false
            } else {
                applySnapshot(snapshot)
                true
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }

    private fun applySnapshot(snapshot: StateSnapshot) {
        _state.update { s ->
            val modules = snapshot.modules.map { it.module.toInt() }.toSet()
            val on = s.outputsOn.filterTo(HashSet()) { it.module !in modules }
            snapshot.modules.forEach { module -> module.onOutputs.forEach { relay -> on += OutputKey(module.module.toInt(), relay) } }
            val zones = s.zones.mapValues { (key, heater) ->
                when (snapshot.module(key.module.toUInt())?.heaters?.getOrNull(key.outputNr)) {
                    HeaterFlag.Heating -> heater.copy(isHeating = true)
                    HeaterFlag.Idle -> heater.copy(isHeating = false)
                    else -> heater
                }
            }
            s.copy(outputsOn = on, zones = zones)
        }
    }

    private suspend fun readZone(module: Int, outputNr: Int): Boolean =
        try {
            val result = connection.request(
                Request.heaterState(connection.serial, module.toUInt(), outputNr.toUByte()),
                setOf(CommandCode.HEATER_STATE),
                settings.readTimeout,
            ) { it.address == module.toUInt() && it.payload.size >= 1 && it.payload[0].toInt() == outputNr }
            val heater = ControllerMessage.decode(result.responses.first()) as? HeaterState
            if (heater == null) {
                false
            } else {
                putZone(ZoneKey(module, outputNr), heater)
                true
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }

    /**
     * Odczyt temperatur (0x72, tylko odczyt, pusty ładunek): każdy czujnik odpowiada raportem 0x72 i zdarzeniem 0x130,
     * które [onMessage] zapisuje w [HomeState.analogInputs]. Najlepszym wysiłkiem – niepowodzenie nie wymusza ponowienia
     * synchronizacji (wartości i tak przyjdą ze zdarzeniami).
     */
    private suspend fun readTemperatures() {
        // Odpowiedzi na ten odczyt nie są „zmianą”, która wymagałaby odświeżenia stref (przed chwilą je odczytano).
        readingTemperatures = true
        try {
            connection.request(
                Request.temperatureReadout(connection.serial), setOf(CommandCode.TEMPERATURE_REPORT), settings.readTimeout,
                collectWindow = settings.temperatureCollectWindow,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // brak odpowiedzi: nic się nie stało, odczyt powtórzy się przy następnej synchronizacji
        } finally {
            readingTemperatures = false
        }
    }

    private suspend fun refreshLoop() {
        for (request in refreshRequests) {
            if (!_state.value.synced) continue // zaraz po połączeniu synchronizacja i tak czyta wszystko
            delay(settings.refreshDebounce)
            _state.value.config?.heatingZones?.forEach { readZone(it.module, it.outputNr) }
            delay(settings.refreshMinGap)
        }
    }

    // Nazwy do komunikatów (zawsze z konfiguracji)

    private fun outputName(key: OutputKey): String = _state.value.config?.output(key.module, key.relay)?.name.orEmpty()

    private fun shutterName(key: ShutterKey): String = _state.value.config?.shutter(key.module, key.outputNr)?.name.orEmpty()

    private fun zoneName(key: ZoneKey): String = _state.value.config?.heatingZone(key.module, key.outputNr)?.name.orEmpty()

    internal companion object {
        private const val EPSILON = 1e-4f
        private val PROFILE_ORDER = listOf(HeatingProfile.Off, HeatingProfile.Eco, HeatingProfile.Comfort, HeatingProfile.Maximum)

        /** Następny / poprzedni profil w kolejności kafelka (zawijanie na końcach; nieznany → Wył.). */
        fun adjacentProfile(profile: HeatingProfile, forward: Boolean): HeatingProfile {
            val index = PROFILE_ORDER.indexOf(profile)
            if (index < 0) return PROFILE_ORDER.first()
            val step = if (forward) 1 else PROFILE_ORDER.size - 1
            return PROFILE_ORDER[(index + step) % PROFILE_ORDER.size]
        }
    }
}
