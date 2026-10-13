package app.domik.deimic.client.commands

import app.domik.deimic.client.Connection
import app.domik.deimic.client.ConnectionErrors
import app.domik.deimic.client.ConnectionProblem
import app.domik.deimic.client.RequestTimeoutException
import app.domik.deimic.protocol.CommandCode
import app.domik.deimic.protocol.ControllerMessage
import app.domik.deimic.protocol.Frame
import app.domik.deimic.protocol.HeaterState
import app.domik.deimic.protocol.HeatingProfile
import app.domik.deimic.protocol.InputCalibration
import app.domik.deimic.protocol.InputCalibrationTable
import app.domik.deimic.protocol.Request
import app.domik.deimic.protocol.ShutterState
import app.domik.deimic.protocol.StateSnapshot
import kotlinx.coroutines.CancellationException
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Timing and behaviour of [DeviceCommands]. */
data class DeviceCommandsConfig(
    /** How long to wait for the output event (0x108 / 0x109) after switching an output. */
    val outputConfirmTimeout: Duration = 2.seconds,
    /** How long to wait for the zone state (0x6B) the controller sends by itself after 0x64 / 0x66. */
    val heaterConfirmTimeout: Duration = 2.seconds,
    /** How long to wait for the shutter event (0x8B) after a shutter command (up / down / stop). */
    val shutterConfirmTimeout: Duration = 3.seconds,
    /** Timeout of explicit read-backs (0x6B request, 0x202 state snapshot). */
    val readTimeout: Duration = 3.seconds,
    /** When an output event is missing after an acknowledgement, check the output in a state snapshot. */
    val snapshotFallback: Boolean = true,
    /** How long to wait for the controller's confirmation (0x132 echo) after writing an input calibration. */
    val calibrationConfirmTimeout: Duration = 3.seconds,
) {
    init {
        require(
            outputConfirmTimeout.isPositive() && heaterConfirmTimeout.isPositive() &&
                shutterConfirmTimeout.isPositive() && readTimeout.isPositive() && calibrationConfirmTimeout.isPositive(),
        )
    }
}

/**
 * Commands that change the controller's state: switching outputs, heating setpoints and profiles, shutters.
 *
 * Every call validates its arguments first (an invalid call sends nothing), sends one request through
 * [connection], waits for the controller's acknowledgement and then for proof that the state really changed
 * (see [CommandOutcome]). Calls suspend for up to a few seconds, never throw (except for cancellation) and
 * may run concurrently; serializing commands for the same output or zone is the caller's job
 * (e.g. [SetpointDebouncer] keeps one request in flight per zone).
 *
 * Modules are `id_device` values, [setOutput]'s `relay` is `output_relay`, zones and shutters are addressed by
 * `output_nr` (not `output_relay`) – exactly the numbers stored in the controller's configuration database.
 *
 * @param experimentalProfileSwitching 0x66 (heating profile) has not been verified on a live controller yet;
 * while this is false, [setProfile] and [adjustSetpointOrSwitchOn] return [CommandOutcome.NotEnabled]
 * without sending anything.
 */
