package app.domik.integrations.thermo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ThermometerDirectoryTest {
    private fun reading(mac: String, counter: Int, temp: Float = 21f, rssi: Int = -60) = ThermometerReading(
        mac = mac, name = null, rssi = rssi, tempC = temp, humidity = 45f, batteryPercent = 80, batteryMv = 2900,
        counter = counter, flags = null, receivedAtMillis = 0,
    )

    private val a = "02:00:00:00:00:01"
    private val b = "02:00:00:00:00:02"

    @Test
    fun `powtorzony pomiar nie jest nowym odczytem ale odswieza sygnal`() {
        val directory = ThermometerDirectory()
        assertTrue(directory.onReading(reading(a, 7, rssi = -60), at = 1_000))
        assertFalse(directory.onReading(reading(a, 7, temp = 99f, rssi = -50), at = 3_500))

        val entry = checkNotNull(directory.entry(a))
        assertEquals(21f, entry.reading.tempC)
        assertEquals(-50, entry.reading.rssi)
        assertEquals(1_000, entry.receivedAt)
        assertEquals(3_500, entry.lastSeenAt)
    }

    @Test
    fun `nowy licznik albo ten sam po oknie to nowy pomiar`() {
        val directory = ThermometerDirectory(duplicateWindowMillis = 60_000)
        assertTrue(directory.onReading(reading(a, 7), at = 0))
        assertTrue(directory.onReading(reading(a, 8, temp = 21.2f), at = 10_000))
        assertTrue(directory.onReading(reading(a, 8, temp = 21.4f), at = 80_000))
        assertEquals(21.4f, directory.entry(a)?.reading?.tempC)
    }

    @Test
    fun `adres bez wzgledu na wielkosc liter`() {
        val directory = ThermometerDirectory()
        directory.onReading(reading("02:00:00:00:00:0a", 1), at = 0)
        assertEquals("02:00:00:00:00:0A", directory.entry("02:00:00:00:00:0A")?.mac)
        assertFalse(directory.onReading(reading("02:00:00:00:00:0A", 1), at = 1))
    }

    @Test
    fun `lista od najsilniejszego sygnalu i ograniczona pojemnosc`() {
        val directory = ThermometerDirectory(capacity = 2)
        directory.onReading(reading(a, 1, rssi = -80), at = 0)
        directory.onReading(reading(b, 1, rssi = -40), at = 1)
        assertEquals(listOf(b, a), directory.all().map { it.mac })

        directory.onReading(reading("02:00:00:00:00:03", 1, rssi = -70), at = 2)
        assertNull(directory.entry(a)) // najdawniej widziany wypada
        assertEquals(2, directory.all().size)
    }
}
