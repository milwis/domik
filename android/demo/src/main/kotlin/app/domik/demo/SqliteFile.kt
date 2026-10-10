package app.domik.demo

import java.io.ByteArrayOutputStream

/**
 * Minimal SQLite file writer, just enough to produce a valid database of a few small rowid tables (every table is
 * a single leaf page, no indexes, no overflow pages). The demo controller needs real SQLite bytes because the
 * app reads the downloaded configuration with the same code as from a real controller.
 *
 * Values of a row: `null`, [Int], [Long] or [String] (UTF-8).
 */
internal object SqliteFile {
    class Table(val name: String, val sql: String, val rows: List<Row>)

    /** [rowId] is the table's rowid; an `INTEGER PRIMARY KEY` column is stored as `null` in [values]. */
    class Row(val rowId: Long, val values: List<Any?>)

    private val PAGE_SIZES = listOf(4096, 8192, 16384, 32768, 65536)
    private const val LEAF_TABLE_PAGE = 0x0D
    private const val FIRST_PAGE_HEADER_OFFSET = 100
    private const val DB_HEADER_LENGTH = 100

    /** Builds the file; the smallest page size in which every table fits one leaf page is used. */
    fun build(tables: List<Table>): ByteArray {
        for (pageSize in PAGE_SIZES) {
            build(tables, pageSize)?.let { return it }
        }
        throw IllegalArgumentException("the tables do not fit single leaf pages even at ${PAGE_SIZES.last()} bytes")
    }

    private fun build(tables: List<Table>, pageSize: Int): ByteArray? {
        val schemaRows = tables.mapIndexed { index, table ->
            Row((index + 1).toLong(), listOf("table", table.name, table.name, index + 2, table.sql))
        }
        val first = leafPage(pageSize, schemaRows, FIRST_PAGE_HEADER_OFFSET) ?: return null
        val tablePages = tables.map { leafPage(pageSize, it.rows.sortedBy(Row::rowId), 0) ?: return null }

        val header = ByteArray(DB_HEADER_LENGTH)
        "SQLite format 3\u0000".forEachIndexed { index, char -> header[index] = char.code.toByte() }
        put16(header, 16, if (pageSize == 65536) 1 else pageSize)
        header[18] = 1 // file format write version: legacy (rollback journal)
        header[19] = 1 // read version
        header[20] = 0 // reserved bytes per page
        header[21] = 64 // maximum embedded payload fraction (must be 64)
        header[22] = 32 // minimum embedded payload fraction (must be 32)
        header[23] = 32 // leaf payload fraction (must be 32)
        put32(header, 24, 1) // file change counter
        put32(header, 28, (tablePages.size + 1).toLong()) // database size in pages
        put32(header, 40, 1) // schema cookie
        put32(header, 44, 4) // schema format number
        put32(header, 56, 1) // text encoding: UTF-8
        put32(header, 92, 1) // version-valid-for = file change counter
        put32(header, 96, SQLITE_VERSION_NUMBER)
        header.copyInto(first)

        val out = ByteArrayOutputStream(pageSize * (tablePages.size + 1))
        out.write(first)
        tablePages.forEach(out::write)
        return out.toByteArray()
    }

    /**
     * One table b-tree leaf page, or null when the cells do not fit. Cells are packed from the end of the page,
     * the cell pointer array follows the 8-byte page header at [headerOffset].
     */
    private fun leafPage(pageSize: Int, rows: List<Row>, headerOffset: Int): ByteArray? {
        val page = ByteArray(pageSize)
        val pointersStart = headerOffset + 8
        var end = pageSize
        val offsets = ArrayList<Int>(rows.size)
        for (row in rows) {
            val payload = record(row.values)
            // No overflow pages: the payload must fit locally (maximum local size of a table leaf cell).
            if (payload.size > pageSize - 35) return null
            val cell = varint(payload.size.toLong()) + varint(row.rowId) + payload
            end -= cell.size
            if (end < pointersStart + 2 * (offsets.size + 1)) return null
            cell.copyInto(page, end)
            offsets += end
        }
        page[headerOffset] = LEAF_TABLE_PAGE.toByte()
        put16(page, headerOffset + 3, rows.size)
        put16(page, headerOffset + 5, if (end == 65536) 0 else end)
        offsets.forEachIndexed { index, offset -> put16(page, pointersStart + 2 * index, offset) }
        return page
    }

    private fun record(values: List<Any?>): ByteArray {
        val types = ByteArrayOutputStream()
        val body = ByteArrayOutputStream()
        for (value in values) {
            when (value) {
                null -> types.write(varint(0))
                is Int -> integer(value.toLong(), types, body)
                is Long -> integer(value, types, body)
                is String -> {
                    val bytes = value.toByteArray(Charsets.UTF_8)
                    types.write(varint(13L + 2L * bytes.size))
                    body.write(bytes)
                }
                else -> throw IllegalArgumentException("unsupported value type ${value::class.simpleName}")
            }
        }
        val typeBytes = types.toByteArray()
        // The header size is a varint that counts itself; it fits one byte for all rows written here.
        val headerSize = 1 + typeBytes.size
        require(headerSize < 128) { "too many columns" }
        return byteArrayOf(headerSize.toByte()) + typeBytes + body.toByteArray()
    }

    private fun integer(value: Long, types: ByteArrayOutputStream, body: ByteArrayOutputStream) {
        val (type, size) = when {
            value == 0L -> 8 to 0
            value == 1L -> 9 to 0
            value in Byte.MIN_VALUE..Byte.MAX_VALUE -> 1 to 1
            value in Short.MIN_VALUE..Short.MAX_VALUE -> 2 to 2
            value in -8388608L..8388607L -> 3 to 3
            value in Int.MIN_VALUE..Int.MAX_VALUE -> 4 to 4
            else -> 6 to 8
        }
        types.write(varint(type.toLong()))
        for (index in size - 1 downTo 0) body.write((value shr (8 * index)).toInt() and 0xFF)
    }

    /** SQLite varint of a non-negative value below 2^56 (more than enough for sizes and rowids here). */
    private fun varint(value: Long): ByteArray {
        require(value >= 0 && value < (1L shl 56)) { "varint out of range" }
        val groups = ArrayList<Int>()
        var rest = value
        do {
            groups += (rest and 0x7F).toInt()
            rest = rest ushr 7
        } while (rest != 0L)
        return ByteArray(groups.size) { index ->
            val group = groups[groups.size - 1 - index]
            (if (index < groups.size - 1) group or 0x80 else group).toByte()
        }
    }

    private fun put16(target: ByteArray, at: Int, value: Int) {
        target[at] = (value shr 8).toByte()
        target[at + 1] = value.toByte()
    }

    private fun put32(target: ByteArray, at: Int, value: Long) {
        for (index in 0 until 4) target[at + index] = (value shr (8 * (3 - index))).toByte()
    }

    /** Written into the header as "the SQLite version that last modified the file" (3.40.0). */
    private const val SQLITE_VERSION_NUMBER = 3_040_000L
}
