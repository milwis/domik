package app.domik.deimic.protocol

/** Errors produced by the protocol layer (framing, byte access, payload parsing, request building). */
sealed class ProtocolException(message: String) : Exception(message) {
    /** A read or write at [offset] needed [needed] bytes but only [available] bytes exist. */
    data class Truncated(val offset: Int, val needed: Int, val available: Int) :
        ProtocolException("truncated: offset $offset needs $needed of $available bytes")

    /** Frame length derived from the header is shorter than the 32-byte header. */
    data class FrameTooShort(val length: Long) : ProtocolException("frame too short: $length")

    /** Header fields len4 (total - 4) and len8 (total - 8) are inconsistent. */
    data class LengthMismatch(val len4: UInt, val len8: UInt) :
        ProtocolException("length mismatch: len4=$len4 len8=$len8")

    /** Header payload length exceeds the payload area (total - 32); a shorter payload (trailing padding) is fine. */
    data class PayloadLengthMismatch(val declared: UInt, val available: Long) :
        ProtocolException("payload length mismatch: declared $declared, only $available available")

    /** Frame length exceeds the decoder limit. */
    data class FrameTooLarge(val length: Long, val limit: Int) :
        ProtocolException("frame too large: $length > $limit")

    /** A payload parser was given a frame with a different command. */
    data class UnexpectedCommand(val command: CommandCode) : ProtocolException("unexpected command $command")

    /** Payload is shorter than the minimum required by the message. */
    data class PayloadTooShort(val command: CommandCode, val minimum: Int, val actual: Int) :
        ProtocolException("payload of $command too short: $actual < $minimum")

    /** Payload has the right size but invalid content. */
    data class InvalidPayload(val command: CommandCode, val reason: String) :
        ProtocolException("invalid payload of $command: $reason")

    /** File path for 0x212 is not ASCII, contains NUL or is 64+ bytes long. */
    data class InvalidFilePath(val reason: String) : ProtocolException("invalid file path: $reason")
}
