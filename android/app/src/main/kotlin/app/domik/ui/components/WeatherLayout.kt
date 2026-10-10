package app.domik.ui.components

import app.domik.integrations.weather.WeatherOption
import app.domik.integrations.weather.WeatherOptions
import kotlin.math.ceil
import kotlin.math.floor

/*
 * Plan układu kafelka pogody wg DOSTĘPNEGO MIEJSCA (nie wg rozmiaru S/M/L): czysty Kotlin bez Compose, więc
 * testowalny na JVM (WeatherLayoutTest). Kafelek może mieć dowolną szerokość, ale NAJWYŻEJ 2 wiersze (TileKind.WEATHER;
 * wyższy rozsypywał układ), a komórka ma ok. 104–160 dp, więc wysokość wnętrza to najwyżej ok. 300 dp. Wszystko
 * liczymy w dp wnętrza kafelka (po odjęciu paddingu).
 *
 * Zasada: zawsze jest bieżąca pogoda (ikona + temperatura); im więcej miejsca, tym więcej szczegółów. Prognoza to
 * dwa POZIOME paski – godzin i dni – o tej samej budowie (kolumna na pozycję, równo rozłożone) i JEDNAKOWEJ liczbie
 * kolumn N o jednakowej szerokości, więc leżą dokładnie jeden pod drugim (komórka pod komórką). STAŁA GĘSTOŚĆ:
 * docelowy rozstaw kolumn to [WeatherLayout.COLUMN_PITCH] (ok. 53 dp), N = szerokość paska / rozstaw (zaokrąglone,
 * więc rozstaw odchyla się najwyżej o ok. 10–15%), najwyżej [WeatherLayout.MAX_COLUMNS] i najwyżej tyle, ile jest dni
 * w prognozie; tak samo, gdy włączony jest tylko jeden pasek. Szerszy kafelek = więcej kolumn, nie rozciągnięte
 * 5 pozycji; gdy kolumn zabrakło (limit), nadwyżka szerokości idzie do bieżącej pogody obok („Split”).
 * O tym, czy pasek się mieści, decyduje wysokość. Układy:
 *  - bardzo wąski (<150 dp): ikona + temperatura + miasto; wyżej szczegóły i pasek dni (2–3 kolumny);
 *  - jeden niski rząd: bieżąca pogoda, a przy szerokości także pasek godzin (albo dni) obok niej („Split”);
 *  - wyżej: bieżąca pogoda, pod nią pasek godzin, szczegóły (jeśli włączone) i pasek dni („Stacked”), jedno pod
 *    drugim od góry, bez przerw na nadwyżkę wysokości;
 *  - za nisko na oba paski w zwykłych wymiarach: układ KOMPAKTOWY (nagłówek w jednej linii, drobniejsze paski i
 *    mniejsze odstępy) – zamiast gubić pasek dni (np. 2×2 i 3×2 na docelowym tablecie, ok. 190 dp wysokości);
 *  - szeroko (od ok. 350 dp): bieżąca pogoda z lewej, z prawej pasek godzin nad paskiem dni („Split”).
 * Gdy nawet kompaktowo mieści się tylko jeden pasek, wygrywa pasek godzin. Wymiary elementów są szacunkiem wysokości z typografii
 * (Type.kt) skalowanym skalą czcionki.
 */

/** Rodzaj układu kafelka – patrz komentarz wyżej. */
enum class WeatherLayoutKind {
    /** Wąski (<150 dp): ikona na górze, temperatura i miasto na dole; opcjonalnie szczegóły i pasek dni pod spodem. */
    Mini,

    /** Sama bieżąca pogoda (ikona, temperatura, miasto, opis, min/maks.), bez prognozy. */
    Row,

    /** Bieżąca pogoda u góry (ikona, temperatura, miasto, opis), pod nią kolejne sekcje jedna pod drugą (paski na całą szerokość). */
    Stacked,

    /** Bieżąca pogoda (i szczegóły) z lewej, prognoza (pasek godzin nad paskiem dni) z prawej. */
    Split,
}

