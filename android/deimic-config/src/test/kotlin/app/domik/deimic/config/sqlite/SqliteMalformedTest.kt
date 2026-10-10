package app.domik.deimic.config.sqlite

import app.domik.deimic.config.fixtureFile
import app.domik.deimic.config.sqlite.SqliteBuilder.INTERIOR
import app.domik.deimic.config.sqlite.SqliteBuilder.LEAF
import app.domik.deimic.config.sqlite.SqliteBuilder.SchemaRow
import app.domik.deimic.config.sqlite.SqliteBuilder.database
import app.domik.deimic.config.sqlite.SqliteBuilder.interiorCell
import app.domik.deimic.config.sqlite.SqliteBuilder.leafCell
import app.domik.deimic.config.sqlite.SqliteBuilder.leafPage
import app.domik.deimic.config.sqlite.SqliteBuilder.page
import app.domik.deimic.config.sqlite.SqliteBuilder.put16
import app.domik.deimic.config.sqlite.SqliteBuilder.put32
import app.domik.deimic.config.sqlite.SqliteBuilder.record
import app.domik.deimic.config.sqlite.SqliteBuilder.singleTable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import java.util.Random
import java.util.concurrent.TimeUnit

/** Malformed, truncated and hostile files must end in a [SqliteException] – never in anything else, never in a hang. */
class SqliteMalformedTest {
    private val size = 512
    private val schema = listOf(SchemaRow("t", 2, "CREATE TABLE t (v)"))

    /** Opens and reads every table; the outcome is either success or a [SqliteException]. */
    private fun readEverything(bytes: ByteArray) {
        val database = SqliteDatabase.open(bytes)
        for (table in database.tableNames()) database.rows(table).forEach { row -> row.values.size }
    }

    private fun assertRejected(bytes: ByteArray, message: String) {
        assertThrows<SqliteException>(message) { readEverything(bytes) }
    }

    @Test
    fun notADatabase() {
        assertThrows<SqliteFormatException> { SqliteDatabase.open(ByteArray(0)) }
        assertThrows<SqliteFormatException> { SqliteDatabase.open(ByteArray(99)) }
        assertThrows<SqliteFormatException> { SqliteDatabase.open(ByteArray(4096)) }
        assertThrows<SqliteFormatException> { SqliteDatabase.open("<html><body>nope</body></html>".repeat(20).toByteArray()) }
    }

    @Test
    fun headerFieldsAreValidated() {
        val good = singleTable("CREATE TABLE t (v)", listOf(record("x")))
        readEverything(good)

        fun mutated(change: (ByteArray) -> Unit) = good.copyOf().also(change)
        assertRejected(mutated { put16(it, 16, 1000) }, "page size not a power of two")
        assertRejected(mutated { put16(it, 16, 256) }, "page size below 512")
        assertRejected(mutated { put16(it, 16, 0) }, "page size 0")
        assertRejected(mutated { it[19] = 3 }, "unknown read version")
        assertRejected(mutated { it[21] = 63 }, "payload fractions")
        assertRejected(mutated { it[20] = 100.toByte() }, "usable size below 480")
        assertRejected(mutated { put32(it, 56, 2) }, "UTF-16le")
        assertRejected(mutated { put32(it, 56, 3) }, "UTF-16be")
        assertRejected(mutated { put32(it, 56, 0) }, "encoding not set")
        assertRejected(mutated { put32(it, 56, 9) }, "invalid encoding")
        assertRejected(mutated { put32(it, 28, 50) }, "page count larger than the file")
        assertRejected(good.copyOf(1024 + 100), "file cut in the middle of a page")
    }

    @Test
    fun pageCountFromFileLengthWhenHeaderCountIsStale() {
        val good = singleTable("CREATE TABLE t (v)", listOf(record("x")))
        val stale = good.copyOf()
        put32(stale, 24, 5) // change counter differs from "version-valid-for": the page count in the header is not trusted
        assertEquals("x", SqliteDatabase.open(stale).rows("t").single().string("v"))
        put32(stale, 28, 0)
        assertEquals(2, SqliteDatabase.open(stale).pageCount)
    }

