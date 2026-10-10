package app.domik.deimic.config

import app.domik.deimic.config.ConfigDatabaseBuilder.level
import app.domik.deimic.config.ConfigDatabaseBuilder.output
import app.domik.deimic.config.ConfigDatabaseBuilder.room
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows

/** Mapping of the controller database to [HomeConfig]. */
class HomeConfigParserTest {
    private val parser = HomeConfigParser()

    private fun parse(
        levels: List<List<Any?>> = listOf(level(1, "Parter (test)")),
        rooms: List<List<Any?>> = listOf(room(1, 1, "Salon (test)")),
        outputs: List<List<Any?>> = emptyList(),
        parser: HomeConfigParser = this.parser,
    ): HomeConfig = parser.parse(ConfigDatabaseBuilder.database(levels, rooms, outputs))

    private fun HomeConfig.issueKinds() = issues.map { it.kind }

    // --- against real SQLite ------------------------------------------------------------------------------------

    private val expected = expectedDocument.obj("config")

    @TestFactory
    fun mappingMatchesTheIndependentPythonImplementation(): List<DynamicTest> =
        listOf("config_synthetic.db3", "config_synthetic_4096.db3").map { name ->
            DynamicTest.dynamicTest(name) {
                val config = parser.parse(fixtureFile(name).readBytes())
                assertEquals(expected.objects("levels").map { it.int("id") to it.string("name") }, config.levels.map { it.id to it.name })
                assertEquals(
                    expected.objects("rooms").map { Triple(it.int("id"), it.intOrNull("levelId"), it.string("name")) },
                    config.rooms.map { Triple(it.id, it.levelId, it.name) },
                )
                assertEquals(expected.list("modules").map { (it as Long).toInt() }, config.modules)

                val expectedOutputs = expected.objects("outputs")
                assertEquals(expectedOutputs.size, config.outputs.size)
                expectedOutputs.zip(config.outputs).forEach { (want, got) ->
                    val where = "output dbId ${got.dbId}"
                    assertEquals(want.long("dbId"), got.dbId, where)
                    assertEquals(want.int("module"), got.module, where)
                    assertEquals(want.int("relay"), got.relay, where)
                    assertEquals(want.string("kind"), got.kind.name, where)
                    assertEquals(want.int("rawType"), got.rawType, where)
                    assertEquals(want.string("name"), got.name, where)
                    assertEquals(want.intOrNull("roomId"), got.roomId, where)
                    assertEquals(want.intOrNull("levelId"), got.levelId, where)
                    assertEquals(want.intOrNull("iconId"), got.iconId, where)
                    assertEquals(want["canOn"], got.canOn, where)
                    assertEquals(want.intOrNull("outputsNumber"), got.outputsNumber, where)
                }

                val expectedZones = expected.objects("heatingZones")
                assertEquals(expectedZones.size, config.heatingZones.size)
                expectedZones.zip(config.heatingZones).forEach { (want, got) ->
                    val where = "zone dbId ${got.dbId}"
                    assertEquals(want.long("dbId"), got.dbId, where)
                    assertEquals(want.int("module"), got.module, where)
                    assertEquals(want.int("outputNr"), got.outputNr, where)
                    assertEquals(want.string("name"), got.name, where)
                    assertEquals(want.intOrNull("roomId"), got.roomId, where)
                    assertEquals(want.intOrNull("levelId"), got.levelId, where)
                }

                val expectedShutters = expected.objects("shutters")
                assertEquals(expectedShutters.size, config.shutters.size)
                expectedShutters.zip(config.shutters).forEach { (want, got) ->
                    val where = "shutter dbId ${got.dbId}"
                    assertEquals(want.long("dbId"), got.dbId, where)
                    assertEquals(want.int("module"), got.module, where)
                    assertEquals(want.int("outputNr"), got.outputNr, where)
                    assertEquals(want.string("name"), got.name, where)
                    assertEquals(want.intOrNull("roomId"), got.roomId, where)
                    assertEquals(want.intOrNull("levelId"), got.levelId, where)
                    assertEquals(want.intOrNull("iconId"), got.iconId, where)
                }

                val expectedSensors = expected.objects("temperatureSensors")
                assertEquals(expectedSensors.size, config.temperatureSensors.size)
                expectedSensors.zip(config.temperatureSensors).forEach { (want, got) ->
                    val where = "sensor dbId ${got.dbId}"
                    assertEquals(want.long("dbId"), got.dbId, where)
                    assertEquals(want.int("module"), got.module, where)
                    assertEquals(want.int("number"), got.number, where)
                    assertEquals(want.string("name"), got.name, where)
                    assertEquals(want.intOrNull("roomId"), got.roomId, where)
                    assertEquals(want.intOrNull("levelId"), got.levelId, where)
                }

                val actualIssues = config.issues
                    .sortedWith(compareBy({ it.table }, { it.rowId }, { it.kind.name }))
                    .map { listOf(it.kind.name, it.table, it.rowId, it.column) }
                val expectedIssues = expected.objects("issues").map {
                    listOf(it.string("kind"), it.string("table"), it.long("rowId"), it["column"])
                }
                assertEquals(expectedIssues, actualIssues)

                // Every selected row is accounted for: kept as output / zone / shutter / sensor, skipped with an issue, or an
                // unsupported type that is left out (reported once, but not kept as an UNKNOWN output).
                val skippedOutputs = config.issues.count { it.table == "outputs" && it.isSkippedRow }
                val unsupportedLeftOut = config.issues.count { it.kind == ConfigIssueKind.UNSUPPORTED_OUTPUT_TYPE } -
                    config.outputs.count { it.kind == OutputKind.UNKNOWN }
                assertEquals(
                    expected.int("selectedRowCount"),
                    config.outputs.size + config.heatingZones.size + config.shutters.size + config.temperatureSensors.size +
                        skippedOutputs + unsupportedLeftOut,
                )
            }
        }