/**
 * Co kafelek wie o sobie: wnętrze [width] × [height] (dp, po odjęciu paddingu), skala czcionki, które sekcje
 * użytkownik włączył ([showHourly], [showDaily], [precipChance]), ile mamy danych ([hourlyAvailable],
 * [dailyAvailable], [detailEntries] – liczba wierszy szczegółów, które da się pokazać) i czy dane są nieaktualne.
 */
data class WeatherLayoutInput(
    val width: Float,
    val height: Float,
    val fontScale: Float = 1f,
    val showHourly: Boolean = true,
    val hourlyAvailable: Int = 0,
    val showDaily: Boolean = true,
    val dailyAvailable: Int = 0,
    val precipChance: Boolean = true,
    val detailEntries: Int = 0,
    val stale: Boolean = false,
    /** Odstęp od dolnej krawędzi wnętrza do krawędzi kafelka (padding, dp) – do wyrównania dolnego marginesu z przerwami. */
    val bottomInset: Float = 0f,
)

/**
 * Wynik planowania. [hourlyCount] = ile pozycji ma pasek godzin (0 = bez paska), [dailyCount] = ile dni ma pasek dni
 * (0 = bez paska), [detailCount] = ile wierszy szczegółów (0 = bez), [detailColumns] = w ilu kolumnach.
 * [leftWidth] – szerokość lewej części układu [WeatherLayoutKind.Split] (dp), [leftLines] – ile linii tekstu pod
 * ikoną i temperaturą w lewej części ([WeatherLayoutKind.Split]: miasto, opis, min/maks., szansa opadów), [hero] –
 * powiększona bieżąca pogoda ([WeatherLayoutKind.Row] na kafelku bez prognozy), [compact] – układ kompaktowy
 * (nagłówek [WeatherLayoutKind.Stacked] w jednej linii, drobniejsze paski, mniejsze odstępy), [gapExtra] – ile dp
 * (ponad minimalny odstęp) dodać przed każdą sekcją [WeatherLayoutKind.Stacked], żeby wolna wysokość rozłożyła się
 * równo między przerwy i dolny margines.
 */
data class WeatherLayoutPlan(
    val kind: WeatherLayoutKind,
    val hourlyCount: Int = 0,
    val dailyCount: Int = 0,
    val detailCount: Int = 0,
    val detailColumns: Int = 1,
    val leftWidth: Float = 0f,
    val leftLines: Int = 0,
    val hero: Boolean = false,
    val compact: Boolean = false,
    val gapExtra: Float = 0f,
) {
    /** Ile pasków prognozy (godzin, dni) kafelek pokazuje: 0–2. */
    val stripCount: Int get() = (if (hourlyCount > 0) 1 else 0) + (if (dailyCount > 0) 1 else 0)

    /** Czy poza bieżącą pogodą cokolwiek jeszcze się mieści. */
    val hasExtras: Boolean get() = stripCount > 0 || detailCount > 0
}

/** Wariant kafelka z komunikatem (wybór miejscowości, wczytywanie, błąd) wg dostępnego miejsca. */
enum class WeatherMessageVariant { Small, Medium, Large }

object WeatherLayout {
    /** Węższe wnętrze to układ [WeatherLayoutKind.Mini]. */
    const val MINI_MAX_WIDTH = 150f

    /**
     * Docelowy rozstaw kolumn obu pasków w dp przy skali czcionki 1 (mieści „17:00”, „niedz.”, „-12°” i „100%”),
     * największa liczba kolumn N (tyle dni daje prognoza) oraz najmniejsza, od której pasek godzin ([MIN_STRIP_ITEMS])
     * i pasek dni ([MIN_DAILY_ITEMS]) mają sens (jeden dzień nic by nie dał).
     */
    const val COLUMN_PITCH = 53f
    const val MAX_COLUMNS = 14
    const val MIN_STRIP_ITEMS = 3
    const val MIN_DAILY_ITEMS = 2

