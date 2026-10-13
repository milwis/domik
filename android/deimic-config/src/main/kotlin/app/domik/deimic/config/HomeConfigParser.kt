package app.domik.deimic.config

import app.domik.deimic.config.sqlite.Row
import app.domik.deimic.config.sqlite.SqliteDatabase
import app.domik.deimic.config.sqlite.SqliteException
import app.domik.deimic.config.sqlite.SqliteValue

/**
 * Turns the controller's SQLite configuration database (`/logs/database-last.db3`) into a [HomeConfig].
 *
 * Reads the rows of `outputs` with `show_on_panel = 1 OR output_type = 123`, plus
 * `template_rooms (id, id_level, Name)` and `template_levels (id, Name)`. Extra tables and columns are ignored.
 *
 * The `output_type` of a row decides what it is and which of its columns matter (the type is checked first):
 * - 123 -> [HeatingZone], addressed by `output_nr` (0..31).
 * - 126 -> [TemperatureSensor], addressed by `output_nr` (0..127); `output_relay`, `icon_id`, `can_on` and
 *   `outputs_number` are not read.
 * - 5 -> [Shutter], addressed by `output_nr` (0..63); `output_relay` (the first relay of the shutter's pair),
 *   `can_on` and `outputs_number` are not read, so they never skip the row.
 * - 1 / 4 / 7 / 8 -> [Output] of the matching [OutputKind], addressed by `output_relay` (0..127).
 * - any other type (e.g. other modules' virtual items with `output_relay = -1`) -> exactly one
 *   [ConfigIssueKind.UNSUPPORTED_OUTPUT_TYPE] issue (informational, [ConfigIssue.isInformational]) and nothing else:
 *   no skip, room or duplicate issues. When the row has a valid `id_device`, name and `output_relay` it is also kept
 *   as an [Output] of kind [OutputKind.UNKNOWN] (diagnostics only), otherwise it is left out.
 *
 * The optional table `inputs (id, id_device, input_nr, id_level, id_room, input_type, name)` becomes [AnalogInput]s
 * (all types are kept; [AnalogInput.isTemperature] tells the temperature ones). A database without the table, or
 * with one lacking these columns, simply yields no inputs - never an error. A row is skipped (issue in table
 * `inputs`) when `id_device` (> 0), `input_nr` (0..255) or `input_type` is NULL / of the wrong type / out of range, or
 * `id_room` / `id_level` have the wrong type; a NULL or empty name gets the [ConfigTexts] fallback. No room, level
 * or duplicate checks are made for inputs.
 *
 * Addresses are compared only within one kind of address: relay outputs, heating zones, shutters and temperature
 * sensors are separate spaces, so a sensor numbered 0 never duplicates a shutter numbered 0 or a relay 0.
 *
 * For zones, shutters, sensors and outputs of a known kind a row is skipped, with an issue naming the column, when `output_type`,
 * `id_device` (> 0), `Name`, the address column or a numeric column that is read for that type is NULL where required,
 * of the wrong type or out of range. For zones and outputs the checks run in this order: output_type, id_device, Name,
 * output_nr | output_relay, icon_id, id_room, id_level, can_on, outputs_number; for shutters: output_type, id_device,
 * Name, output_nr, icon_id, id_room, id_level; for sensors: output_type, id_device, Name, output_nr, id_room,
 * id_level.
 *
 * [NO_ROOM_ID] / [NO_LEVEL_ID] as `id_room` / `id_level` mean "none": kept as stored, not reported as unknown.
 *
 * Names are trimmed; an empty name is replaced by [ConfigTexts] (a NULL name skips an output, zone or sensor row, but
 * only falls back for levels and rooms).
 */
class HomeConfigParser(private val texts: ConfigTexts = ConfigTexts.Polish) {
    /**
     * @throws ConfigParseException not a readable database, or a required table / column is missing.
     */
    fun parse(bytes: ByteArray): HomeConfig {
        val database = try {
            SqliteDatabase.open(bytes)
        } catch (error: SqliteException) {
            throw ConfigParseException("cannot read the configuration database: ${error.message}", error)
        }
        return parse(database)
    }

    /** @throws ConfigParseException a required table / column is missing, or the file turns out to be corrupt. */
    fun parse(database: SqliteDatabase): HomeConfig = try {
        read(database)
    } catch (error: SqliteException) {
        throw ConfigParseException("cannot read the configuration database: ${error.message}", error)
    }

