package app.domik.integrations.thermo

import app.domik.data.KeyValueStore
import app.domik.deimic.client.commands.ZoneKey
import app.domik.deimic.protocol.SerialNumber
import app.domik.thermo.AdvertisementParser
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Przypisanie termometru Xiaomi do strefy ogrzewania ([module], [outputNr]). Wejście analogowe (czujnik Deimic)
 * wynika z pomieszczenia strefy ([app.domik.deimic.config.HomeConfig.temperatureInputsFor]); [inputNr] podaje się
 * ręcznie tylko wtedy, gdy pomieszczenie ma zero albo kilka wejść temperatury.
 *
 * @property mac adres termometru `AA:BB:CC:DD:EE:FF` – tylko w ustawieniach (nigdy w logach ani w dzienniku decyzji)
 * @property label nazwa nadana przez użytkownika (może być pusta)
 */
data class ThermoAssignment(
    val mac: String,
    val module: Int,
    val outputNr: Int,
    val inputNr: Int? = null,
    val label: String = "",
) {
    val zone: ZoneKey get() = ZoneKey(module, outputNr)

    override fun toString(): String =
        "ThermoAssignment(${AdvertisementParser.maskMac(mac)} -> $module/$outputNr, input=$inputNr, label=$label)"

    companion object {
        private val MAC = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")

        /** `aa-bb-…` / `aa:bb:…` → `AA:BB:…`; null, gdy to nie jest adres MAC. */
        fun normalizeMac(text: String): String? = text.trim().uppercase().replace('-', ':').takeIf { MAC.matches(it) }
    }
}

/**
 * Ustawienia termometrów dla jednego profilu sterownika. Domyślnie funkcja jest wyłączona, a po włączeniu działa
 * w trybie próbnym ([dryRun]: decyzje tylko w dzienniku, nic nie jest wysyłane do sterownika).
 *
 * @property threshold próg różnicy Xiaomi − Deimic (°C), po którego przekroczeniu powstaje nowa kalibracja: 0,5 albo 1,0
 * @property assignments najwyżej jedno przypisanie na strefę i jedno na termometr
 */