    @Test
    fun syntheticFixtureCoversEveryKindOfIssue() {
        val config = parser.parse(fixtureFile("config_synthetic.db3").readBytes())
        assertEquals(ConfigIssueKind.entries.toSet(), config.issues.map { it.kind }.toSet())
        assertEquals(OutputKind.entries.toSet(), config.outputs.map { it.kind }.toSet())
        assertTrue(config.heatingZones.isNotEmpty() && config.temperatureSensors.isNotEmpty() && config.modules.size > 3)
        assertTrue(config.shutters.size > 10, "the fixture has shutters of several modules")
        // a shutter is never a relay output
        assertTrue(config.outputs.none { it.rawType == OutputKind.SHUTTER_TYPE })
    }

    @Test
    fun bothPageSizesGiveTheSameModel() {
        assertEquals(
            parser.parse(fixtureFile("config_synthetic.db3").readBytes()),
            parser.parse(fixtureFile("config_synthetic_4096.db3").readBytes()),
        )
    }

    @Test
    fun issueDetailsContainNoNames() {
        val config = parser.parse(fixtureFile("config_synthetic.db3").readBytes())
        val names = (
            config.outputs.map { it.name } + config.heatingZones.map { it.name } + config.shutters.map { it.name } +
                config.temperatureSensors.map { it.name } + config.rooms.map { it.name }
            ).filter { it.length > 3 }
        for (issue in config.issues) {
            assertTrue(names.none { it in issue.detail }, "issue detail leaks a name: ${issue.kind}")
        }
    }

    // --- selection and addressing --------------------------------------------------------------------------------

    @Test
    fun onlyPanelOutputsAndZonesAreRead() {
        val config = parse(
            outputs = listOf(
                output(1, relay = 1, show = 1),
                output(2, relay = 2, show = 0),
                output(3, relay = 3, show = null),
                output(4, relay = 4, show = 2),
                output(5, relay = 5, show = "1"),
                output(6, relay = 6, show = 1.0),
                output(7, nr = 4, relay = 70, type = 123, show = 0, name = "Strefa A"),
                output(8, nr = 5, relay = null, type = 123, show = null, name = "Strefa B"),
            ),
        )
        assertEquals(listOf(1, 6), config.outputs.map { it.relay })
        assertEquals(listOf(4, 5), config.heatingZones.map { it.outputNr })
        assertTrue(config.issues.isEmpty())
    }

    @Test
    fun zonesAreAddressedByOutputNrNotByRelay() {
        val config = parse(outputs = listOf(output(1, device = 2, nr = 7, relay = 99, type = 123, name = "Łazienka (test)")))
        val zone = config.heatingZone(2, 7)
        assertNotNull(zone)
        assertEquals("Łazienka (test)", zone!!.name)
        assertEquals(2u, zone.moduleAddress)
        assertEquals(7u.toUByte(), zone.zoneByte)
        assertNull(config.heatingZone(2, 99))
        assertTrue(config.outputs.isEmpty(), "a zone is not an output")
    }

    @Test
    fun outputKindsAndTheirRawTypes() {
        val types = mapOf(1 to OutputKind.ON_OFF, 4 to OutputKind.RGB, 7 to OutputKind.LED, 8 to OutputKind.DIMMER_ON_OFF)
        val config = parse(outputs = types.keys.mapIndexed { index, type -> output(index + 1L, relay = index, type = type) })
        assertEquals(types.values.toList(), config.outputs.map { it.kind })
        assertEquals(types.keys.toList(), config.outputs.map { it.rawType })
        assertTrue(config.outputs.all { it.isSupported })
        assertTrue(config.issues.isEmpty())
    }

    @Test
    fun unknownTypeIsKeptButFlagged() {
        val config = parse(outputs = listOf(output(1, relay = 3, type = 99), output(2, relay = 4, type = 1)))
        assertEquals(2, config.outputs.size)
        assertEquals(OutputKind.UNKNOWN, config.outputs[0].kind)
        assertEquals(99, config.outputs[0].rawType)
        assertFalse(config.outputs[0].isSupported)
        assertEquals(listOf(config.outputs[1]), config.supportedOutputs)
        assertEquals(listOf(ConfigIssueKind.UNSUPPORTED_OUTPUT_TYPE), config.issueKinds())
        assertEquals(1L, config.issues.single().rowId)
    }

    @Test
    fun duplicateAddressesAreKeptAndReported() {
        val config = parse(
            outputs = listOf(
                output(1, device = 1, relay = 5, name = "pierwsze"),
                output(2, device = 1, relay = 5, name = "drugie"),
                output(3, device = 2, nr = 1, type = 123, name = "z1"),
                output(4, device = 2, nr = 1, type = 123, name = "z2"),
            ),
        )
        assertEquals(2, config.outputs.size)
        assertEquals("pierwsze", config.output(1, 5)!!.name, "lookups return the first")
        assertEquals("z1", config.heatingZone(2, 1)!!.name)
        assertEquals(listOf(ConfigIssueKind.DUPLICATE_ADDRESS, ConfigIssueKind.DUPLICATE_ADDRESS), config.issueKinds())
        assertEquals(listOf(2L, 4L), config.issues.map { it.rowId })
    }

