package app.domik.integrations.thermo

import app.domik.deimic.protocol.InputCalibration
import app.domik.thermo.CalibrationBaselineState
import app.domik.thermo.CalibrationReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ThermoFilesTest {
    @TempDir
    lateinit var dir: File

    private val installer = InputCalibration.onePoint(20f, 17.6f)
    private val domik = InputCalibration.onePoint(20f, 16.1f)

    private fun entry(at: Long, kind: ThermoLogKind = ThermoLogKind.CALIBRATION) = ThermoLogEntry(
        at = at, module = 1, outputNr = 5, inputNr = 4, threshold = 0.5f, kind = kind, reason = CalibrationReason.DIFFERENCE,
        dryRun = true, result = ThermoLogResult.DRY_RUN, xiaomiC = 20.1f, deimicC = 21.6f, differenceC = -1.5f,
        previous = installer, next = domik, clamped = false,
    )

    @Test
    fun `kalibracja bazowa przezywa ponowne otwarcie`() {
        val state = CalibrationBaselineState(installer, lastWrittenByDomik = domik)
        ThermoFiles(dir).baselineStore(1).save(4, state)

        val reopened = ThermoFiles(dir)
        assertEquals(state, reopened.baselineStore(1).load(4))
        assertNull(reopened.baselineStore(2).load(4)) // inny moduł
        assertNull(reopened.baselineStore(1).load(3))
        assertTrue(dir.walkTopDown().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `uszkodzony plik bazowy to brak kalibracji bazowej`() {
        File(dir, "baseline").mkdirs()
        File(dir, "baseline/m1_i4.txt").writeText("v1;zepsute")
        assertNull(ThermoFiles(dir).baselineStore(1).load(4))
    }

    @Test
    fun `wstrzymane wejscia przezywaja ponowne otwarcie`() {
        val files = ThermoFiles(dir)
        files.setPaused(1, 4, true)
        files.setPaused(2, 0, true)
        files.setPaused(2, 0, false)
        assertEquals(setOf(1 to 4), ThermoFiles(dir).pausedInputs())
    }

    @Test
    fun `dziennik jest dopisywany i odczytywany po ponownym otwarciu`() {
        val files = ThermoFiles(dir)
        files.append(listOf(entry(1_000)))
        files.append(listOf(entry(2_000, ThermoLogKind.READBACK)))

        val log = ThermoFiles(dir).log(3_000)
        assertEquals(listOf(entry(1_000), entry(2_000, ThermoLogKind.READBACK)), log)
    }

    @Test
    fun `dziennik usuwa wpisy starsze niz 90 dni`() {
        val day = 24L * 3_600_000
        val files = ThermoFiles(dir)
        files.append(listOf(entry(0)))
        files.append(listOf(entry(91 * day)))
        assertEquals(listOf(entry(91 * day)), files.log(91 * day))
        assertEquals(listOf(entry(91 * day)), ThermoFiles(dir).log(91 * day))
    }

    @Test
    fun `dziennik trzyma najwyzej zadana liczbe wpisow`() {
        val files = ThermoFiles(dir, maxEntries = 3)
        (1..5).forEach { files.append(listOf(entry(it * 1_000L))) }
        assertEquals(listOf(3_000L, 4_000L, 5_000L), files.log(5_000).map { it.at })
        assertEquals(listOf(3_000L, 4_000L, 5_000L), ThermoFiles(dir, maxEntries = 3).log(5_000).map { it.at })
    }

    @Test
    fun `wpis dziennika ma stala postac tekstowa i nie zawiera adresow MAC`() {
        val line = entry(1_000).encode()
        assertEquals(entry(1_000), ThermoLogEntry.decode(line))
        assertTrue(line.startsWith("v1;1000;1;5;4;0.5;CALIBRATION;DIFFERENCE;1;DRY_RUN;"))
        assertNull(ThermoLogEntry.decode("v1;zepsute"))
    }

    @Test
    fun `bez katalogu wszystko jest w pamieci`() {
        val files = ThermoFiles(null)
        files.baselineStore(1).save(4, CalibrationBaselineState(installer))
        files.append(listOf(entry(1_000)))
        assertEquals(CalibrationBaselineState(installer), files.loadBaseline(1, 4))
        assertEquals(1, files.log(1_000).size)
    }

    @Test
    fun `nieudany zapis pliku bazowego jest zglaszany`() {
        File(dir, "baseline").writeText("to plik, nie katalog") // katalogu nie da się utworzyć
        val files = ThermoFiles(dir)
        val state = CalibrationBaselineState(installer, pendingWrite = domik)
        assertFalse(files.baselineStore(1).save(4, state))
        assertTrue(ThermoFiles(null).baselineStore(1).save(4, state))
    }

    @Test
    fun `wszystkie stany wejsc sa czytane z katalogu`() {
        val a = CalibrationBaselineState(installer)
        val b = CalibrationBaselineState(installer, lastWrittenByDomik = domik, lastWriteAt = 5L)
        ThermoFiles(dir).baselineStore(1).save(4, a)
        ThermoFiles(dir).baselineStore(2).save(0, b)
        assertEquals(mapOf((1 to 4) to a, (2 to 0) to b), ThermoFiles(dir).allBaselines())
    }

    @Test
    fun `wejscia wstrzymane recznie przezywaja ponowne otwarcie`() {
        ThermoFiles(dir).setManuallyRestored(1, 4, true)
        assertEquals(setOf(1 to 4), ThermoFiles(dir).manuallyRestoredInputs())
        assertEquals(emptySet<Pair<Int, Int>>(), ThermoFiles(dir).pausedInputs())
    }

    @Test
    fun `opis wpisu jest opcjonalna kolumna`() {
        val plain = entry(1_000)
        val detailed = plain.copy(result = ThermoLogResult.FAILED, detail = ThermoLogDetail.NOT_SENT_READ_FAILED)
        assertEquals(16, plain.encode().split(';').size)
        assertEquals(detailed, ThermoLogEntry.decode(detailed.encode()))
        assertTrue(detailed.encode().endsWith(";NOT_SENT_READ_FAILED"))
    }
}
