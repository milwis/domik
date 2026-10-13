package app.domik.integrations.emodul

import app.domik.data.EmodulCatalog
import app.domik.data.SecretStorageException
import app.domik.data.TileId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Dlaczego ostatnie odświeżenie się nie udało (bez szczegółów – nic z odpowiedzi nie trafia do komunikatów). */
enum class EmodulError { NETWORK, RATE_LIMITED, BAD_RESPONSE, TOO_LARGE }

/**
 * Moduł do pokazania. [snapshot] `null` = dane jeszcze nie pobrane. [menus] – ostatnio pobrane menu (tylko gdy moduł
 * ma kafelki powiązane z menu). [fetchedAtMillis] – kiedy pobrano [snapshot] (dla danych z pliku: kiedy były pobrane).
 * [selection] – wybór użytkownika, co z modułu pokazywać na pulpicie (§6.4a).
 */
data class EmodulModuleData(
    val slot: Int,
    val info: EmodulModuleInfo,
    val enabled: Boolean,
    val snapshot: EmodulSnapshot?,
    val menus: Map<MenuType, EmodulMenu>,
    val texts: EmodulTexts,
    val fetchedAtMillis: Long?,
    val selection: EmodulSelection = EmodulSelection.EMPTY,
) {
    /** Czas danych sterownika (`tilesLastUpdate`) – to on jest pokazywany na kafelkach, nie czas pobrania z chmury. */
    val dataTime: java.time.Instant? get() = snapshot?.lastUpdate

    /** Aktualność danych w chwili [nowMillis] (zależy od czasu, więc liczona na żądanie; bez migawki – [EmodulStaleness.Fresh]). */
    fun staleness(nowMillis: Long): EmodulStaleness =
        if (snapshot == null) EmodulStaleness.Fresh
        else EmodulPolicy.staleness(snapshot.lastUpdate, fetchedAtMillis, info.online, nowMillis)
}

/** Stan integracji dla pulpitu i ustawień. */
sealed interface EmodulState {
    /** Brak klucza (albo został usunięty): kafelki eModul znikają, brak ruchu sieciowego. */
    data object NotConfigured : EmodulState

    /** Klucz jest, ale nie ma jeszcze żadnych danych (pierwsze pobranie, odczyt plików). */
    data object Loading : EmodulState

    /** Serwer odrzucił klucz (401/403): odpytywanie stoi do zmiany klucza albo [EmodulModel.refreshNow]. */
    data object KeyRejected : EmodulState

    /** Są dane ([modules] – także te z pliku, możliwe nieaktualne); [error] = ostatnie odświeżenie nieudane. */
    data class Ready(val modules: List<EmodulModuleData>, val error: EmodulError?) : EmodulState

    /** Nie ma żadnych danych, a ostatnia próba się nie udała. */
    data class Failed(val error: EmodulError) : EmodulState
}

/** Wynik „Sprawdź i zapisz” ([EmodulModel.validateAndSave]). */
sealed interface EmodulSaveResult {
    /** Klucz zapisany (zaszyfrowany); [moduleCount] modułów na koncie. */
    data class Saved(val moduleCount: Int) : EmodulSaveResult

    /** Użytkownik nie zaakceptował jeszcze komunikatu o prywatności – nic nie zostało wysłane. */
    data object NoticeRequired : EmodulSaveResult

    /** Zły kształt klucza (sprawdzenie lokalne, bez sieci). */
    data object Malformed : EmodulSaveResult

    /** Klucz ma datę ważności w przeszłości (lokalnie, bez sieci). */
    data object Expired : EmodulSaveResult

    /** eModul odrzucił klucz (401/403). */
    data object Rejected : EmodulSaveResult

    /** Nie udało się połączyć albo odpowiedź była niepoprawna. */
    data class Failed(val error: EmodulError) : EmodulSaveResult

    /** Nie udało się zaszyfrować klucza (Keystore). */
    data object StorageFailed : EmodulSaveResult

    /** Tryb demo: klucz nie jest używany. */
    data object Demo : EmodulSaveResult
}

/**
 * Dane pobrane przez kreator „Dodaj integrację eModul” **bez zapisywania czegokolwiek**: [modules] z migawkami,
 * menu i tekstami do pokazania listy wyboru (numery slotów są tymczasowe, z [EmodulModuleData.selection] istniejącego
 * wyboru, gdy zmieniany jest klucz tego samego konta). Klucz jest w środku tylko do [EmodulModel.commit] –
 * [toString] go nie pokazuje; porzucenie obiektu (Anuluj) nie zostawia żadnego śladu.
 */
class EmodulPreview internal constructor(
    internal val key: EmodulKey,
    internal val rawModules: String,
    internal val demo: Boolean,
    val modules: List<EmodulModuleData>,
) {
    override fun toString(): String = "EmodulPreview(<ukryty>)"
}

/** Wynik kroku 1 kreatora ([EmodulModel.preview]). */
sealed interface EmodulPreviewResult {
    data class Ready(val preview: EmodulPreview) : EmodulPreviewResult

