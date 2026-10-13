@file:OptIn(ExperimentalCoroutinesApi::class)

package app.domik.integrations.thermo

import app.domik.data.ConfigStatus
import app.domik.data.HomeState
import app.domik.data.InMemoryKeyValueStore
import app.domik.data.InputKey
import app.domik.data.Sessions
import app.domik.demo.DemoNetwork
import app.domik.demo.DemoSimulation
import app.domik.deimic.client.ConnectionState
import app.domik.deimic.client.commands.CalibrationReadout
import app.domik.deimic.client.commands.CommandOutcome
import app.domik.deimic.client.commands.ZoneKey
import app.domik.deimic.config.AnalogInput
import app.domik.deimic.config.HeatingZone
import app.domik.deimic.config.HomeConfig
import app.domik.deimic.config.InMemoryConfigCache
import app.domik.deimic.config.Level
import app.domik.deimic.config.Room
import app.domik.deimic.protocol.InputCalibration
import app.domik.thermo.CalibrationIdleReason
import app.domik.thermo.CalibrationReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** Łańcuch termometr → porównanie z wejściem sterownika → kalibracja 0x132, na czasie wirtualnym. */
class ThermoRuntimeTest {
    // Adres wyłącznie testowy (lokalnie administrowany).
    private val mac = "02:AA:BB:CC:DD:01"
    private val zone = ZoneKey(1, 5)

    /**
     * Strefa `output_nr` 5 w pokoju 3; jej czujnik to wejście 4 (ten sam pokój). Wejście 5 – numer równy numerowi
     * strefy – jest w innym pokoju: pułapka na mapowanie po numerze.
     */
    private val config = HomeConfig(
        levels = listOf(Level(1, "Piętro")),
        rooms = listOf(Room(3, 1, "Pokój 3"), Room(4, 1, "Gabinet"), Room(8, 1, "Strych"), Room(9, 1, "Hol")),
        modules = listOf(1),
        outputs = emptyList(),
        heatingZones = listOf(
            HeatingZone(10, 1, 5, "Pokój 3", roomId = 3, levelId = 1),
            HeatingZone(11, 1, 6, "Strych", roomId = 8, levelId = 1),
            HeatingZone(12, 1, 7, "Hol", roomId = 9, levelId = 1),
        ),
        analogInputs = listOf(
            AnalogInput(20, 1, 4, roomId = 3, levelId = 1, inputType = 8, name = "T sypialnia"),
            AnalogInput(21, 1, 5, roomId = 4, levelId = 1, inputType = 8, name = "T gabinet"),
            AnalogInput(22, 1, 1, roomId = 9, levelId = 1, inputType = 8, name = "T hol 1"),
            AnalogInput(23, 1, 2, roomId = 9, levelId = 1, inputType = 8, name = "T hol 2"),
        ),
    )

    /** Kalibracja instalatora: −2,4 °C. */
    private val installer = InputCalibration.onePoint(20f, 17.6f)

    private class FakePort(val records: MutableList<InputCalibration>, val onChange: () -> Unit) : CalibrationPort {
        val writes = mutableListOf<Pair<Int, InputCalibration>>()
        var reads = 0

        /** Kolejność operacji: "R" odczyt, "W" zapis. */
        val events = mutableListOf<String>()

        /** Wywoływane przy każdym odczycie, zanim sterownik odpowie (np. zmiana w Configuratorze w międzyczasie). */
        var beforeRead: () -> Unit = {}
        var failReads = false

        override suspend fun read(module: Int): CalibrationReadout {
            reads++
            events += "R"
            beforeRead()
            if (failReads) return CalibrationReadout.Failure(CommandOutcome.NoAck)
            return CalibrationReadout.Success(records.toList())
        }

        override suspend fun write(module: Int, inputNr: Int, record: InputCalibration): CommandOutcome {
            writes += inputNr to record
            events += "W"
            records[inputNr] = record
            onChange()
            return CommandOutcome.Confirmed
        }
    }

    private class FakeSource : ThermometerSource {
        override val status: StateFlow<ScannerStatus> = MutableStateFlow(ScannerStatus.SCANNING)
        val flow = MutableSharedFlow<ThermometerReading>(extraBufferCapacity = 64)
        override val readings: SharedFlow<ThermometerReading> = flow
        var lastMode = ScanMode.OFF

