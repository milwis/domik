package app.domik.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LinkSpansTest {
    private fun texts(text: String, urls: List<String>) =
        findLinkRanges(text, urls).map { text.substring(it.start, it.endExclusive) to it.url }

    @Test
    fun `adres w tekscie staje sie odnosnikiem do pelnego adresu https`() {
        val text = "Pobieraj z: github.com/milwis/domik/releases."
        assertEquals(
            listOf("github.com/milwis/domik/releases" to ProjectLinks.RELEASES),
            texts(text, listOf(ProjectLinks.RELEASES)),
        )
    }

    @Test
    fun `kropka konczaca zdanie nie nalezy do odnosnika`() {
        val text = "Kontakt: github.com/milwis/domik/issues. Dziekuje."
        val range = findLinkRanges(text, listOf(ProjectLinks.ISSUES)).single()
        assertEquals("github.com/milwis/domik/issues", text.substring(range.start, range.endExclusive))
    }

    @Test
    fun `dluzszy adres ma pierwszenstwo przed krotszym`() {
        val text = "Zrodlo: github.com/milwis/domik/releases oraz github.com/milwis/domik"
        assertEquals(
            listOf(
                "github.com/milwis/domik/releases" to ProjectLinks.RELEASES,
                "github.com/milwis/domik" to ProjectLinks.REPOSITORY,
            ),
            texts(text, listOf(ProjectLinks.REPOSITORY, ProjectLinks.RELEASES)),
        )
    }

    @Test
    fun `adres bedacy poczatkiem dluzszego nie jest linkowany osobno`() {
        val text = "github.com/milwis/domik/issues"
        assertEquals(
            listOf(text to ProjectLinks.ISSUES),
            texts(text, listOf(ProjectLinks.REPOSITORY, ProjectLinks.ISSUES)),
        )
    }

    @Test
    fun `adres bedacy poczatkiem nieznanej sciezki nie jest linkowany`() {
        assertTrue(findLinkRanges("github.com/milwis/domik/issues-old", listOf(ProjectLinks.ISSUES)).isEmpty())
        assertTrue(findLinkRanges("xgithub.com/milwis/domik/issues", listOf(ProjectLinks.ISSUES)).isEmpty())
    }

    @Test
    fun `kazde wystapienie adresu jest odnosnikiem a wyniki sa posortowane`() {
        val text = "github.com/milwis/domik/issues, a potem github.com/milwis/domik/releases i znowu github.com/milwis/domik/issues"
        val ranges = findLinkRanges(text, listOf(ProjectLinks.ISSUES, ProjectLinks.RELEASES))
        assertEquals(listOf(ProjectLinks.ISSUES, ProjectLinks.RELEASES, ProjectLinks.ISSUES), ranges.map { it.url })
        assertEquals(ranges.sortedBy { it.start }, ranges)
    }

    @Test
    fun `tekst bez adresow nie ma odnosnikow`() {
        assertTrue(findLinkRanges("Zwykly tekst.", ProjectLinks.repositoryLinks).isEmpty())
        assertTrue(findLinkRanges("", ProjectLinks.repositoryLinks).isEmpty())
    }
}