    /** Użytkownik nie zaakceptował komunikatu o prywatności – nic nie zostało wysłane. */
    data object NoticeRequired : EmodulPreviewResult

    data object Malformed : EmodulPreviewResult

    data object Expired : EmodulPreviewResult

    /** eModul odrzucił klucz (401/403). */
    data object Rejected : EmodulPreviewResult

    data class Failed(val error: EmodulError) : EmodulPreviewResult
}

/**
 * Integracja eModul (**tylko odczyt**): klucz, lista modułów, dane modułów, menu i słownik tekstów.
 *
 * Stany ([state]): [EmodulState.NotConfigured] (brak klucza) → po zapisie klucza [EmodulState.Loading] → [EmodulState.Ready];
 * 401/403 → [EmodulState.KeyRejected] (stop, aż do [refreshNow] albo nowego klucza); przy braku jakichkolwiek danych
 * i błędzie → [EmodulState.Failed]; błąd przy istniejących danych zostaje w [EmodulState.Ready.error].
 *
 * Odświeżanie wywołuje UI: [refreshDue] w pętli, tylko gdy pulpit jest widoczny i ma kafelek eModul albo otwarty
 * jest ekran ustawień (zasady w [EmodulPolicy]). W tle model nie wysyła niczego. Wszystkie żądania są sekwencyjne
 * (jedno odświeżenie naraz), blokujące wywołania [EmodulApi] idą na [ioDispatcher]. Po [removeKey], zmianie klucza
 * i zmianie trybu demo trwające odświeżenie nie wysyła już kolejnych żądań, a jego wyniki są odrzucane.
 *
 * Tryb demo ([setDemo]): [DemoEmodulApi], rejestr w pamięci (slot 1), bez plików; prawdziwy [EmodulApi] nie jest wołany.
 * Klucz, jego fragmenty ani dane z odpowiedzi nie trafiają do logów ani wyjątków.
 */
