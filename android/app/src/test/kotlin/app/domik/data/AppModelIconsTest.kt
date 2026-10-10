@file:OptIn(ExperimentalCoroutinesApi::class)

package app.domik.data

import app.domik.deimic.config.HomeConfig
import app.domik.deimic.config.InMemoryConfigCache
import app.domik.demo.DemoNetwork
import app.domik.demo.DemoSimulation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Ikony kafelków wybrane ręcznie w [AppModel]: zapis per profil, przywracanie po restarcie, niezależność od układu. */
class AppModelIconsTest {
    private val store = InMemoryKeyValueStore()
    private val settings = AppSettings(store, FakeCipher()) { "profil-test" }
    private val columns = 8

    private fun TestScope.model(): AppModel {
        settings.acceptTerms()
        settings.mode = AppMode.DEMO
        val dispatcher = StandardTestDispatcher(testScheduler)
        val env = AppEnvironment(
            settings = settings,
            demoCache = { InMemoryConfigCache() },
            controllerCache = { InMemoryConfigCache() },
            sessionDispatcher = dispatcher,
            ioDispatcher = dispatcher,
            demoNetwork = DemoNetwork.Instant,
            demoSimulation = DemoSimulation.Manual,
        )
        return AppModel(env, backgroundScope).also { runCurrent() }
    }

    private fun AppModel.config(): HomeConfig = checkNotNull(checkNotNull(session.value).repository.state.value.config)

    private fun AppModel.profileId(): String = checkNotNull(session.value).profileId

    private fun AppModel.firstSwitch(): TileId.Switch =
        config().outputs.first { it.isSwitch && !it.isAlarm }.let { TileId.Switch(it.module, it.relay) }

    @Test
    fun `bez wyboru ikony wszystko jest automatyczne`() = runTest {
        val model = model()
        assertEquals(IconChoices.EMPTY, model.icons.value)
        assertNull(settings.icons(model.profileId()))
    }

    @Test
    fun `wybor ikony jest od razu zapisywany dla profilu sesji`() = runTest {
        val model = model()
        val tile = model.firstSwitch()

        model.setTileIcon(tile, LightIcon.FAN)

        assertEquals(LightIcon.FAN, model.icons.value[tile])
        assertEquals("v1;${tile.code}=fan", settings.icons(model.profileId()))
        assertEquals("v1;${tile.code}=fan", store.getString("icons.${model.profileId()}"))
    }

    @Test
    fun `automatycznie usuwa wybor a pusty zbior kasuje caly wpis`() = runTest {
        val model = model()
        val tile = model.firstSwitch()
        model.setTileIcon(tile, LightIcon.PUMP)
        assertNotNull(settings.icons(model.profileId()))

        model.setTileIcon(tile, null)

        assertTrue(model.icons.value.isEmpty)
        assertNull(settings.icons(model.profileId()))
    }

    @Test
    fun `ten sam wybor drugi raz nic nie zmienia`() = runTest {
        val model = model()
        val tile = model.firstSwitch()
        model.setTileIcon(tile, LightIcon.PUMP)
        val before = model.icons.value

        model.setTileIcon(tile, LightIcon.PUMP)
        model.setTileIcon(TileId.Switch(9, 9), null)

        assertEquals(before, model.icons.value)
    }

    @Test
    fun `wybor ikon przetrwa ponowne uruchomienie aplikacji`() = runTest {
        val first = model()
        val tile = first.firstSwitch()
        first.setTileIcon(tile, LightIcon.SCONCE)

        val second = model()

        assertEquals(LightIcon.SCONCE, second.icons.value[tile])
    }

    @Test
    fun `uszkodzone wpisy w zapisie sa pomijane`() = runTest {
        val first = model()
        val tile = first.firstSwitch()
        settings.saveIcons(first.profileId(), "v1;${tile.code}=fan;s:1:99=nieznana;smieci")

        val second = model()

        assertEquals(IconChoices(mapOf(tile to LightIcon.FAN)), second.icons.value)
    }

    @Test
    fun `calkowicie uszkodzony zapis to dobor automatyczny`() = runTest {
        val first = model()
        settings.saveIcons(first.profileId(), "to nie jest zapis")

        assertEquals(IconChoices.EMPTY, model().icons.value)
    }

    @Test
    fun `przywrocenie ukladu domyslnego nie kasuje ikon`() = runTest {
        val model = model()
        val config = model.config()
        val tile = model.firstSwitch()
        model.setTileIcon(tile, LightIcon.SPOT)
        model.startLayoutEdit(config, columns)
        model.hideTile(TileId.Weather)

        model.resetLayout(config, columns)

        assertNull(settings.layout(model.profileId()))
        assertEquals(LightIcon.SPOT, model.icons.value[tile])
        assertEquals("v1;${tile.code}=spot", settings.icons(model.profileId()))
    }

    @Test
    fun `zmiany ukladu nie ruszaja ikon i odwrotnie`() = runTest {
        val model = model()
        val config = model.config()
        val tile = model.firstSwitch()
        model.startLayoutEdit(config, columns)
        model.hideTile(tile)
        model.setTileIcon(tile, LightIcon.LED)

        assertEquals(LightIcon.LED, model.icons.value[tile]) // także dla ukrytego kafelka
        assertFalse(tile in checkNotNull(model.layout.value.draft))
        assertNotNull(settings.layout(model.profileId()))
    }

    @Test
    fun `zakonczenie sesji czysci stan ikon ale nie zapis`() = runTest {
        val model = model()
        val tile = model.firstSwitch()
        val profile = model.profileId()
        model.setTileIcon(tile, LightIcon.FAN)

        model.leaveDemo()
        runCurrent()

        assertEquals(IconChoices.EMPTY, model.icons.value)
        assertEquals("v1;${tile.code}=fan", settings.icons(profile))
    }

    @Test
    fun `bez sesji wybor ikony jest ignorowany`() = runTest {
        val model = model()
        val tile = model.firstSwitch()
        model.leaveDemo()
        runCurrent()

        model.setTileIcon(tile, LightIcon.FAN)

        assertEquals(IconChoices.EMPTY, model.icons.value)
    }

    // Wyjścia alarmowe w domu demonstracyjnym

    @Test
    fun `dom demonstracyjny ma wyjscie alarmowe i jest ono domyslnie ukryte`() = runTest {
        val model = model()
        val config = model.config()
        val alarm = config.outputs.single { it.isAlarm }
        assertEquals("Uzbrój alarm", alarm.name)
        val tile = TileId.Switch(alarm.module, alarm.relay)

        val layout = model.layout.value.resolve(config, columns)

        assertFalse(tile in layout)
        assertEquals(listOf(tile), layout.hidden.map { it.id })
        assertEquals(LightIcon.ALARM, alarm.automaticIcon())
    }

    @Test
    fun `dom demonstracyjny pokazuje kilka rodzajow ikon`() = runTest {
        val config = model().config()

        val icons = config.outputs.filter { it.isSwitch }.map { it.automaticIcon() }.toSet()

        assertTrue(
            icons.containsAll(
                setOf(
                    LightIcon.BULB, LightIcon.SCONCE, LightIcon.PENDANT, LightIcon.FLOOR, LightIcon.SPOT, LightIcon.LED,
                    LightIcon.OUTDOOR, LightIcon.SOCKET, LightIcon.FAN, LightIcon.PUMP, LightIcon.ALARM,
                ),
            ),
            "ikony w domu demonstracyjnym: $icons",
        )
    }
}
