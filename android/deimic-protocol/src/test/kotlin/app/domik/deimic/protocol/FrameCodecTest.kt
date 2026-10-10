package app.domik.deimic.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class FrameEncodingTest {
    private val serial = FAKE_SERIAL

    @Test
    fun ping() {
        val expected = hex("00 00 00 1C 00 00 00 18 78 56 34 12 00 00 00 00 CE 01 00 00 00 00 00 00 00 00 00 00 00 00 00 00")
        assertEquals(expected, Request.ping(serial).encode())
    }

    @Test
    fun setSetpoint21point5() {
        val expected = hex(
            "00 00 00 21 00 00 00 1D 78 56 34 12 05 00 00 00 64 00 00 00 00 00 00 00 00 00 00 00 " +
                "05 00 00 00 03 00 00 AC 41",
        )
        assertEquals(expected, Request.setSetpoint(serial, module = 5u, outputNr = 3u, celsius = 21.5f).encode())
    }

    @Test
    fun setOutputOn() {
        val expected = hex(
            "00 00 00 1D 00 00 00 19 78 56 34 12 03 00 00 00 04 01 00 00 00 00 00 00 00 00 00 00 " +
                "01 00 00 00 02",
        )
        assertEquals(expected, Request.setOutput(serial, module = 3u, relay = 2u, on = true).encode())
    }

    @ParameterizedTest
    @CsvSource("23.0, 0500 00b841", "22.5, 05 0000b441")
    fun setpointPayload(celsius: Float, payloadHex: String) {
        val frame = Request.setSetpoint(serial, module = 1u, outputNr = 5u, celsius = celsius)
        assertEquals(hex(payloadHex), frame.payload)
        assertEquals(CommandCode.SET_SETPOINT, frame.command)
        assertEquals(1u, frame.address)
    }

    @Test
    fun otherRequests() {
        assertEquals(CommandCode.OUTPUT_OFF, Request.setOutput(serial, 1u, 12u, on = false).command)
        assertEquals(Bytes.of(1), Request.getState(serial).payload)
        assertTrue(Request.getState(serial, StateRequestVariant.V2).payload.isEmpty())
        assertEquals(Bytes.of(2), Request.getState(serial, StateRequestVariant.V3).payload)
        val temps = Request.temperatureReadout(serial)
        assertEquals(FrameAddress.BROADCAST, temps.address)
        assertEquals(CommandCode.TEMPERATURE_REPORT, temps.command)
        assertTrue(temps.payload.isEmpty())
        val heater = Request.heaterState(serial, module = 1u, outputNr = 4u)
        assertEquals(CommandCode.HEATER_STATE, heater.command)
        assertEquals(Bytes.of(4), heater.payload)
        val profile = Request.setProfile(serial, module = 1u, outputNr = 5u, profile = HeatingProfile.Comfort)
        assertEquals(CommandCode.SET_PROFILE, profile.command)
        assertEquals(Bytes.of(5, 2), profile.payload)
        assertEquals(CommandCode.FIRMWARE_VERSION, Request.firmwareVersion(serial).command)
        assertEquals(CommandCode.CONFIG_CHECKSUM, Request.configChecksum(serial).command)

        val download = Request.fileDownload(serial, "/logs/database-last.db3")
        assertEquals(64, download.payload.size)
        assertEquals(Bytes.ascii("/logs"), download.payload.copyOfRange(0, 5))
        assertEquals(Bytes.zeros(41), download.payload.copyOfRange(23, 64))
    }

    @Test
    fun fileDownloadPathValidation() {
        Request.fileDownload(serial, "a".repeat(63))
        assertThrows<ProtocolException.InvalidFilePath> { Request.fileDownload(serial, "a".repeat(64)) }
        assertThrows<ProtocolException.InvalidFilePath> { Request.fileDownload(serial, "/logs/żółw") }
        assertThrows<ProtocolException.InvalidFilePath> { Request.fileDownload(serial, "a\u0000b") }
    }

    @Test
    fun commandCodeDescription() {
        assertEquals("0x06B", CommandCode.HEATER_STATE.toString())
        assertEquals("0x1CE", CommandCode.PING.toString())
        assertEquals("0xABCDE", CommandCode(0xABCDEu).toString())
        assertNotEquals(CommandCode.PING, CommandCode(0x999u))
        assertEquals(CommandCode.PING, CommandCode(0x1CEu))
    }

    @Test
    fun frameDescriptionHidesSerial() {
        val frame = Request.ping(serial)
        val boxed: Any = frame.serial
        for (text in listOf(frame.toString(), "$frame", listOf(frame).toString(), boxed.toString())) {
            assertFalse(text.contains("12345678"), text)
            assertFalse(text.contains("305419896"), text)
            assertFalse(text.contains("78 56 34 12"), text)
        }
    }

    @Test
    fun frameEqualityComparesPayloadContents() {
        val first = Frame(serial, 1u, CommandCode.OUTPUT_ON, payload = Bytes.of(12))
        val second = Frame(serial, 1u, CommandCode.OUTPUT_ON, payload = Bytes.copyOf(byteArrayOf(12)))
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertNotEquals(first, first.copy(payload = Bytes.of(13)))
    }

    @Test
    fun bytesAreImmutable() {
        val source = byteArrayOf(1, 2, 3)
        val bytes = Bytes.copyOf(source)
        source[0] = 9
        bytes.toByteArray()[1] = 9
        assertEquals(Bytes.of(1, 2, 3), bytes)
    }

    @Test
    fun crc16() {
        assertEquals(0xBB3D.toUShort(), Crc16Arc.checksum("123456789".toByteArray(Charsets.US_ASCII)))
        assertEquals(0.toUShort(), Crc16Arc.checksum(ByteArray(0)))
        assertEquals(0xBB3D.toUShort(), Crc16Arc.checksum(Bytes.ascii("123456789")))
    }
}