    @Test
    fun sameRelayNumberOnDifferentModulesIsNotADuplicate() {
        val config = parse(outputs = listOf(output(1, device = 1, relay = 5), output(2, device = 2, relay = 5)))
        assertTrue(config.issues.isEmpty())
        assertEquals(listOf(1, 2), config.modules)
        assertNotNull(config.output(2, 5))
    }

    // --- shutters (output_type 5) -----------------------------------------------------------------------------------

    @Test
    fun shuttersAreAddressedByOutputNrNotByRelay() {
        // Shape of a real database: shutter N drives the relay pair (2N, 2N + 1), outputs_number = 2.
        val config = parse(
            outputs = listOf(
                output(1, nr = 0, relay = 0, number = 2, type = 5, name = "Roleta A (test)", room = 1, level = 1, icon = 0),
                output(2, nr = 1, relay = 2, number = 2, type = 5, name = " Roleta B (test) ", room = null),
                output(3, device = 2, nr = 4, relay = 8, number = 2, type = 5, name = "Roleta C (test)"),
            ),
        )
        assertTrue(config.outputs.isEmpty(), "a shutter is not a relay output")
        assertTrue(config.issues.isEmpty(), "shutters are neither unsupported nor duplicates")
        assertEquals(listOf(1L, 2L, 3L), config.shutters.map { it.dbId })
        assertEquals(listOf("Roleta A (test)", "Roleta B (test)", "Roleta C (test)"), config.shutters.map { it.name })
        assertEquals(listOf(1, 2), config.modules)

        val shutter = config.shutter(2, 4)!!
        assertEquals("Roleta C (test)", shutter.name)
        assertEquals(2u, shutter.moduleAddress)
        assertEquals(4u.toUByte(), shutter.shutterByte)
        assertNull(config.shutter(2, 8), "the relay number is not an address")
        assertNull(config.shutter(1, 2))
        assertEquals(1, config.shutter(1, 1)!!.outputNr)
        assertNull(config.output(1, 2), "the shutter's relay is not an output")
        assertEquals(0, config.shutter(1, 0)!!.iconId)
        assertNull(config.shutter(1, 1)!!.iconId)
        assertEquals("Salon (test)", config.roomOf(config.shutter(1, 0)!!)!!.name)
        assertNull(config.roomOf(config.shutter(1, 1)!!))
        assertEquals(listOf(1L), config.shuttersInRoom(1).map { it.dbId })
        assertEquals(listOf(1L), config.shuttersOnLevel(1).map { it.dbId })
        assertEquals(listOf(3L), config.shuttersOfModule(2).map { it.dbId })
        assertFalse(config.isEmpty)
    }

    @Test
    fun aShutterIgnoresRelayCanOnAndOutputsNumber() {
        val config = parse(
            outputs = listOf(
                output(1, nr = 0, relay = null, type = 5),
                output(2, nr = 1, relay = -1, type = 5),
                output(3, nr = 2, relay = 200, type = 5),
                output(4, nr = 3, relay = "abc", type = 5),
                output(5, nr = 4, type = 5, canOn = "x", number = byteArrayOf(1)),
                output(6, nr = 63, type = 5),
            ),
        )
        assertEquals(listOf(0, 1, 2, 3, 4, 63), config.shutters.map { it.outputNr })
        assertTrue(config.issues.isEmpty())
    }

    @Test
    fun shuttersWithImpossibleValuesAreSkipped() {
        val config = parse(
            outputs = listOf(
                output(1, nr = null, type = 5),
                output(2, nr = 64, type = 5),
                output(3, nr = -1, type = 5),
                output(4, device = 0, nr = 1, type = 5),
                output(5, nr = 2, type = 5, name = null),
                output(6, nr = 3, type = 5, icon = "x"),
                output(7, nr = 4, type = 5, room = "x"),
                output(8, nr = 5, type = 5, name = "Dobra (test)"),
            ),
        )
        assertEquals(listOf(5), config.shutters.map { it.outputNr })
        assertEquals(
            listOf(
                Triple(ConfigIssueKind.SKIPPED_MISSING_VALUE, 1L, "output_nr"),
                Triple(ConfigIssueKind.SKIPPED_INVALID_VALUE, 2L, "output_nr"),
                Triple(ConfigIssueKind.SKIPPED_INVALID_VALUE, 3L, "output_nr"),
                Triple(ConfigIssueKind.SKIPPED_INVALID_VALUE, 4L, "id_device"),
                Triple(ConfigIssueKind.SKIPPED_MISSING_VALUE, 5L, "Name"),
                Triple(ConfigIssueKind.SKIPPED_INVALID_VALUE, 6L, "icon_id"),
                Triple(ConfigIssueKind.SKIPPED_INVALID_VALUE, 7L, "id_room"),
            ),
            config.issues.map { Triple(it.kind, it.rowId, it.column) },
        )
    }

