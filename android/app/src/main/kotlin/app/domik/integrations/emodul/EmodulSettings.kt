package app.domik.integrations.emodul

import app.domik.data.AesGcmCipher
import app.domik.data.InMemoryKeyValueStore
import app.domik.data.KeyValueStore
import app.domik.data.SecretCipher
import app.domik.data.SecretKeySource
import app.domik.data.SecretStorageException
import app.domik.data.TileId
import java.util.Base64
import javax.crypto.KeyGenerator

/** Moduł zapamiętany w ustawieniach: stały numer [slot] (≥ 1), [udid] z konta użytkownika i flaga „Pokaż na pulpicie”. */
data class EmodulSlot(val slot: Int, val udid: String, val enabled: Boolean)

/**
 * Wybór danych eModul pokazywanych na pulpicie (jeden slot): [selected] – wybrane kafelki i strefy (źródło prawdy dla
 * kafelków na pulpicie), [known] – wszystkie, które sterownik miał w chwili ostatniego zapisu wyboru (kafelek spoza
 * [known] jest „nowy”: pojawił się później i jeszcze nie był proponowany). Puste [known] = wybór jeszcze nie zapisany.
 */
data class EmodulSelection(val selected: Set<TileId.EmodulBase>, val known: Set<TileId.EmodulBase>) {
    companion object {
        val EMPTY = EmodulSelection(emptySet(), emptySet())
    }
}

/**
 * Ustawienia integracji eModul: klucz API (szyfrowany [SecretCipher], jak numer seryjny Deimic – ale z własnym
 * AAD), wersja zaakceptowanego komunikatu o prywatności i rejestr modułów. Klucz nigdy nie leży na dysku jawnie,
 * nie trafia do logów ani do `toString()`.
 *
 * Rejestr (`emodul.modules`): `1|<udid>|1;2|<udid>|0` (slot | udid | włączony). Slot dostaje moduł przy pierwszym
 * zobaczeniu (najmniejszy wolny ≥ 1) i nigdy go nie traci; moduł, który zniknął z konta, zostaje w rejestrze do
 * [removeAll] (jego kafelki znikają z pulpitu przez uzgadnianie układu). `udid` to dane użytkownika, nie kodu.
 *
 * Wybór danych na pulpicie ([selection]): `emodul.slot.<n>.selected` i `emodul.slot.<n>.known` – kody kafelków
 * (`e:1:1006,ez:1:101`); brak wpisu = nic nie wybrane.
 */
