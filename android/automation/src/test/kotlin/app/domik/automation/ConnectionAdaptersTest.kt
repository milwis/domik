@file:OptIn(ExperimentalCoroutinesApi::class)

package app.domik.automation

import app.domik.deimic.client.Connection
import app.domik.deimic.client.ControllerEndpoint
import app.domik.deimic.client.FrameTransport
import app.domik.deimic.client.FrameTransportFactory
import app.domik.deimic.client.InMemoryTransport
import app.domik.deimic.client.commands.DeviceCommands
import app.domik.deimic.protocol.ByteWriter
import app.domik.deimic.protocol.Bytes
import app.domik.deimic.protocol.CommandCode
import app.domik.deimic.protocol.Frame
import app.domik.deimic.protocol.FrameAddress
import app.domik.deimic.protocol.SerialNumber
import app.domik.deimic.protocol.StateField
import app.domik.deimic.protocol.StateFormat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import app.domik.deimic.protocol.StateSnapshot
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

/** The engine on a real [Connection] (in-memory transport) with [DeviceCommands]: nothing mocked above the socket. */
class ConnectionAdaptersTest {
    private val serial = SerialNumber(0x12345678u)

    /** A controller that keeps the state of its outputs and answers like the real one (ack first, then the effect). */
    private class Peer(private val serial: SerialNumber) {
        val on = mutableSetOf<Pair<Int, Int>>()
        val received = mutableListOf<Frame>()
        var answerStateRequests = true
        lateinit var transport: InMemoryTransport

        private fun frame(command: CommandCode, payload: Bytes = Bytes.EMPTY, address: UInt = FrameAddress.BROADCAST) =
            Frame(serial, address, command, payload = payload)

        private fun ack(request: Frame): Frame {
            val writer = ByteWriter()
            writer.writeU32(request.address)
            writer.writeU32(request.command.raw)
            writer.writeU32(request.group)
            writer.writeU32(request.flags)
            writer.writeU32(request.payload.size.toUInt())
            writer.writeBytes(request.payload)
            return frame(CommandCode.ACK, writer.toBytes())
        }

        private fun stateFrame(): Frame {
            val format = StateFormat.NEW
            val addressAt = checkNotNull(format.extent(StateField.ADDRESS)).offset
            val outputsAt = checkNotNull(format.extent(StateField.OUTPUTS)).offset
            val record = ByteWriter(zeroCount = format.recordSize)
            record.setI32(MODULE, addressAt)
            on.filter { it.first == MODULE }.forEach { record.setU8(1u, outputsAt + it.second) }
            return frame(CommandCode.STATE_NEW, record.toBytes())
        }

        fun event(module: Int, relay: Int, isOn: Boolean) {
            if (isOn) on += module to relay else on -= module to relay
            transport.deliver(frame(if (isOn) CommandCode.OUTPUT_ROSE else CommandCode.OUTPUT_FELL, Bytes.of(relay), module.toUInt()))
        }

        fun handle(request: Frame) {
            if (request.command == CommandCode.PING) {
                transport.deliver(frame(CommandCode.PING))
                return
            }
            received += request
            transport.deliver(ack(request))
            when (request.command) {
                CommandCode.OUTPUT_OFF -> event(request.address.toInt(), request.payload[0].toInt(), false)
                CommandCode.OUTPUT_ON -> event(request.address.toInt(), request.payload[0].toInt(), true)
                CommandCode.GET_STATE -> if (answerStateRequests) transport.deliver(stateFrame())
            }
        }
    }

    private class Setup(val peer: Peer, val connection: Connection)

    private fun TestScope.connected(peer: Peer = Peer(serial)): Setup {
        val factory = FrameTransportFactory { _: ControllerEndpoint ->
            InMemoryTransport(onFrame = { frame -> peer.handle(frame) }).also { peer.transport = it } as FrameTransport
        }
        val connection = Connection(ControllerEndpoint("192.0.2.10"), serial, backgroundScope, factory)
        connection.connect()
        runCurrent()
        return Setup(peer, connection)
    }

    @Test
    fun `connected follows the connection state`() = runTest {
        val setup = connected()
        val events = setup.connection.asControllerEvents()
        assertTrue(events.connected.first())

        setup.connection.disconnect()
        assertEquals(false, events.connected.first())
    }

    @Test
    fun `requestState succeeds and the answer is also a message`() = runTest {
        val setup = connected()
        setup.peer.on += MODULE to 3
        setup.peer.on += MODULE to 40
        val events = setup.connection.asControllerEvents()
        val snapshots = mutableListOf<StateSnapshot>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { events.messages.filterIsInstance<StateSnapshot>().toList(snapshots) }

        assertTrue(events.requestState())
        runCurrent()

        assertEquals(listOf(listOf(3, 40)), snapshots.map { it.module(MODULE.toUInt())!!.onOutputs })
    }

    @Test
    fun `requestState fails when the controller does not answer`() = runTest {
        val setup = connected(Peer(serial).also { it.answerStateRequests = false })
        assertFalse(setup.connection.asControllerEvents().requestState())
    }

    @Test
    fun `requestState fails without a connection`() = runTest {
        val setup = connected()
        setup.connection.disconnect()
        assertFalse(setup.connection.asControllerEvents().requestState())
    }

    @Test
    fun `a light switched on at the wall is switched off by the engine through the real command path`() = runTest {
        val setup = connected()
        val output = OutputAddress(MODULE, 3)
        val rules = MutableStateFlow(ruleSet(rule("r", output, 30.minutes)))
        val engine = AutoOffEngine(
            events = setup.connection.asControllerEvents(),
            switcher = DeviceCommands(setup.connection).asOutputSwitcher(),
            rules = rules,
            scope = backgroundScope,
            clock = TestClock(testScheduler),
        )
        engine.start()
        runCurrent()
        assertEquals(listOf(CommandCode.GET_STATE), setup.peer.received.map { it.command }) // the engine read the state

        setup.peer.event(MODULE, 3, true) // somebody presses the wall switch
        runCurrent()
        assertEquals(setOf(output), engine.timers.value.keys)

        advanceTimeBy(30.minutes - 1.minutes)
        runCurrent()
        // only state reads so far (the engine re-reads the state every 10 minutes), no switch-off yet
        assertEquals(setOf(CommandCode.GET_STATE), setup.peer.received.map { it.command }.toSet())

        advanceTimeBy(1.minutes)
        runCurrent()
        val commands = setup.peer.received.filter { it.command != CommandCode.GET_STATE }
        assertEquals(1, commands.size)
        assertEquals(CommandCode.OUTPUT_OFF, commands.single().command)
        assertEquals(MODULE.toUInt(), commands.single().address)
        assertEquals(listOf(3), commands.single().payload.toByteArray().map { it.toInt() })
        assertTrue(setup.peer.on.isEmpty(), "peer on: ${setup.peer.on}")
        assertTrue(engine.timers.value.isEmpty(), "timers: ${engine.timers.value}")
    }

    private companion object {
        /** Module of the fake controller in these tests. */
        const val MODULE = 5
    }
}
