package app.domik.thermo

import app.domik.deimic.protocol.ByteReader
import app.domik.deimic.protocol.InputCalibration
import app.domik.deimic.protocol.toBytes

/**
 * What the app keeps per calibrated input across restarts (`notes/THERMOSTATS.md` §3).
 *
 * @property baseline the installer's calibration, captured from the first 0x131 read before Domik wrote anything,
 *   or the record someone set later outside Domik (Configurator). Restored when the sensor is lost or the zone is
 *   switched off.
 * @property lastWrittenByDomik the last record Domik confirmed on the controller (null before the first write).
 * @property pendingWrite a record Domik decided to write but whose confirmation was not seen yet; persisted before
 *   the frame is sent so that, after a crash, reading it back is not mistaken for a change made in the Configurator.
 * @property lastWriteAt when Domik last decided to write (and, once confirmed, when the confirmation came) – the
 *   corrector re-created after a restart continues the minimum interval and the settle time from here.
 * @property unconfirmed records Domik sent whose write failed (no echo): they may still have reached the controller,
 *   so reading one back is Domik's write, not a change outside Domik. Cleared by the next successful read.
 */
data class CalibrationBaselineState(
    val baseline: InputCalibration,
    val lastWrittenByDomik: InputCalibration? = null,
    val pendingWrite: InputCalibration? = null,
    val lastWriteAt: Long? = null,
    val unconfirmed: List<InputCalibration> = emptyList(),
) {
    /** Domik's own records that the controller may hold: the last confirmed one, the one in flight and the unconfirmed ones. */
    val domikRecords: List<InputCalibration>
        get() = listOfNotNull(lastWrittenByDomik, pendingWrite) + unconfirmed

    /**
     * The controller may hold a record of Domik's other than [baseline] (confirmed, in flight or unconfirmed). Used
     * before forgetting a controller or handing an input to a dry-run corrector: such a shift must be restored first.
     */
    val mayHoldDomikShift: Boolean
        get() = pendingWrite != null || unconfirmed.any { !CalibrationMath.same(it, baseline) } ||
            (lastWrittenByDomik != null && !CalibrationMath.same(lastWrittenByDomik, baseline))

    /** [pendingWrite] (if any) did not get confirmed: it joins [unconfirmed] (at most [MAX_UNCONFIRMED], newest kept). */
    fun pendingFailed(): CalibrationBaselineState {
        val failed = pendingWrite ?: return this
        val kept = unconfirmed.filterNot { CalibrationMath.same(it, failed) } + failed
        return copy(pendingWrite = null, unconfirmed = kept.takeLast(MAX_UNCONFIRMED))
    }

    /**
     * One line of text: `v2;<baseline>;<last written or ->;<pending or ->;<lastWriteAt or ->;<unconfirmed, comma separated, or ->`
     * (records as 18-byte hex, byte-exact). [deserialize] also reads the older `v1;<baseline>;<last>;<pending>`.
     */
    fun serialize(): String = listOf(
        VERSION,
        hex(baseline),
        hexOrDash(lastWrittenByDomik),
        hexOrDash(pendingWrite),
        lastWriteAt?.toString() ?: "-",
        if (unconfirmed.isEmpty()) "-" else unconfirmed.joinToString(",", transform = ::hex),
    ).joinToString(";")

    companion object {
        private const val VERSION = "v2"
        private const val VERSION_1 = "v1"

        /** At most this many unconfirmed records are remembered. */
        const val MAX_UNCONFIRMED = 8

        /** Parses [serialize] output (also `v1`); null for anything else (unknown version, bad hex, wrong length). */
        fun deserialize(text: String): CalibrationBaselineState? {
            val parts = text.trim().split(";")
            val v1 = parts.size == 4 && parts[0] == VERSION_1
            val v2 = parts.size == 6 && parts[0] == VERSION
            if (!v1 && !v2) return null
            val baseline = record(parts[1]) ?: return null
            val lastWritten = if (parts[2] == "-") null else record(parts[2]) ?: return null
            val pending = if (parts[3] == "-") null else record(parts[3]) ?: return null
            if (v1) return CalibrationBaselineState(baseline, lastWritten, pending)
            val lastWriteAt = if (parts[4] == "-") null else parts[4].toLongOrNull() ?: return null
            val unconfirmed = if (parts[5] == "-") {
                emptyList()
            } else {
                parts[5].split(",").map { record(it) ?: return null }
            }
            return CalibrationBaselineState(baseline, lastWritten, pending, lastWriteAt, unconfirmed)
        }

        private fun hex(record: InputCalibration): String = record.encode().toHex()

        private fun hexOrDash(record: InputCalibration?): String = record?.let(::hex) ?: "-"

        private fun record(hex: String): InputCalibration? {
            if (hex.length != InputCalibration.SIZE * 2) return null
            val bytes = ByteArray(InputCalibration.SIZE)
            for (i in bytes.indices) {
                bytes[i] = hex.substring(2 * i, 2 * i + 2).toIntOrNull(16)?.toByte() ?: return null
            }
            return InputCalibration.decode(ByteReader(bytes.toBytes()))
        }
    }
}

