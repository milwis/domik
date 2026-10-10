package app.domik.deimic.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.abs

class MessageParsingTest {
    private fun frame(command: CommandCode, payload: Bytes, address: UInt = 1u) =
        Frame(FAKE_SERIAL, address, command, payload = payload)

    @Test
    fun heaterStateExample() {
        val state = HeaterState.parse(
            frame(CommandCode.HEATER_STATE, hex("01 01 7a 14 ac 41 00 00 00 00 02 00 00 00 b8 41 00")),
        )
        assertEquals(1u, state.module)
        assertEquals(1.toUByte(), state.outputNr)
        assertTrue(state.isHeating)
        assertTrue(abs(state.currentTemperature - 21.51f) < 0.005f)
        assertEquals(HeatingProfile.Comfort, state.profile)
        assertEquals(23.0f, state.setpoint)
    }

    @Test
    fun heaterStateErrors() {
        val short = assertThrows<ProtocolException> { HeaterState.parse(frame(CommandCode.HEATER_STATE, Bytes.zeros(16))) }
        assertEquals(ProtocolException.PayloadTooShort(CommandCode.HEATER_STATE, 17, 16), short)
        val wrong = assertThrows<ProtocolException> {
            HeaterState.parse(frame(CommandCode.TEMPERATURE_REPORT, Bytes.zeros(17)))
        }
        assertEquals(ProtocolException.UnexpectedCommand(CommandCode.TEMPERATURE_REPORT), wrong)
    }

    @Test
    fun heatingProfileMapping() {
        assertEquals(HeatingProfile.Maximum, HeatingProfile.of(3u))
        assertEquals(HeatingProfile.Unknown(9u), HeatingProfile.of(9u))
        assertEquals(HeatingProfile.Comfort, HeatingProfile.Unknown(2u))
        assertEquals(HeatingProfile.Comfort.hashCode(), HeatingProfile.Unknown(2u).hashCode())
        assertNotEquals(HeatingProfile.Eco, HeatingProfile.Comfort)
        assertEquals(1.toUByte(), HeatingProfile.Eco.raw)
        assertEquals("comfort", HeatingProfile.Comfort.toString())
        assertEquals("unknown(9)", HeatingProfile.of(9u).toString())
    }

    @Test
    fun temperatureReport() {
        val report = TemperatureReport.parse(frame(CommandCode.TEMPERATURE_REPORT, hex("00 00 b4 41 02 04 00 ff 00")))
        assertEquals(TemperatureReport(module = 1u, temperature = 22.5f, levelId = 2u, roomId = 4u), report)
        assertNull(report.sensorNumber)
        val withSensor = TemperatureReport.parse(
            frame(CommandCode.TEMPERATURE_REPORT, hex("00 00 b4 41 02 04 00 ff 00 07 00")),
        )
        assertEquals(7.toUShort(), withSensor.sensorNumber)
        assertThrows<ProtocolException.InvalidPayload> {
            TemperatureReport.parse(frame(CommandCode.TEMPERATURE_REPORT, hex("00 00 b4 41 02 04 00 ff 01")))
        }
        assertThrows<ProtocolException.PayloadTooShort> {
            TemperatureReport.parse(frame(CommandCode.TEMPERATURE_REPORT, Bytes.zeros(8)))
        }
    }

    @Test
    fun analogAndOutputEvents() {
        assertEquals(
            AnalogValueChanged(module = 1u, input = 3u, value = 22.5f),
            AnalogValueChanged.parse(frame(CommandCode.ANALOG_VALUE_CHANGED, hex("03 00 00 b4 41"))),
        )
        assertEquals(
            OutputEvent(module = 1u, relay = 12u, isOn = true),
            OutputEvent.parse(frame(CommandCode.OUTPUT_ROSE, Bytes.of(12))),
        )
        assertEquals(
            OutputEvent(module = 1u, relay = 12u, isOn = false),
            OutputEvent.parse(frame(CommandCode.OUTPUT_FELL, Bytes.of(12))),
        )
        assertThrows<ProtocolException> { OutputEvent.parse(frame(CommandCode.OUTPUT_ROSE, Bytes.EMPTY)) }
    }

