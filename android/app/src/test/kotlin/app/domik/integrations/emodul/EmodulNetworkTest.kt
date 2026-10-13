package app.domik.integrations.emodul

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Klient HTTP i `EmodulCloudApi` na lokalnym serwerze testowym (bez internetu, bez prawdziwego klucza). */
class EmodulNetworkTest {
    private var server: HttpServer? = null

    private class Seen(val method: String, val path: String, val authorization: String?, val accept: String?, val userAgent: String?)

    private val seen = mutableListOf<Seen>()
    private val udid = "0123456789abcdef0123456789abcdef"
    private val token = EmodulFixtures.jwt("""{"username":"ktos","user_id":4242,"is_generated":true}""")
    private val key = (EmodulKey.parse(token) as EmodulKeyResult.Valid).key

    @AfterEach
    fun stop() {
        server?.stop(0)
    }

    private fun start(handler: (HttpExchange) -> Unit): String {
        val created = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        created.createContext("/") { exchange ->
            try {
                seen += Seen(
                    exchange.requestMethod,
                    exchange.requestURI.toString(),
                    exchange.requestHeaders.getFirst("Authorization"),
                    exchange.requestHeaders.getFirst("Accept"),
                    exchange.requestHeaders.getFirst("User-Agent"),
                )
                handler(exchange)
            } finally {
                exchange.close()
            }
        }
        created.start()
        server = created
        return "http://127.0.0.1:${created.address.port}/api/v1/"
    }

