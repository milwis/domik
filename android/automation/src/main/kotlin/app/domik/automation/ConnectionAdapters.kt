package app.domik.automation

import app.domik.deimic.client.Connection
import app.domik.deimic.client.ConnectionState
import app.domik.deimic.client.commands.DeviceCommands
import app.domik.deimic.protocol.CommandCode
import app.domik.deimic.protocol.ControllerMessage
import app.domik.deimic.protocol.Request
import app.domik.deimic.protocol.StateSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Adapter of a [Connection] to [ControllerEvents]; [readTimeout] limits the state request (0x202). */
fun Connection.asControllerEvents(readTimeout: Duration = 5.seconds): ControllerEvents {
    val connection = this
    return object : ControllerEvents {
        override val connected: Flow<Boolean> = connection.state.map { it == ConnectionState.Ready }

        override val messages: Flow<ControllerMessage> get() = connection.messages

        override suspend fun requestState(): Boolean =
            try {
                // The same request and response code DeviceCommands uses to verify an output. The response frame is
                // delivered to `messages` as well, in order with the events.
                val result = connection.request(Request.getState(connection.serial), setOf(CommandCode.STATE_NEW), readTimeout)
                val snapshot = ControllerMessage.decode(result.responses.first()) as? StateSnapshot
                snapshot != null && !snapshot.isEmpty && snapshot.modules.isNotEmpty()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
    }
}

/** Adapter of [DeviceCommands] to [OutputSwitcher]. */
fun DeviceCommands.asOutputSwitcher(): OutputSwitcher = OutputSwitcher { module, relay, on -> setOutput(module, relay, on) }