    private fun read(database: SqliteDatabase): HomeConfig {
        checkSchema(database)
        val issues = ArrayList<ConfigIssue>()

        val levels = ArrayList<Level>()
        for (row in database.rows(LEVELS)) {
            try {
                val id = row.id()
                levels += Level(id, row.optionalName() ?: texts.levelName(id))
            } catch (skip: SkipRow) {
                issues += skip.issue(LEVELS, row)
            }
        }
        val levelIds = levels.mapTo(HashSet()) { it.id }

        val rooms = ArrayList<Room>()
        for (row in database.rows(ROOMS)) {
            try {
                val id = row.id()
                val levelId = row.integer("id_level", required = false)
                val name = row.optionalName() ?: texts.roomName(id)
                rooms += Room(id, levelId, name)
                if (levelId != null && levelId != NO_LEVEL_ID && levelId !in levelIds) {
                    issues += ConfigIssue(
                        ConfigIssueKind.UNKNOWN_LEVEL, ROOMS, row.rowId, "id_level", "room $id refers to unknown level $levelId",
                    )
                }
            } catch (skip: SkipRow) {
                issues += skip.issue(ROOMS, row)
            }
        }
        val roomIds = rooms.mapTo(HashSet()) { it.id }

        val outputs = ArrayList<Output>()
        val zones = ArrayList<HeatingZone>()
        val sensors = ArrayList<TemperatureSensor>()
        val shutters = ArrayList<Shutter>()
        // One set per address space: relay outputs, heating zones, shutters and sensors are never compared.
        val outputAddresses = HashSet<Pair<Int, Int>>()
        val zoneAddresses = HashSet<Pair<Int, Int>>()
        val shutterAddresses = HashSet<Pair<Int, Int>>()
        val sensorAddresses = HashSet<Pair<Int, Int>>()

        fun checkPlace(row: Row, roomId: Int?, levelId: Int?) {
            if (roomId != null && roomId != NO_ROOM_ID && roomId !in roomIds) {
                issues += ConfigIssue(
                    ConfigIssueKind.UNKNOWN_ROOM, OUTPUTS, row.rowId, "id_room", "item refers to unknown room $roomId",
                )
            }
            if (levelId != null && levelId != NO_LEVEL_ID && levelId !in levelIds) {
                issues += ConfigIssue(
                    ConfigIssueKind.UNKNOWN_LEVEL, OUTPUTS, row.rowId, "id_level", "item refers to unknown level $levelId",
                )
            }
        }

        fun checkDuplicate(row: Row, seen: MutableSet<Pair<Int, Int>>, address: Pair<Int, Int>, what: String) {
            if (!seen.add(address)) {
                issues += ConfigIssue(
                    ConfigIssueKind.DUPLICATE_ADDRESS, OUTPUTS, row.rowId, null,
                    "$what ${address.first}:${address.second} is defined more than once",
                )
            }
        }

        for (row in database.rows(OUTPUTS)) {
            if (!isSelected(row)) continue
            try {
                val type = row.integer("output_type", required = true)!!
                when {
                    type == OutputKind.HEATING_ZONE_TYPE -> {
                        val zone = readZone(row)
                        zones += zone
                        checkDuplicate(row, zoneAddresses, zone.module to zone.outputNr, "heating zone")
                        checkPlace(row, zone.roomId, zone.levelId)
                    }
                    type == OutputKind.SHUTTER_TYPE -> {
                        val shutter = readShutter(row)
                        shutters += shutter
                        checkDuplicate(row, shutterAddresses, shutter.module to shutter.outputNr, "shutter")
                        checkPlace(row, shutter.roomId, shutter.levelId)
                    }
                    type == OutputKind.TEMPERATURE_SENSOR_TYPE -> {
                        val sensor = readSensor(row)
                        sensors += sensor
                        checkDuplicate(row, sensorAddresses, sensor.module to sensor.number, "temperature sensor")
                        checkPlace(row, sensor.roomId, sensor.levelId)
                    }
                    OutputKind.fromRawType(type) == OutputKind.UNKNOWN -> {
                        // Reported once and nothing else, whatever the other columns hold.
                        issues += ConfigIssue(
                            ConfigIssueKind.UNSUPPORTED_OUTPUT_TYPE, OUTPUTS, row.rowId, "output_type",
                            "unsupported output_type $type",
                        )
                        try {
                            outputs += readOutput(row, type)
                        } catch (notAddressable: SkipRow) {
                            // not addressable like a relay output (e.g. output_relay = -1): left out, the issue above is all
                        }
                    }
                    else -> {
                        val output = readOutput(row, type)
                        outputs += output
                        checkDuplicate(row, outputAddresses, output.module to output.relay, "output")
                        checkPlace(row, output.roomId, output.levelId)
                    }
                }
            } catch (skip: SkipRow) {
                issues += skip.issue(OUTPUTS, row)
            }
        }

        val inputs = readInputs(database, issues)

        val modules = (
            outputs.map { it.module } + zones.map { it.module } + shutters.map { it.module } + sensors.map { it.module }
            ).distinct().sorted()
        return HomeConfig(levels, rooms, modules, outputs, zones, issues, sensors, shutters, inputs)
    }

