package app.domik.deimic.config.sqlite

import app.domik.deimic.config.sqlite.SqliteBuilder.SchemaRow
import app.domik.deimic.config.sqlite.SqliteBuilder.database
import app.domik.deimic.config.sqlite.SqliteBuilder.interiorCell
import app.domik.deimic.config.sqlite.SqliteBuilder.leafPage
import app.domik.deimic.config.sqlite.SqliteBuilder.page
import app.domik.deimic.config.sqlite.SqliteBuilder.record
import app.domik.deimic.config.sqlite.SqliteBuilder.singleTable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Behaviour of the reader on small files built byte by byte. */
class SqliteHandmadeTest {
    @Test
    fun readsRowsOfASimpleTable() {
        val bytes = singleTable(
            "CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT, score REAL, data BLOB)",
            listOf(record(null, "alfa", 1.5, byteArrayOf(1, 2)), record(null, "beta", null, null)),
        )
        val database = SqliteDatabase.open(bytes)
        assertEquals(listOf("t"), database.tableNames())
        assertEquals(listOf("id", "name", "score", "data"), database.columns("t"))
        val rows = database.rows("t").toList()
        assertEquals(2, rows.size)
        assertEquals(1L, rows[0].long("id"), "the rowid alias column holds the rowid")
        assertEquals("alfa", rows[0].string("name"))
        assertEquals(1.5, rows[0].double("score"))
        assertEquals(listOf<Byte>(1, 2), rows[0].blob("data")!!.toList())
        assertEquals(2L, rows[1].rowId)
        assertNull(rows[1].double("score"))
        assertTrue(rows[1].isNull("data"))
    }

    @Test
    fun rowidAliasKeepsAStoredValueOfNull() {
        // INTEGER PRIMARY KEY columns are stored as NULL; the reader substitutes the rowid.
        val bytes = singleTable("CREATE TABLE t (id INTEGER PRIMARY KEY, v)", listOf(record(null, "x")))
        assertEquals(SqliteValue.Integer(1), SqliteDatabase.open(bytes).rows("t").single()["id"])
    }

    @Test
    fun missingTrailingColumnsAreNullAndExtraValuesAreIgnored() {
        val bytes = singleTable(
            "CREATE TABLE t (a, b, c)",
            listOf(record(1), record(1, 2, 3), record(1, 2, 3, 4, 5)),
        )
        val rows = SqliteDatabase.open(bytes).rows("t").toList()
        assertEquals(listOf<SqliteValue>(SqliteValue.Integer(1), SqliteValue.Null, SqliteValue.Null), rows[0].values)
        assertEquals(listOf<SqliteValue>(SqliteValue.Integer(1), SqliteValue.Integer(2), SqliteValue.Integer(3)), rows[1].values)
        assertEquals(3, rows[2].values.size)
    }

    @Test
    fun realAffinityColumnsReturnIntegersAsFloatingPoint() {
        val bytes = singleTable("CREATE TABLE t (a REAL, b INTEGER, c)", listOf(record(3, 3, 3)))
        val row = SqliteDatabase.open(bytes).rows("t").single()
        assertEquals(SqliteValue.Real(3.0), row["a"])
        assertEquals(SqliteValue.Integer(3), row["b"])
        assertEquals(SqliteValue.Integer(3), row["c"])
    }

    @Test
    fun typedGettersAreStrict() {
        val bytes = singleTable("CREATE TABLE t (n, f, s, b, z)", listOf(record(5, 2.0, "x", byteArrayOf(1), null)))
        val row = SqliteDatabase.open(bytes).rows("t").single()
        assertEquals(5L, row.long("n"))
        assertEquals(5, row.int("n"))
        assertEquals(2L, row.long("f"), "a whole-number REAL is accepted as integer")
        assertEquals(5.0, row.double("n"))
        assertNull(row.long("z"))
        assertThrows<SqliteQueryException> { row.long("s") }
        assertThrows<SqliteQueryException> { row.string("n") }
        assertThrows<SqliteQueryException> { row.blob("s") }
        assertThrows<SqliteQueryException> { row.double("b") }
        assertThrows<SqliteQueryException> { row["missing"] }
        assertEquals(5L, row["N"].let { (it as SqliteValue.Integer).value }, "column names are case-insensitive")
    }