    /** O ile (razy) rozstaw kolumn może przekroczyć [COLUMN_PITCH], zanim układ woli oddać nadwyżkę szerokości bieżącej pogodzie. */
    const val MAX_STRETCH = 1.15f

    /** Szerokość, od której [WeatherLayoutKind.Split] jest możliwy w jednym niskim rzędzie, i od której wygrywa ze Stacked. */
    const val SPLIT_MIN_WIDTH = 350f
    const val SPLIT_PREFERRED_WIDTH = 430f
    const val SPLIT_GAP = 16f

    /** Lewa część Split ma co najmniej tyle; szersza tylko wtedy, gdy paski sięgnęły limitu kolumn i nie mają co zrobić z nadwyżką. */
    const val SPLIT_LEFT_MIN = 180f

    /** Od tej szerokości szczegóły układają się w dwóch kolumnach (każda musi pomieścić np. „Wiatr 11 km/h, porywy 25”). */
    const val TWO_COLUMN_MIN_WIDTH = 380f

    /** Szansa opadów poniżej tej wartości (%) nie jest pokazywana przy godzinach i dniach – „0 %” wszędzie to szum. */
    const val PRECIP_VISIBLE_MIN = 10

    /** Wysokość wnętrza, od której sama bieżąca pogoda ([WeatherLayoutKind.Row]) dostaje powiększony układ. */
    const val ROW_HERO_MIN_HEIGHT = 170f

    // Wysokości elementów w dp przy skali czcionki 1.
    const val HEADER_COMPACT = 92f
    const val HEADER_ROW = 64f

    /** Nagłówek Stacked w jednej linii (układ kompaktowy): ikona i temperatura, po prawej miasto nad min/maks. (albo nieaktualnością). */
    const val HEADER_SLIM = 36f
    const val STALE_LINE = 20f

    /** Pasek godzin: godzina (16) + ikona (24) + temperatura (20) + szansa opadów (16). */
    const val STRIP_HEIGHT = 76f
    const val STRIP_HEIGHT_NO_PRECIP = 60f

    /** Pasek dni: dzień (16) + ikona (24) + maks. (20) + min. (16) + szansa opadów (16). */
    const val DAILY_STRIP_HEIGHT = 92f
    const val DAILY_STRIP_HEIGHT_NO_PRECIP = 76f
    const val DETAIL_LINE = 20f
    const val SECTION_GAP = 10f

    /** Układ kompaktowy: drobniejsze paski (godzina 13 + ikona 20 + temperatura 17 + opady 13; dni dodatkowo min. 13) i odstęp. */
    const val STRIP_HEIGHT_COMPACT = 63f
    const val STRIP_HEIGHT_COMPACT_NO_PRECIP = 50f
    const val DAILY_STRIP_HEIGHT_COMPACT = 76f
    const val DAILY_STRIP_HEIGHT_COMPACT_NO_PRECIP = 63f
    const val SECTION_GAP_COMPACT = 5f

    /** Zapas wysokości (dp), którego rozkład wolnego miejsca między przerwy nie zużywa. */
    const val SAFETY_MARGIN = 4f

    /** Wysokość lewej części Split na wysokim kafelku: rząd ikona+temperatura i cztery linie tekstu. */
    const val SPLIT_BLOCK_HEIGHT = 150f
    const val SPLIT_ICON_ROW = 56f
    const val SPLIT_LINE = 20f
    const val MAX_FONT_SCALE = 1.6f

    /**
     * Co do tego, jak często pokazywać godziny: celujemy w ok. 12 godzin pokrycia paska – przy [count] pozycjach
     * krok to 1 godzina (dużo miejsca), 2 albo 3 godziny (mało).
     */
    fun hourlyStep(count: Int): Int = if (count <= 0) 1 else ceil(12f / count).toInt().coerceIn(1, 3)

