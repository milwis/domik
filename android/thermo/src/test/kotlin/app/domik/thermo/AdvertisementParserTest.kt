package app.domik.thermo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Same vectors as `tools/xiaomi_probe/tests/test_adv_parser.py`. */
class AdvertisementParserTest {
    // MAC A4:C1:38:11:22:33; 21.37 °C; 45.67 %; 2950 mV; 87 %; counter 0x2A; flags 0.
    private val pvvx = hex("332211" + "38C1A4" + "5908" + "D711" + "860B" + "57" + "2A" + "00")

    // Same MAC in written order; 21.4 °C = 0x00D6; 45 %; 87 %; 2950 mV = 0x0B86; counter 0x2A.
    private val atc = hex("A4C13811" + "2233" + "00D6" + "2D" + "57" + "0B86" + "2A")

    private fun hex(text: String): ByteArray = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `pvvx decodes all fields`() {
        val reading = AdvertisementParser.parsePvvxCustom(pvvx)!!
        assertEquals(AdvertisementFormat.PVVX, reading.format)
        assertEquals("A4:C1:38:11:22:33", reading.mac)
        assertEquals(21.37f, reading.tempC, 1e-4f)
        assertEquals(45.67f, reading.humidity, 1e-4f)
        assertEquals(2950, reading.batteryMv)
        assertEquals(87, reading.batteryPercent)
        assertEquals(0x2A, reading.counter)
        assertEquals(0, reading.flags)
    }

    @Test
    fun `pvvx negative temperature`() {
        val data = pvvx.copyOf()
        data[6] = (-525 and 0xFF).toByte()
        data[7] = ((-525 shr 8) and 0xFF).toByte()
        assertEquals(-5.25f, AdvertisementParser.parsePvvxCustom(data)!!.tempC, 1e-4f)
    }

    @Test
    fun `pvvx is little-endian and unsigned for humidity and battery`() {
        val data = pvvx.copyOf()
        data[8] = 0x10 // humidity 0x2710 = 100.00 %
        data[9] = 0x27
        data[10] = 0xFF.toByte() // 0xFFFF mV must not turn negative
        data[11] = 0xFF.toByte()
        data[12] = 0xFF.toByte()
        val reading = AdvertisementParser.parsePvvxCustom(data)!!
        assertEquals(100f, reading.humidity, 1e-4f)
        assertEquals(65535, reading.batteryMv)
        assertEquals(255, reading.batteryPercent)
    }

    @Test
    fun `pvvx keeps flags`() {
        val data = pvvx.copyOf()
        data[14] = 0b00001000
        assertEquals(8, AdvertisementParser.parsePvvxCustom(data)!!.flags)
    }

    @Test
    fun `pvvx rejects wrong length`() {
        assertNull(AdvertisementParser.parsePvvxCustom(pvvx.copyOf(14)))
        assertNull(AdvertisementParser.parsePvvxCustom(pvvx + 0))
    }

    @Test
    fun `atc1441 decodes all fields`() {
        val reading = AdvertisementParser.parseAtc1441(atc)!!
        assertEquals(AdvertisementFormat.ATC1441, reading.format)
        assertEquals("A4:C1:38:11:22:33", reading.mac)
        assertEquals(21.4f, reading.tempC, 1e-4f)
        assertEquals(45f, reading.humidity, 1e-4f)
        assertEquals(87, reading.batteryPercent)
        assertEquals(2950, reading.batteryMv)
        assertEquals(0x2A, reading.counter)
        assertNull(reading.flags)
    }

    @Test
    fun `atc1441 is big-endian`() {
        val data = atc.copyOf()
        data[6] = 0x01 // 256 -> 25.6 °C big-endian (little-endian would give 0.1)
        data[7] = 0x00
        assertEquals(25.6f, AdvertisementParser.parseAtc1441(data)!!.tempC, 1e-4f)
    }

    @Test
    fun `atc1441 negative temperature`() {
        val data = atc.copyOf()
        data[6] = ((-52 shr 8) and 0xFF).toByte()
        data[7] = (-52 and 0xFF).toByte()
        assertEquals(-5.2f, AdvertisementParser.parseAtc1441(data)!!.tempC, 1e-4f)
    }

    @Test
    fun `atc1441 rejects wrong length`() {
        assertNull(AdvertisementParser.parseAtc1441(atc.copyOf(12)))
        assertNull(AdvertisementParser.parseAtc1441(atc + 0))
    }

    @Test
    fun `parse dispatches by length`() {
        assertEquals(AdvertisementFormat.PVVX, AdvertisementParser.parse(pvvx)!!.format)
        assertEquals(AdvertisementFormat.ATC1441, AdvertisementParser.parse(atc)!!.format)
        assertNull(AdvertisementParser.parse(ByteArray(14)))
        assertNull(AdvertisementParser.parse(ByteArray(0)))
    }

    @Test
    fun `same device gives the same mac in both formats`() {
        assertEquals(AdvertisementParser.parse(pvvx)!!.mac, AdvertisementParser.parse(atc)!!.mac)
    }

    @Test
    fun `normalizeMac accepts separators and case`() {
        assertEquals("A4:C1:38:11:22:33", AdvertisementParser.normalizeMac("a4-c1:38 11.22 33"))
        for (bad in listOf("", "A4:C1:38:11:22", "ZZ:C1:38:11:22:33", "A4:C1:38:11:22:33:44")) {
            assertThrows<IllegalArgumentException>(bad) { AdvertisementParser.normalizeMac(bad) }
        }
    }

    @Test
    fun `mac is masked in toString and maskMac`() {
        assertEquals("**:**:**:**:**:33", AdvertisementParser.maskMac("A4:C1:38:11:22:33"))
        for (data in listOf(pvvx, atc)) {
            val text = AdvertisementParser.parse(data)!!.toString()
            assertFalse(text.contains("A4:C1"), text)
            assertFalse(text.contains("38:11"), text)
            assertNotNull(Regex("""\*\*:33""").find(text))
        }
    }
}
