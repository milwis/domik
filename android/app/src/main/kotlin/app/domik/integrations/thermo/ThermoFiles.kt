package app.domik.integrations.thermo

import app.domik.deimic.protocol.ByteReader
import app.domik.deimic.protocol.InputCalibration
import app.domik.deimic.protocol.toBytes
import app.domik.thermo.CalibrationBaselineState
import app.domik.thermo.CalibrationBaselineStore
import app.domik.thermo.CalibrationReason
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Co zapisuje wpis dziennika decyzji ([ThermoLogEntry]). */
enum class ThermoLogKind {
    /** Nowa kalibracja (różnica Xiaomi − Deimic ponad próg). */
    CALIBRATION,

    /** Automatyczne przywrócenie kalibracji bazowej (funkcja wyłączona; utrata termometru już nie przywraca – od 2026-10-12). */
    RESTORE,

    /** Przywrócenie kalibracji bazowej na życzenie użytkownika. */
    MANUAL_RESTORE,

    /** Ponowny odczyt 0x131 po zapisie: czy sterownik ma to, co wysłano. */
    READBACK,

    /** Pierwszy odczyt wejścia: kalibracja instalatora zapamiętana jako bazowa. */
    BASELINE_CAPTURED,

    /** Kalibracja zmieniona poza Domikiem (Configurator): zapisy strefy wstrzymane. */
    EXTERNAL_CHANGE,

    /** Użytkownik przyjął zmienioną kalibrację jako bazową: zapisy wznowione. */
    EXTERNAL_CHANGE_ACCEPTED,
}

/** Wynik wpisu dziennika. */
enum class ThermoLogResult {
    /** Tryb próbny: nic nie wysłano. */
    DRY_RUN,

    /** Sterownik potwierdził zapis (echo 0x132). */
    CONFIRMED,

    /** Zapis lub odczyt się nie udał. */
    FAILED,

    /** Odczyt po zapisie zgodny z wysłanym rekordem. */
    VERIFIED,

    /** Odczyt po zapisie różny od wysłanego rekordu. */
    MISMATCH,

    /** Wpis informacyjny (zdarzenia kalibracji bazowej). */
    NONE,
}

/**
 * Dodatkowy opis wpisu dziennika (po przeglądzie 2026-10-12; osobny typ, żeby starsze `when` po [ThermoLogResult]
 * dalej się kompilowały).
 */
enum class ThermoLogDetail {
    /** Zapis nie wysłany: odczyt kalibracji (0x131) tuż przed nim się nie udał. */
    NOT_SENT_READ_FAILED,

    /** Zapis nie wysłany: sterownik ma już inny rekord niż ten, z którego zapis wyliczono (np. zmiana w Configuratorze). */
    NOT_SENT_RECORD_CHANGED,

    /** Przywrócenie przy wyjściu strefy z trybu rzeczywistego (tryb próbny, nie-hub, zmiana lub usunięcie przypisania). */
    LEAVING_LIVE,

    /** Przywrócenie przed zmianą albo zapomnieniem sterownika. */
    LEAVING_CONTROLLER,
}

/**
 * Jeden wpis dziennika decyzji kalibracji (pliki profilu, przeżywa restart; bez adresów MAC).
 *
 * @property threshold próg reguły, która zdecydowała (w trybie próbnym jeden z porównywanych progów); null dla zdarzeń
 * @property previous / next rekord przed i po (zapisany lub symulowany); null, gdy nie dotyczy
 */
