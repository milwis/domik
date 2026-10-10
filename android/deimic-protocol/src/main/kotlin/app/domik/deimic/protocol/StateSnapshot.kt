package app.domik.deimic.protocol

/** Heating zone flag from the collective state (`heaters[output_nr]`, formats v3 / new). Equality by raw byte. */
sealed class HeaterFlag(val raw: UByte) {
    object Idle : HeaterFlag(0u)
    object Heating : HeaterFlag(1u)
    object NoZone : HeaterFlag(255u)
    class Unknown(raw: UByte) : HeaterFlag(raw)

    final override fun equals(other: Any?): Boolean = other is HeaterFlag && other.raw == raw

    final override fun hashCode(): Int = raw.hashCode()

    final override fun toString(): String = when (raw.toInt()) {
        0 -> "idle"
        1 -> "heating"
        255 -> "noZone"
        else -> "unknown($raw)"
    }

    companion object {
        fun of(raw: UByte): HeaterFlag = when (raw.toInt()) {
            0 -> Idle
            1 -> Heating
            255 -> NoZone
            else -> Unknown(raw)
        }
    }
}

/** State of one module from a collective state record. */
data class ModuleState(
    /** Module address (`id_device`): the record's i32 address field reinterpreted as unsigned. */
    val module: UInt,
    val format: StateFormat,
    /** Output relays, index = `output_relay`; true when the byte is != 0. */
    val outputs: List<Boolean>,
    /** Heating zones, index = `output_nr` (v3 / new only). */
    val heaters: List<HeaterFlag>?,
    /** Sensor temperatures in °C (v3 / new / v2: 128, v1: 32; null for old). */
    val temperatures: List<Float>?,
    val dimmers: List<Int>,
    val inputs: List<UByte>,
    val shutterStates: List<UByte>,
    /** Shutter positions (v3 only). */
    val shutterPositions: List<Short>?,
) {
    val onOutputs: List<Int> get() = outputs.indices.filter { outputs[it] }
}

/** Problems found while parsing a collective state; parsing never crashes. */
sealed interface StateParseIssue {
    /** 0x228 payload did not start with the 0x02 version byte. */
    data class MissingV3Prefix(val found: UByte) : StateParseIssue

    /** Data length is not a multiple of any known record size. */
    data class UnknownLength(val length: Int) : StateParseIssue
}

/** Collective state (0x202 / 0x21E / 0x228): N module records of one format. */
data class StateSnapshot(
    val command: CommandCode,
    /** Detected format; null when the payload is empty or could not be parsed. */
    val format: StateFormat?,
    val modules: List<ModuleState>,
    val issues: List<StateParseIssue> = emptyList(),
    /** True when the controller sent no state data (e.g. a v3 request on firmware without v3 support). */
    val isEmpty: Boolean = false,
) : ControllerMessage {
    fun module(address: UInt): ModuleState? = modules.firstOrNull { it.module == address }

    companion object : PayloadParser<StateSnapshot> {
        private const val V3_PREFIX = 0x02

        override val commands: Set<CommandCode> =
            setOf(CommandCode.GET_STATE, CommandCode.STATE_NEW, CommandCode.STATE_V3)

        override fun parse(frame: Frame): StateSnapshot {
            requireCommand(frame)
            var data = frame.payload
            if (frame.command == CommandCode.STATE_V3 && !data.isEmpty()) {
                val first = data[0]
                if (first.toInt() != V3_PREFIX) {
                    return StateSnapshot(frame.command, null, emptyList(), listOf(StateParseIssue.MissingV3Prefix(first)))
                }
                data = data.copyOfRange(1, data.size)
            }
            if (data.isEmpty()) return StateSnapshot(frame.command, null, emptyList(), isEmpty = true)
            val format = StateFormat.entries.firstOrNull { data.size % it.recordSize == 0 }
                ?: return StateSnapshot(frame.command, null, emptyList(), listOf(StateParseIssue.UnknownLength(data.size)))
            val modules = (0 until data.size step format.recordSize).map { start ->
                parseRecord(ByteReader(data.copyOfRange(start, start + format.recordSize)), format)
            }
            return StateSnapshot(frame.command, format, modules)
        }

        internal fun parseRecord(record: ByteReader, format: StateFormat): ModuleState {
            fun <T> values(field: StateField, read: (Int) -> T): List<T>? {
                val extent = format.extent(field) ?: return null
                val size = extent.elementType.size
                return List(extent.elementCount) { read(extent.offset + it * size) }
            }
            val address = values(StateField.ADDRESS, record::i32)?.first() ?: 0
            return ModuleState(
                module = address.toUInt(),
                format = format,
                outputs = values(StateField.OUTPUTS) { record.u8(it).toInt() != 0 } ?: emptyList(),
                heaters = values(StateField.HEATERS) { HeaterFlag.of(record.u8(it)) },
                temperatures = values(StateField.TEMPERATURES) { record.f32(it) },
                dimmers = values(StateField.DIMMERS) { record.i32(it) } ?: emptyList(),
                inputs = values(StateField.INPUTS, record::u8) ?: emptyList(),
                shutterStates = values(StateField.SHUTTER_STATES, record::u8) ?: emptyList(),
                shutterPositions = values(StateField.SHUTTER_POSITIONS) { record.i16(it) },
            )
        }
    }
}
