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

/** Edycja układu pulpitu w [AppModel]: tryb edycji, zapis per profil, przywracanie układu domyślnego. */
class AppModelLayoutTest {
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

    @Test
    fun `bez zapisu obowiazuje uklad domyslny a edycja jeszcze go nie zapisuje`() = runTest {
        val model = model()
        val config = model.config()

        assertFalse(model.layout.value.editing)
        assertNull(model.layout.value.saved)
        assertEquals(DashboardLayout.default(config, columns), model.layout.value.resolve(config, columns))

        model.startLayoutEdit(config, columns)

        assertTrue(model.layout.value.editing)
        assertEquals(DashboardLayout.default(config, columns), model.layout.value.draft)
        assertNull(settings.layout(model.profileId()))
    }

    @Test
    fun `kazda zmiana w edycji jest od razu zapisywana dla profilu sesji`() = runTest {
        val model = model()
        val config = model.config()
        model.startLayoutEdit(config, columns)
        val start = checkNotNull(model.layout.value.draft)
        val last = start.ids.last()

        model.moveTile(last, 0)

        val moved = checkNotNull(model.layout.value.draft)
        assertEquals(last, moved.ids.first())
        assertEquals(moved.encode(), settings.layout(model.profileId()))

        model.cycleTileSize(TileId.Weather)
        assertEquals(TileSize.L, model.layout.value.draft?.size(TileId.Weather))

        // Przeciągnięcie rogu: dowolny rozmiar kalendarza, zapisany od razu; niedozwolony – bez zmian.
        model.resizeTile(TileId.Agenda, TileSize(4, 3))
        assertEquals(TileSize(4, 3), model.layout.value.draft?.size(TileId.Agenda))
        assertTrue(checkNotNull(settings.layout(model.profileId())).contains("+ a 4x3"))
        model.resizeTile(TileId.Agenda, TileSize(1, 1))
        assertEquals(TileSize(4, 3), model.layout.value.draft?.size(TileId.Agenda))
        // Przycisk „rozmiar” w wąskiej siatce pomija rozmiary szersze niż siatka (4×3 spoza listy -> 4×4 za szeroki -> 2×2).
        model.cycleTileSize(TileId.Agenda, columns = 3)
        assertEquals(TileSize.L, model.layout.value.draft?.size(TileId.Agenda))

        model.hideTile(TileId.Agenda)
        assertFalse(TileId.Agenda in checkNotNull(model.layout.value.draft))
        // Wcześniej ukryte jest wyjście alarmowe domu demonstracyjnego (domyślnie ukryte), ostatnio ukryty – kalendarz.
        assertEquals(TileId.Agenda, model.layout.value.draft?.hidden?.last()?.id)

        model.showTile(TileId.Agenda)
        assertEquals(TileId.Agenda, model.layout.value.draft?.ids?.last())
        assertEquals(model.layout.value.draft?.encode(), settings.layout(model.profileId()))
    }

    @Test
    fun `gotowe konczy edycje a zmiany zostaja`() = runTest {
        val model = model()
        val config = model.config()
        model.startLayoutEdit(config, columns)
        model.hideTile(TileId.Weather)

        model.finishLayoutEdit()

        assertFalse(model.layout.value.editing)
        assertFalse(TileId.Weather in model.layout.value.resolve(config, columns))
    }

    @Test
    fun `poza trybem edycji zmiany ukladu sa ignorowane`() = runTest {
        val model = model()
        model.moveTile(TileId.Agenda, 0)
        model.hideTile(TileId.Weather)
        model.cycleTileSize(TileId.Weather)
        model.resizeTile(TileId.Agenda, TileSize(4, 4))

        assertNull(settings.layout(model.profileId()))
        assertNull(model.layout.value.saved)
    }

    @Test
    fun `uklad przetrwa ponowne uruchomienie aplikacji`() = runTest {
        val first = model()
        first.startLayoutEdit(first.config(), columns)
        first.hideTile(TileId.Weather)
        first.cycleTileSize(TileId.Agenda)
        val expected = checkNotNull(first.layout.value.draft)

        val second = model()
        val restored = second.layout.value
        assertFalse(restored.editing)
        assertEquals(expected, restored.saved)
        assertEquals(expected, restored.resolve(second.config(), columns))
    }

