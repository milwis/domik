package app.domik.deimic.protocol

/** Heating zone profile (0 off, 1 eco, 2 comfort, 3 maximum). Equality compares the raw byte only. */
sealed class HeatingProfile(val raw: UByte) {
    object Off : HeatingProfile(0u)
    object Eco : HeatingProfile(1u)
    object Comfort : HeatingProfile(2u)
    object Maximum : HeatingProfile(3u)
    class Unknown(raw: UByte) : HeatingProfile(raw)

    final override fun equals(other: Any?): Boolean = other is HeatingProfile && other.raw == raw

    final override fun hashCode(): Int = raw.hashCode()

    final override fun toString(): String = when (raw.toInt()) {
        0 -> "off"
        1 -> "eco"
        2 -> "comfort"
        3 -> "maximum"
        else -> "unknown($raw)"
    }

    companion object {
        fun of(raw: UByte): HeatingProfile = when (raw.toInt()) {
            0 -> Off
            1 -> Eco
            2 -> Comfort
            3 -> Maximum
            else -> Unknown(raw)
        }
    }
}

/**
 * Heating zone state – response to 0x6B (also sent spontaneously after 0x64). Payload >= 17 B:
 * `[0] output_nr`, `[1] heating now`, `[2..5] f32 current °C`, `[10] profile`, `[12..15] f32 setpoint °C`.
 */
data class HeaterState(
    val module: UInt,
    val outputNr: UByte,
    val isHeating: Boolean,
    val currentTemperature: Float,
    val profile: HeatingProfile,
    /** Setpoint of the active profile. */
    val setpoint: Float,
) : ControllerMessage {
    companion object : PayloadParser<HeaterState> {
        const val MINIMUM_LENGTH: Int = 17

        override val commands: Set<CommandCode> = setOf(CommandCode.HEATER_STATE)

        override fun parse(frame: Frame): HeaterState {
            val reader = payloadReader(frame, MINIMUM_LENGTH)
            return HeaterState(
                module = frame.address,
                outputNr = reader.u8(0),
                isHeating = reader.u8(1).toInt() != 0,
                currentTemperature = reader.f32(2),
                profile = HeatingProfile.of(reader.u8(10)),
                setpoint = reader.f32(12),
            )
        }
    }
}
