package app.domik.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ControllerTlsTest {
    @Test
    fun `wybor dostawcy nigdy nie rzuca i zawsze ma opis do diagnostyki`() {
        // Na JVM bez biblioteki natywnej Androida dostawca dołączony się nie załaduje – wtedy jest dostawca systemowy.
        val choice = ControllerTls.choice
        assertNotNull(choice.note)
        if (choice.provider == null) {
            assertTrue(choice.note.startsWith("bundled Conscrypt unavailable"), choice.note)
        } else {
            assertTrue(choice.note.startsWith("bundled Conscrypt "), choice.note)
        }
        assertEquals(choice.provider, ControllerTls.options().provider)
        assertEquals(choice.note, ControllerTls.options().providerNote)
    }

    @Test
    fun `opis awarii dostawcy zawiera przyczyne i jest skracany`() {
        val note = ControllerTls.fallbackNote(UnsatisfiedLinkError("x".repeat(500)))
        assertTrue(note.startsWith("bundled Conscrypt unavailable, using the system TLS provider (UnsatisfiedLinkError: xxx"), note)
        assertTrue(note.length < 260, note.length.toString())
    }

    @Test
    fun `naglowek raportu ma aplikacje Androida i urzadzenie`() {
        assertEquals(
            listOf("Domik 0.2.0 (2)", "Android 8.0.0 (SDK 26)", "Device: Samsung SM-T510"),
            diagnosticsHeader(AppVersion("0.2.0", 2), "8.0.0", 26, "Samsung", "SM-T510"),
        )
        assertEquals(
            listOf("Domik ?", "Android 14 (SDK 34)", "Device: Pixel"),
            diagnosticsHeader(null, "14", 34, "", "Pixel"),
        )
    }
}
