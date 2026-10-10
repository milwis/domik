package app.domik.automation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MiniJsonTest {
    private fun parse(text: String) = MiniJson.parse(text)

    @Test
    fun `parses scalars`() {
        assertEquals(true, parse("true"))
        assertEquals(false, parse(" false "))
        assertNull(parse("null"))
        assertEquals(0L, parse("0"))
        assertEquals(-17L, parse("-17"))
        assertEquals(1.5, parse("1.5"))
        assertEquals(1000.0, parse("1e3"))
        assertEquals(-0.025, parse("-2.5E-2"))
        assertEquals("a b", parse("\"a b\""))
    }

    @Test
    fun `integers beyond Long become doubles`() {
        assertEquals(Long.MAX_VALUE, parse("9223372036854775807"))
        assertEquals(9.223372036854775808E18, parse("9223372036854775808"))
    }

    @Test
    fun `parses nested structures keeping key order`() {
        val value = parse("""{"b": [1, 2, {"c": null}], "a": {}, "z": []}""") as Map<*, *>
        assertEquals(listOf("b", "a", "z"), value.keys.toList())
        assertEquals(listOf(1L, 2L, mapOf("c" to null)), value["b"])
        assertEquals(emptyMap<String, Any?>(), value["a"])
        assertEquals(emptyList<Any?>(), value["z"])
    }

    @Test
    fun `parses string escapes`() {
        assertEquals("q\" b\\ s/ \b \u000C \n \r \t", parse("\"q\\\" b\\\\ s\\/ \\b \\f \\n \\r \\t\""))
        assertEquals("Zażółć", parse("\"Za\\u017C\\u00F3\\u0142\\u0107\""))
        assertEquals("😀", parse("\"\\uD83D\\uDE00\""))
        assertEquals("żółw", parse("\"żółw\""))
    }

    @Test
    fun `rejects malformed documents`() {
        listOf(
            "", "   ", "{", "[1,", "[1,]", "{\"a\":1,}", "{\"a\" 1}", "{a:1}", "[1 2]", "\"abc", "\"a\\x\"", "\"\\u12\"",
            "01", "+1", "1.", ".5", "1e", "-", "tru", "nul", "NaN", "Infinity", "1e999", "{} x", "[] []", "\"a\nb\"",
        ).forEach { text ->
            assertThrows(JsonSyntaxException::class.java, { parse(text) }, "should fail: <$text>")
        }
    }

    @Test
    fun `limits nesting depth`() {
        val ok = "[".repeat(30) + "]".repeat(30)
        assertTrue(parse(ok) is List<*>)
        val deep = "[".repeat(5000) + "]".repeat(5000)
        assertThrows(JsonSyntaxException::class.java) { parse(deep) }
    }

    @Test
    fun `writes and reads back what it wrote`() {
        val original = mapOf(
            "text" to "quote \" backslash \\ newline \n tab \t control \u0001 unicode żółć 😀",
            "numbers" to listOf(0, -5, 123456789012L, 2.5),
            "flags" to listOf(true, false, null),
            "nested" to mapOf("empty" to emptyMap<String, Any?>(), "list" to emptyList<Any?>()),
        )
        val json = MiniJson.stringify(original)
        assertEquals(
            mapOf(
                "text" to original["text"],
                "numbers" to listOf(0L, -5L, 123456789012L, 2.5),
                "flags" to listOf(true, false, null),
                "nested" to original["nested"],
            ),
            parse(json),
        )
    }

    @Test
    fun `writer escapes control characters and keeps output compact`() {
        assertEquals("""{"a":"x\u0001y","b":[1,true]}""", MiniJson.stringify(mapOf("a" to "x\u0001y", "b" to listOf(1, true))))
    }

    @Test
    fun `writer rejects what JSON cannot hold`() {
        assertThrows(IllegalArgumentException::class.java) { MiniJson.stringify(Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { MiniJson.stringify(mapOf(1 to 2)) }
        assertThrows(IllegalArgumentException::class.java) { MiniJson.stringify(Any()) }
    }
}