    @Test
    fun shutterStateTwoByteVariant() {
        // the live trace of firmware 2017: 0x8B, flg 5, [number, direction], no position
        val state = ShutterState.parse(frame(CommandCode.SHUTTER_STATE, hex("03 02")))
        assertEquals(
            ShutterState(module = 1u, outputNr = 3u, direction = ShutterDirection.MOVING_UP, rawDirection = 2u),
            state,
        )
        assertNull(state.positionPercent)
        assertTrue(state.direction.isMoving)
        val directions = listOf(
            0 to ShutterDirection.UNKNOWN,
            1 to ShutterDirection.STOPPED,
            2 to ShutterDirection.MOVING_UP,
            3 to ShutterDirection.MOVING_DOWN,
            4 to ShutterDirection.AT_TOP,
            5 to ShutterDirection.AT_BOTTOM,
        )
        for ((raw, direction) in directions) {
            assertEquals(direction, ShutterState.parse(frame(CommandCode.SHUTTER_STATE, Bytes.of(0, raw))).direction)
        }
        assertEquals(listOf(false, false, true, true, false, false), directions.map { it.second.isMoving })
    }

    @Test
    fun shutterStateUnknownDirectionKeepsRawByte() {
        val state = ShutterState.parse(frame(CommandCode.SHUTTER_STATE, Bytes.of(7, 6)))
        assertEquals(ShutterDirection.OTHER, state.direction)
        assertEquals(6.toUByte(), state.rawDirection)
        assertEquals(7.toUByte(), state.outputNr)
        assertFalse(state.direction.isMoving)
        assertEquals(ShutterDirection.OTHER, ShutterDirection.of(255u))
        assertNull(ShutterDirection.OTHER.raw)
    }

    @Test
    fun shutterStateFourByteVariantCarriesPosition() {
        // 625 = 0x0271 -> 62.5 %
        val state = ShutterState.parse(frame(CommandCode.SHUTTER_STATE, hex("02 03 71 02")))
        assertEquals(ShutterDirection.MOVING_DOWN, state.direction)
        assertEquals(62.5f, state.positionPercent)
        // 100 % and 0 % are valid; a value above 1000 tenths is ignored instead of failing the whole event
        assertEquals(100.0f, ShutterState.parse(frame(CommandCode.SHUTTER_STATE, hex("00 04 e8 03"))).positionPercent)
        assertEquals(0.0f, ShutterState.parse(frame(CommandCode.SHUTTER_STATE, hex("00 01 00 00"))).positionPercent)
        assertNull(ShutterState.parse(frame(CommandCode.SHUTTER_STATE, hex("00 01 e9 03"))).positionPercent)
        // 3 bytes: not enough for the position, the event still counts
        assertNull(ShutterState.parse(frame(CommandCode.SHUTTER_STATE, hex("00 01 71"))).positionPercent)
        assertEquals(ShutterDirection.STOPPED, ShutterState.parse(frame(CommandCode.SHUTTER_STATE, hex("00 01 71"))).direction)
    }

    @Test
    fun shutterStateErrors() {
        val short = assertThrows<ProtocolException> { ShutterState.parse(frame(CommandCode.SHUTTER_STATE, Bytes.of(3))) }
        assertEquals(ProtocolException.PayloadTooShort(CommandCode.SHUTTER_STATE, 2, 1), short)
        val wrong = assertThrows<ProtocolException> { ShutterState.parse(frame(CommandCode.OUTPUT_ROSE, Bytes.of(3, 2))) }
        assertEquals(ProtocolException.UnexpectedCommand(CommandCode.OUTPUT_ROSE), wrong)
    }

