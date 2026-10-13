@file:OptIn(ExperimentalCoroutinesApi::class)

package app.domik.integrations.emodul

import app.domik.data.DashboardLayout
import app.domik.data.EmodulCatalog
import app.domik.data.FakeCipher
import app.domik.data.InMemoryKeyValueStore
import app.domik.data.TileEntry
import app.domik.data.TileId
import app.domik.data.TileSize
import app.domik.deimic.config.HomeConfig
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** [EmodulModel] na fałszywym API i wirtualnym czasie: stany, polityka odświeżania, klucz, demo. */
class EmodulModelTest {
    @TempDir
    lateinit var dir: File

    private val udid = "0123456789abcdef0123456789abcdef"
    private val token = EmodulFixtures.jwt("""{"username":"ktos","user_id":4242,"is_generated":true}""")
    private val otherToken = EmodulFixtures.jwt("""{"username":"inny","user_id":777,"is_generated":true}""")

    /** Tuż po `tilesLastUpdate` z fixture (2026-10-13 15:37:24Z). */
    private var now = Instant.parse("2026-10-13T15:38:00Z").toEpochMilli()

    private val prefs = InMemoryKeyValueStore()
    private val settings = EmodulSettings(prefs, FakeCipher())
    private val files get() = File(dir, "emodul")

    private class FakeApi : EmodulApi {
        val calls = mutableListOf<String>()
        var modulesJson: String = EmodulFixtures.modulesJson
        var moduleJson: String = EmodulFixtures.moduleJson
        var i18nJson: String = EmodulFixtures.i18nJson
        var failure: Throwable? = null
        var beforeModule: () -> Unit = {}

        private fun call(name: String) {
            calls += name
            failure?.let { throw it }
        }

        override fun modules(key: EmodulKey): String = call("modules").let { modulesJson }

        override fun module(key: EmodulKey, udid: String): String {
            beforeModule()
            call("module")
            return moduleJson
        }

        override fun menu(key: EmodulKey, udid: String, type: MenuType): String = call("menu:${type.name}").let {
            if (type == MenuType.MU) EmodulFixtures.menuUserJson else EmodulFixtures.menuInstallerJson
        }

        override fun i18n(key: EmodulKey, language: String): String = call("i18n:$language").let { i18nJson }

        fun count(prefix: String) = calls.count { it.startsWith(prefix) }
    }

    private fun TestScope.newModel(
        api: EmodulApi,
        language: String = "pl",
        cipherSettings: EmodulSettings = settings,
    ): EmodulModel {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return EmodulModel(
            api, cipherSettings, EmodulStore(files), backgroundScope, dispatcher, clock = { now }, language = { language },
            fallbacks = EmodulFixtures.TestFallbacks,
        ).also { runCurrent() }
    }

    private fun TestScope.save(model: EmodulModel, input: String = token): EmodulSaveResult {
        var result: EmodulSaveResult? = null
        model.validateAndSave(input) { result = it }
        runCurrent()
        return checkNotNull(result)
    }

    private fun TestScope.ready(model: EmodulModel): EmodulState.Ready = model.state.value as EmodulState.Ready

    private fun TestScope.preview(model: EmodulModel, input: String = token): EmodulPreviewResult {
        var result: EmodulPreviewResult? = null
        model.preview(input) { result = it }
        runCurrent()
        return checkNotNull(result)
    }

    private fun TestScope.readyPreview(model: EmodulModel, input: String = token): EmodulPreview =
        (preview(model, input) as EmodulPreviewResult.Ready).preview

    private fun TestScope.commit(model: EmodulModel, preview: EmodulPreview, selected: Set<TileId.EmodulBase>): EmodulSaveResult {
        var result: EmodulSaveResult? = null
        model.commit(preview, selected) { result = it }
        runCurrent()
        return checkNotNull(result)
    }

    /** Kreator w trybie demo do końca: domyślne zaznaczenie. */
    private fun TestScope.setupDemo(model: EmodulModel) {
        val preview = readyPreview(model, "")
        assertEquals(EmodulSaveResult.Saved(1), commit(model, preview, EmodulSeeds.defaultSelection(preview.modules.single())))
    }

    /** Wszystkie kafelki modułu z podglądu. */
    private fun allTiles(preview: EmodulPreview): Set<TileId.EmodulBase> =
        preview.modules.flatMapTo(LinkedHashSet()) { module -> EmodulSeeds.candidates(module).map { it.id } }

    @Test
    fun `bez klucza stan to NotConfigured a odswiezanie nic nie wysyla`() = runTest {
        val api = FakeApi()
        val model = newModel(api)

        assertEquals(EmodulState.NotConfigured, model.state.value)
        assertEquals(EmodulModel.IDLE_DELAY_MILLIS, model.refreshDue())
        model.refreshNow()
        runCurrent()

        assertTrue(api.calls.isEmpty())
        assertEquals(EmodulState.NotConfigured, model.state.value)
    }