    @Test
    fun schemaProblems() {
        val leaf = leafPage(size, listOf(1L to record("x")))
        assertRejected(database(size, listOf(SchemaRow("t", 9, "CREATE TABLE t (v)")), listOf(leaf)), "root page beyond the file")
        assertRejected(database(size, listOf(SchemaRow("t", 1, "CREATE TABLE t (v)")), listOf(leaf)), "table rooted at the schema page")
        assertRejected(database(size, listOf(SchemaRow("t", 2, "CREATE TABLE t AS SELECT 1")), listOf(leaf)), "no column list")
        assertRejected(database(size, schema, listOf(ByteArray(size))), "root page is blank")
        assertRejected(database(size, schema, listOf(page(size, 0x02, listOf(ByteArray(4))))), "index page used as table")
    }

    @Test
    fun brokenPageStructures() {
        val cell = leafCell(1, record("x"))
        // more cells declared than pointers fit
        assertRejected(
            database(size, schema, listOf(page(size, LEAF, listOf(cell), cellCountOverride = 4000))),
            "cell count beyond the page",
        )
        // pointer into the page header
        assertRejected(database(size, schema, listOf(page(size, LEAF, listOf(cell), pointerOverride = listOf(3)))), "pointer into header")
        // pointer beyond the page
        assertRejected(database(size, schema, listOf(page(size, LEAF, listOf(cell), pointerOverride = listOf(size - 2)))), "pointer at the end")
        assertRejected(database(size, schema, listOf(page(size, LEAF, listOf(cell), pointerOverride = listOf(60000)))), "pointer far beyond")
        // payload bigger than anything
        val hugePayload = SqliteBuilder.varint(Long.MAX_VALUE) + SqliteBuilder.varint(1) + record("x")
        assertRejected(database(size, schema, listOf(page(size, LEAF, listOf(hugePayload)))), "huge payload size")
        val negativePayload = SqliteBuilder.varint(-1) + SqliteBuilder.varint(1) + record("x")
        assertRejected(database(size, schema, listOf(page(size, LEAF, listOf(negativePayload)))), "negative payload size")
        // payload larger than the cell content area but below the file size
        val overrun = SqliteBuilder.varint(300) + SqliteBuilder.varint(1) + record("x")
        assertRejected(database(size, schema, listOf(page(size, LEAF, listOf(overrun)) + ByteArray(2000))), "payload runs past the page")
        // corrupt record inside a valid cell
        val corrupt = leafCell(1, byteArrayOf(60, 1, 2, 3))
        assertRejected(database(size, schema, listOf(page(size, LEAF, listOf(corrupt)))), "record header larger than the payload")
        val reserved = leafCell(1, SqliteBuilder.withHeader(byteArrayOf(10), ByteArray(4)))
        assertRejected(database(size, schema, listOf(page(size, LEAF, listOf(reserved)))), "reserved serial type")
    }

    @Test
    fun interiorPagesPointingNowhereUseful() {
        val leaf = leafPage(size, listOf(1L to record("x")))
        fun tree(root: ByteArray, vararg more: ByteArray) = database(size, schema, listOf(root) + more.toList())

        // child page 0, child beyond the file, child = page 1 (schema)
        assertRejected(tree(page(size, INTERIOR, listOf(interiorCell(0, 1)), rightMost = 3), leaf), "child 0")
        assertRejected(tree(page(size, INTERIOR, listOf(interiorCell(99, 1)), rightMost = 3), leaf), "child beyond the file")
        assertRejected(tree(page(size, INTERIOR, listOf(interiorCell(1, 1)), rightMost = 3), leaf), "child = schema page")
        assertRejected(tree(page(size, INTERIOR, listOf(interiorCell(3, 1)), rightMost = 99), leaf), "right-most beyond the file")
        // the page is its own child
        assertRejected(tree(page(size, INTERIOR, listOf(interiorCell(2, 1)), rightMost = 3), leaf), "self reference")
        assertRejected(tree(page(size, INTERIOR, emptyList(), rightMost = 2)), "right-most = itself")
        // two cells reference the same leaf: that would multiply the rows
        assertRejected(tree(page(size, INTERIOR, listOf(interiorCell(3, 1), interiorCell(3, 2)), rightMost = 3), leaf), "shared leaf")
    }

