package app.domik.data

import app.domik.deimic.protocol.SerialNumber
import app.domik.integrations.calendar.CalendarSelection
import app.domik.ui.theme.AccentPalette
import app.domik.ui.theme.AppearanceSettings
import app.domik.ui.theme.ThemeMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalTime

class AppSettingsTest {
    private val store = InMemoryKeyValueStore()
    private val cipher = FakeCipher()
    private var counter = 0
    private fun settings() = AppSettings(store, cipher) { "profil-${++counter}" }
    private val serial = SerialNumber(0x0A1B2C3Du)

    @Test
    fun `swieza instalacja nie ma zgody trybu ani sterownika`() {
        val settings = settings()
        assertEquals(0, settings.acceptedTermsVersion)
        assertFalse(settings.termsAccepted)
        assertEquals(AppMode.UNSET, settings.mode)
        assertNull(settings.controller())
        assertEquals(AppearanceSettings(), settings.appearance)
    }

    @Test
    fun `zgoda na regulamin zapisuje wersje i wygasa po jej zmianie`() {
        val settings = settings()
        settings.acceptTerms()
        assertTrue(settings.termsAccepted)
        assertEquals(TERMS_VERSION, settings.acceptedTermsVersion)
        // ta sama zgoda przy nowszej wersji dokumentów już nie obowiązuje
        assertFalse(isTermsAccepted(settings.acceptedTermsVersion, current = TERMS_VERSION + 1))
        // a starsza zgoda zapisana w pamięci nie przechodzi
        settings.acceptTerms(version = TERMS_VERSION - 1)
        assertFalse(settings.termsAccepted)
    }

    @Test
    fun `tryb pracy jest zapamietywany`() {
        val settings = settings()
        settings.mode = AppMode.DEMO
        assertEquals(AppMode.DEMO, AppSettings(store, cipher).mode)
        store.edit { putString("app.mode", "cos-nieznanego") }
        assertEquals(AppMode.UNSET, AppSettings(store, cipher).mode)
    }

    @Test
    fun `wyglad jest zapamietywany razem z godzinami trybu wg godzin`() {
        val settings = settings()
        val appearance = AppearanceSettings(ThemeMode.Scheduled(LocalTime.of(20, 30), LocalTime.of(7, 0)), AccentPalette.Violet)
        settings.appearance = appearance
        assertEquals(appearance, AppSettings(store, cipher).appearance)
    }

    @Test
    fun `sterownik jest zapamietywany a numer seryjny nie lezy jawnie w magazynie`() {
        val settings = settings()
        val profile = settings.saveController("Mój dom", "192.0.2.10", 60110, serial)
        val stored = AppSettings(store, cipher).controller()!!
        assertEquals(profile, stored.profile)
        assertEquals("Mój dom", stored.profile.name)
        assertEquals(serial, stored.serial)
        val everything = store.values.values.joinToString("|")
        assertFalse(serial.hexString in everything)
        assertFalse(serial.hexString.lowercase() in everything.lowercase())
        assertFalse(serial.value.toString() in everything)
    }

    @Test
    fun `zmiana samej nazwy zachowuje identyfikator profilu`() {
        val settings = settings()
        val first = settings.saveController("A", "192.0.2.10", 60110, serial)
        val second = settings.saveController("B", "192.0.2.10", 60110, serial)
        assertEquals(first.id, second.id)
        assertEquals("B", settings.controller()!!.profile.name)
    }

    @Test
    fun `zmiana adresu portu lub numeru daje nowy profil`() {
        val settings = settings()
        val first = settings.saveController("A", "192.0.2.10", 60110, serial)
        assertNotEquals(first.id, settings.saveController("A", "192.0.2.11", 60110, serial).id)
        val second = settings.controller()!!.profile
        assertNotEquals(second.id, settings.saveController("A", "192.0.2.11", 60111, serial).id)
        val third = settings.controller()!!.profile
        assertNotEquals(third.id, settings.saveController("A", "192.0.2.11", 60111, SerialNumber(1u)).id)
    }

    @Test
    fun `numer nie do odszyfrowania daje profil bez numeru`() {
        val settings = settings()
        settings.saveController("A", "192.0.2.10", 60110, serial)
        cipher.failDecrypt = true
        val stored = settings.controller()!!
        assertNull(stored.serial)
        assertEquals("192.0.2.10", stored.profile.host)
    }

    @Test
    fun `szyfrogram przeniesiony pod inny profil nie daje numeru`() {
        val settings = settings()
        settings.saveController("A", "192.0.2.10", 60110, serial)
        store.edit { putString("controller.id", "obcy-profil") }
        assertNull(settings.controller()!!.serial)
    }