        override fun setMode(mode: ScanMode) {
            lastMode = mode
        }
    }

    /** Sterownik (wejście 4: surowy odczyt [rawC] przez rekord z [port]), termometr co 10 s i runtime. */
    private inner class Rig(
        val test: TestScope,
        settings: ThermoProfileSettings,
        hub: Boolean = true,
        demo: Boolean = false,
        flag: Boolean = true,
        homeConfig: HomeConfig = config,
    ) {
        var rawC = 24.0f
        var xiaomiC = 20.1f
        var thermometerOn = true
        val files = ThermoFiles(null)
        val home = MutableStateFlow(HomeState(config = homeConfig, configStatus = ConfigStatus.Ready, synced = true))
        val connection = MutableStateFlow<ConnectionState>(ConnectionState.Ready)
        val port = FakePort(MutableList(12) { installer }) { updateHome() }
        val source = FakeSource()
        lateinit var runtime: ThermoRuntime
        private val settings0 = settings
        private val hub0 = hub
        private val demo0 = demo
        private val flag0 = flag

        init {
            updateHome()
            test.backgroundScope.launch {
                var counter = 0
                while (true) {
                    if (thermometerOn) {
                        source.flow.emit(
                            ThermometerReading(mac, null, -60, xiaomiC, 45f, 90, 2900, counter, null, test.testScheduler.currentTime),
                        )
                        counter = (counter + 1) and 0xFF
                    }
                    delay(10_000)
                }
            }
            open(settings0)
        }

        private fun open(settings: ThermoProfileSettings) {
            runtime = ThermoRuntime(
                scope = CoroutineScope(test.backgroundScope.coroutineContext + Job(test.backgroundScope.coroutineContext[Job])),
                home = home,
                connection = connection,
                port = port,
                source = source,
                files = files,
                settings = settings,
                hub = hub0,
                demo = demo0,
                clock = { test.testScheduler.currentTime },
                timing = ThermoTiming.DEFAULT,
                writesFeatureEnabled = flag0,
            )
            runtime.start()
            test.runCurrent()
        }

        /** Nowa sesja (np. restart aplikacji): nowy runtime na tych samych plikach i tym samym sterowniku. */
        fun restart(settings: ThermoProfileSettings = settings0) {
            runtime.close()
            open(settings)
        }

        fun reported(input: Int): Float = port.records[input].correctCelsius(rawC)

        fun updateHome() {
            home.value = home.value.copy(analogInputs = mapOf(InputKey(1, 4) to reported(4), InputKey(1, 5) to reported(5)))
        }

        fun advance(duration: Duration) {
            test.advanceTimeBy(duration)
            test.runCurrent()
        }

        fun zoneUi(key: ZoneKey = zone): ThermoZoneUi = runtime.state.value.zones.single { it.zone == key }

        fun log(): List<ThermoLogEntry> = files.log(test.testScheduler.currentTime)
    }

    private val assignment = ThermoAssignment(mac, 1, 5, null, "Narożnik")
    private val live = ThermoProfileSettings(enabled = true, dryRun = false, assignments = listOf(assignment))
    private val dry = live.copy(dryRun = true)

    private fun near(expected: Float, actual: Float?, tolerance: Float = 0.051f) {
        assertTrue(actual != null && abs(expected - actual) <= tolerance, "expected $expected, got $actual")
    }

    @Test
    fun `tryb probny nic nie wysyla a decyzje obu progow trafiaja do dziennika`() = runTest {
        val rig = Rig(this, dry)
        rig.advance(30.minutes)

        assertTrue(rig.port.writes.isEmpty())
        assertTrue(rig.port.reads >= 1)
        val log = rig.log()
        assertTrue(log.any { it.kind == ThermoLogKind.BASELINE_CAPTURED })
        val calibrations = log.filter { it.kind == ThermoLogKind.CALIBRATION }
        assertTrue(calibrations.all { it.dryRun && it.result == ThermoLogResult.DRY_RUN && it.inputNr == 4 })
        assertEquals(setOf(0.5f, 1.0f), calibrations.map { it.threshold }.toSet())
        val ui = rig.zoneUi()
        assertEquals(ThermoZoneStatus.ACTIVE, ui.status)
        assertFalse(ui.writesActive)
        assertTrue(ui.frequency.single { it.threshold == 0.5f }.let { it.primary && it.calibrationsTotal >= 1 && it.calibrationsLast24h >= 1 })
        assertTrue(ui.frequency.single { it.threshold == 1.0f }.calibrationsTotal >= 1)
        near(-2.4f, ui.calibrationOffsetC) // na sterowniku bez zmian
        near(-3.9f, ui.plannedOffsetC) // symulacja: −2,4 − 1,5
    }

