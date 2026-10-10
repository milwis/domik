package app.domik.deimic.config.sqlite

import java.io.ByteArrayOutputStream

/**
 * Builds small SQLite files byte by byte, so that tests can create exactly the structures (and the corruptions)
 * they need without a SQLite library. Page numbers are chosen by the caller; page 1 holds the schema.
 */
internal object SqliteBuilder {
    const val LEAF = 0x0D
    const val INTERIOR = 0x05

    fun varint(value: Long): ByteArray {
        if (value < 0 || value > 0x00FF_FFFF_FFFF_FFFFL) {
            // 9-byte form: 8 groups of 7 bits followed by a full byte.
            val out = ByteArray(9)
            out[8] = (value and 0xFF).toByte()
            var rest = value ushr 8
            for (index in 7 downTo 0) {
                out[index] = ((rest and 0x7F) or 0x80).toByte()
                rest = rest ushr 7
            }
            return out
        }
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

    /** Smallest serial type for an integer, as SQLite writes it. */
    private fun integerType(value: Long): Int = when {
        value == 0L -> 8
        value == 1L -> 9
        value in -128..127 -> 1
        value in -32768..32767 -> 2
        value in -8388608..8388607 -> 3
        value in Int.MIN_VALUE..Int.MAX_VALUE -> 4
        value in -(1L shl 47) until (1L shl 47) -> 5
        else -> 6
    }

    private fun integerBytes(value: Long, size: Int): ByteArray = ByteArray(size) { (value shr (8 * (size - 1 - it))).toByte() }

    /** Record from values: null, Int/Long, Double, String (UTF-8), ByteArray. */
    fun record(vararg values: Any?): ByteArray = recordOf(values.toList())

    fun recordOf(values: List<Any?>): ByteArray {
        val types = ByteArrayOutputStream()
        val body = ByteArrayOutputStream()
        for (value in values) {
            when (value) {
                null -> types.write(varint(0))
                is Int -> integer(value.toLong(), types, body)
                is Long -> integer(value, types, body)
                is Double -> {
                    types.write(varint(7))
                    body.write(integerBytes(value.toRawBits(), 8))
                }
                is String -> {
                    val bytes = value.toByteArray(Charsets.UTF_8)
                    types.write(varint(13L + 2L * bytes.size))
                    body.write(bytes)
                }
                is ByteArray -> {
                    types.write(varint(12L + 2L * value.size))
                    body.write(value)
                }
                else -> error("unsupported value $value")
            }
        }
        return withHeader(types.toByteArray(), body.toByteArray())
    }

    /** Record from already encoded serial types and body bytes. */
    fun withHeader(types: ByteArray, body: ByteArray): ByteArray {
        // The header size varint counts itself; it is one byte for all headers used in the tests.
        val headerSize = 1 + types.size
        require(headerSize < 128)
        return byteArrayOf(headerSize.toByte()) + types + body
    }

    private fun integer(value: Long, types: ByteArrayOutputStream, body: ByteArrayOutputStream) {
        val type = integerType(value)
        types.write(varint(type.toLong()))
        val size = when (type) {
            1 -> 1; 2 -> 2; 3 -> 3; 4 -> 4; 5 -> 6; 6 -> 8; else -> 0
        }
        body.write(integerBytes(value, size))
    }

    /** Table leaf cell: payload size, rowid, payload. */
    fun leafCell(rowId: Long, payload: ByteArray): ByteArray = varint(payload.size.toLong()) + varint(rowId) + payload

    /** Table interior cell: 4-byte child page number, rowid key. */
    fun interiorCell(child: Int, key: Long): ByteArray = integerBytes(child.toLong(), 4) + varint(key)

    /**
     * One b-tree page. Cells are packed from the end of the page; the pointer array follows the page header.
     * [headerOffset] is 100 for page 1. Pointers can be overridden to build corrupt pages.
     */
    fun page(
        pageSize: Int,
        type: Int,
        cells: List<ByteArray>,
        rightMost: Int = 0,
        headerOffset: Int = 0,
        pointerOverride: List<Int>? = null,
        cellCountOverride: Int? = null,
        reserved: Int = 0,
    ): ByteArray {
        val page = ByteArray(pageSize)
        val headerSize = if (type == LEAF) 8 else 12
        val offsets = ArrayList<Int>()
        var end = pageSize - reserved
        for (cell in cells) {
            end -= cell.size
            require(end >= headerOffset + headerSize + 2 * cells.size) { "cells do not fit the page" }
            System.arraycopy(cell, 0, page, end, cell.size)
            offsets += end
        }
        page[headerOffset] = type.toByte()
        put16(page, headerOffset + 3, cellCountOverride ?: cells.size)
        put16(page, headerOffset + 5, end)
        if (type == INTERIOR) put32(page, headerOffset + 8, rightMost.toLong())
        val pointers = pointerOverride ?: offsets
        pointers.forEachIndexed { index, offset -> put16(page, headerOffset + headerSize + 2 * index, offset) }
        return page
    }

    fun leafPage(pageSize: Int, rows: List<Pair<Long, ByteArray>>, headerOffset: Int = 0, reserved: Int = 0): ByteArray =
        page(pageSize, LEAF, rows.map { (rowId, payload) -> leafCell(rowId, payload) }, headerOffset = headerOffset, reserved = reserved)

    fun put16(target: ByteArray, at: Int, value: Int) {
        target[at] = (value shr 8).toByte()
        target[at + 1] = value.toByte()
    }

    fun put32(target: ByteArray, at: Int, value: Long) {
        for (index in 0 until 4) target[at + index] = (value shr (8 * (3 - index))).toByte()
    }

    class SchemaRow(val name: String, val rootPage: Int, val sql: String, val type: String = "table")

    /**
     * Whole database: header, schema on page 1, then [pages] as pages 2, 3, … . [pageCountOverride] and
     * [textEncoding] allow producing inconsistent headers.
     */
    fun database(
        pageSize: Int,
        schema: List<SchemaRow>,
        pages: List<ByteArray>,
        pageCountOverride: Int? = null,
        textEncoding: Int = 1,
        reservedBytes: Int = 0,
    ): ByteArray {
        val schemaRows = schema.mapIndexed { index, row ->
            (index + 1).toLong() to record(row.type, row.name, row.name, row.rootPage, row.sql)
        }
        val first = leafPage(pageSize, schemaRows, headerOffset = 100, reserved = reservedBytes)
        val header = ByteArray(100)
        "SQLite format 3\u0000".forEachIndexed { index, char -> header[index] = char.code.toByte() }
        put16(header, 16, if (pageSize == 65536) 1 else pageSize)
        header[18] = 1
        header[19] = 1
        header[20] = reservedBytes.toByte()
        header[21] = 64
        header[22] = 32
        header[23] = 32
        put32(header, 24, 1)
        put32(header, 28, (pageCountOverride ?: (pages.size + 1)).toLong())
        put32(header, 92, 1)
        put32(header, 56, textEncoding.toLong())
        System.arraycopy(header, 0, first, 0, 100)
        return pages.fold(first) { acc, page -> acc + page }
    }

    /** One-table database whose rows are the given records (all fit one leaf page). */
    fun singleTable(
        sql: String,
        records: List<ByteArray>,
        pageSize: Int = 1024,
        name: String = "t",
    ): ByteArray = database(
        pageSize,
        listOf(SchemaRow(name, 2, sql)),
        listOf(leafPage(pageSize, records.mapIndexed { index, payload -> (index + 1).toLong() to payload })),
    )
}