    @Test
    fun `blad szyfrowania nie zmienia zapisanych ustawien`() {
        val settings = settings()
        val saved = settings.saveController("A", "192.0.2.10", 60110, serial)
        cipher.failEncrypt = true
        try {
            settings.saveController("B", "192.0.2.99", 60110, SerialNumber(2u))
        } catch (_: SecretStorageException) {
            // oczekiwane
        }
        assertEquals(saved, settings.controller()!!.profile)
        assertEquals(serial, settings.controller()!!.serial)
    }

    @Test
    fun `zapomnienie sterownika usuwa adres numer i uklad`() {
        val settings = settings()
        val profile = settings.saveController("A", "192.0.2.10", 60110, serial)
        settings.saveLayout(profile.id, "w,a")
        assertEquals(profile, settings.clearController())
        assertNull(settings.controller())
        assertNull(settings.layout(profile.id))
        assertTrue(store.values.keys.none { it.startsWith("controller.") })
        assertNull(settings.clearController())
    }

    @Test
    fun `uszkodzone wpisy daja brak sterownika zamiast wyjatku`() {
        val settings = settings()
        settings.saveController("A", "192.0.2.10", 60110, serial)
        store.edit { putInt("controller.port", 0) }
        assertNull(settings.controller())
        store.edit {
            putInt("controller.port", 60110)
            putString("controller.serial_sealed", "to nie jest base64!!")
        }
        assertNull(settings.controller()!!.serial)
    }

    @Test
    fun `wybor kalendarzy domyslnie pokazuje wszystkie i jest zapamietywany jako identyfikatory ukrytych`() {
        val settings = settings()
        assertEquals(CalendarSelection(), settings.calendarSelection)
        assertTrue(settings.calendarSelection.isVisible(42))

        settings.calendarSelection = CalendarSelection(setOf(9L, 4L))
        assertEquals(CalendarSelection(setOf(4L, 9L)), AppSettings(store, cipher).calendarSelection)
        assertEquals("4,9", store.getString("calendar.hidden_ids"))

        // przywrocenie wszystkich kalendarzy zostawia czysty magazyn
        settings.calendarSelection = CalendarSelection()
        assertNull(store.getString("calendar.hidden_ids"))
        assertEquals(CalendarSelection(), AppSettings(store, cipher).calendarSelection)
    }

    @Test
    fun `uszkodzony wybor kalendarzy nie psuje ustawien`() {
        store.edit { putString("calendar.hidden_ids", "x,,7,;") }
        assertEquals(CalendarSelection(setOf(7L)), settings().calendarSelection)
    }

    @Test
    fun `zapomnienie sterownika nie zmienia wyboru kalendarzy`() {
        val settings = settings()
        settings.saveController("A", "192.0.2.10", 60110, serial)
        settings.calendarSelection = CalendarSelection(setOf(3L))
        settings.clearController()
        assertEquals(CalendarSelection(setOf(3L)), settings.calendarSelection)
    }

    @Test
    fun `tablet scienny domyslnie ma ekran zawsze wlaczony i pelny ekran`() {
        val kiosk = settings().kiosk
        assertEquals(KioskSettings(), kiosk)
        assertTrue(kiosk.keepScreenOnWhenPowered)
        assertTrue(kiosk.fullscreen)
    }

    @Test
    fun `ustawienia tabletu sciennego sa zapamietywane`() {
        val settings = settings()
        settings.kiosk = KioskSettings(keepScreenOnWhenPowered = false, fullscreen = true)
        assertEquals(KioskSettings(keepScreenOnWhenPowered = false, fullscreen = true), AppSettings(store, cipher).kiosk)
        settings.kiosk = KioskSettings(keepScreenOnWhenPowered = true, fullscreen = false)
        assertEquals(KioskSettings(keepScreenOnWhenPowered = true, fullscreen = false), AppSettings(store, cipher).kiosk)
        settings.kiosk = KioskSettings(keepScreenOnWhenPowered = false, fullscreen = false)
        assertEquals(KioskSettings(keepScreenOnWhenPowered = false, fullscreen = false), AppSettings(store, cipher).kiosk)
    }

    @Test
    fun `zapomnienie sterownika nie zmienia ustawien tabletu sciennego`() {
        val settings = settings()
        settings.saveController("A", "192.0.2.10", 60110, serial)
        settings.kiosk = KioskSettings(keepScreenOnWhenPowered = false, fullscreen = false)
        settings.clearController()
        assertEquals(KioskSettings(keepScreenOnWhenPowered = false, fullscreen = false), settings.kiosk)
        assertEquals(KioskSettings(keepScreenOnWhenPowered = false, fullscreen = false), AppSettings(store, cipher).kiosk)
    }
}