    @Test
    fun `urzadzenie nie bedace hubem nic nie wysyla`() = runTest {
        val rig = Rig(this, live, hub = false)
        rig.advance(30.minutes)

        assertTrue(rig.port.writes.isEmpty())
        assertFalse(rig.runtime.state.value.writesActive)
        assertTrue(rig.log().any { it.kind == ThermoLogKind.CALIBRATION && it.result == ThermoLogResult.DRY_RUN })
    }

    @Test
    fun `flaga funkcji blokuje zapis poza demo`() = runTest {
        val rig = Rig(this, live, flag = false)
        rig.advance(30.minutes)

        assertTrue(rig.port.writes.isEmpty())
        assertTrue(rig.runtime.state.value.writesBlockedByFlag)
        assertFalse(rig.runtime.state.value.writesActive)
    }

    @Test
    fun `w demo zapis do symulatora jest dozwolony mimo flagi`() = runTest {
        val rig = Rig(this, live, hub = false, demo = true, flag = false)
        rig.advance(5.minutes)

        assertEquals(1, rig.port.writes.size)
        assertFalse(rig.runtime.state.value.writesBlockedByFlag)
    }

    @Test
    fun `tryb rzeczywisty kalibruje wejscie z pokoju strefy i weryfikuje odczytem`() = runTest {
        val rig = Rig(this, live)
        near(21.6f, rig.reported(4))
        rig.advance(3.minutes)

        val (input, record) = rig.port.writes.single()
        assertEquals(4, input) // wejście z pomieszczenia strefy, nie numer strefy (5)
        assertEquals(161f, record.true1Tenths) // 17,6 − 1,5 °C
        near(20.1f, rig.home.value.analogInputs[InputKey(1, 4)])
        val log = rig.log()
        assertTrue(log.any { it.kind == ThermoLogKind.CALIBRATION && it.result == ThermoLogResult.CONFIRMED && !it.dryRun && it.reason == CalibrationReason.DIFFERENCE })
        assertTrue(log.any { it.kind == ThermoLogKind.READBACK && it.result == ThermoLogResult.VERIFIED })

        rig.advance(60.minutes)
        assertEquals(1, rig.port.writes.size) // zbieżne – nic więcej
        val ui = rig.zoneUi()
        assertTrue(ui.writesActive)
        assertEquals(4, ui.inputNr)
        near(-3.9f, ui.calibrationOffsetC)
        near(-2.4f, ui.baselineOffsetC)
        assertEquals(20.1f, ui.thermometer.tempC)
        assertTrue(ui.thermometer.fresh)
    }

    @Test
    fun `zmiana kalibracji poza Domikiem wstrzymuje zapisy do potwierdzenia`() = runTest {
        val rig = Rig(this, live)
        rig.xiaomiC = 21.6f // zgodne z Deimic – nic do zrobienia
        rig.advance(5.minutes)
        assertTrue(rig.port.writes.isEmpty())

        // Instalator zmienia kalibrację w Configuratorze (+1,0 °C).
        rig.port.records[4] = InputCalibration.onePoint(20f, 18.6f)
        rig.updateHome()
        rig.runtime.refreshCalibrations()
        rig.test.runCurrent()

        val paused = rig.zoneUi()
        assertEquals(ThermoZoneStatus.PAUSED, paused.status)
        assertTrue(ThermoNotice.EXTERNAL_CHANGE in paused.notices)
        assertFalse(paused.writesActive)
        assertTrue(rig.log().any { it.kind == ThermoLogKind.EXTERNAL_CHANGE })

        rig.advance(40.minutes)
        assertTrue(rig.port.writes.isEmpty()) // różnica −1,0 °C, ale strefa wstrzymana

        rig.runtime.acknowledgeExternalChange(zone)
        rig.advance(5.minutes)
        assertEquals(ThermoZoneStatus.ACTIVE, rig.zoneUi().status)
        assertEquals(176f, rig.port.writes.single().second.true1Tenths) // 18,6 − 1,0 °C: nowa bazowa jest punktem wyjścia
    }

