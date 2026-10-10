package app.domik.ui.components

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import app.domik.R
import app.domik.integrations.weather.WeatherKind

/** Warunki pogodowe do wyświetlenia (ikona + opis); warianty „Night” to ten sam opis z ikoną nocy. */
enum class WeatherCondition(@DrawableRes val iconRes: Int, @StringRes val labelRes: Int) {
    Clear(R.drawable.ic_sun, R.string.weather_clear),
    ClearNight(R.drawable.ic_moon, R.string.weather_clear_night),
    MostlyClear(R.drawable.ic_sun, R.string.weather_mostly_clear),
    MostlyClearNight(R.drawable.ic_moon, R.string.weather_mostly_clear_night),
    PartlyCloudy(R.drawable.ic_cloud_sun, R.string.weather_partly_cloudy),
    PartlyCloudyNight(R.drawable.ic_cloud_moon, R.string.weather_partly_cloudy),
    Cloudy(R.drawable.ic_cloud, R.string.weather_cloudy),
    Fog(R.drawable.ic_fog, R.string.weather_fog),
    Drizzle(R.drawable.ic_rain, R.string.weather_drizzle),
    FreezingDrizzle(R.drawable.ic_rain, R.string.weather_freezing_drizzle),
    Rain(R.drawable.ic_rain, R.string.weather_rain),
    HeavyRain(R.drawable.ic_rain, R.string.weather_heavy_rain),
    FreezingRain(R.drawable.ic_rain, R.string.weather_freezing_rain),
    RainShowers(R.drawable.ic_rain, R.string.weather_rain_showers),
    HeavyRainShowers(R.drawable.ic_rain, R.string.weather_heavy_rain_showers),
    Snow(R.drawable.ic_snow, R.string.weather_snow),
    HeavySnow(R.drawable.ic_snow, R.string.weather_heavy_snow),
    SnowGrains(R.drawable.ic_snow, R.string.weather_snow_grains),
    SnowShowers(R.drawable.ic_snow, R.string.weather_snow_showers),
    Thunderstorm(R.drawable.ic_thunderstorm, R.string.weather_thunderstorm),
    ThunderstormHail(R.drawable.ic_thunderstorm, R.string.weather_thunderstorm_hail),
    Unknown(R.drawable.ic_cloud, R.string.weather_unknown),
    ;

    companion object {
        /** Grupa pogody wg kodu WMO i pora doby → ikona i opis. */
        fun of(kind: WeatherKind, isDay: Boolean): WeatherCondition = when (kind) {
            WeatherKind.Clear -> if (isDay) Clear else ClearNight
            WeatherKind.MostlyClear -> if (isDay) MostlyClear else MostlyClearNight
            WeatherKind.PartlyCloudy -> if (isDay) PartlyCloudy else PartlyCloudyNight
            WeatherKind.Overcast -> Cloudy
            WeatherKind.Fog -> Fog
            WeatherKind.Drizzle -> Drizzle
            WeatherKind.FreezingDrizzle -> FreezingDrizzle
            WeatherKind.Rain -> Rain
            WeatherKind.HeavyRain -> HeavyRain
            WeatherKind.FreezingRain -> FreezingRain
            WeatherKind.RainShowers -> RainShowers
            WeatherKind.HeavyRainShowers -> HeavyRainShowers
            WeatherKind.Snow -> Snow
            WeatherKind.HeavySnow -> HeavySnow
            WeatherKind.SnowGrains -> SnowGrains
            WeatherKind.SnowShowers -> SnowShowers
            WeatherKind.Thunderstorm -> Thunderstorm
            WeatherKind.ThunderstormHail -> ThunderstormHail
            WeatherKind.Unknown -> Unknown
        }
    }
}

/**
 * Jedna godzina prognozy; [label] to gotowa godzina („15:00”). [precipChance] – szansa opadów w procentach
 * (null = brak danych).
 */
@Immutable
data class WeatherHourState(
    val label: String,
    val condition: WeatherCondition,
    val temperature: Float,
    val precipChance: Int? = null,
)

/**
 * Jeden dzień prognozy; [label] to gotowa etykieta („Dziś”, „pt.”). [precipChance] – największa szansa opadów
 * w ciągu doby (%).
 */
@Immutable
data class WeatherDayState(
    val label: String,
    val condition: WeatherCondition,
    val minTemp: Float?,
    val maxTemp: Float?,
    val precipChance: Int? = null,
)

/**
 * Dane kafelka pogody (jedno źródło dla trybu demo i dla prawdziwej pogody – patrz `WeatherTileMapper`).
 * [staleLabel] != null oznacza dane nieaktualne („Nieaktualne · 12:40”). Pola opcjonalne mogą być puste –
 * kafelek pokazuje tylko to, co ma, co użytkownik włączył ([app.domik.integrations.weather.WeatherOptions]) i co
 * mieści się w jego rozmiarze ([WeatherLayout]).
 *
 * [hourly] to kolejne pełne godziny (co godzinę, najbliższe ~24), [daily] – kolejne dni od dzisiejszego (do 14);
 * kafelek sam wybiera, ile z nich pokazać. [sunrise] / [sunset] to gotowe godziny („06:53”), [precipChanceToday] –
 * szansa opadów dzisiaj (%), [precipSumMm] – suma opadów dzisiaj, [uvIndex] – największy indeks UV dzisiaj.
 */
@Immutable
data class WeatherTileState(
    val condition: WeatherCondition,
    val temperature: Float,
    val city: String,
    val minTemp: Float? = null,
    val maxTemp: Float? = null,
    val feelsLike: Float? = null,
    val windKmh: Int? = null,
    val windGustsKmh: Int? = null,
    val humidityPercent: Int? = null,
    val uvIndex: Float? = null,
    val sunrise: String? = null,
    val sunset: String? = null,
    val precipSumMm: Float? = null,
    val precipChanceToday: Int? = null,
    val hourly: List<WeatherHourState> = emptyList(),
    val daily: List<WeatherDayState> = emptyList(),
    val staleLabel: String? = null,
)
