package app.domik.deimic.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class SerialNumberTest {
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "12345678|12345678",
            "'  0x12 34 56 78\t'|12345678",
            "0XabCDef01|ABCDEF01",
            "f|F",
            "FFFFFFFF|FFFFFFFF",
            "00000001|1",
        ],
    )
    fun validInput(text: String, expectedHex: String) {
        val expected = expectedHex.toUInt(16)
        assertEquals(expected, SerialNumber.parse(text).value)
        assertEquals(expected, SerialNumber.parseOrNull(text)?.value)
    }

    @Test
    fun newlinesAreWhitespace() {
        assertEquals(0x12345678u, SerialNumber.parse("  0x12 34 56 78\n").value)
    }

    @Test
    fun invalidInput() {
        fun reason(text: String) = assertThrows<InvalidSerialNumberException> { SerialNumber.parse(text) }
        assertEquals(InvalidSerialNumberException.Reason.EMPTY, reason("").reason)
        assertEquals(InvalidSerialNumberException.Reason.EMPTY, reason("  0x ").reason)
        val tooLong = reason("123456789")
        assertEquals(InvalidSerialNumberException.Reason.TOO_LONG, tooLong.reason)
        assertEquals(9, tooLong.digits)
        for (text in listOf("12G4", "-1", "０x12", "１２")) {
            assertEquals(InvalidSerialNumberException.Reason.INVALID_CHARACTER, reason(text).reason, text)
        }
        assertNull(SerialNumber.parseOrNull("xyz"))
        assertFalse(reason("12G4").message.orEmpty().contains("12G4"))
    }

    @Test
    fun hexStringForStorage() {
        assertEquals("12345678", SerialNumber(0x12345678u).hexString)
        assertEquals("000000AB", SerialNumber(0xABu).hexString)
        assertEquals(0xABu, SerialNumber.parseOrNull(SerialNumber(0xABu).hexString)?.value)
    }

    @Test
    fun textualFormsAreMasked() {
        val serial = SerialNumber(0x12345678u)
        val boxed: Any = serial
        val texts = listOf(
            serial.toString(), "$serial", boxed.toString(), listOf(serial).toString(),
            Pair(serial, 1).toString(), mapOf("serial" to serial).toString(),
        )
        for (text in texts) {
            assertTrue(text.contains(SerialNumber.MASKED_TEXT), text)
            for (leak in listOf("12345678", "1234", "5678", "78", "305419896")) {
                assertFalse(text.contains(leak), "masked text leaks digits: $text")
            }
        }
    }
}
