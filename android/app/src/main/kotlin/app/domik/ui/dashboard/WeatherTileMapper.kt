package app.domik.ui.dashboard

import app.domik.integrations.weather.WeatherKind
import app.domik.integrations.weather.WeatherPolicy
import app.domik.integrations.weather.WeatherSnapshot
import app.domik.ui.components.WeatherCondition
import app.domik.ui.components.WeatherDayState
import app.domik.ui.components.WeatherHourState
import app.domik.ui.components.WeatherTileState
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Teksty potrzebne do zbudowania stanu kafelka, podane z zewnątrz, żeby mapper nie zależał od Androida
 * (testy JVM): [today] to etykieta pierwszego dnia („Dziś”), [stale] składa napis „Nieaktualne · 12:40”,
 * a [dayShort] daje skrót nazwy dnia tygodnia („pon.”).
 * Wszystkie teksty pochodzą z zasobów aplikacji (values = polski, values-en = angielski), żaden nie jest wpisany
 * w kod ani wzięty z odpowiedzi serwisu.
 */
class WeatherTileTexts(
    val today: String,
    val stale: (String) -> String,
    val dayShort: (DayOfWeek) -> String,
)

/**
 * Zamienia prognozę ([WeatherSnapshot]) na stan kafelka ([WeatherTileState]). Ta sama funkcja zasila prawdziwą
 * pogodę i dane przykładowe trybu demo, więc oba wyglądają jednakowo.
 *
 * Wszystko liczone względem chwili [nowMillis]: najbliższe godziny (po kolei, co godzinę, od pierwszej pełnej
 * godziny po „teraz” – kafelek sam wybiera, ile i co którą pokazać, patrz `WeatherLayout.pickHours`), kolejne dni
 * od dzisiejszego (w miejscowości, nie w strefie urządzenia) i napis o nieaktualnych danych ([WeatherPolicy.isStale]
 * względem [fetchedAtMillis]). Wschód i zachód słońca, indeks UV, suma i szansa opadów dotyczą dzisiejszego dnia
 * w miejscowości.
 */
object WeatherTileMapper {
    /** Tyle kolejnych godzin przekazujemy kafelkowi (największy pasek: 14 pozycji co 1–3 godziny pokrywa ≤ 14 godzin). */
    const val MAX_HOURLY = 24

    /** Kolejne dni prognozy (tyle, ile prosimy w [app.domik.integrations.weather.OpenMeteoUrls.FORECAST_DAYS]). */
    const val MAX_DAILY = 14

    private val hourFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    private val dateTimeFormat = DateTimeFormatter.ofPattern("d.MM HH:mm", Locale.ROOT)

    fun map(
        city: String,
        snapshot: WeatherSnapshot,
        fetchedAtMillis: Long,
        nowMillis: Long,
        zone: ZoneId,
        texts: WeatherTileTexts,
    ): WeatherTileState {
        val current = snapshot.current
        val placeToday = snapshot.localNow(nowMillis).toLocalDate()
        val days = snapshot.upcomingDays(nowMillis, MAX_DAILY)
        val todayForecast = days.firstOrNull { it.date == placeToday }
        return WeatherTileState(
            condition = WeatherCondition.of(WeatherKind.fromWmo(current.code), current.isDay),
            temperature = current.temperature.toFloat(),
            city = city,
            minTemp = todayForecast?.minTemperature?.toFloat(),
            maxTemp = todayForecast?.maxTemperature?.toFloat(),
            feelsLike = current.apparentTemperature?.toFloat(),
            windKmh = current.windKmh?.roundToInt(),
            windGustsKmh = current.windGustsKmh?.roundToInt(),
            humidityPercent = current.relativeHumidity,
            uvIndex = todayForecast?.uvIndexMax?.toFloat(),
            sunrise = todayForecast?.sunrise?.format(hourFormat),
            sunset = todayForecast?.sunset?.format(hourFormat),
            precipSumMm = todayForecast?.precipitationSum?.toFloat(),
            precipChanceToday = todayForecast?.precipitationProbability,
            hourly = snapshot.upcomingHours(nowMillis, MAX_HOURLY).map { hour ->
                WeatherHourState(
                    label = hour.time.format(hourFormat),
                    condition = WeatherCondition.of(WeatherKind.fromWmo(hour.code), hour.isDay),
                    temperature = hour.temperature.toFloat(),
                    precipChance = hour.precipitationProbability,
                )
            },
            daily = days.map { day ->
                WeatherDayState(
                    label = if (day.date == placeToday) {
                        texts.today
                    } else {
                        texts.dayShort(day.date.dayOfWeek)
                    },
                    // Dzień w prognozie dobowej: ikona dzienna (bez rozróżniania pory doby).
                    condition = WeatherCondition.of(WeatherKind.fromWmo(day.code), isDay = true),
                    minTemp = day.minTemperature?.toFloat(),
                    maxTemp = day.maxTemperature?.toFloat(),
                    precipChance = day.precipitationProbability,
                )
            },
            staleLabel = if (WeatherPolicy.isStale(fetchedAtMillis, nowMillis)) {
                texts.stale(updateTimeLabel(fetchedAtMillis, nowMillis, zone))
            } else {
                null
            },
        )
    }

    /** Godzina ostatniej aktualizacji; z datą, gdy była innego dnia niż „teraz” (w strefie urządzenia). */
    internal fun updateTimeLabel(fetchedAtMillis: Long, nowMillis: Long, zone: ZoneId): String {
        val fetched = Instant.ofEpochMilli(fetchedAtMillis).atZone(zone)
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        return if (fetched.toLocalDate() == now.toLocalDate()) {
            fetched.format(hourFormat)
        } else {
            fetched.format(dateTimeFormat)
        }
    }
}
