package app.domik.deimic.config.sqlite

/**
 * Column affinity as SQLite derives it from the declared type (rules of file format documentation §3.1). The reader
 * needs it for two things: columns with REAL affinity store whole numbers as integers in the file but return them
 * as floating point numbers, and the default value of a column is converted to the column's affinity.
 */
internal enum class Affinity {
    INTEGER,
    TEXT,
    BLOB,
    REAL,
    NUMERIC,
    ;

    /**
     * Converts [value] the way SQLite does when storing it in a column with this affinity: text that looks like a
     * number becomes a number (INTEGER, NUMERIC, REAL), integers and whole REAL values become integers (INTEGER,
     * NUMERIC) or floats (REAL), integers become text (TEXT). BLOB affinity leaves everything alone. A REAL value
     * stored in a TEXT column is left as it is (SQLite's text formatting of floating point numbers is not
     * reproduced).
     */
    fun apply(value: SqliteValue): SqliteValue = when (this) {
        BLOB -> value
        TEXT -> if (value is SqliteValue.Integer) SqliteValue.Text(value.value.toString()) else value
        INTEGER, NUMERIC -> wholeNumbersAsIntegers(numeric(value))
        REAL -> when (val number = numeric(value)) {
            is SqliteValue.Integer -> SqliteValue.Real(number.value.toDouble())
            else -> number
        }
    }

    private fun numeric(value: SqliteValue): SqliteValue = if (value is SqliteValue.Text) parseNumber(value.value) ?: value else value

    private fun wholeNumbersAsIntegers(value: SqliteValue): SqliteValue {
        if (value !is SqliteValue.Real) return value
        val real = value.value
        val integer = real.toLong()
        return if (real == integer.toDouble() && integer > Long.MIN_VALUE && integer < Long.MAX_VALUE) SqliteValue.Integer(integer) else value
    }

    companion object {
        /** SQLite's rules, in order: INT -> INTEGER; CHAR, CLOB, TEXT -> TEXT; BLOB or no type -> BLOB; REAL, FLOA, DOUB -> REAL; else NUMERIC. */
        fun of(declaredType: String): Affinity {
            val type = declaredType.uppercase()
            return when {
                "INT" in type -> INTEGER
                "CHAR" in type || "CLOB" in type || "TEXT" in type -> TEXT
                type.isEmpty() || "BLOB" in type -> BLOB
                "REAL" in type || "FLOA" in type || "DOUB" in type -> REAL
                else -> NUMERIC
            }
        }

        private val INTEGER_TEXT = Regex("[+-]?[0-9]+")
        private val REAL_TEXT = Regex("[+-]?([0-9]+\\.?[0-9]*|\\.[0-9]+)([eE][+-]?[0-9]+)?")

        /** A text that is a well-formed decimal integer or real literal (surrounding blanks allowed), else null. */
        fun parseNumber(text: String): SqliteValue? {
            val trimmed = text.trim(' ', '\t', '\n', '\u000C', '\r')
            if (INTEGER_TEXT.matches(trimmed)) {
                trimmed.toLongOrNull()?.let { return SqliteValue.Integer(it) }
                return SqliteValue.Real(trimmed.toDouble()) // too large for 64 bits
            }
            if (REAL_TEXT.matches(trimmed)) return SqliteValue.Real(trimmed.toDouble())
            return null
        }
    }
}
