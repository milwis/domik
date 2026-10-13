package app.domik.integrations.emodul

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class EmodulUrlsTest {
    private val urls = EmodulUrls()
    private val udid = "0123456789abcdef0123456789abcdef"

    @Test
    fun `adresy sa dokladnie takie jak w specyfikacji`() {
        assertEquals("https://emodul.eu/api/v1/users/4242/modules", urls.modules(4242))
        assertEquals("https://emodul.eu/api/v1/users/4242/modules/$udid", urls.module(4242, udid))
        assertEquals("https://emodul.eu/api/v1/users/4242/modules/$udid/menu/MU/", urls.menu(4242, udid, MenuType.MU))
        assertEquals("https://emodul.eu/api/v1/users/4242/modules/$udid/menu/MI/", urls.menu(4242, udid, MenuType.MI))
        assertEquals("https://emodul.eu/api/v1/i18n/pl", urls.i18n("pl"))
        assertEquals("https://emodul.eu/api/v1/i18n/en", urls.i18n("en"))
    }

    @Test
    fun `jezyk spoza listy to pl`() {
        assertEquals("https://emodul.eu/api/v1/i18n/pl", urls.i18n("de"))
        assertEquals("https://emodul.eu/api/v1/i18n/pl", urls.i18n("../pl"))
        assertEquals("https://emodul.eu/api/v1/i18n/en", urls.i18n("EN"))
    }

    @Test
    fun `niebezpieczny udid rzuca wyjatek`() {
        for (bad in listOf("abc/def12345", "../../etc/passwd", "abcdef12?x=1", "abcdef12..", "short", "", " $udid", "a".repeat(65))) {
            assertThrows(IllegalArgumentException::class.java) { urls.module(4242, bad) }
            assertThrows(IllegalArgumentException::class.java) { urls.menu(4242, bad, MenuType.MU) }
        }
    }

    @Test
    fun `identyfikator uzytkownika musi byc dodatni`() {
        assertThrows(IllegalArgumentException::class.java) { urls.modules(0) }
        assertThrows(IllegalArgumentException::class.java) { urls.modules(-1) }
    }

    @Test
    fun `wlasna baza dla testow i wymog ukosnika`() {
        assertEquals("http://127.0.0.1:8080/api/v1/users/7/modules", EmodulUrls("http://127.0.0.1:8080/api/v1/").modules(7))
        assertThrows(IllegalArgumentException::class.java) { EmodulUrls("http://127.0.0.1:8080/api/v1") }
    }
}
