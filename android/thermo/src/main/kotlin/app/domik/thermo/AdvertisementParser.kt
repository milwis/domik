package app.domik.thermo

/** Advertisement layout of the thermometer firmware. */
enum class AdvertisementFormat {
    /** pvvx "custom" format: 15 bytes, little-endian. */
    PVVX,

    /** ATC1441 format: 13 bytes, big-endian. */
    ATC1441,
}

/**
 * One decoded advertisement of a Xiaomi thermometer running pvvx/ATC firmware.
 *
 * [toString] never prints the full MAC address (only its last byte), so readings are safe to log.
 */
data class Reading(
    val format: AdvertisementFormat,
    /** Normalized as `AA:BB:CC:DD:EE:FF` (upper case, in the usual written order). */
    val mac: String,
    val tempC: Float,
    /** Relative humidity in percent. */
    val humidity: Float,
    val batteryPercent: Int,
    val batteryMv: Int,
    val counter: Int,
    /** Raw flags byte; only present in the pvvx format. */
    val flags: Int?,
) {
    override fun toString(): String =
        "Reading($format ${AdvertisementParser.maskMac(mac)} $tempC °C $humidity % " +
            "bat $batteryPercent % ($batteryMv mV) #$counter)"
}

/**
 * Decoder for BLE service data with UUID 0x181A as sent by Xiaomi LYWSD03MMC-class thermometers with pvvx/ATC
 * firmware (ported 1:1 from `tools/xiaomi_probe/adv_parser.py`). All functions take the bytes that follow the UUID.
 *
 * pvvx custom, 15 bytes, little-endian:
 * `[0..5]` MAC (byte 0 = least significant), `[6..7]` int16 temperature ×0.01 °C, `[8..9]` uint16 humidity
 * ×0.01 %, `[10..11]` uint16 battery mV, `[12]` battery %, `[13]` counter, `[14]` flags.
 *
 * ATC1441, 13 bytes, big-endian:
 * `[0..5]` MAC (written order), `[6..7]` int16 temperature ×0.1 °C, `[8]` humidity %, `[9]` battery %,
 * `[10..11]` uint16 battery mV, `[12]` counter.
 *
 * pvvx firmware 6.0 and later only sends BTHome v2 (UUID 0xFCD2), which is not supported here.
 */
object AdvertisementParser {
    const val PVVX_CUSTOM_LENGTH: Int = 15
    const val ATC1441_LENGTH: Int = 13

    private const val MAC_LENGTH = 6

    /** Recognizes the format by length; null for other frames (foreign devices, other firmware). */
    fun parse(data: ByteArray): Reading? = parsePvvxCustom(data) ?: parseAtc1441(data)

    fun parsePvvxCustom(data: ByteArray): Reading? {
        if (data.size != PVVX_CUSTOM_LENGTH) return null
        val mac = (MAC_LENGTH - 1 downTo 0).joinToString(":") { hex(data[it]) }
        return Reading(
            format = AdvertisementFormat.PVVX,
            mac = mac,
            tempC = int16Le(data, 6) / 100f,
            humidity = uint16Le(data, 8) / 100f,
            batteryPercent = u8(data, 12),
            batteryMv = uint16Le(data, 10),
            counter = u8(data, 13),
            flags = u8(data, 14),
        )
    }

    fun parseAtc1441(data: ByteArray): Reading? {
        if (data.size != ATC1441_LENGTH) return null
        val mac = (0 until MAC_LENGTH).joinToString(":") { hex(data[it]) }
        return Reading(
            format = AdvertisementFormat.ATC1441,
            mac = mac,
            tempC = int16Be(data, 6) / 10f,
            humidity = u8(data, 8).toFloat(),
            batteryPercent = u8(data, 9),
            batteryMv = uint16Be(data, 10),
            counter = u8(data, 12),
            flags = null,
        )
    }

    /**
     * "aa-bb:cc dd ee ff" -> "AA:BB:CC:DD:EE:FF".
     * @throws IllegalArgumentException if the text is not 6 bytes in hexadecimal notation
     */
    fun normalizeMac(mac: String): String {
        val digits = mac.filter { it !in ":-. " }.uppercase()
        require(digits.length == 2 * MAC_LENGTH && digits.all { it in '0'..'9' || it in 'A'..'F' }) {
            "MAC must be 6 bytes in hexadecimal notation"
        }
        return digits.chunked(2).joinToString(":")
    }

    /** For logs: only the last byte stays visible. */
    fun maskMac(mac: String): String = "**:**:**:**:**:" + mac.replace('-', ':').substringAfterLast(':').uppercase()

    private fun u8(data: ByteArray, index: Int): Int = data[index].toInt() and 0xFF

    private fun hex(byte: Byte): String = "%02X".format(byte.toInt() and 0xFF)

    private fun uint16Le(data: ByteArray, index: Int): Int = u8(data, index) or (u8(data, index + 1) shl 8)

    private fun uint16Be(data: ByteArray, index: Int): Int = (u8(data, index) shl 8) or u8(data, index + 1)

    private fun int16Le(data: ByteArray, index: Int): Int = uint16Le(data, index).toShort().toInt()

    private fun int16Be(data: ByteArray, index: Int): Int = uint16Be(data, index).toShort().toInt()
}
