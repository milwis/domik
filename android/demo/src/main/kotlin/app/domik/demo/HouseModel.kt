package app.domik.demo

import app.domik.deimic.protocol.HeaterState
import app.domik.deimic.protocol.HeatingProfile
import app.domik.deimic.protocol.InputCalibration
import app.domik.deimic.protocol.ShutterDirection
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How the simulated house behaves over time. The defaults make a zone's temperature visibly move within a minute
 * of looking at it (see [timeScale]) while still looking like slow floor heating.
 */
data class DemoSimulation(
    /** Run the background simulation (a tick every [tickInterval] of real time). Tests use `advance` instead. */
    val enabled: Boolean = true,
    /** Real time between two simulation steps. */
    val tickInterval: Duration = 5.seconds,
    /** Simulated seconds per real second: 5 means one real minute is five minutes of house time. */
    val timeScale: Double = 5.0,
    /** Temperature gain of a heating zone in kelvin per simulated minute. */
    val heatingKelvinPerMinute: Double = 0.12,
    /** Heat loss: fraction of the difference to [ambientCelsius] lost per simulated minute. */
    val lossPerMinute: Double = 0.008,
    /** Temperature a zone cools down towards (rest of the house / outdoors). */
    val ambientCelsius: Float = 17.0f,
    /** A zone starts heating when it is this far below its setpoint, and stops at the setpoint. */
    val hysteresis: Float = 0.2f,
    /** A sensor sends a temperature report (0x72) when its value moved by at least this much. */
    val reportStep: Float = 0.1f,
    /**
     * How long a shutter runs before it arrives at its end position by itself and reports it. Real time (not scaled
     * by [timeScale]): a controller with firmware "Dec 13 2017" reports the arrival after about 21 s.
     */
    val shutterTravel: Duration = 20.seconds,
) {
    init {
        require(tickInterval.isPositive()) { "tickInterval must be positive" }
        require(timeScale > 0.0 && timeScale.isFinite()) { "timeScale must be positive" }
        require(heatingKelvinPerMinute >= 0.0 && lossPerMinute >= 0.0) { "rates must not be negative" }
        require(hysteresis >= 0f && reportStep > 0f) { "invalid thresholds" }
        require(shutterTravel.isPositive()) { "shutterTravel must be positive" }
    }

    companion object {
        /** No background simulation – state changes only through commands and `FakeController.advance`. */
        val Manual: DemoSimulation = DemoSimulation(enabled = false)
    }
}

/** A button of a shutter: what a wall switch or the app presses. */
enum class DemoShutterButton { UP, DOWN, STOP }

/**
 * State of the simulated house: output relays, heating zones, shutters and temperature sensors, plus the physics that
 * moves the temperatures. Pure data and arithmetic – no frames, no coroutines, no locking (the owner serializes access).
 */
internal class HouseModel(val home: DemoHome, private val simulation: DemoSimulation) {
    class Zone(val spec: DemoZone, val levelId: Int) {
        val module: Int get() = spec.module
        val outputNr: Int get() = spec.outputNr
        val roomId: Int get() = spec.roomId

        var profile: HeatingProfile = spec.profile
            internal set

        /** Setpoint per profile, indexed by the profile's raw value (off, eco, comfort, maximum). */
        private val setpoints = floatArrayOf(spec.off, spec.eco, spec.comfort, spec.maximum)

        var current: Float = spec.current
            internal set

        var heating: Boolean = spec.profile != HeatingProfile.Off && spec.current < setpoints[spec.profile.raw.toInt()]
            internal set

        /** Setpoint of the active profile. */
        var setpoint: Float
            get() = setpoints[profile.raw.toInt()]
            internal set(value) {
                setpoints[profile.raw.toInt()] = value
            }

        fun state(): HeaterState = HeaterState(
            module = module.toUInt(),
            outputNr = outputNr.toUByte(),
            isHeating = heating,
            currentTemperature = current,
            profile = profile,
            setpoint = setpoint,
        )
    }

    /**
     * A shutter. [direction] is what the controller would report for it: standing, moving, or arrived at an end. The
     * firmware behaves like a one-button remote: a direction command during motion – the same or the opposite
     * one – stops the shutter; there is no position.
     */
    class Shutter(val spec: DemoShutter) {
        val module: Int get() = spec.module
        val outputNr: Int get() = spec.outputNr
        val roomId: Int get() = spec.roomId

        var direction: ShutterDirection = ShutterDirection.STOPPED
            internal set
    }