    @Test
    fun intGetterRejectsValuesOutsideTheIntRange() {
        val bytes = singleTable("CREATE TABLE t (n, f)", listOf(record(1L shl 40, 2.5)))
        val row = SqliteDatabase.open(bytes).rows("t").single()
        assertEquals(1L shl 40, row.long("n"))
        assertThrows<SqliteQueryException> { row.int("n") }
        assertThrows<SqliteQueryException> { row.long("f") }
    }

    @Test
    fun readsATwoLevelTreeInRowidOrder() {
        val size = 512
        val leafA = leafPage(size, listOf(1L to record("a1"), 2L to record("a2")))
        val leafB = leafPage(size, listOf(3L to record("b1")))
        val leafC = leafPage(size, listOf(10L to record("c1"), 11L to record("c2")))
        val root = page(size, SqliteBuilder.INTERIOR, listOf(interiorCell(3, 2), interiorCell(4, 3)), rightMost = 5)
        val bytes = database(size, listOf(SchemaRow("t", 2, "CREATE TABLE t (v)")), listOf(root, leafA, leafB, leafC))
        val rows = SqliteDatabase.open(bytes).rows("t").toList()
        assertEquals(listOf(1L, 2L, 3L, 10L, 11L), rows.map { it.rowId })
        assertEquals(listOf("a1", "a2", "b1", "c1", "c2"), rows.map { it.string("v") })
    }

    @Test
    fun emptyTableHasNoRows() {
        val bytes = singleTable("CREATE TABLE t (v)", emptyList())
        assertEquals(0, SqliteDatabase.open(bytes).rows("t").count())
    }

    @Test
    fun indexesViewsAndTriggersAreIgnored() {
        val size = 512
        val bytes = database(
            size,
            listOf(
                SchemaRow("t", 2, "CREATE TABLE t (v)"),
                SchemaRow("t_index", 3, "CREATE INDEX t_index ON t (v)", type = "index"),
                SchemaRow("t_view", 0, "CREATE VIEW t_view AS SELECT v FROM t", type = "view"),
            ),
            listOf(leafPage(size, listOf(1L to record("x"))), ByteArray(size)),
        )
        val database = SqliteDatabase.open(bytes)
        assertEquals(listOf("t"), database.tableNames())
        assertFalse(database.hasTable("t_index"))
        assertEquals(1, database.rows("t").count())
    }

    @Test
    fun largePagesAndReservedBytes() {
        val size = 65536
        val bytes = singleTable("CREATE TABLE t (v)", listOf(record("x")), pageSize = size)
        assertEquals(65536, SqliteDatabase.open(bytes).pageSize)
        val reserved = database(
            1024, listOf(SchemaRow("t", 2, "CREATE TABLE t (v)")), listOf(leafPage(1024, listOf(1L to record("y")), reserved = 12)), reservedBytes = 12,
        )
        assertEquals("y", SqliteDatabase.open(reserved).rows("t").single().string("v"))
    }

    @Test
    fun bytesAfterTheLastPageAreIgnored() {
        // The header's page count wins over the file length (a real controller file has such a tail).
        val bytes = singleTable("CREATE TABLE t (v)", listOf(record("x")))
        val padded = bytes + ByteArray(700) { 0x7F }
        assertEquals("x", SqliteDatabase.open(padded).rows("t").single().string("v"))
        assertEquals(2, SqliteDatabase.open(padded).pageCount)
    }

    @Test
    fun cellsWithOverflowPages() {
        val size = 512
        // Payload of 1500 bytes in a 512-byte page: X = 477, M = (512-12)*32/255-23 = 39, K = 39 + (1500-39) % 508 = 484 > X -> M.
        val text = "z".repeat(1500)
        val payload = record(text)
        val total = payload.size
        val usable = size
        val maxLocal = usable - 35
        val minLocal = (usable - 12) * 32 / 255 - 23
        val spill = minLocal + (total - minLocal) % (usable - 4)
        val local = if (spill <= maxLocal) spill else minLocal
        val overflowPages = ArrayList<ByteArray>()
        var offset = local
        val firstOverflow = 3
        while (offset < total) {
            val chunk = minOf(total - offset, usable - 4)
            val next = if (offset + chunk < total) firstOverflow + overflowPages.size + 1 else 0
            val overflow = ByteArray(size)
            SqliteBuilder.put32(overflow, 0, next.toLong())
            System.arraycopy(payload, offset, overflow, 4, chunk)
            overflowPages += overflow
            offset += chunk
        }
        val cell = SqliteBuilder.varint(total.toLong()) + SqliteBuilder.varint(1) + payload.copyOf(local) +
            byteArrayOf(0, 0, 0, firstOverflow.toByte())
        val table = page(size, SqliteBuilder.LEAF, listOf(cell))
        val bytes = database(size, listOf(SchemaRow("t", 2, "CREATE TABLE t (v)")), listOf(table) + overflowPages)
        assertEquals(text, SqliteDatabase.open(bytes).rows("t").single().string("v"))
        assertTrue(overflowPages.size >= 2, "the test must use a chain of overflow pages (${overflowPages.size})")
    }

