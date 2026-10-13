@file:OptIn(ExperimentalCoroutinesApi::class)

package app.domik.data

import app.domik.demo.DemoNetwork
import app.domik.demo.DemoSimulation
import app.domik.deimic.client.ConnectionException
import app.domik.deimic.client.ConnectionProblem
import app.domik.deimic.client.FrameTransport
import app.domik.deimic.client.FrameTransportFactory
import app.domik.deimic.client.TransportInfo
import app.domik.deimic.client.commands.ZoneKey
import app.domik.deimic.config.InMemoryConfigCache
import app.domik.deimic.protocol.InputCalibration
import app.domik.deimic.protocol.SerialNumber
import app.domik.integrations.thermo.ThermoSettings
import app.domik.integrations.thermo.ThermoUiState
import app.domik.thermo.CalibrationBaselineState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Termometry w [AppModel]: runtime razem z sesją, ustawienia profilu i blokada trybu rzeczywistego. */
class AppModelThermoTest {
    private val store = InMemoryKeyValueStore()
    private val settings = AppSettings(store, FakeCipher()) { "profil-1" }
    private val thermoSettings = ThermoSettings(store, null)

    // Adres wyłącznie testowy (lokalnie administrowany).
    private val mac = "02:AA:BB:CC:DD:01"