    @Test
    fun `nieaktualny termometr zostawia ostatnia kalibracje i wstrzymuje korekte`() = runTest {
        val rig = Rig(this, live)
        rig.advance(3.minutes)
        assertEquals(1, rig.port.writes.size)
        val calibrated = rig.port.writes[0].second

        rig.thermometerOn = false
        rig.advance(15.minutes)
        assertTrue(ThermoNotice.THERMOMETER_STALE in rig.zoneUi().notices)
        rig.advance(40.minutes)

        assertEquals(1, rig.port.writes.size) // bez przywrócenia kalibracji instalatora
        assertTrue(rig.log().none { it.kind == ThermoLogKind.RESTORE })
        assertEquals(CalibrationIdleReason.SENSOR_LOST, rig.zoneUi().idleReason)
        assertTrue(ThermoNotice.THERMOMETER_STALE in rig.zoneUi().notices)
        assertTrue(calibrated != installer)
    }

    @Test
    fun `w trybie probnym nieaktualny termometr nie daje wpisu o przywroceniu`() = runTest {
        val rig = Rig(this, dry)
        rig.advance(3.minutes)
        rig.thermometerOn = false
        rig.advance(40.minutes)

        assertTrue(rig.port.writes.isEmpty())
        assertTrue(rig.log().none { it.kind == ThermoLogKind.RESTORE })
    }

    @Test
    fun `strefa bez jednego wejscia w pokoju czeka na wybor wejscia`() = runTest {
        val strych = ThermoAssignment("02:AA:BB:CC:DD:02", 1, 6)
        val hol = ThermoAssignment("02:AA:BB:CC:DD:03", 1, 7)
        val rig = Rig(this, dry.copy(assignments = listOf(assignment, strych, hol)))
        rig.advance(1.minutes)

        assertEquals(4, rig.zoneUi().inputNr)
        rig.zoneUi(ZoneKey(1, 6)).let {
            assertEquals(ThermoZoneStatus.NEEDS_INPUT, it.status)
            assertTrue(ThermoNotice.NO_INPUT in it.notices)
            assertEquals(listOf(1, 2, 4, 5), it.inputCandidates)
        }
        rig.zoneUi(ZoneKey(1, 7)).let {
            assertEquals(ThermoZoneStatus.NEEDS_INPUT, it.status)
            assertTrue(ThermoNotice.AMBIGUOUS_INPUT in it.notices)
        }

        rig.runtime.updateSettings(dry.copy(assignments = listOf(assignment, strych, hol.copy(inputNr = 2))), hub = true)
        rig.test.runCurrent()
        assertEquals(ThermoZoneStatus.ACTIVE, rig.zoneUi(ZoneKey(1, 7)).status)
        assertEquals(2, rig.zoneUi(ZoneKey(1, 7)).inputNr)
    }

    @Test
    fun `reczne przywrocenie bazowej w trybie probnym tylko w dzienniku`() = runTest {
        val rig = Rig(this, dry)
        rig.advance(1.minutes)
        rig.runtime.restoreBaseline(zone)
        rig.test.runCurrent()

        assertTrue(rig.port.writes.isEmpty())
        assertTrue(rig.log().any { it.kind == ThermoLogKind.MANUAL_RESTORE && it.result == ThermoLogResult.DRY_RUN })
    }

