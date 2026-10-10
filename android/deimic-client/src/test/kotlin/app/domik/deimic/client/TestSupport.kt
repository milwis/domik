package app.domik.deimic.client

import app.domik.deimic.protocol.Bytes
import app.domik.deimic.protocol.ByteWriter
import app.domik.deimic.protocol.CommandCode
import app.domik.deimic.protocol.Frame
import app.domik.deimic.protocol.FrameAddress
import app.domik.deimic.protocol.SerialNumber
import kotlinx.coroutines.test.TestScope

/** Fake serial used in all tests. */
val FAKE_SERIAL = SerialNumber(0x12345678u)

val TEST_ENDPOINT = ControllerEndpoint("192.0.2.10")

/** Acknowledgement (0x1E0) echoing [request], as sent by the controller. */
fun ackFor(request: Frame): Frame {
    val writer = ByteWriter()
    writer.writeU32(request.address)
    writer.writeU32(request.command.raw)
    writer.writeU32(request.group)
    writer.writeU32(request.flags)
    writer.writeU32(request.payload.size.toUInt())
    writer.writeBytes(request.payload)
    return Frame(request.serial, FrameAddress.BROADCAST, CommandCode.ACK, payload = writer.toBytes())
}

fun controllerFrame(command: CommandCode, payload: Bytes = Bytes.EMPTY, address: UInt = FrameAddress.BROADCAST) =
    Frame(FAKE_SERIAL, address, command, payload = payload)

fun pong(): Frame = controllerFrame(CommandCode.PING)

fun firmwareResponse(text: String = "Dec 13 2017"): Frame {
    val writer = ByteWriter()
    writer.writeBytes(Bytes.ascii(text))
    writer.writeZeros(64 - text.length)
    return controllerFrame(CommandCode.FIRMWARE_VERSION, writer.toBytes())
}

fun checksumResponse(value: Int = 0x4d62): Frame = controllerFrame(CommandCode.CONFIG_CHECKSUM, Bytes.of(value and 0xFF, value shr 8))

fun outputOnEvent(relay: Int): Frame = controllerFrame(CommandCode.OUTPUT_ROSE, Bytes.of(relay), address = 1u)

/**
 * Scriptable controller peer. Answers like the real one: acknowledgement first (if [sendAcks]), then the
 * response. Commands in [silent] get no answer at all; [respondToPing] = false simulates a controller that
 * ignores our frames (e.g. wrong serial number).
 */
class FakeController(
    var sendAcks: Boolean = true,
    var respondToPing: Boolean = true,
    var silent: Set<CommandCode> = emptySet(),
    var firmware: String = "Dec 13 2017",
    var checksum: Int = 0x4d62,
) {
    fun handle(transport: InMemoryTransport, frame: Frame) {
        if (frame.command in silent) return
        if (frame.command == CommandCode.PING && !respondToPing) return
        val response = when (frame.command) {
            CommandCode.PING -> pong()
            CommandCode.FIRMWARE_VERSION -> firmwareResponse(firmware)
            CommandCode.CONFIG_CHECKSUM -> checksumResponse(checksum)
            else -> null
        }
        if (sendAcks) transport.deliver(ackFor(frame))
        response?.let { transport.deliver(it) }
    }
}

/** Creates in-memory transports on demand and remembers them (one per connection attempt). */
class RecordingFactory(
    private val onConnect: suspend (attempt: Int) -> Unit = {},
    private val peer: InMemoryTransport.(Frame) -> Unit = {},
) : FrameTransportFactory {
    val transports = mutableListOf<InMemoryTransport>()

    override fun create(endpoint: ControllerEndpoint): FrameTransport {
        val attempt = transports.size
        return InMemoryTransport(onConnect = { onConnect(attempt) }, onFrame = peer).also { transports += it }
    }
}

/** Virtual time of the test scheduler in milliseconds. */
val TestScope.now: Long get() = testScheduler.currentTime
