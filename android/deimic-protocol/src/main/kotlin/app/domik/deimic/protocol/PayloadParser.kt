package app.domik.deimic.protocol

/** Parser of a controller message type; implemented by the companion objects of the message classes. */
interface PayloadParser<out T : ControllerMessage> {
    /** Commands this parser accepts. */
    val commands: Set<CommandCode>

    /** Parses the frame. Never crashes on bad input; throws [ProtocolException] with a reason instead. */
    fun parse(frame: Frame): T
}

internal fun PayloadParser<*>.requireCommand(frame: Frame) {
    if (frame.command !in commands) throw ProtocolException.UnexpectedCommand(frame.command)
}

internal fun PayloadParser<*>.payloadReader(frame: Frame, minimumLength: Int): ByteReader {
    requireCommand(frame)
    if (frame.payload.size < minimumLength) {
        throw ProtocolException.PayloadTooShort(frame.command, minimumLength, frame.payload.size)
    }
    return ByteReader(frame.payload)
}

/** Decodes NUL-terminated ASCII (bytes after the first NUL are ignored; non-ASCII bytes become U+FFFD). */
internal fun asciiString(bytes: Bytes): String {
    val builder = StringBuilder()
    for (index in 0 until bytes.size) {
        val value = bytes[index].toInt()
        if (value == 0) break
        builder.append(if (value < 0x80) value.toChar() else '�')
    }
    return builder.toString()
}
