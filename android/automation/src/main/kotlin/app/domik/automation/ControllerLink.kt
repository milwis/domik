package app.domik.automation

import app.domik.deimic.client.commands.CommandOutcome
import app.domik.deimic.protocol.ControllerMessage
import kotlinx.coroutines.flow.Flow

/**
 * What [AutoOffEngine] needs to know about the controller. The real implementation wraps a
 * [app.domik.deimic.client.Connection] ([asControllerEvents]); tests and demo mode provide fakes, so the engine
 * never needs a network.
 */
interface ControllerEvents {
    /** True while the link to the controller is up (the engine then also requests the state); emits the current value on collection. */
    val connected: Flow<Boolean>

    /**
     * Everything the controller sends, decoded: the engine uses output events (0x109 = on, 0x108 = off) and state
     * snapshots. A **hot** flow – the engine subscribes before it first requests the state, so no event can fall
     * between the two.
     */
    val messages: Flow<ControllerMessage>

    /**
     * Asks the controller for the collective state of all modules (request 0x202) and waits for the answer.
     * Returns false when no usable answer came in time; never throws (except for coroutine cancellation).
     *
     * **The answer itself must be delivered through [messages]** like every other frame the controller sends (a
     * [app.domik.deimic.client.Connection] does that), not only returned here: that keeps it in order with the
     * output events, so a state that was true when the controller sent it can never be processed after the event
     * that changed it.
     */
    suspend fun requestState(): Boolean
}

/**
 * Switches outputs on the controller. Same contract as
 * [app.domik.deimic.client.commands.DeviceCommands.setOutput]: validates, sends, waits for the controller's
 * confirmation, never throws (except for cancellation).
 */
fun interface OutputSwitcher {
    suspend fun setOutput(module: Int, relay: Int, on: Boolean): CommandOutcome
}