data class ThermoProfileSettings(
    val enabled: Boolean = false,
    val dryRun: Boolean = true,
    val threshold: Float = DEFAULT_THRESHOLD,
    val assignments: List<ThermoAssignment> = emptyList(),
) {
    init {
        require(threshold in THRESHOLDS) { "threshold must be one of $THRESHOLDS" }
    }

    fun assignmentFor(zone: ZoneKey): ThermoAssignment? = assignments.firstOrNull { it.zone == zone }

    /** Przypisuje termometr strefie; poprzednie przypisanie tej strefy i tego termometru znika. */
    fun withAssignment(assignment: ThermoAssignment): ThermoProfileSettings =
        copy(assignments = assignments.filterNot { it.zone == assignment.zone || it.mac == assignment.mac } + assignment)

    fun withoutAssignment(zone: ZoneKey): ThermoProfileSettings = copy(assignments = assignments.filterNot { it.zone == zone })

    /**
     * Zapis wersjonowany, wiersz na pole:
     * ```
     * v1
     * enabled=0
     * dry_run=1
     * threshold=5                     (dziesiąte części °C)
     * a=AA:BB:CC:DD:EE:FF;1;0;4;<etykieta w Base64 URL>   (moduł; output_nr; input_nr albo -; etykieta)
     * ```
     */
    fun encode(): String = buildString {
        append(VERSION).append('\n')
        append("enabled=").append(if (enabled) 1 else 0).append('\n')
        append("dry_run=").append(if (dryRun) 1 else 0).append('\n')
        append("threshold=").append(Math.round(threshold * 10)).append('\n')
        for (a in assignments) {
            val label = Base64.getUrlEncoder().withoutPadding().encodeToString(a.label.toByteArray(Charsets.UTF_8))
            append("a=").append(a.mac).append(';').append(a.module).append(';').append(a.outputNr).append(';')
                .append(a.inputNr?.toString() ?: "-").append(';').append(label).append('\n')
        }
    }

    override fun toString(): String =
        "ThermoProfileSettings(enabled=$enabled, dryRun=$dryRun, threshold=$threshold, assignments=${assignments.size})"

    companion object {
        const val DEFAULT_THRESHOLD = 0.5f

        /** Progi do wyboru (i porównywane równolegle w trybie próbnym). */
        val THRESHOLDS: List<Float> = listOf(0.5f, 1.0f)

        private const val VERSION = "v1"

        /** Odczyt zapisu z [encode]; null dla nieznanej wersji. Błędne wiersze przypisań są pomijane. */
        fun decode(text: String?): ThermoProfileSettings? {
            val lines = text?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: return null
            if (lines.firstOrNull() != VERSION) return null
            var result = ThermoProfileSettings()
            for (line in lines.drop(1)) {
                val key = line.substringBefore('=', "")
                val value = line.substringAfter('=', "")
                result = when (key) {
                    "enabled" -> result.copy(enabled = value == "1")
                    "dry_run" -> result.copy(dryRun = value != "0")
                    "threshold" -> value.toIntOrNull()?.let { tenths -> THRESHOLDS.firstOrNull { Math.round(it * 10) == tenths } }
                        ?.let { result.copy(threshold = it) } ?: result
                    "a" -> assignment(value)?.let(result::withAssignment) ?: result
                    else -> result
                }
            }
            return result
        }

        private fun assignment(text: String): ThermoAssignment? {
            val parts = text.split(';')
            if (parts.size != 5) return null
            val mac = ThermoAssignment.normalizeMac(parts[0]) ?: return null
            val module = parts[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
            val outputNr = parts[2].toIntOrNull()?.takeIf { it >= 0 } ?: return null
            val inputNr = if (parts[3] == "-") null else parts[3].toIntOrNull()?.takeIf { it in 0..MAX_INPUT } ?: return null
            val label = try {
                String(Base64.getUrlDecoder().decode(parts[4]), Charsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                return null
            }
            return ThermoAssignment(mac, module, outputNr, inputNr, label)
        }

        private const val MAX_INPUT = 11
    }
}

/**
 * Trwałe ustawienia termometrów (na wzór [app.domik.data.AppSettings]):
 *  - **per urządzenie**: [hub] – „To urządzenie kalibruje czujniki”; tylko urządzenie-hub wysyła kalibracje
 *    (w domu może być kilka tabletów z Domikiem, kalibrować ma jeden);
 *  - **per profil sterownika**: [ThermoProfileSettings] (jeden wpis tekstowy: przypisania, tryb, próg);
 *  - **per sterownik** (klucz [controllerKey] = skrót numeru seryjnego, nie identyfikator profilu): pliki
 *    w `<root>/<klucz>/` ([files]: kalibracje bazowe, wstrzymania, dziennik decyzji). Zmiana adresu IP / portu
 *    (nowy profil) i „Zapomnij sterownik” ich nie kasują: kalibracja bazowa musi przetrwać, inaczej rekord Domika
 *    zostałby kiedyś zapamiętany jako kalibracja instalatora.
 *
 * @param root katalog plików termometrów (`filesDir/thermo`); null = pliki tylko w pamięci (testy)
 */
class ThermoSettings(private val store: KeyValueStore, private val root: File?) {
    private val memoryFiles = HashMap<String, ThermoFiles>()

    /** „To urządzenie kalibruje czujniki” – domyślnie wyłączone. */
    var hub: Boolean
        get() = store.getInt(KEY_HUB, 0) != 0
        set(value) = store.edit { putInt(KEY_HUB, if (value) 1 else 0) }

    /**
     * Przy zmianie / zapomnieniu sterownika nie udało się przywrócić kalibracji instalatora (ustawienie urządzenia;
     * pokazywane jako [ThermoUiState.previousControllerNotRestored] do czasu potwierdzenia przez użytkownika).
     */
    var leaveWarning: Boolean
        get() = store.getInt(KEY_LEAVE_WARNING, 0) != 0
        set(value) = store.edit { putInt(KEY_LEAVE_WARNING, if (value) 1 else 0) }

    /**
     * Klucz plików sterownika o numerze [serial]: `c` + 16 znaków hex z SHA-256(sól urządzenia + numer). Numer
     * seryjny nie trafia na dysk jawnie; sól (losowa, w ustawieniach urządzenia) utrudnia odgadnięcie numeru z nazwy
     * katalogu tabelą skrótów. Ten sam sterownik = ten sam klucz niezależnie od adresu i profilu.
     */
    fun controllerKey(serial: SerialNumber): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt())
        digest.update("domik.thermo.controller:".toByteArray(Charsets.UTF_8))
        digest.update(serial.hexString.toByteArray(Charsets.US_ASCII))
        return "c" + digest.digest().take(KEY_BYTES).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun salt(): ByteArray = synchronized(this) {
        store.getString(KEY_SALT)?.let { text ->
            try {
                Base64.getDecoder().decode(text).takeIf { it.size == SALT_BYTES }
            } catch (_: IllegalArgumentException) {
                null
            }
        }?.let { return it }
        val fresh = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        store.edit { putString(KEY_SALT, Base64.getEncoder().encodeToString(fresh)) }
        fresh
    }

    /** Ustawienia profilu; [defaults], gdy jeszcze nic nie zapisano (albo zapis jest nieczytelny). */
    fun profile(profileId: String, defaults: ThermoProfileSettings = ThermoProfileSettings()): ThermoProfileSettings =
        ThermoProfileSettings.decode(store.getString(profileKey(profileId))) ?: defaults

    fun saveProfile(profileId: String, settings: ThermoProfileSettings) {
        store.edit { putString(profileKey(profileId), settings.encode()) }
    }

    /**
     * Pliki sterownika o kluczu [controllerKey] ([ThermoSettings.controllerKey]). [legacyProfileId]: katalog z czasów,
     * gdy pliki były per profil (`<root>/<profileId>/`) – jeśli istnieje, a katalogu sterownika jeszcze nie ma, zostaje
     * przeniesiony (kalibracje bazowe nie giną przy aktualizacji aplikacji).
     */
    fun files(controllerKey: String, legacyProfileId: String? = null): ThermoFiles =
        if (root == null) {
            synchronized(memoryFiles) { memoryFiles.getOrPut(controllerKey) { ThermoFiles(null) } }
        } else {
            val dir = File(root, safeName(controllerKey))
            val legacy = legacyProfileId?.let { File(root, safeName(it)) }
            if (!dir.exists() && legacy != null && legacy != dir && legacy.isDirectory) legacy.renameTo(dir)
            ThermoFiles(dir)
        }

    /**
     * Zapomina ustawienia profilu (przypisania z adresami MAC, tryb, próg). Plików sterownika (kalibracje bazowe,
     * wstrzymania, dziennik) celowo nie kasuje – patrz opis klasy.
     */
    fun clearController(profileId: String) {
        store.edit { remove(profileKey(profileId)) }
    }

    private fun profileKey(profileId: String) = "thermo.profile.$profileId"

    /** Identyfikator profilu to UUID; na wszelki wypadek nic poza bezpiecznymi znakami nie trafia do ścieżki. */
    private fun safeName(profileId: String): String = profileId.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private companion object {
        const val KEY_HUB = "thermo.hub"
        const val KEY_LEAVE_WARNING = "thermo.leave_warning"
        const val KEY_SALT = "thermo.key_salt"
        const val SALT_BYTES = 16
        const val KEY_BYTES = 8
    }
}
