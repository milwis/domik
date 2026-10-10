package app.domik.data

import app.domik.deimic.client.ControllerProfile
import app.domik.deimic.protocol.ProtocolConstants
import app.domik.deimic.protocol.SerialNumber
import app.domik.integrations.calendar.CalendarSelection
import app.domik.integrations.weather.WeatherPlace
import app.domik.ui.theme.AccentPalette
import app.domik.ui.theme.AppearanceSettings
import app.domik.ui.theme.ThemeMode
import java.util.Base64
import java.util.UUID

/** Z czym aplikacja pracuje: z niczym (pierwsze uruchomienie), z trybem demo albo ze sterownikiem użytkownika. */
enum class AppMode { UNSET, DEMO, CONTROLLER }

/**
 * Zapisany sterownik. [serial] jest null, gdy nie da się go odszyfrować (np. klucz z Keystore przepadł po
 * przywróceniu telefonu) – ekran ustawień prosi wtedy o ponowne wpisanie numeru.
 */
data class ControllerSettings(val profile: ControllerProfile, val serial: SerialNumber?)

/**
 * Ustawienia aplikacji. Numer seryjny leży na dysku tylko zaszyfrowany ([SecretCipher]), nigdzie indziej;
 * reszta (zgoda na regulamin, tryb, wygląd, adres sterownika) jako zwykłe wpisy w [KeyValueStore].
 * Aplikacja ma jeden profil sterownika, ale model ([ControllerProfile]) ma identyfikator UUID – na przyszłość.
 */
