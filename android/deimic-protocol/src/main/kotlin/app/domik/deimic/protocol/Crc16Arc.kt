package app.domik.deimic.protocol

/** CRC-16/ARC (reflected poly 0x8005 -> 0xA001, init 0, no final XOR); checksum of the configuration database. */
object Crc16Arc {
    fun checksum(data: ByteArray): UShort {
        var crc = 0
        for (byte in data) {
            crc = crc xor (byte.toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
            }
        }
        return crc.toUShort()
    }

    fun checksum(data: Bytes): UShort = checksum(data.unsafeArray())
}
