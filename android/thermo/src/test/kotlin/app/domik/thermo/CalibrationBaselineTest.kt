package app.domik.thermo

import app.domik.deimic.protocol.ByteReader
import app.domik.deimic.protocol.Bytes
import app.domik.deimic.protocol.InputCalibration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CalibrationBaselineTest {
    private val installer = InputCalibration(1u, 0u, 225f, 0f, 180f, 0f)
    private val domik = InputCalibration(1u, 0u, 225f, 0f, 170f, 0f)
    private val configurator = InputCalibration(1u, 0u, 230f, 0f, 200f, 0f)

    @Test
    fun `first read captures the baseline`() {
        val result = CalibrationBaseline.reconcile(null, installer)
        assertEquals(BaselineEvent.CAPTURED, result.event)
        assertEquals(CalibrationBaselineState(installer), result.state)
    }

    @Test
    fun `baseline and domik records are recognised`() {
        val stored = CalibrationBaselineState(installer, lastWrittenByDomik = domik)
        assertEquals(BaselineEvent.MATCHES_BASELINE, CalibrationBaseline.reconcile(stored, installer).event)
        val ours = CalibrationBaseline.reconcile(stored, domik)
        assertEquals(BaselineEvent.MATCHES_DOMIK_WRITE, ours.event)
        assertEquals(stored, ours.state)
    }

    @Test
    fun `a pending write read back counts as domik's`() {
        val stored = CalibrationBaselineState(installer, pendingWrite = domik)
        val result = CalibrationBaseline.reconcile(stored, domik)
        assertEquals(BaselineEvent.MATCHES_DOMIK_WRITE, result.event)
        assertEquals(CalibrationBaselineState(installer, lastWrittenByDomik = domik), result.state)
        // The write did not happen: the baseline is still there and the pending record is forgotten.
        val notWritten = CalibrationBaseline.reconcile(stored, installer)
        assertEquals(BaselineEvent.MATCHES_BASELINE, notWritten.event)
        assertNull(notWritten.state.pendingWrite)
    }

    @Test
    fun `change made in the configurator becomes the new baseline`() {
        val stored = CalibrationBaselineState(installer, lastWrittenByDomik = domik)
        val result = CalibrationBaseline.reconcile(stored, configurator)
        assertEquals(BaselineEvent.EXTERNAL_CHANGE, result.event)
        assertEquals(CalibrationBaselineState(configurator), result.state)
        val withTime = CalibrationBaseline.reconcile(stored.copy(lastWriteAt = 99L), configurator)
        assertEquals(CalibrationBaselineState(configurator, lastWriteAt = 99L), withTime.state) // the interval keeps counting
        assertEquals(result, CalibrationBaseline.reconcile(installer, domik, configurator))
    }

    @Test
    fun `serialized state round trips byte exactly`() {
        val empty = InputCalibration.decode(ByteReader(Bytes.of(0, 0, *IntArray(16) { 0xFF }))) // empty slot (i7..i11)
        val state = CalibrationBaselineState(empty, lastWrittenByDomik = domik, pendingWrite = null)
        val text = state.serialize()
        assertEquals("v2;0000ffffffffffffffffffffffffffffffff;" + domik.encode().toHex() + ";-;-;-", text)
        val back = CalibrationBaselineState.deserialize(text)!!
        assertTrue(CalibrationMath.same(empty, back.baseline))
        assertEquals(domik, back.lastWrittenByDomik)
        assertNull(back.pendingWrite)
        assertEquals(CalibrationBaselineState(installer), CalibrationBaselineState.deserialize(CalibrationBaselineState(installer).serialize()))
    }

    @Test
    fun `time of the last write and unconfirmed records round trip`() {
        val state = CalibrationBaselineState(installer, domik, configurator, lastWriteAt = 1_234_567L, unconfirmed = listOf(domik, configurator))
        assertEquals(state, CalibrationBaselineState.deserialize(state.serialize()))
    }

    @Test
    fun `older v1 text is still read`() {
        val v1 = "v1;" + installer.encode().toHex() + ";" + domik.encode().toHex() + ";-"
        assertEquals(CalibrationBaselineState(installer, lastWrittenByDomik = domik), CalibrationBaselineState.deserialize(v1))
    }

    @Test
    fun `an unconfirmed record read back counts as domik's and a read settles the rest`() {
        val stored = CalibrationBaselineState(installer, pendingWrite = configurator).pendingFailed()
        assertEquals(listOf(configurator), stored.unconfirmed)
        assertNull(stored.pendingWrite)
        assertTrue(stored.mayHoldDomikShift)
        val arrived = CalibrationBaseline.reconcile(stored.copy(pendingWrite = domik), configurator)
        assertEquals(BaselineEvent.MATCHES_DOMIK_WRITE, arrived.event)
        assertEquals(CalibrationBaselineState(installer, lastWrittenByDomik = configurator), arrived.state)
        val none = CalibrationBaseline.reconcile(stored, installer)
        assertEquals(BaselineEvent.MATCHES_BASELINE, none.event)
        assertTrue(none.state.unconfirmed.isEmpty())
    }

    @Test
    fun `bad serialized text is rejected`() {
        assertNull(CalibrationBaselineState.deserialize(""))
        assertNull(CalibrationBaselineState.deserialize("v2;" + installer.encode().toHex() + ";-;-"))
        assertNull(CalibrationBaselineState.deserialize("v1;0100;-;-"))
        assertNull(CalibrationBaselineState.deserialize("v1;zz" + installer.encode().toHex().drop(2) + ";-;-"))
        assertNull(CalibrationBaselineState.deserialize("v1;" + installer.encode().toHex() + ";-"))
        assertNull(CalibrationBaselineState.deserialize("v2;" + installer.encode().toHex() + ";-;-;x;-"))
        assertNull(CalibrationBaselineState.deserialize("v2;" + installer.encode().toHex() + ";-;-;-;0100"))
    }

    @Test
    fun `in-memory store keeps one state per input`() {
        val store = InMemoryCalibrationBaselineStore()
        store.save(4, CalibrationBaselineState(installer))
        assertEquals(CalibrationBaselineState(installer), store.load(4))
        assertNull(store.load(3))
    }
}
