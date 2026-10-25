package app.domik.data

import app.domik.demo.DemoNetwork
import app.domik.demo.DemoSimulation
import app.domik.deimic.client.FrameTransportFactory
import app.domik.deimic.config.ConfigCache
import app.domik.integrations.calendar.CalendarSource
import app.domik.integrations.calendar.NoCalendarSource
import app.domik.integrations.emodul.EmodulApi
import app.domik.integrations.emodul.EmodulFallbacks
import app.domik.integrations.emodul.EmodulModel
import app.domik.integrations.emodul.EmodulSettings
import app.domik.integrations.emodul.EmodulStore
import app.domik.integrations.emodul.NoEmodulApi
import app.domik.integrations.thermo.ThermoSettings
import app.domik.integrations.thermo.ThermometerSource
import app.domik.integrations.weather.NoWeatherCache
import app.domik.integrations.weather.NoWeatherSource
import app.domik.integrations.weather.WeatherCache
import app.domik.integrations.weather.WeatherModel
import app.domik.integrations.weather.WeatherSource
import kotlinx.coroutines.CoroutineDispatcher
import java.io.File
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
 * @property thermometerSource odbiornik termometrów BLE (w aplikacji: skaner BLE; domyślnie brak – testy i tryb demo
 *   podstawiają własne źródło)
 * @property thermoSettings ustawienia i pliki termometrów (w aplikacji: te same SharedPreferences i `filesDir/thermo`;
 *   domyślnie w pamięci – testy)
 * @property emodulApi dostęp do API eModul (w aplikacji: HTTPS tylko GET; domyślnie brak – testy; tryb demo ma własne)
 * @property emodulSettings klucz API eModul (szyfrowany) i rejestr modułów (w aplikacji: te same SharedPreferences i
 *   Keystore; domyślnie w pamięci – testy)
 * @property emodulStore pliki integracji eModul (w aplikacji: `filesDir/emodul`; domyślnie katalog tymczasowy, którego
 *   bez klucza nikt nie dotyka – testy)
 * @property emodulLanguage język słownika tekstów eModul (`pl`/`en`, w aplikacji z zasobów)
 * @property emodulFallbacks teksty zastępcze nazw kafelków eModul (w aplikacji: zasoby)
 * @property wallClock czas ścienny w ms (termometry, dziennik kalibracji); testy podstawiają czas wirtualny
 * @property thermoWritesEnabled zapisy kalibracji poza demo dozwolone ([Features.SENSOR_CALIBRATION_WRITES]; inna
 *   wartość wyłącznie w testach)
 * @property diagnosticsHeader wiersze nagłówka raportu z testu połączenia (wersja aplikacji, Android, model urządzenia;
 *   w aplikacji z `Build`, domyślnie puste – testy)
 * @property leaveRestoreTimeoutMillis ile najdłużej czekać na przywrócenie kalibracji instalatora przed zmianą /
 *   zapomnieniem sterownika
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
    val thermometerSource: ThermometerSource? = null,
    val thermoSettings: ThermoSettings = ThermoSettings(InMemoryKeyValueStore(), null),
    val emodulApi: EmodulApi = NoEmodulApi,
    val emodulSettings: EmodulSettings = EmodulSettings.inMemory(),
    val emodulStore: EmodulStore = EmodulStore(File(System.getProperty("java.io.tmpdir"), "domik-emodul-unused")),
    val emodulLanguage: () -> String = { "pl" },
    val emodulFallbacks: EmodulFallbacks = EmodulModel.PlainFallbacks,
    val wallClock: () -> Long = System::currentTimeMillis,
    val thermoWritesEnabled: Boolean = Features.SENSOR_CALIBRATION_WRITES,
    val leaveRestoreTimeoutMillis: Long = 30_000L,
    val diagnosticsHeader: () -> List<String> = { emptyList() },
)
