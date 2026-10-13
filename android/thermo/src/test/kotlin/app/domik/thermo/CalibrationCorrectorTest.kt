package app.domik.thermo

import app.domik.deimic.protocol.InputCalibration
import app.domik.thermo.CalibrationDecision.Idle
import app.domik.thermo.CalibrationDecision.WouldWrite
import app.domik.thermo.CalibrationDecision.Write
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.abs
import kotlin.time.Duration

class CalibrationCorrectorTest {
    private val minute = 60_000L

    /** Installer's calibration of input 4 (Pokój 3): measured 22.5 → true 18.0, i.e. −4.5 °C. */
    private val i4 = InputCalibration(1u, 0u, 225f, 0f, 180f, 0f)

    private val live = CalibrationParams(dryRun = false)

    /**
     * A fake module that holds one calibration record and reports `apply(record, raw)` for the uncalibrated sensor
     * value [rawC]; writes become visible after [applyDelayMs]. The corrector runs on the rig's virtual clock.
     */
    private inner class Rig(
        params: CalibrationParams = live,
        var record: InputCalibration = i4,
        var rawC: Double = 25.85,
        var xiaomi: Float? = 19.85f,
        var battery: Int? = 80,
        val store: CalibrationBaselineStore = InMemoryCalibrationBaselineStore(),
        var confirm: Boolean = true,
        var applyDelayMs: Long = 0,
        readAtStart: Boolean = true,
    ) {
        var now = 0L
        val corrector = CalibrationCorrector(4, params, store, clock = { now })
        val decisions = mutableListOf<Pair<Long, CalibrationDecision>>()
        private var delayed: Pair<Long, InputCalibration>? = null

        init {
            if (readAtStart) corrector.onCalibrationRead(record)
        }

        val writes: List<Pair<Long, Write>> get() = decisions.mapNotNull { (at, d) -> (d as? Write)?.let { at to it } }
        val wouldWrites: List<Pair<Long, WouldWrite>> get() = decisions.mapNotNull { (at, d) -> (d as? WouldWrite)?.let { at to it } }

        val reported: Float get() = (CalibrationMath.apply(record, rawC * 10) / 10).toFloat()

        fun tick(): CalibrationDecision {
            delayed?.let { (at, r) -> if (now >= at) { record = r; delayed = null } }
            xiaomi?.let { corrector.onXiaomiSample(it, battery, now) }
            corrector.onDeimicSample(reported, now)
            val decision = corrector.evaluate()
            if (decision is Write) {
                if (applyDelayMs == 0L) record = decision.record else delayed = (now + applyDelayMs) to decision.record
                if (confirm) corrector.onWritten(decision.writeId)
            }
            if (decision !is Idle) decisions += now to decision
            return decision
        }

        fun run(duration: Long, step: Long = minute, each: (Long) -> Unit = {}): CalibrationDecision {
            var last: CalibrationDecision = Idle(CalibrationIdleReason.NO_CALIBRATION)
            val end = now + duration
            while (now < end) {
                each(now)
                last = tick()
                now += step
            }
            return last
        }
    }

    private fun near(expected: Float, actual: Float?, tolerance: Float = 0.001f) =
        assertTrue(actual != null && abs(expected - actual) <= tolerance, "expected $expected, got $actual")

    @Test
    fun `defaults follow the specification`() {
        val p = CalibrationParams()
        assertEquals(0.5f, p.threshold)
        assertEquals(5 * minute, p.averagingWindow.inWholeMilliseconds)
        assertEquals(15 * minute, p.minWriteInterval.inWholeMilliseconds)
        assertEquals(30_000L, p.settleTime.inWholeMilliseconds)
        assertEquals(30 * minute, p.staleTimeout.inWholeMilliseconds)
        assertEquals(5.0f, p.maxShiftFromBaseline)
        assertEquals(5f, p.minValidTemp)
        assertEquals(35f, p.maxValidTemp)
        assertEquals(listOf(0.5f, 1.0f), p.comparisonThresholds)
        assertTrue(p.dryRun)
        assertThrows<IllegalArgumentException> { CalibrationParams(averagingWindow = Duration.ZERO) }
        assertThrows<IllegalArgumentException> { CalibrationCorrector(12, store = InMemoryCalibrationBaselineStore()) }
    }

