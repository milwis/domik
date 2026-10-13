package app.domik.deimic.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.abs

/** Vectors captured from a live controller (2026-10-12). */
class InputCalibrationTest {
    private fun frame(command: CommandCode, payload: Bytes, address: UInt = 1u) =
        Frame(FAKE_SERIAL, address, command, payload = payload)

    private val record4 = "01 00 00 00 61 43 00 00 00 00 00 00 34 43 00 00 00 00"

    /** The 12 records read from the live module 1 (i0..i5 calibrated, i6 NaN, i7..i11 0xFF). */
    private val liveTable = listOf(
        "01 00 00 00 89 43 00 00 00 00 00 00 6e 43 00 00 00 00",
        "01 00 00 00 75 43 00 00 00 00 00 00 5d 43 00 00 00 00",
        "01 00 00 80 89 43 00 00 00 00 00 00 62 43 00 00 00 00",
        "01 00 00 00 75 43 00 00 00 00 00 00 5c 43 00 00 00 00",
        record4,
        "01 00 00 00 7a 43 00 00 00 00 00 00 5e 43 00 00 00 00",
        "00 00 00 00 c0 7f 00 00 00 00 00 00 c0 7f 00 00 00 00",
    ) + List(5) { "00 00 ff ff ff ff ff ff ff ff ff ff ff ff ff ff ff ff" }

    private fun near(expected: Float, actual: Float, tolerance: Float = 0.001f) =
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected, got $actual")

    @Test
    fun recordDecodesLiveVector() {
        val record = InputCalibration.decode(hex(record4))
        assertTrue(record.point1Active)
        assertFalse(record.point2Active)
        assertEquals(225.0f, record.measured1Tenths)
        assertEquals(180.0f, record.true1Tenths)
        assertEquals(0f, record.measured2Tenths)
        assertEquals(hex(record4), record.encode())
    }

    @Test
    fun recordsOfTheLiveTableMatchTheDocumentedValues() {
        val table = liveTable.map { InputCalibration.decode(hex(it)) }
        val expected = listOf(27.4f to 23.8f, 24.5f to 22.1f, 27.5f to 22.6f, 24.5f to 22.0f, 22.5f to 18.0f, 25.0f to 22.2f)
        expected.forEachIndexed { input, (measured, truth) ->
            near(measured, table[input].measured1Tenths / 10f, 0.01f)
            near(truth, table[input].true1Tenths / 10f, 0.01f)
            assertFalse(table[input].point2Active)
        }
    }

    @Test
    fun table216BytesRoundTrips() {
        val payload = hex(liveTable.joinToString(" "))
        assertEquals(216, payload.size)
        val parsed = InputCalibrationTable.parse(frame(CommandCode.INPUT_CALIBRATION_GET, payload))
        assertEquals(1u, parsed.module)
        assertEquals(12, parsed.records.size)
        assertTrue(parsed.records[6].measured1Tenths.isNaN())
        assertEquals(payload, parsed.encode())
        assertEquals(parsed, ControllerMessage.decode(frame(CommandCode.INPUT_CALIBRATION_GET, payload)))
        assertEquals(liveTable.indices.map { hex(liveTable[it]) }, parsed.records.map { it.encode() })
    }

    @Test
    fun tableLengthMustBeAMultipleOf18() {
        assertThrows<ProtocolException.InvalidPayload> {
            InputCalibrationTable.parse(frame(CommandCode.INPUT_CALIBRATION_GET, Bytes.zeros(19)))
        }
        assertThrows<ProtocolException.PayloadTooShort> {
            InputCalibrationTable.parse(frame(CommandCode.INPUT_CALIBRATION_GET, Bytes.EMPTY))
        }
        assertThrows<ProtocolException.UnexpectedCommand> {
            InputCalibrationTable.parse(frame(CommandCode.INPUT_CALIBRATION_SET, Bytes.zeros(18)))
        }
        assertEquals(
            1,
            (ControllerMessage.decode(frame(CommandCode.INPUT_CALIBRATION_GET, Bytes.zeros(18))) as InputCalibrationTable).records.size,
        )
        assertTrue(ControllerMessage.decode(frame(CommandCode.INPUT_CALIBRATION_GET, Bytes.zeros(20))) is ControllerMessage.Malformed)
    }

