@file:OptIn(ExperimentalCoroutinesApi::class)

package app.domik.data

import app.domik.demo.DemoNetwork
import app.domik.demo.DemoSimulation
import app.domik.deimic.client.ConnectionException
import app.domik.deimic.client.ConnectionProblem
import app.domik.deimic.client.FrameTransport
import app.domik.deimic.client.FrameTransportFactory
import app.domik.deimic.client.TransportInfo
import app.domik.deimic.client.ConnectionTestReport
import app.domik.deimic.client.ConnectionTestStep
import app.domik.deimic.client.StepResult
import app.domik.deimic.client.StepStatus
import app.domik.deimic.config.InMemoryConfigCache
import app.domik.ui.theme.AccentPalette
import app.domik.ui.theme.AppearanceSettings
import app.domik.ui.theme.ThemeMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Transport, który nigdy się nie łączy – testy aplikacji nie wychodzą do sieci. */
private val OfflineTransportFactory = FrameTransportFactory {
    object : FrameTransport {
        override val info: TransportInfo? = null

        override suspend fun connect() {
            throw ConnectionException(ConnectionProblem.HOST_UNREACHABLE, "tryb testowy: brak sieci")
        }

        override suspend fun read(): ByteArray? = null

        override suspend fun write(bytes: ByteArray) = Unit

        override fun close() = Unit
    }
}

class AppModelTest {
    private val store = InMemoryKeyValueStore()
    private val cipher = FakeCipher()
    private var ids = 0
    private val settings = AppSettings(store, cipher) { "profil-${++ids}" }
    private val controllerCache = InMemoryConfigCache()
    private val testedInputs = mutableListOf<ConnectionInput>()
    private var testReport: ConnectionTestReport = ConnectionTestReport(
        steps = ConnectionTestStep.entries.map { StepResult(it, StepStatus.PASSED) },
        firmwareVersion = "wersja",
    )
    private var testGate: CompletableDeferred<Unit>? = null

    // Przykładowy numer seryjny wyłącznie do testów (nie należy do żadnego prawdziwego sterownika).
    private val serialText = "0A1B2C3D"

    private fun TestScope.model(): AppModel {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val env = AppEnvironment(
            settings = settings,
            demoCache = { InMemoryConfigCache() },
            controllerCache = { controllerCache },
            connectionTestRunner = { input ->
                testedInputs += input
                testGate?.await()
                testReport
            },
            controllerTransport = OfflineTransportFactory,
            sessionDispatcher = dispatcher,
            ioDispatcher = dispatcher,
            demoNetwork = DemoNetwork.Instant,
            demoSimulation = DemoSimulation.Manual,
        )
        return AppModel(env, backgroundScope).also { runCurrent() }
    }

    private fun accepted() {
        settings.acceptTerms()
    }

    // Wybór ekranu startowego

    @Test
    fun `swieza instalacja zaczyna od regulaminu a po zgodzie od wyboru trybu`() = runTest {
        val model = model()
        assertEquals(Route.TERMS, model.route.value)
        assertNull(model.session.value)

        model.acceptTerms()
        runCurrent()

        assertEquals(Route.WELCOME, model.route.value)
        assertEquals(TERMS_VERSION, settings.acceptedTermsVersion)
        assertNull(model.session.value)
    }

    @Test
    fun `zmiana wersji regulaminu wymaga ponownej zgody nawet w trybie demo`() = runTest {
        settings.acceptTerms(TERMS_VERSION - 1)
        settings.mode = AppMode.DEMO

        val model = model()

        assertEquals(Route.TERMS, model.route.value)
        assertNull(model.session.value)
    }

    @Test
    fun `tryb demo uruchamia sesje z symulowanym domem`() = runTest {
        accepted()
        val model = model()
        model.acceptTerms()
        model.chooseDemo()
        runCurrent()

        assertEquals(Route.DASHBOARD, model.route.value)
        val session = checkNotNull(model.session.value)
        assertTrue(session.isDemo)
        assertTrue(session.repository.state.value.synced)
        assertEquals(AppMode.DEMO, settings.mode)
    }

    @Test
    fun `po ponownym uruchomieniu tryb demo wraca prosto na pulpit`() = runTest {
        accepted()
        settings.mode = AppMode.DEMO

        val model = model()

        assertEquals(Route.DASHBOARD, model.route.value)
        assertNotNull(model.session.value)
    }

