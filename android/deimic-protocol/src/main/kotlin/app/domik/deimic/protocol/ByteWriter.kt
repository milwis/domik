package app.domik.deimic.protocol

/** Byte buffer builder: appending writes (`writeU8`, …) and bounds-checked in-place writes (`setU8`, …). */
class ByteWriter(zeroCount: Int = 0) {
    private var data = ByteArray(maxOf(16, zeroCount))

    /** Number of bytes written so far (starts at `zeroCount`). */
    var count: Int = maxOf(0, zeroCount)
        private set

    fun toBytes(): Bytes = Bytes.copyOf(data, 0, count)

    fun toByteArray(): ByteArray = data.copyOf(count)

    // Appending

    fun writeU8(value: UByte) = append(value.toLong(), 1, Endianness.LITTLE)

    fun writeU16(value: UShort, endian: Endianness = Endianness.LITTLE) = append(value.toLong(), 2, endian)

    fun writeU32(value: UInt, endian: Endianness = Endianness.LITTLE) = append(value.toLong(), 4, endian)

    fun writeI16(value: Short, endian: Endianness = Endianness.LITTLE) = append(value.toLong(), 2, endian)

    fun writeI32(value: Int, endian: Endianness = Endianness.LITTLE) = append(value.toLong(), 4, endian)

    fun writeF32(value: Float, endian: Endianness = Endianness.LITTLE) = writeI32(value.toRawBits(), endian)

    fun writeBytes(value: Bytes) = writeBytes(value.unsafeArray())

    fun writeBytes(value: ByteArray) {
        ensureCapacity(count + value.size)
        value.copyInto(data, count)
        count += value.size
    }

    fun writeZeros(count: Int) {
        if (count <= 0) return
        ensureCapacity(this.count + count)
        data.fill(0, this.count, this.count + count)
        this.count += count
    }

    // In place (bounds-checked)

    fun setU8(value: UByte, at: Int) = set(value.toLong(), 1, at, Endianness.LITTLE)

    fun setI16(value: Short, at: Int, endian: Endianness = Endianness.LITTLE) = set(value.toLong(), 2, at, endian)

    fun setI32(value: Int, at: Int, endian: Endianness = Endianness.LITTLE) = set(value.toLong(), 4, at, endian)

    fun setU32(value: UInt, at: Int, endian: Endianness = Endianness.LITTLE) = set(value.toLong(), 4, at, endian)

    fun setF32(value: Float, at: Int, endian: Endianness = Endianness.LITTLE) = setI32(value.toRawBits(), at, endian)

    fun setBytes(value: Bytes, at: Int) {
        checkRange(at, value.size)
        value.unsafeArray().copyInto(data, at)
    }

    private fun append(value: Long, size: Int, endian: Endianness) {
        ensureCapacity(count + size)
        encode(value, size, endian, count)
        count += size
    }

    private fun set(value: Long, size: Int, at: Int, endian: Endianness) {
        checkRange(at, size)
        encode(value, size, endian, at)
    }

    private fun encode(value: Long, size: Int, endian: Endianness, at: Int) {
        for (index in 0 until size) {
            val byte = (value ushr (8 * index)).toByte()
            val position = if (endian == Endianness.LITTLE) at + index else at + size - 1 - index
            data[position] = byte
        }
    }

    private fun checkRange(at: Int, size: Int) {
        if (at < 0 || at > count || size > count - at) {
            throw ProtocolException.Truncated(offset = at, needed = size, available = count)
        }
    }

    private fun ensureCapacity(required: Int) {
        if (required > data.size) data = data.copyOf(maxOf(required, data.size * 2))
    }
}