    @Test
    fun `zapis klucza przechodzi przez Loading do Ready i pobiera wszystko raz`() = runTest {
        val api = FakeApi()
        val model = newModel(api)
        settings.acceptNotice()
        val seen = mutableListOf<EmodulState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect { seen += it } }

        val result = save(model)

        assertEquals(EmodulSaveResult.Saved(1), result)
        assertEquals(listOf(EmodulState.NotConfigured, EmodulState.Loading), seen.take(2))
        assertTrue(seen.last() is EmodulState.Ready)
        assertEquals(listOf("modules", "module", "menu:MU", "menu:MI", "i18n:pl"), api.calls)
        val module = ready(model).modules.single()
        assertEquals(1, module.slot)
        assertEquals("Kocioł testowy", module.info.name)
        assertTrue(module.enabled)
        assertEquals(EmodulFixtures.snapshot.tiles.size, module.snapshot!!.tiles.size)
        assertEquals(setOf(MenuType.MU, MenuType.MI), module.menus.keys)
        assertEquals(now, module.fetchedAtMillis)
        assertNull(ready(model).error)
        // pliki zapisane, a klucz leży tylko zaszyfrowany
        assertTrue(File(files, "module_1.json").isFile)
        assertTrue(File(files, "modules.json").isFile)
        assertEquals(4242L, settings.key()!!.userId)
        assertFalse(prefs.values.values.any { it.toString().contains(token) })
        assertEquals(token.takeLast(4), model.maskedKey()!!.removePrefix("…"))
    }

    @Test
    fun `zapis klucza wymaga zaakceptowanego komunikatu i nic nie wysyla bez niego`() = runTest {
        val api = FakeApi()
        val model = newModel(api)

        assertEquals(EmodulSaveResult.NoticeRequired, save(model))
        assertTrue(api.calls.isEmpty())

        model.acceptNotice()
        assertTrue(model.noticeAccepted)
        assertEquals(EmodulSaveResult.Saved(1), save(model))
    }

    @Test
    fun `zly albo przeterminowany klucz jest odrzucany lokalnie bez sieci`() = runTest {
        val api = FakeApi()
        val model = newModel(api)
        settings.acceptNotice()

        assertEquals(EmodulSaveResult.Malformed, save(model, "nie-klucz"))
        assertEquals(EmodulSaveResult.Expired, save(model, EmodulFixtures.jwt("""{"user_id":5,"exp":1000}""")))

        assertTrue(api.calls.isEmpty())
        assertNull(settings.key())
    }

    @Test
    fun `klucz odrzucony przez serwer albo siec nie zostaje zapisany`() = runTest {
        val api = FakeApi()
        val model = newModel(api)
        settings.acceptNotice()

        api.failure = EmodulHttpException(401)
        assertEquals(EmodulSaveResult.Rejected, save(model))
        api.failure = IOException("brak sieci")
        assertEquals(EmodulSaveResult.Failed(EmodulError.NETWORK), save(model))
        api.failure = null
        api.modulesJson = "to nie json"
        assertEquals(EmodulSaveResult.Failed(EmodulError.BAD_RESPONSE), save(model))

        assertNull(settings.key())
        assertEquals(EmodulState.NotConfigured, model.state.value)
        assertFalse(files.exists())
    }

    @Test
    fun `401 przy odswiezaniu daje KeyRejected i zatrzymuje odpytywanie do Sprobuj ponownie`() = runTest {
        val api = FakeApi()
        val model = newModel(api)
        settings.acceptNotice()
        save(model)
        api.calls.clear()

        now += 61_000
        api.failure = EmodulHttpException(403)
        model.refreshDue()
        assertEquals(EmodulState.KeyRejected, model.state.value)
        assertEquals(1, api.calls.size)

        now += 10 * 60_000L
        model.refreshDue()
        model.refreshDue(resumed = true)
        assertEquals(1, api.calls.size)

        api.failure = null
        model.refreshNow()
        runCurrent()
        assertTrue(model.state.value is EmodulState.Ready)
        assertTrue(api.count("module") >= 2)
    }

    @Test
    fun `odrzucony klucz nie kasuje kafelkow eModul z zapisanego ukladu - tylko usuniecie klucza`() = runTest {
        val api = FakeApi()
        val model = newModel(api)
        settings.acceptNotice()
        save(model)
        val config = HomeConfig(
            levels = emptyList(), rooms = emptyList(), modules = listOf(1), outputs = emptyList(),
            heatingZones = emptyList(), shutters = emptyList(),
        )
        val tile = TileId.Emodul(1, 1006)
        val saved = DashboardLayout(listOf(TileEntry(TileId.Weather, TileSize.M), TileEntry(tile, TileSize.M)), emptyList())
        fun ids(catalog: EmodulCatalog?) = DashboardLayout.reconcile(saved, config, 6, emodul = catalog).tiles.map { it.id }

        now += 61_000
        api.failure = EmodulHttpException(401)
        model.refreshDue()
        assertEquals(EmodulState.KeyRejected, model.state.value)
        assertNull(model.catalog())
        assertTrue(tile in ids(model.catalog()))

        // Po usunięciu klucza (jawna decyzja użytkownika) kafelki znikają.
        model.removeKey()
        assertEquals(EmodulCatalog(emptyMap()), model.catalog())
        assertFalse(tile in ids(model.catalog()))
    }

    @Test
    fun `dane z plikow sa widoczne od razu przy starcie a siec dopiero przy odswiezeniu`() = runTest {
        val api = FakeApi()
        newModel(api).also { settings.acceptNotice(); save(it) }
        now += 3_600_000L
        val calls = api.calls.size

        val restarted = newModel(api)

        val state = restarted.state.value as EmodulState.Ready
        assertEquals("Kocioł testowy", state.modules.single().info.name)
        assertNotNull(state.modules.single().snapshot)
        assertEquals(setOf(MenuType.MU, MenuType.MI), state.modules.single().menus.keys)
        assertEquals(now - 3_600_000L, state.modules.single().fetchedAtMillis)
        assertNotNull(state.modules.single().texts.text(TxtId(1039)))
        assertEquals(calls, api.calls.size)

        restarted.refreshDue()
        assertTrue(api.calls.size > calls)
    }

    @Test
    fun `uszkodzone pliki nie przeszkadzaja a stan to Loading gdy plikow brak`() = runTest {
        settings.saveKey((EmodulKey.parse(token, 0L) as EmodulKeyResult.Valid).key)
        val model = newModel(FakeApi())
        assertEquals(EmodulState.Loading, model.state.value)

        settings.registerModules(listOf(udid))
        files.mkdirs()
        File(files, "module_1.json").writeText("v1 1\n{nie json")
        File(files, "modules.json").writeText("smieci")
        val restarted = newModel(FakeApi())
        assertEquals(EmodulState.Loading, restarted.state.value)
    }

    @Test
    fun `modul jest odswiezany co 60 sekund a menu co 15 minut`() = runTest {
        val api = FakeApi()
        val model = newModel(api)
        settings.acceptNotice()
        save(model)
        api.calls.clear()

        now += 30_000
        assertTrue(model.refreshDue() in 1_000L..60_000L)
        assertTrue(api.calls.isEmpty())

        now += 31_000
        model.refreshDue()
        assertEquals(listOf("module"), api.calls)

        now += 61_000
        model.refreshDue()
        assertEquals(2, api.count("module"))
        assertEquals(0, api.count("menu"))

        now += 15 * 60_000L
        model.refreshDue()
        assertEquals(1, api.count("menu:MU"))
        assertEquals(1, api.count("menu:MI"))
    }

    @Test
    fun `po powrocie na pierwszy plan modul starszy niz 30 sekund jest odswiezany od razu`() = runTest {
        val api = FakeApi()
        val model = newModel(api)
        settings.acceptNotice()
        save(model)
        api.calls.clear()

        now += 20_000
        model.refreshDue(resumed = true)
        assertTrue(api.calls.isEmpty())
        now += 15_000
        model.refreshDue(resumed = true)
        assertEquals(listOf("module"), api.calls)
    }

    @Test
    fun `lista modulow jest pobierana co 6 godzin`() = runTest {
        val api = FakeApi()
        val model = newModel(api)
        settings.acceptNotice()
        save(model)

        now += 5 * 3_600_000L
        model.refreshDue()
        assertEquals(1, api.count("modules"))
        now += 3_600_000L + 1
        model.refreshDue()
        assertEquals(2, api.count("modules"))
    }

    @Test
    fun `slownik jest pobierany raz a ponownie dopiero po dobie gdy brakuje identyfikatorow`() = runTest {
        val api = FakeApi().apply { i18nJson = """{"status":"success","data":{"1":"x"}}""" }
        val model = newModel(api)
        settings.acceptNotice()
        save(model)
        assertEquals(1, api.count("i18n"))

        now += 3_600_000L
        model.refreshDue()
        assertEquals(1, api.count("i18n"))

        now += 24 * 3_600_000L
        model.refreshDue()
        assertEquals(2, api.count("i18n"))
    }

    @Test
    fun `kompletny slownik nie jest pobierany ponownie a zmiana jezyka go odswieza`() = runTest {
        val api = FakeApi()
        var language = "pl"
        val dispatcher = StandardTestDispatcher(testScheduler)
        val model = EmodulModel(
            api, settings, EmodulStore(files), backgroundScope, dispatcher, clock = { now }, language = { language },
            fallbacks = EmodulFixtures.TestFallbacks,
        )
        settings.acceptNotice()
        save(model)
        now += 2 * 24 * 3_600_000L
        model.refreshDue()
        assertEquals(1, api.count("i18n"))

        language = "en"
        model.refreshDue()
        assertEquals(listOf("i18n:pl", "i18n:en"), api.calls.filter { it.startsWith("i18n") })
    }

    @Test
    fun `slownik jest ograniczony do potrzebnych identyfikatorow i trafia do modulu`() = runTest {
        val api = FakeApi()
        val model = newModel(api)
        settings.acceptNotice()
        save(model)

        val module = ready(model).modules.single()
        assertEquals("Zawór wbudowany", EmodulLinks.valveName(EmodulFixtures.tile(1012), module.menus, module.texts))
        assertTrue(module.texts.size < EmodulParser.parseI18n(EmodulFixtures.i18nJson).size + 1)
        assertEquals(module.texts.size, EmodulStore(files).readI18n("pl")!!.entries.size)
    }

    @Test
    fun `blad sieci bez danych to Failed a z danymi to Ready z bledem i backoff 60 120 s`() = runTest {
        val api = FakeApi()
        settings.saveKey((EmodulKey.parse(token, 0L) as EmodulKeyResult.Valid).key)
        val model = newModel(api)
        api.failure = IOException("brak sieci")

        val firstDelay = model.refreshDue()
        assertEquals(EmodulState.Failed(EmodulError.NETWORK), model.state.value)
        assertEquals(1, api.calls.size)
        assertEquals(EmodulModel.IDLE_DELAY_MILLIS, firstDelay)

        now += 59_000
        model.refreshDue()
        assertEquals(1, api.calls.size)
        now += 2_000
        model.refreshDue()
        assertEquals(2, api.calls.size)
        // drugi błąd: odstęp 120 s
        now += 119_000
        model.refreshDue()
        assertEquals(2, api.calls.size)
        now += 2_000
        api.failure = null
        model.refreshDue()
        assertTrue(model.state.value is EmodulState.Ready)

        api.failure = IOException("znowu")
        now += 61_000
        model.refreshDue()
        val ready = model.state.value as EmodulState.Ready
        assertEquals(EmodulError.NETWORK, ready.error)
        assertNotNull(ready.modules.single().snapshot)
    }

    @Test
    fun `429 z Retry-After wstrzymuje odczyty a bez niego na 15 minut`() = runTest {
        val api = FakeApi()
        val model = newModel(api)
        settings.acceptNotice()
        save(model)
        api.calls.clear()

        now += 61_000
        api.failure = EmodulHttpException(429, 120)
        model.refreshDue()
        assertEquals((model.state.value as EmodulState.Ready).error, EmodulError.RATE_LIMITED)
        now += 119_000
        model.refreshDue()
        assertEquals(1, api.calls.size)
        now += 2_000
        api.failure = EmodulHttpException(429, null)
        model.refreshDue()
        assertEquals(2, api.calls.size)
        now += 14 * 60_000L
        model.refreshDue()
        assertEquals(2, api.calls.size)
        now += 61_000
        model.refreshDue()
        assertEquals(3, api.calls.size)
    }

    @Test
    fun `za duza albo niepoprawna odpowiedz to odpowiednie bledy`() = runTest {
        val api = FakeApi()
        val model = newModel(api)
        settings.acceptNotice()
        save(model)

        now += 61_000
        api.failure = EmodulResponseTooLargeException()
        model.refreshDue()
        assertEquals(EmodulError.TOO_LARGE, (model.state.value as EmodulState.Ready).error)

        now += 16 * 60_000L
        api.failure = null
        api.moduleJson = "[]"
        model.refreshDue()
        assertEquals(EmodulError.BAD_RESPONSE, (model.state.value as EmodulState.Ready).error)
    }

    @Test
    fun `usuniecie klucza czysci wszystko i zatrzymuje ruch sieciowy`() = runTest {
        val api = FakeApi()
        val model = newModel(api)
        settings.acceptNotice()
        save(model)
        api.calls.clear()

        model.removeKey()

        assertEquals(EmodulState.NotConfigured, model.state.value)
        assertNull(settings.key())
        assertTrue(settings.slots().isEmpty())
        assertTrue(settings.noticeAccepted)
        assertTrue(files.listFiles().isNullOrEmpty())
        now += 3_600_000L
        model.refreshDue()
        model.refreshNow()
        runCurrent()
        assertTrue(api.calls.isEmpty())
        assertNull(model.maskedKey())
        assertEquals(EmodulState.NotConfigured, model.state.value)
    }

    @Test
    fun `usuniecie klucza w trakcie odswiezania odrzuca wynik i nie wysyla kolejnych zadan`() = runTest {
        val api = FakeApi()
        val model = newModel(api)
        settings.acceptNotice()
        save(model)
        api.calls.clear()
        now += 61_000
        api.beforeModule = { model.removeKey() }

        model.refreshDue()

        assertEquals(EmodulState.NotConfigured, model.state.value)
        // żądanie w toku zdążyło wyjść, ale nic poza nim (menu, słownik) i jego wynik nie jest zapisany
        assertEquals(listOf("module"), api.calls)
        assertTrue(files.listFiles().isNullOrEmpty())
        assertNull(settings.key())
    }

    @Test
    fun `klucz innego konta czysci rejestr i dane poprzedniego`() = runTest {
        val api = FakeApi()
        val model = newModel(api)
        settings.acceptNotice()
        save(model)
        settings.setEnabled(1, false)

        api.modulesJson = EmodulFixtures.modulesJson.replace(udid, "fedcba9876543210fedcba9876543210")
        assertEquals(EmodulSaveResult.Saved(1), save(model, otherToken))

        assertEquals(777L, settings.key()!!.userId)
        val slots = settings.slots()
        assertEquals(listOf(EmodulSlot(1, "fedcba9876543210fedcba9876543210", true)), slots)
        assertEquals(1, ready(model).modules.size)
    }

    @Test
    fun `ten sam klucz zapisany ponownie zachowuje sloty i wylaczenia`() = runTest {
        val api = FakeApi()
        val model = newModel(api)
        settings.acceptNotice()
        save(model)
        model.setModuleEnabled(1, false)

        save(model, EmodulFixtures.jwt("""{"username":"ktos","user_id":4242,"iat":2}"""))

        assertEquals(listOf(false), settings.slots().map { it.enabled })
    }

    @Test
    fun `wylaczony modul nie jest pobierany a wlaczony dostaje dane`() = runTest {
        val api = FakeApi()
        val model = newModel(api)
        settings.acceptNotice()
        save(model)
        api.calls.clear()

        model.setModuleEnabled(1, false)
        assertFalse(ready(model).modules.single().enabled)
        now += 61_000
        model.refreshDue()
        assertEquals(0, api.count("module"))

        model.setModuleEnabled(1, true)
        runCurrent()
        assertTrue(ready(model).modules.single().enabled)
        assertEquals(1, api.count("module"))
        assertEquals(listOf(true), EmodulSettings(prefs, FakeCipher()).slots().map { it.enabled })
    }

    @Test
    fun `modul ktory zniknal z konta nie jest w stanie`() = runTest {
        val api = FakeApi()
        val model = newModel(api)
        settings.acceptNotice()
        save(model)

        api.modulesJson = "[]"
        now += 6 * 3_600_000L + 1
        model.refreshDue()

        assertTrue(ready(model).modules.isEmpty())
        assertEquals(1, settings.slots().size)
    }

    @Test
    fun `tryb demo pokazuje przykladowy kociol bez zadnego ruchu prawdziwego API`() = runTest {
        val api = FakeApi()
        val model = newModel(api)

        model.setDemo(true)
        runCurrent()

        // Demo startuje bez integracji: kreator dopiero ją tworzy (bez klucza i bez sieci).
        assertTrue(api.calls.isEmpty())
        assertTrue(model.isDemo)
        assertEquals(EmodulState.NotConfigured, model.state.value)
        setupDemo(model)

        val module = ready(model).modules.single()
        assertEquals(1, module.slot)
        assertEquals("Kocioł na pellet (demo)", module.info.name)
        assertEquals(18, module.snapshot!!.tiles.size)
        assertEquals(setOf(MenuType.MU, MenuType.MI), module.menus.keys)
        assertEquals("Stan sterownika", module.texts.text(TxtId(9001)))
        assertFalse(files.exists())
        assertNull(model.maskedKey())

        now += 61_000
        model.refreshDue()
        model.refreshNow()
        runCurrent()
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `w trybie demo nie ma zapisu klucza ani usuwania a wyjscie wraca do prawdziwego stanu`() = runTest {
        val api = FakeApi()
        val model = newModel(api)
        model.setDemo(true)
        runCurrent()

        assertEquals(EmodulSaveResult.Demo, save(model))
        assertEquals(EmodulState.NotConfigured, model.state.value)
        setupDemo(model)
        assertTrue(model.state.value is EmodulState.Ready)
        model.removeKey()
        assertEquals(EmodulState.NotConfigured, model.state.value)
        assertEquals(EmodulCatalog(emptyMap()), model.catalog())

        model.setDemo(false)
        runCurrent()
        assertEquals(EmodulState.NotConfigured, model.state.value)
        assertFalse(model.isDemo)
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `wyjscie z demo przywraca zapisane dane prawdziwego klucza`() = runTest {
        val api = FakeApi()
        val model = newModel(api)
        settings.acceptNotice()
        save(model)
        val calls = api.calls.size

        model.setDemo(true)
        runCurrent()
        assertEquals(EmodulState.NotConfigured, model.state.value)
        setupDemo(model)
        assertEquals("Kocioł na pellet (demo)", ready(model).modules.single().info.name)
        model.setDemo(false)
        runCurrent()

        assertEquals("Kocioł testowy", ready(model).modules.single().info.name)
        assertNotNull(ready(model).modules.single().snapshot)
        assertEquals(calls, api.calls.size)
        assertEquals(4242L, settings.key()!!.userId)
    }

    // Aktualność danych i pasek uwagi

    @Test
    fun `swieze dane nie wywoluja paska uwagi a czas danych to tilesLastUpdate sterownika`() = runTest {
        val model = newModel(FakeApi())
        settings.acceptNotice()
        save(model)

        val module = ready(model).modules.single()
        assertEquals(Instant.parse("2026-10-13T15:37:24.733345Z"), module.dataTime)
        assertEquals(EmodulStaleness.Fresh, module.staleness(now))
        assertEquals(EmodulAttention.None, model.attention(setOf(1)))
    }

    @Test
    fun `cisza sterownika po 15 minutach od tilesLastUpdate daje pasek ControllerSilent z czasem danych`() = runTest {
        val model = newModel(FakeApi())
        settings.acceptNotice()
        save(model)

        // chmura odpowiada (nowy odczyt), ale tilesLastUpdate w odpowiedzi stoi w miejscu
        now += 16 * 60_000L
        model.refreshDue()

        val silent = EmodulAttention.ControllerSilent(Instant.parse("2026-10-13T15:37:24.733345Z"))
        assertEquals(silent, model.attention(setOf(1)))
        assertEquals(EmodulStaleness.ControllerSilent(silent.since), ready(model).modules.single().staleness(now))
    }

    @Test
    fun `modul offline daje ControllerSilent`() = runTest {
        val api = FakeApi().apply { modulesJson = EmodulFixtures.modulesJson.replace("\"active\"", "\"inactive\"") }
        val model = newModel(api)
        settings.acceptNotice()
        save(model)

        assertFalse(ready(model).modules.single().info.online)
        assertTrue(model.attention(setOf(1)) is EmodulAttention.ControllerSilent)
    }

    @Test
    fun `brak udanego odczytu od 15 minut daje CloudUnreachable z czasem ostatniego odczytu`() = runTest {
        val api = FakeApi()
        val model = newModel(api)
        settings.acceptNotice()
        save(model)
        val fetchedAt = now

        now += 16 * 60_000L
        api.failure = IOException("brak sieci")
        model.refreshDue()

        assertEquals(EmodulAttention.CloudUnreachable(Instant.ofEpochMilli(fetchedAt)), model.attention(setOf(1)))
        // ma pierwszeństwo przed ciszą sterownika, bo dane tilesLastUpdate też są stare
        assertTrue(ready(model).modules.single().staleness(now) is EmodulStaleness.CloudUnreachable)

        // powrót sieci: pasek znika po świeżym odczycie
        api.failure = null
        model.refreshNow()
        runCurrent()
        assertTrue(model.attention(setOf(1)) is EmodulAttention.ControllerSilent)
    }

    @Test
    fun `pasek uwagi liczy tylko moduly z widocznymi kafelkami`() = runTest {
        val model = newModel(FakeApi())
        settings.acceptNotice()
        save(model)
        now += 16 * 60_000L
        model.refreshDue()

        assertTrue(model.attention(setOf(1)) is EmodulAttention.ControllerSilent)
        assertEquals(EmodulAttention.None, model.attention(emptySet()))
        assertEquals(EmodulAttention.None, model.attention(setOf(2)))
    }

    @Test
    fun `po starcie z plikow starszych niz 15 minut pasek czeka na pierwsza probe odswiezenia`() = runTest {
        val api = FakeApi()
        newModel(api).also { settings.acceptNotice(); save(it) }
        now += 3_600_000L

        val restarted = newModel(api)
        assertEquals(EmodulStaleness.CloudUnreachable(Instant.ofEpochMilli(now - 3_600_000L)), ready(restarted).modules.single().staleness(now))
        assertEquals(EmodulAttention.None, restarted.attention(setOf(1)))

        api.failure = IOException("brak sieci")
        restarted.refreshDue()
        assertTrue(restarted.attention(setOf(1)) is EmodulAttention.CloudUnreachable)
    }

    @Test
    fun `odrzucony klucz to osobny pasek bez wzgledu na widoczne kafelki`() = runTest {
        val api = FakeApi()
        val model = newModel(api)
        settings.acceptNotice()
        save(model)

        now += 61_000
        api.failure = EmodulHttpException(401)
        model.refreshDue()

        assertEquals(EmodulAttention.KeyRejected, model.attention(setOf(1)))
        assertEquals(EmodulAttention.KeyRejected, model.attention(emptySet()))
    }

    @Test
    fun `w trybie demo pasek uwagi nigdy sie nie pojawia`() = runTest {
        val model = newModel(FakeApi())
        model.setDemo(true)
        runCurrent()
        setupDemo(model)

        assertEquals(EmodulAttention.None, model.attention(setOf(1)))
        assertEquals(EmodulAttention.None, model.attention(setOf(1), now + 10 * 3_600_000L))
        assertEquals(EmodulStaleness.Fresh, ready(model).modules.single().staleness(now))
    }

    @Test
    fun `po usunieciu klucza pasek znika`() = runTest {
        val model = newModel(FakeApi())
        settings.acceptNotice()
        save(model)
        now += 16 * 60_000L
        model.refreshDue()
        assertTrue(model.attention(setOf(1)) !is EmodulAttention.None)

        model.removeKey()

        assertEquals(EmodulAttention.None, model.attention(setOf(1)))
    }

    // Kreator „Dodaj integrację eModul” i wybór danych (§6.4a)

    private fun wizardKeys() = prefs.values.keys.filter { it.startsWith("emodul.") && it != "emodul.notice" }

    @Test
    fun `kreator - Dalej pobiera moduly, dane, menu i slownik, ale niczego nie zapisuje`() = runTest {
        val api = FakeApi()
        val model = newModel(api)
        settings.acceptNotice()

        val preview = readyPreview(model)

        assertEquals(listOf("modules", "module", "menu:MU", "menu:MI", "i18n:pl"), api.calls)
        val module = preview.modules.single()
        assertEquals("Kocioł testowy", module.info.name)
        assertEquals(EmodulFixtures.snapshot.tiles.size, module.snapshot!!.tiles.size)
        assertEquals("Kocioł testowy", module.info.name)
        // nic nie zostaje: ani klucz, ani rejestr, ani pliki, ani stan
        assertNull(settings.key())
        assertTrue(settings.slots().isEmpty())
        assertTrue(wizardKeys().isEmpty(), wizardKeys().toString())
        assertFalse(files.exists())
        assertEquals(EmodulState.NotConfigured, model.state.value)
        assertEquals(EmodulCatalog(emptyMap()), model.catalog())
        assertNull(model.maskedKey())
        assertFalse(preview.toString().contains(token))
    }

    @Test
    fun `kreator - Anuluj po obejrzeniu listy nie zostawia niczego i nie powoduje ruchu sieciowego`() = runTest {
        val api = FakeApi()
        val model = newModel(api)
        settings.acceptNotice()
        readyPreview(model)
        api.calls.clear()

        // użytkownik anuluje: porzuca podgląd, nie wołając commit
        now += 10 * 60_000L
        assertEquals(EmodulModel.IDLE_DELAY_MILLIS, model.refreshDue())
        model.refreshNow()
        runCurrent()

        assertTrue(api.calls.isEmpty())
        assertTrue(wizardKeys().isEmpty())
        assertNull(settings.key())
        assertEquals(EmodulState.NotConfigured, model.state.value)
    }

    @Test
    fun `kreator - bez zaakceptowanego komunikatu i ze zlym kluczem lokalnie nic nie jest wysylane`() = runTest {
        val api = FakeApi()
        val model = newModel(api)

        assertEquals(EmodulPreviewResult.NoticeRequired, preview(model))
        settings.acceptNotice()
        assertEquals(EmodulPreviewResult.Malformed, preview(model, "to-nie-jest-klucz"))
        val expired = EmodulFixtures.jwt("""{"username":"x","user_id":4242,"exp":1000}""")
        assertEquals(EmodulPreviewResult.Expired, preview(model, expired))

        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `kreator - klucz wklejony w kilku wierszach dziala, a niepelny klucz nie wysyla zadan`() = runTest {
        val api = FakeApi()
        val model = newModel(api)
        settings.acceptNotice()

        assertEquals(EmodulPreviewResult.Malformed, preview(model, token.take(token.length / 2)))
        assertEquals(EmodulPreviewResult.Malformed, preview(model, "\n"))
        assertTrue(api.calls.isEmpty())

        val wrapped = token.chunked(30).joinToString("\n") + "\n"
        assertTrue(preview(model, wrapped) is EmodulPreviewResult.Ready)
    }

    @Test
    fun `kreator - zapisany wybor to dokladnie przekazany zbior, takze po odznaczeniu wstepnie zaznaczonego`() = runTest {
        val api = FakeApi()
        val model = newModel(api)
        settings.acceptNotice()
        val preview = readyPreview(model)
        val defaults = EmodulSeeds.defaultSelection(preview.modules.single())
        val unchecked = defaults.first()
        val chosen = defaults - unchecked

        assertEquals(EmodulSaveResult.Saved(1), commit(model, preview, chosen))

        val selected = settings.selection(1).selected
        assertEquals(chosen.map { it.number }.toSet(), selected.map { it.number }.toSet())
        assertFalse(selected.any { it.number == unchecked.number })
    }

    @Test
    fun `kreator - klucz odrzucony przez serwer i brak polaczenia zostawiaja modal bez zapisu`() = runTest {
        val api = FakeApi()
        val model = newModel(api)
        settings.acceptNotice()

        api.failure = EmodulHttpException(401)
        assertEquals(EmodulPreviewResult.Rejected, preview(model))
        api.failure = IOException("brak sieci")
        assertEquals(EmodulPreviewResult.Failed(EmodulError.NETWORK), preview(model))

        assertTrue(wizardKeys().isEmpty())
        assertNull(settings.key())
        assertEquals(EmodulState.NotConfigured, model.state.value)
    }

    @Test
    fun `kreator - Zapisz zapisuje zaszyfrowany klucz i wybor, a katalog zawiera tylko wybrane kafelki`() = runTest {
        val api = FakeApi()
        val model = newModel(api)
        settings.acceptNotice()
        val preview = readyPreview(model)
        val all = EmodulSeeds.candidates(preview.modules.single()).map { it.id }
        val chosen = all.take(2).toSet()

        assertEquals(EmodulSaveResult.Saved(1), commit(model, preview, chosen))

        assertEquals(4242L, settings.key()!!.userId)
        assertFalse(prefs.values.values.any { it.toString().contains(token) })
        val selection = settings.selection(1)
        assertEquals(chosen, selection.selected)
        assertEquals(all.toSet(), selection.known)
        val catalog = model.catalog()!!
        assertEquals(chosen, catalog.slots.getValue(1)!!.map { it.id }.toSet())
        assertEquals(chosen, ready(model).modules.single().selection.selected)
    }

    @Test
    fun `wybor - ustawienia zapisuja nowy wybor bez ruchu sieciowego, odznaczone znikaja z katalogu`() = runTest {
        val api = FakeApi()
        val model = newModel(api)
        settings.acceptNotice()
        val preview = readyPreview(model)
        val all = EmodulSeeds.candidates(preview.modules.single()).map { it.id }
        commit(model, preview, all.toSet())
        api.calls.clear()

        val smaller = setOf(all[0], all[3])
        model.setSelection(smaller + TileId.Emodul(1, 424242)) // kafelek spoza migawki jest pomijany

        assertTrue(api.calls.isEmpty())
        assertEquals(smaller, settings.selection(1).selected)
        assertEquals(all.toSet(), settings.selection(1).known)
        assertEquals(smaller, model.catalog()!!.slots.getValue(1)!!.map { it.id }.toSet())
    }

    @Test
    fun `kreator - zmiana klucza tego samego konta pokazuje zapisany wybor, innego konta - czysta liste`() = runTest {
        val api = FakeApi()
        val model = newModel(api)
        settings.acceptNotice()
        val first = readyPreview(model)
        val chosen = EmodulSeeds.candidates(first.modules.single()).map { it.id }.take(3).toSet()
        commit(model, first, chosen)

        val sameAccount = readyPreview(model, EmodulFixtures.jwt("""{"username":"ktos","user_id":4242,"iat":2}"""))
        assertEquals(chosen, sameAccount.modules.single().selection.selected)

        val otherAccount = readyPreview(model, otherToken)
        assertEquals(EmodulSelection.EMPTY, otherAccount.modules.single().selection)
        // sam podgląd nie rusza zapisanego wyboru
        assertEquals(chosen, settings.selection(1).selected)
    }

    @Test
    fun `kreator - zapis klucza innego konta czysci wybor poprzedniego`() = runTest {
        val api = FakeApi()
        val model = newModel(api)
        settings.acceptNotice()
        val first = readyPreview(model)
        commit(model, first, allTiles(first))

        val other = readyPreview(model, otherToken)
        val one = EmodulSeeds.candidates(other.modules.single()).first().id
        commit(model, other, setOf(one))

        assertEquals(777L, settings.key()!!.userId)
        assertEquals(setOf(one), settings.selection(1).selected)
    }

    @Test
    fun `usuniecie integracji kasuje klucz, wybor i pliki, a potem nie ma zadnego ruchu sieciowego`() = runTest {
        val api = FakeApi()
        val model = newModel(api)
        settings.acceptNotice()
        val preview = readyPreview(model)
        commit(model, preview, allTiles(preview))
        assertTrue(wizardKeys().any { it.startsWith("emodul.slot.") })
        api.calls.clear()

        model.removeKey()
        now += 24 * 3_600_000L
        model.refreshDue(resumed = true)
        model.refreshNow()
        runCurrent()

        assertTrue(api.calls.isEmpty())
        assertEquals(EmodulState.NotConfigured, model.state.value)
        assertEquals(EmodulCatalog(emptyMap()), model.catalog())
        assertNull(settings.key())
        assertEquals(EmodulSelection.EMPTY, settings.selection(1))
        assertTrue(wizardKeys().isEmpty(), wizardKeys().toString())
        assertTrue(files.listFiles().isNullOrEmpty())
    }

    @Test
    fun `katalog - stany przejsciowe i bledy nadal daja null, a skonfigurowane Ready - wybrane kafelki`() = runTest {
        val api = FakeApi()
        val model = newModel(api)
        settings.acceptNotice()
        val preview = readyPreview(model)
        commit(model, preview, setOf(EmodulSeeds.candidates(preview.modules.single()).first().id))

        assertEquals(1, model.catalog()!!.slots.getValue(1)!!.size)
        now += 61_000
        api.failure = EmodulHttpException(401)
        model.refreshDue()
        assertNull(model.catalog())
        // selekcja przeżywa odrzucenie klucza
        assertEquals(1, settings.selection(1).selected.size)
    }

    @Test
    fun `tryb demo - kreator nie dotyka prawdziwego API ani ustawien, Anuluj nic nie tworzy`() = runTest {
        val api = FakeApi()
        val model = newModel(api)
        model.setDemo(true)
        runCurrent()

        val preview = readyPreview(model, "")
        assertEquals("Kocioł na pellet (demo)", preview.modules.single().info.name)
        // porzucony podgląd: dalej brak integracji
        assertEquals(EmodulState.NotConfigured, model.state.value)
        assertEquals(EmodulCatalog(emptyMap()), model.catalog())

        val chosen = EmodulSeeds.defaultSelection(preview.modules.single())
        commit(model, preview, chosen)
        assertEquals(chosen, model.catalog()!!.slots.getValue(1)!!.map { it.id }.toSet())
        assertTrue(api.calls.isEmpty())
        assertTrue(wizardKeys().isEmpty())
        assertFalse(files.exists())
    }
}
