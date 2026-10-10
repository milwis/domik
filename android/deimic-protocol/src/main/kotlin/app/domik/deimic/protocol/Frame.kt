package app.domik.deimic.protocol

/**
 * A protocol frame: 32-byte header followed by the payload.
 *
 * Header layout: `[0..3]` u32 BE total-4, `[4..7]` u32 BE total-8, then u32 LE: serial, address, command,
 * group, flags, payload length.
 *
 * [toString] never includes the serial number.
 */
data class Frame(
    val serial: SerialNumber,
    val address: UInt,
    val command: CommandCode,
    val group: UInt = 0u,
    val flags: UInt = 0u,
    val payload: Bytes = Bytes.EMPTY,
) {
    val totalLength: Int get() = HEADER_LENGTH + payload.size

    fun encode(): Bytes {
        val total = totalLength.toUInt()
        val writer = ByteWriter()
        writer.writeU32(total - 4u, Endianness.BIG)
        writer.writeU32(total - 8u, Endianness.BIG)
        writer.writeU32(serial.value)
        writer.writeU32(address)
        writer.writeU32(command.raw)
        writer.writeU32(group)
        writer.writeU32(flags)
        writer.writeU32(payload.size.toUInt())
        writer.writeBytes(payload)
        return writer.toBytes()
    }

    override fun toString(): String {
        val more = if (payload.size > PREVIEW_LENGTH) "…" else ""
        return "Frame(cmd=$command addr=0x${address.toString(16)} grp=$group flg=$flags " +
            "len=${payload.size} dat=${payload.toHex(" ", PREVIEW_LENGTH)}$more)"
    }

    companion object {
        const val HEADER_LENGTH: Int = 32
        private const val PREVIEW_LENGTH = 48
    }
}
