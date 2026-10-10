package app.domik.deimic.config.sqlite

import java.util.BitSet

/**
 * Minimal read-only reader of SQLite database files (file format 3), written in pure Kotlin so that it works the
 * same on the JVM and on Android and needs no library.
 *
 * Supported: rowid tables (including `INTEGER PRIMARY KEY` rowid aliases and columns added with
 * `ALTER TABLE … ADD COLUMN`), UTF-8 text, any page size and reserved bytes per page, overflow pages, all serial
 * types. Indexes, views, triggers and virtual tables are ignored; `WITHOUT ROWID` tables, tables with generated
 * columns and databases with other text encodings are rejected with a [SqliteFormatException]. The write-ahead log
 * is not looked at (only the main file is read). A column's `DEFAULT` is applied only to rows written before the
 * column was added (short records), and only when it is a literal.
 *
 * The reader trusts nothing: every offset, page number, size and varint is bounds-checked, every page is visited
 * at most once per traversal (cycles and shared pages are errors) and sizes are limited by the file size, so a
 * malformed file ends in a [SqliteFormatException] – never in a hang, an out-of-memory error or an
 * `IndexOutOfBoundsException`.
 *
 * The array passed to [open] is **not copied** and must not be modified while the database is in use.
 * Instances are immutable and thread-safe.
 */