    /** Transport, który nigdy się nie łączy – testy nie wychodzą do sieci. */
    private val offline = FrameTransportFactory {
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

    private fun TestScope.model(): AppModel {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val env = AppEnvironment(
            settings = settings,
            demoCache = { InMemoryConfigCache() },
            controllerCache = { InMemoryConfigCache() },
            controllerTransport = offline,
            sessionDispatcher = dispatcher,
            ioDispatcher = dispatcher,
            demoNetwork = DemoNetwork.Instant,
            demoSimulation = DemoSimulation.Manual,
            thermoSettings = thermoSettings,
            wallClock = { testScheduler.currentTime },
        )
        return AppModel(env, backgroundScope).also { runCurrent() }
    }

    @Test
    fun `w demo termometry dzialaja od razu a zapisy ida do symulatora`() = runTest {
        settings.acceptTerms()
        settings.mode = AppMode.DEMO
        val model = model()
        runCurrent()

        val thermo = model.thermo.value
        assertTrue(thermo.active)
        assertTrue(thermo.demo)
        assertEquals(4, thermo.zones.size)
        assertTrue(thermo.writesActive)
        assertFalse(thermo.writesBlockedByFlag)

        assertTrue(model.setThermoDryRun(true))
        runCurrent()
        assertTrue(model.thermo.value.dryRun)
        assertFalse(model.thermo.value.writesActive)
        assertTrue(thermoSettings.profile("demo").dryRun)
        assertEquals(4, thermoSettings.profile("demo").assignments.size) // domyślne przypisania demo zapisane razem ze zmianą

        model.leaveDemo()
        runCurrent()
        assertEquals(ThermoUiState.INACTIVE, model.thermo.value)
    }

    @Test
    fun `poza demo tryb rzeczywisty jest zablokowany flaga`() = runTest {
        settings.acceptTerms()
        settings.saveController("Dom", "192.168.1.50", 60110, SerialNumber(0x0A1B2C3Du))
        settings.mode = AppMode.CONTROLLER
        val model = model()
        runCurrent()

        assertTrue(model.thermo.value.active)
        assertEquals(!Features.SENSOR_CALIBRATION_WRITES, model.thermo.value.writesBlockedByFlag)
        if (!Features.SENSOR_CALIBRATION_WRITES) {
            assertFalse(model.setThermoDryRun(false))
            assertTrue(thermoSettings.profile("profil-1").dryRun)
        }
    }

    @Test
    fun `przypisania i ustawienia trafiaja do profilu a zapomnienie sterownika je kasuje`() = runTest {
        settings.acceptTerms()
        settings.saveController("Dom", "192.168.1.50", 60110, SerialNumber(0x0A1B2C3Du))
        settings.mode = AppMode.CONTROLLER
        val model = model()
        runCurrent()

        assertFalse(model.assignThermometer("to nie MAC", 1, 5))
        assertTrue(model.assignThermometer(mac.lowercase(), 1, 5, label = " Narożnik "))
        model.setThermoEnabled(true)
        assertTrue(model.setThermoThreshold(1.0f))
        assertFalse(model.setThermoThreshold(0.7f))
        model.setThermoHub(true)
        runCurrent()

        val profile = thermoSettings.profile("profil-1")
        assertTrue(profile.enabled)
        assertEquals(1.0f, profile.threshold)
        assertEquals(mac, profile.assignments.single().mac)
        assertEquals("Narożnik", profile.assignments.single().label)
        assertTrue(thermoSettings.hub)
        val state = model.thermo.value
        assertEquals(listOf(ZoneKey(1, 5)), state.zones.map { it.zone })
        assertTrue(state.hub && state.enabled)
        assertEquals(1.0f, state.threshold)

        model.unassignThermometer(ZoneKey(1, 5))
        assertTrue(thermoSettings.profile("profil-1").assignments.isEmpty())

        model.forgetController()
        runCurrent()
        assertFalse(thermoSettings.profile("profil-1").enabled)
        assertTrue(thermoSettings.hub) // ustawienie urządzenia zostaje
    }

    private val serial = SerialNumber(0x0A1B2C3Du)
    private val installer = InputCalibration.onePoint(20f, 17.6f)

    private fun storedState(domik: InputCalibration) {
        val files = thermoSettings.files(thermoSettings.controllerKey(serial))
        assertTrue(files.baselineStore(1).save(4, CalibrationBaselineState(installer, lastWrittenByDomik = domik)))
    }

    @Test
    fun `zapomnienie sterownika bez polaczenia zostawia pliki i trwale ostrzezenie`() = runTest {
        storedState(InputCalibration.onePoint(20f, 18.1f)) // przesunięcie Domika z poprzedniej sesji
        settings.acceptTerms()
        settings.saveController("Dom", "192.168.1.50", 60110, serial)
        settings.mode = AppMode.CONTROLLER
        val model = model()
        runCurrent()
        assertFalse(model.thermo.value.previousControllerNotRestored)

        model.forgetController()
        runCurrent()
        assertTrue(model.thermo.value.previousControllerNotRestored)
        assertTrue(thermoSettings.leaveWarning)
        val kept = thermoSettings.files(thermoSettings.controllerKey(serial)).loadBaseline(1, 4)
        assertEquals(installer, kept?.baseline) // kalibracja instalatora nie ginie

        model.dismissCalibrationLeaveWarning()
        assertFalse(model.thermo.value.previousControllerNotRestored)
        assertFalse(thermoSettings.leaveWarning)
    }

    @Test
    fun `zapomnienie sterownika bez przesuniecia Domika nie ostrzega`() = runTest {
        storedState(installer) // ostatni zapis Domika = bazowa
        settings.acceptTerms()
        settings.saveController("Dom", "192.168.1.50", 60110, serial)
        settings.mode = AppMode.CONTROLLER
        val model = model()
        runCurrent()

        model.forgetController()
        runCurrent()
        assertFalse(model.thermo.value.previousControllerNotRestored)
        assertFalse(thermoSettings.leaveWarning)
    }

    @Test
    fun `reczne wejscie spoza pokoju strefy jest odrzucane`() = runTest {
        settings.acceptTerms()
        settings.mode = AppMode.DEMO
        val model = model()
        runCurrent()

        val zone = model.thermo.value.zones.first { it.inputCandidates.isNotEmpty() }
        val bad = (0..15).first { it !in zone.inputCandidates }
        assertFalse(model.assignThermometer(mac, zone.zone.module, zone.zone.outputNr, inputNr = bad))
        assertTrue(model.assignThermometer(mac, zone.zone.module, zone.zone.outputNr, inputNr = zone.inputCandidates.first()))
    }
}