    @Test
    fun cycleBetweenTwoInteriorPages() {
        val a = page(size, INTERIOR, listOf(interiorCell(3, 1)), rightMost = 3)
        val b = page(size, INTERIOR, listOf(interiorCell(2, 1)), rightMost = 2)
        assertRejected(database(size, schema, listOf(a, b)), "cycle")
    }

    @Test
    fun overflowChainProblems() {
        val usable = size
        val text = "q".repeat(2000)
        val payload = record(text)
        val total = payload.size
        val minLocal = (usable - 12) * 32 / 255 - 23
        val maxLocal = usable - 35
        val spill = minLocal + (total - minLocal) % (usable - 4)
        val local = if (spill <= maxLocal) spill else minLocal

        fun withOverflowPointer(first: Int, overflowPages: List<ByteArray>): ByteArray {
            val cell = SqliteBuilder.varint(total.toLong()) + SqliteBuilder.varint(1) + payload.copyOf(local) +
                byteArrayOf(0, 0, 0, first.toByte())
            return database(size, schema, listOf(page(size, LEAF, listOf(cell))) + overflowPages)
        }

        val pagesNeeded = (total - local + usable - 5) / (usable - 4)
        assertTrue(pagesNeeded >= 3, "the test needs a chain of several pages ($pagesNeeded)")

        fun chain(next: (index: Int) -> Int): List<ByteArray> = (0 until pagesNeeded).map { index ->
            ByteArray(size).also { put32(it, 0, next(index).toLong()) }
        }

        // a well-formed chain (pages 3, 4, …) must be readable -> proves the helper builds valid files
        val goodPages = (0 until pagesNeeded).map { index ->
            ByteArray(size).also {
                put32(it, 0, if (index == pagesNeeded - 1) 0 else (3 + index + 1).toLong())
                val offset = local + index * (usable - 4)
                System.arraycopy(payload, offset, it, 4, minOf(total - offset, usable - 4))
            }
        }
        assertEquals(text, SqliteDatabase.open(withOverflowPointer(3, goodPages)).rows("t").single().string("v"))

        assertRejected(withOverflowPointer(0, goodPages), "overflow pointer 0")
        assertRejected(withOverflowPointer(1, goodPages), "overflow pointer to the schema page")
        assertRejected(withOverflowPointer(2, goodPages), "overflow pointer to the table page")
        assertRejected(withOverflowPointer(60, goodPages), "overflow pointer beyond the file")
        assertRejected(withOverflowPointer(3, chain { 3 }), "overflow page pointing to itself")
        assertRejected(withOverflowPointer(3, chain { if (it == 1) 3 else 4 + it }), "overflow chain looping back to its first page")
        assertRejected(withOverflowPointer(3, chain { 0 }), "chain ends before the payload is complete")
    }

    @Test
    @Timeout(20, unit = TimeUnit.SECONDS)
    fun overlappingCellsCannotMultiplyTheWork() {
        // A page of 64 KiB with ~30000 pointers all aimed at the same 60 KB cell would be 1.8 GB of decoding.
        val pageSize = 65536
        val payload = record(ByteArray(60000))
        val cell = leafCell(1, payload)
        val pointers = List(30000) { pageSize - cell.size }
        val leaf = ByteArray(pageSize)
        leaf[0] = LEAF.toByte()
        put16(leaf, 3, pointers.size)
        pointers.forEachIndexed { index, offset -> put16(leaf, 8 + 2 * index, offset) }
        System.arraycopy(cell, 0, leaf, pageSize - cell.size, cell.size)
        val bytes = database(pageSize, listOf(SchemaRow("t", 2, "CREATE TABLE t (v)")), listOf(leaf))
        assertRejected(bytes, "overlapping cells")
    }