/** What a 0x131 read meant for the baseline; see [CalibrationBaseline.reconcile]. */
enum class BaselineEvent {
    /** Nothing stored yet: the record read is the installer's calibration and becomes the baseline. */
    CAPTURED,

    /** The controller holds the baseline (Domik's earlier records are forgotten: none of them is there). */
    MATCHES_BASELINE,

    /** The controller holds the record Domik wrote last (or the one it was writing, or one whose write failed). */
    MATCHES_DOMIK_WRITE,

    /**
     * The controller holds neither: someone changed the calibration outside Domik (Configurator). The record read
     * becomes the new baseline; the app should tell the user.
     */
    EXTERNAL_CHANGE,
}

data class BaselineReconciliation(val state: CalibrationBaselineState, val event: BaselineEvent)

/** Pure rules that keep the stored baseline in step with what the controller reports (0x131). */
object CalibrationBaseline {
    /**
     * Compares [readFromController] with the stored baseline and Domik's own records ([CalibrationBaselineState.domikRecords]:
     * last confirmed, pending, unconfirmed); records are compared byte-exactly. A successful read settles every
     * earlier write, so pending and unconfirmed records are dropped. The returned state must be persisted.
     */
    fun reconcile(stored: CalibrationBaselineState?, readFromController: InputCalibration): BaselineReconciliation {
        if (stored == null) {
            return BaselineReconciliation(CalibrationBaselineState(readFromController), BaselineEvent.CAPTURED)
        }
        if (stored.domikRecords.any { CalibrationMath.same(readFromController, it) }) {
            return BaselineReconciliation(
                stored.copy(lastWrittenByDomik = readFromController, pendingWrite = null, unconfirmed = emptyList()),
                BaselineEvent.MATCHES_DOMIK_WRITE,
            )
        }
        if (CalibrationMath.same(readFromController, stored.baseline)) {
            // The controller holds the baseline: none of Domik's records is there (any more).
            return BaselineReconciliation(
                stored.copy(lastWrittenByDomik = null, pendingWrite = null, unconfirmed = emptyList()),
                BaselineEvent.MATCHES_BASELINE,
            )
        }
        return BaselineReconciliation(
            CalibrationBaselineState(readFromController, lastWriteAt = stored.lastWriteAt),
            BaselineEvent.EXTERNAL_CHANGE,
        )
    }

    /** Variant with the stored fields passed separately. */
    fun reconcile(
        storedBaseline: InputCalibration?,
        lastWrittenByDomik: InputCalibration?,
        readFromController: InputCalibration,
    ): BaselineReconciliation =
        reconcile(storedBaseline?.let { CalibrationBaselineState(it, lastWrittenByDomik) }, readFromController)
}

/**
 * Persistent storage of [CalibrationBaselineState] per analog input of one controller, implemented by the app
 * (file / SharedPreferences, e.g. with [CalibrationBaselineState.serialize]). Keyed by the controller itself (not by
 * its address): the baseline must survive a change of IP address and must never be shared between two controllers.
 * Called synchronously by [CalibrationCorrector].
 */
interface CalibrationBaselineStore {
    fun load(inputNr: Int): CalibrationBaselineState?

    /** Stores [state]; false when it could not be stored durably (the corrector then sends nothing). */
    fun save(inputNr: Int, state: CalibrationBaselineState): Boolean
}

/** [CalibrationBaselineStore] kept in memory (tests, demo mode). */
class InMemoryCalibrationBaselineStore : CalibrationBaselineStore {
    private val states = HashMap<Int, CalibrationBaselineState>()

    @Synchronized
    override fun load(inputNr: Int): CalibrationBaselineState? = states[inputNr]

    @Synchronized
    override fun save(inputNr: Int, state: CalibrationBaselineState): Boolean {
        states[inputNr] = state
        return true
    }
}
