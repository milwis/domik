package app.domik.demo

import app.domik.deimic.protocol.HeatingProfile
import app.domik.deimic.protocol.ShutterDirection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HouseModelTest {
    private val home = DemoHome.standard()
    private val simulation = DemoSimulation.Manual
    private val model = HouseModel(home, simulation)

    private fun zone(module: Int, nr: Int) = checkNotNull(model.zone(module, nr)) { "zone $module:$nr" }

    private fun hours(count: Int) = count * 3600.0

    @Test
    fun startsFromTheStateOfTheHome() {
        val salon = zone(1, 0)
        assertEquals(HeatingProfile.Comfort, salon.profile)
        assertEquals(22.0f, salon.setpoint)
        assertEquals(21.4f, salon.current)
        assertTrue(salon.heating, "21.4 is below the setpoint 22.0")
        assertFalse(zone(1, 1).heating, "the bedroom is warmer than its eco setpoint")
        assertTrue(zone(1, 2).heating)
        assertFalse(zone(2, 0).heating, "an off zone never heats")
        assertTrue(model.isOn(1, 0) && model.isOn(1, 5) && model.isOn(1, 11))
        assertFalse(model.isOn(1, 1))
        assertFalse(model.isOn(9, 0))
    }

    private fun shutter(module: Int, nr: Int) = checkNotNull(model.shutter(module, nr)) { "shutter $module:$nr" }

    @Test
    fun shuttersStartStandingAndAreAddressedByModuleAndNumber() {
        assertEquals(5, model.shutters.size)
        assertTrue(model.shutters.all { it.direction == ShutterDirection.STOPPED })
        assertEquals("Okno", shutter(1, 0).spec.name)
        assertEquals("Okno", shutter(2, 0).spec.name)
        assertEquals(1, shutter(1, 0).roomId)
        assertEquals(2, shutter(2, 0).roomId)
        assertNull(model.shutter(2, 1))
        assertNull(model.shutter(3, 0))
    }

    @Test
    fun aShutterIsLikeAOneButtonRemote() {
        val window = shutter(1, 0)
        assertEquals(ShutterDirection.MOVING_UP, model.press(window, DemoShutterButton.UP))
        // Any direction button during motion stops; there is no reversal.
        assertEquals(ShutterDirection.STOPPED, model.press(window, DemoShutterButton.UP))
        assertEquals(ShutterDirection.MOVING_DOWN, model.press(window, DemoShutterButton.DOWN))
        assertEquals(ShutterDirection.STOPPED, model.press(window, DemoShutterButton.UP), "the opposite direction also stops")
        assertEquals(ShutterDirection.MOVING_DOWN, model.press(window, DemoShutterButton.DOWN))
        assertEquals(ShutterDirection.STOPPED, model.press(window, DemoShutterButton.STOP))
        assertEquals(ShutterDirection.STOPPED, model.press(window, DemoShutterButton.STOP), "stop on a standing shutter")
        // Other shutters are untouched.
        assertEquals(ShutterDirection.STOPPED, shutter(1, 1).direction)
        assertEquals(ShutterDirection.STOPPED, shutter(2, 0).direction)
    }

    @Test
    fun aMovingShutterArrivesAtTheEndItWasHeadingFor() {
        val up = shutter(1, 0)
        val down = shutter(1, 1)
        model.press(up, DemoShutterButton.UP)
        model.press(down, DemoShutterButton.DOWN)
        assertEquals(ShutterDirection.AT_TOP, model.arrive(up))
        assertEquals(ShutterDirection.AT_BOTTOM, model.arrive(down))
        assertEquals(ShutterDirection.AT_TOP, up.direction)
        assertNull(model.arrive(up), "an arrived shutter does not arrive again")
        assertNull(model.arrive(shutter(1, 2)), "a standing shutter has nothing to arrive at")
        // From an end position a direction button starts the shutter again.
        assertEquals(ShutterDirection.MOVING_DOWN, model.press(up, DemoShutterButton.DOWN))
    }

    @Test
    fun aHeatingZoneWarmsUpTowardsTheSetpointAndHoldsIt() {
        val salon = zone(1, 0)
        model.advance(60.0 * 5)
        assertTrue(salon.current > 21.4f, "warmer after five minutes: ${salon.current}")
        assertTrue(salon.current < 22.0f)

        model.advance(hours(3))
        // Holds the setpoint within the hysteresis band (plus the overshoot of one step).
        assertTrue(salon.current in 21.75f..22.05f, "held around 22: ${salon.current}")
        repeat(200) {
            model.advance(60.0)
            assertTrue(salon.current in 21.75f..22.05f, "stays in the band: ${salon.current}")
        }
    }

    @Test
    fun aZoneAboveItsSetpointCoolsDownWithoutHeatingUntilItDropsBelowTheBand() {
        val bedroom = zone(1, 1) // eco 18.0, now 19.2
        model.advance(60.0)
        assertFalse(bedroom.heating)
        assertTrue(bedroom.current < 19.2f)
        model.advance(hours(24))
        assertTrue(bedroom.current in 17.75f..18.05f, "settles around its eco setpoint: ${bedroom.current}")
    }

    @Test
    fun anOffZoneCoolsTowardsTheAmbientTemperature() {
        val study = zone(2, 0)
        model.advance(hours(48))
        assertFalse(study.heating)
        assertEquals(simulation.ambientCelsius, study.current, 0.05f)
    }

    @Test
    fun changingTheSetpointReevaluatesHeatingImmediately() {
        val bedroom = zone(1, 1)
        assertFalse(bedroom.heating)
        model.setSetpoint(bedroom, 22.0f)
        assertTrue(bedroom.heating)
        model.setSetpoint(bedroom, 15.0f)
        assertFalse(bedroom.heating)
    }

    @Test
    fun everyProfileKeepsItsOwnSetpoint() {
        val bedroom = zone(1, 1)
        model.setSetpoint(bedroom, 17.5f) // eco
        model.setProfile(bedroom, HeatingProfile.Comfort)
        assertEquals(21.0f, bedroom.setpoint, "comfort keeps its own value")
        assertTrue(bedroom.heating)
        model.setSetpoint(bedroom, 22.5f)
        model.setProfile(bedroom, HeatingProfile.Eco)
        assertEquals(17.5f, bedroom.setpoint)
        model.setProfile(bedroom, HeatingProfile.Off)
        assertFalse(bedroom.heating)
        model.setProfile(bedroom, HeatingProfile.Comfort)
        assertEquals(22.5f, bedroom.setpoint)
    }

    @Test
    fun theSimulationIsDeterministic() {
        val other = HouseModel(DemoHome.standard(), simulation)
        model.advance(hours(5))
        other.advance(hours(5))
        assertEquals(model.zones.map { it.current }, other.zones.map { it.current })
        assertEquals(model.zones.map { it.heating }, other.zones.map { it.heating })
    }

    @Test
    fun relaysOfUnknownModulesOrOutsideTheRangeAreRejected() {
        assertFalse(model.setOn(9, 0, true))
        assertFalse(model.setOn(1, 128, true))
        assertFalse(model.setOn(1, -1, true))
        assertTrue(model.setOn(1, 127, true))
        assertTrue(model.isOn(1, 127))
    }

    @Test
    fun sensorsReportOnlyWhenTheValueMoved() {
        assertTrue(model.takeChangedSensors().isEmpty())
        model.advance(60.0 * 10)
        val changed = model.takeChangedSensors()
        assertTrue(changed.isNotEmpty())
        assertTrue(changed.none { it.module == 2 && it.number == 1 }, "the outdoor sensor does not move")
        assertTrue(model.takeChangedSensors().isEmpty(), "nothing new right after a report")
    }

    @Test
    fun zoneSensorsShareTheZoneNumberAndRoom() {
        val sensor = checkNotNull(model.sensor(1, 2))
        assertEquals(zone(1, 2).current, sensor.temperature)
        assertEquals(7, sensor.roomId)
        assertEquals(2, sensor.levelId)
        assertEquals(listOf(1 to 0, 1 to 1, 1 to 2, 2 to 0, 2 to 1), model.sensors.map { it.module to it.number })
    }
}
