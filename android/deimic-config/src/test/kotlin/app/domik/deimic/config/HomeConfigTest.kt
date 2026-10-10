package app.domik.deimic.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Lookups of the in-memory model. */
class HomeConfigTest {
    private fun output(db: Long, module: Int, relay: Int, kind: OutputKind, room: Int?, level: Int?) = Output(
        dbId = db, module = module, relay = relay, kind = kind, rawType = kind.rawType ?: 99, name = "o$db", roomId = room,
        levelId = level, iconId = null, canOn = true, outputsNumber = null,
    )

    private val config = HomeConfig(
        levels = listOf(Level(1, "Parter (test)"), Level(2, "Piętro (test)")),
        rooms = listOf(Room(10, 1, "Salon (test)"), Room(11, 1, "Kuchnia (test)"), Room(20, 2, "Sypialnia"), Room(30, null, "Garaż")),
        modules = listOf(1, 2),
        outputs = listOf(
            output(1, 1, 0, OutputKind.ON_OFF, 10, 1),
            output(2, 1, 1, OutputKind.LED, 10, 1),
            output(3, 1, 2, OutputKind.RGB, 11, 1),
            output(4, 2, 0, OutputKind.ON_OFF, 20, 2),
            output(5, 2, 1, OutputKind.UNKNOWN, null, null),
        ),
        heatingZones = listOf(
            HeatingZone(7, 1, 0, "Salon (test)", 10, 1),
            HeatingZone(8, 2, 3, "Sypialnia", 20, 2),
        ),
        temperatureSensors = listOf(
            TemperatureSensor(9, 1, 0, "Czujnik salon (test)", 10, 1),
            TemperatureSensor(11, 2, 3, "Czujnik sypialnia", 20, 2),
            TemperatureSensor(12, 2, 4, "Czujnik bez pokoju", null, null),
        ),
        shutters = listOf(
            Shutter(13, 1, 0, "Roleta salon (test)", 10, 1, 0),
            Shutter(14, 1, 1, "Roleta kuchnia (test)", 11, 1, null),
            Shutter(15, 2, 0, "Roleta sypialnia", 20, 2, 0),
            Shutter(16, 2, 5, "Roleta bez pokoju", null, null, null),
        ),
    )

    @Test
    fun lookupsById() {
        assertEquals("Parter (test)", config.level(1)!!.name)
        assertNull(config.level(3))
        assertEquals("Kuchnia (test)", config.room(11)!!.name)
        assertNull(config.room(99))
    }

    @Test
    fun roomsAndItemsPerLevelRoomAndModule() {
        assertEquals(listOf(10, 11), config.roomsOnLevel(1).map { it.id })
        assertEquals(listOf(30), config.rooms.filter { it.levelId == null }.map { it.id })
        assertEquals(listOf(1L, 2L), config.outputsInRoom(10).map { it.dbId })
        assertEquals(listOf(7L), config.heatingZonesInRoom(10).map { it.dbId })
        assertEquals(listOf(1L, 2L, 3L), config.outputsOnLevel(1).map { it.dbId })
        assertEquals(listOf(8L), config.heatingZonesOnLevel(2).map { it.dbId })
        assertEquals(listOf(4L, 5L), config.outputsOfModule(2).map { it.dbId })
        assertEquals(listOf(7L), config.heatingZonesOfModule(1).map { it.dbId })
        assertEquals(listOf(2L), config.outputsOfKind(OutputKind.LED).map { it.dbId })
    }

    @Test
    fun addressLookups() {
        assertEquals(3L, config.output(1, 2)!!.dbId)
        assertNull(config.output(1, 3))
        assertNull(config.output(3, 0))
        assertEquals(8L, config.heatingZone(2, 3)!!.dbId)
        assertNull(config.heatingZone(2, 0))
    }

