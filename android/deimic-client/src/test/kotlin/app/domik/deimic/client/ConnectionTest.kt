package app.domik.deimic.client

import app.domik.deimic.protocol.Bytes
import app.domik.deimic.protocol.CommandCode
import app.domik.deimic.protocol.ControllerMessage
import app.domik.deimic.protocol.Frame
import app.domik.deimic.protocol.FrameAddress
import app.domik.deimic.protocol.HeaterState
import app.domik.deimic.protocol.OutputEvent
import app.domik.deimic.protocol.ProtocolException
import app.domik.deimic.protocol.Request
import app.domik.deimic.protocol.SerialNumber
import app.domik.deimic.protocol.TemperatureReport
import app.domik.deimic.protocol.ByteWriter
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.seconds

class ConnectionTest {
    private val controller = FakeController()

    private fun TestScope.connection(
        factory: FrameTransportFactory,
        config: ConnectionConfig = ConnectionConfig(),
    ) = Connection(TEST_ENDPOINT, FAKE_SERIAL, backgroundScope, factory, config)

    /** Factory whose transports are answered by [controller]; ping frames are timestamped in [pingTimes]. */
    private fun TestScope.answeringFactory(pingTimes: MutableList<Long> = mutableListOf()) =
        RecordingFactory { frame ->
            if (frame.command == CommandCode.PING) pingTimes += now
            controller.handle(this, frame)
        }

    @Test
    fun becomesReadyAndSendsKeepAliveEveryFiveSeconds() = runTest {
        val pings = mutableListOf<Long>()
        val factory = answeringFactory(pings)
        val connection = connection(factory)
        connection.connect()
        runCurrent()
        assertEquals(ConnectionState.Ready, connection.state.value)

        advanceTimeBy(20_001)
        assertEquals(listOf(5_000L, 10_000L, 15_000L, 20_000L), pings)
        val ping = factory.transports.single().sentFrames.first()
        assertEquals(Request.ping(FAKE_SERIAL), ping)
        assertEquals(FrameAddress.CONTROLLER, ping.address)
        assertEquals(ConnectionState.Ready, connection.state.value)
    }

    @Test
    fun answeredKeepAliveKeepsConnectionAlive() = runTest {
        val factory = answeringFactory()
        val connection = connection(factory)
        connection.connect()
        advanceTimeBy(120_000)
        assertEquals(ConnectionState.Ready, connection.state.value)
        assertEquals(1, factory.transports.size)
    }

    @Test
    fun noDataForFifteenSecondsTriggersReconnect() = runTest {
        val factory = RecordingFactory() // peer never answers
        val connection = connection(factory)
        connection.connect()
        runCurrent()
        assertEquals(ConnectionState.Ready, connection.state.value)

        advanceTimeBy(14_999)
        assertEquals(ConnectionState.Ready, connection.state.value)
        advanceTimeBy(2)
        assertEquals(
            ConnectionState.WaitingToReconnect(1, 2.seconds, ConnectionProblem.NO_RESPONSE),
            connection.state.value,
        )
        assertTrue(factory.transports[0].isClosed)

        advanceTimeBy(2_000)
        assertEquals(2, factory.transports.size)
        assertEquals(ConnectionState.Ready, connection.state.value)
    }

    @Test
    fun spontaneousEventsAlsoCountAsLiveness() = runTest {
        val factory = RecordingFactory()
        val connection = connection(factory)
        connection.connect()
        runCurrent()
        repeat(5) {
            advanceTimeBy(10_000)
            factory.transports.single().deliver(outputOnEvent(12))
        }
        runCurrent()
        assertEquals(ConnectionState.Ready, connection.state.value)
        assertEquals(1, factory.transports.size)
    }

