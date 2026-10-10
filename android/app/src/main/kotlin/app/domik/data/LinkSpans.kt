package app.domik.data

/** Fragment tekstu `[start, endExclusive)`, który ma być klikalnym odnośnikiem do [url]. */
data class LinkRange(val start: Int, val endExclusive: Int, val url: String)

/**
 * Wyszukuje w [text] adresy w postaci wyświetlanej ([ProjectLinks.displayAddress]) i zwraca ich położenie
 * (rosnąco). Treść tekstu się nie zmienia – adresy, które autor wpisał do tekstu, stają się tylko klikalne.
 * Dłuższe adresy mają pierwszeństwo (`…/domik/releases` nie jest ucinane do `…/domik`), a dopasowanie musi
 * kończyć się na granicy adresu (po nim nie może być litery, cyfry, `/`, `-` ani `_`) i nie może zaczynać się
 * w środku słowa.
 */
fun findLinkRanges(text: String, urls: List<String>): List<LinkRange> {
    val found = mutableListOf<LinkRange>()
    for (url in urls.distinct().sortedByDescending { ProjectLinks.displayAddress(it).length }) {
        val needle = ProjectLinks.displayAddress(url)
        if (needle.isEmpty()) continue
        var from = 0
        while (true) {
            val start = text.indexOf(needle, from)
            if (start < 0) break
            val end = start + needle.length
            from = end
            val boundaries = (start == 0 || !text[start - 1].isLetterOrDigit()) && endsAtAddressBoundary(text, end)
            val overlaps = found.any { start < it.endExclusive && end > it.start }
            if (boundaries && !overlaps) found += LinkRange(start, end, url)
        }
    }
    return found.sortedBy { it.start }
}

private fun endsAtAddressBoundary(text: String, end: Int): Boolean {
    if (end >= text.length) return true
    val next = text[end]
    return !(next.isLetterOrDigit() || next == '/' || next == '-' || next == '_')
}
