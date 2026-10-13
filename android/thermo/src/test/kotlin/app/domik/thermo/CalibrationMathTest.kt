package app.domik.thermo

import app.domik.deimic.protocol.InputCalibration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CalibrationMathTest {
    /** Installer's calibration of input 4 on the owner's controller (22.5 → 18.0 °C), `71_sensor_calibration.md` §7. */
    private val i4 = InputCalibration(1u, 0u, 225f, 0f, 180f, 0f)
    private val inactive = InputCalibration(0u, 0u, 0f, 0f, 0f, 0f)
    private val twoPoint = InputCalibration(1u, 1u, 100f, 300f, 90f, 310f) // slope 1.1

    @Test
    fun `one point is an offset and inverts exactly`() {
        assertEquals(213.5, CalibrationMath.apply(i4, 258.5), 1e-9)
        assertEquals(258.5, CalibrationMath.invert(i4, 213.5)!!, 1e-9)
        assertEquals(213.5, CalibrationMath.apply(inactive, 213.5), 1e-9)
        val point2Only = InputCalibration(0u, 1u, 0f, 200f, 0f, 210f)
        assertEquals(110.0, CalibrationMath.apply(point2Only, 100.0), 1e-9)
    }

    @Test
    fun `two points map through a line`() {
        assertEquals(90.0, CalibrationMath.apply(twoPoint, 100.0), 1e-9)
        assertEquals(310.0, CalibrationMath.apply(twoPoint, 300.0), 1e-9)
        assertEquals(200.0, CalibrationMath.apply(twoPoint, 200.0), 1e-9)
        assertEquals(250.0, CalibrationMath.invert(twoPoint, CalibrationMath.apply(twoPoint, 250.0))!!, 1e-9)
        val flat = InputCalibration(1u, 1u, 100f, 300f, 200f, 200f)
        assertNull(CalibrationMath.invert(flat, 200.0))
        assertFalse(CalibrationMath.isUsable(flat))
    }

    @Test
    fun `usable records`() {
        assertTrue(CalibrationMath.isUsable(i4))
        assertTrue(CalibrationMath.isUsable(inactive))
        // Empty slots read from the live controller: no active point, NaN values.
        assertTrue(CalibrationMath.isUsable(InputCalibration(0u, 0u, Float.NaN, Float.NaN, Float.NaN, Float.NaN)))
        assertFalse(CalibrationMath.isUsable(InputCalibration(1u, 0u, Float.NaN, 0f, 180f, 0f)))
        assertFalse(CalibrationMath.isUsable(InputCalibration(0u, 1u, 0f, 10f, 0f, Float.POSITIVE_INFINITY)))
    }

    @Test
    fun `shift moves the true value by whole tenths and keeps measured and active bytes`() {
        val up = CalibrationMath.shifted(i4, 258.5, 10.0)
        assertEquals(InputCalibration(1u, 0u, 225f, 0f, 190f, 0f), up) // the live test of 2026-10-12: 18.0 → 19.0
        assertEquals(175f, CalibrationMath.shifted(i4, 258.5, -5.1).true1Tenths)
        assertEquals(176f, CalibrationMath.shifted(i4, 258.5, -4.4).true1Tenths)
        assertSame(i4, CalibrationMath.shifted(i4, 258.5, 0.3))
        val odd = InputCalibration(7u, 0u, 225f, 0f, 180.04f, 0f)
        val shiftedOdd = CalibrationMath.shifted(odd, 250.0, 7.0)
        assertEquals(187f, shiftedOdd.true1Tenths)
        assertEquals(7.toUByte(), shiftedOdd.active1)
    }

    @Test
    fun `two point shift keeps the slope`() {
        val moved = CalibrationMath.shifted(twoPoint, 200.0, -15.0)
        assertEquals(InputCalibration(1u, 1u, 100f, 300f, 75f, 295f), moved)
        assertEquals(-15.0, CalibrationMath.shiftFromBaselineTenths(moved, twoPoint, 123.0), 1e-9)
    }

    @Test
    fun `no active point becomes a one point record at the current value`() {
        val created = CalibrationMath.shifted(inactive, 221.6, -10.4)
        assertEquals(InputCalibration(1u, 0u, 222f, 0f, 212f, 0f), created)
        assertEquals(-10.0, CalibrationMath.shiftFromBaselineTenths(created, inactive, 221.6), 1e-9)
    }

    @Test
    fun `records compare byte exactly`() {
        val nan = InputCalibration(0u, 0u, Float.NaN, Float.NaN, Float.NaN, Float.NaN)
        assertTrue(CalibrationMath.same(nan, nan.copy()))
        assertFalse(CalibrationMath.same(i4, i4.copy(true1Tenths = 180.01f)))
        assertTrue(CalibrationMath.same(null, null))
        assertFalse(CalibrationMath.same(i4, null))
    }
}
