package app.domik.integrations.weather

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MiniJsonTest {
    @Test
    fun `parsuje obiekty tablice liczby napisy i literaly`() {
        val value = MiniJson.parse(""" {"a": [1, -2.5, 3e2, "x"], "b": {"c": true, "d": false, "e": null}} """)
        val root = value as Map<*, *>
        assertEquals(listOf(1.0, -2.5, 300.0, "x"), root["a"])
        val b = root["b"] as Map<*, *>
        assertEquals(true, b["c"])
        assertEquals(false, b["d"])
        assertTrue(b.containsKey("e"))
        assertNull(b["e"])
    }

    @Test
    fun `rozumie sekwencje ucieczki i znaki unicode`() {
        assertEquals("Kraków \"x\" \\ /\n\t", MiniJson.parse(""" "Krak\u00f3w \"x\" \\ \/\n\t" """))
        assertEquals("Gdańsk", MiniJson.parse("\"Gdańsk\""))
    }

    @Test
    fun `puste obiekty i tablice`() {
        assertEquals(emptyMap<String, Any?>(), MiniJson.parse("{}"))
        assertEquals(emptyList<Any?>(), MiniJson.parse(" [ ] "))
    }

    @Test
    fun `niepoprawny tekst konczy sie wyjatkiem a nie czymkolwiek innym`() {
        val broken = listOf(
            "", "   ", "<html>Bad gateway</html>", "{", "[1, 2", "{\"a\" 1}", "{\"a\": }", "{a: 1}", "[1,]x",
            "\"niezamkniety", "\"zly \\q\"", "\"\\u12\"", "nul", "tru", "-", "1.2.3", "{} {}", "[1] 2", "NaN", "1e999",
        )
        for (text in broken) {
            assertThrows(WeatherParseException::class.java, { MiniJson.parse(text) }, "tekst: $text")
        }
    }

    @Test
    fun `zbyt gleboko zagniezdzony tekst jest odrzucany zamiast przepelnic stos`() {
        val deep = "[".repeat(10_000) + "]".repeat(10_000)
        assertThrows(WeatherParseException::class.java) { MiniJson.parse(deep) }
    }

    @Test
    fun `znak sterujacy w napisie jest bledem`() {
        assertThrows(WeatherParseException::class.java) { MiniJson.parse("\"a\nb\"") }
    }
}
