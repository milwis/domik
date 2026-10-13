package app.domik.ui.dashboard

import app.domik.data.ConfigStatus
import app.domik.data.HomeState
import app.domik.data.TileId
import app.domik.deimic.client.commands.PendingSetpoint
import app.domik.deimic.client.commands.ZoneKey
import app.domik.deimic.config.HeatingZone
import app.domik.deimic.config.HomeConfig
import app.domik.deimic.config.Level
import app.domik.deimic.config.Room
import app.domik.deimic.protocol.HeaterState
import app.domik.ui.components.HeatingProfile as TileProfile
import app.domik.deimic.protocol.HeatingProfile as ProtocolProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HeatingZoneDialogModelTest {
    private val config = HomeConfig(
        levels = listOf(Level(1, "Parter")),
        rooms = listOf(Room(1, 1, "Salon")),
        modules = listOf(1),
        outputs = emptyList(),
        heatingZones = listOf(HeatingZone(100, 1, 0, "Strefa A", roomId = 1, levelId = 1)),
    )
    private val id = TileId.Zone(1, 0)
    private val key = ZoneKey(1, 0)

    private fun heater(profile: ProtocolProfile, setpoint: Float, current: Float = 20.5f, heating: Boolean = false) =
        HeaterState(1u, 0u, heating, current, profile, setpoint)

    private fun state(
        zones: Map<ZoneKey, HeaterState> = emptyMap(),
        pendingProfiles: Map<ZoneKey, ProtocolProfile> = emptyMap(),
        pendingSetpoints: Map<ZoneKey, PendingSetpoint> = emptyMap(),
        config: HomeConfig? = this.config,
    ) = HomeState(
        config = config, configStatus = ConfigStatus.Ready, synced = true,
        zones = zones, pendingProfiles = pendingProfiles, pendingSetpoints = pendingSetpoints,
    )

    private fun ui(
        state: HomeState,
        lastKnown: ZoneDialogModel? = null,
        online: Boolean = true,
        failed: Boolean = false,
    ) = zoneDialogUi(zoneDialogModel(state, id), lastKnown, online, failed)

    private val comfort get() = state(zones = mapOf(key to heater(ProtocolProfile.Comfort, 22.0f, current = 21.5f, heating = true)))

    // zoneDialogModel

    @Test
    fun `model okna ma te same dane co kafelek strefy`() {
        val model = checkNotNull(zoneDialogModel(comfort, id))
        assertEquals("Strefa A", model.tile.name)
        assertEquals("Salon", model.tile.room) // pomieszczenie z konfiguracji (nazwa różni się od nazwy strefy)
        assertEquals(21.5f, model.tile.currentTemp)
        assertEquals(22.0f, model.tile.setpoint)
        assertEquals(TileProfile.Comfort, model.tile.profile)
        assertTrue(model.tile.heating && model.hasReading && !model.savingSetpoint)
    }

    @Test
    fun `model okna pokazuje temperature termometru, gdy strefa ma swiezy odczyt`() {
        val model = checkNotNull(zoneDialogModel(comfort, id, thermometerTemp = 22.4f))
        assertEquals(22.4f, model.tile.currentTemp)
        assertTrue(model.tile.fromThermometer)
        assertEquals(22.0f, model.tile.setpoint)
        val without = checkNotNull(zoneDialogModel(comfort, id))
        assertEquals(21.5f, without.tile.currentTemp)
        assertFalse(without.tile.fromThermometer)
    }

    @Test
    fun `strefy spoza konfiguracji i brak konfiguracji to null`() {
        assertNull(zoneDialogModel(comfort, TileId.Zone(9, 9)))
        assertNull(zoneDialogModel(state(config = null), id))
    }

    @Test
    fun `niepotwierdzona temperatura zadana jest pokazana i oznaczona jako zapisywana`() {
        val pending = state(
            zones = mapOf(key to heater(ProtocolProfile.Comfort, 22.0f)),
            pendingSetpoints = mapOf(key to PendingSetpoint(target = 23.0f, confirmed = 22.0f, isSending = false)),
        )
        val model = checkNotNull(zoneDialogModel(pending, id))
        assertEquals(23.0f, model.tile.setpoint)
        assertTrue(model.savingSetpoint)
    }

    // zoneDialogUi: komunikat i aktywność kontrolek

    @Test
    fun `zwykly stan - bez komunikatu, wszystkie kontrolki aktywne`() {
        val ui = ui(comfort)
        assertEquals(ZoneDialogStatus.NONE, ui.status)
        assertTrue(ui.controls.canDecrease && ui.controls.canIncrease && ui.controls.profileEnabled)
        assertFalse(ui.controls.increaseSwitchesOn)
    }

    @Test
    fun `granice zakresu wylaczaja odpowiedni przycisk jak na kafelku`() {
        val low = ui(state(zones = mapOf(key to heater(ProtocolProfile.Eco, 5.0f))))
        assertFalse(low.controls.canDecrease)
        assertTrue(low.controls.canIncrease)
        val high = ui(state(zones = mapOf(key to heater(ProtocolProfile.Eco, 30.0f))))
        assertTrue(high.controls.canDecrease)
        assertFalse(high.controls.canIncrease)
    }

    @Test
    fun `wylaczona strefa - aktywny tylko plus, ktory ja wlacza, i wybor profilu`() {
        val ui = ui(state(zones = mapOf(key to heater(ProtocolProfile.Off, 5.0f))))
        assertEquals(ZoneDialogStatus.NONE, ui.status)
        assertFalse(ui.controls.canDecrease)
        assertTrue(ui.controls.canIncrease && ui.controls.increaseSwitchesOn && ui.controls.profileEnabled)
    }

    @Test
    fun `zmiana profilu w toku - zapisywanie i zablokowane sterowanie, a nowy profil widac od razu`() {
        val ui = ui(
            state(
                zones = mapOf(key to heater(ProtocolProfile.Comfort, 22.0f)),
                pendingProfiles = mapOf(key to ProtocolProfile.Eco),
            ),
        )
        assertEquals(ZoneDialogStatus.SAVING, ui.status)
        assertEquals(TileProfile.Eco, ui.tile?.profile)
        assertFalse(ui.controls.canDecrease || ui.controls.canIncrease || ui.controls.profileEnabled)
    }

    @Test
    fun `zapisywanie temperatury zadanej nie blokuje kolejnych dotkniec`() {
        val ui = ui(
            state(
                zones = mapOf(key to heater(ProtocolProfile.Comfort, 22.0f)),
                pendingSetpoints = mapOf(key to PendingSetpoint(22.5f, 22.0f, isSending = true)),
            ),
        )
        assertEquals(ZoneDialogStatus.SAVING, ui.status)
        assertTrue(ui.controls.canDecrease && ui.controls.canIncrease && ui.controls.profileEnabled)
    }

    @Test
    fun `blad polecenia - komunikat, sterowanie dalej aktywne`() {
        val ui = ui(comfort, failed = true)
        assertEquals(ZoneDialogStatus.ERROR, ui.status)
        assertTrue(ui.controls.canDecrease && ui.controls.canIncrease && ui.controls.profileEnabled)
    }

    @Test
    fun `brak polaczenia - komunikat i wszystko nieaktywne, ale stan nadal widac`() {
        val ui = ui(comfort, online = false)
        assertEquals(ZoneDialogStatus.OFFLINE, ui.status)
        assertFalse(ui.controls.canDecrease || ui.controls.canIncrease || ui.controls.profileEnabled)
        assertEquals(22.0f, ui.tile?.setpoint)
    }

    @Test
    fun `brak polaczenia wygrywa z bledem polecenia`() {
        assertEquals(ZoneDialogStatus.OFFLINE, ui(comfort, online = false, failed = true).status)
    }

    @Test
    fun `strefa jeszcze nieodczytana - czekanie na odczyt i brak sterowania, takze profilu`() {
        val ui = ui(state())
        assertEquals(ZoneDialogStatus.NO_READING, ui.status)
        assertNotNull(ui.tile)
        assertFalse(ui.controls.canDecrease || ui.controls.canIncrease || ui.controls.profileEnabled)
    }

    @Test
    fun `strefa zniknela - ostatni znany stan bez sterowania i komunikat`() {
        val last = zoneDialogModel(comfort, id)
        val ui = zoneDialogUi(zoneDialogModel(state(config = null), id), last, online = true, failed = false)
        assertEquals(ZoneDialogStatus.GONE, ui.status)
        assertEquals("Strefa A", ui.tile?.name)
        assertEquals(22.0f, ui.tile?.setpoint)
        assertFalse(ui.controls.canDecrease || ui.controls.canIncrease || ui.controls.profileEnabled)
    }

    @Test
    fun `strefa, ktorej okno nigdy nie widzialo - komunikat bez crasha`() {
        val ui = zoneDialogUi(model = null, lastKnown = null, online = false, failed = true)
        assertEquals(ZoneDialogStatus.GONE, ui.status)
        assertNull(ui.tile)
        assertFalse(ui.controls.canDecrease || ui.controls.canIncrease || ui.controls.profileEnabled)
    }

    // profile

    @Test
    fun `profile interfejsu mapuja sie na profile protokolu`() {
        assertEquals(ProtocolProfile.Off, TileProfile.Off.toProtocolProfile())
        assertEquals(ProtocolProfile.Eco, TileProfile.Eco.toProtocolProfile())
        assertEquals(ProtocolProfile.Comfort, TileProfile.Comfort.toProtocolProfile())
        assertEquals(ProtocolProfile.Maximum, TileProfile.Max.toProtocolProfile())
    }
}
