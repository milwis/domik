package app.domik.data

import app.domik.demo.DemoNetwork
import app.domik.demo.DemoSimulation
import app.domik.deimic.client.FrameTransportFactory
import app.domik.deimic.config.ConfigCache
import app.domik.integrations.calendar.CalendarSource
import app.domik.integrations.calendar.NoCalendarSource
import app.domik.integrations.weather.NoWeatherCache
import app.domik.integrations.weather.NoWeatherSource
import app.domik.integrations.weather.WeatherCache
import app.domik.integrations.weather.WeatherModel
import app.domik.integrations.weather.WeatherSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Wszystko, czego [app.domik.ui.MainViewModel] potrzebuje z zewnątrz – dzięki temu testy podstawiają wersje
 * w pamięci (pamięć podręczna, czas wirtualny, bez Keystore).
 *
 * @property demoCache pamięć podręczna konfiguracji trybu demo (osobna od prawdziwego sterownika)
 * @property controllerCache pamięć podręczna prawdziwego sterownika (osobne wpisy dla każdego profilu)
 * @property controllerTransport podmiana transportu prawdziwego sterownika (null = TLS po gnieździe); tylko testy
 * @property calendarSource źródło wydarzeń kalendarza (w aplikacji: kalendarz systemowy; domyślnie brak – testy)
 * @property weatherSource źródło pogody (w aplikacji: Open-Meteo po HTTPS; domyślnie brak – testy)
 * @property weatherCache ostatnia pobrana prognoza (w aplikacji: plik w prywatnym katalogu; domyślnie brak)
 * @property weatherSearchLanguage język nazw w wyszukiwarce miejscowości (w aplikacji: z zasobów, zgodny z językiem aplikacji)
 */
class AppEnvironment(
    val settings: AppSettings,
    val demoCache: () -> ConfigCache,
    val controllerCache: () -> ConfigCache,
    val connectionTestRunner: ConnectionTestRunner = ConnectionTestRunner.Default,
    val sessionDispatcher: CoroutineDispatcher = Dispatchers.Default,
    val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    val demoNetwork: DemoNetwork = DemoNetwork(),
    val demoSimulation: DemoSimulation = DemoSimulation(),
    val repositoryConfig: HomeRepositoryConfig = HomeRepositoryConfig(),
    val controllerTransport: FrameTransportFactory? = null,
    val calendarSource: CalendarSource = NoCalendarSource,
    val weatherSource: WeatherSource = NoWeatherSource,
    val weatherCache: WeatherCache = NoWeatherCache,
    val weatherSearchLanguage: () -> String = { WeatherModel.defaultSearchLanguage() },
)
