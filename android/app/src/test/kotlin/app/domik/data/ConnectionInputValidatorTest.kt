package app.domik.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConnectionInputValidatorTest {
    private fun valid(host: String, port: String, serial: String): ConnectionInput =
        (ConnectionInputValidator.validate(host, port, serial) as ConnectionInputResult.Valid).input

    private fun invalid(host: String, port: String, serial: String): ConnectionInputResult.Invalid =
        ConnectionInputValidator.validate(host, port, serial) as ConnectionInputResult.Invalid

    @Test
    fun `poprawne dane z domyslnym portem`() {
        val input = valid(" 192.0.2.10 ", "", "0xAB12cd34")
        assertEquals("192.0.2.10", input.host)
        assertEquals(60110, input.port)
        assertEquals("AB12CD34", input.serial.hexString)
    }

    @Test
    fun `numer seryjny ze spacjami i bez prefiksu`() {
        assertEquals("0000BEEF", valid("sterownik.local", "60110", " be ef ").serial.hexString)
    }

    @Test
    fun `wlasny port`() {
        assertEquals(8443, valid("192.0.2.10", " 8443 ", "1").port)
    }

    @Test
    fun `nazwa DNS i IPv6 sa akceptowane`() {
        assertNull(ConnectionInputValidator.hostError("deimic.example"))
        assertNull(ConnectionInputValidator.hostError("moj-dom"))
        assertNull(ConnectionInputValidator.hostError("fe80::1"))
        assertNull(ConnectionInputValidator.hostError("[2001:db8::10]"))
        assertEquals("2001:db8::10", ConnectionInputValidator.normalizeHost("[2001:db8::10]"))
    }

    @Test
    fun `pusty adres`() {
        assertEquals(FieldError.HOST_EMPTY, ConnectionInputValidator.hostError("   "))
        assertEquals(FieldError.HOST_EMPTY, invalid("", "", "1").host)
    }

    @Test
    fun `adresy odrzucone`() {
        listOf(
            "https://192.0.2.10", "192.0.2.10/", "192.0.2.10 1", "999.1.1.1", "-host", "host-", "ho st",
            "user@host", "host:1:2:3:zz", "[192.0.2.10]", "a..b", ":::", "1:2:3::4::5",
        ).forEach { host ->
            assertEquals(FieldError.HOST_INVALID, ConnectionInputValidator.hostError(host), host)
        }
    }

    @Test
    fun `zbyt dluga nazwa`() {
        val label = "a".repeat(64)
        assertEquals(FieldError.HOST_INVALID, ConnectionInputValidator.hostError("$label.example"))
        assertEquals(FieldError.HOST_INVALID, ConnectionInputValidator.hostError(("a".repeat(60) + ".").repeat(5)))
    }

    @Test
    fun `porty poza zakresem lub nie-liczby`() {
        listOf("0", "65536", "-1", "abc", "60 110", "1e3", "123456", "٣").forEach { port ->
            assertEquals(FieldError.PORT_INVALID, invalid("192.0.2.10", port, "1").port, port)
        }
        assertEquals(65535, valid("192.0.2.10", "65535", "1").port)
        assertEquals(1, valid("192.0.2.10", "1", "1").port)
    }

    @Test
    fun `bledy numeru seryjnego`() {
        assertEquals(FieldError.SERIAL_EMPTY, invalid("192.0.2.10", "", "  ").serial)
        assertEquals(FieldError.SERIAL_INVALID_CHARACTER, invalid("192.0.2.10", "", "12GZ").serial)
        assertEquals(FieldError.SERIAL_TOO_LONG, invalid("192.0.2.10", "", "123456789").serial)
    }

    @Test
    fun `bledy kilku pol naraz sa zwracane razem`() {
        val result = invalid("", "99999", "xyz")
        assertEquals(FieldError.HOST_EMPTY, result.host)
        assertEquals(FieldError.PORT_INVALID, result.port)
        assertEquals(FieldError.SERIAL_INVALID_CHARACTER, result.serial)
    }

    @Test
    fun `komunikaty o bledach nie zawieraja numeru seryjnego`() {
        val result = invalid("192.0.2.10", "", "AB12CD3G")
        assertTrue("AB12CD3G" !in result.toString())
    }
}
