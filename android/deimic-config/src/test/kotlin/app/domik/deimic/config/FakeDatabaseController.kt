package app.domik.deimic.config

import app.domik.deimic.client.InMemoryTransport
import app.domik.deimic.protocol.ByteWriter
import app.domik.deimic.protocol.Bytes
import app.domik.deimic.protocol.CommandCode
import app.domik.deimic.protocol.Frame
import app.domik.deimic.protocol.FrameAddress
import app.domik.deimic.protocol.SerialNumber
import app.domik.deimic.protocol.toBytes

/** Fake serial used in all tests. */
val FAKE_SERIAL = SerialNumber(0x12345678u)

private fun controllerFrame(command: CommandCode, payload: Bytes = Bytes.EMPTY, flags: UInt = 0u) =
    Frame(FAKE_SERIAL, FrameAddress.BROADCAST, command, flags = flags, payload = payload)

/** Acknowledgement (0x1E0) echoing [request], as sent by the controller. */
private fun ackFor(request: Frame): Frame {
    val writer = ByteWriter()
    writer.writeU32(request.address)
    writer.writeU32(request.command.raw)
    writer.writeU32(request.group)
    writer.writeU32(request.flags)
    writer.writeU32(request.payload.size.toUInt())
    writer.writeBytes(request.payload)
    return controllerFrame(CommandCode.ACK, writer.toBytes())
}

/**
 * Controller peer for configuration tests: answers ping, the configuration checksum (0x1C8) and file downloads
 * (0x212). Every command received from the client is recorded in [received].
 */
class FakeDatabaseController(
    var database: ByteArray,
    var checksum: Int = 0x4d62,
) {
    val received = mutableListOf<CommandCode>()

    /** File name echoed in the download response. */
    var echoedName: String = ConfigDownloader.DATABASE_PATH

    /** Replaces the whole download payload (name + content) when set. */
    var rawDownloadPayload: ByteArray? = null

    /** Commands that get no answer at all. */
    var silent: Set<CommandCode> = emptySet()

    /** Deliver the download response in chunks of this many bytes (0 = in one piece). */
    var chunkSize: Int = 0

    /** Frames received from the client, with the payloads. */
    val frames = mutableListOf<Frame>()

    val downloads: Int get() = received.count { it == CommandCode.FILE_DOWNLOAD }

    val checksumRequests: Int get() = received.count { it == CommandCode.CONFIG_CHECKSUM }

    fun handle(transport: InMemoryTransport, frame: Frame) {
        if (frame.command != CommandCode.PING) {
            received += frame.command
            frames += frame
        }
        if (frame.command in silent) return
        val response: Frame? = when (frame.command) {
            CommandCode.PING -> controllerFrame(CommandCode.PING)
            CommandCode.CONFIG_CHECKSUM -> controllerFrame(CommandCode.CONFIG_CHECKSUM, Bytes.of(checksum and 0xFF, checksum shr 8))
            CommandCode.FILE_DOWNLOAD -> controllerFrame(CommandCode.FILE_DOWNLOAD, downloadPayload(), flags = 1u)
            else -> null
        }
        transport.deliver(ackFor(frame))
        if (response == null) return
        if (chunkSize > 0 && response.command == CommandCode.FILE_DOWNLOAD) {
            val bytes = response.encode().toByteArray()
            for (start in bytes.indices step chunkSize) {
                transport.deliver(bytes.copyOfRange(start, minOf(bytes.size, start + chunkSize)))
            }
        } else {
            transport.deliver(response)
        }
    }

    private fun downloadPayload(): Bytes {
        rawDownloadPayload?.let { return it.toBytes() }
        val writer = ByteWriter()
        writer.writeBytes(Bytes.ascii(echoedName))
        writer.writeZeros(64 - echoedName.length)
        writer.writeBytes(database.toBytes())
        return writer.toBytes()
    }
}
