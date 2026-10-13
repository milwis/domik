package app.domik.integrations.emodul

import java.util.Locale

/** Wartość do pokazania: [short] na kafelku (np. `65,0°`), [spoken] w opisie dla czytnika ekranu (np. `65,0 °C`). */
data class FormattedValue(val short: String, val spoken: String)

/**
 * Skale, wartości nieważne i formatowanie liczb eModul. Czyste funkcje; separator dziesiętny wg [Locale]
 * (pl → przecinek, en → kropka).
 */
object EmodulFormat {
    /** Zakres surowych dziesiątych części °C uznawany za ważny (−50…150 °C); poza nim czujnik jest odłączony. */
    private val TENTHS_RANGE = -500..1500
    private val SETPOINT_RANGE = 0..120

    /** `raw / 10.0` albo `null` dla wartości spoza −50…150 °C (np. sentinel 32767). */
    fun celsiusFromTenths(raw: Int?): Double? = raw?.takeIf { it in TENTHS_RANGE }?.let { it / 10.0 }

    /** Całkowita temperatura zadana zaworu (°C) albo `null` poza 0…120. */
    fun setpointCelsius(raw: Int?): Int? = raw?.takeIf { it in SETPOINT_RANGE }

    /** Procent albo `null` poza 0…100. */
    fun percent(raw: Int?): Int? = raw?.takeIf { it in 0..100 }

    /** Liczba z [decimals] miejscami po przecinku wg [locale]. */
    fun number(value: Double, decimals: Int, locale: Locale): String =
        String.format(locale, "%.${decimals}f", value)

    /** Temperatura: `12,0°` na kafelku, `12,0 °C` w opisie. */
    fun temperature(celsius: Double, decimals: Int, locale: Locale): FormattedValue {
        val n = number(celsius, decimals, locale)
        return FormattedValue("$n°", "$n °C")
    }

    /** Czas `h:mm` z liczby minut (jednostka 13: h×60+min). */
    fun clock(minutes: Int): String = "%d:%02d".format(Locale.ROOT, minutes / 60, minutes % 60)

    /**
     * Wartość widżetu wg jednostki [unit] (OpenAPI „value type”). `null` = brak ważnej wartości (czujnik
     * odłączony) albo widżet niewidoczny (`-1`) – wywołujący pokazuje „—”.
     */
    fun widget(raw: Int, unit: Int, locale: Locale): FormattedValue? {
        val polish = locale.language != "en"
        return when (unit) {
            -1 -> null
            0 -> plain(raw.toString())
            1 -> plain("$raw s")
            2 -> plain("$raw min")
            3 -> plain("$raw h")
            4 -> plain(number(raw / 10.0, 1, locale))
            5 -> plain(number(raw / 100.0, 2, locale))
            6 -> temperature(raw.toDouble(), 0, locale)
            7 -> celsiusFromTenths(raw)?.let { temperature(it, 1, locale) }
            8 -> plain("$raw %")
            9 -> plain("$raw ‰")
            10 -> plain("$raw kW")
            11 -> plain("$raw kWh")
            12 -> plain("$raw V")
            13 -> plain(clock(raw))
            14 -> plain("$raw " + if (polish) "dni" else "days")
            27 -> plain("$raw obr/min")
            28 -> plain(if (polish) (if (raw != 0) "zał." else "wył.") else (if (raw != 0) "on" else "off"))
            29 -> plain("$raw W")
            32 -> plain("$raw K")
            41 -> plain("$raw l")
            42 -> plain("$raw m³")
            else -> plain(raw.toString())
        }
    }

    private fun plain(text: String) = FormattedValue(text, text)
}
