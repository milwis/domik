package app.domik.deimic.config

/**
 * `id_room` that the controller's database uses for items that belong to no room (e.g. global / virtual items).
 * Observed in a real controller database (rows of ordinary outputs carry it together with [NO_LEVEL_ID]); the meaning
 * is inferred, not documented by the manufacturer - to be confirmed. Such an item is not reported as
 * [ConfigIssueKind.UNKNOWN_ROOM]; its `roomId` stays as stored and [HomeConfig.roomOf] returns null for it.
 */
const val NO_ROOM_ID: Int = 1000

/**
 * `id_level` that the controller's database uses for items (or rooms) that belong to no level. Observed in a real
 * controller database next to [NO_ROOM_ID]; to be confirmed. Not reported as [ConfigIssueKind.UNKNOWN_LEVEL]; the
 * stored value is kept and [HomeConfig.level] returns null for it.
 */
const val NO_LEVEL_ID: Int = 0

/** A floor / level of the house (`template_levels`). */
data class Level(val id: Int, val name: String)

/** A room (`template_rooms`); [levelId] is null when the database does not assign it to a level. */
data class Room(val id: Int, val levelId: Int?, val name: String)

/** What kind of device an [Output] is, from the database column `output_type`. */
enum class OutputKind(val rawType: Int?) {
    /** Light or socket (plain on/off relay). */
    ON_OFF(1),
    RGB(4),
    LED(7),
    DIMMER_ON_OFF(8),

    /** A type this version does not know; the item is kept (see [Output.rawType]) but cannot be controlled. */
    UNKNOWN(null);

    companion object {
        /** Heating zones use this `output_type`; they are modelled by [HeatingZone], not by [OutputKind]. */
        const val HEATING_ZONE_TYPE: Int = 123

        /**
         * Shutters use this `output_type`; they are modelled by [Shutter], not by [OutputKind]: a shutter occupies a
         * pair of relays (`outputs_number` = 2) but commands address it by its own number (`output_nr`).
         */
        const val SHUTTER_TYPE: Int = 5

        /** Temperature sensors use this `output_type`; they are modelled by [TemperatureSensor], not by [OutputKind]. */
        const val TEMPERATURE_SENSOR_TYPE: Int = 126

        fun fromRawType(raw: Int): OutputKind = entries.firstOrNull { it.rawType == raw } ?: UNKNOWN
    }
}

/**
 * A switchable device. Addressed by ([module], [relay]) = (`id_device`, `output_relay`).
 *
 * @property dbId row id in the controller's `outputs` table.
 * @property rawType the unmodified `output_type`.
 * @property roomId / [levelId] as stored; may refer to a room or level that does not exist (see [HomeConfig.issues]),
 *   or be the "none" marker [NO_ROOM_ID] / [NO_LEVEL_ID].
 * @property canOn `can_on`; a missing value counts as true (the column's default).
 * @property outputsNumber `outputs_number` as stored (purpose depends on the device type).
 */
data class Output(
    val dbId: Long,
    val module: Int,
    val relay: Int,
    val kind: OutputKind,
    val rawType: Int,
    val name: String,
    val roomId: Int?,
    val levelId: Int?,
    val iconId: Int?,
    val canOn: Boolean,
    val outputsNumber: Int?,
) {
    /** False for [OutputKind.UNKNOWN]: shown in diagnostics but not to be controlled. */
    val isSupported: Boolean get() = kind != OutputKind.UNKNOWN

    /** Frame address for commands (`Request.setOutput`). */
    val moduleAddress: UInt get() = module.toUInt()

    /** Payload byte for commands (`Request.setOutput`). */
    val relayByte: UByte get() = relay.toUByte()
}

/**
 * A heating zone (`output_type` 123). Addressed by ([module], [outputNr]) = (`id_device`, `output_nr`) – never by
 * the zone's `output_relay`, which differs from `output_nr`.
 */
