package app.domik.deimic.protocol

enum class Endianness { LITTLE, BIG }

/**
 * Bounds-checked reader over a byte buffer.
 *
 * Offers absolute reads (`u8(at)`, `u32(at, endian)`, …) and sequential reads (`readU8()`, …) that advance
 * [offset]. Every out-of-range access throws [ProtocolException.Truncated].
 */
class ByteReader private constructor(private val data: ByteArray) {
    constructor(bytes: Bytes) : this(bytes.unsafeArray())

    var offset: Int = 0
        private set

    val count: Int get() = data.size
    val remaining: Int get() = data.size - offset

    // Absolute reads

    fun u8(at: Int): UByte = integer(at, 1, Endianness.LITTLE).toUByte()

    fun u16(at: Int, endian: Endianness = Endianness.LITTLE): UShort = integer(at, 2, endian).toUShort()

    fun u32(at: Int, endian: Endianness = Endianness.LITTLE): UInt = integer(at, 4, endian).toUInt()

    fun i16(at: Int, endian: Endianness = Endianness.LITTLE): Short = integer(at, 2, endian).toShort()

    fun i32(at: Int, endian: Endianness = Endianness.LITTLE): Int = integer(at, 4, endian).toInt()

    fun f32(at: Int, endian: Endianness = Endianness.LITTLE): Float = Float.fromBits(i32(at, endian))

    fun bytes(at: Int, count: Int): Bytes {
        check(at, count)
        return Bytes.copyOf(data, at, at + count)
    }

    // Sequential reads

    fun readU8(): UByte = u8(offset).also { offset += 1 }

    fun readU16(endian: Endianness = Endianness.LITTLE): UShort = u16(offset, endian).also { offset += 2 }

    fun readU32(endian: Endianness = Endianness.LITTLE): UInt = u32(offset, endian).also { offset += 4 }

    fun readI16(endian: Endianness = Endianness.LITTLE): Short = i16(offset, endian).also { offset += 2 }

    fun readI32(endian: Endianness = Endianness.LITTLE): Int = i32(offset, endian).also { offset += 4 }

    fun readF32(endian: Endianness = Endianness.LITTLE): Float = f32(offset, endian).also { offset += 4 }

    fun readBytes(count: Int): Bytes = bytes(offset, count).also { offset += count }

    fun skip(count: Int) {
        check(offset, count)
        offset += count
    }

    private fun integer(at: Int, size: Int, endian: Endianness): Long {
        check(at, size)
        var acc = 0L
        when (endian) {
            Endianness.LITTLE -> for (index in 0 until size) {
                acc = acc or ((data[at + index].toLong() and 0xFF) shl (8 * index))
            }
            Endianness.BIG -> for (index in 0 until size) {
                acc = (acc shl 8) or (data[at + index].toLong() and 0xFF)
            }
        }
        return acc
    }

    private fun check(at: Int, size: Int) {
        if (at < 0 || size < 0 || at > data.size || size > data.size - at) {
            throw ProtocolException.Truncated(offset = at, needed = size, available = data.size)
        }
    }

    companion object {
        /** Reader over a copy of [array]. */
        fun of(array: ByteArray): ByteReader = ByteReader(array.copyOf())

        fun of(vararg values: Int): ByteReader = ByteReader(Bytes.of(*values))
    }
}
