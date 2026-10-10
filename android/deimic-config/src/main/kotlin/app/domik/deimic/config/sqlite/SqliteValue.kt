package app.domik.deimic.config.sqlite

/** A value stored in a SQLite record (the five storage classes). */
sealed interface SqliteValue {
    data object Null : SqliteValue

    data class Integer(val value: Long) : SqliteValue

    data class Real(val value: Double) : SqliteValue

    /** UTF-8 text (invalid byte sequences are replaced by U+FFFD). */
    data class Text(val value: String) : SqliteValue

    /** Binary data; equality compares the contents. */
    class Blob(val bytes: ByteArray) : SqliteValue {
        override fun equals(other: Any?): Boolean = other is Blob && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()

        override fun toString(): String = "Blob(${bytes.size} B)"
    }
}
