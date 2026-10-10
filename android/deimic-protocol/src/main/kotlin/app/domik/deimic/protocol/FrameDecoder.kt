package app.domik.deimic.protocol

/**
 * Frames and problems found in one chunk by [FrameDecoder.decode].
 *
 * [malformed] holds frames whose header payload length exceeds the frame's payload area; they were skipped
 * without losing sync (their [ControllerMessage.Malformed.frame] carries the whole payload area).
 */
class DecodeResult(val frames: List<Frame>, val malformed: List<ControllerMessage.Malformed>)

/**
 * Streaming frame decoder: feed raw TCP/TLS bytes, get complete frames.
 *
 * Handles frames split across chunks and several frames in one chunk. Framing is by the length fields only:
 * total = len4 + 4 must be >= 32 and <= [maxFrameLength], and len8 == len4 - 4 (checked as soon as 8 bytes are
 * buffered); any violation desynchronizes the stream.
 *
 * The header payload length (plen) is trusted only up to the payload area (total - 32): the real controller
 * sometimes pads the area (e.g. plen 17 in a 19-byte area, reply to 0x6B). If plen <= area, the payload is the
 * first plen bytes and the trailing padding is ignored. If plen > area, the frame is malformed but framing is
 * intact: exactly `total` bytes are skipped and the frame is reported in [DecodeResult.malformed] (not by [append]).
 *
 * After [append] / [decode] throws, the stream is out of sync: the decoder keeps rethrowing the same error
 * (frames decoded earlier in that chunk are discarded) until [reset]. Close the connection and reset before
 * reusing it.
 */
class FrameDecoder(maxFrameLength: Int = DEFAULT_MAX_FRAME_LENGTH) {
    val maxFrameLength: Int = maxOf(Frame.HEADER_LENGTH, maxFrameLength)

    private var buffer = ByteArray(0)
    private var buffered = 0

    var failure: ProtocolException? = null
        private set

    /** Number of bytes buffered while waiting for the rest of a frame. */
    val bufferedByteCount: Int get() = buffered

    fun reset() {
        buffer = ByteArray(0)
        buffered = 0
        failure = null
    }

    fun append(bytes: Bytes): List<Frame> = append(bytes.unsafeArray())

    /** Like [decode], but frames with a payload length exceeding the payload area are silently skipped. */
    fun append(chunk: ByteArray, fromIndex: Int = 0, toIndex: Int = chunk.size): List<Frame> =
        decode(chunk, fromIndex, toIndex).frames

    fun decode(chunk: ByteArray, fromIndex: Int = 0, toIndex: Int = chunk.size): DecodeResult {
        failure?.let { throw it }
        val length = toIndex - fromIndex
        if (buffered + length > buffer.size) {
            buffer = buffer.copyOf(maxOf(buffered + length, buffer.size * 2))
        }
        chunk.copyInto(buffer, buffered, fromIndex, toIndex)
        buffered += length

        val frames = mutableListOf<Frame>()
        val malformed = mutableListOf<ControllerMessage.Malformed>()
        var start = 0
        try {
            while (true) {
                val decoded = decodeFrame(start) ?: break
                if (decoded.error == null) {
                    frames += decoded.frame
                } else {
                    malformed += ControllerMessage.Malformed(decoded.frame, decoded.error)
                }
                start += decoded.length
            }
        } catch (error: ProtocolException) {
            failure = error
            buffer = ByteArray(0)
            buffered = 0
            throw error
        }
        if (start > 0) {
            buffer.copyInto(buffer, 0, start, buffered)
            buffered -= start
        }
        return DecodeResult(frames, malformed)
    }

    /** A frame found in the buffer: [length] bytes were consumed (header + whole payload area). */
    private class Decoded(val length: Int, val frame: Frame, val error: ProtocolException.PayloadLengthMismatch?)

    /** Returns the frame starting at [start], or null if more bytes are needed. */
    private fun decodeFrame(start: Int): Decoded? {
        val available = buffered - start
        if (available < 8) return null
        val header = ByteReader(Bytes.copyOf(buffer, start, start + minOf(available, Frame.HEADER_LENGTH)))
        val len4 = header.u32(0, Endianness.BIG)
        val len8 = header.u32(4, Endianness.BIG)
        val total = len4.toLong() + 4
        if (total < Frame.HEADER_LENGTH) throw ProtocolException.FrameTooShort(total)
        if (total > maxFrameLength) throw ProtocolException.FrameTooLarge(total, maxFrameLength)
        if (len8.toLong() != len4.toLong() - 4) throw ProtocolException.LengthMismatch(len4, len8)
        if (available < total) return null
        val area = (total - Frame.HEADER_LENGTH).toInt()
        val declared = header.u32(28)
        val error = if (declared.toLong() > area) {
            ProtocolException.PayloadLengthMismatch(declared, area.toLong())
        } else {
            null
        }
        // Malformed: keep the whole area for diagnostics; otherwise only the declared bytes (trailing padding dropped).
        val payloadLength = if (error == null) declared.toInt() else area
        val frame = Frame(
            serial = SerialNumber(header.u32(8)),
            address = header.u32(12),
            command = CommandCode(header.u32(16)),
            group = header.u32(20),
            flags = header.u32(24),
            payload = Bytes.copyOf(buffer, start + Frame.HEADER_LENGTH, start + Frame.HEADER_LENGTH + payloadLength),
        )
        return Decoded(total.toInt(), frame, error)
    }

    companion object {
        const val DEFAULT_MAX_FRAME_LENGTH: Int = 4 * 1024 * 1024
    }
}