    /** Rows of the optional `inputs` table; none when the table or one of its columns is missing. */
    private fun readInputs(database: SqliteDatabase, issues: MutableList<ConfigIssue>): List<AnalogInput> {
        if (!database.hasTable(INPUTS)) return emptyList()
        val present = database.columns(INPUTS).mapTo(HashSet()) { it.lowercase() }
        if (INPUT_COLUMNS.any { it.lowercase() !in present }) return emptyList()
        val inputs = ArrayList<AnalogInput>()
        for (row in database.rows(INPUTS)) {
            try {
                val module = row.integer("id_device", required = true, min = 1)!!
                val inputNr = row.integer("input_nr", required = true, min = 0, max = MAX_INPUT)!!
                val type = row.integer("input_type", required = true)!!
                val roomId = row.integer("id_room", required = false)
                val levelId = row.integer("id_level", required = false)
                val name = row.optionalName() ?: texts.analogInputName(inputNr)
                inputs += AnalogInput(row.rowId, module, inputNr, roomId, levelId, type, name)
            } catch (skip: SkipRow) {
                issues += skip.issue(INPUTS, row)
            }
        }
        return inputs
    }

    /** Heating zone row (`output_type` 123); throws [SkipRow] for an unusable value. */
    private fun readZone(row: Row): HeatingZone {
        val module = row.integer("id_device", required = true, min = 1)!!
        val rawName = row.requiredName()
        val outputNr = row.integer("output_nr", required = true, min = 0, max = MAX_ZONE)!!
        // Not used for zones, but a wrong value still makes the row unusable (long-standing behaviour).
        row.integer("icon_id", required = false)
        val roomId = row.integer("id_room", required = false)
        val levelId = row.integer("id_level", required = false)
        row.integer("can_on", required = false)
        row.integer("outputs_number", required = false)
        return HeatingZone(
            dbId = row.rowId, module = module, outputNr = outputNr,
            name = rawName.ifEmpty { texts.heatingZoneName(outputNr) }, roomId = roomId, levelId = levelId,
        )
    }

    /**
     * Shutter row (`output_type` 5). Addressed by `output_nr`; `output_relay`, `can_on` and `outputs_number` describe
     * the pair of relays the shutter drives and are not needed, so a wrong value there does not make the row unusable.
     */
    private fun readShutter(row: Row): Shutter {
        val module = row.integer("id_device", required = true, min = 1)!!
        val rawName = row.requiredName()
        val outputNr = row.integer("output_nr", required = true, min = 0, max = MAX_SHUTTER)!!
        val iconId = row.integer("icon_id", required = false)
        val roomId = row.integer("id_room", required = false)
        val levelId = row.integer("id_level", required = false)
        return Shutter(
            dbId = row.rowId, module = module, outputNr = outputNr,
            name = rawName.ifEmpty { texts.shutterName(outputNr) }, roomId = roomId, levelId = levelId, iconId = iconId,
        )
    }

    /** Temperature sensor row (`output_type` 126); only the columns that matter for a sensor are read. */
    private fun readSensor(row: Row): TemperatureSensor {
        val module = row.integer("id_device", required = true, min = 1)!!
        val rawName = row.requiredName()
        val number = row.integer("output_nr", required = true, min = 0, max = MAX_SENSOR)!!
        val roomId = row.integer("id_room", required = false)
        val levelId = row.integer("id_level", required = false)
        return TemperatureSensor(
            dbId = row.rowId, module = module, number = number,
            name = rawName.ifEmpty { texts.temperatureSensorName(number) }, roomId = roomId, levelId = levelId,
        )
    }

    /** Relay output row of [type] (a known kind, or an unknown one that is addressable like a relay output). */
    private fun readOutput(row: Row, type: Int): Output {
        val module = row.integer("id_device", required = true, min = 1)!!
        val rawName = row.requiredName()
        val relay = row.integer("output_relay", required = true, min = 0, max = MAX_RELAY)!!
        val iconId = row.integer("icon_id", required = false)
        val roomId = row.integer("id_room", required = false)
        val levelId = row.integer("id_level", required = false)
        val canOn = row.integer("can_on", required = false)
        val outputsNumber = row.integer("outputs_number", required = false)
        return Output(
            dbId = row.rowId, module = module, relay = relay, kind = OutputKind.fromRawType(type), rawType = type,
            name = rawName.ifEmpty { texts.outputName(relay) }, roomId = roomId, levelId = levelId,
            iconId = iconId, canOn = canOn?.let { it != 0 } ?: true, outputsNumber = outputsNumber,
        )
    }

