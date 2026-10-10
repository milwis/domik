package app.domik.deimic.protocol

/** A frame received from the controller, routed to its payload parser by [decode]. */
sealed interface ControllerMessage {
    /** Ping response (0x1CE); the controller answers from the broadcast address. */
    data class Pong(val module: UInt) : ControllerMessage

    /** Command without a parser (e.g. 0x20E, 0x222, 0xFE1) – not an error. */
    data class Unknown(val frame: Frame) : ControllerMessage

    /** Known command whose payload could not be parsed, or a frame whose declared payload length exceeds its area. */
    data class Malformed(val frame: Frame, val error: ProtocolException) : ControllerMessage

    companion object {
        fun decode(frame: Frame): ControllerMessage = try {
            when (frame.command) {
                CommandCode.HEATER_STATE -> HeaterState.parse(frame)
                CommandCode.TEMPERATURE_REPORT -> TemperatureReport.parse(frame)
                CommandCode.ANALOG_VALUE_CHANGED -> AnalogValueChanged.parse(frame)
                CommandCode.OUTPUT_ROSE, CommandCode.OUTPUT_FELL -> OutputEvent.parse(frame)
                CommandCode.SHUTTER_STATE -> ShutterState.parse(frame)
                CommandCode.ACK -> Ack.parse(frame)
                CommandCode.FIRMWARE_VERSION -> FirmwareVersion.parse(frame)
                CommandCode.CONFIG_CHECKSUM -> ConfigChecksum.parse(frame)
                CommandCode.FILE_DOWNLOAD -> FileDownloadResponse.parse(frame)
                CommandCode.GET_STATE, CommandCode.STATE_NEW, CommandCode.STATE_V3 -> StateSnapshot.parse(frame)
                CommandCode.PING -> Pong(frame.address)
                else -> Unknown(frame)
            }
        } catch (error: ProtocolException) {
            Malformed(frame, error)
        }
    }
}