class DeviceCommands(
    private val connection: Connection,
    val experimentalProfileSwitching: Boolean = false,
    val config: DeviceCommandsConfig = DeviceCommandsConfig(),
) {
    /**
     * Switches output [relay] of [module] on or off (0x104 / 0x103).
     *
     * Confirmed by the output event the controller sends (0x109 = went on, 0x108 = went off). If no event comes
     * but the request was acknowledged, the output is looked up in a state snapshot (0x202); an output that is
     * already in the requested state probably produces no event. An event is conclusive even without an
     * acknowledgement.
     */
    suspend fun setOutput(module: Int, relay: Int, on: Boolean): CommandOutcome {
        invalidModule(module)?.let { return it }
        if (!CommandLimits.isValidRelay(relay)) return CommandOutcome.Invalid("output relay out of range: $relay")
        val frame = Request.setOutput(connection.serial, module.toUInt(), relay.toUByte(), on)
        val event = if (on) CommandCode.OUTPUT_ROSE else CommandCode.OUTPUT_FELL
        return guarded {
            try {
                connection.request(frame, setOf(event), config.outputConfirmTimeout) { received ->
                    received.address == module.toUInt() && received.payload.size >= 1 &&
                        received.payload[0].toInt() == relay
                }
                CommandOutcome.Confirmed
            } catch (timeout: RequestTimeoutException) {
                if (timeout.acknowledged) verifyOutput(module, relay, on) else CommandOutcome.NoAck
            }
        }
    }

    /**
     * Sets the setpoint of the **active** profile of zone [outputNr] (0x64). [celsius] must be within
     * [CommandLimits.MIN_SETPOINT_CELSIUS]..[CommandLimits.MAX_SETPOINT_CELSIUS]; it is rounded to the nearest
     * 0.5 °C before sending.
     *
     * Confirmed by the zone state (0x6B) that the controller sends by itself after 0x64, or – if that does not
     * come – by an explicit 0x6B request; the setpoint read back must equal the sent one (±0.01 °C), otherwise
     * the result is [CommandOutcome.HeaterMismatch]. When the zone's profile is off the setpoint probably has
     * no visible effect – see [adjustSetpointOrSwitchOn].
     */
    suspend fun setSetpoint(module: Int, outputNr: Int, celsius: Float): CommandOutcome {
        invalidZone(module, outputNr)?.let { return it }
        invalidSetpoint(celsius)?.let { return it }
        val rounded = CommandLimits.roundToStep(celsius)
        val frame = Request.setSetpoint(connection.serial, module.toUInt(), outputNr.toUByte(), rounded)
        return writeHeater(frame, module, outputNr) { it.matchesSetpoint(rounded) }.outcome
    }

    /**
     * Changes the heating profile of zone [outputNr] (0x66). **Experimental**: returns
     * [CommandOutcome.NotEnabled] (sending nothing) unless [experimentalProfileSwitching] is true.
     * Confirmed like [setSetpoint], by comparing the profile in the zone state.
     */
    suspend fun setProfile(module: Int, outputNr: Int, profile: HeatingProfile): CommandOutcome {
        if (!experimentalProfileSwitching) return CommandOutcome.NotEnabled
        invalidZone(module, outputNr)?.let { return it }
        if (profile is HeatingProfile.Unknown) return CommandOutcome.Invalid("unknown heating profile: $profile")
        val frame = Request.setProfile(connection.serial, module.toUInt(), outputNr.toUByte(), profile)
        return writeHeater(frame, module, outputNr) { it.profile == profile }.outcome
    }

    /**
     * Handles a +/- tap on a zone's setpoint, which for a zone that is **off** means "turn it on" instead
     * (rather than writing 0x64 to an inactive zone). Reads the zone state (0x6B) first:
     *
     * - profile off: sends **only** 0x66 comfort – the requested [celsius] is deliberately *not* written, because
     *   0x64 would permanently overwrite the setpoint of the comfort profile. Returns
     *   [CommandOutcome.SwitchedToComfort], whose `state.setpoint` is the comfort setpoint the controller reports
     *   after the switch; the UI shows that value and only subsequent taps change it (through [setSetpoint]).
     * - any other profile: just [setSetpoint] ([celsius] is the new setpoint of the active profile).
     *
     * [celsius] is validated like in [setSetpoint] even when it ends up unused. Returns
     * [CommandOutcome.NotEnabled] (sending nothing, not even the read) unless [experimentalProfileSwitching] is
     * true. If the zone state cannot be read, nothing is written and the result is [CommandOutcome.Failed]. A
     * failed switch is reported like in [setProfile] ([CommandOutcome.NoAck], [CommandOutcome.HeaterMismatch], …).
     */
    suspend fun adjustSetpointOrSwitchOn(module: Int, outputNr: Int, celsius: Float): CommandOutcome {
        if (!experimentalProfileSwitching) return CommandOutcome.NotEnabled
        invalidZone(module, outputNr)?.let { return it }
        invalidSetpoint(celsius)?.let { return it }
        val current = when (val read = readHeater(module, outputNr)) {
            is Readout.Ok -> read.state
            is Readout.Error -> return read.outcome
        }
        if (current.profile != HeatingProfile.Off) return setSetpoint(module, outputNr, celsius)

        val comfort = Request.setProfile(connection.serial, module.toUInt(), outputNr.toUByte(), HeatingProfile.Comfort)
        val switched = writeHeater(comfort, module, outputNr) { it.profile == HeatingProfile.Comfort }
        return when (switched.outcome) {
            CommandOutcome.Confirmed -> CommandOutcome.SwitchedToComfort(switched.state)
            CommandOutcome.AckOnly -> CommandOutcome.SwitchedToComfort(null)
            else -> switched.outcome
        }
    }

    /**
     * Shutter [outputNr] of [module]: **up** (0x8D). See [shutterCommand] for what the controller answers.
     *
     * Careful, the firmware (2017) works like a one-button remote: a direction command sent **while the shutter is
     * moving** – the same or the opposite one – only **stops** it; there is no reversal. The UI therefore shows
     * the buttons as "stop" while the shutter moves, and a change of direction takes two taps (stop, then the
     * other direction). Which way a standing shutter goes is not predicted by the caller – it follows the event
     * ([Connection.shutterStates]) that the controller sends.
     */
    suspend fun shutterUp(module: Int, outputNr: Int): CommandOutcome =
        shutterCommand(module, outputNr) { Request.shutterUp(connection.serial, module.toUInt(), outputNr.toUByte()) }

    /** Shutter [outputNr] of [module]: **down** (0x8E); same behaviour as [shutterUp]. */
    suspend fun shutterDown(module: Int, outputNr: Int): CommandOutcome =
        shutterCommand(module, outputNr) { Request.shutterDown(connection.serial, module.toUInt(), outputNr.toUByte()) }

    /**
     * Shutter [outputNr] of [module]: **stop** (0x82). The byte sent is the shutter *number* (`output_nr`), not
     * `output_relay` (sending the latter stops another shutter or none).
     */
    suspend fun shutterStop(module: Int, outputNr: Int): CommandOutcome =
        shutterCommand(module, outputNr) { Request.shutterStop(connection.serial, module.toUInt(), outputNr.toUByte()) }

    /**
     * Sends a shutter command and waits for the shutter event 0x8B of the same shutter (any direction: the
     * controller reports "moving up/down" or "stopped" depending on what the shutter was doing).
     * [CommandOutcome.Confirmed] = the controller reported a new shutter state;
     * [CommandOutcome.AckOnly] = acknowledged but silent (most likely the command was carried out – there is no
     * state to read back: the collective state's `shutterStates` is not updated by this firmware);
     * [CommandOutcome.NoAck] = neither.
     */
    private suspend fun shutterCommand(module: Int, outputNr: Int, build: () -> Frame): CommandOutcome {
        invalidModule(module)?.let { return it }
        if (!CommandLimits.isValidShutter(outputNr)) return CommandOutcome.Invalid("shutter number out of range: $outputNr")
        val frame = build()
        return guarded {
            try {
                connection.request(frame, setOf(CommandCode.SHUTTER_STATE), config.shutterConfirmTimeout) { received ->
                    received.address == module.toUInt() && received.payload.size >= ShutterState.MINIMUM_LENGTH &&
                        received.payload[0].toInt() == outputNr
                }
                CommandOutcome.Confirmed
            } catch (timeout: RequestTimeoutException) {
                if (timeout.acknowledged) CommandOutcome.AckOnly else CommandOutcome.NoAck
            }
        }
    }

    /**
     * Reads the calibration records of all analog inputs of [module] (0x131; usually 12 records, input 0 first –
     * unused inputs hold garbage, see [InputCalibrationTable]). A read only: nothing on the controller changes.
     * Only a response with the address of [module] is accepted (seen live: the controller answers module 1 with
     * address 1); a table of another module is ignored like any unrelated frame.
     */
    suspend fun readCalibrations(module: Int): CalibrationReadout {
        invalidModule(module)?.let { return CalibrationReadout.Failure(it) }
        return try {
            val result = connection.request(
                Request.inputCalibrations(connection.serial, module.toUInt()),
                setOf(CommandCode.INPUT_CALIBRATION_GET),
                config.readTimeout,
            ) { it.address == module.toUInt() }
            when (val table = ControllerMessage.decode(result.responses.first())) {
                is InputCalibrationTable -> CalibrationReadout.Success(table.records)
                else -> CalibrationReadout.Failure(CommandOutcome.Failed(ConnectionProblem.OTHER, "unreadable calibration table"))
            }
        } catch (_: RequestTimeoutException) {
            CalibrationReadout.Failure(CommandOutcome.NoAck)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            CalibrationReadout.Failure(failed(error))
        }
    }

    /**
     * Writes the calibration [record] of analog input [inputNr] (0..[InputCalibration.MAX_INPUT]) of [module]
     * (0x132). It takes effect at once (new 0x72 / 0x130 / 0x6B with the corrected temperature follow within a few
     * seconds) and **changes the temperature the controller regulates on** – the caller decides what is sensible.
     *
     * Strict: [CommandOutcome.Confirmed] only when the controller answered with a 0x132 frame from [module] (address)
     * whose payload is identical to what was sent. The echo missing (even though the request was acknowledged) is a
     * [CommandOutcome.Failed] with [ConnectionProblem.NO_RESPONSE] – never [CommandOutcome.AckOnly]; a different
     * payload is [CommandOutcome.Failed] with [ConnectionProblem.OTHER]. Neither is retried here: after such a
     * result read the table back with [readCalibrations] to see what the controller holds (this class does not
     * verify by reading automatically – the echo is the controller's own confirmation of the write, a read-back
     * costs another round trip and is left to callers that want it, e.g. before and after a series of writes).
     *
     * Invalid arguments ([CommandOutcome.Invalid], nothing sent): module or input out of range, an active-point
     * flag other than 0 / 1, or a non-finite value in an active point.
     */
    suspend fun writeCalibration(module: Int, inputNr: Int, record: InputCalibration): CommandOutcome {
        invalidModule(module)?.let { return it }
        if (inputNr !in 0..InputCalibration.MAX_INPUT) return CommandOutcome.Invalid("input number out of range: $inputNr")
        invalidCalibration(record)?.let { return it }
        val frame = Request.setInputCalibration(connection.serial, module.toUInt(), inputNr.toUByte(), record)
        return guarded {
            try {
                val result = connection.request(frame, setOf(CommandCode.INPUT_CALIBRATION_SET), config.calibrationConfirmTimeout) {
                    it.address == module.toUInt() && it.payload.size >= 1 && it.payload[0].toInt() == inputNr
                }
                if (result.responses.first().payload == frame.payload) {
                    CommandOutcome.Confirmed
                } else {
                    CommandOutcome.Failed(ConnectionProblem.OTHER, "the controller confirmed a different calibration")
                }
            } catch (timeout: RequestTimeoutException) {
                if (timeout.acknowledged) {
                    CommandOutcome.Failed(ConnectionProblem.NO_RESPONSE, "acknowledged, but no calibration confirmation (0x132)")
                } else {
                    CommandOutcome.NoAck
                }
            }
        }
    }

    // Output verification

    /** No output event arrived: look the output up in a state snapshot (0x202 [01] -> 0x21E). */
    private suspend fun verifyOutput(module: Int, relay: Int, on: Boolean): CommandOutcome {
        if (!config.snapshotFallback) return CommandOutcome.AckOnly
        val actual = try {
            val result = connection.request(Request.getState(connection.serial), setOf(CommandCode.STATE_NEW), config.readTimeout)
            val snapshot = ControllerMessage.decode(result.responses.first()) as? StateSnapshot
            snapshot?.module(module.toUInt())?.outputs?.getOrNull(relay)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null // the request was acknowledged; failing to verify it is not a failure of the command
        }
        return when (actual) {
            null -> CommandOutcome.AckOnly
            on -> CommandOutcome.Confirmed
            else -> CommandOutcome.OutputMismatch(actual)
        }
    }

    // Heating

    private class HeaterWrite(val outcome: CommandOutcome, val state: HeaterState? = null)

    private sealed interface Readout {
        class Ok(val state: HeaterState) : Readout
        class Error(val outcome: CommandOutcome) : Readout
    }

    /**
     * Sends a 0x64 / 0x66 [frame] and checks the resulting zone state with [matches]. The state is taken from the
     * 0x6B the controller sends by itself; if it is missing or shows another state (it may be a stale one), the
     * zone is read explicitly.
     */
    private suspend fun writeHeater(
        frame: Frame,
        module: Int,
        outputNr: Int,
        matches: (HeaterState) -> Boolean,
    ): HeaterWrite = guardedWrite {
        var acknowledged = true
        val automatic = try {
            val result = connection.request(frame, setOf(CommandCode.HEATER_STATE), config.heaterConfirmTimeout) {
                it.isZoneState(module, outputNr)
            }
            acknowledged = result.acknowledged
            decodeHeater(result.responses.first())
        } catch (timeout: RequestTimeoutException) {
            if (!timeout.acknowledged) return@guardedWrite HeaterWrite(CommandOutcome.NoAck)
            null
        }
        if (automatic != null && matches(automatic)) return@guardedWrite HeaterWrite(CommandOutcome.Confirmed, automatic)
        when (val read = readHeater(module, outputNr)) {
            is Readout.Ok -> HeaterWrite(
                if (matches(read.state)) CommandOutcome.Confirmed else CommandOutcome.HeaterMismatch(read.state),
                read.state,
            )
            is Readout.Error -> HeaterWrite(if (acknowledged) CommandOutcome.AckOnly else CommandOutcome.NoAck)
        }
    }

    /** Explicit 0x6B request; a failure is described by a [CommandOutcome.Failed]. */
    private suspend fun readHeater(module: Int, outputNr: Int): Readout =
        try {
            val result = connection.request(
                Request.heaterState(connection.serial, module.toUInt(), outputNr.toUByte()),
                setOf(CommandCode.HEATER_STATE),
                config.readTimeout,
            ) { it.isZoneState(module, outputNr) }
            decodeHeater(result.responses.first())?.let { Readout.Ok(it) }
                ?: Readout.Error(CommandOutcome.Failed(ConnectionProblem.OTHER, "unreadable zone state"))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Readout.Error(failed(error))
        }

    private fun decodeHeater(frame: Frame): HeaterState? = ControllerMessage.decode(frame) as? HeaterState

    private fun Frame.isZoneState(module: Int, outputNr: Int): Boolean =
        address == module.toUInt() && payload.size >= 1 && payload[0].toInt() == outputNr

    private fun HeaterState.matchesSetpoint(celsius: Float): Boolean =
        abs(setpoint - celsius) <= CommandLimits.SETPOINT_TOLERANCE_CELSIUS

    // Validation and error mapping

    private fun invalidModule(module: Int): CommandOutcome.Invalid? =
        if (CommandLimits.isValidModule(module)) null else CommandOutcome.Invalid("module address out of range: $module")

    private fun invalidZone(module: Int, outputNr: Int): CommandOutcome.Invalid? =
        invalidModule(module)
            ?: if (CommandLimits.isValidZone(outputNr)) null else CommandOutcome.Invalid("zone number out of range: $outputNr")

    private fun invalidSetpoint(celsius: Float): CommandOutcome.Invalid? =
        if (CommandLimits.isValidSetpoint(celsius)) {
            null
        } else {
            CommandOutcome.Invalid(
                "setpoint $celsius outside ${CommandLimits.MIN_SETPOINT_CELSIUS}..${CommandLimits.MAX_SETPOINT_CELSIUS} °C",
            )
        }

    private fun invalidCalibration(record: InputCalibration): CommandOutcome.Invalid? {
        if (record.active1 > 1u || record.active2 > 1u) return CommandOutcome.Invalid("active-point flags must be 0 or 1")
        val values = buildList {
            if (record.point1Active) addAll(listOf(record.measured1Tenths, record.true1Tenths))
            if (record.point2Active) addAll(listOf(record.measured2Tenths, record.true2Tenths))
        }
        return if (values.all { it.isFinite() }) null else CommandOutcome.Invalid("calibration contains a non-finite value")
    }

    private fun failed(error: Throwable): CommandOutcome.Failed =
        CommandOutcome.Failed(ConnectionErrors.classify(error), error.message)

    private suspend inline fun guarded(block: () -> CommandOutcome): CommandOutcome =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            failed(error)
        }

    private suspend inline fun guardedWrite(block: () -> HeaterWrite): HeaterWrite =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            HeaterWrite(failed(error))
        }
}
