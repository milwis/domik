package app.domik

import android.app.Application
import android.content.Context
import android.os.Build
import app.domik.data.AesGcmCipher
import app.domik.data.AppEnvironment
import app.domik.data.AppSettings
import app.domik.data.diagnosticsHeader
import app.domik.deimic.config.FileConfigCache
import app.domik.integrations.calendar.ContentResolverCalendarSource
import app.domik.integrations.emodul.EmodulCloudApi
import app.domik.integrations.emodul.EmodulFallback
import app.domik.integrations.emodul.EmodulFallbacks
import app.domik.integrations.emodul.EmodulSettings
import app.domik.integrations.emodul.EmodulStore
import app.domik.integrations.thermo.ThermoSettings
import app.domik.integrations.weather.FileWeatherCache
import app.domik.integrations.weather.OpenMeteoSource
import app.domik.platform.AndroidBleThermometerScanner
import app.domik.platform.AndroidKeystoreKeySource
import app.domik.platform.readAppVersion
import app.domik.platform.SharedPreferencesKeyValueStore
import java.io.File

/**
 * Proces aplikacji: składa [AppEnvironment] z elementów Androida (SharedPreferences, Keystore, katalog plików).
 * Środowisko powstaje dopiero przy pierwszym użyciu.
 */
class DomikApplication : Application() {
    val environment: AppEnvironment by lazy {
        val store = SharedPreferencesKeyValueStore(getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE))
        val cipher = AesGcmCipher(AndroidKeystoreKeySource())
        val settings = AppSettings(store = store, cipher = cipher)
        AppEnvironment(
            settings = settings,
            // Kopie konfiguracji w prywatnym katalogu aplikacji (poza kopiami zapasowymi – patrz data_extraction_rules).
            demoCache = { FileConfigCache(File(filesDir, "config/demo")) },
            controllerCache = { FileConfigCache(File(filesDir, "config/controller")) },
            // Kalendarz systemowy (tylko odczyt); w trybie demo nieużywany.
            calendarSource = ContentResolverCalendarSource(this),
            // Pogoda z Open-Meteo (HTTPS, bez klucza); ostatnia prognoza w pliku, żeby po restarcie bez internetu kafelek nie był pusty.
            weatherSource = OpenMeteoSource(),
            weatherCache = FileWeatherCache(File(filesDir, "weather/forecast.txt")),
            // Nazwy miejscowości w języku aplikacji (kod języka z zasobów: values = pl, values-en = en).
            weatherSearchLanguage = { getString(R.string.weather_search_language) },
            // Termometry Xiaomi (reklamy BLE). Skaner nie dotyka Bluetooth, dopóki tryb to OFF (ustawia go dopiero integracja).
            thermometerSource = AndroidBleThermometerScanner(this),
            // Przypisania termometrów (MAC) w ustawieniach; kalibracje bazowe i dziennik decyzji w filesDir/thermo/<profil>.
            thermoSettings = ThermoSettings(store, File(filesDir, "thermo")),
            // eModul (tylko odczyt, GET po HTTPS): klucz API zaszyfrowany kluczem z Keystore, ostatnie odpowiedzi w filesDir/emodul.
            emodulApi = EmodulCloudApi(),
            emodulSettings = EmodulSettings(store, cipher),
            emodulStore = EmodulStore(File(filesDir, "emodul")),
            emodulLanguage = { getString(R.string.emodul_language) },
            emodulFallbacks = EmodulFallbacks { kind, number -> getString(fallbackResource(kind), number) },
            // Nagłówek raportu z testu połączenia (do wklejenia na forum): wersja aplikacji, Android, model urządzenia.
            diagnosticsHeader = {
                diagnosticsHeader(readAppVersion(this), Build.VERSION.RELEASE, Build.VERSION.SDK_INT, Build.MANUFACTURER, Build.MODEL)
            },
        )
    }

    private companion object {
        const val PREFERENCES_NAME = "domik_settings"

        /** Zasób tekstu zastępczego (nazwa kafelka eModul bez nazwy w słowniku); `%d` w zasobach = numer, jeśli jest. */
        fun fallbackResource(kind: EmodulFallback): Int = when (kind) {
            EmodulFallback.FLAME_SENSOR -> R.string.emodul_fallback_flame_sensor
            EmodulFallback.FIRING_UP -> R.string.emodul_fallback_firing_up
            EmodulFallback.STATUS -> R.string.emodul_fallback_status
            EmodulFallback.FAN -> R.string.emodul_fallback_fan
            EmodulFallback.BUILT_IN_VALVE -> R.string.emodul_fallback_built_in_valve
            EmodulFallback.VALVE_N -> R.string.emodul_fallback_valve_n
            EmodulFallback.FUEL -> R.string.emodul_fallback_fuel
            EmodulFallback.DISINFECTION -> R.string.emodul_fallback_disinfection
            EmodulFallback.CLOCK -> R.string.emodul_fallback_clock
            EmodulFallback.CONTROLLER -> R.string.emodul_fallback_controller
            EmodulFallback.PERIPHERAL -> R.string.emodul_fallback_peripheral
            EmodulFallback.GROUP -> R.string.emodul_fallback_group
            EmodulFallback.OUTPUT_N -> R.string.emodul_fallback_output_n
            EmodulFallback.PUMP_N -> R.string.emodul_fallback_pump_n
            EmodulFallback.SENSOR_N -> R.string.emodul_fallback_sensor_n
            EmodulFallback.TILE_N -> R.string.emodul_fallback_tile_n
        }
    }
}