data class HeatingZone(
    val dbId: Long,
    val module: Int,
    val outputNr: Int,
    val name: String,
    val roomId: Int?,
    val levelId: Int?,
) {
    val moduleAddress: UInt get() = module.toUInt()

    /** Payload byte for heating commands (`Request.heaterState`, `Request.setSetpoint`). */
    val zoneByte: UByte get() = outputNr.toUByte()
}

/**
 * A shutter (`output_type` 5). Addressed by ([module], [outputNr]) = (`id_device`, `output_nr`), the shutter's own number
 * 0, 1, 2… – never by `output_relay` (the shutter drives a pair of relays, `output_relay` = 0, 2, 4…, which commands do
 * not use). The relays do not appear among the [HomeConfig.outputs] and must never be switched with the relay commands.
 *
 * @property iconId `icon_id` as stored (observed 0 = no particular icon), or null.
 */
data class Shutter(
    val dbId: Long,
    val module: Int,
    val outputNr: Int,
    val name: String,
    val roomId: Int?,
    val levelId: Int?,
    val iconId: Int?,
) {
    val moduleAddress: UInt get() = module.toUInt()

    /** Payload byte for shutter commands (`Request.shutterUp`, `shutterDown`, `shutterStop`). */
    val shutterByte: UByte get() = outputNr.toUByte()
}

/**
 * A temperature sensor (`output_type` 126). Addressed by ([module], [number]) = (`id_device`, `output_nr`); the number
 * is the sensor index of the controller's state record (`temperatures`) and of the temperature reports (0x72 / 0x130).
 * The sensor's `output_relay` is meaningless (in the observed database it only repeats the number) and is ignored, so
 * a sensor never clashes with a relay output.
 *
 * Which value the controller reports for the sensor, and how it relates to the heating zone with the same number, is
 * not decided by the configuration (to be confirmed against live readings).
 */
data class TemperatureSensor(
    val dbId: Long,
    val module: Int,
    val number: Int,
    val name: String,
    val roomId: Int?,
    val levelId: Int?,
) {
    val moduleAddress: UInt get() = module.toUInt()
}

/**
 * An input of the controller (`inputs` table), addressed by ([module], [inputNr]) = (`id_device`, `input_nr`). Only
 * [inputType] [TEMPERATURE_INPUT_TYPE] (a temperature sensor input, the one that takes calibration 0x132) is used by the
 * app; other rows (e.g. [BUTTON_INPUT_TYPE]) are kept as stored.
 *
 * The input number is NOT the number of the heating zone it serves: the link goes through the room, see
 * [HomeConfig.temperatureInputsFor]. [roomId] / [levelId] as stored (may be [NO_ROOM_ID] / [NO_LEVEL_ID]).
 */
data class AnalogInput(
    val dbId: Long,
    val module: Int,
    val inputNr: Int,
    val roomId: Int?,
    val levelId: Int?,
    val inputType: Int,
    val name: String,
) {
    val moduleAddress: UInt get() = module.toUInt()

    val isTemperature: Boolean get() = inputType == TEMPERATURE_INPUT_TYPE

    companion object {
        /** `input_type` of a temperature sensor input. */
        const val TEMPERATURE_INPUT_TYPE: Int = 8

        /** `input_type` of a button input. */
        const val BUTTON_INPUT_TYPE: Int = 2
    }
}

enum class ConfigIssueKind {
    /** A row was left out because a required value is NULL. */
    SKIPPED_MISSING_VALUE,

    /** A row was left out because a value has the wrong type or is out of range (impossible address, …). */
    SKIPPED_INVALID_VALUE,

    /**
     * A row has an `output_type` this version does not handle (see [ConfigIssue.isInformational]). Reported once for
     * the row, whatever its other values are. When the row is addressable like a relay output (valid `id_device`,
     * `output_relay` and name) it is kept as [OutputKind.UNKNOWN], otherwise it is left out.
     */
    UNSUPPORTED_OUTPUT_TYPE,