class EmodulModel(
    private val api: EmodulApi,
    private val settings: EmodulSettings,
    private val store: EmodulStore,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
    private val language: () -> String = { EmodulUrls.DEFAULT_LANGUAGE },
    private val fallbacks: EmodulFallbacks = PlainFallbacks,
) {
    private class SlotData {
        var snapshot: EmodulSnapshot? = null
        var snapshotAt: Long? = null
        var menus: Map<MenuType, EmodulMenu> = emptyMap()
        var menusAt: Long? = null
    }

    /** Odrzucony wynik: w międzyczasie zmienił się klucz / tryb. */
    private class Superseded : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    private val demoApi = DemoEmodulApi(clock)

    /** Chroni pola poniżej; trzymany krótko (bez sieci). */
    private val lock = Any()

    /** Szereguje odświeżenia (jedno naraz). */
    private val cycleMutex = Mutex()

    @Volatile
    private var epoch = 0

    @Volatile
    private var loadJob: Job? = null

    private var demo = false

    /** Tryb demo: integracja istnieje dopiero po zapisie kreatora (jak prawdziwy klucz); wybór trzymany w pamięci. */
    private var demoConfigured = false
    private val demoSelections = HashMap<Int, EmodulSelection>()
    private var key: EmodulKey? = settings.key()
    private var registry: List<EmodulSlot> = if (key != null) settings.slots() else emptyList()
    private var infos: Map<String, EmodulModuleInfo>? = null
    private var modulesAt: Long? = null
    private val slotData = HashMap<Int, SlotData>()
    private var dictionary: Map<Int, String> = emptyMap()
    private var dictionaryAt: Long? = null
    private var dictionaryLanguage: String? = null
    private var halted = false
    private var failures = 0
    private var blockedUntil = 0L
    private var lastError: EmodulError? = null

    /** Zakończyła się co najmniej jedna próba odświeżenia (udana lub nie) od startu / zmiany klucza / trybu. */
    private var settled = false

    private val _state = MutableStateFlow<EmodulState>(if (key == null) EmodulState.NotConfigured else EmodulState.Loading)
    val state: StateFlow<EmodulState> = _state.asStateFlow()

    init {
        if (key != null) startLoadingStored()
    }

    /** Komunikat o prywatności (§6.3) został zaakceptowany – dopiero wtedy wolno wysłać klucz. */
    val noticeAccepted: Boolean get() = settings.noticeAccepted

    fun acceptNotice() = settings.acceptNotice()

    /** Zapisany klucz w postaci do pokazania (`…abcd`); nigdy cały. `null` bez klucza i w trybie demo. */
    fun maskedKey(): String? = synchronized(lock) { if (demo) null else key?.masked }

    /**
     * Pasek uwagi na górze pulpitu: najgorszy stan wśród włączonych modułów z kafelkiem na pulpicie ([visibleSlots]).
     * [EmodulAttention.None] w trybie demo, poza wyjątkiem klucza odrzuconego (patrz niżej) bez widocznych kafelków
     * oraz do zakończenia pierwszej próby odświeżenia po starcie (dane z pliku są wtedy z natury stare).
     * [EmodulAttention.KeyRejected] nie wymaga [visibleSlots]: po odrzuceniu klucza katalog jest pusty, więc kafelki
     * znikają z pulpitu, a użytkownik musi się dowiedzieć dlaczego.
     */
    fun attention(visibleSlots: Set<Int>, nowMillis: Long = clock()): EmodulAttention = synchronized(lock) {
        if (demo) return EmodulAttention.None
        when (val current = _state.value) {
            EmodulState.KeyRejected -> EmodulAttention.KeyRejected
            is EmodulState.Ready ->
                if (!settled || visibleSlots.isEmpty()) {
                    EmodulAttention.None
                } else {
                    EmodulAttention.worst(
                        current.modules.filter { it.enabled && it.snapshot != null && it.slot in visibleSlots }
                            .map { it.staleness(nowMillis) },
                    )
                }
            else -> EmodulAttention.None
        }
    }

    /** Katalog kafelków do uzgadniania układu pulpitu (§7.3); patrz [toCatalog]. */
    fun catalog(): EmodulCatalog? = _state.value.toCatalog()

    /** Czy model pracuje na danych demo. */
    val isDemo: Boolean get() = synchronized(lock) { demo }

    /**
     * Przełącza tryb demo (wywoływane przy otwarciu sesji). Demo: syntetyczny kocioł dostępny przez kreator (bez klucza),
     * rejestr w pamięci (slot 1), zero ruchu sieciowego i plików; do czasu zapisu kreatora stan to
     * [EmodulState.NotConfigured]. Wyjście z demo wraca do prawdziwego klucza (dane z plików).
     */
    fun setDemo(value: Boolean) {
        synchronized(lock) {
            if (demo == value) return
            epoch++
            demo = value
            resetData()
            if (value) {
                registry = listOf(EmodulSlot(1, DemoEmodulApi.UDID, enabled = true))
                demoConfigured = false
                demoSelections.clear()
                _state.value = EmodulState.NotConfigured
            } else {
                key = settings.key()
                registry = if (key != null) settings.slots() else emptyList()
                _state.value = if (key == null) EmodulState.NotConfigured else EmodulState.Loading
                if (key != null) startLoadingStored()
            }
        }
    }

    /**
     * Wykonuje zaległe odczyty wg [EmodulPolicy] i zwraca, za ile ms wywołać ponownie (nie więcej niż 60 s; gdy nic
     * nie jest do zrobienia albo odpytywanie stoi, też tyle – wywołanie bez zaległości nie wysyła nic).
     * [resumed] = pulpit wrócił na pierwszy plan: moduły starsze niż 30 s odświeżane od razu.
     */
    suspend fun refreshDue(resumed: Boolean = false): Long = cycle(force = false, resumed = resumed)

    /** „Odśwież” / „Spróbuj ponownie”: zeruje odstęp po błędzie i stan [EmodulState.KeyRejected], odświeża wszystko. */
    fun refreshNow() {
        synchronized(lock) {
            if (!configured()) return
            halted = false
            failures = 0
            blockedUntil = 0L
            modulesAt = null
            if (_state.value == EmodulState.KeyRejected) publish()
        }
        scope.launch { cycle(force = true, resumed = false) }
    }

    /**
     * „Sprawdź i zapisz”: waliduje klucz lokalnie, potem (po zaakceptowaniu komunikatu) pobiera listę modułów –
     * dopiero poprawna odpowiedź zapisuje klucz zaszyfrowany i uruchamia pierwsze pobranie danych. [onResult] woła się
     * w [scope]. Zmiana klucza na klucz innego konta czyści rejestr modułów i pliki poprzedniego konta.
     */
    fun validateAndSave(input: String, onResult: (EmodulSaveResult) -> Unit) {
        if (synchronized(lock) { demo }) return onResult(EmodulSaveResult.Demo)
        if (!settings.noticeAccepted) return onResult(EmodulSaveResult.NoticeRequired)
        val newKey = when (val parsed = EmodulKey.parse(input, clock())) {
            is EmodulKeyResult.Valid -> parsed.key
            EmodulKeyResult.Malformed -> return onResult(EmodulSaveResult.Malformed)
            EmodulKeyResult.Expired -> return onResult(EmodulSaveResult.Expired)
        }
        scope.launch { onResult(save(newKey)) }
    }

    /** Włącza / wyłącza „Pokaż na pulpicie” modułu [slot]; włączony moduł bez danych jest od razu pobierany. */
    fun setModuleEnabled(slot: Int, enabled: Boolean) {
        val changed = synchronized(lock) {
            if (registry.none { it.slot == slot && it.enabled != enabled }) return@synchronized false
            if (!demo && !settings.setEnabled(slot, enabled)) return@synchronized false
            registry = registry.map { if (it.slot == slot) it.copy(enabled = enabled) else it }
            publish()
            true
        }
        if (changed && enabled) scope.launch { cycle(force = false, resumed = false) }
    }

    /**
     * Krok 1 kreatora „Dodaj integrację eModul”: waliduje klucz lokalnie, potem (po zaakceptowaniu komunikatu o
     * prywatności) pobiera listę modułów, dane każdego modułu, ich menu (wartości zadane) i słownik tekstów – **niczego
     * nie zapisuje**. Wynik ([EmodulPreviewResult.Ready]) służy do listy wyboru i do [commit]. W trybie demo klucz jest
     * ignorowany (przykładowy kocioł, bez sieci). [onResult] woła się w [scope].
     */
    fun preview(input: String, onResult: (EmodulPreviewResult) -> Unit) {
        val inDemo = synchronized(lock) { demo }
        val candidate = if (inDemo) {
            DEMO_KEY
        } else {
            if (!settings.noticeAccepted) return onResult(EmodulPreviewResult.NoticeRequired)
            when (val parsed = EmodulKey.parse(input, clock())) {
                is EmodulKeyResult.Valid -> parsed.key
                EmodulKeyResult.Malformed -> return onResult(EmodulPreviewResult.Malformed)
                EmodulKeyResult.Expired -> return onResult(EmodulPreviewResult.Expired)
            }
        }
        scope.launch { onResult(loadPreview(candidate, inDemo)) }
    }

    /**
     * Krok 2 kreatora „Zapisz”: zapisuje klucz (zaszyfrowany), rejestr modułów i wybór [selected] (kafelki z
     * [EmodulPreview.modules], numery slotów tymczasowe), po czym pobiera dane. Zmiana klucza na klucz innego konta czyści
     * dane poprzedniego. W trybie demo zapisuje tylko wybór w pamięci. [onResult] woła się w [scope].
     */
    fun commit(preview: EmodulPreview, selected: Set<TileId.EmodulBase>, onResult: (EmodulSaveResult) -> Unit) {
        scope.launch { onResult(commitNow(preview, selected)) }
    }

    /**
     * Zapisuje wybór danych na pulpicie (ekran „Ustawienia eModul”): [selected] to wybrane kafelki i strefy modułów
     * ze stanu [EmodulState.Ready]; do „znanych” trafiają wszystkie obecne teraz w migawkach (kolejne nowe będą
     * oznaczone jako „nowe”). Moduły bez migawki zostają bez zmian. Nie wysyła żadnych żądań.
     */
    fun setSelection(selected: Set<TileId.EmodulBase>) {
        synchronized(lock) {
            val ready = _state.value as? EmodulState.Ready ?: return
            for (module in ready.modules) {
                if (module.snapshot == null) continue
                val present = EmodulSeeds.candidates(module).mapTo(LinkedHashSet()) { it.id }
                val selection = EmodulSelection(selected.filterTo(LinkedHashSet()) { it in present }, present)
                if (demo) demoSelections[module.slot] = selection else settings.setSelection(module.slot, selection)
            }
            publish()
        }
    }

    /**
     * „Usuń integrację”: kasuje klucz, rejestr modułów, wybór danych i pliki, zatrzymuje odpytywanie (kolejne żądania
     * trwającego odświeżenia nie zostaną wysłane) i przechodzi do [EmodulState.NotConfigured] – kafelki eModul znikają
     * z pulpitu, ruch sieciowy ustaje. W trybie demo czyści tylko wybór w pamięci.
     */
    fun removeKey() {
        synchronized(lock) {
            if (demo) {
                epoch++
                demoConfigured = false
                demoSelections.clear()
                resetData()
                _state.value = EmodulState.NotConfigured
                return
            }
            epoch++
            settings.removeAll()
            store.clear()
            key = null
            registry = emptyList()
            resetData()
            _state.value = EmodulState.NotConfigured
        }
    }

    /** Kończy pracę (zamknięcie aplikacji): żadne dalsze żądania nie zostaną wysłane. */
    fun close() {
        synchronized(lock) { epoch++ }
        loadJob?.cancel()
    }

    // Odświeżanie

    private suspend fun cycle(force: Boolean, resumed: Boolean): Long = cycleMutex.withLock {
        loadJob?.join()
        withContext(ioDispatcher) { runCycle(force, resumed) }
    }

    private fun runCycle(force: Boolean, resumed: Boolean): Long {
        val epochAtStart: Int
        val activeKey: EmodulKey
        val activeApi: EmodulApi
        val lang: String
        synchronized(lock) {
            epochAtStart = epoch
            activeKey = (if (demo) DEMO_KEY.takeIf { demoConfigured } else key) ?: return IDLE_DELAY_MILLIS
            activeApi = if (demo) demoApi else api
            if (halted) return IDLE_DELAY_MILLIS
            val wait = blockedUntil - clock()
            if (wait > 0) return wait.coerceAtMost(IDLE_DELAY_MILLIS)
            lang = normalizedLanguage()
        }
        fun <T> request(call: () -> T): T {
            if (epoch != epochAtStart) throw Superseded()
            return call()
        }
        try {
            // 1. Lista modułów (przy starcie, po zmianie klucza, co 6 h).
            if (EmodulPolicy.needsRefresh(modulesAt, clock(), EmodulPolicy.MODULES_INTERVAL_MILLIS)) {
                val raw = request { activeApi.modules(activeKey) }
                val list = EmodulParser.parseModules(raw)
                applyModules(epochAtStart, list, raw)
            }
            // 2. Moduły włączone na pulpicie (kolejno) i ich menu.
            val moduleMaxAge = if (resumed) EmodulPolicy.RESUME_MAX_AGE_MILLIS else EmodulPolicy.MODULE_INTERVAL_MILLIS
            for (entry in activeSlots()) {
                val known = synchronized(lock) { slotData[entry.slot]?.snapshotAt }
                if (force || EmodulPolicy.needsRefresh(known, clock(), moduleMaxAge)) {
                    val raw = request { activeApi.module(activeKey, entry.udid) }
                    applyModule(epochAtStart, entry.slot, EmodulParser.parseModule(raw), raw)
                }
                refreshMenus(epochAtStart, entry, force) { type -> request { activeApi.menu(activeKey, entry.udid, type) } }
            }
            // 3. Słownik tekstów (rzadko).
            refreshDictionary(epochAtStart, lang) { request { activeApi.i18n(activeKey, lang) } }
            return succeeded(epochAtStart)
        } catch (_: Superseded) {
            return IDLE_DELAY_MILLIS
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return failed(epochAtStart, error)
        }
    }

    private fun activeSlots(): List<EmodulSlot> = synchronized(lock) {
        registry.filter { it.enabled && isPresent(it) }
    }

    private fun refreshMenus(epochAtStart: Int, entry: EmodulSlot, force: Boolean, fetch: (MenuType) -> String) {
        val (needed, menusAt) = synchronized(lock) {
            val data = slotData[entry.slot]
            (data?.snapshot?.tiles?.any { it.menuId > 0 } == true) to data?.menusAt
        }
        if (!needed || !(force || EmodulPolicy.needsRefresh(menusAt, clock(), EmodulPolicy.MENU_INTERVAL_MILLIS))) return
        val raws = MenuType.entries.associateWith { fetch(it) }
        val parsed = raws.mapValues { (type, raw) -> EmodulParser.parseMenu(raw, type) }
        val now = clock()
        synchronized(lock) {
            if (epoch != epochAtStart) throw Superseded()
            val data = slotData.getOrPut(entry.slot) { SlotData() }
            data.menus = parsed
            data.menusAt = now
            if (!demo) raws.forEach { (type, raw) -> store.writeMenu(entry.slot, type, StoredResponse(now, raw)) }
            publish()
        }
    }

    private fun refreshDictionary(epochAtStart: Int, lang: String, fetch: () -> String) {
        val (required, storedAt, sameLanguage, missing) = synchronized(lock) {
            val ids = HashSet<Int>()
            for (entry in registry) {
                if (!entry.enabled || !isPresent(entry)) continue
                val data = slotData[entry.slot] ?: continue
                val snapshot = data.snapshot ?: continue
                ids += EmodulTexts.requiredIds(snapshot, data.menus)
            }
            DictionaryNeed(ids, dictionaryAt, dictionaryLanguage == lang, ids.any { it !in dictionary })
        }
        if (!EmodulPolicy.needsFullI18n(storedAt, sameLanguage, missing, clock())) return
        val subset = EmodulParser.parseI18n(fetch(), required)
        val now = clock()
        synchronized(lock) {
            if (epoch != epochAtStart) throw Superseded()
            dictionary = subset
            dictionaryAt = now
            dictionaryLanguage = lang
            if (!demo) store.writeI18n(lang, now, subset)
            publish()
        }
    }

    private data class DictionaryNeed(val required: Set<Int>, val storedAt: Long?, val sameLanguage: Boolean, val missing: Boolean)

    private fun applyModules(epochAtStart: Int, list: List<EmodulModuleInfo>, raw: String) {
        val now = clock()
        synchronized(lock) {
            if (epoch != epochAtStart) throw Superseded()
            infos = list.associateBy { it.udid }
            modulesAt = now
            registry = if (demo) EmodulSettings.register(registry, list.map { it.udid }) else settings.registerModules(list.map { it.udid })
            if (!demo) store.writeModules(StoredResponse(now, raw))
            publish()
        }
    }

    private fun applyModule(epochAtStart: Int, slot: Int, snapshot: EmodulSnapshot, raw: String) {
        val now = clock()
        synchronized(lock) {
            if (epoch != epochAtStart) throw Superseded()
            val data = slotData.getOrPut(slot) { SlotData() }
            data.snapshot = snapshot
            data.snapshotAt = now
            if (!demo) store.writeModule(slot, StoredResponse(now, raw))
            publish()
        }
    }

    /** Wszystko odświeżone: zeruje błędy i podaje, kiedy najwcześniej coś będzie do zrobienia. */
    private fun succeeded(epochAtStart: Int): Long = synchronized(lock) {
        if (epoch != epochAtStart) return IDLE_DELAY_MILLIS
        failures = 0
        blockedUntil = 0L
        lastError = null
        settled = true
        publish()
        val now = clock()
        var next = EmodulPolicy.delayUntilDue(modulesAt, now, EmodulPolicy.MODULES_INTERVAL_MILLIS)
        for (entry in registry) {
            if (!entry.enabled || !isPresent(entry)) continue
            next = minOf(next, EmodulPolicy.delayUntilDue(slotData[entry.slot]?.snapshotAt, now, EmodulPolicy.MODULE_INTERVAL_MILLIS))
        }
        next.coerceIn(MIN_DELAY_MILLIS, IDLE_DELAY_MILLIS)
    }

    private fun failed(epochAtStart: Int, error: Throwable): Long = synchronized(lock) {
        if (epoch != epochAtStart) return IDLE_DELAY_MILLIS
        val failure = EmodulPolicy.classify(error)
        failures++
        settled = true
        val retry = EmodulPolicy.retryDelayMillis(failure, failures)
        if (retry == null) {
            halted = true
            lastError = null
        } else {
            lastError = failure.toError()
            blockedUntil = clock() + retry
        }
        publish()
        (retry ?: IDLE_DELAY_MILLIS).coerceIn(MIN_DELAY_MILLIS, IDLE_DELAY_MILLIS)
    }

    // Zapis klucza

    private suspend fun save(newKey: EmodulKey): EmodulSaveResult = cycleMutex.withLock {
        loadJob?.join()
        withContext(ioDispatcher) {
            val startEpoch = synchronized(lock) { if (demo) return@withContext EmodulSaveResult.Demo else epoch }
            val raw: String
            val list: List<EmodulModuleInfo>
            try {
                raw = api.modules(newKey)
                list = EmodulParser.parseModules(raw)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                return@withContext when (val failure = EmodulPolicy.classify(error)) {
                    EmodulFailure.KeyRejected -> EmodulSaveResult.Rejected
                    else -> EmodulSaveResult.Failed(failure.toError())
                }
            }
            val failure = persistKey(newKey, startEpoch, list, raw, null)
            if (failure != null) return@withContext failure
            runCycle(force = true, resumed = false)
            EmodulSaveResult.Saved(list.size)
        }
    }

    /**
     * Zapisuje klucz, rejestr i (opcjonalnie) wybór danych po poprawnej odpowiedzi serwera; `null` = zapisano, inaczej
     * powód porażki. [selections] dostaje końcowy rejestr slotów i zwraca wybór per slot.
     */
    private fun persistKey(
        newKey: EmodulKey,
        startEpoch: Int,
        list: List<EmodulModuleInfo>,
        raw: String,
        selections: ((List<EmodulSlot>) -> Map<Int, EmodulSelection>)?,
    ): EmodulSaveResult? = synchronized(lock) {
        if (epoch != startEpoch || demo) return EmodulSaveResult.Failed(EmodulError.NETWORK)
        val previous = key
        if (previous != null && previous.userId != newKey.userId) {
            // Inne konto: rejestr slotów, wybór i pliki poprzedniego konta nie mają sensu.
            settings.removeAll()
            store.clear()
            registry = emptyList()
            resetData()
        }
        try {
            settings.saveKey(newKey)
        } catch (_: SecretStorageException) {
            return EmodulSaveResult.StorageFailed
        }
        epoch++
        key = newKey
        halted = false
        failures = 0
        blockedUntil = 0L
        lastError = null
        infos = list.associateBy { it.udid }
        modulesAt = clock()
        registry = settings.registerModules(list.map { it.udid })
        selections?.invoke(registry)?.forEach { (slot, selection) -> settings.setSelection(slot, selection) }
        store.writeModules(StoredResponse(modulesAt!!, raw))
        _state.value = EmodulState.Loading
        null
    }

    private suspend fun commitNow(preview: EmodulPreview, selected: Set<TileId.EmodulBase>): EmodulSaveResult = cycleMutex.withLock {
        loadJob?.join()
        withContext(ioDispatcher) {
            val (inDemo, startEpoch) = synchronized(lock) { demo to epoch }
            if (inDemo != preview.demo) return@withContext EmodulSaveResult.Failed(EmodulError.NETWORK)
            if (inDemo) {
                synchronized(lock) {
                    if (epoch != startEpoch || !demo) return@withContext EmodulSaveResult.Failed(EmodulError.NETWORK)
                    epoch++
                    resetData()
                    demoConfigured = true
                    demoSelections.clear()
                    registry = EmodulSettings.register(registry, preview.modules.map { it.info.udid })
                    demoSelections += selectionsFor(preview, selected, registry)
                    _state.value = EmodulState.Loading
                }
                runCycle(force = true, resumed = false)
                return@withContext EmodulSaveResult.Saved(preview.modules.size)
            }
            val failure = persistKey(preview.key, startEpoch, preview.modules.map { it.info }, preview.rawModules) { slots ->
                selectionsFor(preview, selected, slots)
            }
            if (failure != null) return@withContext failure
            runCycle(force = true, resumed = false)
            EmodulSaveResult.Saved(preview.modules.size)
        }
    }

    /**
     * Wybór per końcowy slot: [selected] to kafelki z numerami slotów tymczasowymi z [preview]; do „znanych” trafiają
     * wszystkie kafelki obecne w migawce modułu.
     */
    private fun selectionsFor(
        preview: EmodulPreview,
        selected: Set<TileId.EmodulBase>,
        slots: List<EmodulSlot>,
    ): Map<Int, EmodulSelection> {
        val result = HashMap<Int, EmodulSelection>()
        for (module in preview.modules) {
            val slot = slots.firstOrNull { it.udid == module.info.udid }?.slot ?: continue
            val present = EmodulSeeds.candidates(module).map { it.id }
            val chosen = present.filter { it in selected }
            result[slot] = EmodulSelection(chosen.mapTo(LinkedHashSet()) { it.withSlot(slot) }, present.mapTo(LinkedHashSet()) { it.withSlot(slot) })
        }
        return result
    }

    private fun TileId.EmodulBase.withSlot(slot: Int): TileId.EmodulBase = when (this) {
        is TileId.Emodul -> copy(slot = slot)
        is TileId.EmodulZone -> copy(slot = slot)
    }

    private suspend fun loadPreview(candidate: EmodulKey, inDemo: Boolean): EmodulPreviewResult = withContext(ioDispatcher) {
        val startEpoch: Int
        val baseRegistry: List<EmodulSlot>
        val sameAccount: Boolean
        val lang: String
        synchronized(lock) {
            startEpoch = epoch
            sameAccount = inDemo || key?.userId == candidate.userId
            baseRegistry = if (sameAccount) registry else emptyList()
            lang = normalizedLanguage()
        }
        val activeApi = if (inDemo) demoApi else api
        fun <T> request(call: () -> T): T {
            if (epoch != startEpoch) throw Superseded()
            return call()
        }
        try {
            val raw = request { activeApi.modules(candidate) }
            val list = EmodulParser.parseModules(raw)
            val slots = EmodulSettings.register(baseRegistry, list.map { it.udid })
            val loaded = ArrayList<Triple<EmodulModuleInfo, Int, Pair<EmodulSnapshot, Map<MenuType, EmodulMenu>>>>()
            for (info in list) {
                val slot = slots.firstOrNull { it.udid == info.udid }?.slot ?: continue
                val snapshot = EmodulParser.parseModule(request { activeApi.module(candidate, info.udid) })
                val menus = if (snapshot.tiles.any { it.menuId > 0 }) {
                    MenuType.entries.associateWith { type ->
                        EmodulParser.parseMenu(request { activeApi.menu(candidate, info.udid, type) }, type)
                    }
                } else {
                    emptyMap()
                }
                loaded += Triple(info, slot, snapshot to menus)
            }
            // Słownik tekstów (nazwy kafelków na liście wyboru) – tylko potrzebne id, nic nie jest zapisywane.
            val required = loaded.flatMapTo(HashSet()) { (_, _, data) -> EmodulTexts.requiredIds(data.first, data.second) }
            val subset = if (loaded.isEmpty()) emptyMap() else EmodulParser.parseI18n(request { activeApi.i18n(candidate, lang) }, required)
            val texts = EmodulTexts(subset, fallbacks)
            val now = clock()
            val modules = loaded.map { (info, slot, data) ->
                val selection = synchronized(lock) {
                    when {
                        inDemo -> demoSelections[slot]
                        sameAccount -> settings.selection(slot)
                        else -> null
                    } ?: EmodulSelection.EMPTY
                }
                EmodulModuleData(slot, info, true, data.first, data.second, texts, now, selection)
            }
            EmodulPreviewResult.Ready(EmodulPreview(candidate, raw, inDemo, modules))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Superseded) {
            EmodulPreviewResult.Failed(EmodulError.NETWORK)
        } catch (error: Exception) {
            when (val failure = EmodulPolicy.classify(error)) {
                EmodulFailure.KeyRejected -> EmodulPreviewResult.Rejected
                else -> EmodulPreviewResult.Failed(failure.toError())
            }
        }
    }

    // Stan

    /** Odczyt ostatnich odpowiedzi z plików (start): pulpit od razu pokazuje poprzednie dane, oznaczone jako nieaktualne. */
    private fun startLoadingStored() {
        val startEpoch = epoch
        loadJob = scope.launch(ioDispatcher) {
            val modulesFile = store.readModules()
            val slots = synchronized(lock) { registry }
            val files = slots.associate { entry ->
                entry.slot to Triple(
                    store.readModule(entry.slot),
                    MenuType.entries.mapNotNull { type -> store.readMenu(entry.slot, type)?.let { type to it } },
                    entry.udid,
                )
            }
            val stored = store.readI18n(language())
            synchronized(lock) {
                if (epoch != startEpoch || demo) return@launch
                modulesFile?.let { file ->
                    try {
                        infos = EmodulParser.parseModules(file.json).associateBy { it.udid }
                        modulesAt = file.fetchedAtMillis
                    } catch (_: EmodulParseException) {
                        // uszkodzony plik – lista zostanie pobrana z sieci
                    }
                }
                for ((slot, triple) in files) {
                    val (moduleFile, menuFiles, _) = triple
                    val data = SlotData()
                    try {
                        if (moduleFile != null) {
                            data.snapshot = EmodulParser.parseModule(moduleFile.json)
                            data.snapshotAt = moduleFile.fetchedAtMillis
                        }
                        data.menus = menuFiles.associate { (type, file) -> type to EmodulParser.parseMenu(file.json, type) }
                        data.menusAt = menuFiles.minOfOrNull { it.second.fetchedAtMillis }
                            ?.takeIf { menuFiles.size == MenuType.entries.size }
                    } catch (_: EmodulParseException) {
                        slotData.remove(slot)
                        continue
                    }
                    slotData[slot] = data
                }
                stored?.let {
                    dictionary = it.entries
                    dictionaryAt = it.fetchedAtMillis
                    dictionaryLanguage = it.language
                }
                publish()
            }
        }
    }

    /** Czyści dane w pamięci (nie ustawienia, nie pliki). Wołać pod [lock]. */
    private fun resetData() {
        infos = null
        modulesAt = null
        slotData.clear()
        dictionary = emptyMap()
        dictionaryAt = null
        dictionaryLanguage = null
        halted = false
        failures = 0
        blockedUntil = 0L
        lastError = null
        settled = false
    }

    /** Integracja istnieje: jest klucz, a w trybie demo – zapisany kreator. Wołać pod [lock]. */
    private fun configured(): Boolean = if (demo) demoConfigured else key != null

    /** Moduł zniknął z konta, jeśli lista modułów jest znana i nie zawiera jego `udid`. */
    private fun isPresent(entry: EmodulSlot): Boolean = infos?.containsKey(entry.udid) ?: true

    private fun normalizedLanguage(): String =
        language().lowercase(java.util.Locale.ROOT).takeIf { it == "pl" || it == "en" } ?: EmodulUrls.DEFAULT_LANGUAGE

    /** Przelicza [state] z pól modelu. Wołać pod [lock]. */
    private fun publish() {
        val activeKey = configured()
        _state.value = when {
            !activeKey -> EmodulState.NotConfigured
            halted -> EmodulState.KeyRejected
            else -> {
                val texts = EmodulTexts(dictionary, fallbacks)
                val modules = registry.filter(::isPresent).map { entry ->
                    val data = slotData[entry.slot]
                    EmodulModuleData(
                        slot = entry.slot,
                        info = infos?.get(entry.udid) ?: EmodulModuleInfo(entry.udid, "", "", "", online = true),
                        enabled = entry.enabled,
                        snapshot = data?.snapshot,
                        menus = data?.menus.orEmpty(),
                        texts = texts,
                        fetchedAtMillis = data?.snapshotAt,
                        selection = if (demo) demoSelections[entry.slot] ?: EmodulSelection.EMPTY else settings.selection(entry.slot),
                    )
                }
                val hasData = modulesAt != null || modules.any { it.snapshot != null }
                val error = lastError
                when {
                    hasData -> EmodulState.Ready(modules, error)
                    error != null -> EmodulState.Failed(error)
                    else -> EmodulState.Loading
                }
            }
        }
    }

    private fun EmodulFailure.toError(): EmodulError = when (this) {
        EmodulFailure.KeyRejected, EmodulFailure.Network -> EmodulError.NETWORK
        is EmodulFailure.RateLimited -> EmodulError.RATE_LIMITED
        EmodulFailure.TooLarge -> EmodulError.TOO_LARGE
        EmodulFailure.BadResponse -> EmodulError.BAD_RESPONSE
    }

    /** Teksty zastępcze bez zasobów Androida (testy, środowiska bez `Context`): rodzaj i numer. */
    object PlainFallbacks : EmodulFallbacks {
        override fun text(kind: EmodulFallback, number: Int): String =
            if (number != 0) "${kind.name} $number" else kind.name
    }

    companion object {
        /** Odstęp, gdy nic nie czeka albo odpytywanie stoi (pętla budzi się rzadko i niczego wtedy nie wysyła). */
        const val IDLE_DELAY_MILLIS = 60_000L
        private const val MIN_DELAY_MILLIS = 1_000L

        /** Klucz-wypełniacz dla [DemoEmodulApi] (demo go nie sprawdza). */
        private val DEMO_KEY = EmodulKey("demo.demo.demo-demo-demo-demo", 1L)
    }
}
