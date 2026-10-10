package app.domik.deimic.config.sqlite

import app.domik.deimic.config.asList
import app.domik.deimic.config.describeMismatch
import app.domik.deimic.config.expectedDocument
import app.domik.deimic.config.fixtureFile
import app.domik.deimic.config.int
import app.domik.deimic.config.list
import app.domik.deimic.config.long
import app.domik.deimic.config.obj
import app.domik.deimic.config.objects
import app.domik.deimic.config.sha256Hex
import app.domik.deimic.config.string
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows

/**
 * The reader against files written by real SQLite (`tools/gen_config_db.py`): every table of the synthetic
 * databases – 1024 and 4096 byte pages, overflow chains, a freelist, deleted rows, `ALTER TABLE ADD COLUMN`,
 * rowid aliases – must read exactly as the Python `sqlite3` module reads it.
 */
class SqliteFixtureTest {
    private val files = expectedDocument.obj("files")

    private fun open(name: String): SqliteDatabase {
        val bytes = fixtureFile(name).readBytes()
        assertEquals(files.obj(name).string("sha256"), sha256Hex(bytes), "fixture $name differs from the expected-results file")
        return SqliteDatabase.open(bytes)
    }

    @TestFactory
    fun headerFields(): List<DynamicTest> = files.keys.map { name ->
        DynamicTest.dynamicTest(name) {
            val info = files.obj(name)
            val database = open(name)
            assertEquals(info.int("pageSize"), database.pageSize)
            assertEquals(info.int("pageCount"), database.pageCount)
            assertEquals(info.long("fileSize"), fixtureFile(name).length())
            assertTrue(info.int("freelistCount") > 0, "the fixture must contain free pages")
        }
    }

    @TestFactory
    fun everyTableReadsLikeSqlite(): List<DynamicTest> = files.keys.map { name ->
        DynamicTest.dynamicTest(name) {
            val database = open(name)
            val tables = expectedDocument.obj("tables")
            val expectedNames = expectedDocument.list("tableNames").map { it as String }
            assertEquals(expectedNames, database.tableNames())
            for ((table, definition) in tables) {
                compareTable(database, table, definition as Map<String, Any?>)
            }
        }
    }

    @TestFactory
    fun overflowProbeReadsLikeSqlite(): List<DynamicTest> = files.keys.map { name ->
        DynamicTest.dynamicTest(name) {
            val database = open(name)
            compareTable(database, "overflow_probe", files.obj(name).obj("overflowProbe"))
        }
    }

    private fun compareTable(database: SqliteDatabase, table: String, expected: Map<String, Any?>) {
        if (expected["withoutRowid"] == true) {
            assertThrows<SqliteFormatException>("WITHOUT ROWID table $table") { database.rows(table).toList() }
            return
        }
        assertEquals(expected.list("columns"), database.columns(table), "columns of $table")
        val rows = database.rows(table).toList()
        val expectedRows = expected.list("rows").map { it.asList() }
        assertEquals(expectedRows.size, rows.size, "row count of $table")
        expectedRows.forEachIndexed { index, expectedRow ->
            val row = rows[index]
            assertEquals(expectedRow[0], row.rowId, "rowid of $table row $index")
            for (column in 1 until expectedRow.size) {
                val problem = describeMismatch(expectedRow[column], row.values[column - 1])
                assertTrue(problem == null, "$table rowid ${row.rowId} column ${row.columns[column - 1]}: $problem")
            }
        }
    }

    @Test
    fun sequenceCanBeIteratedTwice() {
        val database = open("config_synthetic.db3")
        val first = database.rows("outputs").map { it.rowId }.toList()
        val second = database.rows("outputs").map { it.rowId }.toList()
        assertEquals(first, second)
        assertTrue(first.isNotEmpty())
    }

    @Test
    fun deepTreeWithInteriorPagesUnderInteriorPages() {
        val deep = expectedDocument.obj("deep")
        val bytes = fixtureFile(deep.string("file")).readBytes()
        assertEquals(deep.string("sha256"), sha256Hex(bytes))
        assertTrue(deep.int("treeDepth") >= 3)
        val database = SqliteDatabase.open(bytes)
        assertEquals(deep.int("pageSize"), database.pageSize)

        var count = 0
        var sum = 0L
        var last = Long.MIN_VALUE
        for (row in database.rows(deep.string("table"))) {
            assertTrue(row.rowId > last, "rowids must ascend")
            last = row.rowId
            assertEquals(row.rowId, row.long("id"))
            sum += row.long("v")!!
            count++
        }
        assertEquals(deep.int("rowCount"), count)
        assertEquals(deep.long("valueSum"), sum)
        assertEquals(deep.long("lastRowId"), last)
    }

    @Test
    fun schemaTableIsReadable() {
        val database = open("config_synthetic.db3")
        val rows = database.rows("sqlite_master").toList()
        assertTrue(rows.any { it.string("type") == "table" && it.string("name") == "outputs" })
        assertTrue(rows.any { it.string("type") == "index" }, "indexes are listed in the schema table")
        assertEquals(listOf("type", "name", "tbl_name", "rootpage", "sql"), database.columns("sqlite_schema"))
    }

    @Test
    fun unknownTableIsAQueryError() {
        val database = open("config_synthetic.db3")
        assertThrows<SqliteQueryException> { database.rows("no_such_table") }
        assertThrows<SqliteQueryException> { database.columns("no_such_table") }
        assertTrue(!database.hasTable("no_such_table"))
        assertTrue(database.hasTable("OUTPUTS"), "table names are case-insensitive")
    }
}