    @Test
    fun shutterDuplicatesRoomsAndLevelsAreReportedLikeForOtherItems() {
        val config = parse(
            outputs = listOf(
                output(1, nr = 0, type = 5, name = "Pierwsza (test)", room = 1, level = 1),
                output(2, nr = 0, type = 5, name = "Powtórzona (test)", room = 1, level = 1),
                output(3, device = 2, nr = 0, type = 5, name = "Inny moduł (test)"),
                output(4, nr = 1, type = 5, name = "W nieznanym pokoju (test)", room = 55, level = 66),
                output(5, nr = 2, type = 5, name = "Bez pokoju (test)", room = NO_ROOM_ID, level = NO_LEVEL_ID),
            ),
        )
        assertEquals(5, config.shutters.size)
        assertEquals("Pierwsza (test)", config.shutter(1, 0)!!.name, "lookups return the first")
        assertEquals(
            listOf(
                ConfigIssueKind.DUPLICATE_ADDRESS to 2L,
                ConfigIssueKind.UNKNOWN_ROOM to 4L,
                ConfigIssueKind.UNKNOWN_LEVEL to 4L,
            ),
            config.issues.map { it.kind to it.rowId },
        )
    }

    @Test
    fun emptyShutterNameGetsAFallback() {
        val config = parse(outputs = listOf(output(1, nr = 3, type = 5, name = "   ")))
        assertEquals("Roleta 3", config.shutters.single().name)
        val english = object : ConfigTexts by ConfigTexts.Polish {
            override fun shutterName(outputNr: Int) = "Shutter $outputNr"
        }
        assertEquals("Shutter 3", parse(outputs = listOf(output(1, nr = 3, type = 5, name = "")), parser = HomeConfigParser(english)).shutters.single().name)
    }

    @Test
    fun hiddenShuttersAreNotRead() {
        val config = parse(outputs = listOf(output(1, nr = 0, type = 5, show = 0), output(2, nr = 1, type = 5, show = null)))
        assertTrue(config.shutters.isEmpty())
        assertTrue(config.isEmpty)
    }

    // --- temperature sensors (output_type 126) ----------------------------------------------------------------------

    @Test
    fun temperatureSensorsAreReadAsSensorsNotOutputs() {
        val config = parse(
            outputs = listOf(
                output(1, nr = 0, relay = 0, type = 126, name = "Czujnik A (test)", room = 1, level = 1, canOn = 0),
                output(2, device = 2, nr = 5, relay = 5, type = 126, name = "  Czujnik B (test) ", room = null),
                output(3, relay = 0, name = "Lampa"),
            ),
        )
        assertEquals(listOf(1L, 2L), config.temperatureSensors.map { it.dbId })
        assertEquals(listOf("Czujnik A (test)", "Czujnik B (test)"), config.temperatureSensors.map { it.name })
        assertEquals(listOf("Lampa"), config.outputs.map { it.name })
        assertTrue(config.heatingZones.isEmpty())
        assertTrue(config.issues.isEmpty(), "a sensor is neither unsupported nor a duplicate of the lamp with relay 0")
        assertEquals(listOf(1, 2), config.modules)

        val sensor = config.temperatureSensor(2, 5)!!
        assertEquals("Czujnik B (test)", sensor.name)
        assertNull(sensor.roomId)
        assertEquals(2u, sensor.moduleAddress)
        assertNull(config.temperatureSensor(2, 0))
        assertEquals(listOf(1L), config.temperatureSensorsInRoom(1).map { it.dbId })
        assertEquals(listOf(1L), config.temperatureSensorsOnLevel(1).map { it.dbId })
        assertEquals(listOf(2L), config.temperatureSensorsOfModule(2).map { it.dbId })
        assertEquals("Salon (test)", config.roomOf(config.temperatureSensor(1, 0)!!)!!.name)
        assertNull(config.roomOf(sensor))
    }

    @Test
    fun aSensorIsAddressedByOutputNrAndItsOtherColumnsAreIgnored() {
        val config = parse(
            outputs = listOf(
                output(1, nr = 0, relay = null, type = 126),
                output(2, nr = 1, relay = -1, type = 126),
                output(3, nr = 2, relay = 200, type = 126),
                output(4, nr = 3, relay = "abc", type = 126),
                output(5, nr = 4, type = 126, icon = "x", canOn = "x", number = byteArrayOf(1)),
                output(6, nr = 127, type = 126),
            ),
        )
        assertEquals(listOf(0, 1, 2, 3, 4, 127), config.temperatureSensors.map { it.number })
        assertTrue(config.issues.isEmpty())
    }

    @Test
    fun sensorsWithImpossibleValuesAreSkipped() {
        val config = parse(
            outputs = listOf(
                output(1, nr = null, type = 126),
                output(2, nr = 128, type = 126),
                output(3, nr = -1, type = 126),
                output(4, device = 0, nr = 1, type = 126),
                output(5, nr = 2, type = 126, name = null),
                output(6, nr = 3, type = 126, room = "x"),
                output(7, nr = 4, type = 126, name = "Dobry (test)"),
            ),
        )
        assertEquals(listOf(4), config.temperatureSensors.map { it.number })
        assertEquals(
            listOf(
                Triple(ConfigIssueKind.SKIPPED_MISSING_VALUE, 1L, "output_nr"),
                Triple(ConfigIssueKind.SKIPPED_INVALID_VALUE, 2L, "output_nr"),
                Triple(ConfigIssueKind.SKIPPED_INVALID_VALUE, 3L, "output_nr"),
                Triple(ConfigIssueKind.SKIPPED_INVALID_VALUE, 4L, "id_device"),
                Triple(ConfigIssueKind.SKIPPED_MISSING_VALUE, 5L, "Name"),
                Triple(ConfigIssueKind.SKIPPED_INVALID_VALUE, 6L, "id_room"),
            ),
            config.issues.map { Triple(it.kind, it.rowId, it.column) },
        )
    }

