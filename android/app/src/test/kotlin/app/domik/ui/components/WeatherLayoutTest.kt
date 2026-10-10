package app.domik.ui.components

import app.domik.integrations.weather.WeatherOption
import app.domik.integrations.weather.WeatherOptions
import kotlin.math.ceil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Plan układu kafelka pogody wg dostępnego miejsca ([WeatherLayout]). Rozmiary jak na siatce: komórka 130 dp,
 * odstęp 12 dp; wnętrze kafelka = rozmiar − 2 × padding (wg [TileDensity]). Pogoda ma najwyżej 2 wiersze
 * (TileKind.WEATHER), więc przeglądy rozmiarów kończą się na wysokości wnętrza [MAX_INNER_HEIGHT].
 */
class WeatherLayoutTest {
    /** Wnętrze kafelka o 2 wierszach: 2 × komórka 160 dp (największa) + odstęp − 2 × padding 20 dp, z zapasem. */
    private val MAX_INNER_HEIGHT = 320

    private fun cells(cols: Int, rows: Int, cell: Float = 130f, gap: Float = 12f): Pair<Float, Float> {
        val width = cols * cell + (cols - 1) * gap
        val height = rows * cell + (rows - 1) * gap
        val padding = TileDensity.forHeight(androidx.compose.ui.unit.Dp(height)).padding.value
        return (width - 2 * padding) to (height - 2 * padding)
    }

    /** Padding kafelka (dp) dla komórek [rows] wierszy – tyle dolnego marginesu ma układ poza wnętrzem. */
    private fun cellPadding(rows: Int, cell: Float = 130f, gap: Float = 12f): Float =
        TileDensity.forHeight(androidx.compose.ui.unit.Dp(rows * cell + (rows - 1) * gap)).padding.value

    private fun plan(
        cols: Int,
        rows: Int,
        fontScale: Float = 1f,
        hourly: Boolean = true,
        daily: Boolean = true,
        precip: Boolean = true,
        details: Int = 0,
        stale: Boolean = false,
        cell: Float = 130f,
        days: Int = 14,
    ): WeatherLayoutPlan {
        val (w, h) = cells(cols, rows, cell)
        return WeatherLayout.plan(
            WeatherLayoutInput(
                width = w, height = h, fontScale = fontScale,
                showHourly = hourly, hourlyAvailable = 24,
                showDaily = daily, dailyAvailable = days,
                precipChance = precip, detailEntries = details, stale = stale,
                bottomInset = cellPadding(rows, cell),
            ),
        )
    }

    /** Prognoza z Open-Meteo ma teraz 14 dni, więc pasek dni nie jest ograniczony do tygodnia. */
    @Test
    fun `kafelek 1x1 to ikona temperatura i miasto bez prognozy`() {
        val plan = plan(1, 1, details = 6)
        assertEquals(WeatherLayoutKind.Mini, plan.kind)
        assertFalse(plan.hasExtras)
    }

    @Test
    fun `kafelek 2x1 pokazuje sama biezaca pogode`() {
        val plan = plan(2, 1)
        assertEquals(WeatherLayoutKind.Row, plan.kind)
        assertFalse(plan.hasExtras)
        assertFalse(plan.hero)
    }

    @Test
    fun `kafelek 3x1 i 4x1 pokazuja pasek godzin obok biezacej pogody`() {
        val three = plan(3, 1)
        assertEquals(WeatherLayoutKind.Split, three.kind)
        assertEquals(4, three.hourlyCount)
        assertEquals(0, three.dailyCount)
        val four = plan(4, 1)
        assertEquals(WeatherLayoutKind.Split, four.kind)
        assertEquals(6, four.hourlyCount)
        assertEquals(0, four.dailyCount)
    }

    @Test
    fun `kafelek 2x2 pokazuje naglowek pasek godzin i pasek dni w ukladzie kompaktowym`() {
        val plan = plan(2, 2)
        assertEquals(WeatherLayoutKind.Stacked, plan.kind)
        assertTrue(plan.compact)
        assertEquals(4, plan.hourlyCount)
        assertEquals(4, plan.dailyCount)
    }

