package app.domik.deimic.protocol

/**
 * Calibration record of one analog input (18 B, little-endian), as read with 0x131 (N records, input 0 first)
 * and written with 0x132 (`[u8 input_nr]` + record). Layout:
 * `[0] active point 1`, `[1] active point 2`, `[2..5] f32 measured 1`, `[6..9] f32 measured 2`,
 * `[10..13] f32 true 1`, `[14..17] f32 true 2`. Temperatures are stored in tenths of °C (225.0 = 22.5 °C).
 * One active point = constant offset `true1 - measured1`; two points = linear map through both points.
 * The active bytes are kept raw so that a decode/encode round trip is byte-exact.
 * Layout confirmed against a live controller (read and write round trip).
 */
data class InputCalibration(
    val active1: UByte,
    val active2: UByte,
    val measured1Tenths: Float,
    val measured2Tenths: Float,
    val true1Tenths: Float,
    val true2Tenths: Float,
) {
    val point1Active: Boolean get() = active1 != 0.toUByte()
    val point2Active: Boolean get() = active2 != 0.toUByte()

    /**
     * The value the controller reports for a raw sensor reading [rawTenths] (tenths of °C): no active point = the
     * raw value, one point = shifted by `true - measured`, two points = linear through both. Two points with equal
     * measured values (a vertical line) are treated as one point. 🟡 The firmware's own formula was not
     * checked beyond the one-point case (verified on a live controller).
     */
    fun correctTenths(rawTenths: Float): Float = when {
        point1Active && point2Active && measured1Tenths != measured2Tenths ->
            true1Tenths + (rawTenths - measured1Tenths) * (true2Tenths - true1Tenths) / (measured2Tenths - measured1Tenths)
        point1Active -> rawTenths + (true1Tenths - measured1Tenths)
        point2Active -> rawTenths + (true2Tenths - measured2Tenths)
        else -> rawTenths
    }

    /** [correctTenths] for °C. */
    fun correctCelsius(rawCelsius: Float): Float =
        if (!point1Active && !point2Active) rawCelsius else correctTenths(rawCelsius * 10f) / 10f

    fun encode(): Bytes = ByteWriter().apply {
        writeU8(active1)
        writeU8(active2)
        writeF32(measured1Tenths)
        writeF32(measured2Tenths)
        writeF32(true1Tenths)
        writeF32(true2Tenths)
    }.toBytes()

    companion object {
        const val SIZE = 18

        /** Analog inputs 0..11 exist per module (the controller refuses 0x132 for higher numbers). */
        const val MAX_INPUT = 11

        /** No active point: the controller reports raw values. */
        val INACTIVE: InputCalibration = InputCalibration(0u, 0u, 0f, 0f, 0f, 0f)

        /** One active point: a constant offset, [trueCelsius] - [measuredCelsius] (stored in tenths of °C). */
        fun onePoint(measuredCelsius: Float, trueCelsius: Float): InputCalibration =
            InputCalibration(1u, 0u, measuredCelsius * 10f, 0f, trueCelsius * 10f, 0f)

        /** Decodes the first 18 bytes of [bytes]; throws [ProtocolException.Truncated] when there are fewer. */
        fun decode(bytes: Bytes): InputCalibration = decode(ByteReader(bytes))

        fun decode(reader: ByteReader, at: Int = 0): InputCalibration = InputCalibration(
            active1 = reader.u8(at),
            active2 = reader.u8(at + 1),
            measured1Tenths = reader.f32(at + 2),
            measured2Tenths = reader.f32(at + 6),
            true1Tenths = reader.f32(at + 10),
            true2Tenths = reader.f32(at + 14),
        )
    }
}
