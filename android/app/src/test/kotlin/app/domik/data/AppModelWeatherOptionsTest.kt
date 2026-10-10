@file:OptIn(ExperimentalCoroutinesApi::class)

package app.domik.data

import app.domik.deimic.config.InMemoryConfigCache
import app.domik.demo.DemoNetwork
import app.domik.demo.DemoSimulation
import app.domik.deimic.protocol.SerialNumber
import app.domik.integrations.weather.WeatherOption
import app.domik.integrations.weather.WeatherOptions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Wybór elementów kafelka pogody w [AppModel]: zapis per profil, przywracanie po restarcie, niezależność od układu. */
class AppModelWeatherOptionsTest {
    private val store = InMemoryKeyValueStore()
    private val settings = AppSettings(store, FakeCipher()) { "profil-test" }

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

    private fun AppModel.profileId(): String = checkNotNull(session.value).profileId

    @Test
    fun `bez wyboru obowiazuja ustawienia domyslne i nic nie jest zapisane`() = runTest {
        val model = model()
        assertEquals(WeatherOptions.DEFAULT, model.weatherOptions.value)
        assertNull(settings.weatherOptions(model.profileId()))
    }

    @Test
    fun `zmiana jest od razu widoczna i zapisana dla profilu sesji`() = runTest {
        val model = model()
        val options = WeatherOptions.DEFAULT.with(WeatherOption.Wind, true).with(WeatherOption.Daily, false)

        model.setWeatherOptions(options)

        assertEquals(options, model.weatherOptions.value)
        assertEquals("v1;hourly;precip_chance;wind", settings.weatherOptions(model.profileId()))
        assertEquals("v1;hourly;precip_chance;wind", store.getString("weather.options.${model.profileId()}"))
    }

    @Test
    fun `powrot do ustawien domyslnych kasuje wpis`() = runTest {
        val model = model()
        model.setWeatherOptions(WeatherOptions.DEFAULT.with(WeatherOption.Humidity, true))

        model.setWeatherOptions(WeatherOptions.DEFAULT)

        assertEquals(WeatherOptions.DEFAULT, model.weatherOptions.value)
        assertNull(settings.weatherOptions(model.profileId()))
    }

    @Test
    fun `wszystko wylaczone jest zapamietane i nie wraca do domyslnych`() = runTest {
        val first = model()
        first.setWeatherOptions(WeatherOptions(emptySet()))

        val second = model()

        assertEquals(WeatherOptions(emptySet()), second.weatherOptions.value)
    }

    @Test
    fun `wybor przetrwa ponowne uruchomienie aplikacji`() = runTest {
        val first = model()
        val options = WeatherOptions.DEFAULT.with(WeatherOption.Sun, true).with(WeatherOption.UvIndex, true)
        first.setWeatherOptions(options)

        assertEquals(options, model().weatherOptions.value)
    }

    @Test
    fun `uszkodzony zapis to ustawienia domyslne`() = runTest {
        val first = model()
        settings.saveWeatherOptions(first.profileId(), "smieci")

        assertEquals(WeatherOptions.DEFAULT, model().weatherOptions.value)
    }

    @Test
    fun `przywrocenie ukladu domyslnego nie kasuje wyboru pogody`() = runTest {
        val model = model()
        val options = WeatherOptions.DEFAULT.with(WeatherOption.Wind, true)
        model.setWeatherOptions(options)
        val config = checkNotNull(model.session.value?.repository?.state?.value?.config)
        model.startLayoutEdit(config, 8)

        model.resetLayout(config, 8)

        assertEquals(options, model.weatherOptions.value)
        assertEquals(options.encode(), settings.weatherOptions(model.profileId()))
    }

    @Test
    fun `zapomnienie sterownika usuwa jego wybor pogody`() {
        val profile = settings.saveController("Dom", "192.0.2.10", 60110, SerialNumber(0x0A1B2C3Du))
        settings.saveWeatherOptions(profile.id, WeatherOptions.DEFAULT.with(WeatherOption.Wind, true).encode())
        settings.saveWeatherOptions("inny-profil", "v1;wind")

        settings.clearController()

        assertNull(settings.weatherOptions(profile.id))
        assertEquals("v1;wind", settings.weatherOptions("inny-profil"))
    }
}