    @Test
    fun writeRequestMatchesTheLiveVector() {
        val payload = "04 01 00 00 00 61 43 00 00 00 00 00 00 3e 43 00 00 00 00"
        val record = InputCalibration.onePoint(measuredCelsius = 22.5f, trueCelsius = 19.0f)
        val request = Request.setInputCalibration(FAKE_SERIAL, 1u, 4u, record)
        assertEquals(CommandCode.INPUT_CALIBRATION_SET, request.command)
        assertEquals(1u, request.address)
        assertEquals(hex(payload), request.payload)
        assertEquals(19, request.payload.size)
        assertEquals(record, InputCalibration.decode(hex(payload.substring(3))))
    }

    @Test
    fun writeConfirmationIsTheEchoedPayload() {
        val payload = hex("04 01 00 00 00 61 43 00 00 00 00 00 00 3e 43 00 00 00 00")
        val written = InputCalibrationWritten.parse(frame(CommandCode.INPUT_CALIBRATION_SET, payload))
        assertEquals(1u, written.module)
        assertEquals(4.toUByte(), written.input)
        assertEquals(190.0f, written.record.true1Tenths)
        assertEquals(payload, Bytes.of(4) + written.record.encode())
        assertEquals(written, ControllerMessage.decode(frame(CommandCode.INPUT_CALIBRATION_SET, payload)))
        assertThrows<ProtocolException.PayloadTooShort> {
            InputCalibrationWritten.parse(frame(CommandCode.INPUT_CALIBRATION_SET, Bytes.zeros(18)))
        }
    }

    @Test
    fun readRequestIsEmptyAndAddressedToTheModule() {
        val request = Request.inputCalibrations(FAKE_SERIAL, 1u)
        assertEquals(CommandCode.INPUT_CALIBRATION_GET, request.command)
        assertEquals(1u, request.address)
        assertTrue(request.payload.isEmpty())
    }

    @Test
    fun analogValueChangedLiveVectors() {
        val input4 = AnalogValueChanged.parse(frame(CommandCode.ANALOG_VALUE_CHANGED, hex("04 4e bf b2 41")))
        assertEquals(4.toUByte(), input4.input)
        near(22.343f, input4.value, 0.001f)
        val input1 = AnalogValueChanged.parse(frame(CommandCode.ANALOG_VALUE_CHANGED, hex("01 32 60 ae 41")))
        assertEquals(1.toUByte(), input1.input)
        near(21.797f, input1.value, 0.001f)
        assertEquals(input1, ControllerMessage.decode(frame(CommandCode.ANALOG_VALUE_CHANGED, hex("01 32 60 ae 41"))))
    }

    @Test
    fun correctionOnePoint() {
        val record = InputCalibration.decode(hex(record4)) // 22.5 -> 18.0 = -4.5
        near(21.35f, record.correctCelsius(25.85f))
        near(18.0f, record.correctCelsius(22.5f))
        near(180.0f, record.correctTenths(225.0f))
    }

    @Test
    fun correctionWithoutActivePointIsIdentity() {
        assertEquals(23.456f, InputCalibration.INACTIVE.correctCelsius(23.456f))
        assertEquals(InputCalibration.INACTIVE, InputCalibration.decode(hex("00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00")))
    }

    @Test
    fun correctionTwoPointsIsLinearThroughBoth() {
        // 10.0 -> 11.0 and 30.0 -> 28.0: slope 0.85
        val record = InputCalibration(1u, 1u, 100f, 300f, 110f, 280f)
        near(11.0f, record.correctCelsius(10.0f))
        near(28.0f, record.correctCelsius(30.0f))
        near(19.5f, record.correctCelsius(20.0f))
        near(9.3f, record.correctCelsius(8.0f)) // extrapolated
        // Equal measured values cannot define a line: falls back to the first point's offset.
        near(21.0f, InputCalibration(1u, 1u, 100f, 100f, 110f, 280f).correctCelsius(20.0f))
        // Only the second point active.
        near(21.0f, InputCalibration(0u, 1u, 0f, 300f, 0f, 310f).correctCelsius(20.0f))
    }
}