    @Test
    fun `nothing happens before the calibration was read`() {
        val rig = Rig(readAtStart = false)
        assertEquals(Idle(CalibrationIdleReason.NO_CALIBRATION), rig.run(10 * minute))
        assertTrue(rig.decisions.isEmpty())
        assertNull(rig.store.load(4))
    }

    @Test
    fun `first read stores the installer calibration as baseline`() {
        val store = InMemoryCalibrationBaselineStore()
        val corrector = CalibrationCorrector(4, live, store, clock = { 0L })
        assertEquals(BaselineEvent.CAPTURED, corrector.onCalibrationRead(i4))
        assertEquals(CalibrationBaselineState(i4), store.load(4))
        assertEquals(BaselineEvent.MATCHES_BASELINE, corrector.onCalibrationRead(i4))
    }

    @Test
    fun `stable difference gives one write after which the controller reads the xiaomi value`() {
        val rig = Rig() // controller 21.35, Xiaomi 19.85 → d = −1.5
        rig.run(180 * minute)
        val (at, write) = rig.writes.single()
        assertEquals(minute, at) // two samples are needed in the window
        assertEquals(4, write.inputNr)
        assertEquals(InputCalibration(1u, 0u, 225f, 0f, 165f, 0f), write.record)
        assertEquals(i4, write.previous)
        assertEquals(CalibrationReason.DIFFERENCE, write.reason)
        near(-1.5f, write.differenceC)
        assertFalse(write.clamped)
        near(19.85f, rig.reported)
        assertEquals(CalibrationBaselineState(i4, lastWrittenByDomik = write.record, lastWriteAt = minute), rig.store.load(4))
        val state = rig.corrector.state()
        near(19.85f, state.xiaomiC)
        near(19.85f, state.deimicC)
        near(-1.5f, state.shiftFromBaselineC)
    }

    @Test
    fun `difference of exactly half a degree does not trigger but 0_51 does`() {
        val exact = Rig(rawC = 26.5, xiaomi = 21.5f) // controller 22.0
        exact.run(60 * minute)
        assertTrue(exact.decisions.isEmpty())

        val over = Rig(rawC = 26.5, xiaomi = 21.49f)
        over.run(60 * minute)
        assertEquals(175f, over.writes.single().second.record.true1Tenths) // 180 − 5.1 → 175 (whole tenths)

        val up = Rig(rawC = 26.5, xiaomi = 23.0f)
        up.run(60 * minute)
        assertEquals(190f, up.writes.single().second.record.true1Tenths) // as in the live test: 18.0 → 19.0
    }

    @Test
    fun `writes are at least the minimum interval apart`() {
        val rig = Rig(xiaomi = 21.35f) // no difference at first
        rig.run(90 * minute) { t -> rig.rawC = 25.85 + 0.05 * (t / minute) } // the sensor box warms 3 °C per hour
        assertTrue(rig.writes.size >= 5, "writes: ${rig.writes.size}")
        val gaps = rig.writes.zipWithNext { a, b -> b.first - a.first }
        assertTrue(gaps.all { it >= 15 * minute })
        assertEquals(15 * minute, gaps.min()) // the difference grows faster than the interval allows
        assertTrue(abs(rig.reported - 21.35f) < 1.0f)
    }

    @Test
    fun `deimic samples from before a write and its settle time are ignored`() {
        val fast = live.copy(minWriteInterval = Duration.ZERO)
        val rig = Rig(params = fast, applyDelayMs = 20_000)
        rig.run(30 * minute, step = 10_000)
        assertEquals(1, rig.writes.size)

        val noSettle = Rig(params = fast.copy(settleTime = Duration.ZERO), applyDelayMs = 20_000)
        noSettle.run(30 * minute, step = 10_000)
        assertTrue(noSettle.writes.size > 1, "without the settle time the old window is counted twice")
    }