    @Test
    fun backoffDoublesUpToSixtySecondsAndGivesUpAfterTwentyAttempts() = runTest {
        val attemptTimes = mutableListOf<Long>()
        val states = mutableListOf<ConnectionState>()
        val factory = RecordingFactory(onConnect = {
            attemptTimes += now
            throw ConnectionException(ConnectionProblem.CONNECTION_REFUSED, "refused")
        })
        val connection = connection(factory)
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { connection.state.collect { states += it } }
        connection.connect()
        advanceTimeBy(3_600_000)

        val delays = attemptTimes.zipWithNext { a, b -> (b - a) / 1000 }
        assertEquals(listOf(2L, 4, 8, 16, 32) + List(15) { 60L }, delays)
        assertEquals(21, attemptTimes.size) // first attempt + 20 reconnect attempts
        assertEquals(ConnectionState.Failed(ConnectionProblem.CONNECTION_REFUSED, "refused"), connection.state.value)
        val waits = states.filterIsInstance<ConnectionState.WaitingToReconnect>()
        assertEquals((1..20).toList(), waits.map { it.attempt })
        assertTrue(waits.all { it.problem == ConnectionProblem.CONNECTION_REFUSED })
    }

    @Test
    fun backoffDelayFormula() {
        val config = ConnectionConfig()
        assertEquals(
            listOf(2, 4, 8, 16, 32, 60, 60).map { it.seconds },
            (1..7).map(config::reconnectDelay),
        )
        assertEquals(60.seconds, config.reconnectDelay(1_000))
    }

    @Test
    fun successfulSessionResetsBackoff() = runTest {
        val factory = RecordingFactory(onConnect = { attempt ->
            if (attempt < 2) throw ConnectionException(ConnectionProblem.TIMEOUT, "timeout")
        }) { frame -> controller.handle(this, frame) }
        val connection = connection(factory)
        connection.connect()
        runCurrent()
        assertEquals(ConnectionState.WaitingToReconnect(1, 2.seconds, ConnectionProblem.TIMEOUT), connection.state.value)
        advanceTimeBy(2_001 + 4_000)
        assertEquals(ConnectionState.Ready, connection.state.value)
        assertEquals(3, factory.transports.size)

        factory.transports.last().closeFromPeer()
        runCurrent()
        assertEquals(
            ConnectionState.WaitingToReconnect(1, 2.seconds, ConnectionProblem.CONNECTION_LOST),
            connection.state.value,
        )
    }

    @Test
    fun certificateMismatchFailsWithoutRetrying() = runTest {
        val factory = RecordingFactory(onConnect = {
            throw ConnectionException(ConnectionProblem.CERTIFICATE_MISMATCH, "pin")
        })
        val connection = connection(factory)
        connection.connect()
        advanceTimeBy(600_000)
        assertEquals(ConnectionState.Failed(ConnectionProblem.CERTIFICATE_MISMATCH, "pin"), connection.state.value)
        assertEquals(1, factory.transports.size)
    }

    @Test
    fun hangingConnectTimesOut() = runTest {
        val factory = RecordingFactory(onConnect = { awaitCancellation() })
        val connection = connection(factory, ConnectionConfig(maxReconnectAttempts = 0))
        connection.connect()
        advanceTimeBy(15_001)
        assertEquals(ConnectionProblem.TIMEOUT, (connection.state.value as ConnectionState.Failed).problem)
        assertTrue(factory.transports.single().isClosed)
    }

    @Test
    fun requestMatchesResponseAndAckWhileOtherFramesGoToMessages() = runTest {
        val factory = RecordingFactory { frame ->
            if (frame.command == CommandCode.FIRMWARE_VERSION) {
                deliver(outputOnEvent(12)) // unrelated spontaneous event in between
                deliver(ackFor(frame))
                deliver(firmwareResponse())
            }
        }
        val connection = connection(factory)
        val messages = mutableListOf<ControllerMessage>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { connection.messages.collect { messages += it } }
        connection.connect()
        runCurrent()

        val result = connection.request(Request.firmwareVersion(FAKE_SERIAL), setOf(CommandCode.FIRMWARE_VERSION))
        assertTrue(result.acknowledged)
        assertEquals(listOf(firmwareResponse()), result.responses)
        assertEquals(0L, now) // returned immediately, no grace period needed
        runCurrent()
        assertTrue(messages.contains(OutputEvent(1u, 12u, true)))
        assertTrue(messages.any { it is app.domik.deimic.protocol.Ack })
        assertTrue(messages.any { it is app.domik.deimic.protocol.FirmwareVersion })
    }