    /**
     * Wybiera z kolejnych godzin [hours] (co godzinę, od najbliższej) [count] pozycji paska co [hourlyStep];
     * przy kroku większym niż 1 szansa opadów pozycji to największa szansa w jej przedziale, żeby nie
     * przeoczyć deszczu między pokazanymi godzinami.
     */
    fun pickHours(hours: List<WeatherHourState>, count: Int): List<WeatherHourState> {
        if (count <= 0) return emptyList()
        val step = hourlyStep(count)
        return (0 until count).mapNotNull { k ->
            val start = k * step
            if (start >= hours.size) return@mapNotNull null
            val span = hours.subList(start, minOf(hours.size, start + step))
            hours[start].copy(precipChance = span.mapNotNull { it.precipChance }.maxOrNull())
        }
    }

    fun messageVariant(width: Float, height: Float): WeatherMessageVariant = when {
        width < 190f -> WeatherMessageVariant.Small
        height >= 200f -> WeatherMessageVariant.Large
        else -> WeatherMessageVariant.Medium
    }

    /**
     * Szczegóły ([WeatherOptions.details]), które użytkownik włączył **i** dla których mamy dane (stary zapis w pamięci
     * podręcznej albo dostawca bez pola = pomijamy, zamiast pokazywać „–”). Kolejność jak w [WeatherOptions.DETAIL_OPTIONS].
     */
    fun availableDetails(state: WeatherTileState, options: WeatherOptions): List<WeatherOption> =
        options.details.filter { option ->
            when (option) {
                WeatherOption.FeelsLike -> state.feelsLike != null
                WeatherOption.Wind -> state.windKmh != null
                WeatherOption.Humidity -> state.humidityPercent != null
                WeatherOption.UvIndex -> state.uvIndex != null
                WeatherOption.Sun -> state.sunrise != null && state.sunset != null
                WeatherOption.PrecipitationSum -> state.precipSumMm != null
                else -> false
            }
        }

    /** Czy szansę opadów [percent] warto pokazać przy godzinie albo dniu (patrz [PRECIP_VISIBLE_MIN]). */
    fun precipVisible(percent: Int?): Boolean = percent != null && percent >= PRECIP_VISIBLE_MIN

    fun detailColumns(width: Float): Int = if (width >= TWO_COLUMN_MIN_WIDTH) 2 else 1

    fun plan(input: WeatherLayoutInput): WeatherLayoutPlan {
        val scale = input.fontScale.coerceIn(1f, MAX_FONT_SCALE)
        val ctx = Context(input, scale, compact = false)
        val tight = Context(input, scale, compact = true)
        if (input.width < MINI_MAX_WIDTH) return moreStrips(ctx.mini(), tight.mini())
        val stacked = moreStrips(ctx.stacked(), tight.stacked())
        val split = if (input.width >= SPLIT_MIN_WIDTH) moreStrips(ctx.split(), tight.split()) else null
        val splitUseful = split != null && split.stripCount > 0
        return when {
            splitUseful && (!stacked.hasExtras || preferSplit(input.width, scale, stacked, split!!)) -> split!!
            stacked.hasExtras -> stacked
            else -> WeatherLayoutPlan(WeatherLayoutKind.Row, hero = input.height >= ROW_HERO_MIN_HEIGHT * scale)
        }
    }

    /** Układ kompaktowy tylko wtedy, gdy dzięki niemu mieści się więcej pasków niż w zwykłych wymiarach. */
    private fun moreStrips(normal: WeatherLayoutPlan, compact: WeatherLayoutPlan): WeatherLayoutPlan =
        if (compact.stripCount > normal.stripCount) compact else normal

