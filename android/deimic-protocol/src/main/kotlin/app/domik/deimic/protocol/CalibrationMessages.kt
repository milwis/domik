package app.domik.deimic.protocol

/**
 * Answer to 0x131: the calibration records of the module's analog inputs, `N x 18 B`, input 0 first (no input
 * number in the payload; the index is the input). A real controller sends 12 records (216 B); inputs without a
 * sensor hold garbage (NaN / 0xFF), which is kept as is so that nothing is lost on a round trip.
 */
data class InputCalibrationTable(val module: UInt, val records: List<InputCalibration>) : ControllerMessage {
    /** The payload this table was parsed from (or would be sent as). */
    fun encode(): Bytes = records.fold(Bytes.EMPTY) { acc, record -> acc + record.encode() }

    companion object : PayloadParser<InputCalibrationTable> {
        override val commands: Set<CommandCode> = setOf(CommandCode.INPUT_CALIBRATION_GET)

        override fun parse(frame: Frame): InputCalibrationTable {
            val reader = payloadReader(frame, InputCalibration.SIZE)
            if (reader.count % InputCalibration.SIZE != 0) {
                throw ProtocolException.InvalidPayload(
                    frame.command,
                    "length ${reader.count} is not a multiple of ${InputCalibration.SIZE}",
                )
            }
            val records = List(reader.count / InputCalibration.SIZE) { InputCalibration.decode(reader, it * InputCalibration.SIZE) }
            return InputCalibrationTable(frame.address, records)
        }
    }
}

/**
 * Calibration of one input as sent with 0x132 `[u8 input_nr][18 B record]`: the request, and the controller's
 * confirmation of a write (the same payload echoed back).
 */
data class InputCalibrationWritten(val module: UInt, val input: UByte, val record: InputCalibration) : ControllerMessage {
    companion object : PayloadParser<InputCalibrationWritten> {
        const val LENGTH: Int = 1 + InputCalibration.SIZE

        override val commands: Set<CommandCode> = setOf(CommandCode.INPUT_CALIBRATION_SET)

        override fun parse(frame: Frame): InputCalibrationWritten {
            val reader = payloadReader(frame, LENGTH)
            return InputCalibrationWritten(frame.address, reader.u8(0), InputCalibration.decode(reader, 1))
        }
    }
}