    /**
     * Docelowy tablet: komórka 2×2 ma wnętrze ok. 210 × 190 dp, 3×2 ok. 320 × 190 dp. Oba paski (godzin i dni) mają się
     * zmieścić, układ kompaktowy zamiast gubienia paska dni, a paski mają tyle samo kolumn.
     */
    @Test
    fun `2x2 i 3x2 na docelowym tablecie pokazuja pasek godzin i pasek dni`() {
        for ((width, columns) in listOf(210f to 4, 320f to 6)) {
            val plan = WeatherLayout.plan(WeatherLayoutInput(width = width, height = 190f, hourlyAvailable = 24, dailyAvailable = 14))
            assertEquals(WeatherLayoutKind.Stacked, plan.kind, "$width: $plan")
            assertTrue(plan.compact, "$width: $plan")
            assertEquals(columns, plan.hourlyCount, "$width: $plan")
            assertEquals(columns, plan.dailyCount, "$width: $plan")
            // także z nieaktualnymi danymi (godzina aktualizacji idzie do nagłówka) i bez szansy opadów
            val stale = WeatherLayout.plan(
                WeatherLayoutInput(width = width, height = 190f, hourlyAvailable = 24, dailyAvailable = 14, stale = true),
            )
            assertEquals(2, stale.stripCount, "$width stale: $stale")
            val noPrecip = WeatherLayout.plan(
                WeatherLayoutInput(width = width, height = 190f, hourlyAvailable = 24, dailyAvailable = 14, precipChance = false),
            )
            assertEquals(2, noPrecip.stripCount, "$width bez opadów: $noPrecip")
        }
    }

    @Test
    fun `z jednym paskiem liczba kolumn liczy sie tak samo jak z dwoma`() {
        fun plan(hourly: Boolean, daily: Boolean) = WeatherLayout.plan(
            WeatherLayoutInput(
                width = 320f, height = 190f, showHourly = hourly, hourlyAvailable = 24, showDaily = daily, dailyAvailable = 14,
            ),
        )
        assertEquals(6, plan(hourly = true, daily = false).hourlyCount)
        assertEquals(6, plan(hourly = false, daily = true).dailyCount)
        assertEquals(6, plan(hourly = true, daily = true).hourlyCount)
        // ale tylko tyle kolumn, ile dni ma prognoza – także dla samego paska godzin
        val shortForecast = WeatherLayout.plan(
            WeatherLayoutInput(width = 320f, height = 190f, showDaily = false, hourlyAvailable = 24, dailyAvailable = 5),
        )
        assertEquals(5, shortForecast.hourlyCount)
    }

    @Test
    fun `kafelek 1x2 waski i wysoki pokazuje pasek dni w dwoch kolumnach`() {
        val plan = plan(1, 2)
        assertEquals(WeatherLayoutKind.Mini, plan.kind)
        assertEquals(0, plan.hourlyCount)
        assertEquals(2, plan.dailyCount)
    }

    @Test
    fun `kafelek 3x2 pokazuje oba paski jeden pod drugim w ukladzie kompaktowym`() {
        val plan = plan(3, 2)
        assertEquals(WeatherLayoutKind.Stacked, plan.kind)
        assertTrue(plan.compact)
        assertEquals(7, plan.hourlyCount)
        assertEquals(7, plan.dailyCount)
    }

    @Test
    fun `kafelek 4x2 ma oba paski na cala szerokosc pod jednoliniowym naglowkiem`() {
        val plan = plan(4, 2)
        assertEquals(WeatherLayoutKind.Stacked, plan.kind)
        assertTrue(plan.compact)
        assertEquals(10, plan.hourlyCount)
        assertEquals(10, plan.dailyCount)
    }

    @Test
    fun `kafelek 6x2 ma 14 kolumn pod jednoliniowym naglowkiem a przy tygodniowej prognozie oddaje nadwyzke biezacej pogodzie`() {
        val plan = plan(6, 2)
        assertEquals(WeatherLayoutKind.Stacked, plan.kind)
        assertEquals(WeatherLayout.MAX_COLUMNS, plan.hourlyCount)
        assertEquals(WeatherLayout.MAX_COLUMNS, plan.dailyCount)
        // przy tygodniowej prognozie (starsza pamięć podręczna) nie ma więcej kolumn niż dni, a rozstaw nie rośnie:
        // nadwyżka szerokości trafia do bieżącej pogody, nie do rozciągania siedmiu pozycji
        val week = plan(6, 2, days = 7)
        assertEquals(WeatherLayoutKind.Split, week.kind)
        assertEquals(7, week.hourlyCount)
        assertEquals(7, week.dailyCount)
        assertTrue(week.leftWidth > WeatherLayout.SPLIT_LEFT_MIN * 2, "$week")
    }

    @Test
    fun `kafelek 8x2 ma najwyzej 14 kolumn o docelowym rozstawie a reszte szerokosci dostaje biezaca pogoda`() {
        val (width, _) = cells(8, 2)
        val plan = plan(8, 2)
        assertEquals(WeatherLayoutKind.Split, plan.kind)
        assertEquals(14, plan.hourlyCount)
        assertEquals(14, plan.dailyCount)
        val right = width - plan.leftWidth - WeatherLayout.SPLIT_GAP
        assertEquals(WeatherLayout.COLUMN_PITCH, right / 14, 0.01f)
    }