    @Test
    fun `shift is limited against the baseline`() {
        val rig = Rig(xiaomi = 12.0f) // d = −9.35
        val last = rig.run(120 * minute)
        val write = rig.writes.single().second
        assertTrue(write.clamped)
        assertEquals(130f, write.record.true1Tenths) // 18.0 − 5.0
        assertEquals(Idle(CalibrationIdleReason.AT_LIMIT), last)
        near(-5.0f, rig.corrector.state().shiftFromBaselineC)
    }

    @Test
    fun `the limit counts from the baseline, not from the current record`() {
        val rig = Rig(xiaomi = 18.35f) // −3.0
        rig.run(30 * minute)
        assertEquals(150f, rig.writes.single().second.record.true1Tenths)
        rig.xiaomi = 15.35f // another −3.0 wanted, only −2.0 left
        rig.run(60 * minute)
        val last = rig.writes.last().second
        assertTrue(last.clamped)
        assertEquals(130f, last.record.true1Tenths)
        assertTrue(rig.writes.all { it.second.record.true1Tenths >= 130f })
    }

    @Test
    fun `invalid samples are dropped`() {
        val rig = Rig(xiaomi = null)
        rig.now = 10 * minute
        val c = rig.corrector
        assertFalse(c.onXiaomiSample(Float.NaN))
        assertFalse(c.onXiaomiSample(4.9f))
        assertFalse(c.onXiaomiSample(35.1f))
        assertFalse(c.onXiaomiSample(21f, at = rig.now + minute)) // from the future
        assertFalse(c.onXiaomiSample(21f, at = rig.now - 6 * minute)) // older than the window
        assertFalse(c.onDeimicSample(Float.POSITIVE_INFINITY))
        assertTrue(c.onXiaomiSample(5f))
        assertTrue(c.onDeimicSample(35f))
        assertNull(c.state().xiaomiC) // one sample is not enough for an average
    }

    @Test
    fun `lost xiaomi keeps the last calibration, writes nothing and resumes from it when it is back`() {
        val rig = Rig()
        rig.run(30 * minute)
        assertEquals(1, rig.writes.size)
        val calibrated = rig.record
        assertEquals(165f, calibrated.true1Tenths)
        rig.xiaomi = null
        val lost = rig.run(90 * minute)
        assertEquals(1, rig.writes.size) // no restore
        assertEquals(Idle(CalibrationIdleReason.SENSOR_LOST), lost)
        assertEquals(calibrated, rig.record)
        assertEquals(calibrated, rig.store.load(4)?.lastWrittenByDomik)
        near(19.85f, rig.reported)
        assertFalse(rig.corrector.state().sensorFresh)

        // The thermometer is back and now reads 1 °C more: the correction continues from the kept calibration
        // (stale samples from before the pause are outside the window, so nothing is counted twice).
        rig.xiaomi = 20.85f
        assertEquals(Idle(CalibrationIdleReason.NO_XIAOMI_DATA), rig.run(minute)) // one fresh sample is not enough
        rig.run(10 * minute)
        assertEquals(2, rig.writes.size)
        val (_, resumed) = rig.writes.last()
        assertEquals(CalibrationReason.DIFFERENCE, resumed.reason)
        assertEquals(calibrated, resumed.previous)
        assertEquals(175f, resumed.record.true1Tenths)
        near(20.85f, rig.reported)
    }

    @Test
    fun `without any xiaomi reading the baseline in place is not written again`() {
        val rig = Rig(xiaomi = null)
        assertEquals(Idle(CalibrationIdleReason.NO_XIAOMI_DATA), rig.run(10 * minute))
        assertEquals(Idle(CalibrationIdleReason.SENSOR_LOST), rig.run(60 * minute))
        assertTrue(rig.decisions.isEmpty())
        assertFalse(rig.corrector.state().sensorFresh)
    }