    @Test
    fun sensorLookups() {
        assertEquals(listOf(9L), config.temperatureSensorsInRoom(10).map { it.dbId })
        assertEquals(listOf(11L), config.temperatureSensorsOnLevel(2).map { it.dbId })
        assertEquals(listOf(11L, 12L), config.temperatureSensorsOfModule(2).map { it.dbId })
        assertEquals(11L, config.temperatureSensor(2, 3)!!.dbId)
        assertNull(config.temperatureSensor(2, 0), "numbers are not relays or zones")
        assertEquals("Sypialnia", config.roomOf(config.temperatureSensor(2, 3)!!)!!.name)
        assertNull(config.roomOf(config.temperatureSensor(2, 4)!!))
    }

    @Test
    fun shutterLookups() {
        assertEquals(listOf(13L), config.shuttersInRoom(10).map { it.dbId })
        assertEquals(listOf(13L, 14L), config.shuttersOnLevel(1).map { it.dbId })
        assertEquals(listOf(15L, 16L), config.shuttersOfModule(2).map { it.dbId })
        assertEquals(15L, config.shutter(2, 0)!!.dbId)
        assertEquals(14L, config.shutter(1, 1)!!.dbId)
        assertNull(config.shutter(1, 2), "no shutter with this number")
        assertNull(config.shutter(3, 0))
        assertNull(config.output(2, 5), "a shutter number is not a relay")
        assertEquals("Sypialnia", config.roomOf(config.shutter(2, 0)!!)!!.name)
        assertNull(config.roomOf(config.shutter(2, 5)!!))
        assertEquals(2u, config.shutter(2, 5)!!.moduleAddress)
        assertEquals(5u.toUByte(), config.shutter(2, 5)!!.shutterByte)
    }

    @Test
    fun aConfigurationWithOnlyShuttersIsNotEmpty() {
        val onlyShutters = HomeConfig(
            emptyList(), emptyList(), listOf(1), emptyList(), emptyList(), shutters = listOf(Shutter(1, 1, 0, "r", null, null, null)),
        )
        assertEquals(false, onlyShutters.isEmpty)
        assertEquals(emptyList<Shutter>(), HomeConfig(emptyList(), emptyList(), emptyList(), emptyList(), emptyList()).shutters)
    }

    @Test
    fun anItemOfUnknownKindNeverShadowsARealOutputWithTheSameAddress() {
        val unknownFirst = HomeConfig(
            levels = emptyList(), rooms = emptyList(), modules = listOf(1),
            outputs = listOf(output(1, 1, 5, OutputKind.UNKNOWN, null, null), output(2, 1, 5, OutputKind.ON_OFF, null, null)),
            heatingZones = emptyList(),
        )
        assertEquals(2L, unknownFirst.output(1, 5)!!.dbId)
        val onlyUnknown = unknownFirst.copy(outputs = unknownFirst.outputs.take(1))
        assertEquals(1L, onlyUnknown.output(1, 5)!!.dbId)
    }

    @Test
    fun configurationsBuiltWithoutSensorsStillWork() {
        val old = HomeConfig(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        assertEquals(emptyList<TemperatureSensor>(), old.temperatureSensors)
        assertNull(old.temperatureSensor(1, 0))
    }

    @Test
    fun supportedOutputsAndRoomOf() {
        assertEquals(listOf(1L, 2L, 3L, 4L), config.supportedOutputs.map { it.dbId })
        assertEquals("Sypialnia", config.roomOf(config.output(2, 0)!!)!!.name)
        assertNull(config.roomOf(config.output(2, 1)!!))
        assertEquals("Salon (test)", config.roomOf(config.heatingZone(1, 0)!!)!!.name)
    }

    @Test
    fun commandHelpers() {
        val output = config.output(2, 1)!!
        assertEquals(2u, output.moduleAddress)
        assertEquals(1u.toUByte(), output.relayByte)
        assertEquals(false, config.output(2, 1)!!.isSupported)
        assertEquals(false, config.isEmpty)
        assertEquals(true, HomeConfig(emptyList(), emptyList(), emptyList(), emptyList(), emptyList()).isEmpty)
    }
}