    @Test
    fun `liczba kolumn rosnie z szerokoscia a rozstaw zostaje blisko docelowego (stala gestosc)`() {
        val target = WeatherLayout.COLUMN_PITCH
        // kafelki od 2×2 do 8×2 na siatce
        var previous = 0
        for (cols in 2..8) {
            val (width, height) = cells(cols, 2)
            val plan = WeatherLayout.plan(WeatherLayoutInput(width = width, height = height, hourlyAvailable = 24, dailyAvailable = 14))
            val n = plan.hourlyCount
            assertEquals(2, plan.stripCount, "${cols}x2: $plan")
            assertEquals(n, plan.dailyCount, "${cols}x2: $plan")
            assertTrue(n >= previous, "${cols}x2: $n < $previous")
            previous = n
            assertEquals(1f, (stripRoom(plan, width) / n) / target, 0.15f, "${cols}x2: $plan")
        }
        assertEquals(WeatherLayout.MAX_COLUMNS, previous)
        // gęsty przegląd szerokości od 2×2 na docelowym tablecie (210 dp): rozstaw w ±15% celu, a N rośnie z szerokością
        // (w całym zakresie Stacked, czyli do progu, od którego pasek przeszedłby na Split)
        var last = 0
        var width = 210f
        while (width <= 1100f) {
            val plan = WeatherLayout.plan(
                WeatherLayoutInput(width = width, height = 200f, hourlyAvailable = 24, dailyAvailable = 14),
            )
            val n = plan.hourlyCount
            assertEquals(2, plan.stripCount, "$width: $plan")
            assertEquals(n, plan.dailyCount, "$width: $plan")
            assertEquals(1f, (stripRoom(plan, width) / n) / target, 0.15f, "$width: $plan")
            if (plan.kind == WeatherLayoutKind.Stacked) {
                assertTrue(n >= last, "$width: $n < $last")
                last = n
            }
            width += 5f
        }
    }

    @Test
    fun `pasek dni ma tyle dni ile pozwala szerokosc a nie wysokosc`() {
        fun days(width: Float, height: Float, available: Int = 14) = WeatherLayout.plan(
            WeatherLayoutInput(width = width, height = height, showHourly = false, dailyAvailable = available),
        ).dailyCount
        // ten sam pasek niezależnie od tego, ile jest wysokości ponad potrzebną
        assertEquals(days(232f, 260f), days(232f, MAX_INNER_HEIGHT.toFloat()))
        // kolejne dni dochodzą co ok. 53 dp (docelowy rozstaw), najwyżej 14
        assertEquals(3, days(150f, 260f))
        assertEquals(4, days(232f, 260f))
        assertEquals(6, days(320f, 260f))
        assertEquals(14, days(1100f, 260f))
        // a dni jest nie więcej niż ma prognoza
        assertEquals(3, days(500f, 260f, available = 3))
        assertEquals(7, days(1100f, 260f, available = 7))
    }

    @Test
    fun `pasek dni potrzebuje podobnej wysokosci co pasek godzin`() {
        assertTrue(WeatherLayout.DAILY_STRIP_HEIGHT - WeatherLayout.STRIP_HEIGHT <= WeatherLayout.SECTION_GAP * 2)
        assertTrue(WeatherLayout.DAILY_STRIP_HEIGHT_COMPACT - WeatherLayout.STRIP_HEIGHT_COMPACT <= WeatherLayout.SECTION_GAP * 2)
        // 2x2 (232 x 232 dp): przy wyłączonych godzinach albo dniach zostaje pasek, nigdy pusto
        val hourly = plan(2, 2, daily = false)
        val daily = plan(2, 2, hourly = false)
        assertTrue(hourly.hourlyCount > 0)
        assertTrue(daily.dailyCount > 0)
    }

    @Test
    fun `lewa czesc Split ma co najmniej minimum, a nadwyzka szerokosci idzie na nia a nie na rozciaganie paskow`() {
        for (cols in 3..8) {
            val plan = plan(cols, 1)
            if (plan.kind != WeatherLayoutKind.Split) continue
            val (width, _) = cells(cols, 1)
            assertTrue(plan.leftWidth >= WeatherLayout.SPLIT_LEFT_MIN, "$cols: $plan")
            val right = width - plan.leftWidth - WeatherLayout.SPLIT_GAP
            assertTrue(right / plan.hourlyCount <= WeatherLayout.COLUMN_PITCH + 0.01f, "$cols: $plan")
        }
    }