class AppSettings(
    private val store: KeyValueStore,
    private val cipher: SecretCipher,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    // Regulamin i polityka prywatności

    /** Wersja dokumentów zaakceptowana przez użytkownika; 0 = jeszcze nie. */
    val acceptedTermsVersion: Int get() = store.getInt(KEY_TERMS_VERSION, 0)

    /** Czy użytkownik zaakceptował dokładnie obowiązującą wersję ([TERMS_VERSION]). */
    val termsAccepted: Boolean get() = isTermsAccepted(acceptedTermsVersion)

    fun acceptTerms(version: Int = TERMS_VERSION) {
        store.edit { putInt(KEY_TERMS_VERSION, version) }
    }

    // Tryb pracy

    var mode: AppMode
        get() = store.getString(KEY_MODE)?.let { code -> AppMode.entries.firstOrNull { it.name == code } } ?: AppMode.UNSET
        set(value) = store.edit { putString(KEY_MODE, value.name) }

    // Wygląd

    var appearance: AppearanceSettings
        get() = AppearanceSettings(
            mode = ThemeMode.decode(store.getString(KEY_THEME_MODE)),
            palette = AccentPalette.fromName(store.getString(KEY_PALETTE)),
        )
        set(value) = store.edit {
            putString(KEY_THEME_MODE, value.mode.encode())
            putString(KEY_PALETTE, value.palette.name)
        }

    // Tablet ścienny (ekran zawsze włączony przy zasilaniu, pełny ekran) – domyślnie oba włączone

    var kiosk: KioskSettings
        get() = KioskSettings(
            keepScreenOnWhenPowered = store.getInt(KEY_KIOSK_KEEP_ON, 1) != 0,
            fullscreen = store.getInt(KEY_KIOSK_FULLSCREEN, 1) != 0,
        )
        set(value) = store.edit {
            putInt(KEY_KIOSK_KEEP_ON, if (value.keepScreenOnWhenPowered) 1 else 0)
            putInt(KEY_KIOSK_FULLSCREEN, if (value.fullscreen) 1 else 0)
        }

    // Kalendarz

    /**
     * Które kalendarze z urządzenia pokazuje kafelek kalendarza (zapisane są tylko identyfikatory **ukrytych**,
     * więc domyślnie widać wszystkie, także dodane później). Żadne dane wydarzeń nie są zapisywane.
     */
    var calendarSelection: CalendarSelection
        get() = CalendarSelection.decode(store.getString(KEY_CALENDARS_HIDDEN))
        set(value) = store.edit {
            if (value.hiddenIds.isEmpty()) remove(KEY_CALENDARS_HIDDEN) else putString(KEY_CALENDARS_HIDDEN, value.encode())
        }

    // Pogoda

    /**
     * Miejscowość, dla której kafelek pokazuje pogodę (null = jeszcze nie wybrano; nie ma miasta domyślnego).
     * Zapisujemy tylko nazwę, opis i współrzędne zaokrąglone do 2 miejsc – to jedyne dane, które trafiają do
     * dostawcy pogody.
     */
    var weatherPlace: WeatherPlace?
        get() = WeatherPlace.decode(store.getString(KEY_WEATHER_PLACE))
        set(value) = store.edit {
            if (value == null) remove(KEY_WEATHER_PLACE) else putString(KEY_WEATHER_PLACE, value.rounded().encode())
        }

    // Sterownik

    /** Zapisany sterownik albo null, gdy użytkownik jeszcze go nie skonfigurował. */
    fun controller(): ControllerSettings? {
        val id = store.getString(KEY_PROFILE_ID) ?: return null
        val host = store.getString(KEY_HOST)?.takeIf { it.isNotBlank() } ?: return null
        val port = store.getInt(KEY_PORT, ProtocolConstants.DEFAULT_PORT).takeIf { it in 1..65535 } ?: return null
        val profile = ControllerProfile(id, store.getString(KEY_NAME).orEmpty(), host, port)
        return ControllerSettings(profile, readSerial(id))
    }

    /**
     * Zapisuje sterownik. Gdy zmienił się adres, port lub numer seryjny, powstaje **nowy** profil (nowy
     * identyfikator, więc nowa kopia konfiguracji); sama zmiana nazwy zachowuje identyfikator.
     * @return zapisany profil
     * @throws SecretStorageException nie udało się zaszyfrować numeru seryjnego (nic nie zostało zmienione).
     */
    fun saveController(name: String, host: String, port: Int, serial: SerialNumber): ControllerProfile {
        val existing = controller()
        val unchanged = existing != null && existing.profile.host == host && existing.profile.port == port &&
            existing.serial == serial
        val id = if (unchanged) existing.profile.id else newId()
        val sealed = Base64.getEncoder().encodeToString(cipher.encrypt(serial.hexString.toByteArray(Charsets.US_ASCII), aad(id)))
        store.edit {
            putString(KEY_PROFILE_ID, id)
            putString(KEY_NAME, name)
            putString(KEY_HOST, host)
            putInt(KEY_PORT, port)
            putString(KEY_SERIAL, sealed)
        }
        return ControllerProfile(id, name, host, port)
    }

    /** Zapomina sterownik (adres, numer seryjny, układ pulpitu, wybrane ikony, opcje pogody). Zwraca usunięty profil (do skasowania jego kopii). */
    fun clearController(): ControllerProfile? {
        val removed = controller()?.profile
        store.edit {
            listOf(KEY_PROFILE_ID, KEY_NAME, KEY_HOST, KEY_PORT, KEY_SERIAL).forEach(::remove)
            if (removed != null) {
                remove(layoutKey(removed.id))
                remove(iconsKey(removed.id))
                remove(weatherOptionsKey(removed.id))
            }
        }
        return removed
    }

    // Układ pulpitu (kafelki, rozmiary, kolejność; zapis wersjonowany – patrz [DashboardLayout.encode]), osobny dla
    // każdego profilu

    fun layout(profileId: String): String? = store.getString(layoutKey(profileId))

    fun saveLayout(profileId: String, encoded: String) {
        store.edit { putString(layoutKey(profileId), encoded) }
    }

    /** Usuwa zapisany układ – pulpit wraca do układu domyślnego. */
    fun clearLayout(profileId: String) {
        store.edit { remove(layoutKey(profileId)) }
    }

    // Ikony wybrane ręcznie dla kafelków (zapis wersjonowany – patrz [IconChoices.encode]), osobne dla każdego profilu.
    // Nie są częścią układu: „Przywróć układ domyślny” ich nie kasuje (to wybór użytkownika, nie rozmieszczenie).

    fun icons(profileId: String): String? = store.getString(iconsKey(profileId))

    fun saveIcons(profileId: String, encoded: String) {
        store.edit { putString(iconsKey(profileId), encoded) }
    }

    fun clearIcons(profileId: String) {
        store.edit { remove(iconsKey(profileId)) }
    }

    // Co pokazuje kafelek pogody (zapis wersjonowany – patrz [WeatherOptions.encode]), osobne dla każdego profilu.
    // Brak wpisu = ustawienia domyślne; „Przywróć układ domyślny” tego wyboru nie kasuje (to preferencja, nie rozmieszczenie).

    fun weatherOptions(profileId: String): String? = store.getString(weatherOptionsKey(profileId))

    fun saveWeatherOptions(profileId: String, encoded: String) {
        store.edit { putString(weatherOptionsKey(profileId), encoded) }
    }

    fun clearWeatherOptions(profileId: String) {
        store.edit { remove(weatherOptionsKey(profileId)) }
    }

    private fun readSerial(profileId: String): SerialNumber? {
        val sealed = store.getString(KEY_SERIAL) ?: return null
        val bytes = try {
            Base64.getDecoder().decode(sealed)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val plain = cipher.decrypt(bytes, aad(profileId)) ?: return null
        return SerialNumber.parseOrNull(String(plain, Charsets.US_ASCII))
    }

    private fun aad(profileId: String): ByteArray = "domik.serial:$profileId".toByteArray(Charsets.UTF_8)

    private fun layoutKey(profileId: String) = "layout.$profileId"

    private fun iconsKey(profileId: String) = "icons.$profileId"

    private fun weatherOptionsKey(profileId: String) = "weather.options.$profileId"

    private companion object {
        const val KEY_TERMS_VERSION = "terms.accepted_version"
        const val KEY_MODE = "app.mode"
        const val KEY_THEME_MODE = "appearance.mode"
        const val KEY_PALETTE = "appearance.palette"
        const val KEY_KIOSK_KEEP_ON = "kiosk.keep_screen_on"
        const val KEY_KIOSK_FULLSCREEN = "kiosk.fullscreen"
        const val KEY_CALENDARS_HIDDEN = "calendar.hidden_ids"
        const val KEY_WEATHER_PLACE = "weather.place"
        const val KEY_PROFILE_ID = "controller.id"
        const val KEY_NAME = "controller.name"
        const val KEY_HOST = "controller.host"
        const val KEY_PORT = "controller.port"
        const val KEY_SERIAL = "controller.serial_sealed"
    }
}
