package app.domik.deimic.protocol

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.fail
import kotlin.math.abs

/**
 * Cross-check with an independent Python implementation: `testdata/recorded_frames.json` holds recorded frames
 * (serial replaced with 0x12345678), synthetic state records, request vectors and CRC vectors together with
 * the values that implementation decoded from them.
 */
class FixtureTest {
    @Test
    fun fixtureMetadata() {
        assertTrue(fixtureFile.isFile, "missing ${fixtureFile.path}")
        assertEquals(0x12345678L, fixtures.long("fakeSerial"))
        assertTrue(fixtures.string("note").contains("0x12345678"))
        assertTrue(recordedFrames.size >= 20)
        val commands = recordedFrames.map { it.long("command") }.toSet()
        assertTrue(commands.intersect(setOf(0x222L, 0x254L, 0xFF3L)).isEmpty())
    }

    @TestFactory
    fun frames(): List<DynamicTest> = (recordedFrames + syntheticStates + shutterTrace).map { fixture ->
        val name = "${fixture.string("source")} ${CommandCode(fixture.long("command").toUInt())}"
        DynamicTest.dynamicTest(name) { checkFrame(fixture) }
    }

    @Test
    fun wholeStreamInRandomChunks() {
        val expected = recordedFrames.map { hex(it.string("frameHex")) }
        val stream = expected.fold(Bytes.EMPTY) { acc, bytes -> acc + bytes }.toByteArray()
        val random = SplitMix64(42u)
        val decoder = FrameDecoder()
        val frames = mutableListOf<Frame>()
        var start = 0
        while (start < stream.size) {
            val end = minOf(stream.size, start + (random.next() % 3000u).toInt() + 1)
            frames += decoder.append(stream, start, end)
            start = end
        }
        assertEquals(expected, frames.map { it.encode() })
    }

    @Test
    fun requestsMatchPython() {
        val serial = SerialNumber(fixtures.long("fakeSerial").toUInt())
        val built = mapOf(
            "ping" to Request.ping(serial),
            "versionGet" to Request.firmwareVersion(serial),
            "sendCrc" to Request.configChecksum(serial),
            "fileDownload:/logs/database-last.db3" to Request.fileDownload(serial, "/logs/database-last.db3"),
            "getState:new" to Request.getState(serial, StateRequestVariant.NEW),
            "getState:v2" to Request.getState(serial, StateRequestVariant.V2),
            "getState:v3" to Request.getState(serial, StateRequestVariant.V3),
            "heaterState:5:3" to Request.heaterState(serial, 5u, 3u),
            "temperatureReadout" to Request.temperatureReadout(serial),
            "setOutput:3:2:on" to Request.setOutput(serial, 3u, 2u, on = true),
            "setOutput:1:12:off" to Request.setOutput(serial, 1u, 12u, on = false),
            "setSetpoint:5:3:21.5" to Request.setSetpoint(serial, 5u, 3u, 21.5f),
            "setSetpoint:1:5:22.5" to Request.setSetpoint(serial, 1u, 5u, 22.5f),
            "setProfile:1:5:comfort" to Request.setProfile(serial, 1u, 5u, HeatingProfile.Comfort),
            "shutterUp:1:3" to Request.shutterUp(serial, 1u, 3u),
            "shutterDown:1:3" to Request.shutterDown(serial, 1u, 3u),
            "shutterStop:1:3" to Request.shutterStop(serial, 1u, 3u),
        )
        val vectors = fixtures.objects("requests")
        assertEquals(built.keys, vectors.map { it.string("name") }.toSet())
        for (vector in vectors) {
            val name = vector.string("name")
            assertEquals(vector.string("frameHex"), built.getValue(name).encode().toHex(), name)
        }
    }

    @Test
    fun crcMatchesPython() {
        val vectors = fixtures.objects("crc16")
        assertTrue(vectors.isNotEmpty())
        for (vector in vectors) {
            assertEquals(vector.long("crc").toInt(), Crc16Arc.checksum(hex(vector.string("inputHex"))).toInt())
        }
    }

    private fun checkFrame(fixture: Json) {
        val bytes = hex(fixture.string("frameHex"))
        val frames = FrameDecoder().append(bytes)
        assertEquals(1, frames.size)
        val frame = frames.first()
        assertEquals(0x12345678u, frame.serial.value)
        assertEquals(fixture.long("address").toUInt(), frame.address)
        assertEquals(fixture.long("command").toUInt(), frame.command.raw)
        assertEquals(fixture.long("group").toUInt(), frame.group)
        assertEquals(fixture.long("flags").toUInt(), frame.flags)
        assertEquals(fixture.long("payloadLength").toInt(), frame.payload.size)
        assertEquals(bytes, frame.encode())
        checkMessage(ControllerMessage.decode(frame), fixture.obj("expect"))
    }