    /**
     * Split czy Stacked, gdy oba coś pokazują: Split wygrywa, gdy pokazuje więcej pasków (a w węższym kafelku – gdy
     * Stacked nie ma szczegółów, które woli zachować); Stacked – gdy pasków jest więcej u niego. Przy tylu samych
     * paskach wygrywa Stacked (paski na całą szerokość = najwięcej kolumn), dopóki kolumny nie musiałyby się rozciągnąć
     * ponad [MAX_STRETCH] rozstawu – wtedy Split, który nadwyżkę szerokości oddaje bieżącej pogodzie.
     */
    private fun preferSplit(width: Float, scale: Float, stacked: WeatherLayoutPlan, split: WeatherLayoutPlan): Boolean = when {
        split.stripCount > stacked.stripCount -> stacked.detailCount == 0 || width >= SPLIT_PREFERRED_WIDTH
        split.stripCount < stacked.stripCount -> false
        else -> {
            val columns = maxOf(stacked.hourlyCount, stacked.dailyCount)
            columns > 0 && width / columns > COLUMN_PITCH * scale * MAX_STRETCH
        }
    }

    private class Context(val input: WeatherLayoutInput, val s: Float, val compact: Boolean) {
        val w = input.width
        val h = input.height
        val gap = (if (compact) SECTION_GAP_COMPACT else SECTION_GAP) * s
        val wantHourly = input.showHourly && input.hourlyAvailable > 0
        val wantDaily = input.showDaily && input.dailyAvailable >= MIN_DAILY_ITEMS
        val hourlyStrip = s * when {
            compact -> if (input.precipChance) STRIP_HEIGHT_COMPACT else STRIP_HEIGHT_COMPACT_NO_PRECIP
            else -> if (input.precipChance) STRIP_HEIGHT else STRIP_HEIGHT_NO_PRECIP
        }
        val dailyStrip = s * when {
            compact -> if (input.precipChance) DAILY_STRIP_HEIGHT_COMPACT else DAILY_STRIP_HEIGHT_COMPACT_NO_PRECIP
            else -> if (input.precipChance) DAILY_STRIP_HEIGHT else DAILY_STRIP_HEIGHT_NO_PRECIP
        }
        val stale = if (input.stale) STALE_LINE * s else 0f

        val pitch = COLUMN_PITCH * s

        /**
         * Wspólna liczba kolumn N obu pasków w szerokości [room]: szerokość / docelowy rozstaw (zaokrąglone; rozstaw
         * rośnie z czcionką), najwyżej [MAX_COLUMNS] i najwyżej tyle, ile jest dni w prognozie (gdy prognozy dobowej nie
         * ma, ogranicza tylko szerokość).
         */
        fun columns(room: Float): Int {
            val days = if (input.dailyAvailable >= MIN_DAILY_ITEMS) input.dailyAvailable else MAX_COLUMNS
            return maxOf(0, minOf(MAX_COLUMNS, days, floor(room / pitch + 0.5f).toInt()))
        }

        /** Kolumny paska godzin w szerokości [room] (0, gdy to mniej niż [MIN_STRIP_ITEMS]). */
        fun hourlyItems(room: Float): Int = columns(room).let { if (it >= MIN_STRIP_ITEMS) it else 0 }

        /** Kolumny paska dni w szerokości [room] (0, gdy to mniej niż [MIN_DAILY_ITEMS]) – tyle samo co w pasku godzin. */
        fun dailyItems(room: Float): Int = columns(room).let { if (it >= MIN_DAILY_ITEMS) it else 0 }

        fun mini(): WeatherLayoutPlan {
            var rem = h - HEADER_COMPACT * s - stale
            val details = fitDetails(rem, 1)
            rem -= details.height
            val days = if (wantDaily && rem >= gap + dailyStrip) dailyItems(w) else 0
            return WeatherLayoutPlan(
                WeatherLayoutKind.Mini,
                dailyCount = days,
                detailCount = details.count,
                detailColumns = 1,
                compact = compact,
            )
        }