    @Test
    fun `w demo wartosc wejscia sterownika zbiega do termometru`() = runTest {
        val session = Sessions.demo(
            backgroundScope, InMemoryConfigCache(), DemoNetwork.Instant, DemoSimulation.Manual, StandardTestDispatcher(testScheduler),
        )
        session.repository.start()
        runCurrent()
        val controller = checkNotNull(session.demoController)
        val config = checkNotNull(session.repository.state.value.config)
        val inputs = config.heatingZones.associateWith { config.temperatureInputsFor(it).single() }
        val before = inputs.mapValues { (_, input) -> controller.calibration(input.module, input.inputNr) }

        val runtime = ThermoSessions.create(
            session, ThermoSettings(InMemoryKeyValueStore(), null), null, { testScheduler.currentTime }, backgroundScope,
        )
        runCurrent()
        assertTrue(runtime.state.value.writesActive)
        advanceTimeBy(5.minutes)
        runCurrent()

        assertEquals(inputs.size, runtime.state.value.zones.size)
        for ((zone, input) in inputs) {
            val xiaomi = checkNotNull(controller.roomTemperature(zone.module, zone.outputNr)) + ThermoSessions.DEMO_CORNER_OFFSET_C
            val deimic = checkNotNull(session.repository.state.value.analogInputs[InputKey(input.module, input.inputNr)])
            assertTrue(abs(deimic - xiaomi) <= 0.5f, "${zone.name}: Deimic $deimic, Xiaomi $xiaomi")
            assertNotEquals(before[zone], controller.calibration(input.module, input.inputNr))
        }
        runtime.close()
    }

    // Testy regresyjne – przegląd kodu 2026-10-12

    private val configurator = InputCalibration.onePoint(20f, 18.6f)

    @Test
    fun `kazdy zapis poprzedza swiezy odczyt kalibracji`() = runTest {
        val rig = Rig(this, live)
        rig.advance(3.minutes)
        rig.runtime.restoreBaseline(zone) // ręczne przywrócenie bazowej
        rig.advance(5.minutes)
        assertEquals(2, rig.port.writes.size)
        rig.port.events.withIndex().filter { it.value == "W" }.forEach { (i, _) -> assertEquals("R", rig.port.events[i - 1]) }
    }

    @Test
    fun `zmiana kalibracji tuz przed zapisem porzuca zapis i wstrzymuje strefe`() = runTest {
        val rig = Rig(this, live)
        rig.port.beforeRead = { if (rig.test.testScheduler.currentTime >= 30_000) rig.port.records[4] = configurator }
        rig.advance(3.minutes)

        assertTrue(rig.port.writes.isEmpty())
        assertEquals(ThermoZoneStatus.PAUSED, rig.zoneUi().status)
        val log = rig.log()
        assertTrue(log.any { it.kind == ThermoLogKind.EXTERNAL_CHANGE })
        assertTrue(log.any { it.kind == ThermoLogKind.CALIBRATION && it.detail == ThermoLogDetail.NOT_SENT_RECORD_CHANGED && it.result == ThermoLogResult.FAILED })
        rig.advance(30.minutes)
        assertTrue(rig.port.writes.isEmpty())
    }

    @Test
    fun `nieudany odczyt przed zapisem to brak zapisu`() = runTest {
        val rig = Rig(this, live)
        rig.port.beforeRead = { rig.port.failReads = rig.test.testScheduler.currentTime >= 30_000 && rig.test.testScheduler.currentTime < 10 * 60_000 }
        rig.advance(5.minutes)
        assertTrue(rig.port.writes.isEmpty())
        assertTrue(rig.log().any { it.detail == ThermoLogDetail.NOT_SENT_READ_FAILED })

        rig.advance(20.minutes) // odczyty znów działają; ponowienie po odstępie minimalnym
        assertEquals(1, rig.port.writes.size)
    }

    @Test
    fun `reczne przywrocenie przy probie zapisu tez czyta najpierw i bez odczytu nic nie wysyla`() = runTest {
        val rig = Rig(this, live)
        rig.advance(3.minutes)
        assertEquals(1, rig.port.writes.size)
        rig.port.failReads = true
        rig.runtime.restoreBaseline(zone)
        rig.test.runCurrent()
        assertEquals(1, rig.port.writes.size)
        assertTrue(rig.log().any { it.kind == ThermoLogKind.MANUAL_RESTORE && it.detail == ThermoLogDetail.NOT_SENT_READ_FAILED })
    }

