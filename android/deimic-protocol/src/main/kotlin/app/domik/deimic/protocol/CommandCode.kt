package app.domik.deimic.protocol

/** Command code (frame bytes 16..19, u32 LE). Any value is representable; known codes have constants. */
@JvmInline
value class CommandCode(val raw: UInt) {
    /** Hex form with at least three digits, e.g. `0x06B`, `0x1CE`. */
    override fun toString(): String = "0x" + raw.toString(16).uppercase().padStart(3, '0')

    companion object {
        /** Set the setpoint of the active heating profile. */
        val SET_SETPOINT = CommandCode(0x64u)
        val SET_PROFILE = CommandCode(0x66u)
        /** Heating zone state request / response. */
        val HEATER_STATE = CommandCode(0x6Bu)
        val TEMPERATURE_REPORT = CommandCode(0x72u)
        /**
         * Shutter: stop. Payload `[output_nr]` – the **shutter number**, not `output_relay` (verified on a real
         * controller).
         */
        val SHUTTER_STOP = CommandCode(0x82u)
        /** Event: a shutter changed its state, payload `[output_nr][direction]` (see [ShutterState]). */
        val SHUTTER_STATE = CommandCode(0x8Bu)
        /** Shutter: run up; payload `[output_nr]`. Sent while the shutter is moving it stops it instead. */
        val SHUTTER_UP = CommandCode(0x8Du)
        /** Shutter: run down; payload `[output_nr]`. Sent while the shutter is moving it stops it instead. */
        val SHUTTER_DOWN = CommandCode(0x8Eu)
        val ANALOG_VALUE_CHANGED = CommandCode(0x130u)
        val OUTPUT_OFF = CommandCode(0x103u)
        val OUTPUT_ON = CommandCode(0x104u)
        /** Event: an output went OFF. */
        val OUTPUT_FELL = CommandCode(0x108u)
        /** Event: an output went ON. */
        val OUTPUT_ROSE = CommandCode(0x109u)
        val CONFIG_CHECKSUM = CommandCode(0x1C8u)
        val PING = CommandCode(0x1CEu)
        val FIRMWARE_VERSION = CommandCode(0x1D6u)
        /** The controller's acknowledgement (echo) of every received command. */
        val ACK = CommandCode(0x1E0u)
        /** Collective state request; also the response code for the v2 format. */
        val GET_STATE = CommandCode(0x202u)
        val PERIODIC_STATUS = CommandCode(0x20Eu)
        val FILE_DOWNLOAD = CommandCode(0x212u)
        val STATE_NEW = CommandCode(0x21Eu)
        val SYSTEM_INFO = CommandCode(0x222u)
        val STATE_V3 = CommandCode(0x228u)
        val MODBUS_STATE = CommandCode(0x254u)
        val KEEP_ALIVE = CommandCode(0xFE1u)
        val MODULE_PING = CommandCode(0xFF3u)
    }
}

/** Well-known frame addresses (frame bytes 12..15). Module addresses are `outputs.id_device` values. */
object FrameAddress {
    /** The controller itself (ping, version, CRC, file download, collective state). */
    const val CONTROLLER: UInt = 0u

    /** Broadcast (temperature readout request; the controller answers ping from this address). */
    const val BROADCAST: UInt = 0x1FFu
}