    @Test
    fun relayOutputsZonesShuttersAndSensorsHaveSeparateAddressSpaces() {
        // The same module and number 0 in all four spaces (as happens in a real database, where a sensor, a shutter and a relay can share a number).
        val config = parse(
            outputs = listOf(
                output(1, device = 1, nr = 0, relay = 0, type = 5, name = "Roleta"),
                output(2, device = 1, nr = 0, relay = 0, type = 126, name = "Czujnik"),
                output(3, device = 1, nr = 0, relay = 30, type = 123, name = "Strefa"),
                output(4, device = 1, relay = 0, type = 1, name = "Lampa"),
                output(5, device = 1, nr = 0, relay = 0, type = 126, name = "Czujnik drugi"),
                output(6, device = 1, nr = 0, relay = 31, type = 123, name = "Strefa druga"),
            ),
        )
        // Only the repeats inside one space are duplicates: the second sensor (row 5) and the second zone (row 6); the
        // lamp with relay 0 (row 4) is not a repeat of the shutter numbered 0 (row 1).
        assertEquals(
            listOf(ConfigIssueKind.DUPLICATE_ADDRESS to 5L, ConfigIssueKind.DUPLICATE_ADDRESS to 6L),
            config.issues.map { it.kind to it.rowId },
        )
        assertEquals("Czujnik", config.temperatureSensor(1, 0)!!.name, "lookups return the first")
        assertEquals("Roleta", config.shutter(1, 0)!!.name)
        assertEquals("Lampa", config.output(1, 0)!!.name)
        assertEquals("Strefa", config.heatingZone(1, 0)!!.name)
    }

    // --- types this version does not handle ---------------------------------------------------------------------------

    @Test
    fun unsupportedTypesWithoutARelayAreReportedOnceAndLeftOut() {
        val config = parse(
            outputs = listOf(
                output(1, device = 515, nr = 0, relay = -1, type = 201, room = 0, level = 0),
                output(2, device = 0, nr = 0, relay = -1, type = 121, room = 1000, level = 0),
                output(3, device = 3, nr = -1, relay = -1, type = 2001, room = 1000, level = 0),
                output(4, device = null, nr = null, relay = null, type = 300, name = null, room = 55, level = 66),
                output(5, relay = "abc", type = 99, icon = "x", canOn = "x"),
                output(6, device = 515, relay = -1, type = 201, name = 42),
            ),
        )
        assertTrue(config.outputs.isEmpty() && config.heatingZones.isEmpty() && config.temperatureSensors.isEmpty())
        assertTrue(config.modules.isEmpty())
        assertEquals((1L..6L).toList(), config.issues.map { it.rowId })
        assertTrue(config.issues.all { it.kind == ConfigIssueKind.UNSUPPORTED_OUTPUT_TYPE && it.column == "output_type" })
        assertTrue(config.issues.none { it.isSkippedRow })
        assertTrue(config.issues.all { it.isInformational })
    }

    @Test
    fun anUnsupportedTypeWithARelayIsKeptAsUnknownWithOnlyThatIssue() {
        val config = parse(
            outputs = listOf(
                output(1, relay = 5, type = 99, room = 77, level = 88),
                output(2, relay = 5, type = 1, name = "Lampa"),
            ),
        )
        assertEquals(listOf(OutputKind.UNKNOWN, OutputKind.ON_OFF), config.outputs.map { it.kind })
        assertEquals(77, config.outputs[0].roomId, "the stored room is kept")
        assertEquals(listOf(ConfigIssueKind.UNSUPPORTED_OUTPUT_TYPE to 1L), config.issues.map { it.kind to it.rowId })
        assertEquals("Lampa", config.output(1, 5)!!.name, "an item of unknown type never shadows a real output")
        assertEquals(listOf(config.outputs[1]), config.supportedOutputs)
    }

    @Test
    fun onlyUnsupportedTypesAreInformational() {
        val config = parse(
            outputs = listOf(
                output(1, relay = 1, type = 99),
                output(2, relay = 200),
                output(3, relay = 3, room = 9),
                output(4, relay = 3),
            ),
        )
        assertEquals(
            listOf(
                ConfigIssueKind.UNSUPPORTED_OUTPUT_TYPE to true,
                ConfigIssueKind.SKIPPED_INVALID_VALUE to false,
                ConfigIssueKind.UNKNOWN_ROOM to false,
                ConfigIssueKind.DUPLICATE_ADDRESS to false,
            ),
            config.issues.map { it.kind to it.isInformational },
        )
    }

    // --- "no room" / "no level" markers ---------------------------------------------------------------------------------

    @Test
    fun noRoomAndNoLevelMarkersAreNotReportedAsUnknown() {
        assertEquals(1000, NO_ROOM_ID)
        assertEquals(0, NO_LEVEL_ID)
        val config = parse(
            rooms = listOf(room(1, 1, "Salon (test)"), room(2, NO_LEVEL_ID.toLong(), "Pokój bez piętra (test)")),
            outputs = listOf(
                output(1, relay = 1, room = NO_ROOM_ID, level = NO_LEVEL_ID),
                output(2, nr = 1, type = 123, room = NO_ROOM_ID, level = NO_LEVEL_ID),
                output(3, nr = 1, type = 126, room = NO_ROOM_ID, level = NO_LEVEL_ID),
                output(4, relay = 4, room = NO_ROOM_ID, level = 1),
                output(5, relay = 5, room = 1, level = NO_LEVEL_ID),
            ),
        )
        assertTrue(config.issues.isEmpty(), config.issues.toString())
        assertEquals(5, config.outputs.size + config.heatingZones.size + config.temperatureSensors.size)
        val output = config.output(1, 1)!!
        assertEquals(NO_ROOM_ID, output.roomId, "kept as stored")
        assertEquals(NO_LEVEL_ID, output.levelId)
        assertNull(config.roomOf(output))
        assertNull(config.level(NO_LEVEL_ID))
        assertEquals(NO_LEVEL_ID, config.rooms.last().levelId)
    }

