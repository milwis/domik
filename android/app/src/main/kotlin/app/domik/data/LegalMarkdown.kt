package app.domik.data

/**
 * Mały parser Markdowna dla dokumentów prawnych dołączonych do aplikacji (`legal/regulamin.md`, `polityka-prywatnosci.md`
 * i wersje angielskie – patrz [LegalDocuments]). Obsługuje wyłącznie to, czego te dokumenty używają: nagłówki `#`…`######`,
 * akapity (zawinięte wiersze łączone spacją), listy punktowane i numerowane (także zagnieżdżone, z kontynuacją
 * w kolejnych wierszach), cytaty `>`, tabele `| a | b |`, linię poziomą oraz w tekście **pogrubienie**, *kursywę*,
 * `kod`, `<https://adres>` i `[tekst](adres)`. Nic więcej – to nie jest pełny CommonMark. Czysta logika (bez Compose),
 * żeby dało się ją testować na JVM; rysuje ją `LegalDocumentScreen`.
 */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: List<MdInline>) : MdBlock
    data class Paragraph(val text: List<MdInline>) : MdBlock

    /** Pozycja listy; [depth] od 0, [marker] to „•” (lista punktowana) albo „1.” (numerowana). */
    data class ListItem(val depth: Int, val marker: String, val text: List<MdInline>) : MdBlock

    data class Quote(val blocks: List<MdBlock>) : MdBlock
    data class Table(val header: List<List<MdInline>>, val rows: List<List<List<MdInline>>>) : MdBlock
    data object Rule : MdBlock
}

/** Fragment tekstu w jednym stylu; [link] to surowy adres docelowy z dokumentu (https albo odnośnik względny). */
data class MdInline(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val link: String? = null,
)

object LegalMarkdown {

    fun parse(markdown: String): List<MdBlock> =
        dropNavigation(parseBlocks(markdown.replace("\r\n", "\n").replace('\r', '\n').lines()))

    // --- bloki ---------------------------------------------------------------------------------------------

    private val heading = Regex("""^(#{1,6})\s+(.*?)\s*#*\s*$""")
    private val listMarker = Regex("""^(\s*)([-*+]|\d+[.)])\s+(.*)$""")
    private val rule = Regex("""^\s{0,3}([-*_])(\s*\1){2,}\s*$""")

