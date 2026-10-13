package app.domik.integrations.thermo

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DemoThermometersTest {
    @Test
    fun `macFor gives locally administered addresses`() {
        assertEquals("02:00:00:00:00:01", DemoThermometers.macFor(1))
        assertEquals("02:00:00:00:00:0A", DemoThermometers.macFor(10))
    }

    @Test
    fun `does nothing while off`() = runTest {
        val source = DemoThermometers(backgroundScope, { 21f }, count = 2)
        val received = mutableListOf<ThermometerReading>()
        backgroundScope.launch { source.readings.toList(received) }
        runCurrent()
        advanceTimeBy(60_000)
        assertEquals(ScannerStatus.OFF, source.status.value)
        assertTrue(received.isEmpty())
    }

    @Test
    fun `emits first readings at once then every interval`() = runTest {
        val source = DemoThermometers(backgroundScope, { mac -> if (mac.endsWith("01")) 21.5f else 19f }, count = 2, clock = { 1234L })
        val received = mutableListOf<ThermometerReading>()
        backgroundScope.launch { source.readings.toList(received) }
        runCurrent()

        source.setMode(ScanMode.BACKGROUND)
        runCurrent()
        assertEquals(ScannerStatus.SCANNING, source.status.value)
        assertEquals(2, received.size)
        assertEquals("02:00:00:00:00:01", received[0].mac)
        assertEquals(21.5f, received[0].tempC)
        assertEquals(19f, received[1].tempC)
        assertEquals(1234L, received[0].receivedAtMillis)
        assertEquals(1, received[0].counter)

        advanceTimeBy(9_999)
        assertEquals(2, received.size)
        advanceTimeBy(2)
        assertEquals(4, received.size)
        assertEquals(2, received[2].counter)
        advanceTimeBy(10_000)
        assertEquals(6, received.size)
    }

    @Test
    fun `skips thermometers without temperature`() = runTest {
        val source = DemoThermometers(backgroundScope, { mac -> if (mac.endsWith("02")) null else 20f }, count = 3)
        val received = mutableListOf<ThermometerReading>()
        backgroundScope.launch { source.readings.toList(received) }
        runCurrent()
        source.setMode(ScanMode.DISCOVERY)
        runCurrent()
        assertEquals(listOf("02:00:00:00:00:01", "02:00:00:00:00:03"), received.map { it.mac })
    }

    @Test
    fun `stops after switching off and restarts`() = runTest {
        val source = DemoThermometers(backgroundScope, { 20f }, count = 1)
        val received = mutableListOf<ThermometerReading>()
        backgroundScope.launch { source.readings.toList(received) }
        runCurrent()
        source.setMode(ScanMode.BACKGROUND)
        source.setMode(ScanMode.BACKGROUND) // powtórne włączenie nie dubluje generatora
        runCurrent()
        assertEquals(1, received.size)

        source.setMode(ScanMode.OFF)
        assertEquals(ScannerStatus.OFF, source.status.value)
        advanceTimeBy(60_000)
        assertEquals(1, received.size)

        source.setMode(ScanMode.BACKGROUND)
        runCurrent()
        assertEquals(2, received.size)
        assertFalse(source.status.value == ScannerStatus.OFF)
    }

    @Test
    fun `reading toString masks the mac`() {
        val reading = ThermometerReading("A4:C1:38:11:22:33", null, -60, 21f, 40f, 90, 2900, 1, null, 0L)
        assertFalse(reading.toString().contains("A4:C1"))
        assertTrue(reading.toString().contains("33"))
    }
}
