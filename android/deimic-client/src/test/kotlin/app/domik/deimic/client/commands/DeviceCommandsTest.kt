@file:OptIn(ExperimentalCoroutinesApi::class)

package app.domik.deimic.client.commands

import app.domik.deimic.client.ConnectionProblem
import app.domik.deimic.client.controllerFrame
import app.domik.deimic.client.now
import app.domik.deimic.protocol.ByteReader
import app.domik.deimic.protocol.Bytes
import app.domik.deimic.protocol.CommandCode
import app.domik.deimic.protocol.Frame
import app.domik.deimic.protocol.HeatingProfile
import app.domik.deimic.protocol.ShutterDirection
import app.domik.deimic.protocol.ShutterState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class DeviceCommandsTest {
    private fun Frame.setpoint(): Float = ByteReader(payload).f32(1)

    // Request bytes

    @Test
    fun requestsAreByteIdenticalToRecordedVectors() = runTest {
        val rig = commandsRig(experimentalProfileSwitching = true)
        val calls = listOf(
            "setOutput:3:2:on" to suspend { rig.commands.setOutput(3, 2, true) },
            "setOutput:1:12:off" to suspend { rig.commands.setOutput(1, 12, false) },
            "setSetpoint:5:3:21.5" to suspend { rig.commands.setSetpoint(5, 3, 21.5f) },
            "setSetpoint:1:5:22.5" to suspend { rig.commands.setSetpoint(1, 5, 22.5f) },
            "setProfile:1:5:comfort" to suspend { rig.commands.setProfile(1, 5, HeatingProfile.Comfort) },
        )
        for ((name, call) in calls) {
            assertEquals(CommandOutcome.Confirmed, call(), name)
        }
        // Only the writes were sent: confirmation did not need extra reads.
        assertEquals(calls.size, rig.sent().size)
        for ((index, entry) in calls.withIndex()) {
            assertEquals(RequestVectors[entry.first], rig.sent()[index].encode().toHex(), entry.first)
        }
    }

    @Test
    fun setpointIsRoundedToHalfDegreeBeforeSending() = runTest {
        val rig = commandsRig()
        assertEquals(CommandOutcome.Confirmed, rig.commands.setSetpoint(1, 5, 21.3f))
        assertEquals(21.5f, rig.sent().single().setpoint())
        assertEquals(21.5f, rig.device.zone(1, 5).setpoint)

        assertEquals(CommandOutcome.Confirmed, rig.commands.setSetpoint(1, 5, 21.2f))
        assertEquals(21.0f, rig.sent().last().setpoint())
        assertEquals(CommandOutcome.Confirmed, rig.commands.setSetpoint(1, 5, 29.9f))
        assertEquals(30.0f, rig.sent().last().setpoint())
    }

    @Test
    fun roundingToStep() {
        assertEquals(21.5f, CommandLimits.roundToStep(21.3f))
        assertEquals(21.0f, CommandLimits.roundToStep(21.2f))
        assertEquals(21.5f, CommandLimits.roundToStep(21.25f))
        assertEquals(21.5f, CommandLimits.roundToStep(21.74f))
        assertEquals(22.0f, CommandLimits.roundToStep(21.75f))
        assertEquals(5.0f, CommandLimits.clampToStep(-3f))
        assertEquals(30.0f, CommandLimits.clampToStep(99f))
    }

    // Validation

    @Test
    fun invalidSetpointIsRejectedBeforeSending() = runTest {
        val rig = commandsRig()
        for (celsius in listOf(4.5f, 4.9f, 30.5f, 31f, 0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val outcome = rig.commands.setSetpoint(1, 5, celsius)
            assertTrue(outcome is CommandOutcome.Invalid, "$celsius -> $outcome")
        }
        assertEquals(emptyList<Frame>(), rig.sent())
        assertEquals(CommandOutcome.Confirmed, rig.commands.setSetpoint(1, 5, 5.0f))
        assertEquals(CommandOutcome.Confirmed, rig.commands.setSetpoint(1, 5, 30.0f))
        assertEquals(listOf(5.0f, 30.0f), rig.sent().map { it.setpoint() })
    }

    @Test
    fun invalidAddressesAreRejectedBeforeSending() = runTest {
        val rig = commandsRig(experimentalProfileSwitching = true)
        assertEquals(CommandOutcome.Confirmed, rig.commands.setOutput(1, 127, true)) // highest relay is valid
        assertEquals(CommandOutcome.Confirmed, rig.commands.setSetpoint(1, 31, 21f)) // highest zone is valid
        val validSent = rig.sent().size

        val invalid = listOf(
            rig.commands.setOutput(1, 128, true),
            rig.commands.setOutput(1, -1, true),
            rig.commands.setOutput(1, 256, false),
            rig.commands.setOutput(0, 5, true), // the controller itself
            rig.commands.setOutput(0x1FF, 5, true), // broadcast
            rig.commands.setOutput(-1, 5, true),
            rig.commands.setOutput(0x10000, 5, true),
            rig.commands.setSetpoint(1, 32, 21f),
            rig.commands.setSetpoint(1, -1, 21f),
            rig.commands.setSetpoint(0, 1, 21f),
            rig.commands.setProfile(1, 32, HeatingProfile.Eco),
            rig.commands.setProfile(1, 1, HeatingProfile.of(9u)),
            rig.commands.adjustSetpointOrSwitchOn(1, 32, 21f),
            rig.commands.adjustSetpointOrSwitchOn(1, 1, 31f),
        )
        invalid.forEachIndexed { index, outcome -> assertTrue(outcome is CommandOutcome.Invalid, "#$index $outcome") }
        assertEquals(validSent, rig.sent().size)
    }

    // setOutput

    @Test
    fun outputOnIsConfirmedByRoseEvent() = runTest {
        val rig = commandsRig()
        assertEquals(CommandOutcome.Confirmed, rig.commands.setOutput(1, 12, true))
        assertEquals(0L, now) // no waiting beyond the round trip
        assertEquals(true, rig.device.outputs[1 to 12])
        assertEquals(listOf(CommandCode.OUTPUT_ON), rig.sentCommands())
    }

    @Test
    fun outputOffIsConfirmedByFellEvent() = runTest {
        val rig = commandsRig()
        rig.device.outputs[1 to 12] = true
        assertEquals(CommandOutcome.Confirmed, rig.commands.setOutput(1, 12, false))
        assertEquals(false, rig.device.outputs[1 to 12])
        assertEquals(listOf(CommandCode.OUTPUT_OFF), rig.sentCommands())
    }

    @Test
    fun eventsOfOtherOutputsModulesOrDirectionsDoNotConfirm() = runTest {
        val rig = commandsRig(config = DeviceCommandsConfig(snapshotFallback = false))
        rig.device.sendOutputEvents = false
        val pending = async { rig.commands.setOutput(1, 12, true) }
        runCurrent()
        rig.transport.deliver(outputEvent(1, 13, true), outputEvent(2, 12, true), outputEvent(1, 12, false))
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(pending.isCompleted)

        rig.transport.deliver(outputEvent(1, 12, true))
        runCurrent()
        assertEquals(CommandOutcome.Confirmed, pending.await())
    }

    @Test
    fun acknowledgedWithoutEventIsAckOnlyWhenSnapshotFallbackIsOff() = runTest {
        val rig = commandsRig(config = DeviceCommandsConfig(snapshotFallback = false))
        rig.device.sendOutputEvents = false
        assertEquals(CommandOutcome.AckOnly, rig.commands.setOutput(1, 12, true))
        assertEquals(2_000L, now)
        assertEquals(listOf(CommandCode.OUTPUT_ON), rig.sentCommands())
    }

    @Test
    fun missingEventIsConfirmedBySnapshotWhenOutputAlreadyHadThatState() = runTest {
        val rig = commandsRig()
        rig.device.eventOnlyOnChange = true
        rig.device.outputs[1 to 12] = true
        assertEquals(CommandOutcome.Confirmed, rig.commands.setOutput(1, 12, true))
        assertEquals(listOf(CommandCode.OUTPUT_ON, CommandCode.GET_STATE), rig.sentCommands())
        assertEquals("01", rig.sent().last().payload.toHex()) // 0x202 [01] -> 0x21E
        assertEquals(2_000L, now)
    }

    @Test
    fun snapshotShowingOppositeStateIsAMismatch() = runTest {
        val rig = commandsRig()
        rig.device.applyWrites = false // acknowledges, but nothing changes
        rig.device.sendOutputEvents = false
        assertEquals(CommandOutcome.OutputMismatch(actualOn = false), rig.commands.setOutput(1, 12, true))
    }

    @Test
    fun unavailableSnapshotLeavesAckOnly() = runTest {
        val rig = commandsRig()
        rig.device.sendOutputEvents = false
        rig.device.answerReads = false
        assertEquals(CommandOutcome.AckOnly, rig.commands.setOutput(1, 12, true))
        assertEquals(5_000L, now) // 2 s for the event + 3 s for the snapshot
    }

    @Test
    fun noAcknowledgementAndNoEventIsNoAck() = runTest {
        val rig = commandsRig()
        rig.device.sendAcks = false
        rig.device.sendOutputEvents = false
        assertEquals(CommandOutcome.NoAck, rig.commands.setOutput(1, 12, true))
        assertEquals(2_000L, now)
        assertEquals(listOf(CommandCode.OUTPUT_ON), rig.sentCommands()) // no snapshot without an acknowledgement
    }

    @Test
    fun eventWithoutAcknowledgementStillConfirms() = runTest {
        val rig = commandsRig()
        rig.device.sendAcks = false
        assertEquals(CommandOutcome.Confirmed, rig.commands.setOutput(1, 12, true))
        assertEquals(500L, now) // waited only the acknowledgement grace period
    }

    @Test
    fun connectionDropWhileWaitingFails() = runTest {
        val rig = commandsRig()
        rig.device.sendAcks = false
        rig.device.sendOutputEvents = false
        val pending = async { rig.commands.setOutput(1, 12, true) }
        advanceTimeBy(1_000)
        rig.transport.closeFromPeer()
        runCurrent()
        val outcome = pending.await()
        assertTrue(outcome is CommandOutcome.Failed && outcome.problem == ConnectionProblem.CONNECTION_LOST, "$outcome")
        assertEquals(1_000L, now)
    }

    @Test
    fun notConnectedFailsWithoutSending() = runTest {
        val rig = commandsRig(connect = false)
        val outcome = rig.commands.setOutput(1, 12, true)
        assertTrue(outcome is CommandOutcome.Failed && outcome.problem == ConnectionProblem.CONNECTION_LOST, "$outcome")
        assertTrue(rig.factory.transports.isEmpty())
        assertTrue(rig.commands.setSetpoint(1, 5, 21f) is CommandOutcome.Failed)
    }

    // Shutters

    @Test
    fun shutterRequestsAreByteIdenticalToRecordedVectors() = runTest {
        val rig = commandsRig()
        // The vectors are for shutter 3 of module 1; up -> stop -> down is the order of the live test.
        assertEquals(CommandOutcome.Confirmed, rig.commands.shutterUp(1, 3))
        assertEquals(CommandOutcome.Confirmed, rig.commands.shutterStop(1, 3))
        assertEquals(CommandOutcome.Confirmed, rig.commands.shutterDown(1, 3))
        assertEquals(
            listOf("shutterUp:1:3", "shutterStop:1:3", "shutterDown:1:3").map { RequestVectors[it] },
            rig.sent().map { it.encode().toHex() },
        )
    }

    @Test
    fun shutterCommandsSendTheShutterNumberNotARelay() = runTest {
        val rig = commandsRig()
        assertEquals(CommandOutcome.Confirmed, rig.commands.shutterUp(1, 0))
        assertEquals(CommandOutcome.Confirmed, rig.commands.shutterStop(1, 0))
        assertEquals(listOf("00", "00"), rig.sent().map { it.payload.toHex() })
        assertEquals(listOf(CommandCode.SHUTTER_UP, CommandCode.SHUTTER_STOP), rig.sentCommands())
        assertEquals(emptyList<Frame>(), rig.sent().filter { it.command == CommandCode.OUTPUT_ON || it.command == CommandCode.OUTPUT_OFF })
    }

    @Test
    fun shutterUpAndDownAreConfirmedByTheStateEvent() = runTest {
        val rig = commandsRig()
        assertEquals(CommandOutcome.Confirmed, rig.commands.shutterUp(1, 3))
        assertEquals(ShutterDirection.MOVING_UP, rig.device.shutters[1 to 3])
        assertEquals(CommandOutcome.Confirmed, rig.commands.shutterStop(1, 3))
        assertEquals(CommandOutcome.Confirmed, rig.commands.shutterDown(1, 3))
        assertEquals(ShutterDirection.MOVING_DOWN, rig.device.shutters[1 to 3])
        assertEquals(0L, now) // no waiting beyond the round trip
        assertEquals(3, rig.device.writes.size)
    }

    @Test
    fun directionCommandDuringMotionStopsTheShutter() = runTest {
        // Firmware 2017 behaviour, verified live: no reversal, the command is a stop. The command layer reports
        // it as confirmed (the controller took it); the new state is in the event stream.
        val rig = commandsRig()
        assertEquals(CommandOutcome.Confirmed, rig.commands.shutterUp(1, 3))
        assertEquals(CommandOutcome.Confirmed, rig.commands.shutterUp(1, 3))
        assertEquals(ShutterDirection.STOPPED, rig.device.shutters[1 to 3])
        assertEquals(CommandOutcome.Confirmed, rig.commands.shutterDown(1, 3))
        assertEquals(ShutterDirection.MOVING_DOWN, rig.device.shutters[1 to 3])
        assertEquals(CommandOutcome.Confirmed, rig.commands.shutterUp(1, 3)) // opposite direction while moving
        assertEquals(ShutterDirection.STOPPED, rig.device.shutters[1 to 3])
    }

    @Test
    fun eventsOfOtherShuttersOrModulesDoNotConfirm() = runTest {
        val rig = commandsRig()
        rig.device.sendShutterEvents = false
        val pending = async { rig.commands.shutterDown(1, 3) }
        runCurrent()
        rig.transport.deliver(
            shutterEvent(1, 2, ShutterDirection.MOVING_DOWN),
            shutterEvent(2, 3, ShutterDirection.MOVING_DOWN),
            controllerFrame(CommandCode.SHUTTER_STATE, Bytes.of(3), address = 1u), // too short
        )
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(pending.isCompleted)

        rig.transport.deliver(shutterEvent(1, 3, ShutterDirection.MOVING_DOWN))
        runCurrent()
        assertEquals(CommandOutcome.Confirmed, pending.await())
    }

    @Test
    fun acknowledgedWithoutShutterEventIsAckOnly() = runTest {
        val rig = commandsRig()
        rig.device.sendShutterEvents = false
        assertEquals(CommandOutcome.AckOnly, rig.commands.shutterUp(1, 3))
        assertEquals(3_000L, now)
        assertEquals(listOf(CommandCode.SHUTTER_UP), rig.sentCommands()) // nothing to read back: no state request
    }

    @Test
    fun shutterWithoutAcknowledgementAndEventIsNoAck() = runTest {
        val rig = commandsRig()
        rig.device.sendAcks = false
        rig.device.sendShutterEvents = false
        assertEquals(CommandOutcome.NoAck, rig.commands.shutterStop(1, 3))
        assertEquals(3_000L, now)
    }

    @Test
    fun shutterEventWithoutAcknowledgementStillConfirms() = runTest {
        val rig = commandsRig()
        rig.device.sendAcks = false
        assertEquals(CommandOutcome.Confirmed, rig.commands.shutterUp(1, 3))
        assertEquals(500L, now)
    }

    @Test
    fun shutterConfirmTimeoutIsConfigurable() = runTest {
        val rig = commandsRig(config = DeviceCommandsConfig(shutterConfirmTimeout = 1.seconds))
        rig.device.sendShutterEvents = false
        assertEquals(CommandOutcome.AckOnly, rig.commands.shutterUp(1, 3))
        assertEquals(1_000L, now)
    }

    @Test
    fun invalidShutterArgumentsAreRejectedBeforeSending() = runTest {
        val rig = commandsRig()
        val invalid = listOf(
            rig.commands.shutterUp(1, -1),
            rig.commands.shutterDown(1, 64),
            rig.commands.shutterStop(1, 256),
            rig.commands.shutterUp(0, 3),
            rig.commands.shutterDown(0x1FF, 3),
            rig.commands.shutterStop(0x10000, 3),
        )
        invalid.forEachIndexed { index, outcome -> assertTrue(outcome is CommandOutcome.Invalid, "#$index $outcome") }
        assertEquals(emptyList<Frame>(), rig.sent())
        assertEquals(CommandOutcome.Confirmed, rig.commands.shutterUp(1, 63)) // highest number is valid
        assertEquals(CommandOutcome.Confirmed, rig.commands.shutterStop(1, 0))
    }

    @Test
    fun shutterCommandWhileDisconnectedFailsWithoutSending() = runTest {
        val rig = commandsRig(connect = false)
        val outcome = rig.commands.shutterUp(1, 3)
        assertTrue(outcome is CommandOutcome.Failed && outcome.problem == ConnectionProblem.CONNECTION_LOST, "$outcome")
        assertTrue(rig.factory.transports.isEmpty())
    }

    @Test
    fun shutterStateStreamCarriesCommandEventsAndArrivalByItself() = runTest {
        val rig = commandsRig()
        val seen = mutableListOf<ShutterState>()
        backgroundScope.launch { rig.connection.shutterStates.collect { seen += it } }
        runCurrent()
        rig.commands.shutterUp(1, 3)
        // the controller reports the arrival at the top by itself after the travel time; other traffic is ignored
        rig.transport.deliver(outputEvent(1, 12, true), shutterEvent(1, 3, ShutterDirection.AT_TOP))
        runCurrent()
        assertEquals(
            listOf(ShutterDirection.MOVING_UP, ShutterDirection.AT_TOP),
            seen.map { it.direction },
        )
        assertEquals(listOf(3, 3), seen.map { it.outputNr.toInt() })
    }

    // setSetpoint

    @Test
    fun setpointIsConfirmedByTheStateTheControllerSendsByItself() = runTest {
        val rig = commandsRig()
        assertEquals(CommandOutcome.Confirmed, rig.commands.setSetpoint(1, 5, 23.0f))
        assertEquals(listOf(CommandCode.SET_SETPOINT), rig.sentCommands()) // no extra read-back needed
        assertEquals(0L, now)
    }

    @Test
    fun setpointIsReadBackWhenNoStateArrives() = runTest {
        val rig = commandsRig()
        rig.device.sendAutomaticHeaterState = false
        assertEquals(CommandOutcome.Confirmed, rig.commands.setSetpoint(1, 5, 23.0f))
        assertEquals(listOf(CommandCode.SET_SETPOINT, CommandCode.HEATER_STATE), rig.sentCommands())
        assertEquals("05", rig.sent().last().payload.toHex()) // 0x6B [output_nr]
        assertEquals(2_000L, now)
    }

    @Test
    fun staleAutomaticStateIsCheckedWithAnExplicitRead() = runTest {
        val rig = commandsRig()
        rig.device.automaticHeaterStateIsStale = true // the 0x6B that follows still shows the old setpoint
        assertEquals(CommandOutcome.Confirmed, rig.commands.setSetpoint(1, 5, 23.0f))
        assertEquals(listOf(CommandCode.SET_SETPOINT, CommandCode.HEATER_STATE), rig.sentCommands())
    }

    @Test
    fun differentSetpointReadBackIsAMismatch() = runTest {
        val rig = commandsRig()
        rig.device.applyWrites = false // e.g. profile off: the write has no effect
        rig.device.zone(1, 5).setpoint = 18.0f
        val outcome = rig.commands.setSetpoint(1, 5, 23.0f)
        assertTrue(outcome is CommandOutcome.HeaterMismatch, "$outcome")
        assertEquals(18.0f, (outcome as CommandOutcome.HeaterMismatch).actual.setpoint)
        assertEquals(5, outcome.actual.outputNr.toInt())
    }

    @Test
    fun setpointWithoutReadBackIsAckOnly() = runTest {
        val rig = commandsRig()
        rig.device.sendAutomaticHeaterState = false
        rig.device.answerReads = false
        assertEquals(CommandOutcome.AckOnly, rig.commands.setSetpoint(1, 5, 23.0f))
        assertEquals(5_000L, now) // 2 s for the automatic state + 3 s for the explicit read
    }

    @Test
    fun setpointWithoutAcknowledgementIsNoAck() = runTest {
        val rig = commandsRig()
        rig.device.sendAcks = false
        rig.device.sendAutomaticHeaterState = false
        assertEquals(CommandOutcome.NoAck, rig.commands.setSetpoint(1, 5, 23.0f))
        assertEquals(2_000L, now)
        assertEquals(listOf(CommandCode.SET_SETPOINT), rig.sentCommands())
    }

    @Test
    fun setpointStateOfAnotherZoneDoesNotConfirm() = runTest {
        val rig = commandsRig()
        rig.device.sendAutomaticHeaterState = false
        val pending = async { rig.commands.setSetpoint(1, 5, 23.0f) }
        runCurrent()
        rig.transport.deliver(heaterStateFrame(1, 4, FakeDevice.Zone(setpoint = 23.0f)))
        rig.transport.deliver(heaterStateFrame(2, 5, FakeDevice.Zone(setpoint = 23.0f)))
        runCurrent()
        assertFalse(pending.isCompleted)
        advanceTimeBy(2_001) // no matching state: explicit read-back, answered by the device
        runCurrent()
        assertEquals(CommandOutcome.Confirmed, pending.await())
    }

    @Test
    fun setpointFailsWhenConnectionDrops() = runTest {
        val rig = commandsRig()
        rig.device.sendAcks = false
        rig.device.sendAutomaticHeaterState = false
        val pending = async { rig.commands.setSetpoint(1, 5, 23.0f) }
        advanceTimeBy(500)
        rig.transport.closeFromPeer()
        runCurrent()
        val outcome = pending.await()
        assertTrue(outcome is CommandOutcome.Failed && outcome.problem == ConnectionProblem.CONNECTION_LOST, "$outcome")
    }

    // setProfile (experimental)

    @Test
    fun profileSwitchingIsDisabledByDefaultAndSendsNothing() = runTest {
        val rig = commandsRig()
        assertFalse(rig.commands.experimentalProfileSwitching)
        assertEquals(CommandOutcome.NotEnabled, rig.commands.setProfile(1, 5, HeatingProfile.Comfort))
        assertEquals(CommandOutcome.NotEnabled, rig.commands.adjustSetpointOrSwitchOn(1, 5, 22f))
        advanceTimeBy(10_000)
        assertEquals(emptyList<Frame>(), rig.sent()) // not even a read
        assertEquals(emptyList<Frame>(), rig.device.received)
    }

    @Test
    fun profileIsSentAndConfirmedWhenEnabled() = runTest {
        val rig = commandsRig(experimentalProfileSwitching = true)
        rig.device.zone(1, 5).profile = HeatingProfile.Eco
        assertEquals(CommandOutcome.Confirmed, rig.commands.setProfile(1, 5, HeatingProfile.Maximum))
        assertEquals(HeatingProfile.Maximum, rig.device.zone(1, 5).profile)
        assertEquals("0503", rig.sent().single().payload.toHex()) // [output_nr][profile]
        assertEquals(listOf(CommandCode.SET_PROFILE), rig.sentCommands())
    }

    @Test
    fun profileMismatchIsReported() = runTest {
        val rig = commandsRig(experimentalProfileSwitching = true)
        rig.device.applyWrites = false
        val outcome = rig.commands.setProfile(1, 5, HeatingProfile.Eco)
        assertTrue(outcome is CommandOutcome.HeaterMismatch && outcome.actual.profile == HeatingProfile.Comfort, "$outcome")
    }

    // Off -> comfort helper (adjustSetpointOrSwitchOn)

    @Test
    fun zoneThatIsOffIsOnlySwitchedToComfortAndTheRequestedSetpointIsNotWritten() = runTest {
        val rig = commandsRig(experimentalProfileSwitching = true)
        rig.device.zone(1, 5).apply { profile = HeatingProfile.Off; setpoint = 18.0f; comfortSetpoint = 21.5f }
        val outcome = rig.commands.adjustSetpointOrSwitchOn(1, 5, 22.5f)

        assertTrue(outcome is CommandOutcome.SwitchedToComfort, "$outcome")
        val state = (outcome as CommandOutcome.SwitchedToComfort).state
        assertEquals(HeatingProfile.Comfort, state?.profile)
        assertEquals(21.5f, state?.setpoint) // the comfort setpoint the controller reports, not the requested 22.5
        assertEquals(5, state?.outputNr?.toInt())
        assertTrue(outcome.isAccepted)

        assertEquals(listOf(CommandCode.HEATER_STATE, CommandCode.SET_PROFILE), rig.sentCommands()) // no 0x64
        assertEquals(listOf(CommandCode.SET_PROFILE), rig.device.writes.map { it.command })
        assertEquals(RequestVectors["setProfile:1:5:comfort"], rig.sent()[1].encode().toHex())
        assertEquals(HeatingProfile.Comfort, rig.device.zone(1, 5).profile)
        assertEquals(21.5f, rig.device.zone(1, 5).setpoint) // comfort setpoint untouched
        assertEquals(0L, now)
    }

    @Test
    fun requestedSetpointEqualToComfortIsNotWrittenEither() = runTest {
        val rig = commandsRig(experimentalProfileSwitching = true)
        rig.device.zone(1, 5).apply { profile = HeatingProfile.Off; setpoint = 22.5f; comfortSetpoint = 22.5f }
        val outcome = rig.commands.adjustSetpointOrSwitchOn(1, 5, 22.5f)
        assertTrue(outcome is CommandOutcome.SwitchedToComfort && outcome.state?.setpoint == 22.5f, "$outcome")
        assertEquals(listOf(CommandCode.HEATER_STATE, CommandCode.SET_PROFILE), rig.sentCommands())
    }

    @Test
    fun switchedZoneStateIsTakenFromAnExplicitReadWhenTheAutomaticOneIsStale() = runTest {
        val rig = commandsRig(experimentalProfileSwitching = true)
        rig.device.zone(1, 5).apply { profile = HeatingProfile.Off; setpoint = 18.0f; comfortSetpoint = 21.5f }
        rig.device.automaticHeaterStateIsStale = true // the 0x6B that follows 0x66 still shows the profile off
        val outcome = rig.commands.adjustSetpointOrSwitchOn(1, 5, 22.5f)
        assertTrue(outcome is CommandOutcome.SwitchedToComfort && outcome.state?.setpoint == 21.5f, "$outcome")
        assertEquals(
            listOf(CommandCode.HEATER_STATE, CommandCode.SET_PROFILE, CommandCode.HEATER_STATE),
            rig.sentCommands(),
        )
        assertEquals(listOf(CommandCode.SET_PROFILE), rig.device.writes.map { it.command })
    }

    @Test
    fun switchAcknowledgedButNotReadBackIsSwitchedToComfortWithoutState() = runTest {
        val rig = commandsRig(experimentalProfileSwitching = true)
        rig.device.zone(1, 5).apply { profile = HeatingProfile.Off; comfortSetpoint = 21.5f }
        rig.device.sendAutomaticHeaterState = false
        val pending = async { rig.commands.adjustSetpointOrSwitchOn(1, 5, 22.5f) }
        runCurrent() // initial read answered, 0x66 sent and acknowledged, waiting for the zone state
        rig.device.answerReads = false // the read-back after the 2 s wait goes unanswered
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(CommandOutcome.SwitchedToComfort(null), pending.await())
        assertEquals(listOf(CommandCode.SET_PROFILE), rig.device.writes.map { it.command })
    }

    @Test
    fun switchWithoutAcknowledgementIsNoAck() = runTest {
        val rig = commandsRig(experimentalProfileSwitching = true)
        rig.device.zone(1, 5).profile = HeatingProfile.Off
        rig.device.sendAcks = false
        rig.device.sendAutomaticHeaterState = false
        val outcome = rig.commands.adjustSetpointOrSwitchOn(1, 5, 22.5f)
        assertEquals(CommandOutcome.NoAck, outcome)
        assertFalse(outcome.isAccepted)
        assertEquals(listOf(CommandCode.HEATER_STATE, CommandCode.SET_PROFILE), rig.sentCommands())
    }

    @Test
    fun activeZoneJustGetsTheSetpoint() = runTest {
        val rig = commandsRig(experimentalProfileSwitching = true)
        rig.device.zone(1, 5).profile = HeatingProfile.Eco
        assertEquals(CommandOutcome.Confirmed, rig.commands.adjustSetpointOrSwitchOn(1, 5, 20.0f))
        assertEquals(listOf(CommandCode.HEATER_STATE, CommandCode.SET_SETPOINT), rig.sentCommands())
        assertEquals(HeatingProfile.Eco, rig.device.zone(1, 5).profile)
        assertEquals(20.0f, rig.device.zone(1, 5).setpoint)
    }

    @Test
    fun comfortZoneJustGetsTheSetpoint() = runTest {
        val rig = commandsRig(experimentalProfileSwitching = true)
        assertEquals(CommandOutcome.Confirmed, rig.commands.adjustSetpointOrSwitchOn(1, 5, 23.5f))
        assertEquals(listOf(CommandCode.HEATER_STATE, CommandCode.SET_SETPOINT), rig.sentCommands())
        assertEquals(23.5f, rig.sent().last().setpoint())
    }

    @Test
    fun unreadableZoneStateWritesNothing() = runTest {
        val rig = commandsRig(experimentalProfileSwitching = true)
        rig.device.answerReads = false
        val outcome = rig.commands.adjustSetpointOrSwitchOn(1, 5, 20.0f)
        assertTrue(outcome is CommandOutcome.Failed && outcome.problem == ConnectionProblem.NO_RESPONSE, "$outcome")
        assertEquals(emptyList<Frame>(), rig.device.writes)
        assertEquals(3_000L, now)
    }

    @Test
    fun failedSwitchIsReportedAndNothingElseIsWritten() = runTest {
        val rig = commandsRig(experimentalProfileSwitching = true)
        rig.device.zone(1, 5).profile = HeatingProfile.Off
        // Reads work, writes are acknowledged but ignored: the zone stays off.
        rig.device.applyWrites = false
        val outcome = rig.commands.adjustSetpointOrSwitchOn(1, 5, 20.0f)
        assertTrue(outcome is CommandOutcome.HeaterMismatch && outcome.actual.profile == HeatingProfile.Off, "$outcome")
        assertFalse(outcome.isAccepted)
        assertEquals(listOf(CommandCode.SET_PROFILE), rig.device.writes.map { it.command }) // no setpoint write
    }
}
