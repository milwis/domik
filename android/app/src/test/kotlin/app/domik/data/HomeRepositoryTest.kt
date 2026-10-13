@file:OptIn(ExperimentalCoroutinesApi::class)

package app.domik.data

import app.domik.demo.DemoNetwork
import app.domik.demo.DemoShutterButton
import app.domik.demo.DemoSimulation
import app.domik.demo.FakeController
import app.domik.deimic.client.ConnectionConfig
import app.domik.deimic.client.ConnectionProblem
import app.domik.deimic.client.ConnectionState
import app.domik.deimic.client.commands.ZoneKey
import app.domik.deimic.config.InMemoryConfigCache
import app.domik.deimic.protocol.HeatingProfile
import app.domik.deimic.protocol.ShutterDirection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class HomeRepositoryTest {
    private class Rig(val session: HomeSession, val events: MutableList<HomeEvent>) {
        val repository: HomeRepository get() = session.repository
        val controller: FakeController get() = checkNotNull(session.demoController)
        val state: HomeState get() = repository.state.value
    }

    private val latency100 = DemoNetwork(
        minLatency = 100.milliseconds, maxLatency = 100.milliseconds, connectDelay = 0.milliseconds,
    )

    /** Sesja demo na wirtualnym czasie testu: bez opóźnień sieci i bez symulacji tła (chyba że podano inaczej). */
    private fun TestScope.rig(
        network: DemoNetwork = DemoNetwork.Instant,
        cache: InMemoryConfigCache = InMemoryConfigCache(),
        connectionConfig: ConnectionConfig = ConnectionConfig(),
        start: Boolean = true,
    ): Rig {
        val session = Sessions.demo(
            backgroundScope, cache, network, DemoSimulation.Manual, StandardTestDispatcher(testScheduler),
            connectionConfig = connectionConfig,
        )
        val events = mutableListOf<HomeEvent>()
        backgroundScope.launch { session.repository.events.collect { events += it } }
        runCurrent()
        if (start) {
            session.repository.start()
            runCurrent()
        }
        return Rig(session, events)
    }

    private fun zone(module: Int, outputNr: Int) = ZoneKey(module, outputNr)

    // Start i synchronizacja

    @Test
    fun afterTheConnectionTheWholeHomeIsLoaded() = runTest {
        val rig = rig()
        val state = rig.state

        assertEquals(ConnectionState.Ready, rig.repository.connectionState.value)
        assertEquals(ConfigStatus.Ready, state.configStatus)
        assertTrue(state.synced)
        val config = checkNotNull(state.config)
        assertEquals(rig.controller.home.outputs.size, config.outputs.size)
        // Wyjścia włączone na starcie w domu demonstracyjnym: Sufit w salonie, Blat w kuchni, Lustro w łazience.
        assertEquals(setOf(OutputKey(1, 0), OutputKey(1, 5), OutputKey(1, 11)), state.outputsOn)
        assertEquals(4, state.zones.size)
        assertEquals(HeatingProfile.Comfort, state.zones.getValue(zone(1, 0)).profile)
        assertEquals(22.0f, state.zones.getValue(zone(1, 0)).setpoint)
        assertEquals(HeatingProfile.Eco, state.zones.getValue(zone(1, 1)).profile)
        assertEquals(HeatingProfile.Off, state.zones.getValue(zone(2, 0)).profile)
        assertTrue(state.pendingOutputs.isEmpty() && state.pendingProfiles.isEmpty() && state.pendingSetpoints.isEmpty())
    }

    @Test
    fun zonesAreAddressedByModuleAndNumber() = runTest {
        val rig = rig()
        // Strefa 0 istnieje na obu modułach (Salon i Gabinet) i nie wolno ich pomylić.
        assertEquals(HeatingProfile.Comfort, rig.state.zones.getValue(zone(1, 0)).profile)
        assertEquals(HeatingProfile.Off, rig.state.zones.getValue(zone(2, 0)).profile)
        assertEquals(rig.controller.heaterState(2, 0)?.setpoint, rig.state.zones.getValue(zone(2, 0)).setpoint)
    }

    @Test
    fun theCachedConfigurationIsShownBeforeTheConnectionIsReady() = runTest {
        val cache = InMemoryConfigCache()
        rig(cache = cache) // pierwsze uruchomienie pobiera bazę i zapisuje ją w cache

        val slow = DemoNetwork(minLatency = 0.milliseconds, maxLatency = 0.milliseconds, connectDelay = 10.seconds)
        val second = rig(network = slow, cache = cache)

        assertTrue(second.state.config != null, "konfiguracja z pamięci podręcznej od razu")
        assertEquals(ConfigStatus.Ready, second.state.configStatus)
        assertFalse(second.state.synced)
        assertEquals(ConnectionState.Connecting(0), second.repository.connectionState.value)

        advanceTimeBy(10.seconds + 1.milliseconds)
        runCurrent()
        assertTrue(second.state.synced)
        assertEquals(4, second.state.zones.size)
    }

    @Test
    fun withoutAnyConfigurationAnUnreachableControllerLeavesTheStatusLoading() = runTest {
        val rig = rig(start = false)
        rig.controller.isReachable = false
        rig.repository.start()
        runCurrent()

        assertNull(rig.state.config)
        assertEquals(ConfigStatus.Loading, rig.state.configStatus)
        val waiting = rig.repository.connectionState.value as ConnectionState.WaitingToReconnect
        assertEquals(ConnectionProblem.HOST_UNREACHABLE, waiting.problem)

        // Sterownik wraca – następna próba połączenia kończy się powodzeniem i pełną synchronizacją.
        rig.controller.isReachable = true
        advanceTimeBy(waiting.delay + 1.milliseconds)
        runCurrent()
        assertEquals(ConnectionState.Ready, rig.repository.connectionState.value)
        assertTrue(rig.state.synced)
        assertNotNull(rig.state.config)
    }

    @Test
    fun retryReconnectsAfterTheConnectionGaveUp() = runTest {
        val rig = rig(start = false, connectionConfig = ConnectionConfig(maxReconnectAttempts = 0))
        rig.controller.isReachable = false
        rig.repository.start()
        runCurrent()
        assertTrue(rig.repository.connectionState.value is ConnectionState.Failed)

        rig.controller.isReachable = true
        rig.repository.retry()
        runCurrent()
        assertEquals(ConnectionState.Ready, rig.repository.connectionState.value)
        assertTrue(rig.state.synced)
    }

    @Test
    fun afterTheConnectionDropsTheStateIsReadAgainIncludingMissedChanges() = runTest {
        val rig = rig()
        assertFalse(OutputKey(1, 2) in rig.state.outputsOn)

        rig.controller.dropConnections()
        runCurrent()
        assertFalse(rig.state.synced)
        // Zmiana w czasie przerwy nie dociera zdarzeniem – musi wyjść ze stanu zbiorczego po ponownym połączeniu.
        rig.controller.switchOutputExternally(1, 2, true)
        runCurrent()
        assertFalse(OutputKey(1, 2) in rig.state.outputsOn)

        advanceTimeBy(3.seconds)
        runCurrent()
        assertEquals(ConnectionState.Ready, rig.repository.connectionState.value)
        assertTrue(rig.state.synced)
        assertTrue(OutputKey(1, 2) in rig.state.outputsOn)
    }

    // Światła

    @Test
    fun theTileShowsThePendingStateUntilTheControllerConfirms() = runTest {
        val rig = rig(network = latency100)
        advanceTimeBy(5.seconds)
        runCurrent()
        val key = OutputKey(1, 1)
        assertFalse(key in rig.state.outputsOn)

        rig.repository.toggleOutput(1, 1)
        assertEquals(mapOf(key to true), rig.state.pendingOutputs)
        assertFalse(key in rig.state.outputsOn)

        advanceTimeBy(1.seconds)
        runCurrent()
        assertTrue(rig.state.pendingOutputs.isEmpty())
        assertTrue(key in rig.state.outputsOn)
        assertTrue(rig.controller.isOutputOn(1, 1))

        rig.repository.toggleOutput(1, 1)
        advanceTimeBy(1.seconds)
        runCurrent()
        assertFalse(key in rig.state.outputsOn)
        assertFalse(rig.controller.isOutputOn(1, 1))
        assertTrue(rig.events.isEmpty())
    }

    @Test
    fun aSecondTapDuringTheCommandIsIgnored() = runTest {
        val rig = rig(network = latency100)
        advanceTimeBy(5.seconds)
        runCurrent()

        rig.repository.toggleOutput(1, 1)
        rig.repository.toggleOutput(1, 1)
        advanceTimeBy(1.seconds)
        runCurrent()
        assertTrue(rig.controller.isOutputOn(1, 1))
        assertTrue(OutputKey(1, 1) in rig.state.outputsOn)
    }

    @Test
    fun aWallSwitchChangesTheStateThroughEvents() = runTest {
        val rig = rig()
        assertTrue(OutputKey(1, 0) in rig.state.outputsOn)

        rig.controller.switchOutputExternally(1, 0, false)
        runCurrent()
        assertFalse(OutputKey(1, 0) in rig.state.outputsOn)
        rig.controller.switchOutputExternally(1, 3, true)
        runCurrent()
        assertTrue(OutputKey(1, 3) in rig.state.outputsOn)
    }

    @Test
    fun aFailedCommandRevertsTheTileAndReportsTheProblem() = runTest {
        val rig = rig()
        rig.controller.isReachable = false
        rig.controller.dropConnections()
        runCurrent()
        assertTrue(rig.repository.connectionState.value is ConnectionState.WaitingToReconnect)

        rig.repository.toggleOutput(1, 1)
        runCurrent()

        assertFalse(OutputKey(1, 1) in rig.state.outputsOn)
        assertTrue(rig.state.pendingOutputs.isEmpty())
        val event = rig.events.single() as HomeEvent.CommandFailed
        assertEquals(CommandFailureKind.NOT_CONNECTED, event.kind)
        assertEquals(rig.controller.home.outputs.first { it.relay == 1 && it.module == 1 }.name, event.subject)
    }

    // Rolety

    private fun shutter(module: Int, outputNr: Int) = ShutterKey(module, outputNr)

    @Test
    fun shuttersAreInTheConfigurationButTheirStateIsUnknownUntilTheyMove() = runTest {
        val rig = rig()
        val config = checkNotNull(rig.state.config)
        assertEquals(rig.controller.home.shutters.size, config.shutters.size)
        assertTrue(config.shutters.isNotEmpty())
        // Sterownik nie pozwala zapytać o stan rolety (0x94 niewspierane, stan zbiorczy zawsze 0).
        assertTrue(rig.state.shutters.isEmpty())
        assertTrue(rig.state.pendingShutters.isEmpty())
    }

    @Test
    fun theShutterShowsThePressedButtonUntilTheControllerReportsTheMovement() = runTest {
        val rig = rig(network = latency100)
        advanceTimeBy(5.seconds)
        runCurrent()
        val key = shutter(1, 0)

        rig.repository.pressShutter(1, 0, ShutterButton.UP)
        assertEquals(mapOf(key to ShutterButton.UP), rig.state.pendingShutters)
        assertTrue(rig.state.shutters.isEmpty(), "nic nie jest zakładane z góry")

        advanceTimeBy(1.seconds)
        runCurrent()
        assertTrue(rig.state.pendingShutters.isEmpty())
        assertEquals(mapOf(key to ShutterDirection.MOVING_UP), rig.state.shutters)
        assertEquals(ShutterDirection.MOVING_UP, rig.controller.shutterDirection(1, 0))
        assertTrue(rig.events.isEmpty())
    }

    @Test
    fun aSecondTapOnTheSameShutterDuringTheCommandIsIgnored() = runTest {
        val rig = rig(network = latency100)
        advanceTimeBy(5.seconds)
        runCurrent()

        rig.repository.pressShutter(1, 0, ShutterButton.UP)
        rig.repository.pressShutter(1, 0, ShutterButton.UP) // podwójne dotknięcie nie może zatrzymać rolety od razu po starcie
        rig.repository.pressShutter(1, 1, ShutterButton.DOWN) // inna roleta jest niezależna
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(ShutterDirection.MOVING_UP, rig.controller.shutterDirection(1, 0))
        assertEquals(ShutterDirection.MOVING_DOWN, rig.controller.shutterDirection(1, 1))
        assertEquals(ShutterDirection.MOVING_UP, rig.state.shutters.getValue(shutter(1, 0)))
        assertEquals(ShutterDirection.MOVING_DOWN, rig.state.shutters.getValue(shutter(1, 1)))
    }

    @Test
    fun aDirectionButtonDuringMotionStopsTheShutterAndTheStateFollowsTheController() = runTest {
        val rig = rig()
        val key = shutter(1, 0)
        rig.repository.pressShutter(1, 0, ShutterButton.UP)
        runCurrent()
        assertEquals(ShutterDirection.MOVING_UP, rig.state.shutters.getValue(key))

        // Firmware: kierunek w czasie ruchu (ten sam albo przeciwny) tylko zatrzymuje – aplikacja tego nie ukrywa.
        rig.repository.pressShutter(1, 0, ShutterButton.DOWN)
        runCurrent()
        assertEquals(ShutterDirection.STOPPED, rig.state.shutters.getValue(key))

        rig.repository.pressShutter(1, 0, ShutterButton.DOWN)
        runCurrent()
        assertEquals(ShutterDirection.MOVING_DOWN, rig.state.shutters.getValue(key))
        rig.repository.pressShutter(1, 0, ShutterButton.STOP)
        runCurrent()
        assertEquals(ShutterDirection.STOPPED, rig.state.shutters.getValue(key))
        assertTrue(rig.events.isEmpty())
    }

    @Test
    fun aShutterThatArrivesByItselfIsShownAsOpenOrClosed() = runTest {
        val rig = rig()
        rig.repository.pressShutter(1, 0, ShutterButton.UP)
        rig.repository.pressShutter(1, 1, ShutterButton.DOWN)
        runCurrent()
        advanceTimeBy(19.seconds)
        runCurrent()
        assertEquals(ShutterDirection.MOVING_UP, rig.state.shutters.getValue(shutter(1, 0)))

        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(ShutterDirection.AT_TOP, rig.state.shutters.getValue(shutter(1, 0)))
        assertEquals(ShutterDirection.AT_BOTTOM, rig.state.shutters.getValue(shutter(1, 1)))
    }

    @Test
    fun shuttersWithTheSameNumberOnDifferentModulesAreIndependent() = runTest {
        val rig = rig()
        rig.repository.pressShutter(2, 0, ShutterButton.DOWN)
        runCurrent()
        assertEquals(mapOf(shutter(2, 0) to ShutterDirection.MOVING_DOWN), rig.state.shutters)
        assertEquals(ShutterDirection.STOPPED, rig.controller.shutterDirection(1, 0))
        // Światło o numerze 0 na module 1 się nie zmieniło (roleta nie jest wyjściem).
        assertTrue(OutputKey(1, 0) in rig.state.outputsOn)
        assertEquals(setOf(OutputKey(1, 0), OutputKey(1, 5), OutputKey(1, 11)), rig.state.outputsOn)
    }

    @Test
    fun aWallSwitchMovesTheShutterAndTheTileFollowsWithoutAnyCommand() = runTest {
        val rig = rig()
        rig.controller.pressShutterExternally(1, 3, DemoShutterButton.DOWN)
        runCurrent()
        assertEquals(ShutterDirection.MOVING_DOWN, rig.state.shutters.getValue(shutter(1, 3)))
        rig.controller.pressShutterExternally(1, 3, DemoShutterButton.STOP)
        runCurrent()
        assertEquals(ShutterDirection.STOPPED, rig.state.shutters.getValue(shutter(1, 3)))
    }

    @Test
    fun aFailedShutterCommandReportsTheProblemWithTheNameFromTheConfiguration() = runTest {
        val rig = rig()
        rig.controller.isReachable = false
        rig.controller.dropConnections()
        runCurrent()
        assertTrue(rig.repository.connectionState.value is ConnectionState.WaitingToReconnect)

        rig.repository.pressShutter(1, 1, ShutterButton.UP)
        runCurrent()

        assertTrue(rig.state.pendingShutters.isEmpty())
        assertTrue(rig.state.shutters.isEmpty())
        val event = rig.events.single() as HomeEvent.CommandFailed
        assertEquals(CommandFailureKind.NOT_CONNECTED, event.kind)
        assertEquals(rig.controller.home.shutters.first { it.module == 1 && it.outputNr == 1 }.name, event.subject)
    }

    @Test
    fun anAcknowledgedCommandWithoutAnEventLeavesTheStateUnknown() = runTest {
        val rig = rig()
        // Roleta 1:9 nie istnieje w domu demonstracyjnym: sterownik potwierdza (ACK), ale nie zgłasza zdarzenia.
        rig.repository.pressShutter(1, 9, ShutterButton.UP)
        runCurrent()
        advanceTimeBy(5.seconds)
        runCurrent()
        assertTrue(rig.state.pendingShutters.isEmpty())
        assertTrue(rig.state.shutters.isEmpty())
        assertTrue(rig.events.isEmpty(), "ACK to przyjęcie polecenia, nie błąd")
    }

    @Test
    fun whenTheConnectionDropsAMovingShutterBecomesUnknownButArrivedOnesStay() = runTest {
        val rig = rig()
        rig.repository.pressShutter(1, 0, ShutterButton.UP) // będzie jechać
        rig.repository.pressShutter(1, 1, ShutterButton.DOWN)
        runCurrent()
        advanceTimeBy(25.seconds)
        runCurrent()
        rig.repository.pressShutter(1, 1, ShutterButton.UP) // ta znowu rusza
        rig.repository.pressShutter(1, 2, ShutterButton.UP)
        runCurrent()
        assertEquals(ShutterDirection.AT_TOP, rig.state.shutters.getValue(shutter(1, 0)))
        assertEquals(ShutterDirection.MOVING_UP, rig.state.shutters.getValue(shutter(1, 1)))

        rig.controller.dropConnections()
        runCurrent()
        assertEquals(mapOf(shutter(1, 0) to ShutterDirection.AT_TOP), rig.state.shutters)
    }

    // Ogrzewanie

    @Test
    fun theSetpointFollowsTapsImmediatelyAndIsSentOnceAfterAQuietPeriod() = runTest {
        val rig = rig(network = latency100)
        advanceTimeBy(5.seconds)
        runCurrent()
        val key = zone(1, 0)

        repeat(3) { rig.repository.adjustSetpoint(1, 0, +1) }
        runCurrent()
        assertEquals(23.5f, rig.state.pendingSetpoints.getValue(key).target)
        assertEquals(22.0f, rig.state.zones.getValue(key).setpoint) // kontroler jeszcze nic nie wie
        assertEquals(22.0f, rig.controller.heaterState(1, 0)?.setpoint)

        advanceTimeBy(5.seconds)
        runCurrent()
        assertTrue(rig.state.pendingSetpoints.isEmpty())
        assertEquals(23.5f, rig.controller.heaterState(1, 0)?.setpoint)
        assertEquals(23.5f, rig.state.zones.getValue(key).setpoint)
        assertEquals(HeatingProfile.Comfort, rig.state.zones.getValue(key).profile)
        assertTrue(rig.events.isEmpty())
    }

    @Test
    fun theSetpointStaysWithinTheAllowedRange() = runTest {
        val rig = rig()
        repeat(80) { rig.repository.adjustSetpoint(1, 0, +1) }
        runCurrent()
        assertEquals(30.0f, rig.state.pendingSetpoints.getValue(zone(1, 0)).target)
        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals(30.0f, rig.controller.heaterState(1, 0)?.setpoint)
    }

    @Test
    fun plusOnASwitchedOffZoneTurnsItOnWithItsComfortSetpoint() = runTest {
        val rig = rig()
        val key = zone(2, 0)
        assertEquals(HeatingProfile.Off, rig.state.zones.getValue(key).profile)
        val comfort = rig.controller.home.zones.first { it.module == 2 && it.outputNr == 0 }.comfort

        rig.repository.adjustSetpoint(2, 0, +1)
        advanceTimeBy(5.seconds)
        runCurrent()

        val zone = rig.state.zones.getValue(key)
        assertEquals(HeatingProfile.Comfort, zone.profile)
        assertEquals(comfort, zone.setpoint) // żądana wartość nie nadpisała temperatury profilu Komfort
        assertEquals(comfort, rig.controller.heaterState(2, 0)?.setpoint)
        assertTrue(rig.state.pendingSetpoints.isEmpty())
        assertTrue(rig.events.isEmpty())
    }

    @Test
    fun minusOnASwitchedOffZoneDoesNothing() = runTest {
        val rig = rig()
        rig.repository.adjustSetpoint(2, 0, -1)
        advanceTimeBy(5.seconds)
        runCurrent()
        assertTrue(rig.state.pendingSetpoints.isEmpty())
        assertEquals(HeatingProfile.Off, rig.controller.heaterState(2, 0)?.profile)
    }

    @Test
    fun theProfileArrowsCycleThroughTheProfiles() = runTest {
        val rig = rig(network = latency100)
        advanceTimeBy(5.seconds)
        runCurrent()
        val key = zone(1, 0)
        val maximum = rig.controller.home.zones.first { it.module == 1 && it.outputNr == 0 }.maximum

        rig.repository.stepProfile(1, 0, forward = true)
        assertEquals(HeatingProfile.Maximum, rig.state.pendingProfiles.getValue(key))
        assertEquals(HeatingProfile.Comfort, rig.state.zones.getValue(key).profile)

        advanceTimeBy(2.seconds)
        runCurrent()
        assertTrue(rig.state.pendingProfiles.isEmpty())
        assertEquals(HeatingProfile.Maximum, rig.state.zones.getValue(key).profile)
        assertEquals(maximum, rig.state.zones.getValue(key).setpoint)

        // Dalej do przodu zawija się do „Wyłączone”, a wstecz z „Wyłączone” wraca do „Maks.”.
        rig.repository.stepProfile(1, 0, forward = true)
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(HeatingProfile.Off, rig.state.zones.getValue(key).profile)
        rig.repository.stepProfile(1, 0, forward = false)
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(HeatingProfile.Maximum, rig.state.zones.getValue(key).profile)
        assertTrue(rig.events.isEmpty())
    }

    @Test
    fun aChosenProfileIsSetDirectlyWithoutGoingThroughTheOthers() = runTest {
        val rig = rig(network = latency100)
        advanceTimeBy(5.seconds)
        runCurrent()
        val key = zone(1, 0)

        // Komfort → Eko (okno strefy): jedno polecenie, bez zatrzymania na profilach pośrednich.
        rig.repository.setProfile(1, 0, HeatingProfile.Eco)
        assertEquals(HeatingProfile.Eco, rig.state.pendingProfiles.getValue(key))
        assertEquals(HeatingProfile.Comfort, rig.state.zones.getValue(key).profile)
        advanceTimeBy(2.seconds)
        runCurrent()
        assertTrue(rig.state.pendingProfiles.isEmpty())
        assertEquals(HeatingProfile.Eco, rig.state.zones.getValue(key).profile)
        assertEquals(HeatingProfile.Eco, rig.controller.heaterState(1, 0)?.profile)

        // Eko → Wyłączone → Maks. – także przez „koniec” listy, bo to wybór, a nie przesunięcie o jeden.
        rig.repository.setProfile(1, 0, HeatingProfile.Off)
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(HeatingProfile.Off, rig.state.zones.getValue(key).profile)
        rig.repository.setProfile(1, 0, HeatingProfile.Maximum)
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(HeatingProfile.Maximum, rig.state.zones.getValue(key).profile)
        assertTrue(rig.events.isEmpty())
    }

    @Test
    fun settingTheCurrentProfileOrAnUnknownOneDoesNothing() = runTest {
        val rig = rig(network = latency100)
        advanceTimeBy(5.seconds)
        runCurrent()

        rig.repository.setProfile(1, 0, HeatingProfile.Comfort) // już Komfort
        rig.repository.setProfile(1, 0, HeatingProfile.Unknown(9u))
        rig.repository.setProfile(7, 7, HeatingProfile.Eco) // strefa, której nie ma
        assertTrue(rig.state.pendingProfiles.isEmpty())
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(HeatingProfile.Comfort, rig.state.zones.getValue(zone(1, 0)).profile)
        assertTrue(rig.events.isEmpty())
    }

    @Test
    fun aProfileChangeIsIgnoredWhileAnotherOneIsInProgress() = runTest {
        val rig = rig(network = latency100)
        advanceTimeBy(5.seconds)
        runCurrent()
        val key = zone(1, 0)

        rig.repository.setProfile(1, 0, HeatingProfile.Eco)
        rig.repository.setProfile(1, 0, HeatingProfile.Maximum) // zignorowane: poprzednia zmiana jeszcze trwa
        assertEquals(HeatingProfile.Eco, rig.state.pendingProfiles.getValue(key))
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(HeatingProfile.Eco, rig.state.zones.getValue(key).profile)
    }

    @Test
    fun aTemperatureReportRefreshesTheZones() = runTest {
        val rig = rig()
        advanceTimeBy(1.seconds) // koniec odczytu temperatur z synchronizacji (jego raporty nie odświeżają stref)
        runCurrent()
        val key = zone(1, 1)
        val before = rig.state.zones.getValue(key).currentTemperature

        rig.controller.setZoneTemperature(1, 1, 25.0f)
        rig.controller.advance(1.seconds) // sterownik wysyła raport 0x72 / 0x130 czujnika strefy
        advanceTimeBy(3.seconds)
        runCurrent()

        assertTrue(before != 25.0f)
        // symulacja zdążyła lekko ogrzać strefę, więc porównanie z tolerancją
        assertEquals(25.0f, rig.state.zones.getValue(key).currentTemperature, 0.1f)
    }

    @Test
    fun zonesAreRefreshedPeriodicallyEvenWithoutReports() = runTest {
        val rig = rig()
        // Zmiana bez raportu (symuluje utracone wiadomości): tylko okresowe odświeżenie ją zauważy.
        rig.controller.setZoneTemperature(1, 1, 18.0f)
        advanceTimeBy(30.seconds)
        runCurrent()
        assertTrue(rig.state.zones.getValue(zone(1, 1)).currentTemperature != 18.0f)

        advanceTimeBy(40.seconds)
        runCurrent()
        assertEquals(18.0f, rig.state.zones.getValue(zone(1, 1)).currentTemperature)
    }

    // Czyste funkcje

    @Test
    fun adjacentProfileFollowsTheTileOrder() {
        val next = { p: HeatingProfile -> HomeRepository.adjacentProfile(p, forward = true) }
        val previous = { p: HeatingProfile -> HomeRepository.adjacentProfile(p, forward = false) }
        assertEquals(HeatingProfile.Eco, next(HeatingProfile.Off))
        assertEquals(HeatingProfile.Comfort, next(HeatingProfile.Eco))
        assertEquals(HeatingProfile.Maximum, next(HeatingProfile.Comfort))
        assertEquals(HeatingProfile.Off, next(HeatingProfile.Maximum))
        assertEquals(HeatingProfile.Maximum, previous(HeatingProfile.Off))
        assertEquals(HeatingProfile.Off, previous(HeatingProfile.Eco))
        assertEquals(HeatingProfile.Off, next(HeatingProfile.Unknown(9u)))
        assertEquals(HeatingProfile.Off, previous(HeatingProfile.Unknown(9u)))
    }
}
