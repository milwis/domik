package app.domik.ui.dashboard

import app.domik.data.ConfigStatus
import app.domik.data.DashboardLayout
import app.domik.data.HomeState
import app.domik.data.IconChoices
import app.domik.data.LightIcon
import app.domik.data.OutputKey
import app.domik.data.TileId
import app.domik.deimic.config.HomeConfig
import app.domik.deimic.config.Output
import app.domik.deimic.config.OutputKind
import app.domik.ui.components.DeviceIcon
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Ikony kafelków-przełączników i wyjścia alarmowe w modelu pulpitu. */
class DashboardModelIconsTest {
    private fun output(relay: Int, name: String, iconId: Int? = 3, kind: OutputKind = OutputKind.ON_OFF) = Output(
        dbId = relay.toLong(), module = 1, relay = relay, kind = kind, rawType = kind.rawType ?: 99, name = name,
        roomId = null, levelId = null, iconId = iconId, canOn = true, outputsNumber = null,
    )

    private val config = HomeConfig(
        levels = emptyList(), rooms = emptyList(), modules = listOf(1),
        outputs = listOf(
            output(0, "Sufit"),
            output(1, "Kinkiet"),
            output(2, "Choinka", iconId = 1003),
            output(3, "Taśma", kind = OutputKind.LED),
            output(4, "Uzbrój strefę", iconId = 124),
            output(5, "Pompa", iconId = 1064),
        ),
        heatingZones = emptyList(),
    )

    private fun sw(relay: Int) = TileId.Switch(1, relay)

    private fun state(on: Set<OutputKey> = emptySet(), pending: Map<OutputKey, Boolean> = emptyMap()) = HomeState(
        config = config, configStatus = ConfigStatus.Ready, synced = true, outputsOn = on, pendingOutputs = pending,
    )

    private val layout = DashboardLayout.default(config)

    private fun switches(icons: IconChoices = IconChoices.EMPTY, state: HomeState = state(), layout: DashboardLayout = this.layout) =
        buildDashboard(state, layout, icons).switches.associateBy { it.id }

    @Test
    fun `ikony dobierane automatycznie z icon_id nazwy i typu wyjscia`() {
        val tiles = switches()
        assertEquals(LightIcon.BULB, tiles.getValue(sw(0)).lightIcon)
        assertEquals(LightIcon.SCONCE, tiles.getValue(sw(1)).lightIcon)
        assertEquals(LightIcon.SOCKET, tiles.getValue(sw(2)).lightIcon)
        assertEquals(LightIcon.LED, tiles.getValue(sw(3)).lightIcon)
        assertEquals(LightIcon.PUMP, tiles.getValue(sw(5)).lightIcon)
        assertEquals(DeviceIcon.Sconce, tiles.getValue(sw(1)).icon)
        assertEquals(DeviceIcon.Socket, tiles.getValue(sw(2)).icon)
        assertEquals(DeviceIcon.LedStrip, tiles.getValue(sw(3)).icon)
        assertEquals(DeviceIcon.Pump, tiles.getValue(sw(5)).icon)
    }

    @Test
    fun `ikona wybrana przez uzytkownika ma pierwszenstwo a automatyczna zostaje do podgladu`() {
        val chosen = IconChoices.EMPTY.with(sw(1), LightIcon.FAN).with(sw(0), LightIcon.OUTDOOR)

        val tiles = switches(chosen)

        assertEquals(LightIcon.FAN, tiles.getValue(sw(1)).lightIcon)
        assertEquals(DeviceIcon.Fan, tiles.getValue(sw(1)).icon)
        assertEquals(LightIcon.SCONCE, tiles.getValue(sw(1)).autoIcon)
        assertEquals(LightIcon.OUTDOOR, tiles.getValue(sw(0)).lightIcon)
        assertEquals(LightIcon.BULB, tiles.getValue(sw(0)).autoIcon)
        // Pozostałe bez zmian.
        assertEquals(LightIcon.SOCKET, tiles.getValue(sw(2)).lightIcon)
    }

    @Test
    fun `wybor dla nieistniejacego kafelka jest bez skutku`() {
        val tiles = switches(IconChoices.EMPTY.with(TileId.Switch(1, 99), LightIcon.FAN).with(TileId.Switch(7, 0), LightIcon.PUMP))
        assertEquals(LightIcon.BULB, tiles.getValue(sw(0)).lightIcon)
        assertEquals(setOf(sw(0), sw(1), sw(2), sw(3), sw(5)), tiles.keys)
    }

    // Wyjścia alarmowe