    @Test
    fun `wyjscie strefy z trybu rzeczywistego najpierw przywraca kalibracje bazowa`() = runTest {
        val transitions = listOf<(ThermoRuntime) -> Unit>(
            { it.updateSettings(dry, hub = true) }, // tryb próbny
            { it.updateSettings(live, hub = false) }, // to urządzenie przestaje być hubem
            { it.updateSettings(live.withoutAssignment(zone), hub = true) }, // usunięcie przypisania
            { it.updateSettings(live.copy(assignments = listOf(assignment.copy(inputNr = 5))), hub = true) }, // inne (błędne) wejście
        )
        for ((i, change) in transitions.withIndex()) {
            val rig = Rig(this, live)
            rig.advance(3.minutes)
            assertEquals(1, rig.port.writes.size, "przejście $i")
            change(rig.runtime)
            rig.test.runCurrent()

            assertEquals(2, rig.port.writes.size, "przejście $i")
            assertEquals(installer, rig.port.records[4], "przejście $i")
            assertTrue(
                rig.log().any { it.kind == ThermoLogKind.RESTORE && it.detail == ThermoLogDetail.LEAVING_LIVE && it.result == ThermoLogResult.CONFIRMED },
                "przejście $i",
            )
            rig.advance(40.minutes)
            assertEquals(2, rig.port.writes.size, "przejście $i")
            assertTrue(rig.runtime.state.value.leftoverInputs.isEmpty(), "przejście $i")
            rig.runtime.close()
        }
    }

    @Test
    fun `bez polaczenia przejscie na tryb probny zostawia ostrzezenie o kalibracji bez nadzoru`() = runTest {
        val rig = Rig(this, live)
        rig.advance(3.minutes)
        rig.connection.value = ConnectionState.Idle
        rig.runtime.updateSettings(dry, hub = true)
        rig.test.runCurrent()

        assertEquals(1, rig.port.writes.size)
        assertEquals(listOf(InputKey(1, 4)), rig.runtime.state.value.leftoverInputs)
        assertTrue(ThermoZoneIssue.CALIBRATION_NOT_RESTORED in rig.zoneUi().issues)
        assertTrue(rig.files.loadBaseline(1, 4)!!.mayHoldDomikShift) // stan zostaje w plikach
    }

    @Test
    fun `przed opuszczeniem sterownika kalibracje bazowe sa przywracane`() = runTest {
        val rig = Rig(this, live)
        rig.advance(3.minutes)
        assertTrue(rig.runtime.restoreBeforeLeaving())
        assertEquals(installer, rig.port.records[4])
        assertTrue(rig.log().any { it.detail == ThermoLogDetail.LEAVING_CONTROLLER && it.result == ThermoLogResult.CONFIRMED })
        rig.advance(30.minutes)
        assertEquals(2, rig.port.writes.size) // korektor wyłączony
    }

    @Test
    fun `bez polaczenia opuszczenie sterownika zglasza nieprzywrocona kalibracje`() = runTest {
        val rig = Rig(this, live)
        rig.advance(3.minutes)
        rig.connection.value = ConnectionState.Idle
        assertFalse(rig.runtime.restoreBeforeLeaving())
        assertEquals(1, rig.port.writes.size)

        // Ten sam sterownik dodany ponownie: rekord Domika jest rozpoznany, kalibracja bazowa się nie zmienia.
        rig.connection.value = ConnectionState.Ready
        rig.restart()
        assertEquals(installer, rig.files.loadBaseline(1, 4)!!.baseline)
        assertFalse(rig.log().any { it.kind == ThermoLogKind.EXTERNAL_CHANGE })
    }

    @Test
    fun `nowa sesja zaraz po zapisie nie koryguje drugi raz`() = runTest {
        val rig = Rig(this, live)
        rig.advance(3.minutes)
        assertEquals(1, rig.port.writes.size)
        rig.xiaomiC = 19.0f // znów ponad próg
        rig.restart()
        rig.advance(10.minutes)
        assertEquals(1, rig.port.writes.size) // odstęp 15 min liczony od zapisu sprzed restartu
        rig.advance(10.minutes)
        assertEquals(2, rig.port.writes.size)
    }

