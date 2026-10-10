package app.domik.deimic.config.sqlite

/**
 * Bounds-checked sequential reader over `buffer[position, limit)`. Every access past [limit] throws
 * [SqliteFormatException] – malformed files can never cause an `IndexOutOfBoundsException`.
 */
internal class ByteCursor(private val buffer: ByteArray, var position: Int, private val limit: Int) {
    init {
        require(position in 0..limit && limit <= buffer.size)
    }

    val remaining: Int get() = limit - position

    fun u8(): Int {
        if (position >= limit) throw SqliteFormatException("unexpected end of data at offset $position")
        return buffer[position++].toInt() and 0xFF
    }

    /**
     * SQLite varint: 1–9 bytes, big-endian groups of 7 bits (high bit = continuation); the 9th byte, if reached,
     * contributes all 8 bits. The result is the signed 64-bit value.
     */
    fun varint(): Long {
        var value = 0L
        repeat(8) {
            val byte = u8()
            value = (value shl 7) or (byte and 0x7F).toLong()
            if (byte and 0x80 == 0) return value
        }
        return (value shl 8) or u8().toLong()
    }
}

internal fun ByteArray.u16(at: Int): Int = ((this[at].toInt() and 0xFF) shl 8) or (this[at + 1].toInt() and 0xFF)

internal fun ByteArray.u32(at: Int): Long =
    ((this[at].toLong() and 0xFF) shl 24) or ((this[at + 1].toLong() and 0xFF) shl 16) or
        ((this[at + 2].toLong() and 0xFF) shl 8) or (this[at + 3].toLong() and 0xFF)

/** Decoder of the record format (header of serial types followed by the values). */
internal object RecordDecoder {
    /**
     * Decodes the record stored in `buffer[start, start + length)`.
     *
     * Serial types: 0 NULL, 1–4 big-endian two's complement integers of 1/2/3/4 bytes, 5 = 6 bytes, 6 = 8 bytes,
     * 7 = IEEE-754 double, 8 = integer 0, 9 = integer 1, 10/11 reserved (error), N >= 12 even = BLOB of (N-12)/2
     * bytes, N >= 13 odd = text of (N-13)/2 bytes.
     */
    fun decode(buffer: ByteArray, start: Int, length: Int): Array<SqliteValue> {
        val cursor = ByteCursor(buffer, start, start + length)
        val headerSize = cursor.varint()
        if (headerSize < 1 || headerSize > length) {
            throw SqliteFormatException("record header size $headerSize does not fit the $length-byte payload")
        }
        val headerEnd = start + headerSize.toInt()
        if (cursor.position > headerEnd) throw SqliteFormatException("record header size $headerSize is too small")
        val headerCursor = ByteCursor(buffer, cursor.position, headerEnd)
        val types = ArrayList<Long>()
        while (headerCursor.remaining > 0) types += headerCursor.varint()

        val body = ByteCursor(buffer, headerEnd, start + length)
        return Array(types.size) { index -> value(buffer, body, types[index]) }
    }

    private fun value(buffer: ByteArray, body: ByteCursor, type: Long): SqliteValue = when (type) {
        0L -> SqliteValue.Null
        1L -> SqliteValue.Integer(signed(buffer, body, 1))
        2L -> SqliteValue.Integer(signed(buffer, body, 2))
        3L -> SqliteValue.Integer(signed(buffer, body, 3))
        4L -> SqliteValue.Integer(signed(buffer, body, 4))
        5L -> SqliteValue.Integer(signed(buffer, body, 6))
        6L -> SqliteValue.Integer(signed(buffer, body, 8))
        7L -> SqliteValue.Real(Double.fromBits(signed(buffer, body, 8)))
        8L -> SqliteValue.Integer(0)
        9L -> SqliteValue.Integer(1)
        10L, 11L -> throw SqliteFormatException("reserved serial type $type")
        else -> {
            val size = (type - 12) / 2
            if (size < 0 || size > body.remaining) {
                throw SqliteFormatException("value of $size bytes does not fit the record (${body.remaining} bytes left)")
            }
            val from = body.position
            body.position += size.toInt()
            if (type % 2 == 0L) {
                SqliteValue.Blob(buffer.copyOfRange(from, from + size.toInt()))
            } else {
                SqliteValue.Text(String(buffer, from, size.toInt(), Charsets.UTF_8))
            }
        }
    }

    /** Big-endian two's complement integer of [size] bytes (sign-extended to 64 bits). */
    private fun signed(buffer: ByteArray, body: ByteCursor, size: Int): Long {
        if (size > body.remaining) throw SqliteFormatException("integer of $size bytes does not fit the record")
        var result = if (buffer[body.position].toInt() < 0) -1L else 0L
        repeat(size) { result = (result shl 8) or (buffer[body.position++].toLong() and 0xFF) }
        return result
    }
}
