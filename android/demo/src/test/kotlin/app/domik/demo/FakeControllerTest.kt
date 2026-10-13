@file:OptIn(ExperimentalCoroutinesApi::class)

package app.domik.demo

import app.domik.deimic.client.Connection
import app.domik.deimic.client.ConnectionProblem
import app.domik.deimic.client.ConnectionState
import app.domik.deimic.client.ConnectionTester
import app.domik.deimic.client.commands.CommandOutcome
import app.domik.deimic.config.ConfigDownloader
import app.domik.deimic.config.ConfigSource
import app.domik.deimic.config.ConfigStore
import app.domik.deimic.config.HomeConfigParser
import app.domik.deimic.config.InMemoryConfigCache
import app.domik.deimic.protocol.CommandCode
import app.domik.deimic.protocol.ControllerMessage
import app.domik.deimic.protocol.FirmwareVersion
import app.domik.deimic.protocol.HeaterState
import app.domik.deimic.protocol.HeaterFlag
import app.domik.deimic.protocol.HeatingProfile
import app.domik.deimic.protocol.OutputEvent
import app.domik.deimic.protocol.ShutterDirection
import app.domik.deimic.protocol.ShutterState
import app.domik.deimic.protocol.Request
import app.domik.deimic.protocol.StateFormat
import app.domik.deimic.protocol.StateRequestVariant
import app.domik.deimic.protocol.StateSnapshot
import app.domik.deimic.protocol.TemperatureReport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class FakeControllerTest {
    private suspend fun DemoRig.zoneState(module: Int, outputNr: Int): HeaterState {
        val result = connection.request(
            Request.heaterState(connection.serial, module.toUInt(), outputNr.toUByte()),
            setOf(CommandCode.HEATER_STATE),
        )
        return ControllerMessage.decode(result.responses.first()) as HeaterState
    }

    private suspend fun DemoRig.snapshot(variant: StateRequestVariant = StateRequestVariant.NEW): StateSnapshot {
        val expected = if (variant == StateRequestVariant.NEW) CommandCode.STATE_NEW else CommandCode.GET_STATE
        val result = connection.request(Request.getState(connection.serial, variant), setOf(expected))
        return ControllerMessage.decode(result.responses.first()) as StateSnapshot
    }

    // Connection, version, configuration

    @Test
    fun theConnectionBecomesReadyAndStaysUpWithKeepalive() = runTest {
        val rig = demoRig()
        advanceTimeBy(2.minutes)
        assertEquals(ConnectionState.Ready, rig.connection.state.value)
        assertEquals(rig.controller.serial, rig.connection.serial)
    }

    @Test
    fun theConnectionTesterPassesAgainstTheDemoController() = runTest {
        val controller = FakeController(backgroundScope, simulation = DemoSimulation.Manual)
        val report = ConnectionTester(controller.transportFactory).run(controller.endpoint, controller.serial)
        assertTrue(report.succeeded, report.toString())
        assertEquals(FakeController.FIRMWARE_VERSION, report.firmwareVersion)
        assertEquals(controller.database.checksum, report.configChecksum?.value)
    }

    @Test
    fun configStoreDownloadsTheFictionalHomeAndThenUsesTheCache() = runTest {
        val rig = demoRig(network = DemoNetwork())
        val store = ConfigStore(
            rig.connection, rig.controller.profile, InMemoryConfigCache(), HomeConfigParser(),
            ConfigDownloader(rig.connection), StandardTestDispatcher(testScheduler),
        )

        val first = store.load()
        assertEquals(ConfigSource.CONTROLLER, first.source)
        assertEquals(HomeConfigParser().parse(rig.controller.database.bytes()), first.config)
        assertEquals(rig.controller.database.checksum, first.checksum.value)
        assertEquals(rig.controller.home.outputs.size, first.config.outputs.size)

        val second = store.load()
        assertEquals(ConfigSource.CACHE, second.source)
        assertEquals(first.config, second.config)
    }

    @Test
    fun theDownloadArrivesInSeveralPiecesAndIsStillReassembled() = runTest {
        val rig = demoRig(network = DemoNetwork.Instant.copy(downloadChunkBytes = 100))
        val downloaded = ConfigDownloader(rig.connection).download()
        assertEquals(ConfigDownloader.DATABASE_PATH, DemoDatabase.PATH)
        assertTrue(downloaded.bytes.contentEquals(rig.controller.database.bytes()))
    }

    // Lights

    @Test
    fun switchingALightOnAndOffIsConfirmedByEventsAndVisibleInTheState() = runTest {
        val rig = demoRig()
        assertFalse(rig.controller.isOutputOn(1, 1))

        assertEquals(CommandOutcome.Confirmed, rig.commands.setOutput(1, 1, true))
        assertTrue(rig.controller.isOutputOn(1, 1))
        assertTrue(rig.messages.contains(OutputEvent(1u, 1u, true)))
        assertTrue(1 in checkNotNull(rig.snapshot().module(1u)).onOutputs)

        assertEquals(CommandOutcome.Confirmed, rig.commands.setOutput(1, 1, false))
        assertFalse(rig.controller.isOutputOn(1, 1))
        assertTrue(rig.messages.contains(OutputEvent(1u, 1u, false)))
        assertFalse(1 in checkNotNull(rig.snapshot().module(1u)).onOutputs)
    }

    @Test
    fun switchingAnOutputThatAlreadyHasThatStateIsOnlyAcknowledgedWithoutAnEvent() = runTest {
        val rig = demoRig()
        // Light 1:0 is on from the start, light 1:1 is off.
        assertTrue(rig.controller.isOutputOn(1, 0))
        val sizeBefore = rig.messages.size

        // No event comes; DeviceCommands finds the output in the state snapshot and still reports it as confirmed.
        assertEquals(CommandOutcome.Confirmed, rig.commands.setOutput(1, 0, true))
        assertEquals(CommandOutcome.Confirmed, rig.commands.setOutput(1, 1, false))
        runCurrent()

        val received = rig.messages.drop(sizeBefore)
        assertTrue(received.none { it is OutputEvent }, "no event without a change: $received")
        assertTrue(rig.controller.isOutputOn(1, 0))
        assertFalse(rig.controller.isOutputOn(1, 1))
    }

    @Test
    fun aWallSwitchPressedToTheStateTheOutputAlreadyHasSendsNoEvent() = runTest {
        val rig = demoRig()
        val sizeBefore = rig.messages.size
        assertTrue(rig.controller.switchOutputExternally(1, 0, true), "the relay exists, it is just on already")
        runCurrent()
        assertTrue(rig.messages.drop(sizeBefore).none { it is OutputEvent })
    }

    @Test
    fun lightsOfTheSecondModuleAreIndependentOfTheFirst() = runTest {
        val rig = demoRig()
        assertEquals(CommandOutcome.Confirmed, rig.commands.setOutput(2, 0, true))
        assertTrue(rig.controller.isOutputOn(2, 0))
        assertTrue(rig.controller.isOutputOn(1, 0), "light 1:0 is on from the start and untouched")
        val snapshot = rig.snapshot()
        assertEquals(listOf(1u, 2u), snapshot.modules.map { it.module })
        assertEquals(listOf(0), checkNotNull(snapshot.module(2u)).onOutputs)
        assertEquals(listOf(0, 5, 11), checkNotNull(snapshot.module(1u)).onOutputs)
    }

    @Test
    fun aWallSwitchIsAnnouncedWithoutAnyRequest() = runTest {
        val rig = demoRig()
        assertTrue(rig.controller.switchOutputExternally(1, 2, true))
        runCurrent()
        assertTrue(rig.messages.contains(OutputEvent(1u, 2u, true)))
        assertTrue(rig.controller.isOutputOn(1, 2))
        assertFalse(rig.controller.switchOutputExternally(9, 0, true), "no such module")
    }

    @Test
    fun aRelayOfAnUnknownModuleIsOnlyAcknowledged() = runTest {
        val rig = demoRig()
        assertEquals(CommandOutcome.AckOnly, rig.commands.setOutput(9, 0, true))
        assertFalse(rig.controller.isOutputOn(9, 0))
    }

    // Shutters

    private fun DemoRig.shutterEvents(module: Int = 1, outputNr: Int = 0): List<ShutterDirection> =
        messages.filterIsInstance<ShutterState>().filter { it.module == module.toUInt() && it.outputNr.toInt() == outputNr }
            .map { it.direction }

    @Test
    fun shutterUpStartsTheShutterAndTheEventAnnouncesIt() = runTest {
        val rig = demoRig()
        assertEquals(ShutterDirection.STOPPED, rig.controller.shutterDirection(1, 0))
        assertEquals(CommandOutcome.Confirmed, rig.commands.shutterUp(1, 0))
        runCurrent()
        assertEquals(ShutterDirection.MOVING_UP, rig.controller.shutterDirection(1, 0))
        assertEquals(listOf(ShutterDirection.MOVING_UP), rig.shutterEvents())
        val event = rig.messages.filterIsInstance<ShutterState>().single()
        assertEquals(1u, event.module)
        assertEquals(0.toUByte(), event.outputNr)
        assertEquals(null, event.positionPercent, "the firmware reports no position")
        // The acknowledgement came first, like on the real controller.
        val ack = rig.messages.indexOfFirst { it is app.domik.deimic.protocol.Ack }
        assertTrue(ack in 0 until rig.messages.indexOf(event))
    }

    @Test
    fun aDirectionCommandDuringMotionStopsTheShutterInsteadOfReversingIt() = runTest {
        val rig = demoRig()
        assertEquals(CommandOutcome.Confirmed, rig.commands.shutterDown(1, 1))
        assertEquals(CommandOutcome.Confirmed, rig.commands.shutterUp(1, 1)) // opposite direction while moving
        assertEquals(ShutterDirection.STOPPED, rig.controller.shutterDirection(1, 1))
        assertEquals(CommandOutcome.Confirmed, rig.commands.shutterUp(1, 1))
        assertEquals(CommandOutcome.Confirmed, rig.commands.shutterUp(1, 1)) // same direction while moving
        assertEquals(ShutterDirection.STOPPED, rig.controller.shutterDirection(1, 1))
        runCurrent()
        assertEquals(
            listOf(
                ShutterDirection.MOVING_DOWN, ShutterDirection.STOPPED, ShutterDirection.MOVING_UP, ShutterDirection.STOPPED,
            ),
            rig.shutterEvents(1, 1),
        )
    }

    @Test
    fun shutterStopStopsAMovingShutterAndTheShutterCanThenGoTheOtherWay() = runTest {
        val rig = demoRig()
        rig.commands.shutterUp(1, 2)
        advanceTimeBy(5.seconds)
        assertEquals(CommandOutcome.Confirmed, rig.commands.shutterStop(1, 2))
        assertEquals(ShutterDirection.STOPPED, rig.controller.shutterDirection(1, 2))
        assertEquals(CommandOutcome.Confirmed, rig.commands.shutterDown(1, 2))
        assertEquals(ShutterDirection.MOVING_DOWN, rig.controller.shutterDirection(1, 2))
        runCurrent()
        assertEquals(
            listOf(ShutterDirection.MOVING_UP, ShutterDirection.STOPPED, ShutterDirection.MOVING_DOWN),
            rig.shutterEvents(1, 2),
        )
    }

    @Test
    fun aMovingShutterArrivesByItselfAfterTheTravelTime() = runTest {
        val rig = demoRig(simulation = DemoSimulation.Manual.copy(shutterTravel = 20.seconds))
        rig.commands.shutterUp(1, 0)
        rig.commands.shutterDown(1, 1)
        advanceTimeBy(19.seconds)
        runCurrent()
        assertEquals(ShutterDirection.MOVING_UP, rig.controller.shutterDirection(1, 0))
        assertEquals(listOf(ShutterDirection.MOVING_UP), rig.shutterEvents(1, 0))

        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(ShutterDirection.AT_TOP, rig.controller.shutterDirection(1, 0))
        assertEquals(ShutterDirection.AT_BOTTOM, rig.controller.shutterDirection(1, 1))
        assertEquals(listOf(ShutterDirection.MOVING_UP, ShutterDirection.AT_TOP), rig.shutterEvents(1, 0))
        assertEquals(listOf(ShutterDirection.MOVING_DOWN, ShutterDirection.AT_BOTTOM), rig.shutterEvents(1, 1))
    }

    @Test
    fun aStoppedShutterDoesNotArriveAndANewRunRestartsTheTravelTime() = runTest {
        val rig = demoRig(simulation = DemoSimulation.Manual.copy(shutterTravel = 20.seconds))
        rig.commands.shutterUp(1, 0)
        advanceTimeBy(10.seconds)
        rig.commands.shutterStop(1, 0)
        advanceTimeBy(30.seconds)
        runCurrent()
        assertEquals(ShutterDirection.STOPPED, rig.controller.shutterDirection(1, 0))
        assertEquals(listOf(ShutterDirection.MOVING_UP, ShutterDirection.STOPPED), rig.shutterEvents(1, 0))

        rig.commands.shutterDown(1, 0)
        advanceTimeBy(15.seconds)
        assertEquals(ShutterDirection.MOVING_DOWN, rig.controller.shutterDirection(1, 0))
        advanceTimeBy(6.seconds)
        runCurrent()
        assertEquals(ShutterDirection.AT_BOTTOM, rig.controller.shutterDirection(1, 0))
        assertEquals(
            listOf(ShutterDirection.MOVING_UP, ShutterDirection.STOPPED, ShutterDirection.MOVING_DOWN, ShutterDirection.AT_BOTTOM),
            rig.shutterEvents(1, 0),
        )
    }

    @Test
    fun aPressDuringTheRunCancelsTheArrivalOfThePreviousRun() = runTest {
        val rig = demoRig(simulation = DemoSimulation.Manual.copy(shutterTravel = 20.seconds))
        rig.commands.shutterUp(1, 0)
        advanceTimeBy(15.seconds)
        rig.commands.shutterUp(1, 0) // stops
        rig.commands.shutterUp(1, 0) // starts again at t = 15 s
        advanceTimeBy(10.seconds) // t = 25 s: the first run would have arrived at 20 s
        runCurrent()
        assertEquals(ShutterDirection.MOVING_UP, rig.controller.shutterDirection(1, 0))
        advanceTimeBy(11.seconds) // t = 36 s > 15 + 20
        runCurrent()
        assertEquals(ShutterDirection.AT_TOP, rig.controller.shutterDirection(1, 0))
        assertEquals(1, rig.shutterEvents(1, 0).count { it == ShutterDirection.AT_TOP })
    }

    @Test
    fun shuttersWithTheSameNumberOnDifferentModulesAreDistinct() = runTest {
        val rig = demoRig()
        assertEquals(CommandOutcome.Confirmed, rig.commands.shutterUp(2, 0))
        runCurrent()
        assertEquals(ShutterDirection.MOVING_UP, rig.controller.shutterDirection(2, 0))
        assertEquals(ShutterDirection.STOPPED, rig.controller.shutterDirection(1, 0))
        assertEquals(listOf(ShutterDirection.MOVING_UP), rig.shutterEvents(2, 0))
        assertEquals(emptyList<ShutterDirection>(), rig.shutterEvents(1, 0))
    }

    @Test
    fun aShutterThatDoesNotExistIsOnlyAcknowledged() = runTest {
        val rig = demoRig()
        assertEquals(null, rig.controller.shutterDirection(1, 9))
        assertEquals(CommandOutcome.AckOnly, rig.commands.shutterUp(1, 9))
        assertEquals(CommandOutcome.AckOnly, rig.commands.shutterStop(9, 0))
        assertTrue(rig.messages.none { it is ShutterState })
    }

    @Test
    fun shutterRelaysAreNotOutputsAndTheCollectiveStateKnowsNothingAboutShutters() = runTest {
        val rig = demoRig()
        rig.commands.shutterUp(1, 0)
        val snapshot = rig.snapshot()
        val module = checkNotNull(snapshot.module(1u))
        // The relay pair of shutter 0 (relays 0 and 1 of the shutter, output_relay 0) is not reported as outputs, and
        // like on the real firmware the shutter states in the collective state stay 0.
        assertEquals(listOf(0, 5, 11), module.onOutputs)
        assertTrue(module.shutterStates.all { it.toInt() == 0 }, module.shutterStates.toString())
    }

    @Test
    fun aWallSwitchPressOnAShutterIsAnnouncedWithoutAnyRequest() = runTest {
        val rig = demoRig()
        assertTrue(rig.controller.pressShutterExternally(1, 3, DemoShutterButton.DOWN))
        runCurrent()
        assertEquals(listOf(ShutterDirection.MOVING_DOWN), rig.shutterEvents(1, 3))
        assertEquals(ShutterDirection.MOVING_DOWN, rig.controller.shutterDirection(1, 3))
        assertTrue(rig.controller.pressShutterExternally(1, 3, DemoShutterButton.STOP))
        runCurrent()
        assertEquals(listOf(ShutterDirection.MOVING_DOWN, ShutterDirection.STOPPED), rig.shutterEvents(1, 3))
        assertFalse(rig.controller.pressShutterExternally(1, 9, DemoShutterButton.UP), "no such shutter")
    }

    @Test
    fun theShutterEventGoesToEveryConnectedClient() = runTest {
        val rig = demoRig()
        val other = Connection(rig.controller.endpoint, rig.controller.serial, backgroundScope, rig.controller.transportFactory)
        val otherMessages = mutableListOf<ControllerMessage>()
        backgroundScope.launch { other.messages.collect { otherMessages += it } }
        other.connect()
        runCurrent()

        rig.commands.shutterUp(1, 0)
        runCurrent()
        assertEquals(ShutterDirection.MOVING_UP, otherMessages.filterIsInstance<ShutterState>().single().direction)
    }

    // Heating

    @Test
    fun aSetpointIsConfirmedAndReadBackFromTheZone() = runTest {
        val rig = demoRig()
        assertEquals(CommandOutcome.Confirmed, rig.commands.setSetpoint(1, 0, 23.0f))
        val state = rig.zoneState(1, 0)
        assertEquals(23.0f, state.setpoint)
        assertEquals(HeatingProfile.Comfort, state.profile)
        assertTrue(state.isHeating)
        assertEquals(rig.controller.heaterState(1, 0), state)
    }

    @Test
    fun theZoneStateGoesToEveryConnectedClient() = runTest {
        val rig = demoRig()
        val other = Connection(rig.controller.endpoint, rig.controller.serial, backgroundScope, rig.controller.transportFactory)
        val otherMessages = mutableListOf<ControllerMessage>()
        backgroundScope.launch { other.messages.collect { otherMessages += it } }
        other.connect()
        runCurrent()

        assertEquals(CommandOutcome.Confirmed, rig.commands.setSetpoint(1, 1, 20.5f))
        runCurrent()
        val seen = otherMessages.filterIsInstance<HeaterState>().single()
        assertEquals(20.5f, seen.setpoint)
        assertEquals(1u, seen.module)
        assertEquals(1u.toUByte(), seen.outputNr)
    }

    @Test
    fun switchingTheProfileUsesTheSetpointOfThatProfile() = runTest {
        val rig = demoRig()
        assertEquals(CommandOutcome.Confirmed, rig.commands.setProfile(1, 1, HeatingProfile.Comfort))
        val comfort = rig.zoneState(1, 1)
        assertEquals(21.0f, comfort.setpoint)
        assertTrue(comfort.isHeating, "19.2 is far below 21.0")

        assertEquals(CommandOutcome.Confirmed, rig.commands.setProfile(1, 1, HeatingProfile.Off))
        val off = rig.zoneState(1, 1)
        assertEquals(HeatingProfile.Off, off.profile)
        assertFalse(off.isHeating)
    }

    @Test
    fun tappingPlusOnAZoneThatIsOffSwitchesItToComfortWithoutWritingTheSetpoint() = runTest {
        val rig = demoRig()
        val outcome = rig.commands.adjustSetpointOrSwitchOn(2, 0, 20.0f)
        assertTrue(outcome is CommandOutcome.SwitchedToComfort, outcome.toString())
        assertEquals(21.5f, (outcome as CommandOutcome.SwitchedToComfort).state?.setpoint)
        assertEquals(HeatingProfile.Comfort, rig.controller.heaterState(2, 0)?.profile)
    }

    @Test
    fun zonesWithTheSameNumberOnDifferentModulesAreDistinct() = runTest {
        val rig = demoRig()
        assertEquals("Salon", rig.controller.home.zones.first { it.module == 1 && it.outputNr == 0 }.name)
        assertEquals(CommandOutcome.Confirmed, rig.commands.setSetpoint(2, 0, 25.0f))
        assertEquals(22.0f, rig.controller.heaterState(1, 0)?.setpoint)
        assertEquals(25.0f, rig.controller.heaterState(2, 0)?.setpoint)
    }

    @Test
    fun anUnknownZoneIsOnlyAcknowledged() = runTest {
        val rig = demoRig()
        assertNull(rig.controller.heaterState(1, 7))
        val outcome = rig.commands.setSetpoint(1, 7, 21.0f)
        assertEquals(CommandOutcome.AckOnly, outcome)
    }

    // Temperatures

    @Test
    fun aTemperatureReadoutAnswersForEverySensorWithItsRoom() = runTest {
        val rig = demoRig()
        val result = rig.connection.request(
            Request.temperatureReadout(rig.connection.serial), setOf(CommandCode.TEMPERATURE_REPORT), collectWindow = 500.milliseconds,
        )
        val reports = result.responses.map { ControllerMessage.decode(it) as TemperatureReport }
        assertEquals(
            listOf(1u to 1, 1u to 6, 1u to 7, 2u to 4, 2u to 5),
            reports.map { it.module to it.roomId.toInt() }.sortedWith(compareBy({ it.first }, { it.second })),
        )
        assertEquals(5, reports.size)
        val salon = reports.first { it.module == 1u && it.roomId.toInt() == 1 }
        assertEquals(21.4f, salon.temperature)
        assertEquals(1, salon.levelId.toInt())
        val outdoor = reports.first { it.roomId.toInt() == 5 }
        assertEquals(11.5f, outdoor.temperature)
    }

    @Test
    fun movingTimeMakesZonesHeatAndSendsTemperatureReportsOnlyForChangedSensors() = runTest {
        val rig = demoRig()
        rig.controller.advance(10.minutes)
        runCurrent()
        val reports = rig.messages.filterIsInstance<TemperatureReport>()
        assertTrue(reports.isNotEmpty())
        assertTrue(reports.none { it.roomId.toInt() == 5 }, "the outdoor sensor never changes")
        assertTrue(checkNotNull(rig.controller.temperature(1, 4)) > 21.4f)
        // Every report is followed by the analog value event, like the real controller sends them.
        assertEquals(reports.size, rig.messages.count { it is app.domik.deimic.protocol.AnalogValueChanged })
    }

    @Test
    fun theBackgroundSimulationStartsWithTheFirstConnectionAndKeepsRunning() = runTest {
        val rig = demoRig(simulation = DemoSimulation())
        val before = checkNotNull(rig.controller.temperature(1, 4))
        advanceTimeBy(5.minutes)
        runCurrent()
        val after = checkNotNull(rig.controller.temperature(1, 4))
        assertTrue(after > before, "$before -> $after")
        assertTrue(rig.messages.any { it is TemperatureReport })
        rig.controller.stop()
        val stopped = rig.controller.temperature(1, 4)
        advanceTimeBy(5.minutes)
        assertEquals(stopped, rig.controller.temperature(1, 4))
    }

    @Test
    fun forcingAZoneTemperatureSwitchesTheHeatingOn() = runTest {
        val rig = demoRig()
        assertTrue(rig.controller.setZoneTemperature(1, 0, 22.0f))
        assertFalse(checkNotNull(rig.controller.heaterState(1, 0)).isHeating)
        rig.controller.setZoneTemperature(1, 0, 18.0f)
        assertTrue(checkNotNull(rig.controller.heaterState(1, 0)).isHeating)
        assertFalse(rig.controller.setZoneTemperature(1, 9, 20.0f))
    }

    // Collective state

    @Test
    fun theCollectiveStateInTheNewFormatShowsOutputsHeatersAndTemperatures() = runTest {
        val rig = demoRig()
        val snapshot = rig.snapshot()
        assertEquals(StateFormat.NEW, snapshot.format)
        val first = checkNotNull(snapshot.module(1u))
        assertEquals(HeaterFlag.Heating, first.heaters?.get(0))
        assertEquals(HeaterFlag.Idle, first.heaters?.get(1))
        assertEquals(HeaterFlag.Heating, first.heaters?.get(2))
        assertEquals(HeaterFlag.NoZone, first.heaters?.get(3))
        // The zones' sensors sit on inputs 4, 0, 3 (module 1) - not on the zone numbers.
        assertEquals(21.4f, first.temperatures?.get(4))
        assertEquals(19.2f, first.temperatures?.get(0))
        val second = checkNotNull(snapshot.module(2u))
        assertEquals(HeaterFlag.Idle, second.heaters?.get(0))
        assertEquals(11.5f, second.temperatures?.get(1))
    }

    @Test
    fun theOlderStateFormatsAreAnsweredLikeTheRealFirmware() = runTest {
        val rig = demoRig()
        val v2 = rig.snapshot(StateRequestVariant.V2)
        assertEquals(StateFormat.V2, v2.format)
        assertEquals(listOf(1u, 2u), v2.modules.map { it.module })
        assertNull(v2.modules.first().heaters)

        val v3 = rig.connection.request(Request.getState(rig.connection.serial, StateRequestVariant.V3), setOf(CommandCode.GET_STATE))
        assertTrue((ControllerMessage.decode(v3.responses.first()) as StateSnapshot).isEmpty, "v3 is not supported by this firmware")
    }

    @Test
    fun firmwareIsReportedAsDemo() = runTest {
        val rig = demoRig()
        val result = rig.connection.request(Request.firmwareVersion(rig.connection.serial), setOf(CommandCode.FIRMWARE_VERSION))
        assertEquals("Demo", (ControllerMessage.decode(result.responses.first()) as FirmwareVersion).text)
    }

    // Timing and failures

    private suspend fun TestScope.latencies(network: DemoNetwork): List<Long> {
        val rig = demoRig(network = network)
        return List(6) {
            val start = testScheduler.currentTime
            rig.connection.request(Request.firmwareVersion(rig.connection.serial), setOf(CommandCode.FIRMWARE_VERSION))
            testScheduler.currentTime - start
        }
    }

    @Test
    fun answersComeAfterTheConfiguredLatencyAndAreRepeatable() = runTest {
        val network = DemoNetwork()
        val first = latencies(network)
        val second = latencies(network)
        assertEquals(first, second, "the same seed gives the same delays")
        assertTrue(first.all { it in 50L..150L }, first.toString())
        assertTrue(first.toSet().size > 1, "the latency varies: $first")
        assertTrue(latencies(network.copy(seed = 7)) != first)
    }

    @Test
    fun withoutNetworkDelaysAnswersAreInstant() = runTest {
        assertEquals(List(6) { 0L }, latencies(DemoNetwork.Instant))
    }

    @Test
    fun anUnreachableDemoControllerFailsLikeAnUnreachableHostAndRecovers() = runTest {
        val rig = demoRig(connect = false)
        rig.controller.isReachable = false
        rig.connection.connect()
        runCurrent()
        val waiting = rig.connection.state.value
        assertTrue(waiting is ConnectionState.WaitingToReconnect, waiting.toString())
        assertEquals(ConnectionProblem.HOST_UNREACHABLE, (waiting as ConnectionState.WaitingToReconnect).problem)

        rig.controller.isReachable = true
        advanceTimeBy(3.seconds)
        runCurrent()
        assertEquals(ConnectionState.Ready, rig.connection.state.value)
    }

    @Test
    fun droppedConnectionsAreReestablishedByTheClient() = runTest {
        val rig = demoRig()
        rig.controller.dropConnections()
        runCurrent()
        assertTrue(rig.connection.state.value is ConnectionState.WaitingToReconnect, rig.connection.state.value.toString())
        advanceTimeBy(3.seconds)
        runCurrent()
        assertEquals(ConnectionState.Ready, rig.connection.state.value)
        assertEquals(CommandOutcome.Confirmed, rig.commands.setOutput(1, 4, true))
    }

    @Test
    fun theDemoDoesNotContactAnythingReal() {
        assertEquals("demo.invalid", FakeController.HOST)
        assertEquals(0x12345678u, DemoHome.SERIAL.value)
    }
}
