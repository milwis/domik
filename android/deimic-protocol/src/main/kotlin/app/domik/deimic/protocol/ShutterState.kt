package app.domik.deimic.protocol

/**
 * What a shutter is doing, from the direction byte of the shutter event (0x8B) and of the collective state
 * (`shutterStates`). [OTHER] stands for every value this version does not know (for example 6, "waiting", whose
 * behaviour is unconfirmed on a real controller); the raw byte is kept in [ShutterState.rawDirection].
 */
enum class ShutterDirection(val raw: Int?) {
    /** 0 – no information (also the value the collective state reports for every shutter on firmware 2017). */
    UNKNOWN(0),

    /** 1 – standing (stopped on request or never moved). */
    STOPPED(1),

    /** 2 – moving up. */
    MOVING_UP(2),

    /** 3 – moving down. */
    MOVING_DOWN(3),

    /** 4 – reached the top end (the controller reports it by itself when the travel time has elapsed). */
    AT_TOP(4),

    /** 5 – reached the bottom end (not yet observed on a real controller). */
    AT_BOTTOM(5),

    /** Any other value. */
    OTHER(null);

    /** Moving up or down. */
    val isMoving: Boolean get() = this == MOVING_UP || this == MOVING_DOWN

    companion object {
        fun of(raw: UByte): ShutterDirection = entries.firstOrNull { it.raw == raw.toInt() } ?: OTHER
    }
}

/**
 * Shutter state event (0x8B): payload `[0] output_nr`, `[1] direction` and – only on some firmware, when the
 * payload has at least 4 bytes – `[2..3] u16 LE position × 10` (percent of opening). A controller with
 * firmware "Dec 13 2017" sends 2 bytes, without a position.
 *
 * @property rawDirection the unmodified direction byte
 * @property positionPercent 0..100 (100 = open at the top), or null when the controller does not report it
 *   (or reports a value outside 0..100)
 */
data class ShutterState(
    val module: UInt,
    val outputNr: UByte,
    val direction: ShutterDirection,
    val rawDirection: UByte,
    val positionPercent: Float? = null,
) : ControllerMessage {
    companion object : PayloadParser<ShutterState> {
        const val MINIMUM_LENGTH: Int = 2
        private const val POSITION_LENGTH = 4
        private const val MAX_POSITION_TENTHS = 1000

        override val commands: Set<CommandCode> = setOf(CommandCode.SHUTTER_STATE)

        override fun parse(frame: Frame): ShutterState {
            val reader = payloadReader(frame, MINIMUM_LENGTH)
            val rawDirection = reader.u8(1)
            val tenths = if (reader.count >= POSITION_LENGTH) reader.u16(2).toInt() else null
            return ShutterState(
                module = frame.address,
                outputNr = reader.u8(0),
                direction = ShutterDirection.of(rawDirection),
                rawDirection = rawDirection,
                positionPercent = tenths?.takeIf { it <= MAX_POSITION_TENTHS }?.let { it / 10f },
            )
        }
    }
}
