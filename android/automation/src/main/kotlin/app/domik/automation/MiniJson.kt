package app.domik.automation

/** Syntax error in a JSON document; [message] describes it with the offset. */
internal class JsonSyntaxException(message: String) : Exception(message)

/**
 * Minimal JSON reader / writer – the module must not depend on a JSON library (Android's `org.json` does not exist
 * on a plain JVM, and the project allows only Google / JetBrains libraries).
 *
 * Values: `Map<String, Any?>` (object, insertion order), `List<Any?>` (array), `String`, `Long` (integral number
 * that fits), `Double` (other numbers), `Boolean`, `null`. Strict: no comments, no trailing commas, nesting limited
 * to [MAX_DEPTH].
 */
internal object MiniJson {
    private const val MAX_DEPTH = 32

    fun parse(text: String): Any? {
        val reader = Reader(text)
        val value = reader.readValue(0)
        reader.skipWhitespace()
        if (!reader.atEnd) reader.fail("unexpected characters after the document")
        return value
    }

    fun stringify(value: Any?): String = StringBuilder().also { write(value, it) }.toString()

    private fun write(value: Any?, out: StringBuilder) {
        when (value) {
            null -> out.append("null")
            is Boolean -> out.append(value)
            is Int, is Long, is Short, is Byte -> out.append(value.toString())
            is Double -> out.append(value.also { require(it.isFinite()) { "JSON cannot hold $it" } })
            is Float -> out.append(value.toDouble().also { require(it.isFinite()) { "JSON cannot hold $it" } })
            is String -> writeString(value, out)
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((key, element) in value) {
                    if (!first) out.append(',')
                    first = false
                    writeString(key as? String ?: throw IllegalArgumentException("JSON object keys must be strings"), out)
                    out.append(':')
                    write(element, out)
                }
                out.append('}')
            }
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { index, element ->
                    if (index > 0) out.append(',')
                    write(element, out)
                }
                out.append(']')
            }
            else -> throw IllegalArgumentException("cannot write ${value::class.java.simpleName} as JSON")
        }
    }

    private fun writeString(text: String, out: StringBuilder) {
        out.append('"')
        for (char in text) {
            when {
                char == '"' -> out.append("\\\"")
                char == '\\' -> out.append("\\\\")
                char == '\n' -> out.append("\\n")
                char == '\r' -> out.append("\\r")
                char == '\t' -> out.append("\\t")
                char.code < 0x20 -> out.append("\\u").append(char.code.toString(16).padStart(4, '0'))
                else -> out.append(char)
            }
        }
        out.append('"')
    }

    private class Reader(private val text: String) {
        private var position = 0

        val atEnd: Boolean get() = position >= text.length

        fun fail(reason: String): Nothing = throw JsonSyntaxException("$reason at offset $position")

        fun skipWhitespace() {
            while (position < text.length && text[position] in " \t\r\n") position++
        }

        fun readValue(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail("nesting deeper than $MAX_DEPTH")
            skipWhitespace()
            if (atEnd) fail("unexpected end of document")
            return when (val char = text[position]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (char == '-' || char in '0'..'9') readNumber() else fail("unexpected character '$char'")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!text.startsWith(word, position)) fail("invalid literal")
            position += word.length
            return value
        }

        private fun readObject(depth: Int): Map<String, Any?> {
            position++ // {
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                position++
                return result
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("object key expected")
                val key = readString()
                skipWhitespace()
                if (peek() != ':') fail("':' expected")
                position++
                result[key] = readValue(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> position++
                    '}' -> {
                        position++
                        return result
                    }
                    else -> fail("',' or '}' expected")
                }
            }
        }

        private fun readArray(depth: Int): List<Any?> {
            position++ // [
            val result = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                position++
                return result
            }
            while (true) {
                result += readValue(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> position++
                    ']' -> {
                        position++
                        return result
                    }
                    else -> fail("',' or ']' expected")
                }
            }
        }

        private fun peek(): Char = if (atEnd) fail("unexpected end of document") else text[position]

        private fun readString(): String {
            position++ // opening quote
            val out = StringBuilder()
            while (true) {
                if (atEnd) fail("unterminated string")
                val char = text[position++]
                when {
                    char == '"' -> return out.toString()
                    char == '\\' -> {
                        if (atEnd) fail("unterminated escape")
                        when (val escape = text[position++]) {
                            '"', '\\', '/' -> out.append(escape)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (position + 4 > text.length) fail("truncated \\u escape")
                                val code = text.substring(position, position + 4).toIntOrNull(16)
                                    ?: fail("invalid \\u escape")
                                out.append(code.toChar())
                                position += 4
                            }
                            else -> fail("invalid escape '\\$escape'")
                        }
                    }
                    char.code < 0x20 -> fail("unescaped control character in string")
                    else -> out.append(char)
                }
            }
        }

        private fun readNumber(): Any {
            val start = position
            if (text[position] == '-') position++
            if (atEnd) fail("invalid number")
            if (text[position] == '0') {
                position++
            } else if (text[position] in '1'..'9') {
                digits()
            } else {
                fail("invalid number")
            }
            var integral = true
            if (!atEnd && text[position] == '.') {
                integral = false
                position++
                if (digits() == 0) fail("digits expected after '.'")
            }
            if (!atEnd && (text[position] == 'e' || text[position] == 'E')) {
                integral = false
                position++
                if (!atEnd && (text[position] == '+' || text[position] == '-')) position++
                if (digits() == 0) fail("digits expected in exponent")
            }
            val token = text.substring(start, position)
            if (integral) token.toLongOrNull()?.let { return it }
            return token.toDouble().also { if (!it.isFinite()) fail("number out of range") }
        }

        private fun digits(): Int {
            val start = position
            while (!atEnd && text[position] in '0'..'9') position++
            return position - start
        }
    }
}