    @Test
    fun `critical battery holds the correction without restoring the baseline`() {
        val rig = Rig()
        rig.run(20 * minute)
        val calibrated = rig.record
        rig.battery = 5
        rig.xiaomi = 20.85f // 1 °C warmer, but the battery is flat: nothing is written meanwhile
        assertEquals(Idle(CalibrationIdleReason.SENSOR_LOST), rig.run(5 * minute))
        assertEquals(1, rig.writes.size)
        assertEquals(calibrated, rig.record)
        rig.battery = 80 // replaced battery: normal work again, from the kept calibration
        rig.run(10 * minute)
        assertEquals(175f, rig.writes.last().second.record.true1Tenths)
    }

    @Test
    fun `switching off restores the baseline and switching on resumes`() {
        val rig = Rig()
        rig.run(20 * minute)
        rig.corrector.setEnabled(false)
        rig.run(minute)
        assertEquals(CalibrationReason.DISABLED, rig.writes.last().second.reason)
        assertEquals(Idle(CalibrationIdleReason.DISABLED), rig.run(30 * minute))
        assertEquals(2, rig.writes.size)
        rig.corrector.setEnabled(true)
        rig.run(20 * minute)
        assertEquals(3, rig.writes.size)
        assertEquals(165f, rig.record.true1Tenths)
    }

    @Test
    fun `failed write is retried after the minimum interval`() {
        val rig = Rig(confirm = false)
        rig.run(2 * minute)
        val first = rig.writes.single().second
        assertTrue(rig.corrector.onWriteFailed(first.writeId))
        assertFalse(rig.corrector.onWriteFailed(first.writeId))
        rig.record = i4 // the frame did not reach the controller
        assertEquals(BaselineEvent.MATCHES_BASELINE, rig.corrector.onCalibrationRead(i4))
        assertEquals(Idle(CalibrationIdleReason.RATE_LIMITED), rig.run(14 * minute))
        rig.confirm = true
        rig.run(2 * minute)
        assertEquals(2, rig.writes.size)
        assertTrue(rig.writes.last().second.writeId != first.writeId)
        assertEquals(165f, rig.record.true1Tenths)
    }

    @Test
    fun `unconfirmed write times out and a late confirmation is ignored`() {
        val rig = Rig(confirm = false)
        rig.run(2 * minute)
        val first = rig.writes.single().second
        rig.record = i4
        assertEquals(Idle(CalibrationIdleReason.WRITE_IN_FLIGHT), rig.tick())
        rig.now += 61_000
        assertEquals(Idle(CalibrationIdleReason.RATE_LIMITED), rig.tick())
        assertFalse(rig.corrector.onWritten(first.writeId))
        assertTrue(rig.corrector.state().controllerRecord == i4)
    }

    @Test
    fun `pending write is stored before sending and recognised after a restart`() {
        val store = InMemoryCalibrationBaselineStore()
        val rig = Rig(store = store, confirm = false)
        rig.run(2 * minute)
        val write = rig.writes.single().second
        val at = rig.writes.single().first
        assertEquals(CalibrationBaselineState(i4, pendingWrite = write.record, lastWriteAt = at), store.load(4))

        // The app died before the echo; after a restart the controller holds the new record.
        val restarted = CalibrationCorrector(4, live, store, clock = { rig.now })
        assertEquals(BaselineEvent.MATCHES_DOMIK_WRITE, restarted.onCalibrationRead(write.record))
        assertEquals(CalibrationBaselineState(i4, lastWrittenByDomik = write.record, lastWriteAt = at), store.load(4))
    }

