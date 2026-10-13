package app.domik.thermo

import app.domik.deimic.protocol.InputCalibration
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Tunable rules of [CalibrationCorrector]; the defaults are the ones from `notes/THERMOSTATS.md` §3. */
data class CalibrationParams(
    /** A new calibration is made when `|avg(Xiaomi) - avg(Deimic)|` is strictly greater than this (°C). */
    val threshold: Float = 0.5f,
    /** Both temperatures are averaged over this window. */
    val averagingWindow: Duration = 5.minutes,
    /** A window average needs at least this many samples of each sensor. */
    val minSamplesPerWindow: Int = 2,
    /** Minimum time between two calibration writes (also the retry delay after a failed write). */
    val minWriteInterval: Duration = 15.minutes,
    /** Deimic samples taken earlier than this after a confirmed write are ignored (they may predate the new calibration). */
    val settleTime: Duration = 30.seconds,
    /** Without a valid Xiaomi sample for this long the sensor counts as lost and the correction is held (nothing is written or restored). */
    val staleTimeout: Duration = 30.minutes,
    /** A requested write that is neither confirmed nor failed for this long counts as failed. */
    val writeTimeout: Duration = 1.minutes,
    /** The calibration may raise or lower the reported temperature by at most this much against the baseline (°C). */
    val maxShiftFromBaseline: Float = 5.0f,
    /** Samples outside this range (°C) are dropped, as are NaN and infinite ones. */
    val minValidTemp: Float = 5f,
    val maxValidTemp: Float = 35f,
    /** A Xiaomi battery at or below this level counts like a lost sensor (correction held); an unknown level never does. */
    val criticalBatteryPercent: Int = 5,
    /** When true nothing is meant to be written: [CalibrationCorrector.evaluate] returns [CalibrationDecision.WouldWrite]. */
    val dryRun: Boolean = true,
    /**
     * Dry run only: thresholds simulated side by side (each with its own simulated calibration) to measure how
     * often a calibration would be written ([CalibrationCorrector.frequency]). [threshold] is always simulated.
     */
    val comparisonThresholds: List<Float> = listOf(0.5f, 1.0f),
    /** Log entries older than this are dropped (the counters keep counting). */
    val logRetention: Duration = 7.days,
) {
    init {
        require(threshold >= 0f && threshold.isFinite()) { "threshold must be >= 0" }
        require(comparisonThresholds.all { it >= 0f && it.isFinite() }) { "comparison thresholds must be >= 0" }
        require(averagingWindow.isPositive()) { "averagingWindow must be positive" }
        require(minSamplesPerWindow >= 1) { "minSamplesPerWindow must be >= 1" }
        require(minWriteInterval >= Duration.ZERO) { "minWriteInterval must not be negative" }
        require(settleTime >= Duration.ZERO) { "settleTime must not be negative" }
        require(staleTimeout.isPositive()) { "staleTimeout must be positive" }
        require(writeTimeout.isPositive()) { "writeTimeout must be positive" }
        require(maxShiftFromBaseline >= 0f && maxShiftFromBaseline.isFinite()) { "maxShiftFromBaseline must be >= 0" }
        require(minValidTemp < maxValidTemp) { "minValidTemp must be below maxValidTemp" }
        require(criticalBatteryPercent in 0..100) { "criticalBatteryPercent must be in 0..100" }
        require(logRetention.isPositive()) { "logRetention must be positive" }
    }
}

/** Why [CalibrationCorrector] writes (or would write) a calibration record. */
enum class CalibrationReason {
    /** The averaged Xiaomi and Deimic temperatures differ by more than the threshold. */
    DIFFERENCE,

    /**
     * Legacy: before 2026-10-12 a lost sensor (or critical battery) restored the baseline. No longer produced;
     * kept so that old log files still parse.
     */
    SENSOR_LOST,

    /** The function or the zone was switched off: the baseline is restored. */
    DISABLED,
}

/** Why [CalibrationCorrector.evaluate] does nothing. */
enum class CalibrationIdleReason {
    /** No 0x131 read yet ([CalibrationCorrector.onCalibrationRead]). */
    NO_CALIBRATION,

    /** The record on the controller (or the baseline) has non-finite values or a flat two-point line. */
    CALIBRATION_UNUSABLE,

    /** Switched off and the baseline is in place. */
    DISABLED,

    /** Xiaomi lost or battery critical: the last calibration stays on the controller, the correction is held (no writes). */
    SENSOR_LOST,

    /** Not enough valid Xiaomi samples in the window yet. */
    NO_XIAOMI_DATA,