data class ThermoLogEntry(
    val at: Long,
    val module: Int,
    val outputNr: Int,
    val inputNr: Int,
    val threshold: Float?,
    val kind: ThermoLogKind,
    val reason: CalibrationReason?,
    val dryRun: Boolean,
    val result: ThermoLogResult,
    val xiaomiC: Float? = null,
    val deimicC: Float? = null,
    val differenceC: Float? = null,
    val previous: InputCalibration? = null,
    val next: InputCalibration? = null,
    val clamped: Boolean = false,
    val detail: ThermoLogDetail? = null,
) {
    /**
     * Jeden wiersz: `v1;at;module;output;input;threshold;kind;reason;dry;result;xiaomi;deimic;diff;prev hex;next hex;clamped`
     * i – tylko gdy jest [detail] – `;detail` (starsze wiersze bez tej kolumny czytają się dalej).
     */
    fun encode(): String = (
        listOf(
            VERSION, at, module, outputNr, inputNr, threshold.orDash(), kind.name, reason?.name ?: "-", if (dryRun) 1 else 0,
            result.name, xiaomiC.orDash(), deimicC.orDash(), differenceC.orDash(), hex(previous), hex(next), if (clamped) 1 else 0,
        ) + listOfNotNull(detail?.name)
        ).joinToString(";")

    companion object {
        private const val VERSION = "v1"

        fun decode(line: String): ThermoLogEntry? {
            val p = line.trim().split(';')
            if ((p.size != 16 && p.size != 17) || p[0] != VERSION) return null
            return try {
                ThermoLogEntry(
                    at = p[1].toLong(),
                    module = p[2].toInt(),
                    outputNr = p[3].toInt(),
                    inputNr = p[4].toInt(),
                    threshold = p[5].floatOrNull(),
                    kind = ThermoLogKind.valueOf(p[6]),
                    reason = if (p[7] == "-") null else CalibrationReason.valueOf(p[7]),
                    dryRun = p[8] == "1",
                    result = ThermoLogResult.valueOf(p[9]),
                    xiaomiC = p[10].floatOrNull(),
                    deimicC = p[11].floatOrNull(),
                    differenceC = p[12].floatOrNull(),
                    previous = record(p[13]),
                    next = record(p[14]),
                    clamped = p[15] == "1",
                    detail = p.getOrNull(16)?.let(ThermoLogDetail::valueOf),
                )
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        private fun Float?.orDash(): String = this?.toString() ?: "-"

        private fun String.floatOrNull(): Float? = if (this == "-") null else toFloat()

        /** Rekord jako 36 znaków hex (bajt w bajt jak w 0x131 / 0x132). */
        fun hex(record: InputCalibration?): String =
            record?.encode()?.toByteArray()?.joinToString("") { "%02x".format(it.toInt() and 0xFF) } ?: "-"

        fun record(hex: String): InputCalibration? {
            if (hex == "-") return null
            require(hex.length == InputCalibration.SIZE * 2) { "bad record" }
            val bytes = ByteArray(InputCalibration.SIZE) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
            return InputCalibration.decode(ByteReader(bytes.toBytes()))
        }
    }
}

/**
 * Pliki termometrów jednego sterownika (`filesDir/thermo/<klucz sterownika>/`, klucz = skrót numeru seryjnego –
 * [ThermoSettings.controllerKey]; zmiana adresu IP go nie zmienia, „Zapomnij sterownik” plików nie kasuje):
 *  - `baseline/m<moduł>_i<wejście>.txt` – [CalibrationBaselineState] wejścia ([baselineStore]); nadpisuje go wyłącznie
 *    logika korektora (reconcile / potwierdzony zapis), nigdy nic innego;
 *  - `external_changes.txt` – wejścia wstrzymane po zmianie kalibracji poza Domikiem (do potwierdzenia przez użytkownika);
 *  - `manual_restores.txt` – wejścia z korektą wstrzymaną po ręcznym przywróceniu kalibracji instalatora;
 *  - `decisions.log` – dziennik decyzji ([ThermoLogEntry], dopisywany; starsze niż [retentionMillis] i ponad
 *    [maxEntries] wpisów są usuwane).
 * Każdy plik poza dziennikiem jest zastępowany atomowo (plik tymczasowy + przeniesienie), więc awaria w trakcie
 * zapisu zostawia poprzednią wersję. Brakujący lub uszkodzony plik = brak danych. [directory] null = tylko w pamięci.
 *
 * Bezpieczna wątkowo (wszystko pod jedną blokadą; pliki są małe).
 */
class ThermoFiles(
    private val directory: File?,
    private val retentionMillis: Long = LOG_RETENTION_MILLIS,
    private val maxEntries: Int = MAX_LOG_ENTRIES,
) {
    private val lock = Any()
    private val baselines = HashMap<Pair<Int, Int>, CalibrationBaselineState>()
    private val loadedBaselines = HashSet<Pair<Int, Int>>()
    private var allBaselinesLoaded = false
    private var log: ArrayList<ThermoLogEntry>? = null
    private var appendedSinceCompaction = 0

    /** Kalibracje bazowe wejść modułu [module] (dla `CalibrationCorrector` tego modułu). */
    fun baselineStore(module: Int): CalibrationBaselineStore = object : CalibrationBaselineStore {
        override fun load(inputNr: Int): CalibrationBaselineState? = loadBaseline(module, inputNr)

        override fun save(inputNr: Int, state: CalibrationBaselineState): Boolean = saveBaseline(module, inputNr, state)
    }

    fun loadBaseline(module: Int, inputNr: Int): CalibrationBaselineState? = synchronized(lock) {
        val key = module to inputNr
        if (loadedBaselines.add(key) && directory != null) {
            readText(baselineFile(module, inputNr))?.let(CalibrationBaselineState::deserialize)?.let { baselines[key] = it }
        }
        baselines[key]
    }

    /** Zapisuje stan wejścia; false, gdy pliku nie udało się zapisać (stan w pamięci i tak jest aktualny). */
    private fun saveBaseline(module: Int, inputNr: Int, state: CalibrationBaselineState): Boolean = synchronized(lock) {
        val key = module to inputNr
        loadedBaselines += key
        baselines[key] = state
        directory?.let { writeAtomically(baselineFile(module, inputNr), state.serialize()) } ?: true
    }

    /** Stany wszystkich wejść tego sterownika, jakie są w plikach (klucz: moduł, wejście). */
    fun allBaselines(): Map<Pair<Int, Int>, CalibrationBaselineState> = synchronized(lock) {
        if (!allBaselinesLoaded && directory != null) {
            allBaselinesLoaded = true
            File(directory, BASELINE_DIR).listFiles().orEmpty().forEach { file ->
                val match = BASELINE_NAME.matchEntire(file.name) ?: return@forEach
                val module = match.groupValues[1].toIntOrNull() ?: return@forEach
                val input = match.groupValues[2].toIntOrNull() ?: return@forEach
                loadBaseline(module, input)
            }
        }
        HashMap(baselines)
    }

    // Wejścia wstrzymane (wiersze `moduł;wejście`): po zmianie poza Domikiem i po ręcznym przywróceniu.

    private val paused = InputSet(PAUSED_FILE)
    private val manualRestores = InputSet(MANUAL_FILE)

    /** Wejścia wstrzymane po zmianie kalibracji poza Domikiem. */
    fun pausedInputs(): Set<Pair<Int, Int>> = synchronized(lock) { paused.get() }

    fun setPaused(module: Int, inputNr: Int, paused: Boolean) = synchronized(lock) { this.paused.set(module to inputNr, paused) }

    /** Wejścia z korektą wstrzymaną po ręcznym przywróceniu kalibracji instalatora. */
    fun manuallyRestoredInputs(): Set<Pair<Int, Int>> = synchronized(lock) { manualRestores.get() }

    fun setManuallyRestored(module: Int, inputNr: Int, restored: Boolean) = synchronized(lock) {
        manualRestores.set(module to inputNr, restored)
    }

    /** Zbiór wejść w jednym pliku tekstowym; wołać pod [lock]. */
    private inner class InputSet(private val fileName: String) {
        private var cached: Set<Pair<Int, Int>>? = null

        fun get(): Set<Pair<Int, Int>> {
            cached?.let { return it }
            val read = readText(directory?.let { File(it, fileName) })?.lines().orEmpty().mapNotNull { line ->
                val parts = line.trim().split(';')
                val m = parts.getOrNull(0)?.toIntOrNull()
                val i = parts.getOrNull(1)?.toIntOrNull()
                if (parts.size == 2 && m != null && i != null) m to i else null
            }.toSet()
            cached = read
            return read
        }

        fun set(key: Pair<Int, Int>, member: Boolean) {
            val current = get()
            val updated = if (member) current + key else current - key
            if (updated == current) return
            cached = updated
            directory?.let { writeAtomically(File(it, fileName), updated.joinToString("\n") { (m, i) -> "$m;$i" }) }
        }
    }

    // Dziennik decyzji

    /** Wpisy dziennika (najstarsze pierwsze) nie starsze niż [retentionMillis] względem [now]. */
    fun log(now: Long): List<ThermoLogEntry> = synchronized(lock) {
        val entries = loadedLog()
        prune(entries, now)
        ArrayList(entries)
    }

    /** Dopisuje wpisy (na koniec pliku); co pewien czas plik jest przepisywany bez wpisów przeterminowanych. */
    fun append(entries: List<ThermoLogEntry>) {
        if (entries.isEmpty()) return
        synchronized(lock) {
            val all = loadedLog()
            all += entries
            appendedSinceCompaction += entries.size
            val now = entries.maxOf { it.at }
            val removed = prune(all, now)
            val dir = directory ?: return
            try {
                if (removed || appendedSinceCompaction >= COMPACT_EVERY) {
                    writeAtomically(File(dir, LOG_FILE), all.joinToString("") { it.encode() + "\n" })
                    appendedSinceCompaction = 0
                } else {
                    dir.mkdirs()
                    File(dir, LOG_FILE).appendText(entries.joinToString("") { it.encode() + "\n" }, Charsets.UTF_8)
                }
            } catch (_: IOException) {
                // Dziennik to pomiar, nie stan: brak miejsca nie może zatrzymać kalibracji.
            }
        }
    }

    private fun loadedLog(): ArrayList<ThermoLogEntry> {
        log?.let { return it }
        val read = readText(directory?.let { File(it, LOG_FILE) })?.lineSequence()?.mapNotNull(ThermoLogEntry::decode)?.toCollection(ArrayList())
            ?: ArrayList()
        read.sortBy { it.at }
        log = read
        return read
    }

    private fun prune(entries: ArrayList<ThermoLogEntry>, now: Long): Boolean {
        val before = entries.size
        entries.removeAll { it.at < now - retentionMillis }
        if (entries.size > maxEntries) entries.subList(0, entries.size - maxEntries).clear()
        return entries.size != before
    }

    private fun baselineFile(module: Int, inputNr: Int): File = File(File(directory, BASELINE_DIR), "m${module}_i$inputNr.txt")

    private fun readText(file: File?): String? = try {
        if (file != null && file.isFile) file.readText(Charsets.UTF_8) else null
    } catch (_: IOException) {
        null
    }

    /** Zastępuje plik atomowo; false przy błędzie zapisu (zostaje poprzednia wersja pliku). */
    private fun writeAtomically(file: File, text: String): Boolean {
        return try {
            val dir = file.absoluteFile.parentFile
            dir.mkdirs()
            val temporary = File(dir, file.name + ".tmp")
            temporary.writeText(text, Charsets.UTF_8)
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            true
        } catch (_: IOException) {
            // Zostaje poprzednia wersja pliku; stan w pamięci jest aktualny do końca sesji.
            false
        }
    }

    companion object {
        const val LOG_RETENTION_MILLIS = 90L * 24 * 3_600_000
        const val MAX_LOG_ENTRIES = 5000
        private const val COMPACT_EVERY = 500
        private const val BASELINE_DIR = "baseline"
        private const val PAUSED_FILE = "external_changes.txt"
        private const val MANUAL_FILE = "manual_restores.txt"
        private val BASELINE_NAME = Regex("^m(\\d+)_i(\\d+)\\.txt$")
        private const val LOG_FILE = "decisions.log"
    }
}
