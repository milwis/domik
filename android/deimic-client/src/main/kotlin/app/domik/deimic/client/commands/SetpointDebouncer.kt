package app.domik.deimic.client.commands

import app.domik.deimic.client.ConnectionProblem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** A heating zone as stored in the configuration database: module (`id_device`) and `output_nr`. */
data class ZoneKey(val module: Int, val outputNr: Int)

/**
 * A setpoint the user chose that the controller has not confirmed yet. The UI shows [target] instead of the
 * setpoint last reported by the controller for as long as the zone is listed in [SetpointDebouncer.pending].
 */
data class PendingSetpoint(
    /** The value to show now: the result of the last tap. */
    val target: Float,
    /** The last value known to be on the controller – what the zone falls back to if sending fails. */
    val confirmed: Float,
    /** True while a request for this zone is in flight (newer taps may be waiting behind it). */
    val isSending: Boolean,
)

/**
 * Outcome of one request made by [SetpointDebouncer]. [revertedTo] is the setpoint the zone fell back to when the
 * request was not [CommandOutcome.isAccepted], null otherwise.
 */
data class SetpointResult(
    val zone: ZoneKey,
    val requested: Float,
    val outcome: CommandOutcome,
    val revertedTo: Float?,
)

/**
 * Turns rapid +/- taps on a zone's setpoint into few requests: every tap moves a local target that the UI shows
 * at once ([pending]); the request is sent only after [quietPeriod] without further taps, and only the last value
 * is sent. Per zone at most one request is in flight – taps arriving meanwhile are sent after it has finished.
 * Zones are independent of each other.
 *
 * When a request fails (anything but [CommandOutcome.isAccepted]) the zone reverts to the last confirmed setpoint
 * and newer taps are dropped; [results] reports it. If the controller acknowledges but reports another setpoint
 * ([CommandOutcome.HeaterMismatch]) the zone reverts to the setpoint it reported. When [send] is
 * [DeviceCommands.adjustSetpointOrSwitchOn] and the zone was off ([CommandOutcome.SwitchedToComfort]), the requested
 * value was not written: the zone settles at once (the UI shows the comfort setpoint the controller reports) and
 * taps made while that request was in flight are dropped, because they were relative to the abandoned target.
 *
 * All requests run in [scope], time is only measured with `delay` (virtual time works in tests). Thread-safe.
 *
 * @param send performs the actual request, normally [DeviceCommands.setSetpoint] (or
 * [DeviceCommands.adjustSetpointOrSwitchOn] to turn off zones on). It receives a value within
 * [CommandLimits] on the 0.5 °C grid. Exceptions it throws are treated as failure.
 */
