@file:OptIn(ExperimentalCoroutinesApi::class)

package app.domik.deimic.client.commands

import app.domik.deimic.client.ConnectionProblem
import app.domik.deimic.client.now
import app.domik.deimic.protocol.ByteReader
import app.domik.deimic.protocol.Bytes
import app.domik.deimic.protocol.CommandCode
import app.domik.deimic.protocol.InputCalibration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CalibrationCommandsTest {
    private fun bytes(text: String): Bytes {
        val digits = text.filterNot { it.isWhitespace() }
        return Bytes.of(*IntArray(digits.length / 2) { digits.substring(2 * it, 2 * it + 2).toInt(16) })
    }

    /** i4 of the live controller: 22.5 -> 18.0. */
    private val original = InputCalibration.decode(bytes("01 00 00 00 61 43 00 00 00 00 00 00 34 43 00 00 00 00"))
    private val changed = InputCalibration.onePoint(measuredCelsius = 22.5f, trueCelsius = 19.0f)

    @Test
    fun writeSendsTheLiveRequestAndIsConfirmedByTheEcho() = runTest {
        val rig = commandsRig()
        rig.device.calibrations[4] = original
        assertEquals(CommandOutcome.Confirmed, rig.commands.writeCalibration(1, 4, changed))
        val sent = rig.sent().single()
        assertEquals(CommandCode.INPUT_CALIBRATION_SET, sent.command)
        assertEquals(1u, sent.address)
        assertEquals(bytes("04 01 00 00 00 61 43 00 00 00 00 00 00 3e 43 00 00 00 00"), sent.payload)
        assertEquals(changed, rig.device.calibrations[4])
    }

    @Test
    fun readReturnsTheRecordsInInputOrder() = runTest {
        val rig = commandsRig()
        rig.device.calibrations[4] = original
        val readout = rig.commands.readCalibrations(1)
        val records = (readout as CalibrationReadout.Success).records
        assertEquals(12, records.size)
        assertEquals(original, records[4])
        assertEquals(InputCalibration.INACTIVE, records[0])
        assertEquals(listOf(CommandCode.INPUT_CALIBRATION_GET), rig.sentCommands())
        assertEquals(1u, rig.sent().single().address)
        assertTrue(rig.sent().single().payload.isEmpty())
    }

    @Test
    fun writeThenReadBackMatches() = runTest {
        val rig = commandsRig()
        rig.device.calibrations[4] = original
        assertEquals(CommandOutcome.Confirmed, rig.commands.writeCalibration(1, 4, changed))
        val records = (rig.commands.readCalibrations(1) as CalibrationReadout.Success).records
        assertEquals(changed, records[4])
        assertEquals(changed.encode(), records[4].encode())
    }

    @Test
    fun acknowledgedWriteWithoutEchoIsAFailureNotAckOnly() = runTest {
        val rig = commandsRig()
        rig.device.sendCalibrationEcho = false
        val outcome = rig.commands.writeCalibration(1, 4, changed)
        assertTrue(outcome is CommandOutcome.Failed && outcome.problem == ConnectionProblem.NO_RESPONSE, "$outcome")
        assertEquals(3_000L, now)
    }

    @Test
    fun silentControllerIsNoAck() = runTest {
        val rig = commandsRig()
        rig.device.sendCalibrationEcho = false
        rig.device.sendAcks = false
        assertEquals(CommandOutcome.NoAck, rig.commands.writeCalibration(1, 4, changed))
    }

    @Test
    fun echoWithAnotherPayloadIsAFailure() = runTest {
        val rig = commandsRig()
        rig.device.calibrationEchoOverride = bytes("04 01 00 00 00 61 43 00 00 00 00 00 00 34 43 00 00 00 00")
        val outcome = rig.commands.writeCalibration(1, 4, changed)
        assertTrue(outcome is CommandOutcome.Failed && outcome.problem == ConnectionProblem.OTHER, "$outcome")
    }

    @Test
    fun echoOfAnotherInputIsNotAConfirmation() = runTest {
        val rig = commandsRig()
        rig.device.calibrationEchoOverride = bytes("05 01 00 00 00 61 43 00 00 00 00 00 00 3e 43 00 00 00 00")
        val outcome = rig.commands.writeCalibration(1, 4, changed)
        assertTrue(outcome is CommandOutcome.Failed && outcome.problem == ConnectionProblem.NO_RESPONSE, "$outcome")
    }

    @Test
    fun echoFromAnotherModuleIsNotAConfirmation() = runTest {
        val rig = commandsRig()
        rig.device.calibrationAddressOverride = 2u
        val outcome = rig.commands.writeCalibration(1, 4, changed)
        assertTrue(outcome is CommandOutcome.Failed && outcome.problem == ConnectionProblem.NO_RESPONSE, "$outcome")
    }

    @Test
    fun tableOfAnotherModuleIsIgnored() = runTest {
        val rig = commandsRig()
        rig.device.calibrationAddressOverride = 2u
        val readout = rig.commands.readCalibrations(1)
        assertTrue(readout is CalibrationReadout.Failure && readout.outcome == CommandOutcome.NoAck, "$readout")
    }

    @Test
    fun invalidArgumentsSendNothing() = runTest {
        val rig = commandsRig()
        val nan = InputCalibration(1u, 0u, Float.NaN, 0f, 190f, 0f)
        val flag = InputCalibration(2u, 0u, 225f, 0f, 190f, 0f)
        assertTrue(rig.commands.writeCalibration(0, 4, changed) is CommandOutcome.Invalid)
        assertTrue(rig.commands.writeCalibration(1, 12, changed) is CommandOutcome.Invalid)
        assertTrue(rig.commands.writeCalibration(1, -1, changed) is CommandOutcome.Invalid)
        assertTrue(rig.commands.writeCalibration(1, 4, nan) is CommandOutcome.Invalid)
        assertTrue(rig.commands.writeCalibration(1, 4, flag) is CommandOutcome.Invalid)
        assertTrue((rig.commands.readCalibrations(0) as CalibrationReadout.Failure).outcome is CommandOutcome.Invalid)
        assertEquals(emptyList<CommandCode>(), rig.sentCommands())
    }

    @Test
    fun inactiveRecordWithGarbageValuesIsWrittenAsIs() = runTest {
        // Unused inputs hold NaN / 0xFF; a restore of a backup must be able to write them back byte for byte.
        val rig = commandsRig()
        val empty = InputCalibration.decode(bytes("00 00 00 00 c0 7f 00 00 00 00 00 00 c0 7f 00 00 00 00"))
        assertEquals(CommandOutcome.Confirmed, rig.commands.writeCalibration(1, 6, empty))
        assertEquals(1 + InputCalibration.SIZE, rig.sent().single().payload.size)
        assertEquals(6, ByteReader(rig.sent().single().payload).u8(0).toInt())
    }

    @Test
    fun unansweredReadIsNoAck() = runTest {
        val rig = commandsRig()
        rig.device.answerReads = false
        val readout = rig.commands.readCalibrations(1)
        assertEquals(CalibrationReadout.Failure(CommandOutcome.NoAck), readout)
    }
}
