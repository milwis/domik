package app.domik

import android.app.Application
import android.content.Context
import app.domik.data.AesGcmCipher
import app.domik.data.AppEnvironment
import app.domik.data.AppSettings
import app.domik.deimic.config.FileConfigCache
import app.domik.integrations.calendar.ContentResolverCalendarSource
import app.domik.integrations.weather.FileWeatherCache
import app.domik.integrations.weather.OpenMeteoSource
import app.domik.platform.AndroidKeystoreKeySource
import app.domik.platform.SharedPreferencesKeyValueStore
import java.io.File

/**
 * Proces aplikacji: składa [AppEnvironment] z elementów Androida (SharedPreferences, Keystore, katalog plików).
 * Środowisko powstaje dopiero przy pierwszym użyciu.
 */
class DomikApplication : Application() {
    val environment: AppEnvironment by lazy {
        val settings = AppSettings(
            store = SharedPreferencesKeyValueStore(getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)),
            cipher = AesGcmCipher(AndroidKeystoreKeySource()),
        )
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
        )
    }

    private companion object {
        const val PREFERENCES_NAME = "domik_settings"
    }
}