class FrameDecoderTest {
    private val ping = Request.ping(FAKE_SERIAL)
    private val setpoint = Request.setSetpoint(FAKE_SERIAL, module = 5u, outputNr = 3u, celsius = 21.5f)

    @Test
    fun byteByByte() {
        val decoder = FrameDecoder()
        val frames = mutableListOf<Frame>()
        val bytes = (setpoint.encode() + ping.encode()).toByteArray()
        for (byte in bytes) frames += decoder.append(byteArrayOf(byte))
        assertEquals(listOf(setpoint, ping), frames)
        assertEquals(0, decoder.bufferedByteCount)
    }

    @Test
    fun twoFramesInOneChunk() {
        assertEquals(listOf(ping, setpoint), FrameDecoder().append(ping.encode() + setpoint.encode()))
    }

    @Test
    fun partialHeaderThenRest() {
        val decoder = FrameDecoder()
        val bytes = (setpoint.encode() + ping.encode()).toByteArray()
        assertTrue(decoder.append(bytes, 0, 5).isEmpty())
        assertTrue(decoder.append(bytes, 5, 20).isEmpty())
        assertEquals(listOf(setpoint), decoder.append(bytes, 20, 40))
        assertEquals(3, decoder.bufferedByteCount)
        assertEquals(listOf(ping), decoder.append(bytes, 40, bytes.size))
    }

    @Test
    fun zeroLengthPayload() {
        val frames = FrameDecoder().append(ping.encode())
        assertEquals(1, frames.size)
        assertTrue(frames.first().payload.isEmpty())
        assertEquals(0x12345678u, frames.first().serial.value)
    }

    @Test
    fun tooShortLength() {
        val bytes = ping.encode().toByteArray()
        bytes[3] = 0x1B // total = 31
        bytes[7] = 0x17
        val error = assertThrows<ProtocolException> { FrameDecoder().append(bytes) }
        assertEquals(ProtocolException.FrameTooShort(31), error)
    }

    @Test
    fun len8Mismatch() {
        val bytes = ping.encode().toByteArray()
        bytes[7] = 0x19
        val error = assertThrows<ProtocolException> { FrameDecoder().append(bytes, 0, 8) }
        assertEquals(ProtocolException.LengthMismatch(0x1Cu, 0x19u), error)
    }

    @Test
    fun paddedPayloadAreaIsIgnored() {
        // Regression (live controller): reply to 0x6B with plen 17 in a 19-byte payload area (2 bytes padding).
        val heaterPayload = hex("01 01 7a 14 ac 41 00 00 00 00 02 00 00 00 b8 41 00")
        val raw = hex(
            "00 00 00 2F 00 00 00 2B 78 56 34 12 01 00 00 00 6B 00 00 00 00 00 00 00 00 00 00 00 11 00 00 00" +
                "01 01 7a 14 ac 41 00 00 00 00 02 00 00 00 b8 41 00 00 00",
        ).toByteArray()
        assertEquals(51, raw.size)

        val unpadded = Frame(FAKE_SERIAL, 1u, CommandCode.HEATER_STATE, payload = heaterPayload)
        val expectedState = HeaterState.parse(unpadded)

        val decoder = FrameDecoder()
        val result = decoder.decode(raw + ping.encode().toByteArray())
        assertEquals(listOf(unpadded, ping), result.frames) // the frame after the padding is not misaligned
        assertTrue(result.malformed.isEmpty())
        assertEquals(17, result.frames.first().payload.size)
        assertEquals(expectedState, HeaterState.parse(result.frames.first()))
        assertNull(decoder.failure)
        assertEquals(0, decoder.bufferedByteCount)

        // Same when the bytes arrive one by one.
        val byteByByte = FrameDecoder()
        val frames = mutableListOf<Frame>()
        for (byte in raw) frames += byteByByte.append(byteArrayOf(byte))
        assertEquals(listOf(unpadded), frames)
    }