    /**
     * A temperature sensor on the analog input [number] of its module: the sensor of a [zone] (the zone's
     * [DemoZone.inputNr], which is not its `output_nr`) or a free-standing one.
     */
    class Sensor(
        val module: Int,
        val number: Int,
        val levelId: Int,
        val roomId: Int,
        internal val zone: Zone?,
        fixed: Float,
    ) {
        /** Value of a free-standing sensor (a zone's sensor reports the zone's temperature). */
        internal var fixed: Float = fixed

        /** The value the controller reports, i.e. after the input's calibration. */
        val temperature: Float get() = zone?.current ?: fixed

        /** The value the clients were last told about (a report or a readout). */
        var reported: Float = temperature
            internal set
    }

    private val outputs: Map<Int, BooleanArray> = home.modules.associateWith { BooleanArray(OUTPUTS_PER_MODULE) }

    val zones: List<Zone> = home.zones.map { Zone(it, home.levelOf(it.roomId)) }

    val shutters: List<Shutter> = home.shutters.map(::Shutter)

    val sensors: List<Sensor> = (
        zones.map { Sensor(it.module, it.spec.inputNr, it.levelId, it.roomId, it, 0f) } +
            home.sensors.map { Sensor(it.module, it.number, home.levelOf(it.roomId), it.roomId, null, it.temperature) }
        ).sortedWith(compareBy({ it.module }, { it.number }))

    /**
     * Calibration table per module: [InputCalibration.MAX_INPUT] + 1 records, like 0x131 returns them. Initially a
     * sensor on an input 0..11 has a one-point calibration of -2.5 °C that **leaves the reported temperature as it
     * is**: the raw value of the sensor is 2.5 °C ([RAW_BIAS]) above the true temperature of the room, and the
     * calibration takes it off again; the other inputs are inactive.
     */
    private val calibrations: Map<Int, Array<InputCalibration>> = home.modules.associateWith { module ->
        Array(InputCalibration.MAX_INPUT + 1) { input ->
            sensors.firstOrNull { it.module == module && it.number == input }?.let {
                InputCalibration.onePoint(measuredCelsius = it.temperature + RAW_BIAS, trueCelsius = it.temperature)
            } ?: InputCalibration.INACTIVE
        }
    }

    init {
        home.outputs.filter { it.initiallyOn }.forEach { outputs.getValue(it.module)[it.relay] = true }
    }

    val modules: List<Int> get() = home.modules

    fun hasModule(module: Int): Boolean = module in outputs

    /** True when the relay is on; false for a relay that is off or does not exist. */
    fun isOn(module: Int, relay: Int): Boolean = outputs[module]?.getOrNull(relay) ?: false

    /** Sets a relay. Returns false (and changes nothing) for an unknown module or a relay outside 0..127. */
    fun setOn(module: Int, relay: Int, on: Boolean): Boolean {
        val relays = outputs[module] ?: return false
        if (relay !in relays.indices) return false
        relays[relay] = on
        return true
    }

    fun zone(module: Int, outputNr: Int): Zone? = zones.firstOrNull { it.module == module && it.outputNr == outputNr }

    fun shutter(module: Int, outputNr: Int): Shutter? =
        shutters.firstOrNull { it.module == module && it.outputNr == outputNr }

    /**
     * Presses [button] of [shutter] and returns the new direction: STOP, and any direction button while the shutter
     * moves, stop it; a direction button on a standing shutter starts it (up or down).
     */
    fun press(shutter: Shutter, button: DemoShutterButton): ShutterDirection {
        shutter.direction = when {
            button == DemoShutterButton.STOP || shutter.direction.isMoving -> ShutterDirection.STOPPED
            button == DemoShutterButton.UP -> ShutterDirection.MOVING_UP
            else -> ShutterDirection.MOVING_DOWN
        }
        return shutter.direction
    }

    /** The moving [shutter] reaches its end position; returns the new direction, or null when it was not moving. */
    fun arrive(shutter: Shutter): ShutterDirection? {
        shutter.direction = when (shutter.direction) {
            ShutterDirection.MOVING_UP -> ShutterDirection.AT_TOP
            ShutterDirection.MOVING_DOWN -> ShutterDirection.AT_BOTTOM
            else -> return null
        }
        return shutter.direction
    }

    /** The calibration table of a module (records of inputs 0..11), or null for an unknown module. */
    fun calibrations(module: Int): List<InputCalibration>? = calibrations[module]?.toList()