    private fun HttpExchange.reply(status: Int, body: ByteArray) {
        sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) responseBody.use { it.write(body) }
    }

    private fun HttpExchange.reply(status: Int, body: String = "") = reply(status, body.toByteArray(Charsets.UTF_8))

    private val http = UrlConnectionEmodulHttp(allowInsecureForTests = true)

    @Test
    fun `zadanie ma naglowki Authorization Accept i User-Agent oraz metode GET`() {
        val base = start { it.reply(200, "{\"ok\": \"zażółć\"}") }
        assertEquals("{\"ok\": \"zażółć\"}", http.get(base + "x", token, 1_000))
        val request = seen.single()
        assertEquals("GET", request.method)
        assertEquals("Bearer $token", request.authorization)
        assertEquals("application/json", request.accept)
        assertEquals("Domik", request.userAgent)
    }

    @Test
    fun `status 401 i 403 to EmodulHttpException z kodem`() {
        for (status in listOf(401, 403, 404, 500, 503)) {
            val base = start { it.reply(status, "{\"error\":\"nie\"}") }
            val error = assertThrows(EmodulHttpException::class.java) { http.get(base + "x", token, 1_000) }
            assertEquals(status, error.status)
            assertNull(error.retryAfterSeconds)
            server?.stop(0)
        }
    }

    @Test
    fun `status 429 przenosi Retry-After w sekundach`() {
        val base = start { exchange ->
            exchange.responseHeaders.add("Retry-After", "120")
            exchange.reply(429, "{\"error\":\"za duzo\"}")
        }
        val error = assertThrows(EmodulHttpException::class.java) { http.get(base + "x", token, 1_000) }
        assertEquals(429, error.status)
        assertEquals(120L, error.retryAfterSeconds)
    }

    @Test
    fun `Retry-After inny niz liczba jest pomijany`() {
        val base = start { exchange ->
            exchange.responseHeaders.add("Retry-After", "Wed, 21 Oct 2026 07:28:00 GMT")
            exchange.reply(429)
        }
        assertNull(assertThrows(EmodulHttpException::class.java) { http.get(base + "x", token, 1_000) }.retryAfterSeconds)
    }

    @Test
    fun `przekierowanie 302 nie jest sledzone`() {
        val base = start { exchange ->
            exchange.responseHeaders.add("Location", "http://127.0.0.1:1/inne")
            exchange.reply(302)
        }
        val error = assertThrows(EmodulHttpException::class.java) { http.get(base + "x", token, 1_000) }
        assertEquals(302, error.status)
        assertEquals(1, seen.size)
    }

    @Test
    fun `odpowiedz wieksza niz limit konczy sie bledem`() {
        val base = start { it.reply(200, ByteArray(2_000)) }
        assertThrows(EmodulResponseTooLargeException::class.java) { http.get(base + "x", token, 1_000) }
        assertEquals(2_000, http.get(base + "x", token, 5_000).length)
    }

    @Test
    fun `tresc bledu jest odrzucana i nie trafia do wyjatku`() {
        val base = start { it.reply(500, "tajne-dane-konta ".repeat(10_000)) }
        val error = assertThrows(EmodulHttpException::class.java) { http.get(base + "x", token, 1_000) }
        assertFalse("tajne" in error.message.orEmpty())
    }

    @Test
    fun `zwykle HTTP bez flagi testowej jest odrzucane bez laczenia sie`() {
        val client = UrlConnectionEmodulHttp()
        assertThrows(IOException::class.java) { client.get("http://emodul.eu/api/v1/users/1/modules", token, 1_000) }
        assertThrows(IOException::class.java) { client.get("ftp://example.test/x", token, 1_000) }
        assertThrows(IOException::class.java) { client.get("to nie jest adres", token, 1_000) }
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `klucz z niedozwolonymi znakami jest odrzucany przed polaczeniem`() {
        val base = start { it.reply(200, "{}") }
        for (bad in listOf("", "abc\r\nX-Evil: 1", "a b")) {
            assertThrows(IllegalArgumentException::class.java) { http.get(base + "x", bad, 1_000) }
        }
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `serwer ktory nie odpowiada na czas daje blad bez adresu w komunikacie`() {
        val base = start { exchange ->
            Thread.sleep(1_500)
            exchange.reply(200, "x")
        }
        val error = assertThrows(IOException::class.java) {
            UrlConnectionEmodulHttp(readTimeoutMillis = 200, allowInsecureForTests = true).get(base + "x", token, 1_000)
        }
        assertFalse("127.0.0.1" in error.message.orEmpty())
        assertNull(error.cause)
    }

    @Test
    fun `brak serwera daje blad sieci bez adresu i klucza w komunikacie`() {
        val error = assertThrows(IOException::class.java) {
            UrlConnectionEmodulHttp(connectTimeoutMillis = 500, allowInsecureForTests = true)
                .get("http://127.0.0.1:1/api/v1/users/4242/modules/$udid", token, 1_000)
        }
        val message = error.message.orEmpty()
        assertFalse("127.0.0.1" in message || "4242" in message || udid in message || token in message, message)
    }

    @Test
    fun `EmodulCloudApi wola wlasciwe adresy z kluczem Bearer`() {
        val base = start { it.reply(200, "{}") }
        val api = EmodulCloudApi(http, base)
        api.modules(key)
        api.module(key, udid)
        api.menu(key, udid, MenuType.MU)
        api.menu(key, udid, MenuType.MI)
        api.i18n(key, "en")
        api.i18n(key, "xx")
        assertEquals(
            listOf(
                "/api/v1/users/4242/modules",
                "/api/v1/users/4242/modules/$udid",
                "/api/v1/users/4242/modules/$udid/menu/MU/",
                "/api/v1/users/4242/modules/$udid/menu/MI/",
                "/api/v1/i18n/en",
                "/api/v1/i18n/pl",
            ),
            seen.map { it.path },
        )
        assertTrue(seen.all { it.method == "GET" && it.authorization == "Bearer $token" })
    }

    @Test
    fun `EmodulCloudApi przekazuje limity rozmiaru wedlug rodzaju odpowiedzi`() {
        val limits = mutableListOf<Pair<String, Int>>()
        val api = EmodulCloudApi({ url, _, max -> limits += url.substringAfter("/v1/") to max; "{}" }, "https://example.test/v1/")
        api.modules(key)
        api.module(key, udid)
        api.menu(key, udid, MenuType.MI)
        api.i18n(key, "pl")
        assertEquals(listOf(1, 1, 1, 3).map { it * 1024 * 1024 }, limits.map { it.second })
        assertEquals(3 * 1024 * 1024, EmodulApi.Limits.I18N)
    }

    @Test
    fun `EmodulCloudApi odrzuca niepoprawny udid przed wyslaniem zadania`() {
        val api = EmodulCloudApi({ _, _, _ -> error("nie powinno dojsc do sieci") }, "https://example.test/v1/")
        val error = assertThrows(IllegalArgumentException::class.java) { api.module(key, "../x?y") }
        assertFalse("x?y" in error.message.orEmpty())
    }

    @Test
    fun `bledy HTTP nie zawieraja klucza uid ani udid`() {
        val base = start { it.reply(401, "{\"error\":\"x\"}") }
        val api = EmodulCloudApi(http, base)
        val errors = listOf<() -> Unit>(
            { api.modules(key) },
            { api.module(key, udid) },
            { api.menu(key, udid, MenuType.MU) },
            { api.i18n(key, "pl") },
        ).map { call -> assertThrows(EmodulHttpException::class.java) { call() } }
        for (error in errors) {
            val text = error.message.orEmpty() + error.toString()
            assertFalse(token in text || "4242" in text || udid in text, text)
            assertEquals(401, error.status)
        }
    }

    @Test
    fun `NoEmodulApi zawsze zglasza blad sieci`() {
        assertThrows(IOException::class.java) { NoEmodulApi.modules(key) }
        assertThrows(IOException::class.java) { NoEmodulApi.module(key, udid) }
        assertThrows(IOException::class.java) { NoEmodulApi.menu(key, udid, MenuType.MU) }
        assertThrows(IOException::class.java) { NoEmodulApi.i18n(key, "pl") }
    }
}