    @Test
    fun `wylaczone prognozy znikaja z kafelka`() {
        val noHourly = plan(4, 2, hourly = false)
        assertEquals(0, noHourly.hourlyCount)
        assertTrue(noHourly.dailyCount > 0)
        val noDaily = plan(4, 2, daily = false)
        assertEquals(0, noDaily.dailyCount)
        assertTrue(noDaily.hourlyCount > 0)
        val none = plan(4, 2, hourly = false, daily = false)
        assertFalse(none.hasExtras)
        assertEquals(WeatherLayoutKind.Row, none.kind)
        assertTrue(none.hero)
    }

    @Test
    fun `szczegoly nie kosztuja paska dni, dopoki da sie je pokazac w ukladzie kompaktowym`() {
        val without = plan(2, 2)
        assertEquals(4, without.dailyCount)
        val two = plan(2, 2, details = 2)
        assertEquals(2, two.detailCount)
        assertEquals(4, two.dailyCount)
        assertEquals(4, two.hourlyCount)
        assertTrue(two.compact)
        // trzy linie szczegółów nie mieszczą się już razem z dwoma paskami: szczegóły wypierają pasek dni
        val three = plan(2, 2, details = 3)
        assertEquals(3, three.detailCount)
        assertEquals(0, three.dailyCount)
        assertEquals(4, three.hourlyCount)
        assertFalse(three.compact)
        // niski kafelek: nawet mniej linii szczegółów, ale pasek dni nie wraca
        val low = WeatherLayout.plan(
            WeatherLayoutInput(width = 232f, height = 190f, hourlyAvailable = 24, dailyAvailable = 14, detailEntries = 3),
        )
        assertTrue(low.detailCount > 0)
        assertEquals(0, low.dailyCount)
        assertEquals(4, low.hourlyCount)
    }

    @Test
    fun `szczegoly ukladaja sie w dwoch kolumnach dopiero na szerokim kafelku`() {
        val narrow = WeatherLayout.plan(
            WeatherLayoutInput(width = 300f, height = 232f, showHourly = false, showDaily = false, detailEntries = 4),
        )
        assertEquals(WeatherLayoutKind.Stacked, narrow.kind)
        assertEquals(1, narrow.detailColumns)
        val wide = WeatherLayout.plan(
            WeatherLayoutInput(width = 500f, height = 232f, showHourly = false, showDaily = false, detailEntries = 4),
        )
        assertEquals(WeatherLayoutKind.Stacked, wide.kind)
        assertEquals(2, wide.detailColumns)
        assertEquals(4, wide.detailCount)
    }

    @Test
    fun `szczegoly nie zmieszcza sie w niskim kafelku`() {
        assertEquals(1, plan(2, 1, details = 4).detailCount)
        assertEquals(0, plan(1, 1, details = 4).detailCount)
    }

    @Test
    fun `duza czcionka systemowa zmniejsza ilosc zawartosci a nie wypycha jej poza kafelek`() {
        val normal = plan(2, 2)
        val big = plan(2, 2, fontScale = 1.6f)
        assertEquals(2, normal.stripCount)
        // przy 160% czcionki obie linie prognozy się nie mieszczą – zostaje pasek godzin, w mniejszej liczbie kolumn
        assertEquals(1, big.stripCount)
        assertTrue(big.hourlyCount in WeatherLayout.MIN_STRIP_ITEMS until normal.hourlyCount)
    }

    @Test
    fun `nieaktualne dane trafiaja do naglowka kompaktowego zamiast zabierac pasek`() {
        // 232 x 260 dp: normalnie nagłówek 64 + godziny 86 + dni 102 = 252; z linią „Nieaktualne” (20) już 272
        fun plan(stale: Boolean) = WeatherLayout.plan(
            WeatherLayoutInput(width = 232f, height = 260f, hourlyAvailable = 24, dailyAvailable = 14, stale = stale),
        )
        assertFalse(plan(stale = false).compact)
        assertEquals(4, plan(stale = false).dailyCount)
        // układ kompaktowy pokazuje godzinę aktualizacji w nagłówku, więc oba paski zostają
        assertTrue(plan(stale = true).compact)
        assertEquals(4, plan(stale = true).dailyCount)
        assertEquals(4, plan(stale = true).hourlyCount)
    }

    @Test
    fun `granica trybu Mini to 150 dp`() {
        fun kind(width: Float) = WeatherLayout.plan(WeatherLayoutInput(width = width, height = 120f)).kind
        assertEquals(WeatherLayoutKind.Mini, kind(149.9f))
        assertEquals(WeatherLayoutKind.Row, kind(150f))
    }