    @Test
    fun idsNextToTheMarkersAreStillReportedWhenUnknown() {
        val config = parse(
            outputs = listOf(
                output(1, relay = 1, room = NO_ROOM_ID + 1, level = NO_LEVEL_ID),
                output(2, relay = 2, room = NO_ROOM_ID, level = NO_LEVEL_ID + 7),
            ),
        )
        assertEquals(
            listOf(Triple(ConfigIssueKind.UNKNOWN_ROOM, 1L, "id_room"), Triple(ConfigIssueKind.UNKNOWN_LEVEL, 2L, "id_level")),
            config.issues.map { Triple(it.kind, it.rowId, it.column) },
        )
    }

    // --- row validation ------------------------------------------------------------------------------------------

    @Test
    fun rowsWithImpossibleAddressesAreSkippedWithAnIssue() {
        val config = parse(
            outputs = listOf(
                output(1, relay = 127),
                output(2, relay = 128),
                output(3, relay = -1),
                output(4, relay = null),
                output(5, device = 0),
                output(6, device = null),
                output(7, nr = 31, type = 123),
                output(8, nr = 32, type = 123),
                output(9, nr = null, type = 123),
                output(10, nr = -1, type = 123),
            ),
        )
        assertEquals(listOf(127), config.outputs.map { it.relay })
        assertEquals(listOf(31), config.heatingZones.map { it.outputNr })
        val issues = config.issues.associate { it.rowId to Triple(it.kind, it.table, it.column) }
        assertEquals(Triple(ConfigIssueKind.SKIPPED_INVALID_VALUE, "outputs", "output_relay"), issues[2])
        assertEquals(Triple(ConfigIssueKind.SKIPPED_INVALID_VALUE, "outputs", "output_relay"), issues[3])
        assertEquals(Triple(ConfigIssueKind.SKIPPED_MISSING_VALUE, "outputs", "output_relay"), issues[4])
        assertEquals(Triple(ConfigIssueKind.SKIPPED_INVALID_VALUE, "outputs", "id_device"), issues[5])
        assertEquals(Triple(ConfigIssueKind.SKIPPED_MISSING_VALUE, "outputs", "id_device"), issues[6])
        assertEquals(Triple(ConfigIssueKind.SKIPPED_INVALID_VALUE, "outputs", "output_nr"), issues[8])
        assertEquals(Triple(ConfigIssueKind.SKIPPED_MISSING_VALUE, "outputs", "output_nr"), issues[9])
        assertEquals(Triple(ConfigIssueKind.SKIPPED_INVALID_VALUE, "outputs", "output_nr"), issues[10])
        assertEquals(8, config.issues.size)
        assertTrue(config.issues.all { it.isSkippedRow })
    }

    @Test
    fun valuesOfTheWrongTypeSkipTheRow() {
        val config = parse(
            outputs = listOf(
                output(1, type = "1"),
                output(2, type = null),
                output(3, type = 1.5),
                output(4, name = null),
                output(5, name = 42),
                output(6, name = byteArrayOf(65)),
                output(7, icon = "x"),
                output(8, room = 2.5),
                output(9, level = "1"),
                output(10, canOn = "yes"),
                output(11, number = byteArrayOf(1)),
                output(12, device = (1L shl 40)),
                output(13, relay = 1.5),
            ),
        )
        assertTrue(config.outputs.isEmpty())
        val columns = config.issues.associate { it.rowId to it.column }
        assertEquals(
            mapOf(
                1L to "output_type", 2L to "output_type", 3L to "output_type", 4L to "Name", 5L to "Name", 6L to "Name",
                7L to "icon_id", 8L to "id_room", 9L to "id_level", 10L to "can_on", 11L to "outputs_number",
                12L to "id_device", 13L to "output_relay",
            ),
            columns,
        )
        assertEquals(ConfigIssueKind.SKIPPED_MISSING_VALUE, config.issues.first { it.rowId == 2L }.kind)
        assertEquals(ConfigIssueKind.SKIPPED_MISSING_VALUE, config.issues.first { it.rowId == 4L }.kind)
        assertEquals(ConfigIssueKind.SKIPPED_INVALID_VALUE, config.issues.first { it.rowId == 5L }.kind)
    }

    @Test
    fun wholeNumbersStoredAsRealAreAccepted() {
        val config = parse(
            levels = listOf(level(1, "Parter (test)")),
            rooms = listOf(room(1, 1.0, "Salon (test)")),
            outputs = listOf(output(1, device = 2.0, relay = 3.0, type = 1.0, room = 1.0, level = 1.0, icon = 4.0, canOn = 0.0)),
        )
        val output = config.outputs.single()
        assertEquals(2, output.module)
        assertEquals(3, output.relay)
        assertEquals(1, output.roomId)
        assertEquals(4, output.iconId)
        assertFalse(output.canOn)
        assertEquals(1, config.rooms.single().levelId)
        assertTrue(config.issues.isEmpty())
    }

