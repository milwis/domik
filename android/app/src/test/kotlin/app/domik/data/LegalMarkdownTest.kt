package app.domik.data

import app.domik.data.MdBlock.Heading
import app.domik.data.MdBlock.ListItem
import app.domik.data.MdBlock.Paragraph
import app.domik.data.MdBlock.Quote
import app.domik.data.MdBlock.Table
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LegalMarkdownTest {
    private fun plain(text: String) = listOf(MdInline(text))

    @Test
    fun `naglowki maja poziom i tekst`() {
        val blocks = LegalMarkdown.parse("# Tytul\n\n## § 1. Autor\n\n### 7.1 Kontakt")
        assertEquals(
            listOf(Heading(1, plain("Tytul")), Heading(2, plain("§ 1. Autor")), Heading(3, plain("7.1 Kontakt"))),
            blocks,
        )
    }

    @Test
    fun `zawiniete wiersze akapitu lacza sie spacja a pusty wiersz dzieli akapity`() {
        val blocks = LegalMarkdown.parse("Pierwszy wiersz\ndrugi wiersz.\n\nNowy akapit.")
        assertEquals(listOf(Paragraph(plain("Pierwszy wiersz drugi wiersz.")), Paragraph(plain("Nowy akapit."))), blocks)
    }

    @Test
    fun `pogrubienie kursywa i kod w wierszu`() {
        val text = LegalMarkdown.parseInline("To **wazne** i *kursywa* oraz `kod`.")
        assertEquals(
            listOf(
                MdInline("To "),
                MdInline("wazne", bold = true),
                MdInline(" i "),
                MdInline("kursywa", italic = true),
                MdInline(" oraz "),
                MdInline("kod", code = true),
                MdInline("."),
            ),
            text,
        )
    }

    @Test
    fun `pogrubienie moze zawierac kursywe a samotna gwiazdka zostaje tekstem`() {
        val nested = LegalMarkdown.parseInline("**tak *tez* tak**")
        assertEquals(listOf(MdInline("tak ", bold = true), MdInline("tez", bold = true, italic = true), MdInline(" tak", bold = true)), nested)
        assertEquals(plain("2 * 3 = 6"), LegalMarkdown.parseInline("2 * 3 = 6"))
        assertEquals(plain("niezamkniete **pogrubienie"), LegalMarkdown.parseInline("niezamkniete **pogrubienie"))
    }

    @Test
    fun `adres w nawiasach ostrych i odnosnik markdown staja sie linkami`() {
        val auto = LegalMarkdown.parseInline("Zobacz <https://example.org/zgloszenia>.")
        assertEquals(
            listOf(
                MdInline("Zobacz "),
                MdInline("https://example.org/zgloszenia", link = "https://example.org/zgloszenia"),
                MdInline("."),
            ),
            auto,
        )
        val named = LegalMarkdown.parseInline("w [Regulaminie](regulamin.md) jest")
        assertEquals(listOf(MdInline("w "), MdInline("Regulaminie", link = "regulamin.md"), MdInline(" jest")), named)
    }

    @Test
    fun `znak ucieczki wstawia znak dosłownie`() {
        assertEquals(plain("*nie kursywa*"), LegalMarkdown.parseInline("\\*nie kursywa\\*"))
    }

    @Test
    fun `lista punktowana z kontynuacja w kolejnym wierszu`() {
        val blocks = LegalMarkdown.parse("- pierwsza pozycja\n  ciag dalszy\n- druga")
        assertEquals(
            listOf(ListItem(0, "•", plain("pierwsza pozycja ciag dalszy")), ListItem(0, "•", plain("druga"))),
            blocks,
        )
    }

    @Test
    fun `lista numerowana zachowuje numery a zagniezdzone pozycje maja wieksza glebokosc`() {
        val md = "1. Kontakt:\n   1. pytania;\n   2. luki:\n      <https://github.com/x>.\n2. Dalej"
        val blocks = LegalMarkdown.parse(md)
        assertEquals(
            listOf(
                ListItem(0, "1.", plain("Kontakt:")),
                ListItem(1, "1.", plain("pytania;")),
                ListItem(1, "2.", listOf(MdInline("luki: "), MdInline("https://github.com/x", link = "https://github.com/x"), MdInline("."))),
                ListItem(0, "2.", plain("Dalej")),
            ),
            blocks,
        )
    }

    @Test
    fun `nielistowy wiersz bez wciecia konczy liste`() {
        val blocks = LegalMarkdown.parse("- punkt\n\nAkapit po liscie.")
        assertEquals(listOf(ListItem(0, "•", plain("punkt")), Paragraph(plain("Akapit po liscie."))), blocks)
    }

    @Test
    fun `cytat zawiera wlasne bloki`() {
        val blocks = LegalMarkdown.parse("> **W skrocie**\n>\n> - jeden\n>   dwa\n> - trzy")
        val quote = assertInstanceOf(Quote::class.java, blocks.single())
        assertEquals(
            listOf(
                Paragraph(listOf(MdInline("W skrocie", bold = true))),
                ListItem(0, "•", plain("jeden dwa")),
                ListItem(0, "•", plain("trzy")),
            ),
            quote.blocks,
        )
    }

    @Test
    fun `tabela ma naglowek i wiersze a separator nie jest wierszem`() {
        val md = "| Dane | Cel |\n|---|---|\n| IP | polaczenie |\n| **Serial** | klucz |"
        val table = assertInstanceOf(Table::class.java, LegalMarkdown.parse(md).single())
        assertEquals(listOf(plain("Dane"), plain("Cel")), table.header)
        assertEquals(2, table.rows.size)
        assertEquals(plain("IP"), table.rows[0][0])
        assertEquals(listOf(MdInline("Serial", bold = true)), table.rows[1][0])
        assertEquals(plain("klucz"), table.rows[1][1])
    }

    @Test
    fun `linia pozioma`() {
        assertEquals(listOf(Paragraph(plain("a")), MdBlock.Rule, Paragraph(plain("b"))), LegalMarkdown.parse("a\n\n---\n\nb"))
    }

    @Test
    fun `wiersz z samymi odnosnikami wzglednymi jest pomijany a odnosnik w zdaniu zostaje`() {
        val md = "[English version](en/terms.md) · [Polityka](polityka-prywatnosci.md)\n\nZgodnie z [Regulaminem](regulamin.md)."
        val blocks = LegalMarkdown.parse(md)
        assertEquals(1, blocks.size)
        assertEquals("Zgodnie z Regulaminem.", (blocks.single() as Paragraph).text.plainText())
    }

    @Test
    fun `wiersz z adresem https nie jest nawigacja`() {
        val blocks = LegalMarkdown.parse("[Repozytorium](https://example.org/projekt)")
        assertEquals(1, blocks.size)
    }

    @Test
    fun `konce wierszy windows i pusty tekst`() {
        assertEquals(listOf(Paragraph(plain("a b"))), LegalMarkdown.parse("a\r\nb\r\n"))
        assertTrue(LegalMarkdown.parse("").isEmpty())
    }
}
