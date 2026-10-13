package app.domik.thermo

import app.domik.deimic.protocol.InputCalibration
import kotlin.math.abs
import kotlin.math.sign

/**
 * Keeps the calibration of one analog input of the controller (the temperature sensor of one heating zone) such
 * that the controller reads what the zone's Xiaomi thermometer measures (`notes/THERMOSTATS.md` §0.1, §3).
 * The controller then displays and regulates the Xiaomi temperature itself and the setpoint stays the real one.
 *
 * Rule: `d = avg(Xiaomi) - avg(Deimic)` over [CalibrationParams.averagingWindow]; when `|d| > threshold` and the
 * last write was at least [CalibrationParams.minWriteInterval] ago, the record read from the controller (0x131) is
 * shifted by `d` (true value of every active point `+= d·10`, whole tenths; no active point → a new one-point record),
 * limited to [CalibrationParams.maxShiftFromBaseline] against the baseline. Deimic samples are the values the
 * controller reports (0x130 / 0x72 / 0x6B, already calibrated). When switched off ([setEnabled]) the baseline
 * (installer's calibration, see [CalibrationBaseline]) is written back once. Without a fresh Xiaomi reading for
 * [CalibrationParams.staleTimeout] or with a critical battery nothing is restored (owner's decision 2026-10-12):
 * the last calibration stays on the controller, the correction only pauses ([CalibrationIdleReason.SENSOR_LOST], no
 * writes) and continues from that calibration once fresh samples are back. The averaging window is shorter than
 * the stale timeout, so no sample from before the pause is mixed in.
 *
 * Pure logic: no I/O except [CalibrationBaselineStore] calls; time comes from [clock] (milliseconds, any consistent
 * timebase that does not go backwards). One instance serves one input. Thread-safe.
 *
 * Use from the app:
 * 1. feed [onXiaomiSample] (BLE advertisements) and [onDeimicSample] (controller reports for this input);
 * 2. call [onCalibrationRead] with the record from 0x131 on every (re)connection and after a failed write;
 * 3. call [evaluate] periodically (e.g. every 30–60 s); on [CalibrationDecision.Write] read 0x131 once more and give
 *    the record to [onCalibrationRead] – when the controller no longer holds what the write was computed from, the
 *    write is dropped ([isPending] false; with [BaselineEvent.EXTERNAL_CHANGE] the zone should be paused); a failed
 *    read means [abortWrite]. Only then send 0x132 and report the result with [onWritten] or [onWriteFailed].
 *    [CalibrationDecision.WouldWrite] (dry run) must not be sent.
 *
 * Safety (code review 2026-10-12): after a failed write nothing is written before a successful read (the frame may
 * have arrived); records sent without confirmation stay known as Domik's ([CalibrationBaselineState.unconfirmed]);
 * nothing is sent unless the record to be written was stored first; the time of the last write is stored, so a
 * corrector created again (restart, new session) keeps the minimum interval and the settle time.
 *
 * In dry run ([CalibrationParams.dryRun]) the controller record never changes, so each threshold (the primary
 * one and [CalibrationParams.comparisonThresholds]) runs on its own simulated record: the Deimic value it sees is
 * what the controller would report under that record. [frequency] and [log] give the measured write frequency.
 */