    @Test
    fun `reczne przywrocenie wstrzymuje korekte strefy do wznowienia`() = runTest {
        val rig = Rig(this, live)
        rig.advance(3.minutes)
        rig.runtime.restoreBaseline(zone)
        rig.test.runCurrent()
        assertEquals(installer, rig.port.records[4])
        assertTrue(rig.log().any { it.kind == ThermoLogKind.MANUAL_RESTORE && it.result == ThermoLogResult.CONFIRMED })

        rig.advance(60.minutes)
        assertEquals(2, rig.port.writes.size) // reguła nie cofa przywrócenia
        rig.zoneUi().let {
            assertTrue(it.manuallyPaused)
            assertTrue(ThermoZoneIssue.MANUAL_RESTORE in it.issues)
            assertEquals(ThermoZoneStatus.ACTIVE, it.status)
        }
        rig.restart() // wstrzymanie przeżywa nową sesję
        rig.advance(30.minutes)
        assertEquals(2, rig.port.writes.size)

        rig.runtime.resumeCalibration(zone)
        rig.advance(5.minutes)
        assertEquals(3, rig.port.writes.size)
        assertFalse(rig.zoneUi().manuallyPaused)
    }

    @Test
    fun `reczne wejscie spoza dozwolonych nie dostaje korektora`() = runTest {
        val strych = ThermoAssignment("02:AA:BB:CC:DD:02", 1, 6)
        val hol = ThermoAssignment("02:AA:BB:CC:DD:03", 1, 7)
        val rig = Rig(
            this,
            live.copy(assignments = listOf(assignment.copy(inputNr = 5), strych.copy(inputNr = 9), hol.copy(inputNr = 5))),
        )
        rig.advance(10.minutes)

        assertTrue(rig.port.writes.isEmpty())
        rig.zoneUi().let {
            assertEquals(ThermoZoneStatus.NEEDS_INPUT, it.status)
            assertTrue(ThermoZoneIssue.INPUT_CONFLICT in it.issues)
            assertEquals(null, it.inputNr)
            assertEquals(listOf(4), it.inputCandidates)
        }
        rig.zoneUi(ZoneKey(1, 6)).let {
            assertEquals(ThermoZoneStatus.NEEDS_INPUT, it.status)
            assertTrue(ThermoZoneIssue.INPUT_NOT_ALLOWED in it.issues)
        }
        rig.zoneUi(ZoneKey(1, 7)).let {
            assertEquals(ThermoZoneStatus.NEEDS_INPUT, it.status)
            assertTrue(ThermoZoneIssue.INPUT_NOT_ALLOWED in it.issues)
            assertEquals(listOf(1, 2), it.inputCandidates)
        }
    }

    @Test
    fun `wybor wejscia wg pomieszczenia i modulu`() {
        val room1 = listOf(4)
        val room2 = listOf(1, 2)
        val module = listOf(1, 2, 4, 5)
        assertEquals(ThermoRuntime.InputResolution(4), ThermoRuntime.resolveInput(null, room1, module))
        assertEquals(ThermoRuntime.InputResolution(4), ThermoRuntime.resolveInput(4, room1, module))
        assertEquals(ThermoRuntime.InputResolution(null, ThermoZoneIssue.INPUT_CONFLICT), ThermoRuntime.resolveInput(5, room1, module))
        assertEquals(ThermoRuntime.InputResolution(null), ThermoRuntime.resolveInput(null, room2, module))
        assertEquals(ThermoRuntime.InputResolution(2), ThermoRuntime.resolveInput(2, room2, module))
        assertEquals(ThermoRuntime.InputResolution(null, ThermoZoneIssue.INPUT_NOT_ALLOWED), ThermoRuntime.resolveInput(4, room2, module))
        assertEquals(ThermoRuntime.InputResolution(5), ThermoRuntime.resolveInput(5, emptyList(), module))
        assertEquals(ThermoRuntime.InputResolution(null, ThermoZoneIssue.INPUT_NOT_ALLOWED), ThermoRuntime.resolveInput(9, emptyList(), module))
        assertEquals(listOf(4), ThermoRuntime.allowedInputs(config, 1, 5))
        assertEquals(listOf(1, 2), ThermoRuntime.allowedInputs(config, 1, 7))
        assertEquals(listOf(1, 2, 4, 5), ThermoRuntime.allowedInputs(config, 1, 6))
        assertEquals(null, ThermoRuntime.allowedInputs(config, 1, 99))
    }
}