class SqliteDatabase private constructor(
    private val data: ByteArray,
    /** Page size in bytes (512 … 65536). */
    val pageSize: Int,
    /** Number of pages (from the header when it is valid, otherwise from the file length). */
    val pageCount: Int,
    private val usableSize: Int,
    private val entries: List<SchemaEntry>,
) {
    private class SchemaEntry(val name: String, val rootPage: Int, val sql: String?)

    private class Cell(val rowId: Long, val buffer: ByteArray, val offset: Int, val length: Int)

    /** Position in a b-tree traversal. [next] is the index of the next cell (interior: [cellCount] = right-most child). */
    private class PageState(
        val isLeaf: Boolean,
        val pageStart: Int,
        val pointerArray: Int,
        val cellCount: Int,
        val rightMost: Long,
    ) {
        var next: Int = 0
    }

    private class TableHandle(val name: String, val rootPage: Int, val definition: TableDefinition) {
        val layout = RowLayout(name, definition.columns.map { it.name })
        val realColumns: IntArray =
            definition.columns.indices.filter { definition.columns[it].isRealAffinity }.toIntArray()
    }

    private val handles = HashMap<String, TableHandle>()
    private val entriesByName: Map<String, SchemaEntry> =
        entries.associateByTo(HashMap()) { it.name.lowercase() }

    /** Names of all tables in the order of the schema (internal tables such as `sqlite_sequence` included). */
    fun tableNames(): List<String> = entries.map { it.name }

    fun hasTable(table: String): Boolean = table.lowercase() in entriesByName || isSchemaTable(table)

    /**
     * Column names of [table] in declaration order, as written in its `CREATE TABLE` statement.
     *
     * @throws SqliteQueryException no such table.
     * @throws SqliteFormatException the table definition cannot be understood or the table is `WITHOUT ROWID`.
     */
    fun columns(table: String): List<String> = handle(table).definition.columns.map { it.name }

    /**
     * Lazily reads all rows of [table] in rowid order. A row has exactly the declared columns: missing trailing
     * values (rows written before an `ALTER TABLE ADD COLUMN`) are the column's `DEFAULT` literal (NULL when it has
     * none), extra stored values are ignored, a rowid
     * alias column holds the rowid, and columns with REAL affinity return integers as floating point numbers.
     *
     * The table is looked up immediately, the file is traversed while the sequence is iterated; a corrupt file
     * therefore throws [SqliteFormatException] from the iteration. The sequence can be iterated repeatedly.
     *
     * @throws SqliteQueryException no such table.
     * @throws SqliteFormatException the table definition cannot be understood or the table is `WITHOUT ROWID`.
     */
    fun rows(table: String): Sequence<Row> {
        val handle = handle(table)
        val width = handle.definition.columns.size
        val alias = handle.definition.rowidAlias
        return cells(handle.rootPage).map { cell ->
            val record = RecordDecoder.decode(cell.buffer, cell.offset, cell.length)
            val values = Array<SqliteValue>(width) { index ->
                if (index < record.size) record[index] else handle.definition.columns[index].defaultValue
            }
            if (alias >= 0 && values[alias] == SqliteValue.Null) values[alias] = SqliteValue.Integer(cell.rowId)
            for (index in handle.realColumns) {
                val value = values[index]
                if (value is SqliteValue.Integer) values[index] = SqliteValue.Real(value.value.toDouble())
            }
            Row(handle.layout, cell.rowId, values)
        }
    }

    override fun toString(): String = "SqliteDatabase(pageSize=$pageSize, pages=$pageCount, tables=${entries.size})"

    // --- tables -------------------------------------------------------------------------------------------------

    private fun isSchemaTable(table: String): Boolean =
        table.equals("sqlite_schema", ignoreCase = true) || table.equals("sqlite_master", ignoreCase = true)

    private fun handle(table: String): TableHandle {
        val key = table.lowercase()
        synchronized(handles) {
            handles[key]?.let { return it }
            val created = when {
                isSchemaTable(table) -> TableHandle(
                    table, 1,
                    TableDefinition(SCHEMA_COLUMNS.map { ColumnDefinition(it, "") }, rowidAlias = -1, withoutRowid = false),
                )
                else -> {
                    val entry = entriesByName[key] ?: throw SqliteQueryException("no such table: $table")
                    val sql = entry.sql ?: throw SqliteFormatException("table '${entry.name}' has no CREATE statement")
                    val definition = TableDefinition.parse(entry.name, sql)
                    if (definition.withoutRowid) {
                        throw SqliteFormatException("table '${entry.name}' is WITHOUT ROWID, which is not supported")
                    }
                    TableHandle(entry.name, entry.rootPage, definition)
                }
            }
            handles[key] = created
            return created
        }
    }

    // --- b-tree traversal ---------------------------------------------------------------------------------------

    /**
     * Leaf cells of the table b-tree with the given root page, in rowid order. Interior pages (0x05) only
     * route; the payload lives in the leaves (0x0D). Each page, including overflow pages, may be used only once, and
     * the payloads of all cells together may not exceed the file size.
     */
    private fun cells(rootPage: Int): Sequence<Cell> = sequence {
        val visited = BitSet(pageCount + 1)
        // In a valid file all cell payloads together fit the file; this bounds the work for overlapping cells.
        val budget = LongArray(1) { data.size.toLong() }
        val stack = ArrayList<PageState>()
        stack += enter(rootPage.toLong(), visited, isRoot = true)
        while (stack.isNotEmpty()) {
            val state = stack[stack.size - 1]
            if (state.isLeaf) {
                if (state.next < state.cellCount) {
                    yield(leafCell(state, state.next++, visited, budget))
                } else {
                    stack.removeAt(stack.size - 1)
                }
            } else if (state.next < state.cellCount) {
                val child = data.u32(state.pageStart + cellOffset(state, state.next++))
                stack += enter(child, visited, isRoot = false)
            } else if (state.next == state.cellCount) {
                state.next++
                stack += enter(state.rightMost, visited, isRoot = false)
            } else {
                stack.removeAt(stack.size - 1)
            }
        }
    }

    private fun enter(page: Long, visited: BitSet, isRoot: Boolean): PageState {
        if (page < 1 || page > pageCount) throw SqliteFormatException("page number $page outside the file ($pageCount pages)")
        if (page == 1L && !isRoot) throw SqliteFormatException("page 1 cannot be a child page of a b-tree")
        val number = page.toInt()
        if (visited.get(number)) throw SqliteFormatException("page $number is referenced more than once (cycle or shared page)")
        visited.set(number)

        val pageStart = (number - 1) * pageSize
        val base = pageStart + if (number == 1) HEADER_SIZE else 0
        val type = data[base].toInt() and 0xFF
        val isLeaf = when (type) {
            LEAF_TABLE -> true
            INTERIOR_TABLE -> false
            else -> throw SqliteFormatException("page $number is not a table b-tree page (type 0x${type.toString(16)})")
        }
        val headerSize = if (isLeaf) 8 else 12
        val cellCount = data.u16(base + 3)
        val pointerArray = base + headerSize
        if (pointerArray + 2 * cellCount > pageStart + usableSize) {
            throw SqliteFormatException("page $number: $cellCount cell pointers do not fit the page")
        }
        val rightMost = if (isLeaf) 0L else data.u32(base + 8)
        return PageState(isLeaf, pageStart, pointerArray, cellCount, rightMost)
    }

    /** Offset of cell [index] from the start of its page; validated to lie in the cell content area. */
    private fun cellOffset(state: PageState, index: Int): Int {
        val offset = data.u16(state.pointerArray + 2 * index)
        val firstAllowed = state.pointerArray + 2 * state.cellCount - state.pageStart
        if (offset < firstAllowed || offset > usableSize - 4) {
            throw SqliteFormatException("cell offset $offset outside the cell content area of the page")
        }
        return offset
    }

    private fun leafCell(state: PageState, index: Int, visited: BitSet, budget: LongArray): Cell {
        val cursor = ByteCursor(data, state.pageStart + cellOffset(state, index), state.pageStart + usableSize)
        val payloadSize = cursor.varint()
        val rowId = cursor.varint()
        if (payloadSize < 0 || payloadSize > data.size) {
            throw SqliteFormatException("payload size $payloadSize is larger than the file")
        }
        budget[0] -= payloadSize + 2
        if (budget[0] < 0) throw SqliteFormatException("the cell payloads add up to more than the file size (overlapping cells)")
        val total = payloadSize.toInt()
        val maxLocal = usableSize - 35
        if (total <= maxLocal) {
            if (total > cursor.remaining) throw SqliteFormatException("cell payload runs past the end of the page")
            return Cell(rowId, data, cursor.position, total)
        }
        // Part of the payload stays on the page: K = M + ((P - M) % (U - 4)) if K <= X, else M.
        val minLocal = (usableSize - 12) * 32 / 255 - 23
        val spill = minLocal + (total - minLocal) % (usableSize - 4)
        val local = if (spill <= maxLocal) spill else minLocal
        if (local + 4 > cursor.remaining) throw SqliteFormatException("cell payload runs past the end of the page")
        val localStart = cursor.position
        val firstOverflow = data.u32(localStart + local)

        val payload = ByteArray(total)
        System.arraycopy(data, localStart, payload, 0, local)
        var filled = local
        var overflowPage = firstOverflow
        while (filled < total) {
            if (overflowPage < 2 || overflowPage > pageCount) {
                throw SqliteFormatException("overflow page number $overflowPage outside the file ($pageCount pages)")
            }
            val number = overflowPage.toInt()
            if (visited.get(number)) {
                throw SqliteFormatException("overflow page $number is referenced more than once (cycle or shared page)")
            }
            visited.set(number)
            val start = (number - 1) * pageSize
            val chunk = minOf(total - filled, usableSize - 4)
            System.arraycopy(data, start + 4, payload, filled, chunk)
            filled += chunk
            overflowPage = data.u32(start)
        }
        return Cell(rowId, payload, 0, total)
    }

    companion object {
        /** The 16-byte magic string at the start of every database file. */
        const val MAGIC: String = "SQLite format 3\u0000"

        private const val HEADER_SIZE = 100
        private const val LEAF_TABLE = 0x0D
        private const val INTERIOR_TABLE = 0x05
        private val SCHEMA_COLUMNS = listOf("type", "name", "tbl_name", "rootpage", "sql")

        /** True when [bytes] start with the SQLite magic string (a quick test, not a validation). */
        fun hasMagic(bytes: ByteArray): Boolean =
            bytes.size >= MAGIC.length && MAGIC.indices.all { bytes[it] == MAGIC[it].code.toByte() }

        /**
         * Opens a database held in memory: validates the 100-byte header and reads the schema.
         *
         * @throws SqliteFormatException not a SQLite file, unsupported (text encoding, version) or corrupt.
         */
        fun open(bytes: ByteArray): SqliteDatabase {
            if (bytes.size < HEADER_SIZE) throw SqliteFormatException("file too short for a SQLite database (${bytes.size} B)")
            if (!hasMagic(bytes)) throw SqliteFormatException("not a SQLite database (wrong header string)")

            val rawPageSize = bytes.u16(16)
            val pageSize = if (rawPageSize == 1) 65536 else rawPageSize
            if (pageSize < 512 || pageSize > 65536 || pageSize and (pageSize - 1) != 0) {
                throw SqliteFormatException("invalid page size $rawPageSize")
            }
            val readVersion = bytes[19].toInt() and 0xFF
            if (readVersion != 1 && readVersion != 2) {
                throw SqliteFormatException("unsupported file format read version $readVersion")
            }
            // Payload fractions are fixed by the file format (64, 32, 32); SQLite itself rejects other values.
            if (bytes[21].toInt() != 64 || bytes[22].toInt() != 32 || bytes[23].toInt() != 32) {
                throw SqliteFormatException("invalid payload fractions in the header")
            }
            val usableSize = pageSize - (bytes[20].toInt() and 0xFF)
            if (usableSize < 480) throw SqliteFormatException("usable page size $usableSize is too small")
            when (val encoding = bytes.u32(56)) {
                1L -> Unit
                0L -> throw SqliteFormatException("the database is empty (text encoding not set)")
                2L, 3L -> throw SqliteFormatException("UTF-16 databases are not supported")
                else -> throw SqliteFormatException("invalid text encoding $encoding")
            }

            // The page count in the header is valid only when the change counter equals "version-valid-for".
            val headerPages = bytes.u32(28)
            val pageCountLong =
                if (headerPages != 0L && bytes.u32(24) == bytes.u32(92)) headerPages else (bytes.size / pageSize).toLong()
            if (pageCountLong < 1) throw SqliteFormatException("file is shorter than one page")
            if (pageCountLong * pageSize > bytes.size) {
                throw SqliteFormatException(
                    "file is truncated: $pageCountLong pages of $pageSize B expected, only ${bytes.size} B present",
                )
            }
            val pageCount = pageCountLong.toInt()

            val database = SqliteDatabase(bytes, pageSize, pageCount, usableSize, emptyList())
            return SqliteDatabase(bytes, pageSize, pageCount, usableSize, database.readSchema())
        }
    }

    /** Reads `sqlite_schema` (b-tree at page 1): `type, name, tbl_name, rootpage, sql`. Only ordinary tables are kept. */
    private fun readSchema(): List<SchemaEntry> {
        val result = ArrayList<SchemaEntry>()
        for (cell in cells(1)) {
            val record = RecordDecoder.decode(cell.buffer, cell.offset, cell.length)
            if (record.size < 4) throw SqliteFormatException("sqlite_schema row ${cell.rowId} has only ${record.size} columns")
            val type = (record[0] as? SqliteValue.Text)?.value
            val name = (record[1] as? SqliteValue.Text)?.value
            val rootPage = (record[3] as? SqliteValue.Integer)?.value
            if (type == null || name == null || rootPage == null) {
                throw SqliteFormatException("sqlite_schema row ${cell.rowId} is malformed")
            }
            if (type != "table" || rootPage == 0L) continue // indexes, views, triggers, virtual tables
            if (rootPage < 2 || rootPage > pageCount) {
                throw SqliteFormatException("table '$name' has root page $rootPage outside the file ($pageCount pages)")
            }
            val sql = if (record.size > 4) (record[4] as? SqliteValue.Text)?.value else null
            result += SchemaEntry(name, rootPage.toInt(), sql)
        }
        return result
    }
}
