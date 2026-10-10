package app.domik.deimic.config

import app.domik.deimic.config.sqlite.SqliteBuilder
import app.domik.deimic.config.sqlite.SqliteBuilder.SchemaRow

/** Hand-made controller databases for parser tests: only the three tables the parser reads. */
internal object ConfigDatabaseBuilder {
    const val LEVELS_SQL = "CREATE TABLE template_levels (id INTEGER PRIMARY KEY, Name VARCHAR(30))"
    const val ROOMS_SQL = "CREATE TABLE template_rooms (id INTEGER PRIMARY KEY, id_level INTEGER, Name VARCHAR(30))"
    const val OUTPUTS_SQL = "CREATE TABLE outputs (id INTEGER PRIMARY KEY, id_device INTEGER, output_nr INTEGER, " +
        "output_relay INTEGER, outputs_number INTEGER, output_type INTEGER, Name VARCHAR(30), icon_id INTEGER, " +
        "id_room INTEGER, id_level INTEGER, can_on INTEGER, show_on_panel INTEGER)"

    /** Row of `template_levels`: id, Name. */
    fun level(id: Long, name: Any?) = listOf<Any?>(id, name)

    /** Row of `template_rooms`: id, id_level, Name. */
    fun room(id: Long, levelId: Any?, name: Any?) = listOf<Any?>(id, levelId, name)

    /** Row of `outputs`: id, id_device, output_nr, output_relay, outputs_number, output_type, Name, icon_id, id_room, id_level, can_on, show_on_panel. */
    fun output(
        id: Long,
        device: Any? = 1,
        nr: Any? = 0,
        relay: Any? = 0,
        number: Any? = 0,
        type: Any? = 1,
        name: Any? = "x",
        icon: Any? = null,
        room: Any? = null,
        level: Any? = null,
        canOn: Any? = 1,
        show: Any? = 1,
    ) = listOf(id, device, nr, relay, number, type, name, icon, room, level, canOn, show)

    private fun leaf(pageSize: Int, rows: List<List<Any?>>): ByteArray = SqliteBuilder.leafPage(
        pageSize,
        rows.map { row -> (row[0] as Long) to SqliteBuilder.recordOf(listOf<Any?>(null) + row.drop(1)) },
    )

    fun database(
        levels: List<List<Any?>> = emptyList(),
        rooms: List<List<Any?>> = emptyList(),
        outputs: List<List<Any?>> = emptyList(),
        pageSize: Int = 4096,
        levelsSql: String = LEVELS_SQL,
        roomsSql: String = ROOMS_SQL,
        outputsSql: String = OUTPUTS_SQL,
    ): ByteArray = SqliteBuilder.database(
        pageSize,
        listOf(
            SchemaRow("template_levels", 2, levelsSql),
            SchemaRow("template_rooms", 3, roomsSql),
            SchemaRow("outputs", 4, outputsSql),
        ),
        listOf(leaf(pageSize, levels), leaf(pageSize, rooms), leaf(pageSize, outputs)),
    )
}
