package app.domik.ui.dashboard

import app.domik.data.ConfigStatus
import app.domik.data.DashboardLayout
import app.domik.data.HomeState
import app.domik.data.OutputKey
import app.domik.data.ShutterButton
import app.domik.data.ShutterKey
import app.domik.data.TileEntry
import app.domik.data.TileId
import app.domik.data.TileSize
import app.domik.deimic.client.commands.PendingSetpoint
import app.domik.deimic.client.commands.ZoneKey
import app.domik.deimic.config.HeatingZone
import app.domik.deimic.config.HomeConfig
import app.domik.deimic.config.Level
import app.domik.deimic.config.Output
import app.domik.deimic.config.OutputKind
import app.domik.deimic.config.Room
import app.domik.deimic.config.Shutter
import app.domik.deimic.protocol.HeaterState
import app.domik.deimic.protocol.ShutterDirection
import app.domik.ui.components.ShutterControl
import app.domik.ui.components.ShutterDisplay
import app.domik.ui.components.HeatingProfile as TileProfile
import app.domik.deimic.protocol.HeatingProfile as ProtocolProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DashboardModelTest {
    private fun output(relay: Int, name: String, roomId: Int?, kind: OutputKind = OutputKind.ON_OFF, canOn: Boolean = true) = Output(
        dbId = relay.toLong(), module = 1, relay = relay, kind = kind, rawType = kind.rawType ?: 99, name = name,
        roomId = roomId, levelId = null, iconId = 3, canOn = canOn, outputsNumber = null,
    )

    private val zones = listOf(
        HeatingZone(100, 1, 0, "Salon", roomId = 1, levelId = 1),
        HeatingZone(101, 2, 0, "Gabinet", roomId = 4, levelId = 1),
        HeatingZone(102, 1, 3, "Strefa bez pokoju", roomId = null, levelId = null),
    )
    private val config = HomeConfig(
        levels = listOf(Level(1, "Parter")),
        rooms = listOf(Room(1, 1, "Salon"), Room(4, 1, "Biuro")),
        modules = listOf(1, 2),
        outputs = listOf(
            output(0, "Sufit", 1),
            output(2, "Kinkiet", 4),
            output(3, "Przycisk", 1, canOn = false),
        ),
        heatingZones = zones,
        shutters = listOf(
            Shutter(300, 1, 0, "Roleta okienna", roomId = 1, levelId = 1, iconId = 3), // ten sam numer co światło „Sufit”
            Shutter(301, 2, 0, "Roleta gabinetu", roomId = 4, levelId = 1, iconId = null), // ten sam numer na drugim module
            Shutter(302, 1, 2, "Roleta bez pokoju", roomId = null, levelId = null, iconId = null),
        ),
    )
    private val layout = DashboardLayout.default(config)

    private fun heater(profile: ProtocolProfile, setpoint: Float, current: Float = 20.0f, heating: Boolean = false) =
        HeaterState(1u, 0u, heating, current, profile, setpoint)

    private fun state(
        zones: Map<ZoneKey, HeaterState> = emptyMap(),
        on: Set<OutputKey> = emptySet(),
        pendingOutputs: Map<OutputKey, Boolean> = emptyMap(),
        pendingProfiles: Map<ZoneKey, ProtocolProfile> = emptyMap(),
        pendingSetpoints: Map<ZoneKey, PendingSetpoint> = emptyMap(),
        shutters: Map<ShutterKey, ShutterDirection> = emptyMap(),
        pendingShutters: Map<ShutterKey, ShutterButton> = emptyMap(),
    ) = HomeState(
        config = config, configStatus = ConfigStatus.Ready, synced = true, outputsOn = on, pendingOutputs = pendingOutputs,
        zones = zones, pendingProfiles = pendingProfiles, pendingSetpoints = pendingSetpoints,
        shutters = shutters, pendingShutters = pendingShutters,
    )

    @Test
    fun `kafelki wedlug ukladu - strefy, przelaczniki i rolety`() {
        val model = buildDashboard(state(), layout)
        assertEquals(listOf(TileId.Zone(1, 0), TileId.Zone(2, 0), TileId.Zone(1, 3)), model.zones.map { it.id })
        assertEquals(listOf("Sufit", "Kinkiet", "Przycisk"), model.switches.map { it.name })
        assertEquals(
            listOf(TileId.Shutter(1, 0), TileId.Shutter(2, 0), TileId.Shutter(1, 2)), model.shutters.map { it.id },
        )
        assertEquals(listOf("Roleta okienna", "Roleta gabinetu", "Roleta bez pokoju"), model.shutters.map { it.name })
        assertTrue(model.shutters.all { it.size == TileSize.M }, "rolety domyślnie M (2×1)")
        assertTrue(model.showWeather && model.showAgenda && model.hasDevices)
    }

    @Test
    fun `bez konfiguracji nie ma kafelkow urzadzen`() {
        val model = buildDashboard(HomeState(), DashboardLayout.default(null))
        assertTrue(model.zones.isEmpty() && model.switches.isEmpty() && model.shutters.isEmpty())
        assertFalse(model.hasDevices)
    }

    @Test
    fun `urzadzenia usuniete z konfiguracji nie maja kafelka`() {
        val stale = DashboardLayout(
            layout.tiles + TileEntry(TileId.Switch(1, 99), TileSize.S) + TileEntry(TileId.Zone(9, 9), TileSize.L) +
                TileEntry(TileId.Shutter(9, 9), TileSize.M),
            hidden = listOf(TileEntry(TileId.Switch(1, 98), TileSize.S), TileEntry(TileId.Shutter(9, 8), TileSize.M)),
        )
        val model = buildDashboard(state(), stale)
        assertEquals(3, model.switches.size)
        assertEquals(3, model.zones.size)
        assertEquals(3, model.shutters.size)
        assertTrue(model.hidden.isEmpty())
    }

    @Test
    fun `kafelki dostaja rozmiar z ukladu`() {
        val resized = layout
            .resized(TileId.Zone(1, 0), TileSize.M)
            .resized(TileId.Switch(1, 0), TileSize.M)
            .resized(TileId.Shutter(2, 0), TileSize.L) // niedozwolony (roleta tylko 2×1) – bez zmian
            .resized(TileId.Weather, TileSize.L)
        val model = buildDashboard(state(), resized)
        assertEquals(TileSize.M, model.zones[0].size)
        assertEquals(TileSize.L, model.zones[1].size)
        assertEquals(TileSize.M, model.switches[0].size)
        assertEquals(TileSize.S, model.switches[1].size)
        assertEquals(TileSize.M, model.shutters[0].size)
        assertEquals(TileSize.M, model.shutters[1].size)
        assertEquals(TileSize.L, model.tiles.first { it is WeatherTileModel }.size)
    }

    @Test
    fun `ukryte kafelki trafiaja do grup arkusza Dodaj z nazwami z konfiguracji`() {
        val hiddenLayout = layout.hide(TileId.Zone(2, 0)).hide(TileId.Switch(1, 2)).hide(TileId.Shutter(2, 0)).hide(TileId.Agenda)
        val model = buildDashboard(state(), hiddenLayout)
        assertEquals(listOf(TileId.Zone(1, 0), TileId.Zone(1, 3)), model.zones.map { it.id })
        assertFalse(model.showAgenda)
        assertEquals(
            listOf(
                HiddenTileModel(TileId.Zone(2, 0), "Gabinet", "Biuro", TileGroup.HEATING),
                HiddenTileModel(TileId.Switch(1, 2), "Kinkiet", "Biuro", TileGroup.LIGHTS),
                HiddenTileModel(TileId.Shutter(2, 0), "Roleta gabinetu", "Biuro", TileGroup.SHUTTERS),
                HiddenTileModel(TileId.Agenda, "", "", TileGroup.OTHER),
            ),
            model.hidden,
        )
        assertEquals("Gabinet", model.hidden.first { it.id == TileId.Zone(2, 0) }.name)
        assertEquals("Biuro", model.hidden.first { it.id == TileId.Switch(1, 2) }.room)
    }

    @Test
    fun `pulpit z wszystkimi urzadzeniami ukrytymi nadal ma urzadzenia - da sie je dodac`() {
        var all = layout
        layout.tiles.forEach { all = all.hide(it.id) }
        val model = buildDashboard(state(), all)
        assertTrue(model.tiles.isEmpty())
        assertTrue(model.hasDevices)
        assertEquals(layout.tiles.size, model.hidden.size)
    }

    @Test
    fun `podpis - pomieszczenie, a gdy takie samo jak nazwa lub brak, to pietro`() {
        val model = buildDashboard(state(), layout)
        assertEquals("Parter", model.zones[0].state.room) // strefa „Salon” w pokoju „Salon”
        assertEquals("Biuro", model.zones[1].state.room)
        assertEquals("", model.zones[2].state.room)
        assertEquals("Salon", model.switches[0].room)
    }

    @Test
    fun `strefa jeszcze nieodczytana jest wygaszona i bez wartosci`() {
        val tile = buildDashboard(state(), layout).zones[0].state
        assertNull(tile.currentTemp)
        assertNull(tile.setpoint)
        assertEquals(TileProfile.Off, tile.profile)
        assertFalse(tile.canDecrease || tile.canIncrease || tile.canSwitchOn || tile.busy)
    }

    @Test
    fun `strefa z odczytu`() {
        val key = ZoneKey(1, 0)
        val tile = buildDashboard(
            state(zones = mapOf(key to heater(ProtocolProfile.Comfort, 22.0f, current = 21.5f, heating = true))), layout,
        ).zones[0].state
        assertEquals(21.5f, tile.currentTemp)
        assertEquals(22.0f, tile.setpoint)
        assertEquals(TileProfile.Comfort, tile.profile)
        assertTrue(tile.heating && tile.canDecrease && tile.canIncrease && !tile.busy && !tile.canSwitchOn)
    }

    @Test
    fun `granice zakresu wylaczaja przyciski`() {
        val key = ZoneKey(1, 0)
        assertFalse(buildDashboard(state(zones = mapOf(key to heater(ProtocolProfile.Eco, 5.0f))), layout).zones[0].state.canDecrease)
        assertFalse(buildDashboard(state(zones = mapOf(key to heater(ProtocolProfile.Eco, 30.0f))), layout).zones[0].state.canIncrease)
        assertTrue(buildDashboard(state(zones = mapOf(key to heater(ProtocolProfile.Eco, 5.5f))), layout).zones[0].state.canDecrease)
    }

    @Test
    fun `niepoprawna temperatura z czujnika to brak odczytu`() {
        val key = ZoneKey(1, 0)
        val tile = buildDashboard(state(zones = mapOf(key to heater(ProtocolProfile.Comfort, 22f, current = Float.NaN))), layout).zones[0].state
        assertNull(tile.currentTemp)
    }

    @Test
    fun `oczekujaca temperatura zadana jest pokazana od razu`() {
        val key = ZoneKey(1, 0)
        val tile = buildDashboard(
            state(
                zones = mapOf(key to heater(ProtocolProfile.Comfort, 22.0f)),
                pendingSetpoints = mapOf(key to PendingSetpoint(target = 23.5f, confirmed = 22.0f, isSending = false)),
            ),
            layout,
        ).zones[0].state
        assertEquals(23.5f, tile.setpoint)
        assertFalse(tile.busy)
    }

    @Test
    fun `wylaczona strefa pozwala tylko na wlaczenie plusem`() {
        val key = ZoneKey(2, 0)
        val tile = buildDashboard(state(zones = mapOf(key to heater(ProtocolProfile.Off, 5.0f))), layout).zones[1].state
        assertEquals(TileProfile.Off, tile.profile)
        assertTrue(tile.canSwitchOn)
        assertFalse(tile.busy)
    }

    @Test
    fun `wlaczanie wylaczonej strefy blokuje sterowanie i nie pokazuje fikcyjnej temperatury`() {
        val key = ZoneKey(2, 0)
        val tile = buildDashboard(
            state(
                zones = mapOf(key to heater(ProtocolProfile.Off, 5.0f)),
                pendingSetpoints = mapOf(key to PendingSetpoint(5.5f, 5.0f, isSending = true)),
            ),
            layout,
        ).zones[1].state
        assertEquals(5.0f, tile.setpoint)
        assertTrue(tile.busy)
    }

    @Test
    fun `oczekujacy profil jest pokazany od razu a sterowanie zablokowane`() {
        val key = ZoneKey(1, 0)
        val tile = buildDashboard(
            state(
                zones = mapOf(key to heater(ProtocolProfile.Comfort, 22.0f)),
                pendingProfiles = mapOf(key to ProtocolProfile.Maximum),
            ),
            layout,
        ).zones[0].state
        assertEquals(TileProfile.Max, tile.profile)
        assertTrue(tile.busy)
    }

    @Test
    fun `profil nieznany wyswietla sie jak wylaczony`() {
        val key = ZoneKey(1, 0)
        val tile = buildDashboard(state(zones = mapOf(key to heater(ProtocolProfile.Unknown(9u), 22.0f))), layout).zones[0].state
        assertEquals(TileProfile.Off, tile.profile)
    }

    @Test
    fun `przelacznik - stan z wyjsc, oczekiwanie pokazuje stan docelowy`() {
        val on = buildDashboard(state(on = setOf(OutputKey(1, 0))), layout).switches[0]
        assertTrue(on.on && !on.pending)

        val turningOff = buildDashboard(
            state(on = setOf(OutputKey(1, 0)), pendingOutputs = mapOf(OutputKey(1, 0) to false)), layout,
        ).switches[0]
        assertFalse(turningOff.on)
        assertTrue(turningOff.pending)

        val turningOn = buildDashboard(state(pendingOutputs = mapOf(OutputKey(1, 2) to true)), layout).switches[1]
        assertTrue(turningOn.on && turningOn.pending)
    }

    // Rolety

    private val window = ShutterKey(1, 0)

    private fun shutterTile(
        key: ShutterKey = window,
        direction: ShutterDirection? = null,
        pending: ShutterButton? = null,
    ): ShutterTileModel {
        val model = buildDashboard(
            state(
                shutters = direction?.let { mapOf(key to it) }.orEmpty(),
                pendingShutters = pending?.let { mapOf(key to it) }.orEmpty(),
            ),
            layout,
        )
        return model.shutters.first { it.id.module == key.module && it.id.outputNr == key.outputNr }
    }

    @Test
    fun `roleta bez zdarzenia ma stan nieznany`() {
        val tile = shutterTile()
        assertEquals(ShutterDisplay.Unknown, tile.display)
        assertNull(tile.pending)
    }

    @Test
    fun `kierunki sterownika odwzorowane na podpis kafelka`() {
        assertEquals(ShutterDisplay.MovingUp, shutterTile(direction = ShutterDirection.MOVING_UP).display)
        assertEquals(ShutterDisplay.MovingDown, shutterTile(direction = ShutterDirection.MOVING_DOWN).display)
        assertEquals(ShutterDisplay.Stopped, shutterTile(direction = ShutterDirection.STOPPED).display)
        assertEquals(ShutterDisplay.Open, shutterTile(direction = ShutterDirection.AT_TOP).display)
        assertEquals(ShutterDisplay.Closed, shutterTile(direction = ShutterDirection.AT_BOTTOM).display)
        assertEquals(ShutterDisplay.Unknown, shutterTile(direction = ShutterDirection.UNKNOWN).display)
        assertEquals(ShutterDisplay.Unknown, shutterTile(direction = ShutterDirection.OTHER).display)
        assertTrue(ShutterDisplay.MovingUp.isMoving && ShutterDisplay.MovingDown.isMoving)
        assertFalse(ShutterDisplay.Stopped.isMoving || ShutterDisplay.Open.isMoving || ShutterDisplay.Unknown.isMoving)
    }

    @Test
    fun `wcisniety przycisk jest oczekujacy`() {
        assertEquals(ShutterControl.Up, shutterTile(pending = ShutterButton.UP).pending)
        assertEquals(ShutterControl.Stop, shutterTile(pending = ShutterButton.STOP).pending)
        assertEquals(ShutterControl.Down, shutterTile(pending = ShutterButton.DOWN).pending)
    }

    @Test
    fun `przyciski kafelka odwzorowane z powrotem na przyciski repozytorium`() {
        assertEquals(ShutterButton.UP, ShutterControl.Up.toButton())
        assertEquals(ShutterButton.STOP, ShutterControl.Stop.toButton())
        assertEquals(ShutterButton.DOWN, ShutterControl.Down.toButton())
        // Przycisk aktywny = ten, w którego kierunku roleta jedzie; stop nigdy.
        assertEquals(ShutterDisplay.MovingUp, ShutterControl.Up.movingDisplay)
        assertEquals(ShutterDisplay.MovingDown, ShutterControl.Down.movingDisplay)
        assertNull(ShutterControl.Stop.movingDisplay)
    }

    @Test
    fun `rolety o tym samym numerze na roznych modulach i swiatlo o tym numerze nie myla sie`() {
        val direction = mapOf(ShutterKey(2, 0) to ShutterDirection.MOVING_DOWN)
        val model = buildDashboard(state(shutters = direction, on = setOf(OutputKey(1, 0))), layout)
        assertEquals(ShutterDisplay.Unknown, model.shutters.first { it.id == TileId.Shutter(1, 0) }.display)
        assertEquals(ShutterDisplay.MovingDown, model.shutters.first { it.id == TileId.Shutter(2, 0) }.display)
        assertTrue(model.switches[0].on, "światło o numerze 0 działa niezależnie od rolety 0")
    }

    @Test
    fun `podpis rolety - pomieszczenie, a bez pomieszczenia samo pietro albo nic`() {
        assertEquals("Salon", shutterTile(window).room)
        assertEquals("Biuro", shutterTile(ShutterKey(2, 0)).room)
        assertEquals("", shutterTile(ShutterKey(1, 2)).room)
    }

    @Test
    fun `ukryta roleta wraca przez arkusz Dodaj, a pulpit z samymi roletami ma urzadzenia`() {
        val onlyShutters = DashboardLayout(
            tiles = emptyList(),
            hidden = layout.tiles.filter { it.id is TileId.Shutter },
        )
        val model = buildDashboard(state(), onlyShutters)
        assertTrue(model.tiles.isEmpty())
        assertTrue(model.hasDevices)
        assertEquals(
            listOf(TileGroup.SHUTTERS, TileGroup.SHUTTERS, TileGroup.SHUTTERS),
            model.hidden.map { it.group },
        )
        val shown = buildDashboard(state(), onlyShutters.show(TileId.Shutter(2, 0)))
        assertEquals(listOf(TileId.Shutter(2, 0)), shown.shutters.map { it.id })
        assertEquals(TileSize.M, shown.shutters.single().size)
    }

    @Test
    fun `grupy arkusza Dodaj - kolejnosc wyswietlania`() {
        assertEquals(
            listOf(TileGroup.HEATING, TileGroup.LIGHTS, TileGroup.SHUTTERS, TileGroup.ALARM, TileGroup.OTHER),
            TileGroup.entries,
        )
    }
}