    @Test
    fun `wyjscie alarmowe jest ukryte w ukladzie domyslnym i ma grupe Alarm w arkuszu Dodaj`() {
        val model = buildDashboard(state(), layout)

        assertFalse(sw(4) in model.switches.map { it.id })
        val hidden = model.hidden.single()
        assertEquals(sw(4), hidden.id)
        assertEquals(TileGroup.ALARM, hidden.group)
        assertEquals("Uzbrój strefę", hidden.name)
        // Reszta ukrytych (gdyby były) zostaje w grupie Światła.
        assertTrue(model.hasDevices)
    }

    @Test
    fun `ukryty zwykly przelacznik zostaje w grupie Swiatla`() {
        val model = buildDashboard(state(), layout.hide(sw(0)))
        assertEquals(TileGroup.LIGHTS, model.hidden.single { it.id == sw(0) }.group)
        assertEquals(TileGroup.ALARM, model.hidden.single { it.id == sw(4) }.group)
    }

    @Test
    fun `po dodaniu przez uzytkownika kafelek alarmu jest na pulpicie i wymaga potwierdzenia`() {
        val tiles = switches(layout = layout.show(sw(4)))
        val alarm = tiles.getValue(sw(4))

        assertTrue(alarm.confirm)
        assertEquals(LightIcon.ALARM, alarm.lightIcon)
        assertEquals(DeviceIcon.Alarm, alarm.icon)
        assertTrue(tiles.filterKeys { it != sw(4) }.values.none { it.confirm })
    }

    @Test
    fun `dotkniecie alarmu zawsze daje prosbe o potwierdzenie - przy wlaczaniu i wylaczaniu`() {
        val shown = layout.show(sw(4))

        val off = switches(layout = shown).getValue(sw(4))
        assertEquals(ConfirmRequest(sw(4), "Uzbrój strefę", turnOn = true), off.confirmRequest())

        val on = switches(state = state(on = setOf(OutputKey(1, 4))), layout = shown).getValue(sw(4))
        assertEquals(ConfirmRequest(sw(4), "Uzbrój strefę", turnOn = false), on.confirmRequest())
    }

    @Test
    fun `zwykle wyjscie nie pyta o potwierdzenie`() {
        val tiles = switches()
        assertNull(tiles.getValue(sw(0)).confirmRequest())
        assertNull(tiles.getValue(sw(2)).confirmRequest())
        assertNull(tiles.getValue(sw(5)).confirmRequest())
    }

    @Test
    fun `stan docelowy w trakcie przelaczania - kierunek wg pokazanego stanu a w trakcie nic`() {
        val shown = layout.show(sw(4))
        val key = OutputKey(1, 4)

        // Oczekujące włączenie: nie ma o co pytać drugi raz.
        val pending = switches(state = state(pending = mapOf(key to true)), layout = shown).getValue(sw(4))
        assertTrue(pending.pending && pending.on)
        assertNull(pending.confirmRequest())
    }

    @Test
    fun `zwykle wyjscie z recznie wybrana ikona alarmu tez pyta o potwierdzenie`() {
        val tile = switches(IconChoices.EMPTY.with(sw(0), LightIcon.ALARM)).getValue(sw(0))
        assertTrue(tile.confirm)
        assertEquals(ConfirmRequest(sw(0), "Sufit", turnOn = true), tile.confirmRequest())
        // Ale nie jest to „wyjście alarmowe z bazy” – nie jest domyślnie ukryte.
        assertTrue(sw(0) in layout)
    }

    @Test
    fun `wyjscie alarmowe z recznie zmieniona ikona nadal pyta o potwierdzenie`() {
        val tile = switches(IconChoices.EMPTY.with(sw(4), LightIcon.BULB), layout = layout.show(sw(4))).getValue(sw(4))
        assertEquals(LightIcon.BULB, tile.lightIcon)
        assertTrue(tile.confirm)
        assertNotNull(tile.confirmRequest())
    }

    @Test
    fun `potwierdzenie przelacza tylko gdy wyjscie nadal nie jest w zadanym stanie`() {
        val enable = ConfirmRequest(sw(4), "Uzbrój strefę", turnOn = true)
        assertTrue(enable.stillNeeded(currentlyOn = false))
        assertFalse(enable.stillNeeded(currentlyOn = true)) // ktoś już włączył – przełączenie wyłączyłoby

        val disable = ConfirmRequest(sw(4), "Uzbrój strefę", turnOn = false)
        assertTrue(disable.stillNeeded(currentlyOn = true))
        assertFalse(disable.stillNeeded(currentlyOn = false))
    }
}