    @Test
    fun withoutRowidTablesAreRejectedWhenUsed() {
        val bytes = singleTable("CREATE TABLE t (a INTEGER PRIMARY KEY, b) WITHOUT ROWID", listOf(record(1, 2)))
        val database = SqliteDatabase.open(bytes)
        assertEquals(listOf("t"), database.tableNames())
        assertThrows<SqliteFormatException> { database.rows("t") }
    }

    @Test
    fun alterTableAddColumnRowsGetTheColumnDefault() {
        // Table declared with 3 columns, rows written when it had 1: the missing values are the defaults.
        val bytes = singleTable("CREATE TABLE t (a, b TEXT DEFAULT 'x', c INTEGER, d INTEGER DEFAULT 7)", listOf(record(7)))
        val row = SqliteDatabase.open(bytes).rows("t").single()
        assertEquals(
            listOf<SqliteValue>(SqliteValue.Integer(7), SqliteValue.Text("x"), SqliteValue.Null, SqliteValue.Integer(7)),
            row.values,
        )
    }

    @Test
    fun explicitNullIsNotReplacedByTheDefault() {
        val bytes = singleTable("CREATE TABLE t (a, b INTEGER DEFAULT 5)", listOf(record(1, null)))
        assertEquals(SqliteValue.Null, SqliteDatabase.open(bytes).rows("t").single()["b"])
    }

    @Test
    fun defaultsAreConvertedToTheColumnAffinity() {
        val bytes = singleTable(
            "CREATE TABLE t (a, i INTEGER DEFAULT '9', r REAL DEFAULT 3, w INTEGER DEFAULT 2.0, h INTEGER DEFAULT 0x10, " +
                "n INTEGER DEFAULT -4, b DEFAULT TRUE, x BLOB DEFAULT x'ff00', s TEXT DEFAULT 12, e REAL DEFAULT 1e+2)",
            listOf(record(1)),
        )
        val row = SqliteDatabase.open(bytes).rows("t").single()
        assertEquals(SqliteValue.Integer(9), row["i"])
        assertEquals(SqliteValue.Real(3.0), row["r"])
        assertEquals(SqliteValue.Integer(2), row["w"])
        assertEquals(SqliteValue.Integer(16), row["h"])
        assertEquals(SqliteValue.Integer(-4), row["n"])
        assertEquals(SqliteValue.Integer(1), row["b"])
        assertEquals(SqliteValue.Blob(byteArrayOf(-1, 0)), row["x"])
        assertEquals(SqliteValue.Text("12"), row["s"])
        assertEquals(SqliteValue.Real(100.0), row["e"])
    }

    @Test
    fun defaultsThatAreNotLiteralsAreNull() {
        val bytes = singleTable("CREATE TABLE t (a, b TEXT DEFAULT CURRENT_TIMESTAMP, c INTEGER DEFAULT (1 + 2))", listOf(record(1)))
        val row = SqliteDatabase.open(bytes).rows("t").single()
        assertEquals(SqliteValue.Null, row["b"])
        assertEquals(SqliteValue.Null, row["c"])
    }

    @Test
    fun generatedColumnsAreRejected() {
        val bytes = singleTable("CREATE TABLE t (a INTEGER, b INTEGER GENERATED ALWAYS AS (a + 1) VIRTUAL, c)", listOf(record(1, 2)))
        val database = SqliteDatabase.open(bytes)
        assertThrows<SqliteFormatException> { database.rows("t") }
        val shortForm = singleTable("CREATE TABLE t (a INTEGER, b AS (a + 1), c)", listOf(record(1, 2)))
        assertThrows<SqliteFormatException> { SqliteDatabase.open(shortForm).columns("t") }
    }
}
