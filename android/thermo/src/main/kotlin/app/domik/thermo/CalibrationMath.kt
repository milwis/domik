package app.domik.thermo

import app.domik.deimic.protocol.InputCalibration
import kotlin.math.roundToLong

/**
 * Arithmetic on the controller's analog-input calibration records ([InputCalibration], values in tenths of °C).
 *
 * The controller reports `f(raw)`, where `raw` is the uncalibrated value of the input: no active point = `raw`,
 * one active point (1 or 2) = `raw + (true - measured)`, two active points = the line through both points
 * (confirmed on a live controller). Two active points with equal measured values are treated as
 * point 1 alone, as in `InputCalibration.correctTenths`. Everything here works in tenths of °C, as stored in the record.
 */
object CalibrationMath {
    /** The value reported for the uncalibrated value [rawTenths]. */
    fun apply(record: InputCalibration, rawTenths: Double): Double = when {
        isTwoPoint(record) -> {
            val slope = (record.true2Tenths.toDouble() - record.true1Tenths) / (record.measured2Tenths.toDouble() - record.measured1Tenths)
            record.true1Tenths + (rawTenths - record.measured1Tenths) * slope
        }
        record.point1Active -> rawTenths + (record.true1Tenths.toDouble() - record.measured1Tenths)
        record.point2Active -> rawTenths + (record.true2Tenths.toDouble() - record.measured2Tenths)
        else -> rawTenths
    }

    /** The uncalibrated value behind the reported [reportedTenths]; null when the record cannot be inverted (flat line). */
    fun invert(record: InputCalibration, reportedTenths: Double): Double? = when {
        isTwoPoint(record) -> {
            val rise = record.true2Tenths.toDouble() - record.true1Tenths
            if (rise == 0.0) {
                null
            } else {
                record.measured1Tenths + (reportedTenths - record.true1Tenths) * (record.measured2Tenths.toDouble() - record.measured1Tenths) / rise
            }
        }
        record.point1Active -> reportedTenths - (record.true1Tenths.toDouble() - record.measured1Tenths)
        record.point2Active -> reportedTenths - (record.true2Tenths.toDouble() - record.measured2Tenths)
        else -> reportedTenths
    }

    /**
     * True when the values of every active point are finite and a two-point line is not flat, i.e. the record
     * can be used and shifted. Empty slots of the controller (NaN values, no active point) are usable: they mean
     * "no calibration".
     */
    fun isUsable(record: InputCalibration): Boolean {
        if (record.point1Active && !(record.measured1Tenths.isFinite() && record.true1Tenths.isFinite())) return false
        if (record.point2Active && !(record.measured2Tenths.isFinite() && record.true2Tenths.isFinite())) return false
        return !(isTwoPoint(record) && record.true1Tenths == record.true2Tenths)
    }

    /** Byte-exact comparison (as sent / read with 0x132 / 0x131); NaN equals NaN with the same bits. */
    fun same(a: InputCalibration?, b: InputCalibration?): Boolean =
        if (a == null || b == null) a === b else a.encode() == b.encode()

    /** How much [record] raises the reported value against [baseline] at [rawTenths] (tenths of °C). */
    fun shiftFromBaselineTenths(record: InputCalibration, baseline: InputCalibration, rawTenths: Double): Double =
        apply(record, rawTenths) - apply(baseline, rawTenths)

    /**
     * A record that reports `deltaTenths` more than [record] (rounded to whole tenths of °C):
     * - active point(s): the true value of every active point moves by the same amount (one point = a new offset,
     *   two points = the line moves and keeps its slope); measured values and the active bytes stay as they are;
     * - no active point: point 1 becomes active with `measured1 = round(rawTenths)` and `true1 = measured1 + round(delta)`.
     * The true value of the first active point ends on a whole tenth. Returns [record] itself when the rounded change is 0.
     */
    fun shifted(record: InputCalibration, rawTenths: Double, deltaTenths: Double): InputCalibration {
        if (!record.point1Active && !record.point2Active) {
            val step = deltaTenths.roundToLong()
            if (step == 0L) return record
            val measured = rawTenths.roundToLong()
            return record.copy(active1 = 1u, measured1Tenths = measured.toFloat(), true1Tenths = (measured + step).toFloat())
        }
        val reference = if (record.point1Active) record.true1Tenths.toDouble() else record.true2Tenths.toDouble()
        val applied = (reference + deltaTenths).roundToLong() - reference
        if (applied == 0.0) return record
        return record.copy(
            true1Tenths = if (record.point1Active) (record.true1Tenths + applied).toFloat() else record.true1Tenths,
            true2Tenths = if (record.point2Active) (record.true2Tenths + applied).toFloat() else record.true2Tenths,
        )
    }

    private fun isTwoPoint(record: InputCalibration): Boolean =
        record.point1Active && record.point2Active && record.measured1Tenths != record.measured2Tenths
}
