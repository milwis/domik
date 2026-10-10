package app.domik.ui.dashboard

import app.domik.integrations.weather.CurrentWeather
import app.domik.integrations.weather.DailyWeather
import app.domik.integrations.weather.HourlyWeather
import app.domik.integrations.weather.WeatherSnapshot
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * Pogoda przykładowa **tylko dla trybu demo** (bez internetu): zmyślona, zakotwiczona w bieżącym czasie, żeby paski
 * godzin i dni zawsze zaczynały się od „teraz”. Przechodzi przez ten sam mapper co prawdziwa pogoda
 * ([WeatherTileMapper]), więc demo wygląda jak prawdziwy kafelek. Nie zawiera żadnych danych prawdziwego domu.
 */
object DemoWeather {
    // Nazwa miejscowości przykładowej pochodzi z zasobów (weather_demo_city), nie z kodu.

    /** Chwila „teraz” jako milisekundy; demo ma przesunięcie 0, więc czas lokalny = [now]. */
    fun nowMillis(now: LocalDateTime): Long = now.toInstant(ZoneOffset.UTC).toEpochMilli()

    /** Prognoza przykładowa wokół [now] (czas lokalny urządzenia, traktowany jak czas „miejscowości”). */
    fun snapshot(now: LocalDateTime): WeatherSnapshot {
        val firstHour = now.truncatedTo(ChronoUnit.HOURS).plusHours(1)
        val hourly = List(HOURS) { i ->
            val time = firstHour.plusHours(i.toLong())
            val chance = HOURLY_PRECIP[time.hour]
            HourlyWeather(
                time = time,
                temperature = temperatureAt(time.hour),
                code = when {
                    chance >= 50 -> CODE_RAIN
                    time.hour in 11..16 -> CODE_PARTLY_CLOUDY
                    else -> CODE_OVERCAST
                },
                isDay = time.hour in DAY_HOURS,
                precipitationProbability = chance,
            )
        }
        val today = now.toLocalDate()
        val daily = List(DAYS) { i ->
            val date = today.plusDays(i.toLong())
            DailyWeather(
                date = date,
                minTemperature = 8.0 - (i % 3),
                maxTemperature = 16.0 - (i % 4),
                code = DAY_CODES[i % DAY_CODES.size],
                precipitationProbability = DAY_PRECIP[i % DAY_PRECIP.size],
                precipitationSum = DAY_PRECIP_SUM[i % DAY_PRECIP_SUM.size],
                sunrise = date.atTime(SUNRISE),
                sunset = date.atTime(SUNSET),
                uvIndexMax = DAY_UV[i % DAY_UV.size],
                windMaxKmh = 22.0 + (i % 3) * 4,
            )
        }
        return WeatherSnapshot(
            utcOffsetSeconds = 0,
            current = CurrentWeather(
                time = now,
                temperature = 14.0,
                apparentTemperature = 12.0,
                code = CODE_PARTLY_CLOUDY,
                isDay = now.hour in DAY_HOURS,
                windKmh = 11.0,
                windGustsKmh = 24.0,
                relativeHumidity = 62,
            ),
            hourly = hourly,
            daily = daily,
        )
    }

    /** Łagodna krzywa dobowa 9–16 °C (najcieplej około 15:00). */
    private fun temperatureAt(hour: Int): Double {
        val distance = kotlin.math.abs(hour - WARMEST_HOUR).let { if (it > 12) 24 - it else it }
        return 16.0 - distance * 0.6
    }

    private const val HOURS = 24
    private const val DAYS = 14
    private const val WARMEST_HOUR = 15
    private const val CODE_PARTLY_CLOUDY = 2
    private const val CODE_OVERCAST = 3
    private const val CODE_RAIN = 61
    private val DAY_HOURS = 6..19
    private val SUNRISE = LocalTime.of(6, 12)
    private val SUNSET = LocalTime.of(19, 48)
    private val DAY_CODES = listOf(2, 61, 3, 0, 80, 71, 1)

    /** Szansa opadów (%) wg godziny doby (indeks = godzina): przelotny deszcz po południu. */
    private val HOURLY_PRECIP = listOf(0, 0, 0, 0, 0, 5, 5, 10, 10, 10, 15, 20, 30, 40, 55, 65, 50, 35, 20, 10, 5, 5, 0, 0)
    private val DAY_PRECIP = listOf(40, 70, 35, 5, 55, 80, 10)
    private val DAY_PRECIP_SUM = listOf(0.4, 4.6, 0.8, 0.0, 2.1, 6.3, 0.0)
    private val DAY_UV = listOf(3.0, 2.0, 2.0, 4.0, 2.0, 1.0, 3.0)
}