    /** `show_on_panel = 1 OR output_type = 123`, with SQLite's numeric comparison (NULL and other types do not match). */
    private fun isSelected(row: Row): Boolean =
        isNumber(row["show_on_panel"], 1) || isNumber(row["output_type"], OutputKind.HEATING_ZONE_TYPE.toLong())

    private fun isNumber(value: SqliteValue, expected: Long): Boolean = when (value) {
        is SqliteValue.Integer -> value.value == expected
        is SqliteValue.Real -> value.value == expected.toDouble()
        else -> false
    }

    private fun checkSchema(database: SqliteDatabase) {
        val problems = ArrayList<String>()
        for ((table, required) in REQUIRED_COLUMNS) {
            if (!database.hasTable(table)) {
                problems += "missing table '$table'"
                continue
            }
            val present = database.columns(table).mapTo(HashSet()) { it.lowercase() }
            val missing = required.filter { it.lowercase() !in present }
            if (missing.isNotEmpty()) problems += "table '$table' lacks column(s): ${missing.joinToString(", ")}"
        }
        if (problems.isNotEmpty()) {
            throw ConfigParseException("the database is not a Deimic configuration: ${problems.joinToString("; ")}")
        }
    }

    /** Row skipped because of the value in [column]; [missing] = NULL where a value is required. */
    private class SkipRow(val column: String, val missing: Boolean) : RuntimeException(null, null, false, false) {
        fun issue(table: String, row: Row): ConfigIssue = ConfigIssue(
            kind = if (missing) ConfigIssueKind.SKIPPED_MISSING_VALUE else ConfigIssueKind.SKIPPED_INVALID_VALUE,
            table = table,
            rowId = row.rowId,
            column = column,
            detail = if (missing) "required value is NULL" else "value is of the wrong type or out of range",
        )
    }

    /** Integer column value in [min]..[max]; NULL gives null (or skips the row when [required]). */
    private fun Row.integer(
        column: String,
        required: Boolean,
        min: Int = Int.MIN_VALUE,
        max: Int = Int.MAX_VALUE,
    ): Int? {
        val number: Long = when (val raw = this[column]) {
            SqliteValue.Null -> if (required) throw SkipRow(column, missing = true) else return null
            is SqliteValue.Integer -> raw.value
            is SqliteValue.Real ->
                if (raw.value == Math.rint(raw.value) && Math.abs(raw.value) < WHOLE_NUMBER_LIMIT) {
                    raw.value.toLong()
                } else {
                    throw SkipRow(column, missing = false)
                }
            else -> throw SkipRow(column, missing = false)
        }
        if (number < min || number > max) throw SkipRow(column, missing = false)
        return number.toInt()
    }

    /** The `id` column, or the rowid when the column is not a rowid alias and holds NULL. */
    private fun Row.id(): Int {
        if (isNull("id")) {
            if (rowId < Int.MIN_VALUE || rowId > Int.MAX_VALUE) throw SkipRow("id", missing = false)
            return rowId.toInt()
        }
        return integer("id", required = true)!!
    }

    /** Name of an output or zone: NULL skips the row, a non-text value is invalid; the text is trimmed (may be empty). */
    private fun Row.requiredName(): String = when (val raw = this["Name"]) {
        SqliteValue.Null -> throw SkipRow("Name", missing = true)
        is SqliteValue.Text -> raw.value.trim()
        else -> throw SkipRow("Name", missing = false)
    }

    /** Name of a level or room: trimmed text, or null (NULL or empty) for the fallback; a non-text value skips the row. */
    private fun Row.optionalName(): String? = when (val raw = this["Name"]) {
        SqliteValue.Null -> null
        is SqliteValue.Text -> raw.value.trim().ifEmpty { null }
        else -> throw SkipRow("Name", missing = false)
    }

    private companion object {
        const val LEVELS = "template_levels"
        const val ROOMS = "template_rooms"
        const val OUTPUTS = "outputs"
        const val INPUTS = "inputs"
        const val MAX_INPUT = 255
        const val MAX_RELAY = 127
        const val MAX_ZONE = 31
        const val MAX_SHUTTER = 63
        const val MAX_SENSOR = 127

        /** Doubles below this magnitude convert to Long exactly. */
        const val WHOLE_NUMBER_LIMIT = 9.0e18

        val INPUT_COLUMNS = listOf("id_device", "input_nr", "id_room", "id_level", "input_type", "Name")

        val REQUIRED_COLUMNS: Map<String, List<String>> = linkedMapOf(
            LEVELS to listOf("id", "Name"),
            ROOMS to listOf("id", "id_level", "Name"),
            OUTPUTS to listOf(
                "id", "id_device", "output_nr", "output_relay", "outputs_number", "output_type", "Name", "icon_id",
                "id_room", "id_level", "can_on", "show_on_panel",
            ),
        )
    }
}