    @Test
    fun canOnDefaultsToTrue() {
        val config = parse(outputs = listOf(output(1, relay = 1, canOn = null), output(2, relay = 2, canOn = 0), output(3, relay = 3, canOn = 5)))
        assertEquals(listOf(true, false, true), config.outputs.map { it.canOn })
    }

    @Test
    fun brokenRowsDoNotAffectTheOthers() {
        val config = parse(
            outputs = listOf(output(1, relay = 1), output(2, relay = 200), output(3, relay = 3), output(4, name = null), output(5, relay = 5)),
        )
        assertEquals(listOf(1, 3, 5), config.outputs.map { it.relay })
        assertEquals(listOf(2L, 4L), config.issues.map { it.rowId })
    }

    // --- names ----------------------------------------------------------------------------------------------------

    @Test
    fun namesAreTrimmedAndEmptyNamesGetFallbacks() {
        val config = parse(
            levels = listOf(level(1, "  Parter (test)\t"), level(2, ""), level(3, "   "), level(4, null)),
            rooms = listOf(room(1, 1, " Salon (test) "), room(2, 1, ""), room(3, null, null)),
            outputs = listOf(
                output(1, relay = 7, name = " Lampa "),
                output(2, relay = 8, name = ""),
                output(3, relay = 9, name = "  "),
                output(4, nr = 2, type = 123, name = ""),
                output(5, nr = 3, type = 123, name = "  Podłoga  "),
            ),
        )
        assertEquals(listOf("Parter (test)", "Poziom 2", "Poziom 3", "Poziom 4"), config.levels.map { it.name })
        assertEquals(listOf("Salon (test)", "Pomieszczenie 2", "Pomieszczenie 3"), config.rooms.map { it.name })
        assertEquals(listOf("Lampa", "Wyjście 8", "Wyjście 9"), config.outputs.map { it.name })
        assertEquals(listOf("Strefa 2", "Podłoga"), config.heatingZones.map { it.name })
        assertTrue(config.issues.isEmpty())
    }

    @Test
    fun fallbackTextsCanBeReplaced() {
        val english = object : ConfigTexts {
            override fun levelName(id: Int) = "Level $id"
            override fun roomName(id: Int) = "Room $id"
            override fun outputName(relay: Int) = "Output $relay"
            override fun heatingZoneName(outputNr: Int) = "Zone $outputNr"
            override fun temperatureSensorName(number: Int) = "Sensor $number"
        }
        val config = parse(
            levels = listOf(level(1, null)),
            rooms = listOf(room(2, 1, "")),
            outputs = listOf(
                output(1, relay = 4, name = ""),
                output(2, nr = 6, type = 123, name = ""),
                output(3, nr = 8, type = 126, name = ""),
            ),
            parser = HomeConfigParser(english),
        )
        assertEquals("Level 1", config.levels.single().name)
        assertEquals("Room 2", config.rooms.single().name)
        assertEquals("Output 4", config.outputs.single().name)
        assertEquals("Zone 6", config.heatingZones.single().name)
        assertEquals("Sensor 8", config.temperatureSensors.single().name)
    }

    @Test
    fun textsWrittenBeforeSensorsExistedKeepWorking() {
        val old = object : ConfigTexts {
            override fun levelName(id: Int) = "L$id"
            override fun roomName(id: Int) = "R$id"
            override fun outputName(relay: Int) = "O$relay"
            override fun heatingZoneName(outputNr: Int) = "Z$outputNr"
        }
        val config = parse(outputs = listOf(output(1, nr = 3, type = 126, name = "")), parser = HomeConfigParser(old))
        assertEquals("Czujnik 3", config.temperatureSensors.single().name)
    }

    @Test
    fun unicodeNamesSurvive() {
        val name = "Łazienka ąęśćżźńół 😀 日本語"
        assertEquals(name, parse(outputs = listOf(output(1, name = name))).outputs.single().name)
    }

    // --- references -------------------------------------------------------------------------------------------------

    @Test
    fun unknownRoomsAndLevelsAreReportedAndKept() {
        val config = parse(
            levels = listOf(level(1, "Parter (test)")),
            rooms = listOf(room(1, 1, "Salon (test)"), room(2, 9, "Strych")),
            outputs = listOf(
                output(1, relay = 1, room = 1, level = 1),
                output(2, relay = 2, room = 5, level = 1),
                output(3, relay = 3, room = 1, level = 8),
                output(4, nr = 1, type = 123, room = 6, level = 7),
            ),
        )
        assertEquals(4, config.outputs.size + config.heatingZones.size)
        assertEquals(5, config.output(1, 2)!!.roomId, "the id is kept")
        val issues = config.issues.map { Triple(it.kind, it.table, it.rowId) }.toSet()
        assertEquals(
            setOf(
                Triple(ConfigIssueKind.UNKNOWN_LEVEL, "template_rooms", 2L),
                Triple(ConfigIssueKind.UNKNOWN_ROOM, "outputs", 2L),
                Triple(ConfigIssueKind.UNKNOWN_LEVEL, "outputs", 3L),
                Triple(ConfigIssueKind.UNKNOWN_ROOM, "outputs", 4L),
                Triple(ConfigIssueKind.UNKNOWN_LEVEL, "outputs", 4L),
            ),
            issues,
        )
        assertNull(config.roomOf(config.output(1, 2)!!))
        assertEquals("Salon (test)", config.roomOf(config.output(1, 1)!!)!!.name)
    }

