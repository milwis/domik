package app.domik.demo

import app.domik.deimic.protocol.Crc16Arc

/**
 * The configuration database of a [DemoHome] as a controller would serve it (`/logs/database-last.db3`): a real
 * SQLite file with the controller's tables `template_levels`, `template_rooms` and `outputs` (column layout of
 * the controller's schema), filled only with the fictional house. Deterministic: the same home gives the same
 * bytes (and therefore the same checksum) every time.
 */
class DemoDatabase private constructor(val home: DemoHome, private val bytes: ByteArray) {
    /** The SQLite file (a copy). */
    fun bytes(): ByteArray = bytes.copyOf()

    val size: Int get() = bytes.size

    /** CRC-16/ARC of the file – the version token the controller reports for command 0x1C8. */
    val checksum: UShort = Crc16Arc.checksum(bytes)

    companion object {
        /** The file the controller serves for command 0x212 (the only path [FakeController] knows). */
        const val PATH: String = "/logs/database-last.db3"

        /** A shutter drives a pair of relays: `outputs_number` of its row. */
        private const val SHUTTER_RELAYS = 2

        private const val LEVELS_SQL =
            "CREATE TABLE template_levels (id INTEGER PRIMARY KEY, Name TEXT, id_template INTEGER, have_rooms INTEGER)"
        private const val ROOMS_SQL = "CREATE TABLE template_rooms (id INTEGER PRIMARY KEY, id_level INTEGER, " +
            "id_template INTEGER, Name TEXT, id_room_group INTEGER)"
        private const val OUTPUTS_SQL =
            "CREATE TABLE \"outputs\" (\"id\" INTEGER PRIMARY KEY ,\"id_device\" INTEGER,\"output_nr\" INTEGER," +
                "\"id_level\" INTEGER,\"id_room\" INTEGER,\"output_type\" INTEGER,\"Name\" TEXT," +
                "\"show_on_panel\" INTEGER,\"pin_lock\" INTEGER DEFAULT (0) ,\"icon_id\" INTEGER," +
                "\"can_on\" INTEGER DEFAULT (1) ,\"id_device_in\" INTEGER DEFAULT (0) ," +
                "\"output_nr_in\" INTEGER DEFAULT (0), \"output_relay\" INTEGER DEFAULT -1, " +
                "\"outputs_number\" INTEGER DEFAULT 0, \"question\" INTEGER DEFAULT 0)"

        fun build(home: DemoHome): DemoDatabase {
            val levels = home.levels.map { SqliteFile.Row(it.id.toLong(), listOf(null, it.name, 1, 1)) }
            val rooms = home.rooms.map { SqliteFile.Row(it.id.toLong(), listOf(null, it.levelId, 1, it.name, 0)) }
            val outputs = home.outputs.map { output ->
                outputRow(
                    output.module, output.roomId, home, output.outputType, output.name, output.iconId, output.relay,
                    output.outputsNumber, outputNr = 0,
                )
            }
            val shutters = home.shutters.map { shutter ->
                outputRow(
                    shutter.module, shutter.roomId, home, DemoOutputType.SHUTTER, shutter.name, shutter.iconId,
                    shutter.relay, SHUTTER_RELAYS, outputNr = shutter.outputNr,
                )
            }
            val zones = home.zones.map { zone ->
                outputRow(
                    zone.module, zone.roomId, home, DemoOutputType.HEATING_ZONE, zone.name, 0, zone.relay, 0,
                    outputNr = zone.outputNr,
                )
            }
            // Row ids run through outputs, shutters and zones in one sequence (the order of the controller's table).
            val outputRows = (outputs + shutters + zones).mapIndexed { index, values -> SqliteFile.Row(index + 1L, values) }
            val file = SqliteFile.build(
                listOf(
                    SqliteFile.Table("template_levels", LEVELS_SQL, levels),
                    SqliteFile.Table("template_rooms", ROOMS_SQL, rooms),
                    SqliteFile.Table("outputs", OUTPUTS_SQL, outputRows),
                ),
            )
            return DemoDatabase(home, file)
        }

        /** Values of an `outputs` row in column order, `id` (rowid alias) as null. */
        private fun outputRow(
            module: Int,
            roomId: Int,
            home: DemoHome,
            outputType: Int,
            name: String,
            iconId: Int,
            relay: Int,
            outputsNumber: Int,
            outputNr: Int,
        ): List<Any?> = listOf(
            null, module, outputNr, home.levelOf(roomId), roomId, outputType, name, 1, 0, iconId, 1, 0, 0, relay,
            outputsNumber, 0,
        )
    }
}
