package app.domik.data

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Dokumenty prawne dołączane do aplikacji są kopiowane przy buildzie z `legal/` (zadanie `copyLegalAssets`). Testy
 * czytają te same pliki z `legal/` (katalog nad `android/`) i pilnują, żeby parser radził sobie z każdym z nich,
 * a lista kopiowana przez Gradle zgadzała się z [LegalDocument].
 */
class LegalDocumentsTest {
    private val legalDir = File("../../legal")

    private fun source(document: LegalDocument, language: String): String {
        val relative = document.assetPath(language).removePrefix(LegalDocument.ASSET_DIR + "/")
        val file = File(legalDir, relative)
        check(file.isFile) { "Brak ${file.path} (testy uruchamiaj Gradle'em z modułu app)" }
        return file.readText()
    }

    @Test
    fun `sciezki assets zaleza od jezyka a nie-polski to angielski`() {
        assertEquals("legal/regulamin.md", LegalDocument.TERMS.assetPath("pl"))
        assertEquals("legal/en/terms.md", LegalDocument.TERMS.assetPath("en"))
        assertEquals("legal/polityka-prywatnosci.md", LegalDocument.PRIVACY.assetPath("pl"))
        assertEquals("legal/en/privacy-policy.md", LegalDocument.PRIVACY.assetPath("en"))
        assertEquals(LegalDocument.TERMS.assetPath("en"), LegalDocument.TERMS.assetPath("de"))
    }

    @Test
    fun `jezyk dokumentu w zasobach pasuje do jezyka interfejsu`() {
        assertEquals("pl", stringResources("values").getValue("legal_language"))
        assertEquals("en", stringResources("values-en").getValue("legal_language"))
    }

    @Test
    fun `odnosniki wzgledne rozpoznaja dokument w obu jezykach`() {
        assertEquals(LegalDocument.PRIVACY, LegalDocument.linkedFrom("polityka-prywatnosci.md"))
        assertEquals(LegalDocument.PRIVACY, LegalDocument.linkedFrom("privacy-policy.md"))
        assertEquals(LegalDocument.TERMS, LegalDocument.linkedFrom("../regulamin.md"))
        assertEquals(LegalDocument.TERMS, LegalDocument.linkedFrom("terms.md#s4"))
        assertNull(LegalDocument.linkedFrom("README.md"))
    }

    @Test
    fun `gradle kopiuje dokladnie te pliki co LegalDocument i nie kopiuje makiet`() {
        val script = moduleFile("build.gradle.kts").readText()
        val listed = Regex("""files\.set\(listOf\(([^)]*)\)\)""").find(script)?.groupValues?.get(1)
        val files = Regex(""""([^"]+)"""").findAll(checkNotNull(listed) { "brak files.set(listOf(…)) w build.gradle.kts" })
            .map { it.groupValues[1] }.toList()
        assertEquals(LegalDocument.sourceFiles.sorted(), files.sorted())
        assertTrue(files.none { it.contains("ekrany") || it.contains("app-screens") })
    }

    @Test
    fun `parser czyta wszystkie cztery dokumenty bez resztek skladni markdown`() {
        for (document in LegalDocument.entries) {
            for (language in listOf("pl", "en")) {
                val blocks = LegalMarkdown.parse(source(document, language))
                val label = document.assetPath(language)
                assertTrue(blocks.size > 20, "$label: za malo blokow (${blocks.size})")
                assertTrue(blocks.first() is MdBlock.Heading, "$label: dokument nie zaczyna sie od naglowka")
                for (text in blocks.flatMap(::visibleTexts)) {
                    assertFalse(text.contains("**"), "$label: resztka ** w „$text”")
                    assertFalse(text.contains("<http"), "$label: nierozpoznany adres w „$text”")
                    assertFalse(text.contains("](") , "$label: nierozpoznany odnosnik w „$text”")
                    assertFalse(text.startsWith("|"), "$label: nierozpoznana tabela w „$text”")
                }
            }
        }
    }

    @Test
    fun `dokumenty zawieraja naglowki paragrafow i nie zawieraja wiersza nawigacji repozytorium`() {
        val terms = LegalMarkdown.parse(source(LegalDocument.TERMS, "pl"))
        val headings = terms.filterIsInstance<MdBlock.Heading>().map { it.text.plainText() }
        assertTrue(headings.any { it.startsWith("§ 1.") }, headings.toString())
        assertTrue(terms.filterIsInstance<MdBlock.Paragraph>().none { it.text.plainText().contains("English version") })
        val privacy = LegalMarkdown.parse(source(LegalDocument.PRIVACY, "en"))
        assertTrue(privacy.any { it is MdBlock.Table }, "polityka powinna zawierac tabele danych")
    }

    private fun visibleTexts(block: MdBlock): List<String> = when (block) {
        is MdBlock.Heading -> listOf(block.text.plainText())
        is MdBlock.Paragraph -> listOf(block.text.plainText())
        is MdBlock.ListItem -> listOf(block.text.plainText())
        is MdBlock.Quote -> block.blocks.flatMap(::visibleTexts)
        is MdBlock.Table -> (block.header + block.rows.flatten()).map { it.plainText() }
        MdBlock.Rule -> emptyList()
    }
}
