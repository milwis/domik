package app.domik.integrations.weather

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Adresy zapytań (prywatność, HTTPS) i klient HTTP na lokalnym serwerze testowym (bez internetu). */
class OpenMeteoNetworkTest {
    private var server: HttpServer? = null

    @AfterEach
    fun stop() {
        server?.stop(0)
    }

    private fun query(url: String): Map<String, String> =
        url.substringAfter('?').split('&').associate { it.substringBefore('=') to it.substringAfter('=') }

    @Test
    fun `adres prognozy jest HTTPS i ma tylko zaokraglone wspolrzedne`() {
        val url = OpenMeteoUrls.forecast(50.0614312, 19.9365812)
        assertTrue(url.startsWith("https://api.open-meteo.com/v1/forecast?"), url)
        val params = query(url)
        assertEquals("50.06", params["latitude"])
        assertEquals("19.94", params["longitude"])
        assertEquals("auto", params["timezone"])
        assertEquals("celsius", params["temperature_unit"])
        assertEquals("kmh", params["wind_speed_unit"])
        assertFalse("50.0614" in url || "19.9365" in url, url)
    }

    @Test
    fun `adres prognozy zachowuje znak ujemnych wspolrzednych`() {
        val params = query(OpenMeteoUrls.forecast(-33.8688, -70.6483))
        assertEquals("-33.87", params["latitude"])
        assertEquals("-70.65", params["longitude"])
    }

    @Test
    fun `adres prognozy zadaje biezace godzinowe i dobowe pola`() {
        val params = query(OpenMeteoUrls.forecast(50.0, 19.0))
        val current = params["current"].orEmpty().split(',')
        for (field in listOf(
            "temperature_2m", "apparent_temperature", "relative_humidity_2m", "weather_code", "wind_speed_10m",
            "wind_gusts_10m", "is_day",
        )) {
            assertTrue(field in current, field)
        }
        val hourly = params["hourly"].orEmpty().split(',')
        for (field in listOf("temperature_2m", "precipitation_probability", "weather_code", "is_day")) {
            assertTrue(field in hourly, field)
        }
        val daily = params["daily"].orEmpty().split(',')
        for (field in listOf(
            "weather_code", "temperature_2m_max", "temperature_2m_min", "precipitation_probability_max",
            "precipitation_sum", "sunrise", "sunset", "uv_index_max", "wind_speed_10m_max",
        )) {
            assertTrue(field in daily, field)
        }
    }

    @Test
    fun `adres prognozy to jedno zapytanie na 14 dni i 48 godzin`() {
        val params = query(OpenMeteoUrls.forecast(50.0, 19.0))
        assertEquals("14", params["forecast_days"])
        assertEquals("48", params["forecast_hours"])
    }

    @Test
    fun `adres wyszukiwania koduje nazwe i przycina spacje`() {
        val url = OpenMeteoUrls.geocoding("  Nowy Sącz ", "pl")
        assertTrue(url.startsWith("https://geocoding-api.open-meteo.com/v1/search?"), url)
        val params = query(url)
        assertEquals("Nowy+S%C4%85cz", params["name"])
        assertEquals("pl", params["language"])
        assertEquals("10", params["count"])
        assertEquals("json", params["format"])
    }

    @Test
    fun `adres wyszukiwania nie przepuszcza dodatkowych parametrow z wpisanego tekstu`() {
        val params = query(OpenMeteoUrls.geocoding("Kraków&count=1000#x", "en"))
        assertEquals("10", params["count"])
        assertEquals("Krak%C3%B3w%26count%3D1000%23x", params["name"])
    }

    @Test
    fun `klient odrzuca zwykle HTTP bez laczenia sie`() {
        val client = UrlConnectionHttpGet()
        assertThrows(IOException::class.java) { client.get("http://api.open-meteo.com/v1/forecast") }
        assertThrows(IOException::class.java) { client.get("ftp://example.test/x") }
    }

    private fun start(handler: (com.sun.net.httpserver.HttpExchange) -> Unit): String {
        val created = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        created.createContext("/") { exchange ->
            try {
                handler(exchange)
            } finally {
                exchange.close()
            }
        }
        created.start()
        server = created
        return "http://127.0.0.1:${created.address.port}/x"
    }

    private fun com.sun.net.httpserver.HttpExchange.reply(status: Int, body: ByteArray) {
        sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) responseBody.use { it.write(body) }
    }

    @Test
    fun `klient zwraca tresc odpowiedzi 200 i przedstawia sie neutralnie`() {
        var userAgent: String? = null
        val url = start { exchange ->
            userAgent = exchange.requestHeaders.getFirst("User-Agent")
            exchange.reply(200, "{\"ok\": \"zażółć\"}".toByteArray(Charsets.UTF_8))
        }
        assertEquals("{\"ok\": \"zażółć\"}", UrlConnectionHttpGet(allowInsecureForTests = true).get(url))
        assertEquals("Domik", userAgent)
    }

    @Test
    fun `klient zglasza blad dla odpowiedzi innej niz 200`() {
        for (status in listOf(204, 404, 429, 500)) {
            val url = start { it.reply(status, if (status == 204) ByteArray(0) else "x".toByteArray()) }
            assertThrows(IOException::class.java, { UrlConnectionHttpGet(allowInsecureForTests = true).get(url) }, "HTTP $status")
            server?.stop(0)
        }
    }

    @Test
    fun `klient nie idzie za przekierowaniem`() {
        val url = start { exchange ->
            exchange.responseHeaders.add("Location", "http://127.0.0.1:1/inne")
            exchange.reply(302, ByteArray(0))
        }
        assertThrows(IOException::class.java) { UrlConnectionHttpGet(allowInsecureForTests = true).get(url) }
    }

    @Test
    fun `klient odrzuca zbyt duza odpowiedz`() {
        val url = start { it.reply(200, ByteArray(2_000)) }
        assertThrows(IOException::class.java) { UrlConnectionHttpGet(maxBytes = 1_000, allowInsecureForTests = true).get(url) }
        assertEquals(2_000, UrlConnectionHttpGet(maxBytes = 5_000, allowInsecureForTests = true).get(url).length)
    }

    @Test
    fun `klient konczy sie bledem gdy serwer nie odpowiada na czas`() {
        val url = start { exchange ->
            Thread.sleep(1_500)
            exchange.reply(200, "x".toByteArray())
        }
        assertThrows(IOException::class.java) {
            UrlConnectionHttpGet(readTimeoutMillis = 200, allowInsecureForTests = true).get(url)
        }
    }

    @Test
    fun `zrodlo Open-Meteo wola wlasciwe adresy`() = runBlocking {
        val requested = mutableListOf<String>()
        val source = OpenMeteoSource { url -> requested += url; "{}" }
        source.forecast(50.0614, 19.9366)
        source.search("Gdańsk", "pl")
        assertEquals(OpenMeteoUrls.forecast(50.0614, 19.9366), requested[0])
        assertEquals(OpenMeteoUrls.geocoding("Gdańsk", "pl"), requested[1])
    }
}