    @Test
    fun payloadLengthBelowAreaTakesFirstBytesOnly() {
        val bytes = setpoint.encode().toByteArray() // 5-byte payload area
        bytes[28] = 4
        val frame = FrameDecoder().append(bytes).single()
        assertEquals(setpoint.copy(payload = setpoint.payload.copyOfRange(0, 4)), frame)
        bytes[28] = 0
        assertTrue(FrameDecoder().append(bytes).single().payload.isEmpty())
    }

    @Test
    fun payloadLengthBeyondAreaIsSkippedAsMalformed() {
        val bytes = setpoint.encode().toByteArray() // 5-byte payload area
        bytes[28] = 40
        val decoder = FrameDecoder()
        val result = decoder.decode(bytes + ping.encode().toByteArray() + bytes + setpoint.encode().toByteArray())
        assertEquals(listOf(ping, setpoint), result.frames)
        assertEquals(2, result.malformed.size)
        val malformed = result.malformed.first()
        assertEquals(ProtocolException.PayloadLengthMismatch(40u, 5), malformed.error)
        assertEquals(CommandCode.SET_SETPOINT, malformed.frame.command)
        assertEquals(setpoint.payload, malformed.frame.payload) // whole payload area kept for diagnostics
        assertNull(decoder.failure) // framing is intact, the decoder stays usable
        assertEquals(0, decoder.bufferedByteCount)
        assertEquals(listOf(ping), decoder.append(ping.encode()))

        // Skipping also works when the malformed frame is split across chunks; append() drops it silently.
        val byteByByte = FrameDecoder()
        val frames = mutableListOf<Frame>()
        for (byte in bytes + ping.encode().toByteArray()) frames += byteByByte.append(byteArrayOf(byte))
        assertEquals(listOf(ping), frames)
        assertEquals(0, byteByByte.bufferedByteCount)
    }

    @Test
    fun oversizeRejectedBeforeBuffering() {
        val writer = ByteWriter()
        writer.writeU32(2048u - 4u, Endianness.BIG)
        writer.writeU32(2048u - 8u, Endianness.BIG)
        val error = assertThrows<ProtocolException> { FrameDecoder(maxFrameLength = 1024).append(writer.toBytes()) }
        assertEquals(ProtocolException.FrameTooLarge(2048, 1024), error)

        val huge = ByteWriter()
        huge.writeU32(UInt.MAX_VALUE, Endianness.BIG)
        huge.writeU32(UInt.MAX_VALUE - 4u, Endianness.BIG)
        assertThrows<ProtocolException.FrameTooLarge> { FrameDecoder().append(huge.toBytes()) }
    }

    @Test
    fun staysFailedUntilReset() {
        val decoder = FrameDecoder()
        val bad = ping.encode().toByteArray()
        bad[7] = 0
        assertThrows<ProtocolException> { decoder.append(bad) }
        assertNotNull(decoder.failure)
        assertThrows<ProtocolException> { decoder.append(ping.encode()) }
        decoder.reset()
        assertNull(decoder.failure)
        assertEquals(listOf(ping), decoder.append(ping.encode()))
    }

    @Test
    fun errorDiscardsFramesOfTheSameChunk() {
        val bad = ping.encode().toByteArray()
        bad[7] = 0
        val decoder = FrameDecoder()
        assertThrows<ProtocolException> { decoder.append(ping.encode() + Bytes.copyOf(bad)) }
        assertEquals(0, decoder.bufferedByteCount)
    }

    @Test
    fun roundTripLargePayload() {
        val frame = Frame(
            OTHER_SERIAL, 0x1FFu, CommandCode.STATE_NEW, group = 2u, flags = 3u,
            payload = Bytes.copyOf(ByteArray(15148) { it.toByte() }),
        )
        val decoder = FrameDecoder()
        val encoded = frame.encode().toByteArray()
        val frames = mutableListOf<Frame>()
        for (start in encoded.indices step 1000) {
            frames += decoder.append(encoded, start, minOf(start + 1000, encoded.size))
        }
        assertEquals(listOf(frame), frames)
    }

    private companion object {
        val OTHER_SERIAL = SerialNumber(1u)
    }
}