    @Test
    fun shutterRequestsAddressByShutterNumber() {
        // 0x8D / 0x8E / 0x82 carry the shutter NUMBER (output_nr), never the relay; addressed to the module
        val up = Request.shutterUp(FAKE_SERIAL, module = 1u, outputNr = 3u)
        assertEquals(CommandCode.SHUTTER_UP, up.command)
        assertEquals(1u, up.address)
        assertEquals(Bytes.of(3), up.payload)
        val down = Request.shutterDown(FAKE_SERIAL, module = 1u, outputNr = 0u)
        assertEquals(CommandCode.SHUTTER_DOWN, down.command)
        assertEquals(Bytes.of(0), down.payload)
        val stop = Request.shutterStop(FAKE_SERIAL, module = 2u, outputNr = 1u)
        assertEquals(CommandCode.SHUTTER_STOP, stop.command)
        assertEquals(2u, stop.address)
        assertEquals(Bytes.of(1), stop.payload)
        assertEquals(0x8Du, CommandCode.SHUTTER_UP.raw)
        assertEquals(0x8Eu, CommandCode.SHUTTER_DOWN.raw)
        assertEquals(0x82u, CommandCode.SHUTTER_STOP.raw)
        assertEquals(0x8Bu, CommandCode.SHUTTER_STATE.raw)
    }

    @Test
    fun shutterAckEchoesRequest() {
        // 0x1E0 of the live test: echo of the request, the shutter number in the last byte
        val request = Request.shutterUp(FAKE_SERIAL, module = 1u, outputNr = 3u)
        val ack = Ack.parse(
            frame(
                CommandCode.ACK,
                hex("01 00 00 00 8d 00 00 00 00 00 00 00 00 00 00 00 01 00 00 00 03"),
                address = 0x1FFu,
            ),
        )
        assertEquals(CommandCode.SHUTTER_UP, ack.command)
        assertTrue(ack.acknowledges(request))
        assertFalse(ack.acknowledges(Request.shutterDown(FAKE_SERIAL, module = 1u, outputNr = 3u)))
        assertFalse(ack.acknowledges(Request.shutterUp(FAKE_SERIAL, module = 1u, outputNr = 2u)))
    }

    @Test
    fun ackEchoesRequest() {
        val request = Request.setOutput(FAKE_SERIAL, module = 1u, relay = 12u, on = true)
        val writer = ByteWriter()
        for (value in listOf(request.address, request.command.raw, 0u, 0u, 1u)) writer.writeU32(value)
        writer.writeU8(12u)
        val ack = Ack.parse(frame(CommandCode.ACK, writer.toBytes(), address = 0x1FFu))
        assertEquals(CommandCode.OUTPUT_ON, ack.command)
        assertEquals(Bytes.of(12), ack.payload)
        assertTrue(ack.acknowledges(request))
        assertFalse(ack.acknowledges(Request.setOutput(FAKE_SERIAL, module = 1u, relay = 12u, on = false)))

        val truncated = ByteWriter()
        for (value in listOf(1u, 0x64u, 0u, 0u, 5u)) truncated.writeU32(value)
        truncated.writeU8(5u)
        val partial = Ack.parse(frame(CommandCode.ACK, truncated.toBytes()))
        assertEquals(5u, partial.payloadLength)
        assertEquals(Bytes.of(5), partial.payload)
        assertThrows<ProtocolException> { Ack.parse(frame(CommandCode.ACK, Bytes.zeros(19))) }
    }

    @Test
    fun firmwareChecksumFile() {
        val version = Bytes.ascii("Dec 13 2017") + Bytes.zeros(53)
        assertEquals("Dec 13 2017", FirmwareVersion.parse(frame(CommandCode.FIRMWARE_VERSION, version)).text)
        assertEquals("x", FirmwareVersion.parse(frame(CommandCode.FIRMWARE_VERSION, Bytes.ascii(" x \u0000garbage"))).text)
        assertEquals("", FirmwareVersion.parse(frame(CommandCode.FIRMWARE_VERSION, Bytes.EMPTY)).text)
        assertEquals("a�b", FirmwareVersion.parse(frame(CommandCode.FIRMWARE_VERSION, Bytes.of(0x61, 0xC3, 0x62))).text)

        val crc = ConfigChecksum.parse(frame(CommandCode.CONFIG_CHECKSUM, hex("62 4D")))
        assertEquals(0x4D62.toUShort(), crc.value)
        assertEquals("4d62", crc.hexString)
        assertThrows<ProtocolException> { ConfigChecksum.parse(frame(CommandCode.CONFIG_CHECKSUM, Bytes.of(1))) }

        val name = Bytes.ascii("/logs/database-last.db3")
        val file = name + Bytes.zeros(64 - name.size) + Bytes.of(1, 2, 3)
        val response = FileDownloadResponse.parse(frame(CommandCode.FILE_DOWNLOAD, file))
        assertEquals("/logs/database-last.db3", response.name)
        assertEquals(Bytes.of(1, 2, 3), response.content)
        assertThrows<ProtocolException> { FileDownloadResponse.parse(frame(CommandCode.FILE_DOWNLOAD, Bytes.zeros(63))) }
    }

