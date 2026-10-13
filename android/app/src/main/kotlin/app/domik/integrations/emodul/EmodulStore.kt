package app.domik.integrations.emodul

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Ostatnia surowa odpowiedź API (moduł albo menu) i czas jej pobrania. */
class StoredResponse(val fetchedAtMillis: Long, val json: String)

/** Zapisany podzbiór słownika `i18n` dla [language]. */
class StoredI18n(val language: String, val fetchedAtMillis: Long, val entries: Map<Int, String>)

/**
 * Pliki integracji eModul w katalogu [dir] (`filesDir/emodul/`): ostatnie surowe odpowiedzi modułu i menu na slot
 * (`module_<slot>.json`, `menu_<slot>_<MU|MI>.json`), listę modułów (`modules.json`) oraz podzbiór słownika (`i18n_<lang>.txt`). Dzięki nim po
 * uruchomieniu bez internetu pulpit pokazuje ostatnie (oznaczone jako nieaktualne) dane.
 *
 * Format: pierwsza linia `v1 <czas pobrania ms>`, dalej treść (surowy JSON albo linie `<id>\t<tekst>`, gdzie `\t`,
 * `\n`, `\r` i `\` w tekście są poprzedzone `\`). Zapis przez plik tymczasowy i zmianę nazwy (przerwany zapis nie
 * psuje poprzedniej wersji). Błędy zapisu są ignorowane; uszkodzony plik daje `null`. Dane pochodzą z konta
 * użytkownika – katalog jest prywatny dla aplikacji (kopia zapasowa wyłączona).
 */
class EmodulStore(private val dir: File) {
    /** Ostatnia lista modułów konta (`modules.json`) – nazwy i wersje do pokazania przed pierwszym odczytem z sieci. */
    fun readModules(): StoredResponse? = readResponse(File(dir, MODULES_FILE))

    fun writeModules(response: StoredResponse) = writeResponse(File(dir, MODULES_FILE), response)

    fun readModule(slot: Int): StoredResponse? = readResponse(moduleFile(slot))

    fun writeModule(slot: Int, response: StoredResponse) = writeResponse(moduleFile(slot), response)

    fun readMenu(slot: Int, type: MenuType): StoredResponse? = readResponse(menuFile(slot, type))

    fun writeMenu(slot: Int, type: MenuType, response: StoredResponse) = writeResponse(menuFile(slot, type), response)

    /** Podzbiór słownika dla [language] (`pl`/`en`, inny → `pl`); `null` gdy brak lub plik uszkodzony. */
    fun readI18n(language: String): StoredI18n? {
        val lang = normalizeLanguage(language)
        val (fetchedAt, body) = readWithHeader(i18nFile(lang)) ?: return null
        val entries = decodeEntries(body) ?: return null
        return StoredI18n(lang, fetchedAt, entries)
    }

    fun writeI18n(language: String, fetchedAtMillis: Long, entries: Map<Int, String>) {
        val lang = normalizeLanguage(language)
        writeWithHeader(i18nFile(lang), fetchedAtMillis, encodeEntries(entries))
    }

    /** Usuwa pliki jednego slotu (moduł i oba menu). */
    fun clearSlot(slot: Int) {
        moduleFile(slot).delete()
        MenuType.entries.forEach { menuFile(slot, it).delete() }
    }

    /** Usuwa wszystko (usunięcie klucza). */
    fun clear() {
        dir.listFiles()?.forEach { if (it.isFile) it.delete() }
    }

    private fun moduleFile(slot: Int) = File(dir, "module_${checkedSlot(slot)}.json")

    private fun menuFile(slot: Int, type: MenuType) = File(dir, "menu_${checkedSlot(slot)}_${type.name}.json")

    private fun i18nFile(language: String) = File(dir, "i18n_$language.txt")

    private fun checkedSlot(slot: Int): Int {
        require(slot >= 1) { "Numer slotu musi być dodatni" }
        return slot
    }

    private fun normalizeLanguage(language: String): String =
        language.lowercase(java.util.Locale.ROOT).takeIf { it == "pl" || it == "en" } ?: EmodulUrls.DEFAULT_LANGUAGE

    private fun readResponse(file: File): StoredResponse? =
        readWithHeader(file)?.let { (fetchedAt, body) -> StoredResponse(fetchedAt, body) }

    private fun writeResponse(file: File, response: StoredResponse) =
        writeWithHeader(file, response.fetchedAtMillis, response.json)

    private fun readWithHeader(file: File): Pair<Long, String>? = try {
        val text = file.readText(Charsets.UTF_8)
        val newline = text.indexOf('\n')
        val header = if (newline > 0) text.substring(0, newline).split(' ') else emptyList()
        val fetchedAt = header.getOrNull(1)?.toLongOrNull()
        if (header.size == 2 && header[0] == VERSION && fetchedAt != null) fetchedAt to text.substring(newline + 1) else null
    } catch (_: IOException) {
        null
    }

    private fun writeWithHeader(file: File, fetchedAtMillis: Long, body: String) {
        try {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText("$VERSION $fetchedAtMillis\n$body", Charsets.UTF_8)
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: IOException) {
            // Brak plików nie jest błędem – dane zostaną pobrane ponownie.
        }
    }

    internal companion object {
        const val VERSION = "v1"
        const val MODULES_FILE = "modules.json"

        fun encodeEntries(entries: Map<Int, String>): String = buildString {
            for (id in entries.keys.sorted()) append(id).append('\t').append(escape(entries.getValue(id))).append('\n')
        }

        /** `null` gdy jakakolwiek linia jest uszkodzona (zły id, brak tabulatora, nieznana sekwencja `\`). */
        fun decodeEntries(body: String): Map<Int, String>? {
            val result = HashMap<Int, String>()
            if (body.isEmpty()) return result
            if (!body.endsWith('\n')) return null
            for (line in body.removeSuffix("\n").split('\n')) {
                val tab = line.indexOf('\t')
                if (tab <= 0) return null
                val id = line.substring(0, tab).toIntOrNull() ?: return null
                result[id] = unescape(line.substring(tab + 1)) ?: return null
            }
            return result
        }

        private fun escape(text: String): String = buildString(text.length) {
            for (c in text) {
                when (c) {
                    '\\' -> append("\\\\")
                    '\t' -> append("\\t")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    else -> append(c)
                }
            }
        }

        private fun unescape(text: String): String? {
            if ('\\' !in text) return text
            val out = StringBuilder(text.length)
            var i = 0
            while (i < text.length) {
                val c = text[i++]
                if (c != '\\') {
                    out.append(c)
                    continue
                }
                when (text.getOrNull(i++)) {
                    '\\' -> out.append('\\')
                    't' -> out.append('\t')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    else -> return null
                }
            }
            return out.toString()
        }
    }
}