    /**
     * Replaces the calibration of an input. The sensor on that input (same number) reports the raw value run through
     * the new calibration from now on: `reported = new(raw)`, where `raw` is recovered from the value reported until
     * now with the old calibration. Returns false (changing nothing) for an unknown module, an input above
     * [InputCalibration.MAX_INPUT] or an active point with a non-finite value.
     */
    fun setCalibration(module: Int, input: Int, record: InputCalibration): Boolean {
        val table = calibrations[module] ?: return false
        if (input !in table.indices) return false
        val points = listOfNotNull(
            (record.measured1Tenths to record.true1Tenths).takeIf { record.point1Active },
            (record.measured2Tenths to record.true2Tenths).takeIf { record.point2Active },
        )
        if (points.any { !it.first.isFinite() || !it.second.isFinite() }) return false
        val old = table[input]
        table[input] = record
        val sensor = sensor(module, input) ?: return true
        val corrected = record.correctCelsius(rawOf(old, sensor.temperature))
        if (!corrected.isFinite()) return true
        val zone = sensor.zone
        if (zone != null) setCurrent(zone, corrected) else sensor.fixed = corrected
        return true
    }

    /** The raw sensor value behind a [reported] one, undoing [calibration] (which is linear). */
    private fun rawOf(calibration: InputCalibration, reported: Float): Float {
        if (!calibration.point1Active && !calibration.point2Active) return reported
        val atZero = calibration.correctTenths(0f)
        val slope = calibration.correctTenths(1f) - atZero
        if (slope == 0f || !slope.isFinite()) return reported
        return (reported * 10f - atZero) / slope / 10f
    }

    /**
     * The true temperature of the room of [zone] – what an independent thermometer would show – as opposed to
     * [Zone.current], which is what the controller reports (the raw sensor value through the input's calibration).
     * A change of the calibration moves the reported value but not this one; the physics moves both.
     */
    fun roomTemperature(zone: Zone): Float {
        val table = calibrations[zone.module] ?: return zone.current
        val calibration = table.getOrNull(zone.spec.inputNr) ?: return zone.current
        return rawOf(calibration, zone.current) - RAW_BIAS
    }

    /** The sensor on the analog input [number] of [module] (not the zone with that `output_nr`). */
    fun sensor(module: Int, number: Int): Sensor? = sensors.firstOrNull { it.module == module && it.number == number }

    fun sensorsOf(module: Int): List<Sensor> = sensors.filter { it.module == module }

    /** Sets the setpoint of the zone's active profile and re-evaluates whether it heats. */
    fun setSetpoint(zone: Zone, celsius: Float) {
        zone.setpoint = celsius
        updateHeating(zone)
    }

    fun setProfile(zone: Zone, profile: HeatingProfile) {
        zone.profile = profile
        updateHeating(zone)
    }

    fun setCurrent(zone: Zone, celsius: Float) {
        zone.current = celsius
        updateHeating(zone)
    }

    /** Moves the house forward by [seconds] of simulated time. */
    fun advance(seconds: Double) {
        require(seconds >= 0.0) { "cannot go back in time" }
        var remaining = seconds
        while (remaining > 0.0) {
            val step = minOf(remaining, MAX_STEP_SECONDS)
            zones.forEach { step(it, step / 60.0) }
            remaining -= step
        }
    }

    /** Sensors whose value moved by at least [DemoSimulation.reportStep] since they were last reported; marks them reported. */
    fun takeChangedSensors(): List<Sensor> =
        sensors.filter { abs(it.temperature - it.reported) >= simulation.reportStep - EPSILON }.onEach { it.reported = it.temperature }

    /** Marks every sensor as reported (the clients just read all values). */
    fun markAllReported() {
        sensors.forEach { it.reported = it.temperature }
    }

    private fun step(zone: Zone, minutes: Double) {
        updateHeating(zone)
        val gain = if (zone.heating) simulation.heatingKelvinPerMinute else 0.0
        val loss = simulation.lossPerMinute * (zone.current - simulation.ambientCelsius)
        zone.current += ((gain - loss) * minutes).toFloat()
        updateHeating(zone)
    }

    /** Off never heats; otherwise heat below `setpoint - hysteresis` until the setpoint is reached. */
    private fun updateHeating(zone: Zone) {
        zone.heating = when {
            zone.profile == HeatingProfile.Off -> false
            zone.current >= zone.setpoint -> false
            zone.current <= zone.setpoint - simulation.hysteresis -> true
            else -> zone.heating
        }
    }

    companion object {
        const val OUTPUTS_PER_MODULE: Int = 128
        const val SENSORS_PER_MODULE: Int = 128

        /** How much the raw value of a sensor exceeds the true temperature, before the initial calibration removes it. */
        const val RAW_BIAS: Float = 2.5f

        /** Longest physics step; keeps long jumps accurate. */
        private const val MAX_STEP_SECONDS = 10.0
        private const val EPSILON = 1e-4f
    }
}
