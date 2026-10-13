package app.domik.integrations.emodul

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EmodulKeyTest {
    private val now = 1_800_000_000_000L
    private val valid = EmodulFixtures.jwt("""{"username":"test","user_id":4242,"is_generated":true,"iat":1700000000}""")

    private fun parse(input: String) = EmodulKey.parse(input, now)

    @Test
    fun `poprawny klucz daje user_id z payloadu`() {
        val result = parse(valid)
        assertTrue(result is EmodulKeyResult.Valid)
        val key = (result as EmodulKeyResult.Valid).key
        assertEquals(4242L, key.userId)
        assertEquals(valid, key.token)
    }

    @Test
    fun `przedrostek Bearer i biale znaki sa usuwane`() {
        val key = (parse("  Bearer   $valid \n") as EmodulKeyResult.Valid).key
        assertEquals(valid, key.token)
        assertEquals(valid, (parse("bearer $valid") as EmodulKeyResult.Valid).key.token)
    }

    @Test
    fun `za malo czesci albo znaki spoza base64url to Malformed`() {
        assertEquals(EmodulKeyResult.Malformed, parse(valid.substringBeforeLast('.')))
        assertEquals(EmodulKeyResult.Malformed, parse("$valid.extra"))
        assertEquals(EmodulKeyResult.Malformed, parse(valid.replace('a', '+')))
        assertEquals(EmodulKeyResult.Malformed, parse("a b.$valid"))
        assertEquals(EmodulKeyResult.Malformed, parse(""))
        assertEquals(EmodulKeyResult.Malformed, parse("a.b.c"))
    }

    @Test
    fun `za dlugi klucz to Malformed`() {
        assertEquals(EmodulKeyResult.Malformed, parse("a".repeat(EmodulKey.MAX_LENGTH) + ".bbbbbbbbbb.c"))
    }

    @Test
    fun `payload bez JSON albo bez poprawnego user_id to Malformed`() {
        assertEquals(EmodulKeyResult.Malformed, parse(EmodulFixtures.jwt("to nie jest json")))
        assertEquals(EmodulKeyResult.Malformed, parse(EmodulFixtures.jwt("""[1,2,3]""")))
        assertEquals(EmodulKeyResult.Malformed, parse(EmodulFixtures.jwt("""{"username":"test","iat":1}""")))
        assertEquals(EmodulKeyResult.Malformed, parse(EmodulFixtures.jwt("""{"user_id":0,"iat":1111111111}""")))
        assertEquals(EmodulKeyResult.Malformed, parse(EmodulFixtures.jwt("""{"user_id":-5,"iat":1111111111}""")))
        assertEquals(EmodulKeyResult.Malformed, parse(EmodulFixtures.jwt("""{"user_id":12.5,"iat":1111111111}""")))
        assertEquals(EmodulKeyResult.Malformed, parse(EmodulFixtures.jwt("""{"user_id":"4242","iat":1111111111}""")))
    }

    @Test
    fun `exp w przeszlosci to Expired a w przyszlosci jest ok`() {
        assertEquals(EmodulKeyResult.Expired, parse(EmodulFixtures.jwt("""{"user_id":4242,"exp":1700000000}""")))
        assertTrue(parse(EmodulFixtures.jwt("""{"user_id":4242,"exp":1900000000}""")) is EmodulKeyResult.Valid)
    }

    @Test
    fun `toString i masked nie zawieraja calego klucza`() {
        val key = (parse(valid) as EmodulKeyResult.Valid).key
        assertEquals("EmodulKey(<ukryty>)", key.toString())
        assertFalse(key.toString().contains(valid))
        assertEquals("…" + valid.takeLast(4), key.masked)
        assertFalse(key.masked.contains(valid.take(10)))
        assertFalse(EmodulKeyResult.Valid(key).toString().contains(valid))
    }

    @Test
    fun `klucz zlamany na wiersze albo ze spacjami w srodku jest sklejany`() {
        val wrapped = valid.chunked(40).joinToString("\r\n") + "\n"
        assertEquals(valid, (parse(wrapped) as EmodulKeyResult.Valid).key.token)
        assertEquals(valid, (parse("\t" + valid.replaceFirst(".", " . ") + " ") as EmodulKeyResult.Valid).key.token)
        assertEquals(valid, (parse("Bearer\n$valid\n") as EmodulKeyResult.Valid).key.token)
    }

    @Test
    fun `niepelny klucz (ucieta koncowka albo sam naglowek) nie jest poprawny lokalnie`() {
        assertTrue(parse(valid.take(valid.length / 2)) !is EmodulKeyResult.Valid)
        assertTrue(parse(valid.substringBefore('.')) !is EmodulKeyResult.Valid)
        assertTrue(parse(valid.substringBeforeLast('.') + ".") is EmodulKeyResult.Valid) // pusty podpis dopuszcza kształt JWT
        assertTrue(parse("   \n ") !is EmodulKeyResult.Valid)
    }
}