    @Test
    fun `uszkodzony zapis daje uklad domyslny`() = runTest {
        val first = model()
        settings.saveLayout(first.profileId(), "v2;+ w;to nie jest uklad")

        val second = model()

        assertNull(second.layout.value.saved)
        assertEquals(DashboardLayout.default(second.config(), columns), second.layout.value.resolve(second.config(), columns))
    }

    @Test
    fun `przywrocenie ukladu domyslnego kasuje zapis a w edycji zostaje w trybie edycji`() = runTest {
        val model = model()
        val config = model.config()
        model.startLayoutEdit(config, columns)
        model.hideTile(TileId.Weather)
        assertNotNull(settings.layout(model.profileId()))

        model.resetLayout(config, columns)

        assertNull(settings.layout(model.profileId()))
        assertTrue(model.layout.value.editing)
        assertEquals(DashboardLayout.default(config, columns), model.layout.value.draft)

        // Kolejna zmiana po przywróceniu znów jest zapisywana.
        model.hideTile(TileId.Agenda)
        assertNotNull(settings.layout(model.profileId()))
    }

    @Test
    fun `przywrocenie ukladu poza edycja zostawia tryb zwyklego widoku`() = runTest {
        val model = model()
        val config = model.config()
        model.startLayoutEdit(config, columns)
        model.hideTile(TileId.Weather)
        model.finishLayoutEdit()

        model.resetLayout(config, columns)

        assertFalse(model.layout.value.editing)
        assertNull(model.layout.value.saved)
        assertNull(settings.layout(model.profileId()))
    }

    @Test
    fun `zakonczenie sesji czysci stan ukladu`() = runTest {
        val model = model()
        model.startLayoutEdit(model.config(), columns)
        assertTrue(model.layout.value.editing)

        model.leaveDemo()
        runCurrent()

        assertEquals(LayoutState(), model.layout.value)
    }

    @Test
    fun `czyszczenie ukladu profilu usuwa tylko jego zapis`() {
        settings.saveLayout("a", "v2;+ w m")
        settings.saveLayout("b", "v2;+ a c")

        settings.clearLayout("a")

        assertNull(settings.layout("a"))
        assertEquals("v2;+ a c", settings.layout("b"))
    }

    @Test
    fun `zapis v2 z widocznym alarmem - alarm ukryty, po swiadomym dodaniu zapis biezacy (v4) i alarm zostaje po restarcie`() = runTest {
        val alarm = TileId.Switch(2, 5) // „Uzbrój alarm” (icon_id 124) w domu demonstracyjnym
        val first = model()
        val v2 = "v2;+ w m;+ a c;+ s:1:0 s;+ s:2:5 s"
        settings.saveLayout(first.profileId(), v2)

        val second = model()
        val config = second.config()
        assertTrue(second.layout.value.migrateAlarms)
        val resolved = second.layout.value.resolve(config, columns)
        assertFalse(alarm in resolved)
        assertTrue(alarm in resolved.hidden.map { it.id })
        assertTrue(TileId.Switch(1, 0) in resolved)
        assertEquals(v2, settings.layout(second.profileId())) // samo wczytanie niczego nie zapisuje

        // Użytkownik świadomie dodaje alarm z powrotem: zapis w bieżącej wersji, flaga migracji znika.
        second.startLayoutEdit(config, columns)
        assertFalse(alarm in checkNotNull(second.layout.value.draft))
        second.showTile(alarm)
        assertTrue(alarm in checkNotNull(second.layout.value.draft))
        assertTrue(checkNotNull(settings.layout(second.profileId())).startsWith("v4;"))
        assertFalse(second.layout.value.migrateAlarms)

        val third = model()
        assertFalse(third.layout.value.migrateAlarms)
        assertTrue(alarm in third.layout.value.resolve(third.config(), columns))
    }

    @Test
    fun `zapis v3 z widocznym alarmem jest wiarygodny - nie jest ukrywany`() = runTest {
        val alarm = TileId.Switch(2, 5)
        val first = model()
        settings.saveLayout(first.profileId(), "v3;+ w m;+ a c;+ s:1:0 s;+ s:2:5 s")

        val second = model()

        assertFalse(second.layout.value.migrateAlarms)
        assertTrue(alarm in second.layout.value.resolve(second.config(), columns))
    }
}
