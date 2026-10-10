package app.domik.deimic.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class StateSnapshotTest {
    private fun frame(command: CommandCode, payload: Bytes) =
        Frame(FAKE_SERIAL, FrameAddress.BROADCAST, command, payload = payload)

    @ParameterizedTest
    @EnumSource(StateFormat::class)
    fun layoutTableIsConsistent(format: StateFormat) {
        var end = 0
        for (extent in format.layout) {
            assertTrue(extent.offset >= end, "$format ${extent.field} overlaps previous field")
            assertEquals(0, extent.byteLength % extent.elementType.size)
            end = extent.offset + extent.byteLength
        }
        assertEquals(format.recordSize, end, "$format layout must end at the record size")
        assertEquals(format.layout.size, format.layout.map { it.field }.toSet().size)
    }

    @ParameterizedTest
    @EnumSource(StateFormat::class)
    fun multipleModules(format: StateFormat) {
        val heaters = listOf(1, 0, 255, 7)
        val temperatures = listOf(21.5f, -3.25f)
        val bytes = syntheticRecord(format, module = 1, on = listOf(0, 12, 127), heaters = heaters, temperatures = temperatures) +
            syntheticRecord(format, module = 512, on = listOf(3)) +
            syntheticRecord(format, module = -1)
        val snapshot = StateSnapshot.parse(frame(CommandCode.STATE_NEW, bytes))
        assertEquals(format, snapshot.format)
        assertTrue(snapshot.issues.isEmpty())
        assertFalse(snapshot.isEmpty)
        assertEquals(listOf(1u, 512u, UInt.MAX_VALUE), snapshot.modules.map { it.module })
        val first = checkNotNull(snapshot.module(1u))
        assertEquals(128, first.outputs.size)
        assertEquals(listOf(0, 12, 127), first.onOutputs)
        assertEquals(listOf(3), snapshot.module(512u)?.onOutputs)

        when (format) {
            StateFormat.V3, StateFormat.NEW -> {
                assertEquals(32, first.heaters?.size)
                assertEquals(
                    listOf(HeaterFlag.Heating, HeaterFlag.Idle, HeaterFlag.NoZone, HeaterFlag.Unknown(7u)),
                    first.heaters?.take(4),
                )
                assertEquals(128, first.temperatures?.size)
                assertEquals(temperatures, first.temperatures?.take(2))
                assertEquals(32, first.dimmers.size)
                assertEquals(128, first.inputs.size)
                assertEquals(64, first.shutterStates.size)
                assertEquals(if (format == StateFormat.V3) 64 else 0, first.shutterPositions?.size ?: 0)
            }
            StateFormat.V2 -> {
                assertNull(first.heaters)
                assertNull(first.shutterPositions)
                assertEquals(temperatures, first.temperatures?.take(2))
            }
            StateFormat.V1 -> {
                assertNull(first.heaters)
                assertEquals(32, first.temperatures?.size)
                assertEquals(temperatures, first.temperatures?.take(2))
                assertEquals(8, first.dimmers.size)
                assertEquals(32, first.inputs.size)
                assertEquals(12, first.shutterStates.size)
            }
            StateFormat.OLD -> {
                assertNull(first.heaters)
                assertNull(first.temperatures)
            }
        }
    }

    @Test
    fun v3WithPrefix() {
        val snapshot = StateSnapshot.parse(
            frame(CommandCode.STATE_V3, Bytes.of(0x02) + syntheticRecord(StateFormat.V3, module = 7, on = listOf(5))),
        )
        assertEquals(StateFormat.V3, snapshot.format)
        assertEquals(7u, snapshot.modules.first().module)
        assertEquals(listOf(5), snapshot.modules.first().onOutputs)
    }

    @Test
    fun v3WithoutPrefixIsAnIssue() {
        val snapshot = StateSnapshot.parse(
            frame(CommandCode.STATE_V3, syntheticRecord(StateFormat.V3, module = 7, on = listOf(5))),
        )
        assertTrue(snapshot.modules.isEmpty())
        assertEquals(listOf(StateParseIssue.MissingV3Prefix(0u)), snapshot.issues)
    }

    @Test
    fun unknownLengthIsAnIssue() {
        val payload = Bytes.copyOf(ByteArray(1000) { 1 })
        val snapshot = StateSnapshot.parse(frame(CommandCode.GET_STATE, payload))
        assertTrue(snapshot.modules.isEmpty())
        assertNull(snapshot.format)
        assertEquals(listOf(StateParseIssue.UnknownLength(1000)), snapshot.issues)
        assertFalse(snapshot.isEmpty)
    }

    @Test
    fun emptyPayload() {
        for (command in listOf(CommandCode.GET_STATE, CommandCode.STATE_NEW, CommandCode.STATE_V3)) {
            val snapshot = StateSnapshot.parse(frame(command, Bytes.EMPTY))
            assertTrue(snapshot.isEmpty)
            assertTrue(snapshot.modules.isEmpty() && snapshot.issues.isEmpty())
            assertNull(snapshot.format)
        }
        assertTrue(StateSnapshot.parse(frame(CommandCode.STATE_V3, Bytes.of(0x02))).isEmpty)
    }

    @Test
    fun wrongCommand() {
        val error = assertThrows<ProtocolException> { StateSnapshot.parse(frame(CommandCode.HEATER_STATE, Bytes.EMPTY)) }
        assertEquals(ProtocolException.UnexpectedCommand(CommandCode.HEATER_STATE), error)
    }
}
