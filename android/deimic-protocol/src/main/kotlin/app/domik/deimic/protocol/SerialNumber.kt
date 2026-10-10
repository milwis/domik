package app.domik.deimic.protocol

/**
 * Controller serial number – the only secret of the protocol (written into every frame, bytes 8..11).
 *
 * [toString] is always fully masked. Use [hexString] explicitly when the value must be stored
 * (encrypted storage) – never log it.
 */
@JvmInline
value class SerialNumber(val value: UInt) {
    /** Canonical storage form: 8 uppercase hex digits, zero-padded. Not for logging or UI. */
    val hexString: String get() = value.toString(16).uppercase().padStart(8, '0')

    override fun toString(): String = MASKED_TEXT

    companion object {
        const val MASKED_TEXT: String = "••••••••"

        /**
         * Parses user input: removes all whitespace, accepts an optional `0x`/`0X` prefix,
         * case-insensitive, 1–8 hex digits.
         */
        fun parse(text: String): SerialNumber {
            var digits = text.filterNot { it.isWhitespace() }
            if (digits.startsWith("0x") || digits.startsWith("0X")) digits = digits.substring(2)
            if (digits.isEmpty()) throw InvalidSerialNumberException(InvalidSerialNumberException.Reason.EMPTY)
            if (!digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                throw InvalidSerialNumberException(InvalidSerialNumberException.Reason.INVALID_CHARACTER)
            }
            if (digits.length > 8) {
                throw InvalidSerialNumberException(InvalidSerialNumberException.Reason.TOO_LONG, digits.length)
            }
            return SerialNumber(digits.toUInt(16))
        }

        /** Like [parse], but returns null for invalid input. */
        fun parseOrNull(text: String): SerialNumber? =
            try {
                parse(text)
            } catch (_: InvalidSerialNumberException) {
                null
            }
    }
}

/** Invalid serial number input. The message never contains the input text. */
class InvalidSerialNumberException(val reason: Reason, val digits: Int? = null) :
    IllegalArgumentException("invalid serial number: $reason" + (digits?.let { " ($it digits)" } ?: "")) {
    enum class Reason { EMPTY, INVALID_CHARACTER, TOO_LONG }
}