    @Test
    fun `brak danych prognozy to brak sekcji prognozy`() {
        val plan = WeatherLayout.plan(
            WeatherLayoutInput(width = 516f, height = 232f, hourlyAvailable = 0, dailyAvailable = 0),
        )
        assertFalse(plan.hasExtras)
        assertEquals(WeatherLayoutKind.Row, plan.kind)
    }

    @Test
    fun `jeden dzien to za malo na pasek`() {
        val plan = WeatherLayout.plan(
            WeatherLayoutInput(width = 232f, height = 232f, showHourly = false, dailyAvailable = 1),
        )
        assertEquals(0, plan.dailyCount)
    }

    @Test
    fun `plan nigdy nie przekracza wysokosci ani szerokosci wnetrza`() {
        for (fontScale in listOf(1f, 1.3f, 1.6f)) {
            for (width in 60..900 step 10) {
                for (height in 50..MAX_INNER_HEIGHT step 10) {
                    for (hourlyAndDaily in listOf(true to true, true to false, false to true, false to false)) {
                        for (details in listOf(0, 2, 6)) {
                            for (stale in listOf(false, true)) {
                                for (precip in listOf(true, false)) {
                                    for (days in listOf(7, 14)) {
                                        val input = WeatherLayoutInput(
                                            width = width.toFloat(), height = height.toFloat(), fontScale = fontScale,
                                            showHourly = hourlyAndDaily.first, hourlyAvailable = 24,
                                            showDaily = hourlyAndDaily.second, dailyAvailable = days,
                                            precipChance = precip, detailEntries = details, stale = stale,
                                        )
                                        assertFits(input)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** Ta sama kontrola dla prognozy z mniejszą liczbą dni i godzin (np. pierwsze pobranie albo krótka odpowiedź). */
    @Test
    fun `plan miesci sie tez przy krotkiej prognozie`() {
        for (days in 0..14) {
            for (hours in listOf(0, 1, 4)) {
                for (width in 100..700 step 25) {
                    for (height in 80..MAX_INNER_HEIGHT step 20) {
                        assertFits(
                            WeatherLayoutInput(
                                width = width.toFloat(), height = height.toFloat(),
                                hourlyAvailable = hours, dailyAvailable = days, detailEntries = 3,
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun assertFits(input: WeatherLayoutInput) {
        val plan = WeatherLayout.plan(input)
        val used = usedHeight(plan, input)
        if (plan.hasExtras) {
            assertTrue(used <= input.height + 0.01f, "wysokość $used > ${input.height} ($plan, $input)")
        }
        assertTrue(plan.hourlyCount == 0 || plan.hourlyCount >= WeatherLayout.MIN_STRIP_ITEMS, "$plan")
        assertTrue(plan.dailyCount == 0 || plan.dailyCount >= WeatherLayout.MIN_DAILY_ITEMS, "$plan")
        assertTrue(plan.dailyCount <= WeatherLayout.MAX_COLUMNS, "$plan")
        assertTrue(plan.hourlyCount <= WeatherLayout.MAX_COLUMNS, "$plan")
        // liczba kolumn N nigdy nie przekracza liczby dni w prognozie (gdy prognoza dobowa w ogóle jest)
        if (input.dailyAvailable >= WeatherLayout.MIN_DAILY_ITEMS) {
            assertTrue(plan.dailyCount <= input.dailyAvailable, "$plan, $input")
            assertTrue(plan.hourlyCount <= input.dailyAvailable, "$plan, $input")
        }
        // oba paski mają tyle samo kolumn (o tej samej szerokości, więc leżą jedna pod drugą)
        if (plan.hourlyCount > 0 && plan.dailyCount > 0) {
            assertEquals(plan.hourlyCount, plan.dailyCount, "$plan, $input")
        }
        // stała gęstość: rozstaw kolumn (szerokość paska / N) nie schodzi poniżej 3/4 docelowego, a gdy kolumn nie
        // ogranicza prognoza (14 dni) – nie przekracza go o więcej niż MAX_STRETCH w Split (Stacked może więcej, ale
        // wtedy planer wybiera Split, o ile ten jest możliwy)
        val scale = input.fontScale.coerceIn(1f, WeatherLayout.MAX_FONT_SCALE)
        val room = stripRoom(plan, input.width)
        val target = WeatherLayout.COLUMN_PITCH * scale
        for (n in listOf(plan.hourlyCount, plan.dailyCount)) {
            if (n > 0) assertTrue(room / n >= target * 0.75f - 0.01f, "rozstaw ${room / n} < 3/4 z $target: $plan, $input")
        }
        // układ kompaktowy jest tylko tam, gdzie są paski (po to jest)
        if (plan.compact) assertTrue(plan.stripCount > 0, "$plan")
        if (plan.kind == WeatherLayoutKind.Split) {
            assertTrue(plan.leftWidth >= WeatherLayout.SPLIT_LEFT_MIN - 0.01f, "$plan")
            assertTrue(plan.leftWidth + WeatherLayout.SPLIT_GAP + room <= input.width + 0.01f, "$plan")
            assertTrue(room > 0f, "$plan")
        }
        // prognoza wyłączona albo bez danych nigdy nie pojawia się na kafelku
        if (!input.showHourly || input.hourlyAvailable == 0) assertEquals(0, plan.hourlyCount, "$input")
        if (!input.showDaily || input.dailyAvailable < WeatherLayout.MIN_DAILY_ITEMS) assertEquals(0, plan.dailyCount, "$input")
    }

    /** Szerokość, jaką paski dostają w danym układzie: cały kafelek albo jego prawa część w Split. */
    private fun stripRoom(plan: WeatherLayoutPlan, width: Float): Float =
        if (plan.kind == WeatherLayoutKind.Split) width - plan.leftWidth - WeatherLayout.SPLIT_GAP else width

    /** Wysokość, jaką plan zajmie (wg stałych planera przy danej skali czcionki) – do sprawdzenia, że nic nie wystaje. */
    private fun usedHeight(plan: WeatherLayoutPlan, input: WeatherLayoutInput): Float {
        val s = input.fontScale.coerceIn(1f, WeatherLayout.MAX_FONT_SCALE)
        val compact = plan.compact
        val gap = (if (compact) WeatherLayout.SECTION_GAP_COMPACT else WeatherLayout.SECTION_GAP) * s
        val hourlyStrip = s * when {
            compact -> if (input.precipChance) WeatherLayout.STRIP_HEIGHT_COMPACT else WeatherLayout.STRIP_HEIGHT_COMPACT_NO_PRECIP
            else -> if (input.precipChance) WeatherLayout.STRIP_HEIGHT else WeatherLayout.STRIP_HEIGHT_NO_PRECIP
        }
        val dailyStrip = s * when {
            compact -> if (input.precipChance) WeatherLayout.DAILY_STRIP_HEIGHT_COMPACT else WeatherLayout.DAILY_STRIP_HEIGHT_COMPACT_NO_PRECIP
            else -> if (input.precipChance) WeatherLayout.DAILY_STRIP_HEIGHT else WeatherLayout.DAILY_STRIP_HEIGHT_NO_PRECIP
        }
        val details = if (plan.detailCount > 0) {
            gap + ceil(plan.detailCount.toFloat() / plan.detailColumns) * WeatherLayout.DETAIL_LINE * s
        } else {
            0f
        }
        val hourly = if (plan.hourlyCount > 0) gap + hourlyStrip else 0f
        val daily = if (plan.dailyCount > 0) gap + dailyStrip else 0f
        val stale = if (input.stale) WeatherLayout.STALE_LINE * s else 0f
        return when (plan.kind) {
            WeatherLayoutKind.Mini -> WeatherLayout.HEADER_COMPACT * s + stale + details + daily
            WeatherLayoutKind.Row -> 0f
            WeatherLayoutKind.Stacked -> {
                val header = (if (compact) WeatherLayout.HEADER_SLIM else WeatherLayout.HEADER_ROW) * s
                // w układzie kompaktowym nieaktualność jest w nagłówku (zamiast min/maks.), więc nie zabiera wysokości
                val sections = listOf(plan.hourlyCount, plan.detailCount, plan.dailyCount).count { it > 0 }
                header + (if (compact) 0f else stale) + hourly + details + daily + sections * plan.gapExtra
            }
            // prawa kolumna Split: pasek godzin nad paskiem dni (bez odstępu nad pierwszym z nich)
            WeatherLayoutKind.Split -> (hourly + daily) - gap
        }
    }

    @Test
    fun `paski godzin i dni mieszcza sie w szerokosci i maja tyle samo kolumn`() {
        for (width in 100..900 step 5) {
            val plan = WeatherLayout.plan(
                WeatherLayoutInput(width = width.toFloat(), height = 300f, hourlyAvailable = 24, dailyAvailable = 14),
            )
            val room = stripRoom(plan, width.toFloat())
            for (n in listOf(plan.hourlyCount, plan.dailyCount)) {
                if (n > 0) assertTrue(room / n >= WeatherLayout.COLUMN_PITCH * 0.75f - 0.01f, "szer. $width: $plan")
            }
            if (plan.hourlyCount > 0 && plan.dailyCount > 0) assertEquals(plan.hourlyCount, plan.dailyCount, "szer. $width: $plan")
        }
    }

    // --- godziny ---

    @Test
    fun `krok godzin zalezy od liczby pozycji paska`() {
        assertEquals(1, WeatherLayout.hourlyStep(12))
        assertEquals(1, WeatherLayout.hourlyStep(20))
        assertEquals(2, WeatherLayout.hourlyStep(6))
        assertEquals(2, WeatherLayout.hourlyStep(7))
        assertEquals(3, WeatherLayout.hourlyStep(5))
        assertEquals(3, WeatherLayout.hourlyStep(3))
        assertEquals(3, WeatherLayout.hourlyStep(1))
        assertEquals(1, WeatherLayout.hourlyStep(0))
    }

    private fun hours(count: Int, precip: (Int) -> Int? = { it }) = List(count) { i ->
        WeatherHourState("%02d:00".format((10 + i) % 24), WeatherCondition.Clear, 10f + i, precip(i))
    }

    @Test
    fun `wybor godzin co krok z najwieksza szansa opadow z przedzialu`() {
        val picked = WeatherLayout.pickHours(hours(24), 5)
        assertEquals(listOf("10:00", "13:00", "16:00", "19:00", "22:00"), picked.map { it.label })
        assertEquals(listOf(10f, 13f, 16f, 19f, 22f), picked.map { it.temperature })
        // przedziały [0..2], [3..5], [6..8], [9..11], [12..14] → szansa to największa z przedziału
        assertEquals(listOf(2, 5, 8, 11, 14), picked.map { it.precipChance })
    }

    @Test
    fun `przy kroku jeden godziny sa kolejne a szansa opadow bez zmian`() {
        val picked = WeatherLayout.pickHours(hours(24) { it * 2 }, 12)
        assertEquals(12, picked.size)
        assertEquals(List(12) { it * 2 }, picked.map { it.precipChance })
        assertEquals("21:00", picked.last().label)
    }

    @Test
    fun `brak szansy opadow w przedziale zostaje brakiem`() {
        val picked = WeatherLayout.pickHours(hours(24) { null }, 5)
        assertTrue(picked.all { it.precipChance == null })
    }

    @Test
    fun `za malo godzin to krotszy pasek a nie wyjatek`() {
        assertEquals(1, WeatherLayout.pickHours(hours(2), 5).size)
        assertEquals(2, WeatherLayout.pickHours(hours(4), 5).size)
        assertTrue(WeatherLayout.pickHours(emptyList(), 5).isEmpty())
        assertTrue(WeatherLayout.pickHours(hours(24), 0).isEmpty())
    }

    // --- szczegoly, szansa opadow, lista dni, komunikaty ---

    private val full = WeatherTileState(
        condition = WeatherCondition.Clear, temperature = 14f, city = "Kraków",
        feelsLike = 12f, windKmh = 11, windGustsKmh = 24, humidityPercent = 62, uvIndex = 3f,
        sunrise = "06:53", sunset = "17:59", precipSumMm = 0.4f, precipChanceToday = 35,
    )
    private val allDetails = WeatherOptions(WeatherOptions.DETAIL_OPTIONS.toSet())

    @Test
    fun `szczegoly sa w stalej kolejnosci i tylko te z danymi`() {
        assertEquals(WeatherOptions.DETAIL_OPTIONS, WeatherLayout.availableDetails(full, allDetails))
        val sparse = full.copy(humidityPercent = null, sunrise = null, precipSumMm = null)
        assertEquals(
            listOf(WeatherOption.FeelsLike, WeatherOption.Wind, WeatherOption.UvIndex),
            WeatherLayout.availableDetails(sparse, allDetails),
        )
        assertEquals(listOf(WeatherOption.Sun), WeatherLayout.availableDetails(full, WeatherOptions(setOf(WeatherOption.Sun))))
    }

    @Test
    fun `wschod bez zachodu to brak pozycji slonca`() {
        val onlyRise = full.copy(sunset = null)
        assertFalse(WeatherOption.Sun in WeatherLayout.availableDetails(onlyRise, allDetails))
    }

    @Test
    fun `domyslny wybor nie ma szczegolow`() {
        assertTrue(WeatherLayout.availableDetails(full, WeatherOptions.DEFAULT).isEmpty())
    }

    @Test
    fun `opcje prognozy nigdy nie sa szczegolami`() {
        val all = WeatherOptions(WeatherOption.entries.toSet())
        assertEquals(WeatherOptions.DETAIL_OPTIONS, WeatherLayout.availableDetails(full, all))
    }

    @Test
    fun `szansa opadow ponizej 10 procent jest ukrywana`() {
        assertFalse(WeatherLayout.precipVisible(null))
        assertFalse(WeatherLayout.precipVisible(0))
        assertFalse(WeatherLayout.precipVisible(9))
        assertTrue(WeatherLayout.precipVisible(10))
        assertTrue(WeatherLayout.precipVisible(100))
    }

    /**
     * Wolna wysokość rozkłada się równo: przerwy (nagłówek-godziny, godziny-dni) są jednakowe, a dolny margines
     * (padding + reszta) jest od nich większy tylko o zapas bezpieczeństwa.
     */
    @Test
    fun `wolna wysokosc rozklada sie rowno miedzy przerwy i dolny margines`() {
        var checked = 0
        for (width in 150..500 step 10) {
            for (height in 120..MAX_INNER_HEIGHT step 4) {
                val input = WeatherLayoutInput(
                    width = width.toFloat(), height = height.toFloat(), hourlyAvailable = 24, dailyAvailable = 14,
                    bottomInset = 14f,
                )
                val plan = WeatherLayout.plan(input)
                if (plan.kind != WeatherLayoutKind.Stacked || plan.hourlyCount == 0 || plan.dailyCount == 0) continue
                assertTrue(plan.gapExtra >= 0f, "$plan")
                val sections = listOf(plan.hourlyCount, plan.detailCount, plan.dailyCount).count { it > 0 }
                val used = usedHeight(plan, input)
                val usedMin = used - sections * plan.gapExtra
                val minGap = if (plan.compact) WeatherLayout.SECTION_GAP_COMPACT else WeatherLayout.SECTION_GAP
                val gapVisible = minGap + plan.gapExtra
                val bottomVisible = input.bottomInset + (input.height - used)
                assertTrue(bottomVisible >= gapVisible - 0.01f, "dół $bottomVisible < przerwa $gapVisible: $plan, $input")
                val spare = input.height - usedMin - WeatherLayout.SAFETY_MARGIN
                if (plan.gapExtra > 0f && plan.gapExtra < spare / sections - 0.01f) {
                    assertEquals(WeatherLayout.SAFETY_MARGIN, bottomVisible - gapVisible, 0.01f, "$plan, $input")
                    checked++
                }
            }
        }
        assertTrue(checked > 50, "za mało rozmiarów z równym rozkładem: $checked")
    }

    /** Kafelki docelowe (2 wiersze) mają przerwy większe niż minimum, a nic nie wystaje. */
    @Test
    fun `kafelki dwuwierszowe maja wieksze przerwy niz minimum i nic nie wystaje`() {
        for ((cols, rows) in listOf(2 to 2, 3 to 2, 4 to 2)) {
            val (w, h) = cells(cols, rows)
            val input = WeatherLayoutInput(
                width = w, height = h, hourlyAvailable = 24, dailyAvailable = 14, bottomInset = cellPadding(rows),
            )
            val plan = WeatherLayout.plan(input)
            if (plan.kind == WeatherLayoutKind.Stacked) {
                assertTrue(plan.gapExtra > 0f, "$cols x $rows bez dodatku do przerw: $plan")
                assertTrue(usedHeight(plan, input) <= input.height - WeatherLayout.SAFETY_MARGIN + 0.01f, "$plan")
            }
        }
    }

    @Test
    fun `dodatek do przerw nigdy nie jest ujemny i dotyczy tylko Stacked`() {
        for (height in 80..MAX_INNER_HEIGHT step 4) {
            for (width in 150..500 step 25) {
                val input = WeatherLayoutInput(
                    width = width.toFloat(), height = height.toFloat(), hourlyAvailable = 24, dailyAvailable = 14,
                    bottomInset = 12f,
                )
                val plan = WeatherLayout.plan(input)
                assertTrue(plan.gapExtra >= 0f, "$plan, $input")
                if (plan.kind != WeatherLayoutKind.Stacked) assertEquals(0f, plan.gapExtra, "$plan")
            }
        }
    }

    @Test
    fun `komunikat dobiera wariant do rozmiaru kafelka`() {
        assertEquals(WeatherMessageVariant.Small, WeatherLayout.messageVariant(130f, 130f))
        assertEquals(WeatherMessageVariant.Small, WeatherLayout.messageVariant(130f, 272f))
        assertEquals(WeatherMessageVariant.Medium, WeatherLayout.messageVariant(272f, 130f))
        assertEquals(WeatherMessageVariant.Medium, WeatherLayout.messageVariant(556f, 130f))
        assertEquals(WeatherMessageVariant.Large, WeatherLayout.messageVariant(272f, 272f))
        assertEquals(WeatherMessageVariant.Large, WeatherLayout.messageVariant(556f, 414f))
    }
}