    @Test
    fun `opuszczenie trybu demo bez sterownika wraca na ekran powitalny i konczy sesje`() = runTest {
        accepted()
        val model = model()
        model.acceptTerms()
        model.chooseDemo()
        runCurrent()
        assertNotNull(model.session.value)

        model.leaveDemo()
        runCurrent()

        assertEquals(Route.WELCOME, model.route.value)
        assertEquals(AppMode.UNSET, settings.mode)
        assertNull(model.session.value)
    }

    // Formularz połączenia

    @Test
    fun `zapis niepoprawnego formularza pokazuje bledy i nic nie zapisuje`() = runTest {
        accepted()
        val model = model()
        model.acceptTerms()
        model.openConnectionSettings()
        model.editHost("")
        model.editSerial("xyz")

        model.saveConnection()
        runCurrent()

        val form = model.form.value
        assertEquals(FieldError.HOST_EMPTY, form.hostError)
        assertEquals(FieldError.SERIAL_INVALID_CHARACTER, form.serialError)
        assertNull(settings.controller())
        assertEquals(Route.CONNECTION_SETTINGS, model.route.value)
    }

    @Test
    fun `poprawny zapis szyfruje numer, przelacza tryb i otwiera sesje ze sterownikiem`() = runTest {
        accepted()
        val model = model()
        model.acceptTerms()
        model.openConnectionSettings()
        model.editName("Dom")
        model.editHost("192.168.1.50")
        model.editSerial(serialText)

        model.saveConnection()
        runCurrent()

        val saved = checkNotNull(settings.controller())
        assertEquals("192.168.1.50", saved.profile.host)
        assertEquals(60110, saved.profile.port)
        assertEquals("Dom", saved.profile.name)
        assertNotNull(saved.serial)
        assertEquals(AppMode.CONTROLLER, settings.mode)
        assertEquals(Route.DASHBOARD, model.route.value)
        val session = checkNotNull(model.session.value)
        assertFalse(session.isDemo)
        // Numer seryjny nie zostaje w formularzu ani w jego opisie tekstowym.
        assertEquals("", model.form.value.serial)
        assertFalse(model.form.value.toString().contains(serialText, ignoreCase = true))
    }

    @Test
    fun `blad szyfrowania nie zmienia ustawien i zostaje w formularzu`() = runTest {
        accepted()
        val model = model()
        model.acceptTerms()
        model.openConnectionSettings()
        model.editHost("sterownik.local")
        model.editSerial(serialText)
        cipher.failEncrypt = true

        model.saveConnection()
        runCurrent()

        assertTrue(model.form.value.storageFailed)
        assertNull(settings.controller())
        assertEquals(Route.CONNECTION_SETTINGS, model.route.value)
        assertEquals(AppMode.UNSET, settings.mode)
    }

    @Test
    fun `test polaczenia przekazuje dane z formularza i pokazuje wynik`() = runTest {
        accepted()
        val model = model()
        model.acceptTerms()
        model.openConnectionSettings()
        model.editHost("192.168.1.50")
        model.editPort("60111")
        model.editSerial(serialText)
        testGate = CompletableDeferred()

        model.testConnection()
        runCurrent()
        assertEquals(ConnectionTestState.Running, model.form.value.test)

        testGate!!.complete(Unit)
        runCurrent()

        val done = model.form.value.test as ConnectionTestState.Done
        assertEquals(ConnectionTestOutcome.Success("wersja", null), done.outcome)
        assertEquals("192.168.1.50", testedInputs.single().host)
        assertEquals(60111, testedInputs.single().port)
        // Test niczego nie zapisuje.
        assertNull(settings.controller())
    }

    @Test
    fun `nieudany test pokazuje powod`() = runTest {
        accepted()
        val model = model()
        model.acceptTerms()
        model.openConnectionSettings()
        model.editHost("192.168.1.50")
        model.editSerial(serialText)
        testReport = ConnectionTestReport(
            listOf(StepResult(ConnectionTestStep.TLS, StepStatus.FAILED, ConnectionProblem.CERTIFICATE_MISMATCH)) +
                listOf(ConnectionTestStep.PING, ConnectionTestStep.FIRMWARE_VERSION, ConnectionTestStep.CONFIG_CHECKSUM)
                    .map { StepResult(it, StepStatus.SKIPPED) },
        )

        model.testConnection()
        runCurrent()

        val done = model.form.value.test as ConnectionTestState.Done
        assertEquals(ConnectionTestOutcome.Failure(ConnectionTestFailure.CERTIFICATE_MISMATCH), done.outcome)
    }