    /** The item refers to a room id that is not in `template_rooms` and is not [NO_ROOM_ID] (the id is kept). */
    UNKNOWN_ROOM,

    /** The item refers to a level id that is not in `template_levels` and is not [NO_LEVEL_ID] (the id is kept). */
    UNKNOWN_LEVEL,

    /**
     * Two items of the same kind of address share it: relay outputs by (`id_device`, `output_relay`), heating zones by
     * (`id_device`, `output_nr`), shutters by (`id_device`, `output_nr`), temperature sensors by (`id_device`,
     * `output_nr`). Items of different kinds are never compared. Both are kept, lookups by address return the first.
     */
    DUPLICATE_ADDRESS,
}

/**
 * Something noteworthy found while reading the database. [detail] is technical text for diagnostics (not a UI
 * string, no names); [column] is the offending column of a skipped row.
 */
data class ConfigIssue(
    val kind: ConfigIssueKind,
    val table: String,
    val rowId: Long,
    val column: String?,
    val detail: String,
) {
    val isSkippedRow: Boolean
        get() = kind == ConfigIssueKind.SKIPPED_MISSING_VALUE || kind == ConfigIssueKind.SKIPPED_INVALID_VALUE

    /** Just a remark (an item of a type this version ignores), nothing is wrong with the configuration. */
    val isInformational: Boolean get() = kind == ConfigIssueKind.UNSUPPORTED_OUTPUT_TYPE
}

/**
 * The device model read from the controller's configuration database: levels, rooms, outputs, heating zones,
 * shutters and temperature sensors, independent of any UI. Used for dashboard tiles and for group operations ("all lights",
 * "heating in the bedroom").
 *
 * Items keep the database order. [modules] lists the distinct `id_device` values of the items.
 * [analogInputs] is read from the optional `inputs` table (empty when the database has none).
 * [temperatureSensors], [shutters] and [analogInputs] come last (and default to empty) so that earlier positional constructions
 * keep working.
 */
