package app.domik.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

class ProjectLinksTest {
    @Test
    fun `adresy w repozytorium zaczynaja sie od adresu projektu`() {
        for (url in ProjectLinks.repositoryLinks) {
            assertTrue(url.startsWith("https://github.com/milwis/domik"), url)
        }
    }

    @Test
    fun `wszystkie adresy sa https i nie powtarzaja sie`() {
        val all = ProjectLinks.repositoryLinks + ProjectLinks.externalLinks
        for (url in all) assertTrue(isHttpsUrl(url), url)
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun `listy obejmuja wszystkie stale obiektu`() {
        val constants = ProjectLinks::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { it.get(null) as String }
            .toSet()
        assertEquals(constants, (ProjectLinks.repositoryLinks + ProjectLinks.externalLinks).toSet())
    }

    @Test
    fun `adresy dokumentow i zgloszen wskazuja pliki z repozytorium`() {
        assertEquals("https://github.com/milwis/domik", ProjectLinks.REPOSITORY)
        assertEquals("https://github.com/milwis/domik/releases", ProjectLinks.RELEASES)
        assertEquals("https://github.com/milwis/domik/issues", ProjectLinks.ISSUES)
        assertEquals("https://github.com/milwis/domik/tree/main/legal", ProjectLinks.LEGAL_DIR)
        assertEquals("https://github.com/milwis/domik/blob/main/legal/regulamin.md", ProjectLinks.TERMS)
        assertEquals("https://github.com/milwis/domik/blob/main/legal/polityka-prywatnosci.md", ProjectLinks.PRIVACY_POLICY)
        assertEquals("https://github.com/milwis/domik/blob/main/LICENSE", ProjectLinks.LICENSE_MIT)
    }

    @Test
    fun `postac wyswietlana nie ma schematu ani koncowego ukosnika`() {
        assertEquals("github.com/milwis/domik/releases", ProjectLinks.displayAddress(ProjectLinks.RELEASES))
        assertEquals("open-meteo.com", ProjectLinks.displayAddress(ProjectLinks.OPEN_METEO))
        assertEquals("creativecommons.org/licenses/by/4.0", ProjectLinks.displayAddress(ProjectLinks.LICENSE_CC_BY_4))
    }

    @Test
    fun `przegladarce przekazujemy tylko poprawne adresy https`() {
        assertTrue(isHttpsUrl("https://github.com/milwis/domik"))
        assertFalse(isHttpsUrl("http://github.com/milwis/domik"))
        assertFalse(isHttpsUrl("javascript:alert(1)"))
        assertFalse(isHttpsUrl("intent://scan/#Intent;end"))
        assertFalse(isHttpsUrl("https://"))
        assertFalse(isHttpsUrl(""))
        assertFalse(isHttpsUrl("https://example.com/a b"))
        assertFalse(isHttpsUrl("https://example.com/a\nb"))
    }
}