    private fun parseBlocks(lines: List<String>): List<MdBlock> {
        val blocks = mutableListOf<MdBlock>()
        val paragraph = StringBuilder()
        // Stos wcięć otwartych list: poziom zagnieżdżenia = pozycja wcięcia na stosie.
        val indents = mutableListOf<Int>()
        // Ostatnia pozycja listy, do której można dopisać kontynuację (kolejne wiersze z wcięciem).
        var openItem: StringBuilder? = null
        var openItemMeta: Pair<Int, String>? = null

        fun flushParagraph() {
            if (paragraph.isNotBlank()) blocks += MdBlock.Paragraph(parseInline(paragraph.toString().trim()))
            paragraph.clear()
        }

        fun flushItem() {
            val item = openItem
            val meta = openItemMeta
            if (item != null && meta != null) blocks += MdBlock.ListItem(meta.first, meta.second, parseInline(item.toString().trim()))
            openItem = null
            openItemMeta = null
        }

        fun endList() {
            flushItem()
            indents.clear()
        }

        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> {
                    // Pusty wiersz kończy akapit, ale nie listę (kolejny wiersz zdecyduje, czy lista trwa).
                    flushParagraph()
                }

                trimmed.startsWith(">") -> {
                    flushParagraph()
                    endList()
                    val quote = mutableListOf<String>()
                    while (index < lines.size && lines[index].trim().startsWith(">")) {
                        quote += lines[index].trim().removePrefix(">").removePrefix(" ")
                        index++
                    }
                    blocks += MdBlock.Quote(parseBlocks(quote))
                    continue
                }

                trimmed.startsWith("|") -> {
                    flushParagraph()
                    endList()
                    val rows = mutableListOf<String>()
                    while (index < lines.size && lines[index].trim().startsWith("|")) {
                        rows += lines[index].trim()
                        index++
                    }
                    parseTable(rows)?.let { blocks += it }
                    continue
                }

                heading.matches(trimmed) && line.length == line.trimStart().length -> {
                    flushParagraph()
                    endList()
                    val match = heading.matchEntire(trimmed)!!
                    blocks += MdBlock.Heading(match.groupValues[1].length, parseInline(match.groupValues[2]))
                }

                rule.matches(line) -> {
                    flushParagraph()
                    endList()
                    blocks += MdBlock.Rule
                }

                listMarker.matches(line) -> {
                    flushParagraph()
                    flushItem()
                    val match = listMarker.matchEntire(line)!!
                    val indent = match.groupValues[1].length
                    while (indents.isNotEmpty() && indents.last() > indent) indents.removeAt(indents.lastIndex)
                    if (indents.isEmpty() || indents.last() < indent) indents += indent
                    val bullet = match.groupValues[2]
                    val marker = if (bullet[0].isDigit()) bullet else "•"
                    openItem = StringBuilder(match.groupValues[3])
                    openItemMeta = (indents.size - 1) to marker
                }

                openItem != null && line.startsWith(" ") -> {
                    // Kontynuacja pozycji listy (wiersz z wcięciem).
                    openItem!!.append(' ').append(trimmed)
                }

                else -> {
                    // Zwykły tekst bez wcięcia: kończy listę i dokłada się do akapitu.
                    endList()
                    if (paragraph.isNotEmpty()) paragraph.append(' ')
                    paragraph.append(trimmed)
                }
            }
            index++
        }
        flushParagraph()
        endList()
        return blocks
    }

    private fun parseTable(rows: List<String>): MdBlock.Table? {
        if (rows.size < 2) return null
        fun cells(row: String): List<String> = row.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }
        val header = cells(rows[0])
        val separator = cells(rows[1])
        if (separator.isEmpty() || !separator.all { cell -> cell.isNotEmpty() && cell.all { it == '-' || it == ':' } }) return null
        val body = rows.drop(2).map { row -> cells(row).map(::parseInline) }
        return MdBlock.Table(header.map(::parseInline), body)
    }

    /**
     * Wiersz „nawigacji po repozytorium” (np. `[English version](en/terms.md) · [Polityka prywatności](…)`): same
     * odnośniki względne rozdzielone kropkami środkowymi. W aplikacji nie ma dokąd prowadzić, więc go pomijamy.
     */
    private fun dropNavigation(blocks: List<MdBlock>): List<MdBlock> = blocks.filterNot { block ->
        block is MdBlock.Paragraph && isNavigation(block.text)
    }

    private fun isNavigation(text: List<MdInline>): Boolean {
        val links = text.filter { it.link != null }
        if (links.isEmpty() || links.any { isHttpsUrl(it.link!!) }) return false
        return text.filter { it.link == null }.all { part -> part.text.all { it.isWhitespace() || it == '·' || it == '|' } }
    }

    // --- tekst w wierszu ---------------------------------------------------------------------------------------

    fun parseInline(text: String): List<MdInline> {
        val out = mutableListOf<MdInline>()
        inline(text, MdInline(""), out)
        return merge(out)
    }

    private fun inline(text: String, style: MdInline, out: MutableList<MdInline>) {
        val plain = StringBuilder()
        fun flush() {
            if (plain.isNotEmpty()) out += style.copy(text = plain.toString())
            plain.clear()
        }

        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < text.length && text[i + 1] in "\\`*_{}[]()#+-.!|<>~" -> {
                    plain.append(text[i + 1])
                    i += 2
                }

                c == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) {
                        flush()
                        out += style.copy(text = text.substring(i + 1, end), code = true)
                        i = end + 1
                    } else {
                        plain.append(c)
                        i++
                    }
                }

                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > i + 2) {
                        flush()
                        inline(text.substring(i + 2, end), style.copy(bold = true), out)
                        i = end + 2
                    } else {
                        plain.append("**")
                        i += 2
                    }
                }

                c == '*' && i + 1 < text.length && !text[i + 1].isWhitespace() -> {
                    val end = closingStar(text, i + 1)
                    if (end > i) {
                        flush()
                        inline(text.substring(i + 1, end), style.copy(italic = true), out)
                        i = end + 1
                    } else {
                        plain.append(c)
                        i++
                    }
                }

                c == '<' && (text.startsWith("<https://", i) || text.startsWith("<http://", i)) -> {
                    val end = text.indexOf('>', i)
                    if (end > i) {
                        flush()
                        val url = text.substring(i + 1, end)
                        out += style.copy(text = url, link = url)
                        i = end + 1
                    } else {
                        plain.append(c)
                        i++
                    }
                }

                c == '[' -> {
                    val close = text.indexOf("](", i + 1)
                    val end = if (close > i) text.indexOf(')', close + 2) else -1
                    if (end > close) {
                        flush()
                        inline(text.substring(i + 1, close), style.copy(link = text.substring(close + 2, end)), out)
                        i = end + 1
                    } else {
                        plain.append(c)
                        i++
                    }
                }

                else -> {
                    plain.append(c)
                    i++
                }
            }
        }
        flush()
    }

    /** Indeks zamykającej `*` (pojedynczej, nie początku `**`, niepoprzedzonej spacją) albo -1. */
    private fun closingStar(text: String, from: Int): Int {
        var i = from
        while (i < text.length) {
            if (text[i] == '*' && !text[i - 1].isWhitespace() && !(i + 1 < text.length && text[i + 1] == '*')) return i
            i++
        }
        return -1
    }

    /** Łączy sąsiednie fragmenty w tym samym stylu (np. po wyjściu z zagnieżdżenia). */
    private fun merge(parts: List<MdInline>): List<MdInline> {
        val merged = mutableListOf<MdInline>()
        for (part in parts) {
            val last = merged.lastOrNull()
            if (last != null && last.copy(text = "") == part.copy(text = "") && last.link == null) {
                merged[merged.lastIndex] = last.copy(text = last.text + part.text)
            } else {
                merged += part
            }
        }
        return merged
    }
}

/** Zwykły tekst fragmentów (bez stylu), do opisów dostępności i testów. */
fun List<MdInline>.plainText(): String = joinToString("") { it.text }