    @Test
    fun `calibration changed in the configurator becomes the new baseline`() {
        val rig = Rig()
        rig.run(20 * minute)
        val installerAgain = InputCalibration(1u, 0u, 220f, 0f, 190f, 0f) // −3.0 °C
        rig.record = installerAgain
        assertEquals(BaselineEvent.EXTERNAL_CHANGE, rig.corrector.onCalibrationRead(installerAgain))
        assertEquals(CalibrationBaselineState(installerAgain, lastWriteAt = rig.writes.last().first), rig.store.load(4))
        rig.xiaomi = 10f // far below: limited to −5.0 from the new baseline
        rig.run(30 * minute)
        val write = rig.writes.last().second
        assertTrue(write.clamped)
        assertEquals(140f, write.record.true1Tenths)
    }

    @Test
    fun `input without calibration gets a one point record`() {
        val none = InputCalibration(0u, 0u, 0f, 0f, 0f, 0f)
        val rig = Rig(record = none, rawC = 22.0, xiaomi = 21.0f)
        rig.run(30 * minute)
        assertEquals(InputCalibration(1u, 0u, 220f, 0f, 210f, 0f), rig.writes.single().second.record)
        near(21.0f, rig.reported)
    }

    @Test
    fun `two point calibration is moved and keeps its slope`() {
        val twoPoint = InputCalibration(1u, 1u, 100f, 300f, 90f, 310f)
        val rig = Rig(record = twoPoint, rawC = 20.0, xiaomi = 18.5f) // controller 20.0
        rig.run(30 * minute)
        assertEquals(InputCalibration(1u, 1u, 100f, 300f, 75f, 295f), rig.writes.single().second.record)
        near(18.5f, rig.reported)
    }

    @Test
    fun `unusable record is left alone`() {
        val broken = InputCalibration(1u, 0u, Float.NaN, 0f, 180f, 0f)
        val rig = Rig(record = broken, rawC = 22.0)
        assertEquals(Idle(CalibrationIdleReason.CALIBRATION_UNUSABLE), rig.run(30 * minute))
        assertTrue(rig.decisions.isEmpty())
    }

    @Test
    fun `dry run reports would-write and leaves the controller and the stored writes alone`() {
        val rig = Rig(params = CalibrationParams())
        rig.run(180 * minute)
        assertTrue(rig.writes.isEmpty())
        val (at, would) = rig.wouldWrites.single() // the simulation sees the corrected value afterwards
        assertEquals(minute, at)
        assertEquals(165f, would.record.true1Tenths)
        assertEquals(i4, rig.record)
        assertEquals(CalibrationBaselineState(i4), rig.store.load(4))
        val state = rig.corrector.state()
        assertEquals(i4, state.controllerRecord)
        assertEquals(would.record, state.plannedRecord)
        val log = rig.corrector.log(0.5f)
        assertEquals(1, log.size)
        assertTrue(log.single().dryRun)
        near(21.35f, log.single().deimicC)
    }

    @Test
    fun `dry run does not simulate a restore when the thermometer is lost`() {
        val rig = Rig(params = CalibrationParams())
        rig.run(30 * minute)
        assertEquals(1, rig.wouldWrites.size)
        rig.xiaomi = null
        assertEquals(Idle(CalibrationIdleReason.SENSOR_LOST), rig.run(90 * minute))
        assertEquals(1, rig.wouldWrites.size)
        assertTrue(rig.corrector.log().none { it.reason != CalibrationReason.DIFFERENCE })
        assertTrue(rig.corrector.frequency().all { it.restores == 0 })
    }

    @Test
    fun `dry run counts both thresholds side by side`() {
        val rig = Rig(params = CalibrationParams(), xiaomi = 21.35f)
        // Xiaomi steps by 0.9 °C every half hour (e.g. sun on the thermometer).
        rig.run(240 * minute) { t -> rig.xiaomi = if ((t / (30 * minute)) % 2 == 1L) 22.25f else 21.35f }
        val frequency = rig.corrector.frequency().associateBy { it.threshold }
        val half = frequency.getValue(0.5f)
        val one = frequency.getValue(1.0f)
        assertTrue(half.primary)
        assertFalse(one.primary)
        assertEquals(7, half.calibrations) // one per step
        assertEquals(0, one.calibrations)
        assertEquals(7, half.calibrationsLast24h)
        assertEquals(2, half.calibrationsLastHour)
        assertEquals(7, rig.wouldWrites.size)
        assertEquals(7, rig.corrector.log().size)
        assertTrue(rig.corrector.log(1.0f).isEmpty())
    }