class EmodulSettings(
    private val store: KeyValueStore,
    private val cipher: SecretCipher,
) {
    /**
     * Zapisany klucz albo `null` (brak, uszkodzony, nie da się odszyfrować – np. klucz z Keystore przepadł).
     * Data ważności (`exp`) nie jest tu sprawdzana – o odrzuceniu klucza decyduje serwer.
     */
    fun key(): EmodulKey? {
        val sealed = store.getString(KEY_KEY) ?: return null
        val bytes = try {
            Base64.getDecoder().decode(sealed)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val plain = cipher.decrypt(bytes, KEY_AAD) ?: return null
        return (EmodulKey.parse(String(plain, Charsets.UTF_8), nowMillis = 0L) as? EmodulKeyResult.Valid)?.key
    }

    /** @throws SecretStorageException nie udało się zaszyfrować (nic nie zostało zmienione). */
    fun saveKey(key: EmodulKey) {
        val sealed = Base64.getEncoder().encodeToString(cipher.encrypt(key.token.toByteArray(Charsets.UTF_8), KEY_AAD))
        store.edit { putString(KEY_KEY, sealed) }
    }

    /** Wersja komunikatu o prywatności zaakceptowana przez użytkownika; 0 = jeszcze nie. */
    val acceptedNoticeVersion: Int get() = store.getInt(KEY_NOTICE, 0)

    val noticeAccepted: Boolean get() = acceptedNoticeVersion >= NOTICE_VERSION

    fun acceptNotice(version: Int = NOTICE_VERSION) {
        store.edit { putInt(KEY_NOTICE, version) }
    }

    /** Rejestr modułów w kolejności slotów; uszkodzone wpisy są pomijane. */
    fun slots(): List<EmodulSlot> = decodeRegistry(store.getString(KEY_MODULES))

    /** Dopisuje moduły widziane po raz pierwszy ([udids]) i zwraca cały rejestr. */
    fun registerModules(udids: Collection<String>): List<EmodulSlot> {
        val current = slots()
        val updated = register(current, udids)
        if (updated != current) store.edit { putString(KEY_MODULES, encodeRegistry(updated)) }
        return updated
    }

    /** Włącza / wyłącza „Pokaż na pulpicie”. Zwraca `false`, gdy slotu nie ma. */
    fun setEnabled(slot: Int, enabled: Boolean): Boolean {
        val current = slots()
        if (current.none { it.slot == slot }) return false
        val updated = current.map { if (it.slot == slot) it.copy(enabled = enabled) else it }
        if (updated != current) store.edit { putString(KEY_MODULES, encodeRegistry(updated)) }
        return true
    }

    /** Wybór danych slotu ([slot]); brak zapisu – [EmodulSelection.EMPTY]. Kody z innego slotu i uszkodzone są pomijane. */
    fun selection(slot: Int): EmodulSelection {
        val selected = decodeTiles(store.getString(selectedKey(slot)), slot)
        val known = decodeTiles(store.getString(knownKey(slot)), slot)
        return EmodulSelection(selected, known + selected)
    }

    /** Zapisuje wybór slotu ([slot]) – nie sprawdza, czy slot jest w rejestrze. */
    fun setSelection(slot: Int, selection: EmodulSelection) {
        store.edit {
            putString(selectedKey(slot), encodeTiles(selection.selected, slot))
            putString(knownKey(slot), encodeTiles(selection.known + selection.selected, slot))
        }
    }

    /**
     * Usuwa klucz, rejestr modułów i wybór danych. Zaakceptowany komunikat zostaje (użytkownik zgodził się na rodzaj połączenia,
     * nie na konkretny klucz). Pliki [EmodulStore] czyści wywołujący.
     */
    fun removeAll() {
        val slots = slots().map { it.slot }
        store.edit {
            remove(KEY_KEY)
            remove(KEY_MODULES)
            for (slot in slots) {
                remove(selectedKey(slot))
                remove(knownKey(slot))
            }
        }
    }

    companion object {
        /** Wersja komunikatu przed pierwszym połączeniem (§6.3); podniesienie wymusza ponowną zgodę. */
        const val NOTICE_VERSION = 1

        private const val KEY_KEY = "emodul.key"
        private const val KEY_NOTICE = "emodul.notice"
        private const val KEY_MODULES = "emodul.modules"
        private fun selectedKey(slot: Int) = "emodul.slot.$slot.selected"

        private fun knownKey(slot: Int) = "emodul.slot.$slot.known"

        internal fun encodeTiles(tiles: Set<TileId.EmodulBase>, slot: Int): String =
            tiles.filter { it.slot == slot }.sortedBy { it.code }.joinToString(",") { it.code }

        internal fun decodeTiles(text: String?, slot: Int): Set<TileId.EmodulBase> =
            text.orEmpty().split(',').mapNotNullTo(LinkedHashSet()) {
                (TileId.decode(it.trim()) as? TileId.EmodulBase)?.takeIf { id -> id.slot == slot }
            }

        private val KEY_AAD = "domik.emodul.key:v1".toByteArray(Charsets.UTF_8)

        /** Dopisuje do [existing] moduły z [udids] jeszcze bez slotu (każdy dostaje najmniejszy wolny ≥ 1, włączony). */
        fun register(existing: List<EmodulSlot>, udids: Collection<String>): List<EmodulSlot> {
            val result = existing.toMutableList()
            for (udid in udids) {
                if (!EmodulUrls.isValidUdid(udid) || result.any { it.udid == udid }) continue
                val used = result.mapTo(HashSet()) { it.slot }
                val slot = generateSequence(1) { it + 1 }.first { it !in used }
                result += EmodulSlot(slot, udid, enabled = true)
            }
            return result.sortedBy { it.slot }
        }

        internal fun encodeRegistry(slots: List<EmodulSlot>): String =
            slots.sortedBy { it.slot }.joinToString(";") { "${it.slot}|${it.udid}|${if (it.enabled) 1 else 0}" }

        internal fun decodeRegistry(text: String?): List<EmodulSlot> {
            if (text.isNullOrBlank()) return emptyList()
            val result = ArrayList<EmodulSlot>()
            for (entry in text.split(';')) {
                val parts = entry.split('|')
                if (parts.size != 3) continue
                val slot = parts[0].toIntOrNull()?.takeIf { it >= 1 } ?: continue
                val udid = parts[1].takeIf(EmodulUrls::isValidUdid) ?: continue
                val enabled = when (parts[2]) {
                    "1" -> true
                    "0" -> false
                    else -> continue
                }
                if (result.any { it.slot == slot || it.udid == udid }) continue
                result += EmodulSlot(slot, udid, enabled)
            }
            return result.sortedBy { it.slot }
        }

        /**
         * Ustawienia w pamięci z jednorazowym kluczem szyfrującym (bez Android Keystore): testy i środowiska bez
         * zapisu na dysku. Nic nie przeżywa zamknięcia procesu.
         */
        fun inMemory(): EmodulSettings {
            val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            return EmodulSettings(InMemoryKeyValueStore(), AesGcmCipher(SecretKeySource { key }))
        }
    }
}
