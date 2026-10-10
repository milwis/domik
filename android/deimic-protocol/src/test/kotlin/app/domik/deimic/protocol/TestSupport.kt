package app.domik.deimic.protocol

/** Fake serial used in all tests and fixtures. */
val FAKE_SERIAL = SerialNumber(0x12345678u)

/** Hex string with optional whitespace, e.g. "00 1c ab". */
fun hex(text: String): Bytes {
    val digits = text.filterNot { it.isWhitespace() }
    require(digits.length % 2 == 0) { "odd hex length" }
    return Bytes.copyOf(ByteArray(digits.length / 2) { digits.substring(2 * it, 2 * it + 2).toInt(16).toByte() })
}

fun floatFromBitsHex(bits: String): Float = Float.fromBits(bits.toLong(16).toInt())

fun Float.bitsHex(): String = toRawBits().toUInt().toString(16).padStart(8, '0')

/** Builds a synthetic collective-state record of [format] with the given module address and ON outputs. */
fun syntheticRecord(
    format: StateFormat,
    module: Int,
    on: List<Int> = emptyList(),
    heaters: List<Int> = emptyList(),
    temperatures: List<Float> = emptyList(),
): Bytes {
    val writer = ByteWriter(zeroCount = format.recordSize)
    format.extent(StateField.ADDRESS)?.let { writer.setI32(module, it.offset) }
    format.extent(StateField.OUTPUTS)?.let { extent -> on.forEach { writer.setU8(1u, extent.offset + it) } }
    format.extent(StateField.HEATERS)?.let { extent ->
        heaters.forEachIndexed { index, value -> writer.setU8(value.toUByte(), extent.offset + index) }
    }
    format.extent(StateField.TEMPERATURES)?.let { extent ->
        temperatures.forEachIndexed { index, value -> writer.setF32(value, extent.offset + 4 * index) }
    }
    return writer.toBytes()
}

/** Deterministic PRNG for reproducible chunking. */
class SplitMix64(private var state: ULong) {
    fun next(): ULong {
        state += 0x9E3779B97F4A7C15uL
        var z = state
        z = (z xor (z shr 30)) * 0xBF58476D1CE4E5B9uL
        z = (z xor (z shr 27)) * 0x94D049BB133111EBuL
        return z xor (z shr 31)
    }
}

/**
 * Minimal JSON reader for test fixtures (objects, arrays, strings, numbers, booleans, null).
 * Integers become [Long], other numbers [Double].
 */
class MiniJson private constructor(private val text: String) {
    private var pos = 0

    private fun parseDocument(): Any? {
        val value = parseValue()
        skipWhitespace()
        require(pos == text.length) { "trailing data at $pos" }
        return value
    }

    private fun parseValue(): Any? {
        skipWhitespace()
        require(pos < text.length) { "unexpected end" }
        return when (val char = text[pos]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> if (char == '-' || char.isDigit()) parseNumber() else error("unexpected '$char' at $pos")
        }
    }

    private fun parseObject(): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        pos++
        skipWhitespace()
        if (text[pos] == '}') return result.also { pos++ }
        while (true) {
            skipWhitespace()
            val key = parseString()
            skipWhitespace()
            expect(':')
            result[key] = parseValue()
            skipWhitespace()
            if (text[pos] == ',') pos++ else return result.also { expect('}') }
        }
    }

    private fun parseArray(): List<Any?> {
        val result = ArrayList<Any?>()
        pos++
        skipWhitespace()
        if (text[pos] == ']') return result.also { pos++ }
        while (true) {
            result += parseValue()
            skipWhitespace()
            if (text[pos] == ',') pos++ else return result.also { expect(']') }
        }
    }

    private fun parseString(): String {
        expect('"')
        val builder = StringBuilder()
        while (true) {
            val char = text[pos++]
            when (char) {
                '"' -> return builder.toString()
                '\\' -> {
                    when (val escaped = text[pos++]) {
                        '"', '\\', '/' -> builder.append(escaped)
                        'b' -> builder.append('\b')
                        'f' -> builder.append('\u000C')
                        'n' -> builder.append('\n')
                        'r' -> builder.append('\r')
                        't' -> builder.append('\t')
                        'u' -> builder.append(text.substring(pos, pos + 4).toInt(16).toChar()).also { pos += 4 }
                        else -> error("bad escape '$escaped' at $pos")
                    }
                }
                else -> builder.append(char)
            }
        }
    }

    private fun parseNumber(): Any {
        val start = pos
        while (pos < text.length && (text[pos].isDigit() || text[pos] in "+-.eE")) pos++
        val token = text.substring(start, pos)
        return if (token.any { it in ".eE" }) token.toDouble() else token.toLong()
    }

    private fun literal(word: String, value: Any?): Any? {
        require(text.startsWith(word, pos)) { "bad literal at $pos" }
        pos += word.length
        return value
    }

    private fun expect(char: Char) {
        require(pos < text.length && text[pos] == char) { "expected '$char' at $pos" }
        pos++
    }

    private fun skipWhitespace() {
        while (pos < text.length && text[pos].isWhitespace()) pos++
    }

    companion object {
        fun parse(text: String): Any? = MiniJson(text).parseDocument()
    }
}