    @Test
    fun `test z niepoprawnym formularzem w ogole nie laczy sie ze sterownikiem`() = runTest {
        accepted()
        val model = model()
        model.acceptTerms()
        model.openConnectionSettings()
        model.editHost("192.168.1.50")
        model.editSerial("")

        model.testConnection()
        runCurrent()

        assertTrue(testedInputs.isEmpty())
        assertEquals(FieldError.SERIAL_EMPTY, model.form.value.serialError)
    }

    @Test
    fun `puste pole numeru przy zapisanym sterowniku zachowuje zapisany numer`() = runTest {
        accepted()
        val profile = settings.saveController("Dom", "192.168.1.50", 60110, app.domik.deimic.protocol.SerialNumber(0x0A1B2C3Du))
        settings.mode = AppMode.CONTROLLER
        val model = model()
        model.openConnectionSettings()
        assertTrue(model.form.value.serialStored)
        assertEquals("192.168.1.50", model.form.value.host)

        model.editName("Nowa nazwa")
        model.saveConnection()
        runCurrent()

        val saved = checkNotNull(settings.controller())
        assertEquals("Nowa nazwa", saved.profile.name)
        assertEquals(profile.id, saved.profile.id)
        assertNotNull(saved.serial)
    }

    @Test
    fun `zmiana adresu tworzy nowy profil i usuwa kopie konfiguracji starego`() = runTest {
        accepted()
        val old = settings.saveController("Dom", "192.168.1.50", 60110, app.domik.deimic.protocol.SerialNumber(0x0A1B2C3Du))
        settings.mode = AppMode.CONTROLLER
        controllerCache.write(old.id, app.domik.deimic.config.CachedConfig(1u, byteArrayOf(1, 2, 3)))
        val model = model()
        model.openConnectionSettings()

        model.editHost("10.0.0.51")
        model.saveConnection()
        runCurrent()

        val saved = checkNotNull(settings.controller())
        assertNotEquals(old.id, saved.profile.id)
        assertNull(controllerCache.read(old.id))
    }

    @Test
    fun `zapomnienie sterownika czysci ustawienia i wraca na ekran powitalny`() = runTest {
        accepted()
        settings.saveController("Dom", "192.168.1.50", 60110, app.domik.deimic.protocol.SerialNumber(0x0A1B2C3Du))
        settings.mode = AppMode.CONTROLLER
        val model = model()
        assertNotNull(model.session.value)

        model.forgetController()
        runCurrent()

        assertNull(settings.controller())
        assertEquals(AppMode.UNSET, settings.mode)
        assertEquals(Route.WELCOME, model.route.value)
        assertNull(model.session.value)
    }

    @Test
    fun `przelaczanie miedzy demo a sterownikiem nie zostawia dwoch sesji`() = runTest {
        accepted()
        settings.saveController("Dom", "192.168.1.50", 60110, app.domik.deimic.protocol.SerialNumber(0x0A1B2C3Du))
        settings.mode = AppMode.CONTROLLER
        val model = model()
        val controllerSession = checkNotNull(model.session.value)
        assertFalse(controllerSession.isDemo)

        model.chooseDemo()
        runCurrent()
        val demo = checkNotNull(model.session.value)
        assertTrue(demo.isDemo)

        model.leaveDemo()
        runCurrent()
        val again = checkNotNull(model.session.value)
        assertFalse(again.isDemo)
        assertNotEquals(controllerSession, again)
    }

    @Test
    fun `wyglad jest zapamietywany w ustawieniach`() = runTest {
        accepted()
        val model = model()
        val chosen = AppearanceSettings(ThemeMode.Dark, AccentPalette.Blue)

        model.setAppearance(chosen)

        assertEquals(chosen, model.appearance.value)
        assertEquals(chosen, settings.appearance)
    }

    @Test
    fun `anulowanie formularza przy pierwszym uruchomieniu wraca na ekran powitalny`() = runTest {
        accepted()
        val model = model()
        model.acceptTerms()
        model.openConnectionSettings()
        assertTrue(model.form.value.canCancel)

        model.cancelConnectionSettings()
        runCurrent()

        assertEquals(Route.WELCOME, model.route.value)
    }
}