    private fun checkMessage(message: ControllerMessage, expect: Json) {
        val kind = expect.string("kind")
        when {
            kind == "heaterState" && message is HeaterState -> {
                assertEquals(expect.long("module").toUInt(), message.module)
                assertEquals(expect.long("outputNr").toInt(), message.outputNr.toInt())
                assertEquals(expect.boolean("isHeating"), message.isHeating)
                assertEquals(expect.string("currentBits"), message.currentTemperature.bitsHex())
                assertEquals(expect.string("setpointBits"), message.setpoint.bitsHex())
                assertMatchesRounded(message.currentTemperature, expect.double("current"))
                assertMatchesRounded(message.setpoint, expect.double("setpoint"))
                assertEquals(expect.string("profile"), message.profile.toString())
            }
            kind == "temperatureReport" && message is TemperatureReport -> {
                assertEquals(expect.long("module").toUInt(), message.module)
                assertEquals(expect.long("levelId").toInt(), message.levelId.toInt())
                assertEquals(expect.long("roomId").toInt(), message.roomId.toInt())
                assertEquals(expect.longOrNull("sensorNumber")?.toInt(), message.sensorNumber?.toInt())
                assertEquals(expect.string("temperatureBits"), message.temperature.bitsHex())
                assertMatchesRounded(message.temperature, expect.double("temperature"))
            }
            kind == "analogValueChanged" && message is AnalogValueChanged -> {
                assertEquals(expect.long("module").toUInt(), message.module)
                assertEquals(expect.long("input").toInt(), message.input.toInt())
                assertEquals(expect.string("valueBits"), message.value.bitsHex())
            }
            kind == "ack" && message is Ack -> {
                assertEquals(expect.long("address").toUInt(), message.address)
                assertEquals(expect.long("command").toUInt(), message.command.raw)
                assertEquals(expect.long("group").toUInt(), message.group)
                assertEquals(expect.long("flags").toUInt(), message.flags)
                assertEquals(expect.long("payloadLength").toUInt(), message.payloadLength)
                assertEquals(expect.string("payloadHex"), message.payload.toHex())
            }
            kind == "shutterState" && message is ShutterState -> {
                assertEquals(expect.long("module").toUInt(), message.module)
                assertEquals(expect.long("outputNr").toInt(), message.outputNr.toInt())
                assertEquals(expect.long("rawDirection").toInt(), message.rawDirection.toInt())
                assertEquals(expect.string("direction"), message.direction.name)
                assertEquals(expect.longOrNull("positionTenths"), message.positionPercent?.let { Math.round(it * 10f).toLong() })
            }
            kind == "pong" && message is ControllerMessage.Pong ->
                assertEquals(expect.long("module").toUInt(), message.module)
            kind == "unknown" && message is ControllerMessage.Unknown -> Unit
            kind == "stateSnapshot" && message is StateSnapshot -> checkState(message, expect.objects("modules"))
            else -> fail("expected $kind, got $message")
        }
    }

    private fun checkState(snapshot: StateSnapshot, expected: List<Json>) {
        assertTrue(snapshot.issues.isEmpty())
        assertEquals(expected.isEmpty(), snapshot.isEmpty)
        assertEquals(expected.map { it.long("module").toUInt() }, snapshot.modules.map { it.module })
        for ((module, python) in snapshot.modules.zip(expected)) {
            assertEquals(referenceFormatLabel(python.string("format")), module.format.label)
            assertEquals(python.longs("onOutputs").map { it.toInt() }, module.onOutputs)
            val heaters = python.longsOrNull("heaters")
            if (heaters != null) {
                assertEquals(heaters.map { it.toInt() }, module.heaters?.map { it.raw.toInt() })
            } else {
                assertNull(module.heaters)
            }
            val bits = python.stringsOrNull("temperatureBits")
            if (bits != null) {
                assertEquals(bits, checkNotNull(module.temperatures).map { it.bitsHex() })
            }
        }
    }

    /** The reference client spells the "new" format with a trailing underscore. */
    private fun referenceFormatLabel(python: String): String = python.trimEnd('_')

    /** The reference client rounds to 2 decimals; compare the Kotlin float the same way. */
    private fun assertMatchesRounded(value: Float, python: Double) {
        val rounded = Math.round(value.toDouble() * 100) / 100.0
        assertTrue(abs(rounded - python) <= 0.005 + 1e-9, "$value vs $python")
    }

    /** Thin typed view over a parsed JSON object. */
    class Json(private val map: Map<String, Any?>) {
        private fun value(key: String): Any? {
            assertTrue(map.containsKey(key), "missing key $key")
            return map[key]
        }

        fun string(key: String): String = value(key) as String
        fun long(key: String): Long = value(key) as Long
        fun longOrNull(key: String): Long? = map[key] as Long?
        fun double(key: String): Double = (value(key) as Number).toDouble()
        fun boolean(key: String): Boolean = value(key) as Boolean
        fun obj(key: String): Json = Json(asMap(value(key)))
        fun objects(key: String): List<Json> = (value(key) as List<*>).map { Json(asMap(it)) }
        fun longs(key: String): List<Long> = (value(key) as List<*>).map { it as Long }
        fun longsOrNull(key: String): List<Long>? = (map[key] as List<*>?)?.map { it as Long }
        fun stringsOrNull(key: String): List<String>? = (map[key] as List<*>?)?.map { it as String }

        private fun asMap(value: Any?): Map<String, Any?> =
            (value as Map<*, *>).entries.associate { (key, item) -> key as String to item }
    }

    companion object {
        private val fixtureFile: File by lazy {
            val dir = checkNotNull(System.getProperty("domik.testdata")) { "system property domik.testdata not set" }
            File(dir, "recorded_frames.json")
        }

        private val fixtures: Json by lazy {
            @Suppress("UNCHECKED_CAST")
            Json(MiniJson.parse(fixtureFile.readText()) as Map<String, Any?>)
        }

        private val recordedFrames: List<Json> by lazy { fixtures.objects("recordedFrames") }
        private val syntheticStates: List<Json> by lazy { fixtures.objects("syntheticStates") }
        private val shutterTrace: List<Json> by lazy { fixtures.objects("shutterTrace") }
    }
}
