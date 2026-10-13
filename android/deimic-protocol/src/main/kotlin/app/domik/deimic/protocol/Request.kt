package app.domik.deimic.protocol

/** Variant of the collective state request (0x202 payload). */
enum class StateRequestVariant(private val payloadByte: Int?) {
    /** `[01]` -> 0x21E with "new" records (2164 B); supported by firmware "Dec 13 2017". */
    NEW(0x01),

    /** `[]` -> 0x202 with v2 records (1556 B). */
    V2(null),

    /** `[02]` -> 0x228 with v3 records (2292 B); empty response on older firmware. */
    V3(0x02);

    val payload: Bytes get() = payloadByte?.let { Bytes.of(it) } ?: Bytes.EMPTY
}

/** Pure request frame builders. Nothing is sent and value ranges are not validated here. */
object Request {
    const val FILE_PATH_LENGTH: Int = 64

    fun ping(serial: SerialNumber): Frame = Frame(serial, FrameAddress.CONTROLLER, CommandCode.PING)

    fun firmwareVersion(serial: SerialNumber): Frame =
        Frame(serial, FrameAddress.CONTROLLER, CommandCode.FIRMWARE_VERSION)

    fun configChecksum(serial: SerialNumber): Frame =
        Frame(serial, FrameAddress.CONTROLLER, CommandCode.CONFIG_CHECKSUM)

    /** 0x212 with a 64-byte ASCII path padded with NULs; the path must be shorter than 64 bytes. */
    fun fileDownload(serial: SerialNumber, path: String): Frame {
        if (!path.all { it.code in 1..0x7F }) {
            throw ProtocolException.InvalidFilePath("path must be ASCII without NUL")
        }
        if (path.length >= FILE_PATH_LENGTH) {
            throw ProtocolException.InvalidFilePath("path must be shorter than $FILE_PATH_LENGTH bytes")
        }
        val writer = ByteWriter()
        writer.writeBytes(Bytes.ascii(path))
        writer.writeZeros(FILE_PATH_LENGTH - path.length)
        return Frame(serial, FrameAddress.CONTROLLER, CommandCode.FILE_DOWNLOAD, payload = writer.toBytes())
    }

    fun getState(serial: SerialNumber, variant: StateRequestVariant = StateRequestVariant.NEW): Frame =
        Frame(serial, FrameAddress.CONTROLLER, CommandCode.GET_STATE, payload = variant.payload)

    /** 0x6B `[output_nr]` – the controller answers with a 17-byte [HeaterState]. */
    fun heaterState(serial: SerialNumber, module: UInt, outputNr: UByte): Frame =
        Frame(serial, module, CommandCode.HEATER_STATE, payload = Bytes.of(outputNr.toInt()))

    /** 0x72 to broadcast, empty payload – each sensor answers with a [TemperatureReport]. */
    fun temperatureReadout(serial: SerialNumber): Frame =
        Frame(serial, FrameAddress.BROADCAST, CommandCode.TEMPERATURE_REPORT)

    /** 0x104 (on) / 0x103 (off) `[output_relay]`. */
    fun setOutput(serial: SerialNumber, module: UInt, relay: UByte, on: Boolean): Frame =
        Frame(
            serial, module,
            if (on) CommandCode.OUTPUT_ON else CommandCode.OUTPUT_OFF,
            payload = Bytes.of(relay.toInt()),
        )

    /** 0x64 `[output_nr][f32 LE °C]` – sets the setpoint of the active profile. */
    fun setSetpoint(serial: SerialNumber, module: UInt, outputNr: UByte, celsius: Float): Frame {
        val writer = ByteWriter()
        writer.writeU8(outputNr)
        writer.writeF32(celsius)
        return Frame(serial, module, CommandCode.SET_SETPOINT, payload = writer.toBytes())
    }

    /** 0x66 `[output_nr][profile]`. */
    fun setProfile(serial: SerialNumber, module: UInt, outputNr: UByte, profile: HeatingProfile): Frame =
        Frame(serial, module, CommandCode.SET_PROFILE, payload = Bytes.of(outputNr.toInt(), profile.raw.toInt()))

    /**
     * 0x8D `[output_nr]` – shutter up. `output_nr` is the shutter's number in its module (not `output_relay`).
     * Sent while the shutter is moving (in either direction) the controller stops it instead of reversing.
     */
    fun shutterUp(serial: SerialNumber, module: UInt, outputNr: UByte): Frame =
        Frame(serial, module, CommandCode.SHUTTER_UP, payload = Bytes.of(outputNr.toInt()))

    /** 0x8E `[output_nr]` – shutter down; same addressing and toggle behaviour as [shutterUp]. */
    fun shutterDown(serial: SerialNumber, module: UInt, outputNr: UByte): Frame =
        Frame(serial, module, CommandCode.SHUTTER_DOWN, payload = Bytes.of(outputNr.toInt()))

    /**
     * 0x82 `[output_nr]` – stop the shutter. The byte is the shutter **number** (`output_nr`), not `output_relay`
     * (the two differ for shutters other than the first; a wrong value stops another shutter or none).
     */
    fun shutterStop(serial: SerialNumber, module: UInt, outputNr: UByte): Frame =
        Frame(serial, module, CommandCode.SHUTTER_STOP, payload = Bytes.of(outputNr.toInt()))

    /** 0x131, empty payload, addressed to [module] – the controller answers with [InputCalibrationTable]. */
    fun inputCalibrations(serial: SerialNumber, module: UInt): Frame =
        Frame(serial, module, CommandCode.INPUT_CALIBRATION_GET)

    /**
     * 0x132 `[input_nr][18 B record]` – writes the calibration of analog input [input] (0..[InputCalibration.MAX_INPUT]).
     * Takes effect at once; the controller confirms by echoing the payload ([InputCalibrationWritten]).
     */
    fun setInputCalibration(serial: SerialNumber, module: UInt, input: UByte, record: InputCalibration): Frame =
        Frame(serial, module, CommandCode.INPUT_CALIBRATION_SET, payload = Bytes.of(input.toInt()) + record.encode())
}
