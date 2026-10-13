package app.domik.integrations.thermo

import app.domik.data.InMemoryKeyValueStore
import app.domik.deimic.client.commands.ZoneKey
import app.domik.deimic.protocol.InputCalibration
import app.domik.deimic.protocol.SerialNumber
import app.domik.thermo.CalibrationBaselineState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ThermoSettingsTest {
    private val store = InMemoryKeyValueStore()

    // Adresy wyłącznie testowe (lokalnie administrowane), nie należą do żadnego termometru.
    private val macA = "02:AA:BB:CC:DD:01"
    private val macB = "02:AA:BB:CC:DD:02"

    @Test
    fun `domyslnie hub wylaczony a funkcja wylaczona w trybie probnym z progiem 0,5`() {
        val settings = ThermoSettings(store, null)
        assertFalse(settings.hub)
        val profile = settings.profile("p1")
        assertFalse(profile.enabled)
        assertTrue(profile.dryRun)
        assertEquals(0.5f, profile.threshold)
        assertTrue(profile.assignments.isEmpty())
    }

    @Test
    fun `hub jest ustawieniem urzadzenia a reszta ustawieniem profilu`() {
        val settings = ThermoSettings(store, null)
        settings.hub = true
        settings.saveProfile("p1", ThermoProfileSettings(enabled = true, dryRun = false, threshold = 1.0f))

        assertTrue(ThermoSettings(store, null).hub)
        assertEquals(ThermoProfileSettings(enabled = true, dryRun = false, threshold = 1.0f), settings.profile("p1"))
        assertEquals(ThermoProfileSettings(), settings.profile("p2"))
    }

    @Test
    fun `zapis profilu z przypisaniami przezywa odczyt`() {
        val profile = ThermoProfileSettings(
            enabled = true,
            threshold = 1.0f,
            assignments = listOf(
                ThermoAssignment(macA, 1, 5, null, "Narożnik; przy oknie\n„zimno”"),
                ThermoAssignment(macB, 2, 0, 3, ""),
            ),
        )
        assertEquals(profile, ThermoProfileSettings.decode(profile.encode()))
    }

    @Test
    fun `nieznana wersja albo smieci daja wartosci domyslne`() {
        assertNull(ThermoProfileSettings.decode("v9\nenabled=1"))
        assertNull(ThermoProfileSettings.decode(null))
        store.edit { putString("thermo.profile.p1", "{json?}") }
        assertEquals(ThermoProfileSettings(), ThermoSettings(store, null).profile("p1"))
    }

    @Test
    fun `bledne wiersze przypisan sa pomijane`() {
        val text = "v1\nenabled=1\na=nie-mac;1;0;-;\na=$macA;1;0;99;\na=$macB;1;2;-;\nthreshold=7"
        val decoded = checkNotNull(ThermoProfileSettings.decode(text))
        assertTrue(decoded.enabled)
        assertEquals(0.5f, decoded.threshold) // 0,7 °C nie jest dozwolonym progiem
        assertEquals(listOf(ThermoAssignment(macB, 1, 2, null, "")), decoded.assignments)
    }

    @Test
    fun `strefa i termometr maja najwyzej jedno przypisanie`() {
        val profile = ThermoProfileSettings()
            .withAssignment(ThermoAssignment(macA, 1, 0))
            .withAssignment(ThermoAssignment(macB, 1, 0)) // ta sama strefa – zastępuje
            .withAssignment(ThermoAssignment(macB, 1, 1)) // ten sam termometr – przenosi
        assertEquals(listOf(ThermoAssignment(macB, 1, 1)), profile.assignments)
        assertEquals(emptyList<ThermoAssignment>(), profile.withoutAssignment(ZoneKey(1, 1)).assignments)
    }

    @Test
    fun `adres MAC jest normalizowany`() {
        assertEquals("02:AA:BB:CC:DD:0F", ThermoAssignment.normalizeMac(" 02-aa-bb-cc-dd-0f "))
        assertNull(ThermoAssignment.normalizeMac("02:AA:BB:CC:DD"))
        assertFalse(ThermoAssignment(macA, 1, 0).toString().contains("AA:BB"))
    }

    @Test
    fun `zapomnienie sterownika kasuje ustawienia profilu ale nie pliki kalibracji`(@TempDir root: File) {
        val settings = ThermoSettings(store, root)
        settings.saveProfile("p1", ThermoProfileSettings(enabled = true))
        settings.saveProfile("p2", ThermoProfileSettings(enabled = true))
        val key = settings.controllerKey(SerialNumber(0x0A1B2C3Du))
        val record = InputCalibration.onePoint(20f, 17.6f)
        val domik = InputCalibration.onePoint(20f, 16.1f)
        settings.files(key).baselineStore(1).save(4, CalibrationBaselineState(record, lastWrittenByDomik = domik))

        settings.clearController("p1")

        assertEquals(ThermoProfileSettings(), settings.profile("p1"))
        assertTrue(settings.profile("p2").enabled)
        // Kalibracja bazowa zostaje: po ponownym dodaniu sterownika rekord Domika nie zostanie wzięty za instalatorski.
        assertEquals(CalibrationBaselineState(record, lastWrittenByDomik = domik), settings.files(key).loadBaseline(1, 4))
    }

    @Test
    fun `klucz plikow zalezy od numeru seryjnego a nie od profilu i go nie ujawnia`(@TempDir root: File) {
        val settings = ThermoSettings(store, root)
        val serial = SerialNumber(0x0A1B2C3Du)
        val key = settings.controllerKey(serial)
        assertEquals(key, settings.controllerKey(SerialNumber(0x0A1B2C3Du)))
        assertEquals(key, ThermoSettings(store, root).controllerKey(serial)) // ta sama sól urządzenia
        assertNotEquals(key, settings.controllerKey(SerialNumber(0x0A1B2C3Eu)))
        assertTrue(Regex("^c[0-9a-f]{16}$").matches(key), key)
        assertFalse(key.contains(serial.hexString, ignoreCase = true))
        assertNotEquals(key, ThermoSettings(InMemoryKeyValueStore(), root).controllerKey(serial)) // inna instalacja – inna sól

        settings.files(key).baselineStore(1).save(4, CalibrationBaselineState(InputCalibration.onePoint(20f, 17.6f)))
        val names = root.walkTopDown().map { it.name }.toList()
        assertTrue(names.none { it.contains(serial.hexString, ignoreCase = true) }, "$names")
    }

    @Test
    fun `stary katalog profilu jest przenoszony do katalogu sterownika`(@TempDir root: File) {
        val settings = ThermoSettings(store, root)
        val record = InputCalibration.onePoint(20f, 17.6f)
        settings.files("stary-profil").baselineStore(1).save(4, CalibrationBaselineState(record)) // dawny układ: katalog = profil
        val key = settings.controllerKey(SerialNumber(0x0A1B2C3Du))

        val files = settings.files(key, legacyProfileId = "stary-profil")

        assertEquals(CalibrationBaselineState(record), files.loadBaseline(1, 4))
        assertFalse(File(root, "stary-profil").exists())
    }

    @Test
    fun `ostrzezenie o nieprzywroconej kalibracji jest trwale`() {
        val settings = ThermoSettings(store, null)
        assertFalse(settings.leaveWarning)
        settings.leaveWarning = true
        assertTrue(ThermoSettings(store, null).leaveWarning)
    }
}
