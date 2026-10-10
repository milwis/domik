package app.domik.deimic.config.sqlite

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RecordDecoderTest {
    private fun decode(bytes: ByteArray): Array<SqliteValue> = RecordDecoder.decode(bytes, 0, bytes.size)

    private fun varintOf(vararg bytes: Int): Long {
        val array = ByteArray(bytes.size) { bytes[it].toByte() }
        return ByteCursor(array, 0, array.size).varint()
    }

    @Test
    fun varintsOfEveryLength() {
        assertEquals(0L, varintOf(0x00))
        assertEquals(127L, varintOf(0x7F))
        assertEquals(128L, varintOf(0x81, 0x00))
        assertEquals(0x3FFFL, varintOf(0xFF, 0x7F))
        assertEquals(0x4000L, varintOf(0x81, 0x80, 0x00))
        assertEquals((1L shl 49) - 1, varintOf(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x7F))
        assertEquals((1L shl 56) - 1, varintOf(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x7F))
        // Nine bytes: the last byte carries all eight bits.
        assertEquals((((1L shl 56) - 1) shl 8) or 0xFF, varintOf(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF))
        assertEquals(-1L, varintOf(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF))
    }

    @Test
    fun builderVarintsRoundTrip() {
        val values = listOf(0L, 1L, 127, 128, 255, 16383, 16384, 1L shl 20, (1L shl 49) - 1, 1L shl 49, (1L shl 56) - 1, 1L shl 56, -1L, Long.MAX_VALUE, Long.MIN_VALUE)
        for (value in values) {
            val encoded = SqliteBuilder.varint(value)
            val cursor = ByteCursor(encoded, 0, encoded.size)
            assertEquals(value, cursor.varint(), "value $value")
            assertEquals(0, cursor.remaining, "length of the encoding of $value")
        }
    }

    @Test
    fun truncatedVarintIsAnError() {
        assertThrows<SqliteFormatException> { varintOf(0x81) }
        assertThrows<SqliteFormatException> { varintOf(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF) }
    }

    @Test
    fun integerSerialTypesAndSignExtension() {
        val values = listOf<Long>(0, 1, -1, 127, -128, 128, -129, 32767, -32768, 32768, 8388607, -8388608, 8388608,
            Int.MAX_VALUE.toLong(), Int.MIN_VALUE.toLong(), 1L shl 40, -(1L shl 40), (1L shl 47) - 1, -(1L shl 47),
            1L shl 47, Long.MAX_VALUE, Long.MIN_VALUE)
        val decoded = decode(SqliteBuilder.recordOf(values))
        assertEquals(values.map { SqliteValue.Integer(it) }, decoded.toList())
    }

    @Test
    fun serialTypeCoverage() {
        val blob = ByteArray(300) { it.toByte() }
        val text = "Zażółć gęślą jaźń € 😀"
        val decoded = decode(SqliteBuilder.record(null, 42, 3.25, text, blob, "", ByteArray(0), -0.0, Double.NaN))
        assertEquals(SqliteValue.Null, decoded[0])
        assertEquals(SqliteValue.Integer(42), decoded[1])
        assertEquals(SqliteValue.Real(3.25), decoded[2])
        assertEquals(SqliteValue.Text(text), decoded[3])
        assertEquals(SqliteValue.Blob(blob), decoded[4])
        assertEquals(SqliteValue.Text(""), decoded[5])
        assertEquals(SqliteValue.Blob(ByteArray(0)), decoded[6])
        assertEquals(-0.0, (decoded[7] as SqliteValue.Real).value, 0.0)
        assertTrue(1.0 / (decoded[7] as SqliteValue.Real).value < 0, "negative zero is preserved")
        assertTrue((decoded[8] as SqliteValue.Real).value.isNaN())
    }

    @Test
    fun serialTypesEightAndNineAreConstants() {
        val record = SqliteBuilder.withHeader(byteArrayOf(8, 9, 0), ByteArray(0))
        assertEquals(listOf<SqliteValue>(SqliteValue.Integer(0), SqliteValue.Integer(1), SqliteValue.Null), decode(record).toList())
    }

    @Test
    fun emptyRecord() {
        assertEquals(0, decode(byteArrayOf(1)).size)
    }

    @Test
    fun reservedSerialTypesAreRejected() {
        assertThrows<SqliteFormatException> { decode(SqliteBuilder.withHeader(byteArrayOf(10), ByteArray(0))) }
        assertThrows<SqliteFormatException> { decode(SqliteBuilder.withHeader(byteArrayOf(11), ByteArray(0))) }
    }

    @Test
    fun valuesThatDoNotFitAreRejected() {
        // 4-byte integer with 3 body bytes
        assertThrows<SqliteFormatException> { decode(SqliteBuilder.withHeader(byteArrayOf(4), ByteArray(3))) }
        // text of 5 bytes with 4 body bytes
        assertThrows<SqliteFormatException> { decode(SqliteBuilder.withHeader(byteArrayOf(23), ByteArray(4))) }
        // huge blob length
        assertThrows<SqliteFormatException> {
            decode(SqliteBuilder.withHeader(SqliteBuilder.varint(Long.MAX_VALUE - 1), ByteArray(10)))
        }
        // negative serial type (varint with the top bit set)
        assertThrows<SqliteFormatException> {
            decode(SqliteBuilder.withHeader(SqliteBuilder.varint(-5L), ByteArray(10)))
        }
    }

    @Test
    fun badHeaderSizes() {
        assertThrows<SqliteFormatException> { decode(byteArrayOf(0)) } // header size 0
        assertThrows<SqliteFormatException> { decode(byteArrayOf(5, 1)) } // header larger than the payload
        assertThrows<SqliteFormatException> { decode(byteArrayOf()) } // nothing at all
        // header size 1 written as a two-byte varint: the varint itself is longer than the declared header
        assertThrows<SqliteFormatException> { decode(byteArrayOf(0x80.toByte(), 0x01, 0, 0)) }
    }

    @Test
    fun decodesAtAnOffsetOfALargerBuffer() {
        val record = SqliteBuilder.record("abc", 7)
        val buffer = ByteArray(10) { 0x55 } + record + ByteArray(10) { 0x66 }
        val decoded = RecordDecoder.decode(buffer, 10, record.size)
        assertEquals(listOf<SqliteValue>(SqliteValue.Text("abc"), SqliteValue.Integer(7)), decoded.toList())
    }

    @Test
    fun invalidUtf8DoesNotThrow() {
        val bad = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x41)
        val decoded = decode(SqliteBuilder.record(bad.copyOf()).also { it[1] = 19 }) // retype the blob as text
        assertTrue(decoded[0] is SqliteValue.Text)
    }

    @Test
    fun blobValuesAreIndependentCopies() {
        val record = SqliteBuilder.record(byteArrayOf(1, 2, 3))
        val decoded = decode(record)
        record[record.size - 1] = 99
        assertArrayEquals(byteArrayOf(1, 2, 3), (decoded[0] as SqliteValue.Blob).bytes)
    }
}
