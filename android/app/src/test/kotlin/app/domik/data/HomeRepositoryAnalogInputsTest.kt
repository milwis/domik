@file:OptIn(ExperimentalCoroutinesApi::class)

package app.domik.data

import app.domik.demo.DemoNetwork
import app.domik.demo.DemoSimulation
import app.domik.deimic.client.commands.CalibrationReadout
import app.domik.deimic.client.commands.CommandOutcome
import app.domik.deimic.config.InMemoryConfigCache
import app.domik.deimic.protocol.InputCalibration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/** Wartości wejść analogowych (0x130 / 0x72) w stanie domu oraz port kalibracji sesji. */
class HomeRepositoryAnalogInputsTest {
    private fun TestScope.session(): HomeSession {
        val session = Sessions.demo(
            backgroundScope, InMemoryConfigCache(), DemoNetwork.Instant, DemoSimulation.Manual, StandardTestDispatcher(testScheduler),
        )
        session.repository.start()
        runCurrent()
        return session
    }

    private fun near(expected: Float, actual: Float?) {
        assertNotNull(actual)
        assertTrue(abs(expected - actual!!) <= 0.005f, "expected $expected, got $actual")
    }

    @Test
    fun theTemperatureReadoutAtSyncFillsEveryInputOfTheHome() = runTest {
        val state = session().repository.state.value
        // Wejścia stref w domu demo mają numery inne niż strefy: 4, 0, 3 na module 1 i 2 na module 2; czujnik zewnętrzny to 1.
        assertEquals(
            setOf(InputKey(1, 4), InputKey(1, 0), InputKey(1, 3), InputKey(2, 2), InputKey(2, 1)),
            state.analogInputs.keys,
        )
        near(21.4f, state.analogInputs[InputKey(1, 4)])
        near(19.2f, state.analogInputs[InputKey(1, 0)])
        near(11.5f, state.analogInputs[InputKey(2, 1)])
    }

    @Test
    fun theInputOfAZoneIsFoundThroughTheRoomOfTheConfiguration() = runTest {
        val state = session().repository.state.value
        val config = checkNotNull(state.config)
        for (zone in config.heatingZones) {
            val input = config.temperatureInputsFor(zone).single()
            val fromInput = state.analogInputs[InputKey(input.module, input.inputNr)]
            val fromZone = state.zones.getValue(app.domik.deimic.client.commands.ZoneKey(zone.module, zone.outputNr)).currentTemperature
            near(fromZone, fromInput)
        }
    }

    @Test
    fun anAnalogValueEventUpdatesTheStateOfThatInputOnly() = runTest {
        val session = session()
        val record = (session.calibration.read(1) as CalibrationReadout.Success).records[4]
        assertEquals(CommandOutcome.Confirmed, session.calibration.write(1, 4, record.copy(true1Tenths = record.true1Tenths + 10f)))
        runCurrent()
        val inputs = session.repository.state.value.analogInputs
        near(22.4f, inputs[InputKey(1, 4)])
        near(19.2f, inputs[InputKey(1, 0)])
        assertNull(inputs[InputKey(1, 5)])
    }

    @Test
    fun aChangeBelowOneHundredthOfADegreeIsNotStored() = runTest {
        val session = session()
        val before = session.repository.state.value.analogInputs
        val record = (session.calibration.read(1) as CalibrationReadout.Success).records[4]
        // +0,005 °C: sterownik zgłasza nową wartość, ale stan zostaje (to samo, niezmienione odwzorowanie).
        session.calibration.write(1, 4, record.copy(true1Tenths = record.true1Tenths + 0.05f))
        runCurrent()
        assertEquals(before, session.repository.state.value.analogInputs)
        assertTrue(before[InputKey(1, 4)] == session.repository.state.value.analogInputs[InputKey(1, 4)])
    }

    @Test
    fun theSessionExposesTheCalibrationPortOfItsConnection() = runTest {
        val session = session()
        val readout = session.calibration.read(1)
        assertTrue(readout is CalibrationReadout.Success)
        assertEquals(InputCalibration.MAX_INPUT + 1, (readout as CalibrationReadout.Success).records.size)
        assertEquals(InputCalibration.INACTIVE, readout.records[1])
        assertTrue(readout.records[0].point1Active)
        assertTrue(session.calibration.read(9) is CalibrationReadout.Failure)
    }
}
