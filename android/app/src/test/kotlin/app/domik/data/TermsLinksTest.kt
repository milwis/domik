package app.domik.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/**
 * Ekran akceptacji pokazuje streszczenie z adresami w postaci zwykłego tekstu; aplikacja robi je klikalnymi
 * ([findLinkRanges]). Te testy pilnują, żeby każdy adres w tekście (polskim i angielskim) był klikalny, a żeby
 * sama treść prawna – w tym token daty publikacji – pozostała nietknięta.
 */
class TermsLinksTest {
    private val locales = listOf("values", "values-en")

    private fun termsBody(dir: String): String = checkNotNull(stringResources(dir)["terms_body"]) { "brak terms_body w $dir" }

    @Test
    fun `kazdy adres z listy wystepuje w tekscie obu wersji jezykowych`() {
        for (dir in locales) {
            val found = findLinkRanges(termsBody(dir), TERMS_BODY_LINKS).map { it.url }.toSet()
            assertEquals(TERMS_BODY_LINKS.toSet(), found, "adresy klikalne w $dir")
        }
    }

    @Test
    fun `kazdy adres wpisany w tekscie jest klikalny`() {
        val address = Regex("""github\.com/[A-Za-z0-9_./-]*[A-Za-z0-9_/-]""")
        for (dir in locales) {
            val body = termsBody(dir)
            val ranges = findLinkRanges(body, TERMS_BODY_LINKS)
            for (match in address.findAll(body)) {
                val covered = ranges.any { it.start == match.range.first && it.endExclusive == match.range.last + 1 }
                if (!covered) fail<Unit>("W $dir adres „${match.value}” nie jest klikalny – dopisz go do TERMS_BODY_LINKS")
            }
        }
    }

    @Test
    fun `tekst zawiera date wersji regulaminu i zadnego niepodstawionego tokenu`() {
        val expected = mapOf("values" to "13 października 2026", "values-en" to "13 October 2026")
        for (dir in locales) {
            val body = termsBody(dir)
            assertTrue(body.contains(expected.getValue(dir)), dir)
            assertTrue(!body.contains("<<"), "$dir: niepodstawiony token")
        }
    }

    @Test
    fun `w obu wersjach jezykowych sa teksty przyciskow dokumentow`() {
        for (dir in locales) {
            val strings = stringResources(dir)
            for (key in listOf("terms_open_terms", "terms_open_privacy")) {
                assertTrue(!strings[key].isNullOrBlank(), "$dir: brak $key")
            }
        }
    }
}
