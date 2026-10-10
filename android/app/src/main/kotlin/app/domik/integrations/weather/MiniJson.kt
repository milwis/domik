package app.domik.integrations.weather

/** Odpowiedź dostawcy nie jest poprawnym JSON-em albo nie ma wymaganych pól. */
class WeatherParseException(message: String) : Exception(message)

/**
 * Minimalny parser JSON bez zależności (działa tak samo w aplikacji i w testach JVM – `org.json` z Androida
 * w testach jednostkowych jest atrapą). Zwraca: `Map<String, Any?>`, `List<Any?>`, `String`, `Double`, `Boolean`
 * albo `null`. Zagnieżdżenie jest ograniczone ([MAX_DEPTH]), a rozmiar odpowiedzi ogranicza już warstwa HTTP.
 */
internal object MiniJson {
    private const val MAX_DEPTH = 32

    /** @throws WeatherParseException tekst nie jest poprawnym JSON-em */
    fun parse(text: String): Any? {
        val reader = Reader(text)
        val value = reader.readValue(0)
        reader.skipWhitespace()
        if (!reader.atEnd) reader.fail("Nadmiarowe znaki po wartości")
        return value
    }

    private class Reader(private val text: String) {
        private var pos = 0
        val atEnd: Boolean get() = pos >= text.length

        fun fail(message: String): Nothing = throw WeatherParseException("$message (pozycja $pos)")

        fun skipWhitespace() {
            while (pos < text.length && text[pos].let { it == ' ' || it == '\n' || it == '\r' || it == '\t' }) pos++
        }

        fun readValue(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail("Zbyt głębokie zagnieżdżenie")
            skipWhitespace()
            if (atEnd) fail("Nieoczekiwany koniec tekstu")
            return when (val c = text[pos]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') readNumber() else fail("Nieoczekiwany znak '$c'")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!text.startsWith(word, pos)) fail("Nieoczekiwany znak")
            pos += word.length
            return value
        }

        private fun readObject(depth: Int): Map<String, Any?> {
            val result = LinkedHashMap<String, Any?>()
            pos++ // '{'
            skipWhitespace()
            if (peek() == '}') {
                pos++
                return result
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("Oczekiwano nazwy pola")
                val key = readString()
                skipWhitespace()
                expect(':')
                result[key] = readValue(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return result
                    }
                    else -> fail("Oczekiwano ',' lub '}'")
                }
            }
        }

        private fun readArray(depth: Int): List<Any?> {
            val result = ArrayList<Any?>()
            pos++ // '['
            skipWhitespace()
            if (peek() == ']') {
                pos++
                return result
            }
            while (true) {
                result += readValue(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return result
                    }
                    else -> fail("Oczekiwano ',' lub ']'")
                }
            }
        }

        private fun readString(): String {
            pos++ // otwierający '"'
            val out = StringBuilder()
            while (true) {
                if (atEnd) fail("Niezamknięty napis")
                val c = text[pos++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> out.append(readEscape())
                    c < ' ' -> fail("Znak sterujący w napisie")
                    else -> out.append(c)
                }
            }
        }

        private fun readEscape(): Char {
            if (atEnd) fail("Nieoczekiwany koniec tekstu")
            return when (val e = text[pos++]) {
                '"', '\\', '/' -> e
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    if (pos + 4 > text.length) fail("Za krótka sekwencja \\u")
                    val code = text.substring(pos, pos + 4).toIntOrNull(16) ?: fail("Niepoprawna sekwencja \\u")
                    pos += 4
                    code.toChar()
                }
                else -> fail("Niepoprawna sekwencja ucieczki")
            }
        }

        private fun readNumber(): Double {
            val start = pos
            while (pos < text.length && text[pos].let { it in '0'..'9' || it == '-' || it == '+' || it == '.' || it == 'e' || it == 'E' }) pos++
            return text.substring(start, pos).toDoubleOrNull()?.takeIf { it.isFinite() } ?: fail("Niepoprawna liczba")
        }

        private fun peek(): Char = if (atEnd) fail("Nieoczekiwany koniec tekstu") else text[pos]

        private fun expect(c: Char) {
            if (peek() != c) fail("Oczekiwano '$c'")
            pos++
        }
    }
}
