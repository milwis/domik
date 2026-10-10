@file:OptIn(ExperimentalCoroutinesApi::class)

package app.domik.demo

import app.domik.deimic.client.Connection
import app.domik.deimic.client.ConnectionState
import app.domik.deimic.client.commands.DeviceCommands
import app.domik.deimic.protocol.ControllerMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.Assertions.assertEquals

/** A demo controller, a real [Connection] to it and [DeviceCommands] on that connection. */
class DemoRig(
    val controller: FakeController,
    val connection: Connection,
    val commands: DeviceCommands,
    /** Every message the connection received from the controller, in order (collected from the start). */
    val messages: MutableList<ControllerMessage>,
)

/**
 * Creates the rig in [backgroundScope]. By default there are no network delays and no background simulation, so a
 * test controls time and state itself; pass [network] / [simulation] to test those.
 */
fun TestScope.demoRig(
    network: DemoNetwork = DemoNetwork.Instant,
    simulation: DemoSimulation = DemoSimulation.Manual,
    home: DemoHome = DemoHome.standard(),
    connect: Boolean = true,
): DemoRig {
    val controller = FakeController(backgroundScope, home, network = network, simulation = simulation)
    val connection = Connection(controller.endpoint, controller.serial, backgroundScope, controller.transportFactory)
    val messages = mutableListOf<ControllerMessage>()
    backgroundScope.launch { connection.messages.collect { messages += it } }
    runCurrent()
    if (connect) {
        connection.connect()
        runCurrent()
        // The connection is Ready once the (virtual) connect delay has passed.
        if (network.connectDelay.isPositive()) testScheduler.advanceTimeBy(network.connectDelay.inWholeMilliseconds + 1)
        runCurrent()
        assertEquals(ConnectionState.Ready, connection.state.value)
    }
    return DemoRig(controller, connection, DeviceCommands(connection, experimentalProfileSwitching = true), messages)
}
