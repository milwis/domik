package app.domik.deimic.config.sqlite

/** Column names of a table, shared by all its rows; names are looked up case-insensitively like in SQLite. */
internal class RowLayout(val table: String, val columns: List<String>) {
    private val indexByName: Map<String, Int> = HashMap<String, Int>().also { map ->
        columns.forEachIndexed { index, name -> map.putIfAbsent(name.lowercase(), index) }
    }

    fun indexOf(column: String): Int =
        indexByName[column.lowercase()] ?: throw SqliteQueryException("table '$table' has no column '$column'")
}

/**
 * One table row. Raw values are available through [get] / [values]; the typed getters are strict: NULL gives
 * `null`, a value of another storage class throws [SqliteQueryException] (no silent conversions, except that a
 * whole-number REAL is accepted as an integer and an integer as a floating point number).
 */
class Row internal constructor(
    private val layout: RowLayout,
    /** SQLite rowid (for an `INTEGER PRIMARY KEY` column it equals the column value). */
    val rowId: Long,
    private val stored: Array<SqliteValue>,
) {
    val table: String get() = layout.table

    val columns: List<String> get() = layout.columns

    /** Values in column order. */
    val values: List<SqliteValue> get() = stored.asList()

    /** Raw value of [column]. @throws SqliteQueryException no such column. */
    operator fun get(column: String): SqliteValue = stored[layout.indexOf(column)]

    fun isNull(column: String): Boolean = get(column) == SqliteValue.Null

    fun long(column: String): Long? = when (val value = get(column)) {
        SqliteValue.Null -> null
        is SqliteValue.Integer -> value.value
        is SqliteValue.Real ->
            if (value.value == Math.rint(value.value) && value.value >= -9.223372036854776E18 && value.value < 9.223372036854776E18) {
                value.value.toLong()
            } else {
                mismatch(column, value, "an integer")
            }
        else -> mismatch(column, value, "an integer")
    }

    /** Like [long], additionally requires the value to fit an [Int]. */
    fun int(column: String): Int? {
        val value = long(column) ?: return null
        if (value < Int.MIN_VALUE || value > Int.MAX_VALUE) {
            throw SqliteQueryException("column '$column' of table '$table' holds $value, which does not fit an Int")
        }
        return value.toInt()
    }

    fun double(column: String): Double? = when (val value = get(column)) {
        SqliteValue.Null -> null
        is SqliteValue.Real -> value.value
        is SqliteValue.Integer -> value.value.toDouble()
        else -> mismatch(column, value, "a number")
    }

    fun string(column: String): String? = when (val value = get(column)) {
        SqliteValue.Null -> null
        is SqliteValue.Text -> value.value
        else -> mismatch(column, value, "text")
    }

    /** The bytes of a BLOB value (not copied). */
    fun blob(column: String): ByteArray? = when (val value = get(column)) {
        SqliteValue.Null -> null
        is SqliteValue.Blob -> value.bytes
        else -> mismatch(column, value, "a blob")
    }

    override fun toString(): String = "Row($table #$rowId)"

    private fun mismatch(column: String, value: SqliteValue, expected: String): Nothing {
        val actual = when (value) {
            SqliteValue.Null -> "NULL"
            is SqliteValue.Integer -> "an integer"
            is SqliteValue.Real -> "a floating point number"
            is SqliteValue.Text -> "text"
            is SqliteValue.Blob -> "a blob"
        }
        throw SqliteQueryException("column '$column' of table '$table' holds $actual, expected $expected")
    }
}
