package app.domik.data

import app.domik.deimic.config.HomeConfig
import app.domik.integrations.calendar.CalendarModel
import app.domik.integrations.weather.WeatherModel
import app.domik.integrations.weather.WeatherOptions
import app.domik.ui.theme.AppearanceSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Stan przycisku „Testuj połączenie”. */
sealed interface ConnectionTestState {
    data object Idle : ConnectionTestState

    data object Running : ConnectionTestState

    data class Done(val outcome: ConnectionTestOutcome) : ConnectionTestState
}

/**
 * Formularz połączenia ze sterownikiem. [serial] to tekst wpisany przez użytkownika – dlatego [toString] go nie
 * ujawnia (żeby nie trafił do logów).
 *
 * @property serialStored w ustawieniach jest już zapisany numer seryjny – pole można zostawić puste
 * @property canCancel jest dokąd wrócić bez zapisywania (poprzedni ekran)
 * @property canForget jest zapisany sterownik, który można usunąć
 * @property storageFailed nie udało się zaszyfrować i zapisać numeru seryjnego
 */
data class ConnectionFormState(
    val name: String = "",
    val host: String = "",
    val port: String = "",
    val serial: String = "",
    val hostError: FieldError? = null,
    val portError: FieldError? = null,
    val serialError: FieldError? = null,
    val serialStored: Boolean = false,
    val test: ConnectionTestState = ConnectionTestState.Idle,
    val storageFailed: Boolean = false,
    val canCancel: Boolean = false,
    val canForget: Boolean = false,
) {
    override fun toString(): String = "ConnectionFormState(host=$host, port=$port, serial=<ukryty>)"
}

/** Co o ustawieniach potrzebuje menu: tryb pracy i (bez numeru seryjnego) zapisany sterownik. */
data class AppInfo(val mode: AppMode, val controllerName: String, val controllerHost: String?)

/**
 * Układ pulpitu bieżącej sesji: [saved] to zapis z ustawień (null = nie zapisano, obowiązuje układ domyślny),
 * [draft] – układ w trybie edycji (null = poza edycją; wtedy zapis i bieżący układ są takie same).
 *
 * @property migrateAlarms [saved] jest w starszym formacie (sprzed ukrywania wyjść alarmowych): do pierwszej zmiany
 *   w trybie edycji widoczne wyjścia alarmowe są pokazywane jako ukryte (jednorazowa migracja, bez zapisu – zapis
 *   w wersji 3 powstaje przy pierwszej edycji, po której alarm dodany przez użytkownika już nie jest ukrywany).
 */
data class LayoutState(
    val saved: DashboardLayout? = null,
    val draft: DashboardLayout? = null,
    val migrateAlarms: Boolean = false,
) {
    val editing: Boolean get() = draft != null

    /** Układ do pokazania: robocza kopia w edycji, inaczej zapis uzgodniony z [config] (domyślny dla [columns] kolumn). */
    fun resolve(config: HomeConfig?, columns: Int): DashboardLayout =
        draft ?: DashboardLayout.reconcile(saved, config, columns, migrateAlarms)
}

/**
 * Logika aplikacji ponad sesją domu, bez Androida (testowalna na JVM): który ekran jest widoczny, wygląd,
 * formularz połączenia z testem, przełączanie między trybem demo a prawdziwym sterownikiem i cykl życia sesji
 * ([HomeSession]). Wszystkie akcje wywoływane z UI wracają od razu; praca dzieje się w [scope].
 *
 * Zmiany sesji są szeregowane (jedna naraz): stara sesja jest zamykana przed otwarciem nowej, więc dwa
 * połączenia ze sterownikiem nigdy nie istnieją równocześnie.
 */