    @Test
    fun responseBeforeAckWaitsForAck() = runTest {
        val factory = RecordingFactory { frame ->
            if (frame.command == CommandCode.PING) deliver(pong(), ackFor(frame))
        }
        val connection = connection(factory)
        connection.connect()
        runCurrent()
        val result = connection.request(Request.ping(FAKE_SERIAL), setOf(CommandCode.PING))
        assertTrue(result.acknowledged)
        assertEquals(0L, now)
    }

    @Test
    fun missingAckIsNotAnError() = runTest {
        val factory = RecordingFactory { frame ->
            if (frame.command == CommandCode.CONFIG_CHECKSUM) deliver(checksumResponse())
        }
        val connection = connection(factory)
        connection.connect()
        runCurrent()
        val result = connection.request(Request.configChecksum(FAKE_SERIAL), setOf(CommandCode.CONFIG_CHECKSUM))
        assertFalse(result.acknowledged)
        assertEquals(1, result.responses.size)
        assertEquals(500L, now) // waited only the acknowledgement grace period
    }

    @Test
    fun ackForADifferentRequestDoesNotCount() = runTest {
        val factory = RecordingFactory { frame ->
            if (frame.command == CommandCode.CONFIG_CHECKSUM) {
                deliver(ackFor(Request.ping(FAKE_SERIAL)), checksumResponse())
            }
        }
        val connection = connection(factory)
        connection.connect()
        runCurrent()
        val result = connection.request(Request.configChecksum(FAKE_SERIAL), setOf(CommandCode.CONFIG_CHECKSUM))
        assertFalse(result.acknowledged)
    }

    @Test
    fun requestTimeoutReportsWhetherAcknowledged() = runTest {
        val factory = RecordingFactory { frame -> deliver(ackFor(frame)) } // acknowledges, never answers
        val connection = connection(factory)
        connection.connect()
        runCurrent()
        val error = assertThrows<RequestTimeoutException> {
            connection.request(Request.firmwareVersion(FAKE_SERIAL), setOf(CommandCode.FIRMWARE_VERSION), timeout = 3.seconds)
        }
        assertTrue(error.acknowledged)
        assertEquals(ConnectionProblem.NO_RESPONSE, error.problem)
        assertEquals(3_000L, now)
    }

    @Test
    fun collectWindowGathersMultipleResponses() = runTest {
        fun report(room: Int, celsius: Float) = ByteWriter().run {
            writeF32(celsius); writeU8(0u); writeU8(room.toUByte()); writeU8(0u); writeU8(0xFFu); writeU8(0u)
            controllerFrame(CommandCode.TEMPERATURE_REPORT, toBytes(), address = 1u)
        }
        val factory = RecordingFactory { frame ->
            if (frame.command == CommandCode.TEMPERATURE_REPORT) {
                deliver(ackFor(frame), report(1, 21.5f), report(2, 20.0f))
            }
        }
        val connection = connection(factory)
        connection.connect()
        runCurrent()
        val pending = async {
            connection.request(
                Request.temperatureReadout(FAKE_SERIAL), setOf(CommandCode.TEMPERATURE_REPORT),
                collectWindow = 1.seconds,
            )
        }
        advanceTimeBy(500)
        factory.transports.single().deliver(report(3, 19.0f))
        advanceTimeBy(600)
        val result = pending.await()
        val rooms = result.responses.map { (ControllerMessage.decode(it) as TemperatureReport).roomId.toInt() }
        assertEquals(listOf(1, 2, 3), rooms)
    }

    @Test
    fun acceptFilterSelectsMatchingResponse() = runTest {
        val factory = RecordingFactory { frame ->
            if (frame.command == CommandCode.HEATER_STATE) {
                fun state(nr: Int) = controllerFrame(CommandCode.HEATER_STATE, app.domik.deimic.protocol.Bytes.zeros(17).let {
                    val b = it.toByteArray(); b[0] = nr.toByte(); app.domik.deimic.protocol.Bytes.copyOf(b)
                }, address = 1u)
                deliver(ackFor(frame), state(4), state(5))
            }
        }
        val connection = connection(factory)
        connection.connect()
        runCurrent()
        val result = connection.request(
            Request.heaterState(FAKE_SERIAL, 1u, 5u), setOf(CommandCode.HEATER_STATE),
        ) { it.payload[0].toInt() == 5 }
        assertEquals(5, result.responses.single().payload[0].toInt())
    }

