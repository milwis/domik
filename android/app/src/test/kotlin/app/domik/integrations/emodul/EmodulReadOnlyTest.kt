package app.domik.integrations.emodul

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Integracja eModul jest tylko do odczytu: źródła nie mogą zawierać żadnej metody zapisu HTTP ani wysyłania treści
 * (wymaganie właściciela, `notes/EMODUL.md` §0.1). Test czyta pliki źródłowe modułu `:app`.
 */
class EmodulReadOnlyTest {
    /** Katalog `src/main/kotlin/app/domik` (testy Gradle uruchamiają się w katalogu modułu `android/app`). */
    private fun sourceRoot(): File {
        val candidates = listOf("src/main/kotlin/app/domik", "app/src/main/kotlin/app/domik", "android/app/src/main/kotlin/app/domik")
        return candidates.map { File(it) }.firstOrNull { it.isDirectory } ?: error("Nie znaleziono katalogu źródeł")
    }

    private fun emodulSources(): List<File> {
        val root = sourceRoot()
        val integration = File(root, "integrations/emodul").listFiles { f -> f.extension == "kt" }.orEmpty().toList()
        val ui = File(root, "ui").walkTopDown().filter { it.isFile && it.extension == "kt" && it.name.startsWith("Emodul") }.toList()
        return integration + ui
    }

    private val forbiddenWords = listOf("POST", "PUT", "DELETE", "PATCH")
    private val forbiddenFragments = listOf(
        "setRequestMethod", "requestMethod", "doOutput", "outputStream", "getOutputStream", "setDoOutput",
        "force_data_sync", "alarm_history", "HttpPost", "HttpPut", "HttpDelete",
    )

    @Test
    fun `znaleziono pliki integracji do sprawdzenia`() {
        val names = emodulSources().map { it.name }
        assertTrue(names.containsAll(listOf("EmodulHttp.kt", "EmodulApi.kt", "EmodulStore.kt", "EmodulPolicy.kt", "EmodulUrls.kt")), names.toString())
    }

    @Test
    fun `zrodla eModul nie zawieraja metod zapisu ani wysylania tresci`() {
        for (file in emodulSources()) {
            val text = file.readText(Charsets.UTF_8)
            for (word in forbiddenWords) {
                assertFalse(Regex("\\b$word\\b").containsMatchIn(text), "${file.name}: $word")
            }
            for (fragment in forbiddenFragments) {
                assertFalse(fragment in text, "${file.name}: $fragment")
            }
            assertFalse(Regex("\"pin\"", RegexOption.IGNORE_CASE).containsMatchIn(text), "${file.name}: pin")
        }
    }

    @Test
    fun `klient HTTP nie wywoluje setRequestMethod ani nie otwiera strumienia zapisu`() {
        val http = emodulSources().single { it.name == "EmodulHttp.kt" }.readText(Charsets.UTF_8)
        assertFalse("setRequestMethod" in http)
        assertFalse(Regex("\\boutputStream\\b|\\bgetOutputStream\\b|\\bdoOutput\\b|\\bdoInput\\b").containsMatchIn(http))
        // interfejs ma jedną metodę (get) i jedną implementację tej metody
        assertEquals(2, Regex("fun get\\(").findAll(http).count())
    }

    @Test
    fun `adresy nie obejmuja menu serwisowego producenta ani logowania`() {
        val urls = emodulSources().single { it.name == "EmodulUrls.kt" }.readText(Charsets.UTF_8)
        for (fragment in listOf("menu/MS", "menu/MP", "authentication")) {
            assertFalse(fragment in urls, fragment)
        }
        assertFalse(Regex("\"MS\"|\"MP\"").containsMatchIn(urls))
        assertEquals(listOf("MU", "MI"), MenuType.entries.map { it.name })
    }

    @Test
    fun `w calym pakiecie jest tylko jedno miejsce laczenia z siecia`() {
        val users = emodulSources().filter { "openConnection" in it.readText(Charsets.UTF_8) }.map { it.name }
        assertEquals(listOf("EmodulHttp.kt"), users)
        val others = emodulSources().filter { Regex("https?://").containsMatchIn(it.readText(Charsets.UTF_8)) }
            .flatMap { f -> Regex("https?://[A-Za-z0-9.-]+").findAll(f.readText(Charsets.UTF_8)).map { it.value } }
            .filter { !it.contains("emodul.eu") }
        // inne hosty mogą pojawić się wyłącznie w komentarzach/dokumentacji – żaden nie jest hostem aplikacji
        assertTrue(others.all { it.contains("api-documentation.emodul.eu") || it.isEmpty() }, others.toString())
    }
}
