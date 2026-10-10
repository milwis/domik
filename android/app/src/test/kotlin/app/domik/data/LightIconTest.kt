package app.domik.data

import app.domik.deimic.config.HomeConfig
import app.domik.deimic.config.Output
import app.domik.deimic.config.OutputKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LightIconTest {
    private fun auto(name: String, iconId: Int? = null, kind: OutputKind = OutputKind.ON_OFF) =
        LightIcons.automatic(iconId, name, kind)

    @Test
    fun `klucze ikon sa unikalne i odczytywane z powrotem`() {
        assertEquals(12, LightIcon.entries.size)
        assertEquals(LightIcon.entries.size, LightIcon.entries.map { it.key }.toSet().size)
        LightIcon.entries.forEach { assertEquals(it, LightIcon.fromKey(it.key)) }
        assertEquals(
            listOf("bulb", "sconce", "pendant", "floor", "spot", "led", "outdoor", "socket", "fan", "pump", "alarm", "switch"),
            LightIcon.entries.map { it.key },
        )
        assertEquals(null, LightIcon.fromKey("nieznana"))
    }

    // Dobór po icon_id z bazy sterownika

    @Test
    fun `icon_id z bazy ma pierwszenstwo przed nazwa`() {
        assertEquals(LightIcon.SOCKET, auto("Sufit", iconId = 1003))
        assertEquals(LightIcon.PUMP, auto("Kinkiet", iconId = 1064))
        assertEquals(LightIcon.ALARM, auto("Oczka", iconId = 124))
    }

    @Test
    fun `nieznany icon_id oddaje decyzje nazwie`() {
        assertEquals(LightIcon.SCONCE, auto("Kinkiet", iconId = 3))
        assertEquals(LightIcon.BULB, auto("Sufit", iconId = 3))
        assertEquals(LightIcon.BULB, auto("Sufit", iconId = 0))
    }

    // Dobór po słowach w nazwie

    @Test
    fun `slowa w nazwie - kazdy rodzaj`() {
        assertEquals(LightIcon.SCONCE, auto("Kinkiet"))
        assertEquals(LightIcon.PENDANT, auto("Żyrandol"))
        assertEquals(LightIcon.PENDANT, auto("Lampa wisząca"))
        assertEquals(LightIcon.FLOOR, auto("Lampa stojąca"))
        assertEquals(LightIcon.SPOT, auto("Oczka"))
        assertEquals(LightIcon.SPOT, auto("Halogeny"))
        assertEquals(LightIcon.LED, auto("Taśma LED"))
        assertEquals(LightIcon.OUTDOOR, auto("Lampa ogrodowa"))
        assertEquals(LightIcon.SOCKET, auto("Gniazdko"))
        assertEquals(LightIcon.FAN, auto("Wentylator"))
        assertEquals(LightIcon.FAN, auto("Went. łazienka"))
        assertEquals(LightIcon.PUMP, auto("Pompa cyrkulacyjna"))
    }

    @Test
    fun `wielkosc liter i polskie znaki nie maja znaczenia`() {
        assertEquals(LightIcon.SCONCE, auto("KINKIET"))
        assertEquals(LightIcon.SCONCE, auto("kinkiet"))
        assertEquals(LightIcon.PENDANT, auto("ŻYRANDOL"))
        assertEquals(LightIcon.PENDANT, auto("zyrandol"))
        assertEquals(LightIcon.PENDANT, auto("LAMPA WISZĄCA"))
        assertEquals(LightIcon.PENDANT, auto("lampa wiszaca"))
        assertEquals(LightIcon.LED, auto("TAŚMA led"))
        assertEquals(LightIcon.LED, auto("tasma"))
        assertEquals(LightIcon.OUTDOOR, auto("Ogród"))
        assertEquals(LightIcon.OUTDOOR, auto("ogrod"))
        assertEquals(LightIcon.OUTDOOR, auto("Lampa zewnętrzna"))
    }

    @Test
    fun `odmiana polska - rodzaj poznaje sie po poczatku slowa`() {
        for (name in listOf("Kinkiet", "Kinkiety", "Kinkiecie", "Kinkietów", "kinkietowy")) assertEquals(LightIcon.SCONCE, auto(name), name)
        for (name in listOf("Żyrandol", "Żyrandola", "Żyrandolu", "żyrandole")) assertEquals(LightIcon.PENDANT, auto(name), name)
        for (name in listOf("Lampa wisząca", "Lampy wiszące", "Lampa wiszącej")) assertEquals(LightIcon.PENDANT, auto(name), name)
        for (name in listOf("Lampa stojąca", "Lampy stojące", "Lampa podłogowa")) assertEquals(LightIcon.FLOOR, auto(name), name)
        for (name in listOf("Oczko", "Oczka", "Oczek", "Spoty", "Spot", "Halogen")) assertEquals(LightIcon.SPOT, auto(name), name)
        for (name in listOf("Taśma", "Taśmy", "Taśmie LED", "LED", "Led")) assertEquals(LightIcon.LED, auto(name), name)
        for (name in listOf("Ogród", "Ogrodowa", "Ogródek", "Taras", "Tarasu", "Tarasowa", "Zewnętrzna", "Elewacja", "Elewacji")) {
            assertEquals(LightIcon.OUTDOOR, auto(name), name)
        }
        for (name in listOf("Gniazdko", "Gniazdek", "Gniazdo", "Gniazda")) assertEquals(LightIcon.SOCKET, auto(name), name)
        for (name in listOf("Wentylator", "Wentylatora", "Wentylacja", "Went")) assertEquals(LightIcon.FAN, auto(name), name)
        for (name in listOf("Pompa", "Pompy", "Pompie", "Pompka")) assertEquals(LightIcon.PUMP, auto(name), name)
    }

    @Test
    fun `slowo moze stac w srodku nazwy i otaczac je interpunkcja`() {
        assertEquals(LightIcon.SCONCE, auto("Salon - kinkiet nad kanapą"))
        assertEquals(LightIcon.SCONCE, auto("(kinkiet)"))
        assertEquals(LightIcon.LED, auto("Blat/LED"))
        assertEquals(LightIcon.FAN, auto("Łazienka: wentylator"))
    }

    @Test
    fun `urzadzenie wygrywa z miejscem na zewnatrz`() {
        assertEquals(LightIcon.PUMP, auto("Pompa tarasowa"))
        assertEquals(LightIcon.SCONCE, auto("Kinkiet taras"))
        assertEquals(LightIcon.SOCKET, auto("Gniazdko ogród"))
        assertEquals(LightIcon.LED, auto("Taśma LED - elewacja"))
        assertEquals(LightIcon.SPOT, auto("Oczka na zewnątrz"))
        assertEquals(LightIcon.FLOOR, auto("Lampa stojąca na tarasie"))
    }

    @Test
    fun `lampa bez dopowiedzenia to zarowka a slowa nie sa dopasowywane w srodku wyrazu`() {
        assertEquals(LightIcon.BULB, auto("Lampa"))
        assertEquals(LightIcon.BULB, auto("Lampka nocna"))
        assertEquals(LightIcon.BULB, auto("Lampa nad biurkiem"))
        assertEquals(LightIcon.BULB, auto("Ledwo świeci")) // „led” tylko jako całe słowo
        assertEquals(LightIcon.BULB, auto("Zaspot")) // „spot” tylko na początku słowa
        assertEquals(LightIcon.BULB, auto("Wisząca szafka")) // „wisząca” bez lampy
        assertEquals(LightIcon.BULB, auto("Stojąca szafa")) // „stojąca” bez lampy
    }

    // Dobór po typie wyjścia i wartość domyślna

    @Test
    fun `typ wyjscia LED daje tasme gdy nazwa nic nie mowi`() {
        assertEquals(LightIcon.LED, auto("Wnęka", kind = OutputKind.LED))
        assertEquals(LightIcon.LED, auto("", kind = OutputKind.LED))
        assertEquals(LightIcon.BULB, auto("Wnęka", kind = OutputKind.DIMMER_ON_OFF))
        // Nazwa i icon_id wygrywają z typem.
        assertEquals(LightIcon.SCONCE, auto("Kinkiet", kind = OutputKind.LED))
        assertEquals(LightIcon.SOCKET, auto("Wnęka", iconId = 1003, kind = OutputKind.LED))
    }

    @Test
    fun `reszta to zarowka - takze pusta lub dziwna nazwa`() {
        assertEquals(LightIcon.BULB, auto("Sufit"))
        assertEquals(LightIcon.BULB, auto(""))
        assertEquals(LightIcon.BULB, auto("   "))
        assertEquals(LightIcon.BULB, auto("---"))
        assertEquals(LightIcon.BULB, auto("Wyjście 12", kind = OutputKind.UNKNOWN))
    }

    // Alarm

    private fun output(name: String, iconId: Int?, relay: Int = 1, kind: OutputKind = OutputKind.ON_OFF) = Output(
        dbId = relay.toLong(), module = 1, relay = relay, kind = kind, rawType = kind.rawType ?: 99, name = name,
        roomId = null, levelId = null, iconId = iconId, canOn = true, outputsNumber = null,
    )

    @Test
    fun `alarm - wyjscie z icon_id 124`() {
        assertTrue(LightIcons.isAlarmOutput(124))
        assertFalse(LightIcons.isAlarmOutput(1003))
        assertFalse(LightIcons.isAlarmOutput(3))
        assertFalse(LightIcons.isAlarmOutput(null))
        assertTrue(output("Uzbrój", 124).isAlarm)
        assertFalse(output("Alarm", 5).isAlarm) // nazwa nic nie znaczy – decyduje baza
        assertEquals(LightIcon.ALARM, output("Uzbrój", 124).automaticIcon())
    }

    @Test
    fun `potwierdzenie - alarm z bazy zawsze a poza tym tylko gdy pokazana jest ikona alarmu`() {
        assertTrue(LightIcons.requiresConfirmation(124, LightIcon.ALARM))
        // Ręcznie wybrana inna ikona nie znosi potwierdzenia dla wyjścia alarmowego z bazy.
        assertTrue(LightIcons.requiresConfirmation(124, LightIcon.BULB))
        // Zwykłe wyjście, któremu użytkownik wybrał ikonę alarmu, też pyta.
        assertTrue(LightIcons.requiresConfirmation(3, LightIcon.ALARM))
        assertTrue(LightIcons.requiresConfirmation(null, LightIcon.ALARM))
        assertFalse(LightIcons.requiresConfirmation(3, LightIcon.BULB))
        assertFalse(LightIcons.requiresConfirmation(null, LightIcon.SWITCH))
        assertFalse(LightIcons.requiresConfirmation(1064, LightIcon.PUMP))
    }

    @Test
    fun `kafelki alarmowe - tylko przelaczniki z icon_id 124`() {
        val config = HomeConfig(
            levels = emptyList(), rooms = emptyList(), modules = listOf(1),
            outputs = listOf(
                output("Zwykłe", 3, relay = 0),
                output("Uzbrój", 124, relay = 1),
                output("Panika", 124, relay = 2, kind = OutputKind.LED),
                output("Kolorowe", 124, relay = 3, kind = OutputKind.RGB), // RGB ma osobny kafelek, nie jest przełącznikiem
            ),
            heatingZones = emptyList(),
        )
        assertEquals(setOf<TileId>(TileId.Switch(1, 1), TileId.Switch(1, 2)), alarmTileIds(config))
        assertEquals(emptySet<TileId>(), alarmTileIds(null))
    }
}
