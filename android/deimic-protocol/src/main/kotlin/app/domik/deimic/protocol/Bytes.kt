package app.domik.deimic.protocol

/**
 * Immutable byte sequence with value semantics (`equals`/`hashCode` compare contents).
 * Used for payloads so that frames and messages can be plain data classes.
 */
class Bytes private constructor(private val data: ByteArray) {
    val size: Int get() = data.size

    fun isEmpty(): Boolean = data.isEmpty()

    operator fun get(index: Int): UByte = data[index].toUByte()

    /** Returns a copy of the contents. */
    fun toByteArray(): ByteArray = data.copyOf()

    fun copyOfRange(fromIndex: Int, toIndex: Int): Bytes = Bytes(data.copyOfRange(fromIndex, toIndex))

    operator fun plus(other: Bytes): Bytes = Bytes(data + other.data)

    /** Lowercase hex without separators; with [separator] e.g. "01 02 ff". */
    fun toHex(separator: String = "", limit: Int = Int.MAX_VALUE): String {
        val count = minOf(limit, data.size)
        val builder = StringBuilder(count * (2 + separator.length))
        for (index in 0 until count) {
            if (index > 0) builder.append(separator)
            val value = data[index].toInt() and 0xFF
            builder.append(HEX_DIGITS[value ushr 4]).append(HEX_DIGITS[value and 0x0F])
        }
        return builder.toString()
    }

    internal fun unsafeArray(): ByteArray = data

    override fun equals(other: Any?): Boolean = other is Bytes && data.contentEquals(other.data)

    override fun hashCode(): Int = data.contentHashCode()

    override fun toString(): String =
        "Bytes($size: ${toHex(" ", PREVIEW_LENGTH)}${if (size > PREVIEW_LENGTH) "…" else ""})"

    companion object {
        private const val HEX_DIGITS = "0123456789abcdef"
        private const val PREVIEW_LENGTH = 32

        val EMPTY: Bytes = Bytes(ByteArray(0))

        /** Copies [array] (or its range). */
        fun copyOf(array: ByteArray, fromIndex: Int = 0, toIndex: Int = array.size): Bytes =
            Bytes(array.copyOfRange(fromIndex, toIndex))

        /** Builds bytes from integers 0..255 (higher bits are truncated). */
        fun of(vararg values: Int): Bytes = Bytes(ByteArray(values.size) { values[it].toByte() })

        fun zeros(count: Int): Bytes = Bytes(ByteArray(count))

        fun ascii(text: String): Bytes = Bytes(text.toByteArray(Charsets.US_ASCII))

        /** Takes ownership of [array] without copying; the caller must not modify it afterwards. */
        internal fun wrap(array: ByteArray): Bytes = Bytes(array)
    }
}

fun ByteArray.toBytes(): Bytes = Bytes.copyOf(this)
