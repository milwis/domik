package app.domik.deimic.client.commands

import app.domik.deimic.protocol.FrameAddress
import kotlin.math.roundToInt

/** Value ranges enforced before anything is sent to the controller. */
object CommandLimits {
    /** Lowest setpoint accepted by [DeviceCommands.setSetpoint] (the controller UI itself allows 0–40 °C). */
    const val MIN_SETPOINT_CELSIUS: Float = 5.0f

    const val MAX_SETPOINT_CELSIUS: Float = 30.0f

    /** Setpoints are sent in steps of this size. */
    const val SETPOINT_STEP_CELSIUS: Float = 0.5f

    /** Output relays are indexed 0..127 (the `outputs` array of the collective state has 128 entries). */
    const val MAX_OUTPUT_RELAY: Int = 127

    /** Heating zones (`output_nr`) are indexed 0..31 (the `heaters` array has 32 entries). */
    const val MAX_ZONE_NUMBER: Int = 31

    /** Shutters (`output_nr`) are indexed 0..63 (the `shutterStates` array of the collective state has 64 entries). */
    const val MAX_SHUTTER_NUMBER: Int = 63

    /**
     * Highest module address (`id_device`) accepted. Address 0 (the controller itself) and the broadcast address
     * are never valid targets of an output or heating command.
     */
    const val MAX_MODULE_ADDRESS: Int = 0xFFFF

    /** Tolerance when comparing a setpoint read back from the controller with the requested one. */
    const val SETPOINT_TOLERANCE_CELSIUS: Float = 0.01f

    fun isValidModule(module: Int): Boolean =
        module in 1..MAX_MODULE_ADDRESS && module.toUInt() != FrameAddress.BROADCAST

    fun isValidRelay(relay: Int): Boolean = relay in 0..MAX_OUTPUT_RELAY

    fun isValidZone(outputNr: Int): Boolean = outputNr in 0..MAX_ZONE_NUMBER

    fun isValidShutter(outputNr: Int): Boolean = outputNr in 0..MAX_SHUTTER_NUMBER

    /** True for finite values in [MIN_SETPOINT_CELSIUS]..[MAX_SETPOINT_CELSIUS] (before rounding). */
    fun isValidSetpoint(celsius: Float): Boolean =
        celsius.isFinite() && celsius in MIN_SETPOINT_CELSIUS..MAX_SETPOINT_CELSIUS

    /** Rounds to the nearest [SETPOINT_STEP_CELSIUS] (ties up), e.g. 21.3 -> 21.5, 21.2 -> 21.0. */
    fun roundToStep(celsius: Float): Float = (celsius / SETPOINT_STEP_CELSIUS).roundToInt() * SETPOINT_STEP_CELSIUS

    /** Clamps to the allowed range and rounds to the step – for UI input such as +/- buttons. */
    fun clampToStep(celsius: Float): Float =
        roundToStep(celsius.coerceIn(MIN_SETPOINT_CELSIUS, MAX_SETPOINT_CELSIUS))
}
