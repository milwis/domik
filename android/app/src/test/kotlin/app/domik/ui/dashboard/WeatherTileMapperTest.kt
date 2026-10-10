package app.domik.ui.dashboard

import app.domik.integrations.weather.HourlyWeather
import app.domik.integrations.weather.WeatherKind
import app.domik.integrations.weather.OpenMeteoParser
import app.domik.integrations.weather.WeatherFixtures
import app.domik.ui.components.WeatherCondition
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WeatherTileMapperTest {
    private val zone = ZoneId.of("Europe/Warsaw")
    // Teksty podawane z zewnątrz (w aplikacji: z zasobów); tu skróty dni są jawne, więc test nie zależy od ustawień JVM.
    private val dayNames = listOf("pon.", "wt.", "śr.", "czw.", "pt.", "sob.", "niedz.")
    private val texts = WeatherTileTexts(
        today = "Dziś",
        stale = { "Nieaktualne · $it" },
        dayShort = { dayNames[it.ordinal] },
    )
    private val snapshot = OpenMeteoParser.parseForecast(WeatherFixtures.FORECAST)
    private val now = WeatherFixtures.NOW_MILLIS

    private fun map(
        snapshot: app.domik.integrations.weather.WeatherSnapshot = this.snapshot,
        fetchedAt: Long = now,
        nowMillis: Long = now,
    ) = WeatherTileMapper.map("Kraków", snapshot, fetchedAt, nowMillis, zone, texts)

    private fun dayName(day: DayOfWeek) = dayNames[day.ordinal]

    @Test
    fun `biezaca pogoda trafia na kafelek`() {
        val tile = map()
        assertEquals("Kraków", tile.city)
        assertEquals(WeatherCondition.PartlyCloudy, tile.condition)
        assertEquals(13.4f, tile.temperature)
        assertEquals(11.9f, tile.feelsLike)
        assertEquals(15, tile.windKmh)
        assertNull(tile.staleLabel)
    }

    @Test
    fun `min i maks to prognoza na dzis w miejscowosci`() {
        val tile = map()
        assertEquals(7.2f, tile.minTemp)
        assertEquals(14.1f, tile.maxTemp)
    }

    @Test
    fun `noc daje ikony nocne`() {
        val night = snapshot.copy(current = snapshot.current.copy(isDay = false, code = 0))
        assertEquals(WeatherCondition.ClearNight, map(night).condition)
        assertEquals(WeatherCondition.PartlyCloudyNight, map(snapshot.copy(current = snapshot.current.copy(isDay = false))).condition)
        // chmury zakrywające niebo wyglądają tak samo w dzień i w nocy
        assertEquals(WeatherCondition.Cloudy, map(snapshot.copy(current = snapshot.current.copy(isDay = false, code = 3))).condition)
    }

    @Test
    fun `nieznany kod pogody to ikona bez opisu a nie awaria`() {
        assertEquals(WeatherCondition.Unknown, map(snapshot.copy(current = snapshot.current.copy(code = 1234))).condition)
    }

    @Test
    fun `godziny sa kolejne co godzine od pierwszej pelnej godziny po teraz`() {
        val start = LocalDateTime.of(2026, 10, 10, 15, 0)
        val hours = List(48) { i ->
            val time = start.plusHours(i.toLong())
            HourlyWeather(time, temperature = 10.0 + i, code = 0, isDay = time.hour in 6..19, precipitationProbability = i)
        }
        val tile = map(snapshot.copy(hourly = hours))
        // kafelek dostaje dobę z góry; ile i co którą godzinę pokazać, wybiera sam wg szerokości (WeatherLayout.pickHours)
        assertEquals(WeatherTileMapper.MAX_HOURLY, tile.hourly.size)
        assertEquals(List(24) { "%02d:00".format((15 + it) % 24) }, tile.hourly.map { it.label })
        assertEquals(List(24) { 10f + it }, tile.hourly.map { it.temperature })
        assertEquals(List(24) { it }, tile.hourly.map { it.precipChance })
        assertEquals(WeatherCondition.Clear, tile.hourly[0].condition)
        assertEquals(WeatherCondition.ClearNight, tile.hourly[6].condition)
    }

    @Test
    fun `godziny ktore juz minely nie trafiaja na kafelek`() {
        val tile = map()
        // 13:00 i 14:00 już minęły; 16:00 nie ma temperatury (luka w odpowiedzi), więc parser je pominął
        assertEquals(listOf("15:00", "17:00", "18:00"), tile.hourly.map { it.label })
    }

    @Test
    fun `dni zaczynaja sie od Dzis a dalej sa skrotami dni tygodnia`() {
        val tile = map()
        assertEquals(3, tile.daily.size)
        assertEquals("Dziś", tile.daily[0].label)
        assertEquals("niedz.", tile.daily[1].label)
        assertEquals("pon.", tile.daily[2].label)
        assertEquals(dayName(LocalDate.of(2026, 10, 11).dayOfWeek), tile.daily[1].label)
        assertEquals(dayName(LocalDate.of(2026, 10, 12).dayOfWeek), tile.daily[2].label)
        assertEquals(WeatherCondition.Rain, tile.daily[1].condition)
        assertEquals(WeatherCondition.Thunderstorm, tile.daily[2].condition)
        assertEquals(12.0f, tile.daily[1].maxTemp)
        assertNull(tile.daily[2].maxTemp)
        assertEquals(5.0f, tile.daily[2].minTemp)
    }

    @Test
    fun `dzien miejscowosci a nie urzadzenia decyduje o Dzis`() {
        // 14 h przesunięcia: w miejscowości jest już 11 października (02:20)
        val far = snapshot.copy(utcOffsetSeconds = 14 * 3600)
        val tile = map(far)
        assertEquals("Dziś", tile.daily.first().label)
        assertEquals(12.0f, tile.maxTemp)
        assertEquals(2, tile.daily.size)
    }

    @Test
    fun `po uplywie dni prognoza dobowa znika a nie pokazuje przeszlosci`() {
        val later = now + 4 * 24 * 3_600_000L
        val tile = map(nowMillis = later, fetchedAt = later)
        assertTrue(tile.daily.isEmpty())
        assertNull(tile.minTemp)
        assertNull(tile.maxTemp)
        assertTrue(tile.hourly.isEmpty())
    }

    @Test
    fun `dane do 45 minut nie sa oznaczone`() {
        assertNull(map(fetchedAt = now - 44 * 60_000L).staleLabel)
        assertNull(map(fetchedAt = now - 45 * 60_000L).staleLabel)
    }

    @Test
    fun `swiezo pobrane dane nie sa oznaczone gdy zegar interfejsu jest zaokraglony do minuty`() {
        // interfejs liczy „teraz” z dokładnością do minuty, więc pobranie bywa kilkadziesiąt sekund „po” tej chwili
        assertNull(map(fetchedAt = now + 45_000L).staleLabel)
    }

    @Test
    fun `starsze dane maja napis z godzina aktualizacji w strefie urzadzenia`() {
        // teraz 14:20 lokalnie, pobrane 46 minut wcześniej = 13:34
        assertEquals("Nieaktualne · 13:34", map(fetchedAt = now - 46 * 60_000L).staleLabel)
    }

    @Test
    fun `dane z poprzedniego dnia maja napis z data`() {
        // 30 godzin wcześniej: 9.10 08:20 czasu warszawskiego
        assertEquals("Nieaktualne · 9.10 08:20", map(fetchedAt = now - 30 * 3_600_000L).staleLabel)
    }

    @Test
    fun `wiatr jest zaokraglany do pelnych kilometrow na godzine`() {
        val calm = snapshot.copy(current = snapshot.current.copy(windKmh = 0.4))
        assertEquals(0, map(calm).windKmh)
        assertNull(map(snapshot.copy(current = snapshot.current.copy(windKmh = null))).windKmh)
    }

    @Test
    fun `pogoda przykladowa trybu demo przechodzi przez ten sam mapper`() {
        val moment = LocalDateTime.of(2026, 10, 10, 9, 41)
        val millis = DemoWeather.nowMillis(moment)
        val tile = WeatherTileMapper.map(
            "Warszawa", DemoWeather.snapshot(moment), millis, millis, java.time.ZoneOffset.UTC, texts,
        )
        assertEquals("Warszawa", tile.city)
        assertEquals(14f, tile.temperature)
        assertNull(tile.staleLabel)
        assertEquals(List(24) { "%02d:00".format((10 + it) % 24) }, tile.hourly.map { it.label })
        assertEquals(WeatherTileMapper.MAX_DAILY, tile.daily.size)
        assertEquals("Dziś", tile.daily.first().label)
        assertTrue(tile.minTemp != null && tile.maxTemp != null)
        // demo ma komplet pól dodatkowych, żeby każdy wybór w oknie „co pokazywać” dało widoczny efekt
        assertEquals(24, tile.windGustsKmh)
        assertEquals(62, tile.humidityPercent)
        assertEquals("06:12", tile.sunrise)
        assertEquals("19:48", tile.sunset)
        assertEquals(40, tile.precipChanceToday)
        assertTrue(tile.uvIndex != null && tile.precipSumMm != null)
        assertTrue(tile.hourly.all { it.precipChance != null })
        assertTrue(tile.daily.all { it.precipChance != null })
    }

    @Test
    fun `pogoda przykladowa w nocy ma ikone nocna`() {
        val moment = LocalDateTime.of(2026, 10, 10, 23, 30)
        val millis = DemoWeather.nowMillis(moment)
        val tile = WeatherTileMapper.map(
            "Warszawa", DemoWeather.snapshot(moment), millis, millis, java.time.ZoneOffset.UTC, texts,
        )
        assertEquals(WeatherCondition.PartlyCloudyNight, tile.condition)
        assertEquals("00:00", tile.hourly.first().label)
        assertEquals(WeatherCondition.Cloudy, tile.hourly.first().condition)
    }

    @Test
    fun `rozszerzone pola prawdziwej odpowiedzi trafiaja na kafelek`() {
        val extended = OpenMeteoParser.parseForecast(
            javaClass.getResource("/weather/open_meteo_forecast_extended.json")!!.readText(Charsets.UTF_8),
        )
        // Kraków, 16:10 czasu lokalnego (UTC+2)
        val moment = java.time.Instant.parse("2026-10-10T14:10:00Z").toEpochMilli()
        val tile = WeatherTileMapper.map("Kraków", extended, moment, moment, zone, texts)
        assertEquals(13, tile.windKmh)
        assertEquals(28, tile.windGustsKmh)
        assertEquals(53, tile.humidityPercent)
        assertEquals(2.85f, tile.uvIndex)
        assertEquals("06:53", tile.sunrise)
        assertEquals("17:59", tile.sunset)
        assertEquals(0.4f, tile.precipSumMm)
        assertEquals(35, tile.precipChanceToday)
        assertEquals(14, tile.daily.size)
        assertEquals(65, tile.daily[1].precipChance)
        assertEquals("17:00", tile.hourly.first().label)
        assertEquals(24, tile.hourly.size)
        assertEquals(11, tile.hourly.single { it.label == "18:00" }.precipChance)
    }

    @Test
    fun `brak pol dodatkowych w starej odpowiedzi to puste pola a nie awaria`() {
        val tile = map()
        assertNull(tile.windGustsKmh)
        assertNull(tile.humidityPercent)
        assertNull(tile.uvIndex)
        assertNull(tile.sunrise)
        assertNull(tile.sunset)
        assertNull(tile.precipSumMm)
        assertNull(tile.precipChanceToday)
        assertNull(tile.hourly.first().precipChance)
        assertNull(tile.daily.first().precipChance)
    }

    @Test
    fun `kazda znana grupa pogody ma wlasny opis w dzien i w nocy`() {
        for (kind in WeatherKind.entries) {
            val day = WeatherCondition.of(kind, isDay = true)
            val night = WeatherCondition.of(kind, isDay = false)
            val unknown = kind == WeatherKind.Unknown
            assertEquals(unknown, day == WeatherCondition.Unknown, "dzień: $kind")
            assertEquals(unknown, night == WeatherCondition.Unknown, "noc: $kind")
        }
    }

    @Test
    fun `slonce i polsloneczne niebo w nocy maja ikone ksiezyca`() {
        for (kind in listOf(
            WeatherKind.Clear,
            WeatherKind.MostlyClear,
            WeatherKind.PartlyCloudy,
        )) {
            assertTrue(WeatherCondition.of(kind, true).iconRes != WeatherCondition.of(kind, false).iconRes, "$kind")
        }
        // zachmurzenie całkowite i opady nie zależą od pory doby
        assertEquals(
            WeatherCondition.of(WeatherKind.Rain, true),
            WeatherCondition.of(WeatherKind.Rain, false),
        )
    }
}