    @Test
    fun `dry run with a wider primary threshold still measures the narrower one`() {
        val rig = Rig(params = CalibrationParams(threshold = 1.0f), xiaomi = 21.35f)
        rig.run(240 * minute) { t -> rig.xiaomi = if ((t / (30 * minute)) % 2 == 1L) 22.25f else 21.35f }
        assertTrue(rig.wouldWrites.isEmpty())
        val frequency = rig.corrector.frequency()
        assertEquals(listOf(1.0f, 0.5f), frequency.map { it.threshold })
        assertEquals(7, frequency[1].calibrations)
    }

    // Regression tests – code review 2026-10-12

    @Test
    fun `a write is dropped when the read right before it shows another record`() {
        val rig = Rig(confirm = false)
        rig.run(2 * minute)
        val write = rig.writes.single().second
        assertTrue(rig.corrector.isPending(write.writeId))

        // Read before sending: the controller still holds what the write was computed from → stays valid.
        assertEquals(BaselineEvent.MATCHES_BASELINE, rig.corrector.onCalibrationRead(i4))
        assertTrue(rig.corrector.isPending(write.writeId))
        assertEquals(write.record, rig.store.load(4)!!.pendingWrite) // still known as Domik's

        // Someone changed it in the Configurator meanwhile → dropped, never sent.
        val configurator = InputCalibration(1u, 0u, 220f, 0f, 190f, 0f)
        assertEquals(BaselineEvent.EXTERNAL_CHANGE, rig.corrector.onCalibrationRead(configurator))
        assertFalse(rig.corrector.isPending(write.writeId))
        assertFalse(rig.corrector.onWritten(write.writeId))
        assertNull(rig.store.load(4)!!.pendingWrite)
    }

    @Test
    fun `an aborted write is forgotten and retried after the interval without a read`() {
        val rig = Rig(confirm = false)
        rig.run(2 * minute)
        val write = rig.writes.single().second
        rig.record = i4 // never sent
        assertTrue(rig.corrector.abortWrite(write.writeId))
        assertFalse(rig.corrector.abortWrite(write.writeId))
        assertNull(rig.store.load(4)!!.pendingWrite)
        assertEquals(Idle(CalibrationIdleReason.RATE_LIMITED), rig.run(14 * minute))
        rig.confirm = true
        rig.run(2 * minute)
        assertEquals(2, rig.writes.size)
    }

    @Test
    fun `after a failed write nothing is written before a successful read`() {
        val rig = Rig(confirm = false)
        rig.run(2 * minute)
        val first = rig.writes.single().second
        assertTrue(rig.corrector.onWriteFailed(first.writeId))
        assertEquals(listOf(first.record), rig.store.load(4)!!.unconfirmed)
        assertTrue(rig.corrector.state().readRequired)

        rig.record = i4 // whether the frame arrived is unknown to Domik
        rig.confirm = true
        assertEquals(Idle(CalibrationIdleReason.RATE_LIMITED, CalibrationIdleDetail.READ_REQUIRED), rig.run(60 * minute))
        assertEquals(1, rig.writes.size)

        // The frame did arrive after all: Domik's own record, not a change in the Configurator.
        rig.record = first.record
        assertEquals(BaselineEvent.MATCHES_DOMIK_WRITE, rig.corrector.onCalibrationRead(first.record))
        assertEquals(i4, rig.store.load(4)!!.baseline)
        assertFalse(rig.corrector.state().readRequired)
        rig.run(20 * minute)
        assertEquals(1, rig.writes.size) // converged with the record that arrived
    }