    @Test
    fun splitAndMergedFramesAreDecoded() = runTest {
        val factory = RecordingFactory()
        val connection = connection(factory)
        val messages = mutableListOf<ControllerMessage>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { connection.messages.collect { messages += it } }
        connection.connect()
        runCurrent()
        val transport = factory.transports.single()
        // Frame 1 byte by byte.
        outputOnEvent(1).encode().toByteArray().forEach { transport.deliver(byteArrayOf(it)) }
        // Frames 2+3 merged, and the beginning of frame 4 in the same chunk.
        val merged = outputOnEvent(2).encode().toByteArray() + outputOnEvent(3).encode().toByteArray()
        val fourth = outputOnEvent(4).encode().toByteArray()
        transport.deliver(merged + fourth.copyOfRange(0, 10))
        runCurrent()
        assertEquals(3, messages.size)
        transport.deliver(fourth.copyOfRange(10, fourth.size))
        runCurrent()
        assertEquals((1..4).map { OutputEvent(1u, it.toUByte(), true) }, messages)
    }

    @Test
    fun decoderErrorClosesAndReconnects() = runTest {
        val factory = answeringFactory()
        val connection = connection(factory)
        connection.connect()
        runCurrent()
        // len4 = 0x10 (< 32-byte header): invalid frame.
        factory.transports[0].deliver(byteArrayOf(0, 0, 0, 0x10, 0, 0, 0, 0x0C))
        runCurrent()
        assertEquals(
            ConnectionState.WaitingToReconnect(1, 2.seconds, ConnectionProblem.DECODER_ERROR),
            connection.state.value,
        )
        assertTrue(factory.transports[0].isClosed)
        advanceTimeBy(2_001)
        assertEquals(ConnectionState.Ready, connection.state.value)
        // The new session has a fresh decoder: valid frames are decoded again.
        val result = connection.request(Request.ping(FAKE_SERIAL), setOf(CommandCode.PING))
        assertEquals(1, result.responses.size)
    }

    @Test
    fun malformedFrameDoesNotAffectTheConnection() = runTest {
        val factory = answeringFactory()
        val connection = connection(factory)
        val states = mutableListOf<ConnectionState>()
        val frames = mutableListOf<Frame>()
        val messages = mutableListOf<ControllerMessage>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { connection.state.collect { states += it } }
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { connection.frames.collect { frames += it } }
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { connection.messages.collect { messages += it } }
        connection.connect()
        runCurrent()
        assertEquals(ConnectionState.Ready, connection.state.value)
        val statesBefore = states.toList()

        // payload length (40) exceeds the 1-byte payload area, but len4/len8 are consistent: framing is intact.
        val bad = outputOnEvent(9).encode().toByteArray().also { it[28] = 40 }
        val transport = factory.transports.single()
        transport.deliver(outputOnEvent(1).encode().toByteArray() + bad + outputOnEvent(2).encode().toByteArray())
        runCurrent()

        assertEquals(statesBefore, states) // no state change at all
        assertEquals(ConnectionState.Ready, connection.state.value)
        assertEquals(1, factory.transports.size)
        assertFalse(transport.isClosed)
        assertEquals(listOf(outputOnEvent(1), outputOnEvent(2)), frames) // the malformed frame is not a normal frame
        assertTrue(messages.contains(OutputEvent(1u, 1u, true)))
        assertTrue(messages.contains(OutputEvent(1u, 2u, true)))
        val malformed = messages.filterIsInstance<ControllerMessage.Malformed>().single()
        assertEquals(ProtocolException.PayloadLengthMismatch(40u, 1), malformed.error)

        // The session keeps working: requests are answered.
        val result = connection.request(Request.ping(FAKE_SERIAL), setOf(CommandCode.PING))
        assertEquals(1, result.responses.size)
        assertEquals(1, factory.transports.size)
    }

