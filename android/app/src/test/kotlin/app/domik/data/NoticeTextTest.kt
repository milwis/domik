package app.domik.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NoticeTextTest {
    @Test
    fun `wiersze akapitu sa zlewane a akapity rozdzielane pustym wierszem`() {
        val raw = "Pierwszy akapit\n  ciag dalszy\r\n\n\n   Drugi akapit\n"
        assertEquals("Pierwszy akapit ciag dalszy\n\nDrugi akapit", reflowNoticeText(raw))
    }

    @Test
    fun `podkreslenie naglowka znakami rownosci jest pomijane`() {
        val raw = "Ikony (res)\n==========\n\nTresc"
        assertEquals("Ikony (res)\n\nTresc", reflowNoticeText(raw))
    }

    @Test
    fun `pusty tekst daje pusty wynik`() {
        assertEquals("", reflowNoticeText(""))
        assertEquals("", reflowNoticeText("\n\n  \n"))
    }

    @Test
    fun `uklad plikow z informacja o licencji nie zmienia ani jednego slowa`() {
        for (name in listOf("notice_material_icons", "notice_conscrypt")) checkLayout(moduleFile("src/main/res/raw/$name.txt").readText())
    }

    private fun checkLayout(raw: String) {
        val reflowed = reflowNoticeText(raw)
        fun words(text: String) = text.split(Regex("\\s+")).filter { it.isNotEmpty() && !it.all { c -> c == '=' } }
        assertEquals(words(raw), words(reflowed))
        assertTrue(reflowed.contains("Version 2.0, January 2004"))
        assertFalse(reflowed.lines().any { it.startsWith(" ") })
    }
}