        fun stacked(): WeatherLayoutPlan {
            val header = if (compact) HEADER_SLIM else HEADER_ROW
            // W układzie kompaktowym nieaktualność mieści się w nagłówku (zastępuje min/maks.), więc nie zabiera wysokości.
            var rem = h - header * s - (if (compact) 0f else stale)
            var hourly = 0
            if (wantHourly && rem >= gap + hourlyStrip) {
                hourly = hourlyItems(w)
                if (hourly > 0) rem -= gap + hourlyStrip
            }
            val columns = detailColumns(w)
            val details = fitDetails(rem, columns)
            rem -= details.height
            val days = if (wantDaily && rem >= gap + dailyStrip) dailyItems(w) else 0
            if (days > 0) rem -= gap + dailyStrip
            val sections = (if (hourly > 0) 1 else 0) + (if (details.count > 0) 1 else 0) + (if (days > 0) 1 else 0)
            return WeatherLayoutPlan(
                WeatherLayoutKind.Stacked,
                hourlyCount = hourly,
                dailyCount = days,
                detailCount = details.count,
                detailColumns = columns,
                compact = compact,
                gapExtra = gapExtra(rem, sections),
            )
        }

        /**
         * Dodatkowy odstęp przed każdą z [sections] sekcji, gdy zostaje [slack] wolnej wysokości: przerwa między sekcjami
         * (minimalny [gap] + dodatek) ma wyglądać tak samo jak dolny margines (reszta wolnego miejsca + [WeatherLayoutInput.bottomInset]).
         * Z małym zapasem ([SAFETY_MARGIN]), żeby drobna różnica między szacunkiem a rzeczywistą wysokością tekstu nie
         * wypchnęła ostatniej sekcji poza kafelek.
         */
        fun gapExtra(slack: Float, sections: Int): Float {
            val spare = slack - SAFETY_MARGIN * s
            if (sections <= 0 || spare <= 0f) return 0f
            val even = (spare + input.bottomInset - gap) / (sections + 1)
            return even.coerceIn(0f, spare / sections)
        }

        fun split(): WeatherLayoutPlan {
            // Lewa część ma co najmniej [SPLIT_LEFT_MIN]; w reszcie mieści się tyle kolumn, ile daje docelowy rozstaw.
            // Gdy kolumn jest mniej, niż by się zmieściło (limit albo mało dni w prognozie), pasek zostaje przy
            // docelowym rozstawie, a nadwyżka szerokości idzie do lewej części (bieżąca pogoda), nie do rozciągania.
            val room = w - SPLIT_LEFT_MIN - SPLIT_GAP
            val n = columns(room)
            val right = if (n > 0) minOf(room, n * pitch) else room
            val left = w - SPLIT_GAP - right
            var rem = h
            var hourly = 0
            if (wantHourly && rem >= hourlyStrip) {
                hourly = hourlyItems(right)
                if (hourly > 0) rem -= hourlyStrip + gap
            }
            val days = if (wantDaily && rem >= dailyStrip) dailyItems(right) else 0
            val tall = h >= (SPLIT_BLOCK_HEIGHT + SPLIT_LINE) * s
            val leftLines: Int
            var details = Fit(0, 0f)
            if (tall) {
                leftLines = 4
                details = fitDetails(h - SPLIT_BLOCK_HEIGHT * s - stale, 1)
            } else {
                leftLines = floor((h - SPLIT_ICON_ROW * s) / (SPLIT_LINE * s)).toInt().coerceIn(1, 3)
            }
            return WeatherLayoutPlan(
                WeatherLayoutKind.Split,
                hourlyCount = hourly,
                dailyCount = days,
                detailCount = details.count,
                detailColumns = 1,
                leftWidth = left,
                leftLines = leftLines,
                compact = compact,
            )
        }

        /** Szczegóły: ile wierszy zmieści [rem] (z odstępem nad nimi) w [columns] kolumnach. */
        fun fitDetails(rem: Float, columns: Int): Fit {
            val entries = input.detailEntries
            if (entries <= 0 || rem < gap + DETAIL_LINE * s) return Fit(0, 0f)
            val lines = floor((rem - gap) / (DETAIL_LINE * s)).toInt()
            val count = minOf(entries, lines * columns)
            val used = ceil(count.toFloat() / columns).toInt()
            return Fit(count, gap + used * DETAIL_LINE * s)
        }
    }

    private class Fit(val count: Int, val height: Float)
}