    @Test
    fun paddedPayloadAreaIsDeliveredAsNormalMessage() = runTest {
        val factory = RecordingFactory()
        val connection = connection(factory)
        val messages = mutableListOf<ControllerMessage>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { connection.messages.collect { messages += it } }
        connection.connect()
        runCurrent()

        // Real controller: reply to 0x6B has plen 17 but a 19-byte payload area (2 bytes padding).
        val payload = Bytes.of(0x01, 0x01, 0x7a, 0x14, 0xac, 0x41, 0, 0, 0, 0, 0x02, 0, 0, 0, 0xb8, 0x41, 0x00)
        val heater = controllerFrame(CommandCode.HEATER_STATE, payload, address = 1u)
        val padded = heater.encode().toByteArray() + ByteArray(2)
        padded[3] = (padded[3] + 2).toByte() // len4
        padded[7] = (padded[7] + 2).toByte() // len8
        factory.transports.single().deliver(padded + outputOnEvent(3).encode().toByteArray())
        runCurrent()

        assertEquals(ConnectionState.Ready, connection.state.value)
        assertEquals(listOf(HeaterState.parse(heater), OutputEvent(1u, 3u, true)), messages)
    }

    @Test
    fun requestFailsFastWhenConnectionDrops() = runTest {
        val factory = RecordingFactory()
        val connection = connection(factory)
        connection.connect()
        runCurrent()
        val pending = async {
            runCatching { connection.request(Request.firmwareVersion(FAKE_SERIAL), setOf(CommandCode.FIRMWARE_VERSION)) }
        }
        advanceTimeBy(1_000)
        factory.transports.single().closeFromPeer()
        runCurrent()
        val error = pending.await().exceptionOrNull() as ConnectionException
        assertEquals(ConnectionProblem.CONNECTION_LOST, error.problem)
        assertEquals(1_000L, now)
    }

    @Test
    fun sendRequiresReadyConnectionAndMatchingSerial() = runTest {
        val factory = answeringFactory()
        val connection = connection(factory)
        assertThrows<NotConnectedException> { connection.send(Request.ping(FAKE_SERIAL)) }
        connection.connect()
        runCurrent()
        assertThrows<IllegalArgumentException> { connection.send(Request.ping(SerialNumber(1u))) }
        connection.send(Request.ping(FAKE_SERIAL))
        assertEquals(1, factory.transports.single().sentFrames.size)
    }

    @Test
    fun disconnectClosesTransportAndStopsKeepAlive() = runTest {
        val pings = mutableListOf<Long>()
        val factory = answeringFactory(pings)
        val connection = connection(factory)
        connection.connect()
        advanceTimeBy(6_000)
        connection.disconnect()
        assertEquals(ConnectionState.Idle, connection.state.value)
        assertTrue(factory.transports.single().isClosed)
        advanceTimeBy(60_000)
        assertEquals(listOf(5_000L), pings)
        assertEquals(1, factory.transports.size)

        connection.connect() // can be started again
        runCurrent()
        assertEquals(ConnectionState.Ready, connection.state.value)
        assertEquals(2, factory.transports.size)
    }

    @Test
    fun frameAndMessageFlowsCarryEveryIncomingFrame() = runTest {
        val factory = RecordingFactory()
        val connection = connection(factory)
        val frames = mutableListOf<CommandCode>()
        val messages = mutableListOf<ControllerMessage>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { connection.frames.collect { frames += it.command } }
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { connection.messages.collect { messages += it } }
        connection.connect()
        runCurrent()
        factory.transports.single().deliver(pong(), outputOnEvent(7), firmwareResponse())
        runCurrent()
        assertEquals(listOf(CommandCode.PING, CommandCode.OUTPUT_ROSE, CommandCode.FIRMWARE_VERSION), frames)
        assertEquals(ControllerMessage.Pong(FrameAddress.BROADCAST), messages[0])
        assertEquals(OutputEvent(1u, 7u, true), messages[1])
    }
}
