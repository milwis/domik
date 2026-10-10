package app.domik.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AppVersionTest {
    @Test
    fun `wersja sklada sie z nazwy i kodu`() {
        assertEquals(AppVersion("0.1.0", 1), appVersionOf("0.1.0", 1))
        assertEquals(AppVersion("1.2.3-beta", 4_000_000_000L), appVersionOf("1.2.3-beta", 4_000_000_000L))
    }

    @Test
    fun `nazwa jest przycinana`() {
        assertEquals(AppVersion("0.1.0", 7), appVersionOf("  0.1.0 ", 7))
    }

    @Test
    fun `brak nazwy wersji oznacza brak wersji`() {
        assertNull(appVersionOf(null, 1))
        assertNull(appVersionOf("", 1))
        assertNull(appVersionOf("   ", 1))
    }
}