    /** Not enough valid Deimic samples in the window (counted after the settle time of the last write). */
    NO_DEIMIC_DATA,

    /** The difference is at most the threshold. */
    WITHIN_THRESHOLD,

    /** A write is wanted but the last attempt was less than `minWriteInterval` ago. */
    RATE_LIMITED,

    /** A write is wanted but the shift against the baseline already is at `maxShiftFromBaseline`. */
    AT_LIMIT,

    /** The previous [CalibrationDecision.Write] is neither confirmed nor failed yet. */
    WRITE_IN_FLIGHT,
}

/**
 * Finer cause of an [CalibrationDecision.Idle] that blocks a write which the rule wants (added 2026-10-12 after the
 * code review; the [CalibrationIdleReason] stays the closest older value, [CalibrationIdleReason.RATE_LIMITED]).
 */
enum class CalibrationIdleDetail {
    /** A write failed (no echo): the frame may have arrived, so no new write before a successful 0x131 read. */
    READ_REQUIRED,

    /** The state (baseline, record to be written) could not be stored: nothing is sent without it. */
    STATE_NOT_SAVED,
}

/** Result of [CalibrationCorrector.evaluate]. */
sealed interface CalibrationDecision {
    data class Idle(val reason: CalibrationIdleReason, val detail: CalibrationIdleDetail? = null) : CalibrationDecision

    /**
     * Send 0x132 `[inputNr] + record.encode()` to the module, then call `onWritten(writeId)` (or `onWriteFailed(writeId)`).
     * [differenceC] is `avg(Xiaomi) - avg(Deimic)` (null for a restore); [clamped] tells that [record] was limited
     * by `maxShiftFromBaseline`.
     */
    data class Write(
        val writeId: Long,
        val inputNr: Int,
        val record: InputCalibration,
        val previous: InputCalibration,
        val reason: CalibrationReason,
        val differenceC: Float?,
        val clamped: Boolean,
    ) : CalibrationDecision

    /** Dry run only: what would have been written. Nothing must be sent. */
    data class WouldWrite(
        val inputNr: Int,
        val record: InputCalibration,
        val previous: InputCalibration,
        val reason: CalibrationReason,
        val differenceC: Float?,
        val clamped: Boolean,
    ) : CalibrationDecision
}

/** One (would-be) write, for the log used to measure how often calibrations happen. */
data class CalibrationLogEntry(
    val at: Long,
    val inputNr: Int,
    /** The threshold of the rule that decided; in dry run one of the simulated thresholds. */
    val threshold: Float,
    val dryRun: Boolean,
    val reason: CalibrationReason,
    val xiaomiC: Float?,
    /** The Deimic temperature the rule saw (in dry run: as it would be under the simulated calibration). */
    val deimicC: Float?,
    val differenceC: Float?,
    val previous: InputCalibration,
    val next: InputCalibration,
    val clamped: Boolean,
)

/** How many (would-be) writes one threshold produced. */
data class CalibrationFrequency(
    val threshold: Float,
    /** True for `CalibrationParams.threshold` (the rule whose decisions [CalibrationCorrector.evaluate] returns). */
    val primary: Boolean,
    val dryRun: Boolean,
    /** Counted since this time (creation of the corrector). */
    val since: Long,
    val calibrations: Int,
    val restores: Int,
    val calibrationsLastHour: Int,
    val calibrationsLast24h: Int,
)

/** What the UI shows for one zone. */
data class CalibrationState(
    val inputNr: Int,
    val enabled: Boolean,
    val dryRun: Boolean,
    /** Averaged Xiaomi temperature (the one that counts), null without valid samples in the window. */
    val xiaomiC: Float?,
    /** Averaged temperature reported by the controller for this input. */
    val deimicC: Float?,
    /** `xiaomiC - deimicC`. */
    val differenceC: Float?,
    val sensorFresh: Boolean,
    val batteryPercent: Int?,
    /** Last record known on the controller (read or confirmed write). */
    val controllerRecord: InputCalibration?,
    val baseline: InputCalibration?,
    /** Dry run: the simulated record of the primary threshold; live: same as [controllerRecord]. */
    val plannedRecord: InputCalibration?,
    /** How much [plannedRecord] shifts the reading against [baseline] (°C), at the current reading. */
    val shiftFromBaselineC: Float?,
    val writeInFlight: Boolean,
    /** A failed write waits for a 0x131 read before anything else is written. */
    val readRequired: Boolean = false,
    /** The last attempt to store the state failed (writes are held back until it succeeds). */
    val stateNotSaved: Boolean = false,
)
