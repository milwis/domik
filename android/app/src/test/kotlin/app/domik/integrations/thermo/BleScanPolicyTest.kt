package app.domik.integrations.thermo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BleScanPolicyTest {
    @Test
    fun `first start is allowed at once`() {
        assertEquals(0L, BleScanPolicy().millisUntilStartAllowed(0))
    }

    @Test
    fun `starts are at least 7 s apart`() {
        val policy = BleScanPolicy()
        policy.recordStart(1_000)
        assertEquals(7_000L, policy.millisUntilStartAllowed(1_000))
        assertEquals(1_000L, policy.millisUntilStartAllowed(7_000))
        assertEquals(0L, policy.millisUntilStartAllowed(8_000))
    }

    @Test
    fun `never exceeds 5 starts in any 30 s window`() {
        val policy = BleScanPolicy()
        var now = 0L
        val starts = mutableListOf<Long>()
        repeat(40) {
            now += policy.millisUntilStartAllowed(now)
            policy.recordStart(now)
            policy.recordStop()
            starts += now
        }
        for (i in 5 until starts.size) {
            assertTrue(starts[i] - starts[i - 5] >= 30_000, "window violated at $i: $starts")
        }
        for (i in 1 until starts.size) assertTrue(starts[i] - starts[i - 1] >= 7_000)
    }

    @Test
    fun `window rule bites with a short minimum interval`() {
        val policy = BleScanPolicy(minStartIntervalMillis = 1_000)
        for (t in 0 until 5) policy.recordStart(t * 1_000L)
        // Szósty start dopiero, gdy pierwszy wypadnie z okna 30 s.
        assertEquals(30_000L - 4_000L, policy.millisUntilStartAllowed(4_000))
        assertEquals(0L, policy.millisUntilStartAllowed(30_000))
    }

    @Test
    fun `planned restart after 25 minutes of scanning`() {
        val policy = BleScanPolicy()
        assertNull(policy.millisUntilRestart(0))
        assertFalse(policy.isRestartDue(0))
        policy.recordStart(10_000)
        assertFalse(policy.isRestartDue(10_000 + 25 * 60_000 - 1))
        assertEquals(1L, policy.millisUntilRestart(10_000 + 25 * 60_000 - 1))
        assertTrue(policy.isRestartDue(10_000 + 25 * 60_000))
        policy.recordStop()
        assertNull(policy.millisUntilRestart(10_000 + 25 * 60_000))
        assertFalse(policy.isRestartDue(10_000 + 26 * 60_000))
    }

    @Test
    fun `restart is not delayed by the rate limit after a long scan`() {
        val policy = BleScanPolicy()
        policy.recordStart(0)
        val restartAt = 25L * 60_000
        policy.recordStop()
        assertEquals(0L, policy.millisUntilStartAllowed(restartAt))
    }

    @Test
    fun `clock going backwards does not block scanning forever`() {
        val policy = BleScanPolicy()
        policy.recordStart(100_000)
        assertEquals(0L, policy.millisUntilStartAllowed(5_000))
    }

    @Test
    fun `intensity follows mode`() {
        val policy = BleScanPolicy()
        assertEquals(ScanIntensity.NONE, policy.intensityFor(ScanMode.OFF))
        assertEquals(ScanIntensity.LOW_POWER, policy.intensityFor(ScanMode.BACKGROUND))
        assertEquals(ScanIntensity.LOW_LATENCY, policy.intensityFor(ScanMode.DISCOVERY))
    }
}
