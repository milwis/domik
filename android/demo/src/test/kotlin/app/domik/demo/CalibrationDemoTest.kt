@file:OptIn(ExperimentalCoroutinesApi::class)

package app.domik.demo

import app.domik.deimic.client.Connection
import app.domik.deimic.client.ConnectionProblem
import app.domik.deimic.client.commands.CalibrationReadout
import app.domik.deimic.client.commands.CommandOutcome
import app.domik.deimic.protocol.Ack
import app.domik.deimic.protocol.AnalogValueChanged
import app.domik.deimic.protocol.ControllerMessage
import app.domik.deimic.protocol.HeaterState
import app.domik.deimic.protocol.InputCalibration
import app.domik.deimic.protocol.InputCalibrationTable
import app.domik.deimic.protocol.InputCalibrationWritten
import app.domik.deimic.protocol.TemperatureReport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class CalibrationDemoTest {
    private fun near(expected: Float, actual: Float?, tolerance: Float = 0.01f) {
        assertNotNull(actual)
        assertTrue(abs(expected - actual!!) <= tolerance, "expected $expected, got $actual")
    }

    private suspend fun DemoRig.records(module: Int = 1): List<InputCalibration> =
        (commands.readCalibrations(module) as CalibrationReadout.Success).records

    @Test
    fun theTableHasTwelveRecordsAndCalibratesTheSensorsOfTheHouse() = runTest {
        val rig = demoRig()
        val module1 = rig.records(1)
        assertEquals(12, module1.size)
        // Salon, Sypialnia, Łazienka sit on inputs 4, 0, 3 (not on 0..2): one active point, a constant offset of -2.5 °C.
        for (input in listOf(4, 0, 3)) {
            assertTrue(module1[input].point1Active && !module1[input].point2Active, "input $input")
            near(-25f, module1[input].true1Tenths - module1[input].measured1Tenths)
        }
        assertEquals(InputCalibration.INACTIVE, module1[1])
        assertEquals(InputCalibration.INACTIVE, module1[2])
        assertEquals(InputCalibration.INACTIVE, module1[11])
        val module2 = rig.records(2)
        assertTrue(module2[2].point1Active, "the zone sensor of module 2")
        assertEquals(InputCalibration.INACTIVE, module2[0])
        assertTrue(module2[1].point1Active, "the free-standing sensor of module 2")
        near(11.5f * 10f, module2[1].true1Tenths)
        // Reading changed nothing and the controller sent one table per request.
        assertEquals(21.4f, rig.controller.temperature(1, 4))
        assertEquals(2, rig.messages.count { it is InputCalibrationTable })
    }

    @Test
    fun theTableIsTheSameBytesTheControllerHolds() = runTest {
        val rig = demoRig()
        val records = rig.records(1)
        records.forEachIndexed { input, record -> assertEquals(rig.controller.calibration(1, input), record) }
        assertNull(rig.controller.calibration(1, 12))
        assertNull(rig.controller.calibration(9, 0))
    }

    @Test
    fun writingAnOffsetChangesTheReportedTemperatureAndSendsTheNewValue() = runTest {
        val rig = demoRig()
        val before = rig.records(1)[4]
        val shifted = before.copy(true1Tenths = before.true1Tenths + 10f) // +1.0 °C
        val sizeBefore = rig.messages.size

        assertEquals(CommandOutcome.Confirmed, rig.commands.writeCalibration(1, 4, shifted))
        runCurrent()

        near(22.4f, rig.controller.temperature(1, 4))
        assertEquals(shifted, rig.records(1)[4])
        val received = rig.messages.drop(sizeBefore)
        // Order like on the real controller: acknowledgement, the 0x132 echo, then the recalculated values.
        val ack = received.indexOfFirst { it is Ack }
        val echo = received.indexOfFirst { it is InputCalibrationWritten }
        val report = received.indexOfFirst { it is TemperatureReport }
        assertTrue(ack in 0 until echo && echo < report, "ack=$ack echo=$echo report=$report")
        val written = received.filterIsInstance<InputCalibrationWritten>().single()
        assertEquals(1u, written.module)
        assertEquals(4.toUByte(), written.input)
        assertEquals(shifted, written.record)
        near(22.4f, received.filterIsInstance<TemperatureReport>().single { it.roomId.toInt() == 1 }.temperature)
        near(22.4f, received.filterIsInstance<AnalogValueChanged>().single { it.input.toInt() == 4 }.value)
        near(22.4f, received.filterIsInstance<HeaterState>().single { it.outputNr.toInt() == 0 }.currentTemperature)
    }

    @Test
    fun theNewTemperatureReachesTheHeatingDecision() = runTest {
        val rig = demoRig()
        assertTrue(checkNotNull(rig.controller.heaterState(1, 0)).isHeating, "Salon 21.4 < comfort 22.0")
        val record = rig.records(1)[4]
        assertEquals(CommandOutcome.Confirmed, rig.commands.writeCalibration(1, 4, record.copy(true1Tenths = record.true1Tenths + 10f)))
        val state = checkNotNull(rig.controller.heaterState(1, 0))
        assertFalse(state.isHeating)
        near(22.4f, state.currentTemperature)
    }

    @Test
    fun anInactiveCalibrationReportsTheRawValue() = runTest {
        val rig = demoRig()
        assertEquals(CommandOutcome.Confirmed, rig.commands.writeCalibration(1, 4, InputCalibration.INACTIVE))
        near(21.4f + 2.5f, rig.controller.temperature(1, 4)) // the raw value behind the initial -2.5 °C offset
        // Back to the original offset: the same reported value as before.
        assertEquals(CommandOutcome.Confirmed, rig.commands.writeCalibration(1, 4, InputCalibration.onePoint(24.0f, 21.5f)))
        near(21.4f + 2.5f - 2.5f, rig.controller.temperature(1, 4))
    }

    @Test
    fun twoPointsMapLinearlyThroughBothPoints() = runTest {
        val rig = demoRig()
        // Sypialnia (input 0) reports 19.2 °C, raw 21.7 °C. Points 20 -> 20 and 30 -> 25: slope 0.5.
        val twoPoints = InputCalibration(1u, 1u, 200f, 300f, 200f, 250f)
        assertEquals(CommandOutcome.Confirmed, rig.commands.writeCalibration(1, 0, twoPoints))
        near(20.0f + (21.7f - 20.0f) * 0.5f, rig.controller.temperature(1, 0))
    }

    @Test
    fun aFreeStandingSensorFollowsItsCalibrationToo() = runTest {
        val rig = demoRig()
        val record = rig.records(2)[1]
        assertEquals(CommandOutcome.Confirmed, rig.commands.writeCalibration(2, 1, record.copy(true1Tenths = record.true1Tenths - 20f)))
        near(9.5f, rig.controller.temperature(2, 1))
        assertTrue(rig.messages.filterIsInstance<AnalogValueChanged>().any { it.module == 2u && it.input.toInt() == 1 })
    }

    @Test
    fun otherClientsGetTheNewValuesButNotTheEcho() = runTest {
        val rig = demoRig()
        val other = Connection(rig.controller.endpoint, rig.controller.serial, backgroundScope, rig.controller.transportFactory)
        val otherMessages = mutableListOf<ControllerMessage>()
        backgroundScope.launch { other.messages.collect { otherMessages += it } }
        other.connect()
        runCurrent()

        val record = rig.records(1)[4]
        rig.commands.writeCalibration(1, 4, record.copy(true1Tenths = record.true1Tenths + 5f))
        runCurrent()
        assertTrue(otherMessages.none { it is InputCalibrationWritten })
        near(21.9f, otherMessages.filterIsInstance<TemperatureReport>().single { it.roomId.toInt() == 1 }.temperature)
    }

    @Test
    fun anUnknownModuleIsAcknowledgedButNotConfirmed() = runTest {
        val rig = demoRig()
        val outcome = rig.commands.writeCalibration(9, 0, InputCalibration.onePoint(20f, 20f))
        assertTrue(outcome is CommandOutcome.Failed && outcome.problem == ConnectionProblem.NO_RESPONSE, "$outcome")
        assertTrue((rig.commands.readCalibrations(9) as CalibrationReadout.Failure).outcome == CommandOutcome.NoAck)
        assertEquals(21.4f, rig.controller.temperature(1, 4))
    }

    @Test
    fun aNonFiniteActivePointIsIgnoredByTheController() = runTest {
        val rig = demoRig()
        val request = app.domik.deimic.protocol.Request.setInputCalibration(
            rig.connection.serial, 1u, 4u, InputCalibration(1u, 0u, Float.NaN, 0f, 200f, 0f),
        )
        val result = runCatching {
            rig.connection.request(request, setOf(app.domik.deimic.protocol.CommandCode.INPUT_CALIBRATION_SET))
        }
        assertTrue(result.isFailure)
        assertEquals(21.4f, rig.controller.temperature(1, 4))
        assertTrue(rig.controller.calibration(1, 4)!!.measured1Tenths.isFinite())
    }

    @Test
    fun calibratingAnInputChangesTheZoneOfItsRoomNotTheZoneWithTheSameNumber() = runTest {
        val rig = demoRig()
        // Input 0 is the sensor of Sypialnia (zone 1); zone 0 is Salon, whose sensor is input 4.
        val record = rig.records(1)[0]
        assertEquals(CommandOutcome.Confirmed, rig.commands.writeCalibration(1, 0, record.copy(true1Tenths = record.true1Tenths + 10f)))
        near(20.2f, rig.controller.heaterState(1, 1)?.currentTemperature)
        near(20.2f, rig.controller.temperature(1, 0))
        near(21.4f, rig.controller.heaterState(1, 0)?.currentTemperature)
        near(21.4f, rig.controller.temperature(1, 4))
        val reported = rig.messages.filterIsInstance<HeaterState>().single()
        assertEquals(1, reported.outputNr.toInt())
    }

    @Test
    fun theRoomTemperatureIsTheTruthAndDoesNotFollowTheCalibration() = runTest {
        val rig = demoRig()
        near(21.4f, rig.controller.roomTemperature(1, 0))
        near(19.2f, rig.controller.roomTemperature(1, 1))
        assertNull(rig.controller.roomTemperature(1, 9))
        assertNull(rig.controller.roomTemperature(2, 3))
        // A fake room thermometer reads 20.4 °C; calibrating the Deimic sensor onto it makes the controller agree.
        val record = rig.records(1)[0]
        val shift = (20.4f - 19.2f) * 10f
        assertEquals(CommandOutcome.Confirmed, rig.commands.writeCalibration(1, 0, record.copy(true1Tenths = record.true1Tenths + shift)))
        near(19.2f, rig.controller.roomTemperature(1, 1))
        near(20.4f, rig.controller.temperature(1, 0))
        rig.controller.advance(kotlin.time.Duration.parse("10m"))
        near(rig.controller.roomTemperature(1, 1)!! + 1.2f, rig.controller.temperature(1, 0), 0.02f)
    }
}