class AppModel(private val env: AppEnvironment, private val scope: CoroutineScope) {
    private val settings = env.settings
    private val sessionScope = CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + env.sessionDispatcher)
    private val sessionLock = Mutex()

    private val _route = MutableStateFlow(resolveRoute(settings))
    private val _appearance = MutableStateFlow(settings.appearance)
    private val _session = MutableStateFlow<HomeSession?>(null)
    private val _form = MutableStateFlow(ConnectionFormState())
    private val _info = MutableStateFlow(readInfo())
    private val _layout = MutableStateFlow(LayoutState())
    private val _icons = MutableStateFlow(IconChoices.EMPTY)
    private val _weatherOptions = MutableStateFlow(WeatherOptions.DEFAULT)
    private var testJob: Job? = null

    val route: StateFlow<Route> = _route.asStateFlow()
    val appearance: StateFlow<AppearanceSettings> = _appearance.asStateFlow()

    /** Aktywna sesja; null poza pulpitem i na chwilę podczas przełączania. */
    val session: StateFlow<HomeSession?> = _session.asStateFlow()
    val form: StateFlow<ConnectionFormState> = _form.asStateFlow()
    val info: StateFlow<AppInfo> = _info.asStateFlow()

    /** Układ pulpitu bieżącej sesji i tryb edycji. */
    val layout: StateFlow<LayoutState> = _layout.asStateFlow()

    /** Ikony kafelków wybrane ręcznie przez użytkownika (bieżąca sesja); brak wpisu = dobór automatyczny. */
    val icons: StateFlow<IconChoices> = _icons.asStateFlow()

    /** Co pokazuje kafelek pogody (bieżąca sesja); bez zapisu obowiązują ustawienia domyślne. */
    val weatherOptions: StateFlow<WeatherOptions> = _weatherOptions.asStateFlow()

    /** Kalendarz z urządzenia (kafelek kalendarza w trybie sterownika; tryb demo go nie używa). */
    val calendar = CalendarModel(env.calendarSource, settings, scope, env.ioDispatcher)

    /** Pogoda dla wybranej miejscowości (kafelek pogody w trybie sterownika; tryb demo jej nie używa). */
    val weather = WeatherModel(
        env.weatherSource, settings, scope, env.ioDispatcher, env.weatherCache,
        searchLanguage = env.weatherSearchLanguage,
    )

    init {
        when (_route.value) {
            Route.DASHBOARD -> restartSession()
            Route.CONNECTION_SETTINGS -> prepareForm()
            else -> Unit
        }
    }

    // Wygląd

    fun setAppearance(value: AppearanceSettings) {
        settings.appearance = value
        _appearance.value = value
    }

    // Regulamin i wybór trybu

    fun acceptTerms() {
        settings.acceptTerms()
        showRoute(resolveRoute(settings))
    }

    fun chooseDemo() {
        settings.mode = AppMode.DEMO
        refreshInfo()
        showRoute(Route.DASHBOARD)
    }

    /** „Połącz ze swoim sterownikiem” (ekran powitalny albo menu w trybie demo). */
    fun openConnectionSettings() {
        prepareForm()
        _route.value = Route.CONNECTION_SETTINGS
    }

    /** Wychodzi z formularza bez zapisu: do pulpitu albo (przy pierwszym uruchomieniu) na ekran powitalny. */
    fun cancelConnectionSettings() {
        testJob?.cancel()
        showRoute(resolveRoute(settings).takeIf { it != Route.CONNECTION_SETTINGS } ?: return)
    }

    /** Opuszcza tryb demo: wraca do zapisanego sterownika, a gdy go nie ma – na ekran powitalny. */
    fun leaveDemo() {
        if (settings.mode != AppMode.DEMO) return
        settings.mode = if (settings.controller()?.serial != null) AppMode.CONTROLLER else AppMode.UNSET
        refreshInfo()
        showRoute(resolveRoute(settings))
    }

    // Formularz połączenia

    fun editName(value: String) = _form.update { it.copy(name = value) }

    fun editHost(value: String) = _form.update { it.copy(host = value, hostError = null, test = ConnectionTestState.Idle) }

    fun editPort(value: String) = _form.update { it.copy(port = value, portError = null, test = ConnectionTestState.Idle) }

    fun editSerial(value: String) = _form.update {
        it.copy(serial = value, serialError = null, storageFailed = false, test = ConnectionTestState.Idle)
    }

    /** „Testuj połączenie”: walidacja pól, potem sprawdzenie TLS, odpowiedzi na ping i wersji (tylko odczyt). */
    fun testConnection() {
        val input = validatedInput() ?: return
        testJob?.cancel()
        _form.update { it.copy(test = ConnectionTestState.Running) }
        testJob = scope.launch {
            val outcome = try {
                env.connectionTestRunner.run(input).toOutcome()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ConnectionTestOutcome.Failure(ConnectionTestFailure.OTHER)
            }
            _form.update { it.copy(test = ConnectionTestState.Done(outcome)) }
        }
    }

    /** „Zapisz”: waliduje, zapisuje (numer seryjny szyfrowany), przełącza na sesję z prawdziwym sterownikiem. */
    fun saveConnection() {
        val input = validatedInput() ?: return
        testJob?.cancel()
        val previous = settings.controller()?.profile
        val profile = try {
            settings.saveController(_form.value.name.trim(), input.host, input.port, input.serial)
        } catch (_: SecretStorageException) {
            _form.update { it.copy(storageFailed = true) }
            return
        }
        settings.mode = AppMode.CONTROLLER
        refreshInfo()
        if (previous != null && previous.id != profile.id) removeCachedConfig(previous.id)
        _form.update { it.copy(serial = "", test = ConnectionTestState.Idle) }
        showRoute(Route.DASHBOARD)
    }

    /** Usuwa zapisany sterownik (adres, numer seryjny, kopię konfiguracji). */
    fun forgetController() {
        testJob?.cancel()
        val removed = settings.clearController() ?: return
        removeCachedConfig(removed.id)
        if (settings.mode == AppMode.CONTROLLER) settings.mode = AppMode.UNSET
        refreshInfo()
        showRoute(resolveRoute(settings))
    }

    // Sesja

    // Układ pulpitu i tryb edycji. Każda zmiana w trybie edycji jest od razu zapisywana (osobno dla profilu sesji),
    // więc nic nie ginie przy zabiciu aplikacji; „Gotowe” tylko kończy edycję.

    /** Wchodzi w tryb edycji; [columns] = liczba kolumn siatki (od niej zależy układ domyślny). */
    fun startLayoutEdit(config: HomeConfig?, columns: Int) {
        val state = _layout.value
        if (state.editing || _session.value == null) return
        _layout.value = state.copy(draft = state.resolve(config, columns))
    }

    /** Kończy tryb edycji (zmiany są już zapisane). */
    fun finishLayoutEdit() {
        _layout.update { it.copy(draft = null) }
    }

    /** Przesuwa kafelek na pozycję [toIndex] wśród widocznych. */
    fun moveTile(id: TileId, toIndex: Int) = editLayout { it.moved(id, toIndex) }

    /** Przełącza kafelek na kolejny rozmiar z listy jego rodzaju (przycisk „rozmiar”) w siatce o [columns] kolumnach. */
    fun cycleTileSize(id: TileId, columns: Int = TileSize.MAX_COLS) = editLayout { it.cycled(id, columns) }

    /** Ustawia rozmiar kafelka (przeciągnięcie rogu); rozmiar niedozwolony dla rodzaju kafelka – bez zmian. */
    fun resizeTile(id: TileId, size: TileSize) = editLayout { it.resized(id, size) }

    /** Ukrywa kafelek (można go przywrócić przyciskiem „Dodaj”). */
    fun hideTile(id: TileId) = editLayout { it.hide(id) }

    /** Dodaje ukryty kafelek na koniec pulpitu. */
    fun showTile(id: TileId) = editLayout { it.show(id) }

    /** Przywraca układ domyślny (kasuje zapis); w trybie edycji zostaje on włączony. */
    fun resetLayout(config: HomeConfig?, columns: Int) {
        val session = _session.value ?: return
        settings.clearLayout(session.profileId)
        _layout.update { LayoutState(saved = null, draft = if (it.editing) DashboardLayout.default(config, columns) else null) }
    }

    /**
     * Zapamiętuje ikonę kafelka [id] wybraną w trybie edycji (`null` = „Automatycznie”) dla profilu sesji. Układ
     * pulpitu i „Przywróć układ domyślny” tego wyboru nie dotykają.
     */
    fun setTileIcon(id: TileId.Switch, icon: LightIcon?) {
        val session = _session.value ?: return
        val updated = _icons.value.with(id, icon)
        if (updated == _icons.value) return
        if (updated.isEmpty) settings.clearIcons(session.profileId) else settings.saveIcons(session.profileId, updated.encode())
        _icons.value = updated
    }

    /** Zapamiętuje wybór elementów kafelka pogody ([WeatherOptions]) dla profilu sesji; zmiana jest od razu widoczna na kafelku. */
    fun setWeatherOptions(options: WeatherOptions) {
        val session = _session.value ?: return
        if (options == _weatherOptions.value) return
        if (options == WeatherOptions.DEFAULT) {
            settings.clearWeatherOptions(session.profileId)
        } else {
            settings.saveWeatherOptions(session.profileId, options.encode())
        }
        _weatherOptions.value = options
    }

    private fun editLayout(change: (DashboardLayout) -> DashboardLayout) {
        val session = _session.value ?: return
        val current = _layout.value.draft ?: return
        val updated = change(current)
        if (updated == current) return
        settings.saveLayout(session.profileId, updated.encode())
        _layout.value = LayoutState(saved = updated, draft = updated)
    }

    /** Ponawia połączenie od razu (przycisk na pasku, powrót aplikacji na pierwszy plan). */
    fun retryConnection() {
        _session.value?.repository?.retry()
    }

    /** Kończy pracę (ViewModel zniszczony): zrywa połączenie i anuluje korutyny sesji. */
    fun shutdown() {
        val session = _session.value
        _session.value = null
        // Własny zakres: zakres ViewModelu bywa już anulowany, a połączenie trzeba zamknąć porządnie.
        CoroutineScope(env.sessionDispatcher).launch {
            try {
                session?.close()
            } finally {
                sessionScope.cancel()
            }
        }
    }

    private fun showRoute(route: Route) {
        _route.value = route
        when (route) {
            Route.DASHBOARD -> restartSession()
            Route.WELCOME, Route.TERMS -> closeSession()
            Route.CONNECTION_SETTINGS -> prepareForm()
        }
    }

    private fun restartSession() {
        scope.launch {
            sessionLock.withLock {
                closeCurrent()
                val opened = openSession()?.also { it.repository.start() }
                val stored = opened?.let { DashboardLayout.load(settings.layout(it.profileId)) }
                _layout.value = LayoutState(saved = stored?.layout, migrateAlarms = stored?.alarmMigrationPending == true)
                _icons.value = opened?.let { IconChoices.decode(settings.icons(it.profileId)) } ?: IconChoices.EMPTY
                _weatherOptions.value = opened?.let { WeatherOptions.decode(settings.weatherOptions(it.profileId)) } ?: WeatherOptions.DEFAULT
                _session.value = opened
            }
        }
    }

    private fun closeSession() {
        scope.launch { sessionLock.withLock { closeCurrent() } }
    }

    private suspend fun closeCurrent() {
        val old = _session.value ?: return
        _session.value = null
        _layout.value = LayoutState()
        _icons.value = IconChoices.EMPTY
        _weatherOptions.value = WeatherOptions.DEFAULT
        old.close()
    }

    private fun openSession(): HomeSession? = when (settings.mode) {
        AppMode.DEMO -> Sessions.demo(
            sessionScope, env.demoCache(), env.demoNetwork, env.demoSimulation, env.ioDispatcher, env.repositoryConfig,
        )
        AppMode.CONTROLLER -> settings.controller()?.let { saved ->
            saved.serial?.let { serial ->
                Sessions.controller(
                    sessionScope, saved.profile, serial, env.controllerCache(), env.ioDispatcher, env.repositoryConfig,
                    transportFactory = env.controllerTransport,
                )
            }
        }
        AppMode.UNSET -> null
    }

    private fun removeCachedConfig(profileId: String) {
        scope.launch(env.ioDispatcher) {
            try {
                env.controllerCache().remove(profileId)
            } catch (_: Exception) {
                // Pozostałość kopii jest nieszkodliwa (osobny identyfikator profilu); nie przerywamy zapisu.
            }
        }
    }

    private fun prepareForm() {
        val saved = settings.controller()
        _form.value = ConnectionFormState(
            name = saved?.profile?.name.orEmpty(),
            host = saved?.profile?.host.orEmpty(),
            port = saved?.profile?.port?.toString().orEmpty(),
            serialStored = saved?.serial != null,
            canCancel = resolveRoute(settings) != Route.CONNECTION_SETTINGS,
            canForget = saved != null,
        )
    }

    /** Waliduje pola; przy błędzie zapisuje go w formularzu i zwraca null. Puste pole numeru = zapisany numer. */
    private fun validatedInput(): ConnectionInput? {
        val form = _form.value
        val stored = settings.controller()?.serial
        val serialText = if (form.serial.isBlank() && stored != null) stored.hexString else form.serial
        return when (val result = ConnectionInputValidator.validate(form.host, form.port, serialText)) {
            is ConnectionInputResult.Valid -> result.input
            is ConnectionInputResult.Invalid -> {
                _form.update {
                    it.copy(hostError = result.host, portError = result.port, serialError = result.serial)
                }
                null
            }
        }
    }

    private fun refreshInfo() {
        _info.value = readInfo()
    }

    private fun readInfo(): AppInfo {
        val saved = settings.controller()
        return AppInfo(settings.mode, saved?.profile?.name.orEmpty(), saved?.profile?.host)
    }
}
