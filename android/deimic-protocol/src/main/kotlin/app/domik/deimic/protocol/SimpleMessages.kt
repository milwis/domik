package app.domik.deimic.protocol

/**
 * Temperature sensor report (0x72). Payload >= 9 B: `[0..3] f32 °C`, `[4] id_level`, `[5] id_room`,
 * `[8] status` (must be 0), optional `[9..10] u16 sensor number` when >= 11 B.
 */
data class TemperatureReport(
    val module: UInt,
    val temperature: Float,
    val levelId: UByte,
    val roomId: UByte,
    val sensorNumber: UShort? = null,
) : ControllerMessage {
    companion object : PayloadParser<TemperatureReport> {
        const val MINIMUM_LENGTH: Int = 9

        override val commands: Set<CommandCode> = setOf(CommandCode.TEMPERATURE_REPORT)

        override fun parse(frame: Frame): TemperatureReport {
            val reader = payloadReader(frame, MINIMUM_LENGTH)
            val status = reader.u8(8)
            if (status.toInt() != 0) {
                throw ProtocolException.InvalidPayload(frame.command, "sensor status $status != 0")
            }
            return TemperatureReport(
                module = frame.address,
                temperature = reader.f32(0),
                levelId = reader.u8(4),
                roomId = reader.u8(5),
                sensorNumber = if (reader.count >= 11) reader.u16(9) else null,
            )
        }
    }
}

/** Analog input value change (0x130, sent alongside temperature reports): `[0] input`, `[1..4] f32 value`. */
data class AnalogValueChanged(val module: UInt, val input: UByte, val value: Float) : ControllerMessage {
    companion object : PayloadParser<AnalogValueChanged> {
        const val MINIMUM_LENGTH: Int = 5

        override val commands: Set<CommandCode> = setOf(CommandCode.ANALOG_VALUE_CHANGED)

        override fun parse(frame: Frame): AnalogValueChanged {
            val reader = payloadReader(frame, MINIMUM_LENGTH)
            return AnalogValueChanged(module = frame.address, input = reader.u8(0), value = reader.f32(1))
        }
    }
}

/** Output change event: 0x109 (went ON) / 0x108 (went OFF), payload `[0] output_relay`. */
data class OutputEvent(val module: UInt, val relay: UByte, val isOn: Boolean) : ControllerMessage {
    companion object : PayloadParser<OutputEvent> {
        override val commands: Set<CommandCode> = setOf(CommandCode.OUTPUT_ROSE, CommandCode.OUTPUT_FELL)

        override fun parse(frame: Frame): OutputEvent {
            val reader = payloadReader(frame, 1)
            return OutputEvent(
                module = frame.address,
                relay = reader.u8(0),
                isOn = frame.command == CommandCode.OUTPUT_ROSE,
            )
        }
    }
}

/**
 * Acknowledgement 0x1E0: echo of the received request header and payload
 * `[u32 addr][u32 cmd][u32 grp][u32 flg][u32 plen][payload]`. A payload shorter than `plen` is tolerated
 * ([payload] then holds what is present).
 */
data class Ack(
    val address: UInt,
    val command: CommandCode,
    val group: UInt,
    val flags: UInt,
    val payloadLength: UInt,
    val payload: Bytes,
) : ControllerMessage {
    /** True when this acknowledgement echoes [request] (address, command, group, flags and payload). */
    fun acknowledges(request: Frame): Boolean =
        address == request.address && command == request.command && group == request.group &&
            flags == request.flags && payloadLength.toLong() == request.payload.size.toLong() &&
            payload == request.payload

    companion object : PayloadParser<Ack> {
        const val MINIMUM_LENGTH: Int = 20

        override val commands: Set<CommandCode> = setOf(CommandCode.ACK)

        override fun parse(frame: Frame): Ack {
            val reader = payloadReader(frame, MINIMUM_LENGTH)
            val length = reader.u32(16)
            val available = minOf(length.toLong(), (reader.count - MINIMUM_LENGTH).toLong()).toInt()
            return Ack(
                address = reader.u32(0),
                command = CommandCode(reader.u32(4)),
                group = reader.u32(8),
                flags = reader.u32(12),
                payloadLength = length,
                payload = reader.bytes(MINIMUM_LENGTH, available),
            )
        }
    }
}

/** Firmware version text (0x1D6): ASCII up to the first NUL, trimmed (e.g. "Dec 13 2017"). */
data class FirmwareVersion(val text: String) : ControllerMessage {
    companion object : PayloadParser<FirmwareVersion> {
        override val commands: Set<CommandCode> = setOf(CommandCode.FIRMWARE_VERSION)

        override fun parse(frame: Frame): FirmwareVersion {
            requireCommand(frame)
            return FirmwareVersion(asciiString(frame.payload).trim())
        }
    }
}

/** Configuration database checksum (0x1C8): CRC-16/ARC of the database file, `[0..1] u16 LE`. */
data class ConfigChecksum(val value: UShort) : ControllerMessage {
    /** Lowercase hex without padding, as printed by the reference client (e.g. "4d62"). */
    val hexString: String get() = value.toString(16)

    companion object : PayloadParser<ConfigChecksum> {
        override val commands: Set<CommandCode> = setOf(CommandCode.CONFIG_CHECKSUM)

        override fun parse(frame: Frame): ConfigChecksum = ConfigChecksum(payloadReader(frame, 2).u16(0))
    }
}

/** File download response (0x212): `[0..63]` file name (ASCII, NUL-padded), `[64…]` file content. */
data class FileDownloadResponse(val name: String, val content: Bytes) : ControllerMessage {
    companion object : PayloadParser<FileDownloadResponse> {
        const val NAME_LENGTH: Int = 64

        override val commands: Set<CommandCode> = setOf(CommandCode.FILE_DOWNLOAD)

        override fun parse(frame: Frame): FileDownloadResponse {
            val reader = payloadReader(frame, NAME_LENGTH)
            return FileDownloadResponse(
                name = asciiString(reader.bytes(0, NAME_LENGTH)),
                content = reader.bytes(NAME_LENGTH, reader.count - NAME_LENGTH),
            )
        }
    }
}