    @Test
    fun `a record sent without confirmation is still recognised after a restart`() {
        val store = InMemoryCalibrationBaselineStore()
        val rig = Rig(store = store, confirm = false)
        rig.run(2 * minute)
        val first = rig.writes.single().second
        rig.corrector.onWriteFailed(first.writeId)

        val restarted = CalibrationCorrector(4, live, store, clock = { rig.now })
        assertEquals(BaselineEvent.MATCHES_DOMIK_WRITE, restarted.onCalibrationRead(first.record))
        assertEquals(i4, store.load(4)!!.baseline)
    }

    @Test
    fun `nothing is sent when the record to be written cannot be stored`() {
        var working = false
        val memory = InMemoryCalibrationBaselineStore()
        val store = object : CalibrationBaselineStore {
            override fun load(inputNr: Int) = memory.load(inputNr)

            override fun save(inputNr: Int, state: CalibrationBaselineState): Boolean {
                if (working) memory.save(inputNr, state)
                return working
            }
        }
        val rig = Rig(store = store)
        assertEquals(Idle(CalibrationIdleReason.RATE_LIMITED, CalibrationIdleDetail.STATE_NOT_SAVED), rig.run(10 * minute))
        assertTrue(rig.writes.isEmpty())
        assertTrue(rig.corrector.state().stateNotSaved)
        assertNull(rig.corrector.baseline!!.pendingWrite)

        working = true
        rig.run(2 * minute)
        assertEquals(1, rig.writes.size)
        assertEquals(i4, memory.load(4)!!.baseline) // the baseline got stored together with the write
        assertFalse(rig.corrector.state().stateNotSaved)
    }

    @Test
    fun `a corrector created again keeps the interval and the settle time of the last write`() {
        val store = InMemoryCalibrationBaselineStore()
        val fast = live.copy(settleTime = kotlin.time.Duration.parse("2m"))
        val rig = Rig(params = fast, store = store)
        rig.run(2 * minute)
        val (at, write) = rig.writes.single()

        // Re-created (session restart) a minute later; the controller reads the old window's samples still.
        rig.now = at + minute
        val again = CalibrationCorrector(4, fast, store, clock = { rig.now })
        again.onCalibrationRead(write.record)
        for (t in listOf(at - 30_000, at, at + 30_000)) {
            again.onXiaomiSample(15.0f, 80, t) // far off: would trigger at once
            again.onDeimicSample(21.35f, t) // samples from before the write and within the settle time
        }
        assertEquals(Idle(CalibrationIdleReason.NO_DEIMIC_DATA), again.evaluate())
        rig.now = at + 3 * minute
        again.onXiaomiSample(15.0f, 80, rig.now)
        again.onDeimicSample(19.85f, rig.now)
        again.onDeimicSample(19.85f, rig.now)
        assertEquals(Idle(CalibrationIdleReason.RATE_LIMITED), again.evaluate())
        rig.now = at + 15 * minute
        again.onXiaomiSample(15.0f, 80, rig.now)
        again.onXiaomiSample(15.0f, 80, rig.now)
        again.onDeimicSample(19.85f, rig.now)
        again.onDeimicSample(19.85f, rig.now)
        assertTrue(again.evaluate() is Write)
    }

    @Test
    fun `restore now ignores the interval but not a write in flight`() {
        val rig = Rig()
        rig.run(2 * minute)
        rig.corrector.setEnabled(false)
        val restore = rig.corrector.restoreNow()
        assertTrue(restore is Write && restore.record == i4 && restore.reason == CalibrationReason.DISABLED, "$restore")
        assertEquals(Idle(CalibrationIdleReason.WRITE_IN_FLIGHT), rig.corrector.restoreNow())
        rig.corrector.onWritten((restore as Write).writeId)
        assertEquals(Idle(CalibrationIdleReason.DISABLED), rig.corrector.restoreNow())
        assertFalse(rig.store.load(4)!!.mayHoldDomikShift)
    }
}
