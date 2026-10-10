package app.domik.integrations.weather

/**
 * Co kafelek pogody może pokazać oprócz bieżącej ikony i temperatury (te są zawsze). Użytkownik wybiera je w oknie
 * „Pogoda – co pokazywać”; kafelek pokazuje wybrane elementy tylko wtedy, gdy mieszczą się w jego rozmiarze.
 *
 * @property key stały klucz w zapisie ustawień (nie zmieniać – patrz [WeatherOptions.encode])
 */
enum class WeatherOption(val key: String) {
    /** Pasek najbliższych godzin (godzina, ikona, temperatura). */
    Hourly("hourly"),

    /** Lista kolejnych dni (dzień tygodnia, ikona, minimum i maksimum). */
    Daily("daily"),

    /** Szansa opadów (w procentach) przy godzinach i dniach. */
    PrecipitationChance("precip_chance"),

    /** Suma opadów w ciągu dzisiejszej doby (mm). */
    PrecipitationSum("precip_sum"),

    /** Temperatura odczuwalna. */
    FeelsLike("feels_like"),

    /** Prędkość wiatru i porywy. */
    Wind("wind"),

    /** Wilgotność względna powietrza. */
    Humidity("humidity"),

    /** Indeks UV (największy dzisiaj). */
    UvIndex("uv"),

    /** Wschód i zachód słońca. */
    Sun("sun"),
}

/**
 * Wybór elementów kafelka pogody ([WeatherOption]). Niezmienne; zmiany zwracają nowy obiekt. Domyślnie włączone są
 * godziny, dni i szansa opadów – szczegóły (odczuwalna, wiatr, wilgotność, UV, słońce, suma opadów) trzeba włączyć.
 *
 * Zapis ([encode] / [decode]) jest wersjonowany: `v1;hourly;daily;precip_chance`. Brak zapisu albo zapis z innej
 * wersji aplikacji = ustawienia domyślne; nieznane klucze są pomijane; `v1` bez kluczy oznacza „wszystko wyłączone”.
 */
data class WeatherOptions(val enabled: Set<WeatherOption> = DEFAULT_ENABLED) {
    operator fun contains(option: WeatherOption): Boolean = option in enabled

    /** Kopia z opcją [option] włączoną albo wyłączoną. */
    fun with(option: WeatherOption, on: Boolean): WeatherOptions =
        copy(enabled = if (on) enabled + option else enabled - option)

    /** Opcje szczegółów (wiersz informacji pod bieżącą pogodą), które użytkownik włączył, w stałej kolejności. */
    val details: List<WeatherOption> get() = DETAIL_OPTIONS.filter { it in enabled }

    /** Zapis do ustawień; opcje w kolejności deklaracji, więc równe wybory dają równy tekst. */
    fun encode(): String = buildString {
        append("v").append(VERSION)
        WeatherOption.entries.filter { it in enabled }.forEach { append(';').append(it.key) }
    }

    companion object {
        const val VERSION = 1

        val DEFAULT_ENABLED: Set<WeatherOption> =
            setOf(WeatherOption.Hourly, WeatherOption.Daily, WeatherOption.PrecipitationChance)

        val DEFAULT = WeatherOptions()

        /** Szczegóły w kolejności wyświetlania. */
        val DETAIL_OPTIONS: List<WeatherOption> = listOf(
            WeatherOption.FeelsLike,
            WeatherOption.Wind,
            WeatherOption.Humidity,
            WeatherOption.UvIndex,
            WeatherOption.Sun,
            WeatherOption.PrecipitationSum,
        )

        /** Odczyt zapisu z [encode]; brak, uszkodzenie albo inna wersja zapisu = [DEFAULT]. */
        fun decode(text: String?): WeatherOptions {
            if (text.isNullOrBlank()) return DEFAULT
            val records = text.trim().split(';')
            if (records[0] != "v$VERSION") return DEFAULT
            val byKey = WeatherOption.entries.associateBy { it.key }
            return WeatherOptions(records.drop(1).mapNotNull { byKey[it.trim()] }.toSet())
        }
    }
}
