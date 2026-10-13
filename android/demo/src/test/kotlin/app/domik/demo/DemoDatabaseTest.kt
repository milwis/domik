package app.domik.demo

import app.domik.deimic.config.HomeConfig
import app.domik.deimic.config.HomeConfigParser
import app.domik.deimic.config.OutputKind
import app.domik.deimic.protocol.Crc16Arc
import app.domik.deimic.protocol.HeatingProfile
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DemoDatabaseTest {
    private val home = DemoHome.standard()
    private val database = DemoDatabase.build(home)
    private val config: HomeConfig = HomeConfigParser().parse(database.bytes())

    @Test
    fun parserReadsTheFictionalHomeBackWithoutIssues() {
        assertEquals(emptyList<Any>(), config.issues)
        assertEquals(home.levels.map { it.id to it.name }, config.levels.map { it.id to it.name })
        assertEquals(
            home.rooms.map { Triple(it.id, it.levelId, it.name) },
            config.rooms.map { Triple(it.id, it.levelId, it.name) },
        )
        assertEquals(home.modules, config.modules)
        assertEquals(home.outputs.size, config.outputs.size)
        assertEquals(home.zones.size, config.heatingZones.size)
        assertEquals(home.shutters.size, config.shutters.size)
    }

    @Test
    fun shuttersAreAddressedByModuleAndNumberAndAreNotOutputs() {
        for (spec in home.shutters) {
            val shutter = checkNotNull(config.shutter(spec.module, spec.outputNr)) { "shutter ${spec.module}:${spec.outputNr}" }
            assertEquals(spec.name, shutter.name)
            assertEquals(spec.roomId, shutter.roomId)
            assertEquals(home.levelOf(spec.roomId), shutter.levelId)
        }
        // The same number exists on two modules.
        assertEquals(2, config.shutters.count { it.outputNr == 0 })
        // Shutters have an address space of their own: their relay numbers may coincide with those of lights, and the
        // shutter rows never show up among the outputs.
        assertTrue(config.outputs.none { it.rawType == DemoOutputType.SHUTTER })
        assertEquals(home.outputs.size, config.outputs.size)
    }

    @Test
    fun outputsKeepTheirAddressNameRoomAndKind() {
        for (spec in home.outputs) {
            val output = checkNotNull(config.output(spec.module, spec.relay)) { "output ${spec.module}:${spec.relay}" }
            assertEquals(spec.name, output.name)
            assertEquals(spec.roomId, output.roomId)
            assertEquals(home.levelOf(spec.roomId), output.levelId)
            assertEquals(spec.outputType, output.rawType)
            assertEquals(spec.outputsNumber, output.outputsNumber)
            assertTrue(output.canOn)
        }
    }

    @Test
    fun zonesAreAddressedByModuleAndOutputNumber() {
        for (spec in home.zones) {
            val zone = checkNotNull(config.heatingZone(spec.module, spec.outputNr)) { "zone ${spec.module}:${spec.outputNr}" }
            assertEquals(spec.name, zone.name)
            assertEquals(spec.roomId, zone.roomId)
            assertEquals(home.levelOf(spec.roomId), zone.levelId)
        }
        // The same output number exists on two modules.
        assertEquals(2, config.heatingZones.count { it.outputNr == 0 })
    }

    @Test
    fun everyZoneFindsItsTemperatureInputThroughTheRoomAndTheNumbersDiffer() {
        for (spec in home.zones) {
            val zone = checkNotNull(config.heatingZone(spec.module, spec.outputNr))
            val inputs = config.temperatureInputsFor(zone)
            assertEquals(listOf(spec.inputNr), inputs.map { it.inputNr }, "zone ${spec.module}:${spec.outputNr}")
            assertEquals(spec.module, inputs.single().module)
            assertTrue(spec.inputNr != spec.outputNr, "input and zone numbers must differ in the demo home")
        }
        // The outdoor sensor is an input too, in a room without a zone.
        val outdoor = home.sensors.single()
        assertTrue(config.analogInputs.any { it.module == outdoor.module && it.inputNr == outdoor.number && it.roomId == outdoor.roomId })
        assertEquals(home.zones.size + home.sensors.size, config.analogInputs.size)
        assertTrue(config.analogInputs.all { it.isTemperature })
    }

    @Test
    fun theDemoHomeShowsAllKindsOfDevicesAndProfiles() {
        val kinds = config.outputs.map { it.kind }.toSet()
        assertEquals(setOf(OutputKind.ON_OFF, OutputKind.LED, OutputKind.DIMMER_ON_OFF, OutputKind.RGB), kinds)
        assertTrue(config.shutters.size >= 4, "several shutters in different rooms")
        assertTrue(config.shutters.map { it.roomId }.toSet().size >= 3)
        assertTrue(config.modules.size >= 2)
        assertTrue(config.heatingZones.size in 3..4)
        val profiles = home.zones.map { it.profile }.toSet()
        assertEquals(setOf(HeatingProfile.Comfort, HeatingProfile.Eco, HeatingProfile.Maximum, HeatingProfile.Off), profiles)
        assertTrue(config.levels.size >= 2 && config.rooms.size >= 6)
    }

    @Test
    fun sameHomeGivesIdenticalBytesAndChecksum() {
        val again = DemoDatabase.build(DemoHome.standard())
        assertArrayEquals(database.bytes(), again.bytes())
        assertEquals(database.checksum, again.checksum)
        assertEquals(Crc16Arc.checksum(database.bytes()), database.checksum)
    }

    @Test
    fun fileHasTheSqliteHeaderAndWholePages() {
        val bytes = database.bytes()
        assertEquals("SQLite format 3\u0000", String(bytes, 0, 16, Charsets.US_ASCII))
        val pageSize = ((bytes[16].toInt() and 0xFF) shl 8) or (bytes[17].toInt() and 0xFF)
        assertEquals(0, bytes.size % pageSize)
        val pageCount = ((bytes[28].toInt() and 0xFF) shl 24) or ((bytes[29].toInt() and 0xFF) shl 16) or
            ((bytes[30].toInt() and 0xFF) shl 8) or (bytes[31].toInt() and 0xFF)
        assertEquals(bytes.size / pageSize, pageCount)
    }

    @Test
    fun aBigHomeUsesLargerPagesAndStillParses() {
        val rooms = listOf(DemoRoom(1, 1, "Pokój"))
        val outputs = (1..3).flatMap { module ->
            (0..99).map { DemoOutput(module, it, "Światło numer $it w module $module", roomId = 1) }
        }
        val big = DemoHome("Duży", listOf(DemoLevel(1, "Parter")), rooms, outputs, zones = emptyList())
        val bytes = DemoDatabase.build(big).bytes()
        assertTrue(bytes.size > DemoDatabase.build(home).size)
        val parsed = HomeConfigParser().parse(bytes)
        assertEquals(300, parsed.outputs.size)
        assertEquals("Światło numer 42 w module 3", parsed.output(3, 42)?.name)
    }
}