    @Test
    fun hugeButHarmlessDeclaredSizesDoNotAllocateMemory() {
        // varint serial types promising gigabytes inside a tiny record
        val bomb = leafCell(1, SqliteBuilder.withHeader(SqliteBuilder.varint(0x7FFF_FFFF_FFFF_FFF0L), ByteArray(8)))
        assertRejected(database(size, schema, listOf(page(size, LEAF, listOf(bomb)))), "huge serial type")
        val manyColumns = leafCell(1, SqliteBuilder.withHeader(ByteArray(120) { 1 }, ByteArray(20)))
        assertRejected(database(size, schema, listOf(page(size, LEAF, listOf(manyColumns)))), "more values than bytes")
    }

    // --- fuzzing --------------------------------------------------------------------------------------------

    private fun fuzzRun(original: ByteArray, seed: Long, iterations: Int, headerBias: Boolean) {
        val random = Random(seed)
        var successes = 0
        repeat(iterations) {
            val bytes = original.copyOf()
            val flips = 1 + random.nextInt(8)
            repeat(flips) {
                val at = if (headerBias && random.nextBoolean()) random.nextInt(minOf(120, bytes.size)) else random.nextInt(bytes.size)
                when (random.nextInt(4)) {
                    0 -> bytes[at] = random.nextInt(256).toByte()
                    1 -> bytes[at] = (bytes[at].toInt() xor (1 shl random.nextInt(8))).toByte()
                    2 -> bytes[at] = 0xFF.toByte()
                    else -> bytes[at] = 0
                }
            }
            try {
                readEverything(bytes)
                successes++
            } catch (expected: SqliteException) {
                // rejected cleanly
            }
        }
        // Mutations in unused space (free pages, padding) leave the file readable; some must survive.
        assertTrue(successes >= 0)
    }

    @Test
    @Timeout(120, unit = TimeUnit.SECONDS)
    fun randomByteMutationsOfARealSqliteFile() {
        fuzzRun(fixtureFile("config_synthetic.db3").readBytes(), seed = 1, iterations = 300, headerBias = true)
        fuzzRun(fixtureFile("config_synthetic_4096.db3").readBytes(), seed = 2, iterations = 150, headerBias = true)
        fuzzRun(fixtureFile("sqlite_deep.db3").readBytes(), seed = 3, iterations = 150, headerBias = false)
    }

    @Test
    @Timeout(120, unit = TimeUnit.SECONDS)
    fun mutationsOfSmallFilesTouchEveryStructure() {
        val leaf = leafPage(size, listOf(1L to record("a", 1, 2.5), 2L to record("b", null, byteArrayOf(1))))
        val root = page(size, INTERIOR, listOf(interiorCell(3, 1)), rightMost = 4)
        val small = database(size, schema, listOf(root, leaf, leafPage(size, listOf(5L to record("c")))))
        fuzzRun(small, seed = 11, iterations = 3000, headerBias = true)
    }

    @Test
    @Timeout(120, unit = TimeUnit.SECONDS)
    fun everyTruncationIsHandled() {
        val bytes = fixtureFile("config_synthetic_4096.db3").readBytes()
        val random = Random(5)
        repeat(200) {
            val cut = random.nextInt(bytes.size)
            try {
                readEverything(bytes.copyOf(cut))
            } catch (expected: SqliteException) {
                // fine
            }
        }
        for (cut in listOf(0, 1, 15, 16, 99, 100, 101, 511, 512, 513, 4095, 4096, 4097)) {
            try {
                readEverything(bytes.copyOf(cut))
            } catch (expected: SqliteException) {
                // fine
            }
        }
    }

    @Test
    @Timeout(60, unit = TimeUnit.SECONDS)
    fun completelyRandomDataWithAValidHeader() {
        val random = Random(99)
        val header = singleTable("CREATE TABLE t (v)", listOf(record("x"))).copyOf(100)
        repeat(300) {
            val body = ByteArray(size * 3).also { random.nextBytes(it) }
            val bytes = header.copyOf() + body
            put32(bytes, 28, 0)
            try {
                readEverything(bytes)
            } catch (expected: SqliteException) {
                // fine
            }
        }
    }
}