class CalibrationCorrector(
    val inputNr: Int,
    val params: CalibrationParams = CalibrationParams(),
    private val store: CalibrationBaselineStore,
    private val clock: () -> Long = System::currentTimeMillis,
    enabled: Boolean = true,
) {
    private class Sample(val at: Long, val value: Double)

    private class Pending(val id: Long, val record: InputCalibration, val since: Long)

    private sealed interface Proposal {
        class Skip(val reason: CalibrationIdleReason) : Proposal

        class Change(
            val record: InputCalibration,
            val reason: CalibrationReason,
            val xiaomiC: Double?,
            val deimicC: Double?,
            val differenceC: Double?,
            val clamped: Boolean,
        ) : Proposal
    }

    /** The calibration one threshold works with: the controller's record (live) or a simulated one (dry run). */
    private class Track(val threshold: Float, val primary: Boolean) {
        var record: InputCalibration? = null
        var lastAttemptAt: Long? = null
        var lastAttemptFailed = false
        var ignoreDeimicBefore = Long.MIN_VALUE
        var calibrations = 0
        var restores = 0
        val calibrationTimes = ArrayDeque<Long>()
        val log = ArrayDeque<CalibrationLogEntry>()
    }

    init {
        require(inputNr in 0..MAX_INPUT) { "inputNr must be in 0..$MAX_INPUT" }
    }

    private val windowMs = params.averagingWindow.inWholeMilliseconds
    private val minIntervalMs = params.minWriteInterval.inWholeMilliseconds
    private val settleMs = params.settleTime.inWholeMilliseconds
    private val staleMs = params.staleTimeout.inWholeMilliseconds
    private val writeTimeoutMs = params.writeTimeout.inWholeMilliseconds
    private val logRetentionMs = params.logRetention.inWholeMilliseconds

    private val startedAt = clock()
    private var enabled = enabled
    private val xiaomiSamples = ArrayList<Sample>()
    private val deimicSamples = ArrayList<Sample>()
    private var lastXiaomiAt: Long? = null
    private var batteryPercent: Int? = null

    private var baselineState: CalibrationBaselineState? = store.load(inputNr)
    private var controllerRecord: InputCalibration? = null
    private var pending: Pending? = null
    private var nextWriteId = 1L

    /** Set when a write failed, cleared by the next [onCalibrationRead]; while set nothing is written. */
    private var readRequired = false

    /** The last [CalibrationBaselineStore.save] failed: memory is ahead of the stored state. */
    private var stateNotSaved = false

    private val primary = Track(params.threshold, primary = true)
    private val tracks: List<Track> = if (params.dryRun) {
        listOf(primary) + params.comparisonThresholds.distinct().filter { it != params.threshold }.map { Track(it, primary = false) }
    } else {
        listOf(primary)
    }

    init {
        // A corrector created again (restart, new session, other mode) continues where the last write left off.
        baselineState?.lastWriteAt?.let { at ->
            tracks.forEach {
                it.lastAttemptAt = at
                it.ignoreDeimicBefore = at + settleMs
            }
        }
    }

    /** The baseline state as last given to the store (null before the first 0x131 read on a fresh install). */
    val baseline: CalibrationBaselineState?
        @Synchronized get() = baselineState

    /**
     * A Xiaomi reading taken at [at]. Returns false (sample dropped) for NaN / infinite values, values outside
     * `minValidTemp..maxValidTemp`, and samples older than the averaging window or from the future. A battery level
     * in 0..100 is recorded even when the temperature is dropped.
     */
    @Synchronized
    fun onXiaomiSample(tempC: Float, batteryPercent: Int? = null, at: Long = clock()): Boolean {
        if (batteryPercent != null && batteryPercent in 0..100) this.batteryPercent = batteryPercent
        if (!add(xiaomiSamples, tempC, at)) return false
        lastXiaomiAt = maxOf(lastXiaomiAt ?: at, at)
        return true
    }

    /** The temperature the controller reports for this input (calibrated), taken at [at]; validated like Xiaomi samples. */
    @Synchronized
    fun onDeimicSample(tempC: Float, at: Long = clock()): Boolean = add(deimicSamples, tempC, at)

    /**
     * The record of this input read with 0x131. Reconciles the stored baseline ([CalibrationBaseline.reconcile])
     * and saves it; [BaselineEvent.EXTERNAL_CHANGE] (calibration changed in the Configurator) should be shown to the user.
     * When the record differs from the one known so far, Deimic samples older than now + settle time are ignored and
     * the dry-run simulations restart from it.
     *
     * A [CalibrationDecision.Write] not sent yet stays valid only when [record] is what it was computed from (and
     * the event is not [BaselineEvent.EXTERNAL_CHANGE]); otherwise it is dropped ([isPending] turns false).
     */
    @Synchronized
    fun onCalibrationRead(record: InputCalibration): BaselineEvent {
        val now = clock()
        val reconciled = CalibrationBaseline.reconcile(baselineState, record)
        val previous = controllerRecord
        var state = reconciled.state
        pending?.let { inFlight ->
            when {
                CalibrationMath.same(record, inFlight.record) -> Unit // already there; the echo settles it
                reconciled.event != BaselineEvent.EXTERNAL_CHANGE && CalibrationMath.same(record, previous) ->
                    state = state.copy(pendingWrite = inFlight.record) // still to be sent: keep it known as Domik's
                else -> pending = null // computed from a record the controller no longer holds: never sent
            }
        }
        readRequired = false
        saveState(state)
        controllerRecord = record
        if (previous == null) {
            tracks.forEach { if (it.record == null || !params.dryRun) it.record = record }
        } else if (!CalibrationMath.same(previous, record)) {
            tracks.forEach {
                it.record = record
                it.ignoreDeimicBefore = maxOf(it.ignoreDeimicBefore, now + settleMs)
            }
        }
        return reconciled.event
    }

    /** Switching off (function or zone) restores the baseline on the next [evaluate]; switching on resumes the rule. */
    @Synchronized
    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    /** The [CalibrationDecision.Write] with [writeId] is still to be sent (not dropped, failed or confirmed). */
    @Synchronized
    fun isPending(writeId: Long): Boolean = pending?.id == writeId

    /**
     * The write with [writeId] was not sent at all (e.g. the 0x131 read before it failed). It is forgotten; the next
     * attempt waits `minWriteInterval` like after a failure, but no read is required (nothing reached the controller).
     */
    @Synchronized
    fun abortWrite(writeId: Long): Boolean {
        val aborted = pending?.takeIf { it.id == writeId } ?: return false
        pending = null
        primary.lastAttemptFailed = true
        baselineState?.let { state ->
            if (CalibrationMath.same(state.pendingWrite, aborted.record)) saveState(state.copy(pendingWrite = null))
        }
        return true
    }

    /**
     * The write requested by [CalibrationDecision.Write] with [writeId] was confirmed by the controller (0x132 echo).
     * Returns false for an unknown or outdated id (e.g. after a timeout), which is then ignored.
     */
    @Synchronized
    fun onWritten(writeId: Long): Boolean {
        val written = pending?.takeIf { it.id == writeId } ?: return false
        val now = clock()
        pending = null
        controllerRecord = written.record
        primary.record = written.record
        primary.lastAttemptFailed = false
        primary.lastAttemptAt = maxOf(primary.lastAttemptAt ?: now, now)
        primary.ignoreDeimicBefore = now + settleMs
        baselineState?.let { saveState(it.copy(lastWrittenByDomik = written.record, pendingWrite = null, lastWriteAt = now)) }
        return true
    }

    /**
     * The write with [writeId] failed (error, no echo). It is retried after `minWriteInterval`. The frame may still
     * have reached the controller: read 0x131 again ([onCalibrationRead]) before the retry is due.
     */
    @Synchronized
    fun onWriteFailed(writeId: Long): Boolean {
        if (pending?.id != writeId) return false
        failPending(clock())
        return true
    }

    /**
     * Live only: the baseline restore wanted now, without waiting for the minimum interval or a read after a failed
     * write (the caller reads 0x131 right before sending anyway). Used when the corrector is switched off for good –
     * manual restore, leaving live mode, forgetting the controller. [setEnabled] (false) should be called first.
     * Idle when the baseline is in place (or nothing is known yet), when a write is in flight, or in dry run.
     */
    @Synchronized
    fun restoreNow(reason: CalibrationReason = CalibrationReason.DISABLED): CalibrationDecision {
        val now = clock()
        pending?.let { if (now - it.since > writeTimeoutMs) failPending(now) }
        if (params.dryRun) return CalibrationDecision.Idle(CalibrationIdleReason.DISABLED)
        if (pending != null) return CalibrationDecision.Idle(CalibrationIdleReason.WRITE_IN_FLIGHT)
        val record = primary.record ?: return CalibrationDecision.Idle(CalibrationIdleReason.NO_CALIBRATION)
        val baseline = baselineState?.baseline ?: return CalibrationDecision.Idle(CalibrationIdleReason.NO_CALIBRATION)
        if (CalibrationMath.same(record, baseline)) return CalibrationDecision.Idle(CalibrationIdleReason.DISABLED)
        return request(Proposal.Change(baseline, reason, null, null, null, clamped = false), now)
    }

    /** Decides what to do now; see the class description. */
    @Synchronized
    fun evaluate(): CalibrationDecision {
        val now = clock()
        pending?.let { if (now - it.since > writeTimeoutMs) failPending(now) }
        if (params.dryRun) {
            tracks.filter { !it.primary }.forEach { track ->
                val proposal = propose(track, now)
                if (proposal is Proposal.Change) simulate(track, proposal, now)
            }
            return when (val proposal = propose(primary, now)) {
                is Proposal.Skip -> CalibrationDecision.Idle(proposal.reason)
                is Proposal.Change -> simulate(primary, proposal, now)
            }
        }
        if (pending != null) return CalibrationDecision.Idle(CalibrationIdleReason.WRITE_IN_FLIGHT)
        return when (val proposal = propose(primary, now)) {
            is Proposal.Skip -> CalibrationDecision.Idle(proposal.reason)
            is Proposal.Change -> if (readRequired) {
                CalibrationDecision.Idle(CalibrationIdleReason.RATE_LIMITED, CalibrationIdleDetail.READ_REQUIRED)
            } else {
                request(proposal, now)
            }
        }
    }

    /** Write counts per simulated threshold (dry run) or of the real writes (live), primary threshold first. */
    @Synchronized
    fun frequency(): List<CalibrationFrequency> {
        val now = clock()
        return tracks.map { track ->
            prune(track, now)
            CalibrationFrequency(
                threshold = track.threshold,
                primary = track.primary,
                dryRun = params.dryRun,
                since = startedAt,
                calibrations = track.calibrations,
                restores = track.restores,
                calibrationsLastHour = track.calibrationTimes.count { it > now - HOUR_MS },
                calibrationsLast24h = track.calibrationTimes.size,
            )
        }
    }

    /** Logged (would-be) writes within `logRetention`, oldest first; only those of [threshold] when given. */
    @Synchronized
    fun log(threshold: Float? = null): List<CalibrationLogEntry> {
        val now = clock()
        return tracks.filter { threshold == null || it.threshold == threshold }
            .flatMap { prune(it, now); it.log }
            .sortedBy { it.at }
    }

    /** UI model; see [CalibrationState]. */
    @Synchronized
    fun state(): CalibrationState {
        val now = clock()
        val xiaomi = average(xiaomiSamples, now, Long.MIN_VALUE)
        val deimic = average(deimicSamples, now, Long.MIN_VALUE)
        val base = baselineState?.baseline
        val real = controllerRecord
        val planned = primary.record
        val shift = if (base != null && real != null && planned != null && deimic != null) {
            CalibrationMath.invert(real, deimic * 10)?.let { CalibrationMath.shiftFromBaselineTenths(planned, base, it) / 10 }
        } else {
            null
        }
        return CalibrationState(
            inputNr = inputNr,
            enabled = enabled,
            dryRun = params.dryRun,
            xiaomiC = xiaomi?.toFloat(),
            deimicC = deimic?.toFloat(),
            differenceC = if (xiaomi != null && deimic != null) (xiaomi - deimic).toFloat() else null,
            sensorFresh = !isLost(now),
            batteryPercent = batteryPercent,
            controllerRecord = real,
            baseline = base,
            plannedRecord = planned,
            shiftFromBaselineC = shift?.toFloat(),
            writeInFlight = pending != null,
            readRequired = readRequired,
            stateNotSaved = stateNotSaved,
        )
    }

    private fun propose(track: Track, now: Long): Proposal {
        val record = track.record ?: return Proposal.Skip(CalibrationIdleReason.NO_CALIBRATION)
        val real = controllerRecord ?: return Proposal.Skip(CalibrationIdleReason.NO_CALIBRATION)
        val baseline = baselineState?.baseline ?: return Proposal.Skip(CalibrationIdleReason.NO_CALIBRATION)
        if (!enabled) {
            if (CalibrationMath.same(record, baseline)) return Proposal.Skip(CalibrationIdleReason.DISABLED)
            if (track.lastAttemptFailed && rateLimited(track, now)) return Proposal.Skip(CalibrationIdleReason.RATE_LIMITED)
            return Proposal.Change(baseline, CalibrationReason.DISABLED, null, null, null, clamped = false)
        }
        // Owner's decision 2026-10-12: a lost thermometer (or flat battery) does not restore the baseline; the last
        // calibration stays on the controller and the correction is only held (no writes) until fresh samples return.
        if (isLost(now)) return Proposal.Skip(CalibrationIdleReason.SENSOR_LOST)
        if (!CalibrationMath.isUsable(record) || !CalibrationMath.isUsable(baseline) || !CalibrationMath.isUsable(real)) {
            return Proposal.Skip(CalibrationIdleReason.CALIBRATION_UNUSABLE)
        }
        val xiaomi = average(xiaomiSamples, now, Long.MIN_VALUE) ?: return Proposal.Skip(CalibrationIdleReason.NO_XIAOMI_DATA)
        val reported = average(deimicSamples, now, track.ignoreDeimicBefore) ?: return Proposal.Skip(CalibrationIdleReason.NO_DEIMIC_DATA)
        val raw = CalibrationMath.invert(real, reported * 10) ?: return Proposal.Skip(CalibrationIdleReason.CALIBRATION_UNUSABLE)
        val deimicTenths = CalibrationMath.apply(record, raw) // what the controller reports (would report) under `record`
        val differenceTenths = xiaomi * 10 - deimicTenths
        if (abs(differenceTenths / 10) <= track.threshold + TRIGGER_EPSILON) return Proposal.Skip(CalibrationIdleReason.WITHIN_THRESHOLD)
        if (rateLimited(track, now)) return Proposal.Skip(CalibrationIdleReason.RATE_LIMITED)

        val limit = params.maxShiftFromBaseline * 10.0
        val currentShift = CalibrationMath.shiftFromBaselineTenths(record, baseline, raw)
        val wantedShift = currentShift + differenceTenths
        val clamped = abs(wantedShift) > limit + LIMIT_EPSILON
        val delta = wantedShift.coerceIn(-limit, limit) - currentShift
        if (delta.sign != differenceTenths.sign) return Proposal.Skip(CalibrationIdleReason.AT_LIMIT)
        var next = CalibrationMath.shifted(record, raw, delta)
        if (abs(CalibrationMath.shiftFromBaselineTenths(next, baseline, raw)) > limit + LIMIT_EPSILON) {
            next = CalibrationMath.shifted(record, raw, delta - delta.sign) // rounding went past the limit
        }
        if (CalibrationMath.same(next, record)) {
            return Proposal.Skip(if (clamped) CalibrationIdleReason.AT_LIMIT else CalibrationIdleReason.WITHIN_THRESHOLD)
        }
        return Proposal.Change(next, CalibrationReason.DIFFERENCE, xiaomi, deimicTenths / 10, differenceTenths / 10, clamped)
    }

    private fun simulate(track: Track, change: Proposal.Change, now: Long): CalibrationDecision.WouldWrite {
        val previous = track.record!!
        track.record = change.record
        track.lastAttemptAt = now
        track.lastAttemptFailed = false
        track.ignoreDeimicBefore = now + settleMs
        record(track, change, previous, now)
        return CalibrationDecision.WouldWrite(inputNr, change.record, previous, change.reason, change.differenceC?.toFloat(), change.clamped)
    }

    /** Stores the record to be written (and the time) first; when that fails nothing is to be sent. */
    private fun request(change: Proposal.Change, now: Long): CalibrationDecision {
        val previous = primary.record!!
        val state = baselineState ?: return CalibrationDecision.Idle(CalibrationIdleReason.NO_CALIBRATION)
        // A pending record left over (e.g. from before a restart) stays known as Domik's.
        val stored = state.pendingFailed().copy(pendingWrite = change.record, lastWriteAt = now)
        if (!saveState(stored)) {
            baselineState = state // nothing is sent, so memory must not claim a pending write either
            return CalibrationDecision.Idle(CalibrationIdleReason.RATE_LIMITED, CalibrationIdleDetail.STATE_NOT_SAVED)
        }
        val id = nextWriteId++
        pending = Pending(id, change.record, now)
        primary.lastAttemptAt = now
        record(primary, change, previous, now)
        return CalibrationDecision.Write(id, inputNr, change.record, previous, change.reason, change.differenceC?.toFloat(), change.clamped)
    }

    private fun record(track: Track, change: Proposal.Change, previous: InputCalibration, now: Long) {
        if (change.reason == CalibrationReason.DIFFERENCE) {
            track.calibrations++
            track.calibrationTimes.addLast(now)
        } else {
            track.restores++
        }
        track.log.addLast(
            CalibrationLogEntry(
                at = now,
                inputNr = inputNr,
                threshold = track.threshold,
                dryRun = params.dryRun,
                reason = change.reason,
                xiaomiC = change.xiaomiC?.toFloat(),
                deimicC = change.deimicC?.toFloat(),
                differenceC = change.differenceC?.toFloat(),
                previous = previous,
                next = change.record,
                clamped = change.clamped,
            ),
        )
        prune(track, now)
    }

    /** The write may or may not have reached the controller: its record stays known as Domik's and a read is required. */
    private fun failPending(now: Long) {
        pending = null
        primary.lastAttemptFailed = true
        primary.lastAttemptAt = now
        readRequired = true
        baselineState?.let { saveState(it.pendingFailed()) }
    }

    /** Keeps [state] in memory and stores it; false when storing failed (memory then stays ahead of the store). */
    private fun saveState(state: CalibrationBaselineState): Boolean {
        if (state == baselineState && !stateNotSaved) return true
        baselineState = state
        stateNotSaved = !store.save(inputNr, state)
        return !stateNotSaved
    }

    private fun add(samples: ArrayList<Sample>, tempC: Float, at: Long): Boolean {
        val now = clock()
        if (!tempC.isFinite() || tempC < params.minValidTemp || tempC > params.maxValidTemp) return false
        if (at > now + FUTURE_TOLERANCE_MS || at < now - windowMs) return false
        samples.removeAll { it.at < now - windowMs }
        if (samples.size >= MAX_SAMPLES) samples.removeAt(0)
        samples += Sample(at, tempC.toDouble())
        return true
    }

    /** Mean of the samples in the window not older than [notBefore]; null with fewer than `minSamplesPerWindow`. */
    private fun average(samples: List<Sample>, now: Long, notBefore: Long): Double? {
        val from = maxOf(now - windowMs, notBefore)
        val recent = samples.filter { it.at >= from && it.at <= now + FUTURE_TOLERANCE_MS }
        return if (recent.size < params.minSamplesPerWindow) null else recent.sumOf { it.value } / recent.size
    }

    /** No valid Xiaomi sample for longer than `staleTimeout` (counted from creation if none ever came), or battery critical. */
    private fun isLost(now: Long): Boolean {
        val since = lastXiaomiAt ?: startedAt
        val batteryCritical = batteryPercent?.let { it <= params.criticalBatteryPercent } ?: false
        return now - since > staleMs || batteryCritical
    }

    private fun rateLimited(track: Track, now: Long): Boolean = track.lastAttemptAt?.let { now - it < minIntervalMs } ?: false

    private fun prune(track: Track, now: Long) {
        while (track.calibrationTimes.isNotEmpty() && track.calibrationTimes.first() <= now - DAY_MS) track.calibrationTimes.removeFirst()
        while (track.log.isNotEmpty() && (track.log.first().at < now - logRetentionMs || track.log.size > MAX_LOG_ENTRIES)) {
            track.log.removeFirst()
        }
    }

    private companion object {
        /** Analog inputs 0..11 exist per module (the controller refuses 0x132 for higher numbers). */
        const val MAX_INPUT = 11
        /** Absorbs float rounding of the sensor values so that a difference of exactly 0.5 °C never triggers. */
        const val TRIGGER_EPSILON = 1e-4
        /** Tenths of °C. */
        const val LIMIT_EPSILON = 1e-3
        const val FUTURE_TOLERANCE_MS = 5_000L
        const val MAX_SAMPLES = 4096
        const val MAX_LOG_ENTRIES = 5000
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 24 * HOUR_MS
    }
}
