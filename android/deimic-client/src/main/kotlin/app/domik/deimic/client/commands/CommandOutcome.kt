package app.domik.deimic.client.commands

import app.domik.deimic.client.ConnectionProblem
import app.domik.deimic.protocol.HeaterState

/**
 * Result of a [DeviceCommands] call. Commands never throw (apart from coroutine cancellation): every way a
 * command can end is one of these cases, so the UI can map them to messages in a single `when`.
 *
 * "Confirmed" always means that the controller acknowledged the command **and** reported the requested state
 * afterwards (output event, heater state or state snapshot) – not merely that bytes were written.
 */
sealed interface CommandOutcome {
    /** The controller accepted the command and the state it reports matches the request. */
    data object Confirmed : CommandOutcome

    /**
     * The controller acknowledged the command (0x1E0), but the resulting state could not be verified in time:
     * no output event, and the fallback read-back (state snapshot / heater state) failed or was disabled.
     * The command was most likely carried out.
     */
    data object AckOnly : CommandOutcome

    /** The command was sent but the controller neither acknowledged it nor reported a matching state. */
    data object NoAck : CommandOutcome

    /** The command could not be sent or the connection broke while waiting ([problem] says why). */
    data class Failed(val problem: ConnectionProblem, val detail: String? = null) : CommandOutcome

    /** An argument is out of range; **nothing was sent**. [reason] is technical text, not a UI string. */
    data class Invalid(val reason: String) : CommandOutcome

    /** The feature is switched off (experimental profile switching); **nothing was sent**. */
    data object NotEnabled : CommandOutcome

    /** Acknowledged, but a state read back afterwards shows the output in the opposite state ([actualOn]). */
    data class OutputMismatch(val actualOn: Boolean) : CommandOutcome

    /** Acknowledged, but the zone state read back afterwards ([actual]) differs from the requested one. */
    data class HeaterMismatch(val actual: HeaterState) : CommandOutcome

    /**
     * Only from [DeviceCommands.adjustSetpointOrSwitchOn]: the zone's profile was off, so it was switched to
     * comfort (0x66) and the requested setpoint was **not** written (it would permanently overwrite the comfort
     * profile's setpoint); the caller should show the comfort setpoint and let further +/- taps change it.
     *
     * [state] is the zone state (0x6B) that confirmed the switch – `state.setpoint` is the comfort setpoint now in
     * force. It is null only when the switch was acknowledged but the zone state could not be read back in time
     * (the switch most likely happened, like [AckOnly]); the caller then takes the setpoint from the next state
     * the controller reports.
     */
    data class SwitchedToComfort(val state: HeaterState?) : CommandOutcome

    /** True for [Confirmed], [AckOnly] and [SwitchedToComfort]: the controller took the command. */
    val isAccepted: Boolean get() = this is Confirmed || this is AckOnly || this is SwitchedToComfort
}