data class HomeConfig(
    val levels: List<Level>,
    val rooms: List<Room>,
    val modules: List<Int>,
    val outputs: List<Output>,
    val heatingZones: List<HeatingZone>,
    val issues: List<ConfigIssue> = emptyList(),
    val temperatureSensors: List<TemperatureSensor> = emptyList(),
    val shutters: List<Shutter> = emptyList(),
    val analogInputs: List<AnalogInput> = emptyList(),
) {
    private val levelsById: Map<Int, Level> by lazy { levels.associateBy { it.id } }
    private val roomsById: Map<Int, Room> by lazy { rooms.associateBy { it.id } }
    private val outputsByAddress: Map<Pair<Int, Int>, Output> by lazy {
        // Items of known kinds go first: one of an unknown type never shadows a real output with the same address.
        LinkedHashMap<Pair<Int, Int>, Output>().also { map ->
            outputs.sortedBy { if (it.isSupported) 0 else 1 }.forEach { map.putIfAbsent(it.module to it.relay, it) }
        }
    }
    private val zonesByAddress: Map<Pair<Int, Int>, HeatingZone> by lazy {
        LinkedHashMap<Pair<Int, Int>, HeatingZone>().also { map -> heatingZones.forEach { map.putIfAbsent(it.module to it.outputNr, it) } }
    }
    private val shuttersByAddress: Map<Pair<Int, Int>, Shutter> by lazy {
        LinkedHashMap<Pair<Int, Int>, Shutter>().also { map -> shutters.forEach { map.putIfAbsent(it.module to it.outputNr, it) } }
    }
    private val sensorsByAddress: Map<Pair<Int, Int>, TemperatureSensor> by lazy {
        LinkedHashMap<Pair<Int, Int>, TemperatureSensor>().also { map ->
            temperatureSensors.forEach { map.putIfAbsent(it.module to it.number, it) }
        }
    }

    /** Outputs that can be controlled (kind known). */
    val supportedOutputs: List<Output> get() = outputs.filter { it.isSupported }

    /** Nothing to control: no outputs, heating zones or shutters (sensors alone are read-only). */
    val isEmpty: Boolean get() = outputs.isEmpty() && heatingZones.isEmpty() && shutters.isEmpty()

    fun level(id: Int): Level? = levelsById[id]

    fun room(id: Int): Room? = roomsById[id]

    fun roomsOnLevel(levelId: Int): List<Room> = rooms.filter { it.levelId == levelId }

    /** The room an item belongs to, if it has one and the room exists. */
    fun roomOf(output: Output): Room? = output.roomId?.let(::room)

    fun roomOf(zone: HeatingZone): Room? = zone.roomId?.let(::room)

    fun roomOf(shutter: Shutter): Room? = shutter.roomId?.let(::room)

    fun roomOf(sensor: TemperatureSensor): Room? = sensor.roomId?.let(::room)

    fun outputsInRoom(roomId: Int): List<Output> = outputs.filter { it.roomId == roomId }

    fun heatingZonesInRoom(roomId: Int): List<HeatingZone> = heatingZones.filter { it.roomId == roomId }

    fun shuttersInRoom(roomId: Int): List<Shutter> = shutters.filter { it.roomId == roomId }

    fun temperatureSensorsInRoom(roomId: Int): List<TemperatureSensor> = temperatureSensors.filter { it.roomId == roomId }

    fun outputsOnLevel(levelId: Int): List<Output> = outputs.filter { it.levelId == levelId }

    fun heatingZonesOnLevel(levelId: Int): List<HeatingZone> = heatingZones.filter { it.levelId == levelId }

    fun shuttersOnLevel(levelId: Int): List<Shutter> = shutters.filter { it.levelId == levelId }

    fun temperatureSensorsOnLevel(levelId: Int): List<TemperatureSensor> = temperatureSensors.filter { it.levelId == levelId }

    fun outputsOfModule(module: Int): List<Output> = outputs.filter { it.module == module }

    fun heatingZonesOfModule(module: Int): List<HeatingZone> = heatingZones.filter { it.module == module }

    fun shuttersOfModule(module: Int): List<Shutter> = shutters.filter { it.module == module }

    fun temperatureSensorsOfModule(module: Int): List<TemperatureSensor> = temperatureSensors.filter { it.module == module }

    fun outputsOfKind(kind: OutputKind): List<Output> = outputs.filter { it.kind == kind }

    /** The output with this address (`id_device`, `output_relay`), or null. */
    fun output(module: Int, relay: Int): Output? = outputsByAddress[module to relay]

    /** The zone with this address (`id_device`, `output_nr`), or null. */
    fun heatingZone(module: Int, outputNr: Int): HeatingZone? = zonesByAddress[module to outputNr]

    /** The shutter with this address (`id_device`, `output_nr`), or null. */
    fun shutter(module: Int, outputNr: Int): Shutter? = shuttersByAddress[module to outputNr]

    /** The temperature sensor with this address (`id_device`, `output_nr`), or null. */
    fun temperatureSensor(module: Int, number: Int): TemperatureSensor? = sensorsByAddress[module to number]

    /**
     * The temperature inputs (`input_type` 8) that measure [zone]: same module, same room and level. The input number
     * never equals the zone number by design, so nothing is guessed from numbers: a zone without a room (null or
     * [NO_ROOM_ID]), or a database without `inputs`, gives an empty list. Several inputs in one room are all returned
     * (database order); the caller decides what to do with an ambiguous result.
     */
    fun temperatureInputsFor(zone: HeatingZone): List<AnalogInput> {
        val room = zone.roomId
        if (room == null || room == NO_ROOM_ID) return emptyList()
        return analogInputs.filter {
            it.isTemperature && it.module == zone.module && it.roomId == room && it.levelId == zone.levelId
        }
    }
}
