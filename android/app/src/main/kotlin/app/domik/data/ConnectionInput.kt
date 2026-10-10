package app.domik.data

import app.domik.deimic.protocol.InvalidSerialNumberException
import app.domik.deimic.protocol.ProtocolConstants
import app.domik.deimic.protocol.SerialNumber

/** Powód odrzucenia pola formularza połączenia; tekst komunikatu wybiera warstwa UI. */
enum class FieldError {
    HOST_EMPTY,
    HOST_INVALID,
    PORT_INVALID,
    SERIAL_EMPTY,
    SERIAL_INVALID_CHARACTER,
    SERIAL_TOO_LONG,
}

/** Poprawnie sprawdzone dane połączenia ze sterownikiem. [serial] nigdy nie trafia do logów (`toString` jest zamaskowany). */
data class ConnectionInput(val host: String, val port: Int, val serial: SerialNumber)

/** Wynik sprawdzenia formularza: albo dane, albo błędy pól (każde pole osobno). */
sealed interface ConnectionInputResult {
    data class Valid(val input: ConnectionInput) : ConnectionInputResult

    data class Invalid(val host: FieldError?, val port: FieldError?, val serial: FieldError?) : ConnectionInputResult
}

/**
 * Sprawdzanie pól „Adres”, „Port” i „Numer seryjny” z ekranu ustawień połączenia. Czysty Kotlin (bez Androida).
 * Sprawdza tylko postać wpisu – nie łączy się z siecią.
 */
object ConnectionInputValidator {
    private const val MAX_HOST_LENGTH = 253
    private const val MAX_LABEL_LENGTH = 63

    private val IPV4_LIKE = Regex("""\d+(\.\d+){3}""")
    private val HOST_LABEL = Regex("""[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?""")
    private val IPV6_CHARS = Regex("""[0-9A-Fa-f:.]+""")

    /** [portText] puste = port domyślny sterownika. */
    fun validate(hostText: String, portText: String, serialText: String): ConnectionInputResult {
        val host = hostError(hostText)
        val port = parsePort(portText)
        val serial = parseSerial(serialText)
        return if (host == null && port.value != null && serial.value != null) {
            ConnectionInputResult.Valid(ConnectionInput(normalizeHost(hostText), port.value, serial.value))
        } else {
            ConnectionInputResult.Invalid(host, port.error, serial.error)
        }
    }

    /** Adres bez otaczających spacji i bez nawiasów kwadratowych wokół IPv6. */
    fun normalizeHost(hostText: String): String = hostText.trim().removePrefix("[").removeSuffix("]")

    /** Błąd pola adresu albo null. Akceptuje IPv4, nazwę DNS i IPv6; odrzuca schemat (`http://`), ścieżkę i spacje. */
    fun hostError(hostText: String): FieldError? {
        val host = hostText.trim()
        if (host.isEmpty()) return FieldError.HOST_EMPTY
        if (host.any { it.isWhitespace() } || '/' in host || '@' in host || '#' in host || '?' in host) {
            return FieldError.HOST_INVALID
        }
        val bracketed = host.startsWith("[") && host.endsWith("]")
        val inner = if (bracketed) host.substring(1, host.length - 1) else host
        return when {
            ':' in inner -> if (isIpv6(inner)) null else FieldError.HOST_INVALID
            bracketed -> FieldError.HOST_INVALID
            IPV4_LIKE.matches(inner) -> if (isIpv4(inner)) null else FieldError.HOST_INVALID
            else -> if (isHostName(inner)) null else FieldError.HOST_INVALID
        }
    }

    private class Parsed<T>(val value: T?, val error: FieldError?)

    private fun parsePort(text: String): Parsed<Int> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Parsed(ProtocolConstants.DEFAULT_PORT, null)
        val value = trimmed.takeIf { it.length <= 5 && it.all { char -> char in '0'..'9' } }?.toIntOrNull()
        return if (value != null && value in 1..65535) Parsed(value, null) else Parsed(null, FieldError.PORT_INVALID)
    }

    private fun parseSerial(text: String): Parsed<SerialNumber> =
        try {
            Parsed(SerialNumber.parse(text), null)
        } catch (error: InvalidSerialNumberException) {
            Parsed(
                null,
                when (error.reason) {
                    InvalidSerialNumberException.Reason.EMPTY -> FieldError.SERIAL_EMPTY
                    InvalidSerialNumberException.Reason.INVALID_CHARACTER -> FieldError.SERIAL_INVALID_CHARACTER
                    InvalidSerialNumberException.Reason.TOO_LONG -> FieldError.SERIAL_TOO_LONG
                },
            )
        }

    private fun isIpv4(text: String): Boolean =
        text.split('.').all { part -> part.length <= 3 && (part.toIntOrNull() ?: 256) in 0..255 }

    private fun isIpv6(text: String): Boolean {
        if (!IPV6_CHARS.matches(text) || text.count { it == ':' } !in 2..7 || ":::" in text) return false
        return text.split("::").size <= 2
    }

    private fun isHostName(text: String): Boolean {
        if (text.length > MAX_HOST_LENGTH) return false
        val labels = text.removeSuffix(".").split('.')
        return labels.all { it.length <= MAX_LABEL_LENGTH && HOST_LABEL.matches(it) }
    }
}