class SetpointDebouncer(
    private val scope: CoroutineScope,
    val quietPeriod: Duration = DEFAULT_QUIET_PERIOD,
    private val send: suspend (zone: ZoneKey, celsius: Float) -> CommandOutcome,
) {
    /** Sends with [DeviceCommands.setSetpoint]. */
    constructor(scope: CoroutineScope, commands: DeviceCommands, quietPeriod: Duration = DEFAULT_QUIET_PERIOD) :
        this(scope, quietPeriod, { zone, celsius -> commands.setSetpoint(zone.module, zone.outputNr, celsius) })

    init {
        require(quietPeriod.isPositive()) { "quietPeriod must be positive" }
    }

    private class ZoneState(var target: Float, var confirmed: Float) {
        /** Quiet-period timer; null once it has fired. */
        var timer: Job? = null

        /** Incremented by every tap and by a revert, so that a timer that lost the race can recognise itself. */
        var generation: Int = 0
        var sending: Boolean = false

        fun snapshot() = PendingSetpoint(target, confirmed, sending)
    }

    private val lock = Any()
    private val zones = HashMap<ZoneKey, ZoneState>()

    private val _pending = MutableStateFlow<Map<ZoneKey, PendingSetpoint>>(emptyMap())

    /**
     * Zones with a setpoint that is not yet settled (waiting for the quiet period, in flight, or queued behind
     * a request in flight). A zone not listed shows the setpoint reported by the controller.
     */
    val pending: StateFlow<Map<ZoneKey, PendingSetpoint>> = _pending.asStateFlow()

    private val _results = MutableSharedFlow<SetpointResult>(
        extraBufferCapacity = RESULT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** One entry per finished request, e.g. to show an error when it was not accepted. Never suspends senders. */
    val results: SharedFlow<SetpointResult> = _results.asSharedFlow()

    /**
     * Moves the target of [zone] by [steps] × 0.5 °C (negative = down), clamped to 5–30 °C. Returns the new
     * target, which is also visible in [pending] immediately.
     *
     * @param reportedSetpoint the setpoint currently reported by the controller; used as the starting point (and
     * as the value to revert to) when no change is pending for the zone, ignored otherwise.
     */
    fun adjust(zone: ZoneKey, steps: Int, reportedSetpoint: Float): Float =
        change(zone, reportedSetpoint) { it + steps * CommandLimits.SETPOINT_STEP_CELSIUS }

    /** Sets the target of [zone] to [celsius] (clamped to 5–30 °C, rounded to 0.5 °C), e.g. from a slider. */
    fun setTarget(zone: ZoneKey, celsius: Float, reportedSetpoint: Float): Float {
        require(celsius.isFinite()) { "celsius must be finite" }
        return change(zone, reportedSetpoint) { celsius }
    }

    private fun change(zone: ZoneKey, reportedSetpoint: Float, next: (current: Float) -> Float): Float {
        require(reportedSetpoint.isFinite()) { "reportedSetpoint must be finite" }
        return synchronized(lock) {
            val state = zones.getOrPut(zone) { ZoneState(target = reportedSetpoint, confirmed = reportedSetpoint) }
            state.target = CommandLimits.clampToStep(next(state.target))
            val generation = ++state.generation
            state.timer?.cancel()
            state.timer = scope.launch {
                delay(quietPeriod)
                onQuiet(zone, state, generation)
            }
            publish(zone)
            state.target
        }
    }

    /** The quiet period of the latest tap has elapsed. */
    private fun onQuiet(zone: ZoneKey, state: ZoneState, generation: Int) {
        val requested = synchronized(lock) {
            if (zones[zone] !== state || state.generation != generation) return // superseded by a newer tap
            state.timer = null
            beginSend(zone, state)
        }
        if (requested != null) launchSend(zone, state, requested)
    }

    /**
     * Under [lock]: decides what to do once the quiet period is over (timer == null) and no request is in
     * flight. Returns the value to send, or null when there is nothing to send (zone settled, or not yet time).
     */
    private fun beginSend(zone: ZoneKey, state: ZoneState): Float? {
        if (state.sending || state.timer != null) return null
        if (state.target == state.confirmed) {
            settle(zone)
            return null
        }
        state.sending = true
        publish(zone)
        return state.target
    }

    private fun launchSend(zone: ZoneKey, state: ZoneState, requested: Float) {
        scope.launch {
            val outcome = try {
                send(zone, requested)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                CommandOutcome.Failed(ConnectionProblem.OTHER, error.message)
            }
            onSent(zone, state, requested, outcome)
        }
    }

    private fun onSent(zone: ZoneKey, state: ZoneState, requested: Float, outcome: CommandOutcome) {
        val following = synchronized(lock) {
            state.sending = false
            if (outcome is CommandOutcome.SwitchedToComfort) {
                // The zone was switched on with its comfort setpoint; the requested value was not written.
                state.generation++
                state.timer?.cancel()
                state.timer = null
                settle(zone)
                _results.tryEmit(SetpointResult(zone, requested, outcome, revertedTo = null))
                null
            } else if (outcome.isAccepted) {
                state.confirmed = requested
                _results.tryEmit(SetpointResult(zone, requested, outcome, revertedTo = null))
                // A tap during the request restarted the timer (it will send); otherwise send the newest target
                // now if it differs, or settle.
                if (state.timer == null) {
                    beginSend(zone, state)
                } else {
                    publish(zone)
                    null
                }
            } else {
                if (outcome is CommandOutcome.HeaterMismatch) state.confirmed = outcome.actual.setpoint
                state.generation++
                state.timer?.cancel()
                state.timer = null
                settle(zone)
                _results.tryEmit(SetpointResult(zone, requested, outcome, revertedTo = state.confirmed))
                null
            }
        }
        if (following != null) launchSend(zone, state, following)
    }

    /** Under [lock]: forgets the zone; the UI falls back to the controller's reported setpoint. */
    private fun settle(zone: ZoneKey) {
        zones.remove(zone)
        publish(zone)
    }

    /** Under [lock]: copies the state of [zone] into [pending]. */
    private fun publish(zone: ZoneKey) {
        val state = zones[zone]
        _pending.value = if (state == null) _pending.value - zone else _pending.value + (zone to state.snapshot())
    }

    companion object {
        val DEFAULT_QUIET_PERIOD: Duration = 600.milliseconds
        private const val RESULT_BUFFER = 64
    }
}