    @Test
    fun controllerMessageRouting() {
        assertEquals(
            OutputEvent(module = 1u, relay = 2u, isOn = true),
            ControllerMessage.decode(frame(CommandCode.OUTPUT_ROSE, Bytes.of(2))),
        )
        assertEquals(
            ControllerMessage.Pong(0x1FFu),
            ControllerMessage.decode(frame(CommandCode.PING, Bytes.EMPTY, address = 0x1FFu)),
        )
        assertEquals(
            ShutterState(module = 1u, outputNr = 3u, direction = ShutterDirection.AT_TOP, rawDirection = 4u),
            ControllerMessage.decode(frame(CommandCode.SHUTTER_STATE, Bytes.of(3, 4))),
        )
        assertEquals(
            ControllerMessage.Malformed(
                frame(CommandCode.SHUTTER_STATE, Bytes.of(3)),
                ProtocolException.PayloadTooShort(CommandCode.SHUTTER_STATE, 2, 1),
            ),
            ControllerMessage.decode(frame(CommandCode.SHUTTER_STATE, Bytes.of(3))),
        )
        val unknown = frame(CommandCode.PERIODIC_STATUS, Bytes.of(1, 0, 0, 0))
        assertEquals(ControllerMessage.Unknown(unknown), ControllerMessage.decode(unknown))
        val odd = frame(CommandCode(0xDEADu), Bytes.EMPTY)
        assertEquals(ControllerMessage.Unknown(odd), ControllerMessage.decode(odd))
        val bad = frame(CommandCode.HEATER_STATE, Bytes.of(1, 2))
        assertEquals(
            ControllerMessage.Malformed(bad, ProtocolException.PayloadTooShort(CommandCode.HEATER_STATE, 17, 2)),
            ControllerMessage.decode(bad),
        )
    }

    @Test
    fun byteReaderBounds() {
        val reader = ByteReader.of(0x01, 0x02, 0x03, 0x04, 0x05)
        assertEquals(0x01020304u, reader.u32(0, Endianness.BIG))
        assertEquals(0x05040302u, reader.u32(1))
        assertEquals(0x0405.toShort(), reader.i16(3, Endianness.BIG))
        assertEquals(ProtocolException.Truncated(2, 4, 5), assertThrows<ProtocolException> { reader.u32(2) })
        assertThrows<ProtocolException> { reader.u8(-1) }
        assertThrows<ProtocolException> { reader.bytes(4, Int.MAX_VALUE) }
        assertEquals(1.toUByte(), reader.readU8())
        assertEquals(0x0203.toUShort(), reader.readU16(Endianness.BIG))
        assertEquals(2, reader.remaining)
        assertThrows<ProtocolException> { reader.readU32() }
        assertEquals(3, reader.offset)
        assertEquals((-1).toShort(), ByteReader.of(0xFF, 0xFF).i16(0))
        assertEquals(0xFFFFFFFFu, ByteReader.of(0xFF, 0xFF, 0xFF, 0xFF).u32(0))
        assertEquals(21.5f, ByteReader.of(0x00, 0x00, 0xAC, 0x41).f32(0))
    }

    @Test
    fun byteWriter() {
        val writer = ByteWriter()
        writer.writeU16(0x0102u, Endianness.BIG)
        writer.writeI32(-2)
        writer.writeF32(21.5f)
        assertEquals(hex("0102 feffffff 0000ac41"), writer.toBytes())
        val fixed = ByteWriter(zeroCount = 4)
        fixed.setI16(-1, 2)
        assertEquals(Bytes.of(0, 0, 0xFF, 0xFF), fixed.toBytes())
        assertEquals(ProtocolException.Truncated(3, 2, 4), assertThrows<ProtocolException> { fixed.setI16(1, 3) })
        assertThrows<ProtocolException> { fixed.setU8(1u, -1) }
    }
}
