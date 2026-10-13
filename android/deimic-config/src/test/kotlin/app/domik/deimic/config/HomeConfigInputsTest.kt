package app.domik.deimic.config

import app.domik.deimic.config.ConfigDatabaseBuilder.input
import app.domik.deimic.config.ConfigDatabaseBuilder.level
import app.domik.deimic.config.ConfigDatabaseBuilder.output
import app.domik.deimic.config.ConfigDatabaseBuilder.room
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The optional `inputs` table and the room-based zone <-> temperature input mapping (fictional data). */
class HomeConfigInputsTest {
    private val parser = HomeConfigParser()

    private val levels = listOf(level(1, "Parter (test)"))
    private val rooms = listOf(room(10, 1, "Pokój A (test)"), room(11, 1, "Pokój B (test)"), room(12, 1, "Pokój C (test)"))

    // Zone numbers (2, 3) and input numbers (5, 2) are deliberately different, like in a real installation.
    private val outputs = listOf(
        output(1, nr = 2, relay = -1, type = 123, name = "Strefa A", room = 10, level = 1),
        output(2, nr = 3, relay = -1, type = 123, name = "Strefa B", room = 11, level = 1),
        output(3, nr = 4, relay = -1, type = 123, name = "Strefa C", room = 12, level = 1),
    )

    private fun parse(inputs: List<List<Any?>>?): HomeConfig =
        parser.parse(ConfigDatabaseBuilder.database(levels, rooms, outputs, inputs = inputs))

    private fun HomeConfig.zone(nr: Int) = heatingZone(1, nr)!!

    @Test
    fun missingInputsTableGivesNoInputsAndNoError() {
        val config = parse(inputs = null)
        assertTrue(config.analogInputs.isEmpty())
        assertTrue(config.temperatureInputsFor(config.zone(2)).isEmpty())
        assertTrue(config.issues.none { it.table == "inputs" })
    }

    @Test
    fun inputsTableWithoutNeededColumnsIsIgnored() {
        val bytes = ConfigDatabaseBuilder.database(
            levels, rooms, outputs, inputs = listOf(listOf<Any?>(1L, 1, 5)),
            inputsSql = "CREATE TABLE inputs (id INTEGER PRIMARY KEY, id_device INTEGER, input_nr INTEGER)",
        )
        assertTrue(parser.parse(bytes).analogInputs.isEmpty())
    }

    @Test
    fun inputsAreReadWithAllFields() {
        val config = parse(
            listOf(
                input(1, nr = 5, level = 1, room = 10, type = 8, name = " Czujnik A "),
                input(2, nr = 30, level = 1, room = 10, type = 2, name = "Przycisk"),
                input(3, nr = 0, level = 1, room = 11, type = 8, name = ""),
            ),
        )
        assertEquals(
            listOf(
                AnalogInput(1, 1, 5, 10, 1, 8, "Czujnik A"),
                AnalogInput(2, 1, 30, 10, 1, 2, "Przycisk"),
                AnalogInput(3, 1, 0, 11, 1, 8, "Wejście 0"),
            ),
            config.analogInputs,
        )
        assertTrue(config.issues.isEmpty(), config.issues.toString())
    }

    @Test
    fun zoneFindsItsInputThroughTheRoomNotThroughTheNumber() {
        val config = parse(
            listOf(
                input(1, nr = 5, level = 1, room = 10),
                input(2, nr = 2, level = 1, room = 11),
            ),
        )
        assertEquals(listOf(5), config.temperatureInputsFor(config.zone(2)).map { it.inputNr })
        assertEquals(listOf(2), config.temperatureInputsFor(config.zone(3)).map { it.inputNr })
    }

    @Test
    fun noInputInTheRoomGivesEmptyList() {
        val config = parse(listOf(input(1, nr = 2, level = 1, room = 11)))
        // zone 4 is in room 12; input number 4 is not guessed
        assertTrue(config.temperatureInputsFor(config.zone(4)).isEmpty())
    }

    @Test
    fun twoInputsInOneRoomAreBothReturnedInDatabaseOrder() {
        val config = parse(listOf(input(1, nr = 7, level = 1, room = 10), input(2, nr = 3, level = 1, room = 10)))
        assertEquals(listOf(7, 3), config.temperatureInputsFor(config.zone(2)).map { it.inputNr })
    }

    @Test
    fun buttonsOtherModulesAndOtherLevelsAreNotTemperatureInputsOfTheZone() {
        val config = parse(
            listOf(
                input(1, nr = 9, level = 1, room = 10, type = 2),
                input(2, device = 2, nr = 8, level = 1, room = 10, type = 8),
                input(3, nr = 6, level = 2, room = 10, type = 8),
            ),
        )
        assertEquals(3, config.analogInputs.size)
        assertTrue(config.temperatureInputsFor(config.zone(2)).isEmpty())
    }

    @Test
    fun zoneWithoutRoomMatchesNothing() {
        val noRoom = HeatingZone(1, 1, 2, "Z", NO_ROOM_ID, NO_LEVEL_ID)
        val config = HomeConfig(
            levels = emptyList(), rooms = emptyList(), modules = listOf(1), outputs = emptyList(), heatingZones = listOf(noRoom),
            analogInputs = listOf(AnalogInput(1, 1, 5, NO_ROOM_ID, NO_LEVEL_ID, 8, "x")),
        )
        assertTrue(config.temperatureInputsFor(noRoom).isEmpty())
    }

    @Test
    fun badInputRowsAreSkippedWithAnIssueInTheInputsTable() {
        val config = parse(
            listOf(
                input(1, device = null, nr = 5, room = 10),
                input(2, nr = "x", room = 10),
                input(3, nr = 4, type = null),
                input(4, nr = 6, level = 1, room = 10),
            ),
        )
        assertEquals(listOf(4L), config.analogInputs.map { it.dbId })
        assertEquals(listOf("id_device", "input_nr", "input_type"), config.issues.filter { it.table == "inputs" }.map { it.column })
        assertTrue(config.issues.all { it.isSkippedRow })
    }
}