    @Test
    fun levelsAndRoomsWithBadValuesAreSkipped() {
        val config = parse(
            levels = listOf(level(1, "Parter (test)"), level(2, 42), level(3, byteArrayOf(1))),
            rooms = listOf(room(1, "x", "Salon (test)"), room(2, 1, 7), room(3, 1, "Kuchnia (test)")),
        )
        assertEquals(listOf(1), config.levels.map { it.id })
        assertEquals(listOf(3), config.rooms.map { it.id })
        assertEquals(
            listOf(
                Triple("template_levels", 2L, "Name"), Triple("template_levels", 3L, "Name"),
                Triple("template_rooms", 1L, "id_level"), Triple("template_rooms", 2L, "Name"),
            ),
            config.issues.map { Triple(it.table, it.rowId, it.column) },
        )
        assertTrue(config.issues.all { it.kind == ConfigIssueKind.SKIPPED_INVALID_VALUE })
    }

    // --- structure of the database ----------------------------------------------------------------------------------

    @Test
    fun emptyTablesGiveAnEmptyModel() {
        val config = parse(levels = emptyList(), rooms = emptyList(), outputs = emptyList())
        assertTrue(config.isEmpty)
        assertTrue(config.modules.isEmpty())
        assertTrue(config.issues.isEmpty())
    }

    @Test
    fun extraTablesAndColumnsAreIgnored() {
        val sql = ConfigDatabaseBuilder.OUTPUTS_SQL.replace("show_on_panel INTEGER)", "show_on_panel INTEGER, extra TEXT, more BLOB)")
        val bytes = ConfigDatabaseBuilder.database(
            levels = listOf(level(1, "A")),
            outputs = listOf(output(1, relay = 2)),
            outputsSql = sql,
        )
        assertEquals(1, parser.parse(bytes).outputs.size)
    }

    @Test
    fun columnNamesAreCaseInsensitive() {
        val sql = ConfigDatabaseBuilder.OUTPUTS_SQL.replace("Name VARCHAR(30)", "NAME VARCHAR(30)")
        val bytes = ConfigDatabaseBuilder.database(outputs = listOf(output(1, relay = 2, name = "Lampa")), outputsSql = sql)
        assertEquals("Lampa", parser.parse(bytes).outputs.single().name)
    }

    @Test
    fun missingTablesAndColumnsAreOneDescriptiveError() {
        val bytes = ConfigDatabaseBuilder.database(
            outputsSql = ConfigDatabaseBuilder.OUTPUTS_SQL.replace("output_relay INTEGER, ", "").replace("can_on INTEGER, ", ""),
            roomsSql = "CREATE TABLE other_rooms (id INTEGER PRIMARY KEY)",
        )
        val error = assertThrows<ConfigParseException> { parser.parse(bytes) }
        val message = error.message!!
        assertTrue("template_rooms" in message, message)
        assertTrue("output_relay" in message && "can_on" in message, message)
    }

    @Test
    fun notADatabaseIsAParseException() {
        assertThrows<ConfigParseException> { parser.parse(ByteArray(0)) }
        assertThrows<ConfigParseException> { parser.parse("hello".toByteArray()) }
        assertThrows<ConfigParseException> { parser.parse(ByteArray(8192)) }
    }

    @Test
    fun corruptionInsideTheDatabaseIsAParseException() {
        val bytes = fixtureFile("config_synthetic.db3").readBytes()
        // wipe a table page: the b-tree page type becomes invalid
        val damaged = bytes.copyOf()
        val tablePage = 1 // the first page after the schema page holds data in this fixture
        for (offset in 0 until 64) damaged[tablePage * 1024 + offset] = 0x7F
        val error = assertThrows<ConfigParseException> { parser.parse(damaged) }
        assertNotNull(error.cause)
    }

    @Test
    fun withoutRowidOutputsTableIsRejectedCleanly() {
        val bytes = ConfigDatabaseBuilder.database(
            outputsSql = "CREATE TABLE outputs (id INTEGER PRIMARY KEY, id_device, output_nr, output_relay, outputs_number, " +
                "output_type, Name, icon_id, id_room, id_level, can_on, show_on_panel) WITHOUT ROWID",
        )
        assertThrows<ConfigParseException> { parser.parse(bytes) }
    }

    // --- optional: a real controller database (local file) ------------------------------------------------------------------------

    /**
     * Runs only when `-Pdomik.realDatabase=<file>` is passed to Gradle. The file contains personal data and stays
     * out of the repository; this test prints nothing and its messages contain only counts and kinds, never names.
     */
    @Test
    fun realDatabaseParsesWhenProvided() {
        val path = System.getProperty("domik.realDatabase")?.takeIf { it.isNotBlank() }
        org.junit.jupiter.api.Assumptions.assumeTrue(path != null, "no real database provided")
        val config = parser.parse(java.io.File(path!!).readBytes())
        assertFalse(config.outputs.isEmpty(), "no outputs were read")
        assertFalse(config.heatingZones.isEmpty(), "no heating zones were read")
        assertFalse(config.temperatureSensors.isEmpty(), "no temperature sensors were read")
        assertFalse(config.shutters.isEmpty(), "no shutters were read")
        assertTrue(config.outputs.none { it.rawType == OutputKind.SHUTTER_TYPE }, "a shutter was read as a relay output")
        val problems = config.issues.filterNot { it.isInformational }
        assertTrue(problems.isEmpty(), "unexpected issues: ${problems.groupingBy { it.kind }.eachCount()}")
    }
}
