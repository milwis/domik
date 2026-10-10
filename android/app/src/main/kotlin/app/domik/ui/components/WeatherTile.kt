package app.domik.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.domik.R
import app.domik.integrations.weather.WeatherOption
import app.domik.integrations.weather.WeatherOptions
import app.domik.ui.theme.DomikTheme

/**
 * Kafelek pogody. Zawartość dobiera się do **dostępnego miejsca** (mierzonego w dp, nie do rozmiaru
 * S/M/L – kafelek może mieć dowolny rozmiar od 1×1 komórki wzwyż), a o tym, co pokazać, decydują [options]
 * („Pogoda – co pokazywać”). Plan układu to czysta funkcja [WeatherLayout.plan] (testy JVM); tutaj tylko go rysujemy:
 *  - wąski (1 kolumna): ikona, temperatura, miasto; wyżej szczegóły i pasek kolejnych dni;
 *  - szerszy, niski: ikona i temperatura, miasto, opis, min/maks.; przy szerokości także pasek godzin obok;
 *  - wyższy: pasek najbliższych godzin (godzina, ikona, temperatura, szansa opadów), szczegóły i pasek dni (dzień,
 *    ikona, maks., min., szansa opadów) – oba poziome, o tej samej liczbie i szerokości kolumn (jeden pod drugim),
 *    ułożone od góry: nagłówek, godziny, dni (bez rozpychania do dołu); gdy brakuje wysokości – układ kompaktowy
 *    (nagłówek w jednej linii, drobniejsze paski);
 *  - duży: bieżąca pogoda z lewej, z prawej pasek godzin nad paskiem dni (albo oba paski pod nią na całą szerokość).
 * Dane nieaktualne są oznaczone godziną ostatniej aktualizacji. Bezstanowy, bez interakcji.
 */
@Composable
fun WeatherTile(
    state: WeatherTileState,
    modifier: Modifier = Modifier,
    options: WeatherOptions = WeatherOptions.DEFAULT,
) {
    val details = WeatherLayout.availableDetails(state, options)
    val texts = weatherTexts(state, options, details)
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier) {
        val padding = TileDensity.forHeight(maxHeight).padding
        val width = maxWidth - padding * 2
        val height = maxHeight - padding * 2
        val plan = WeatherLayout.plan(
            WeatherLayoutInput(
                width = width.value,
                height = height.value,
                fontScale = fontScale,
                showHourly = WeatherOption.Hourly in options,
                hourlyAvailable = state.hourly.size,
                showDaily = WeatherOption.Daily in options,
                dailyAvailable = state.daily.size,
                precipChance = WeatherOption.PrecipitationChance in options,
                detailEntries = texts.details.size,
                stale = state.staleLabel != null,
                bottomInset = padding.value,
            ),
        )
        TileCard(
            state = TileState.Neutral,
            modifier = Modifier.fillMaxSize(),
            contentDescription = texts.description,
            contentPadding = PaddingValues(padding),
        ) { tile ->
            val ctx = WeatherContext(state, texts, options, plan, tile, width, height)
            when (plan.kind) {
                WeatherLayoutKind.Mini -> MiniContent(ctx)
                WeatherLayoutKind.Row -> if (plan.hero) RowHeroContent(ctx) else RowContent(ctx)
                WeatherLayoutKind.Stacked -> StackedContent(ctx)
                WeatherLayoutKind.Split -> SplitContent(ctx)
            }
        }
    }
}

/** Gotowe teksty kafelka (z zasobów, w bieżącym języku), żeby ich nie składać w każdym wariancie osobno. */
@Immutable
private class WeatherTexts(
    val condition: String,
    val temperature: String,
    /** „9° / 16°” albo null, gdy nie ma prognozy na dziś. */
    val minMax: String?,
    /** „Szansa opadów 40%” (tylko gdy włączona i znana). */
    val precipToday: String?,
    /** Wszystkie dostępne i włączone szczegóły, po jednej linii, w stałej kolejności. */
    val details: List<String>,
    val description: String,
)

@Composable
private fun weatherTexts(state: WeatherTileState, options: WeatherOptions, details: List<WeatherOption>): WeatherTexts {
    val condition = stringResource(state.condition.labelRes)
    val temperature = formatWholeTemperature(state.temperature)
    val minMax = if (state.minTemp != null && state.maxTemp != null) {
        stringResource(R.string.weather_min_max_short, formatWholeTemperature(state.minTemp), formatWholeTemperature(state.maxTemp))
    } else {
        null
    }
    val precipToday = if (WeatherOption.PrecipitationChance in options) {
        state.precipChanceToday?.let { stringResource(R.string.weather_precip_chance_today, it) }
    } else {
        null
    }
    val lines = details.map { detailText(it, state) }
    val summary = stringResource(R.string.weather_summary, state.city, condition, temperature)
    val description = listOfNotNull(summary, precipToday).plus(lines).plus(listOfNotNull(state.staleLabel)).joinToString(". ")
    return WeatherTexts(condition, temperature, minMax, precipToday, lines, description)
}

@Composable
private fun detailText(option: WeatherOption, state: WeatherTileState): String = when (option) {
    WeatherOption.FeelsLike -> stringResource(R.string.weather_feels_like, formatWholeTemperature(state.feelsLike))
    WeatherOption.Wind -> {
        val wind = state.windKmh ?: 0
        val gusts = state.windGustsKmh
        if (gusts != null && gusts > wind) {
            stringResource(R.string.weather_wind_gusts, wind, gusts)
        } else {
            stringResource(R.string.weather_wind, wind)
        }
    }
    WeatherOption.Humidity -> stringResource(R.string.weather_humidity, state.humidityPercent ?: 0)
    WeatherOption.UvIndex -> stringResource(R.string.weather_uv, Math.round(state.uvIndex ?: 0f))
    WeatherOption.Sun -> stringResource(R.string.weather_sun, state.sunrise.orEmpty(), state.sunset.orEmpty())
    WeatherOption.PrecipitationSum -> stringResource(R.string.weather_precip_sum, formatPrecipitation(state.precipSumMm ?: 0f))
    else -> ""
}

/** Wszystko, czego potrzebują warianty rysowania: dane, teksty, plan, kolory i wymiary wnętrza kafelka. */
@Immutable
private class WeatherContext(
    val state: WeatherTileState,
    val texts: WeatherTexts,
    val options: WeatherOptions,
    val plan: WeatherLayoutPlan,
    val tile: TileColors,
    val width: Dp,
    val height: Dp,
) {
    val gap: Dp get() = (if (plan.compact) WeatherLayout.SECTION_GAP_COMPACT else WeatherLayout.SECTION_GAP).dp
    val showPrecip: Boolean get() = WeatherOption.PrecipitationChance in options
}

private const val STALE_ALPHA = 0.45f

@Composable
private fun ConditionIcon(ctx: WeatherContext, size: Dp, dimWhenStale: Boolean = false) {
    Image(
        painter = painterResource(ctx.state.condition.iconRes),
        contentDescription = null,
        colorFilter = ColorFilter.tint(ctx.tile.icon),
        modifier = Modifier.size(size).alpha(if (dimWhenStale && ctx.state.staleLabel != null) STALE_ALPHA else 1f),
    )
}

@Composable
private fun SingleLine(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
    maxLines: Int = 1,
) {
    Text(
        text = text,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        textAlign = textAlign,
        modifier = modifier,
    )
}

// --- Mini: wąski kafelek (1 kolumna) -----------------------------------------------------------------------

@Composable
private fun MiniContent(ctx: WeatherContext) {
    val plan = ctx.plan
    val tile = ctx.tile
    val stale = ctx.state.staleLabel
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = if (plan.hasExtras) Arrangement.Top else Arrangement.SpaceBetween,
    ) {
        // Nieaktualne dane: przygaszona ikona (tekstu nie ma gdzie dopisać); z listą pod spodem – także linia z godziną.
        ConditionIcon(ctx, 36.dp, dimWhenStale = true)
        Column {
            Text(
                text = ctx.texts.temperature,
                style = MaterialTheme.typography.headlineMedium,
                color = tile.title,
                maxLines = 1,
            )
            SingleLine(ctx.state.city, MaterialTheme.typography.bodyMedium, tile.subtitle)
        }
        if (plan.detailCount > 0) {
            Spacer(Modifier.height(ctx.gap))
            DetailsBlock(ctx, plan.detailCount, 1)
        }
        // Od góry: miasto, szczegóły, pasek dni – jedno pod drugim, bez rozpychania do dołu.
        if (plan.dailyCount > 0) {
            Spacer(Modifier.height(ctx.gap))
            DailyStrip(ctx, plan.dailyCount)
        }
        if (plan.hasExtras && stale != null) {
            SingleLine(stale, MaterialTheme.typography.bodySmall, tile.subtitle)
        }
    }
}

// --- Row: sama bieżąca pogoda -------------------------------------------------------------------------------

/** Wysokość zawartości, od której opis pogody może zająć 2 linie obok miasta i trzeciej linii. */
private val TALL_ROW_HEIGHT = 100.dp

/** Węższe wnętrze: ikona i temperatura w rzędzie, pod spodem samo miasto. */
private val NARROW_ROW_WIDTH = 230.dp

@Composable
private fun RowContent(ctx: WeatherContext) {
    val tile = ctx.tile
    val state = ctx.state
    // Nieaktualność ma pierwszeństwo przed min/maks. i dostaje całą szerokość kafelka pod rzędem (polskie
    // „Nieaktualne · 13:32” nie mieści się w wąskiej kolumnie obok temperatury, a godzina nie może zniknąć w „…”).
    val staleLine = state.staleLabel
    val minMaxLine = if (staleLine == null) ctx.texts.minMax else null
    val tall = ctx.height >= TALL_ROW_HEIGHT
    if (ctx.width < NARROW_ROW_WIDTH) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ConditionIcon(ctx, 40.dp)
                Spacer(Modifier.width(12.dp))
                Text(ctx.texts.temperature, style = MaterialTheme.typography.headlineLarge, color = tile.title, maxLines = 1)
            }
            SingleLine(state.city, MaterialTheme.typography.titleMedium, tile.subtitle)
            if (staleLine != null) SingleLine(staleLine, MaterialTheme.typography.bodyMedium, tile.subtitle)
        }
        return
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ConditionIcon(ctx, 44.dp)
                Spacer(Modifier.width(14.dp))
                Text(ctx.texts.temperature, style = MaterialTheme.typography.displaySmall, color = tile.title, maxLines = 1)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f, fill = false), horizontalAlignment = Alignment.End) {
                SingleLine(state.city, MaterialTheme.typography.titleMedium, tile.title, textAlign = TextAlign.End)
                SingleLine(
                    ctx.texts.condition,
                    MaterialTheme.typography.bodyMedium,
                    tile.subtitle,
                    textAlign = TextAlign.End,
                    // Długie polskie opisy („Częściowo pochmurno”) zawijamy do 2 linii, gdy kafelek jest dość wysoki.
                    maxLines = if ((staleLine == null && minMaxLine == null) || tall) 2 else 1,
                )
                if (minMaxLine != null) {
                    SingleLine(minMaxLine, MaterialTheme.typography.bodyMedium, tile.subtitle, textAlign = TextAlign.End)
                }
            }
        }
        if (staleLine != null) {
            SingleLine(
                staleLine,
                MaterialTheme.typography.bodyMedium,
                tile.subtitle,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Sama bieżąca pogoda na wysokim kafelku: duża ikona, miasto, duża temperatura, opis, min/maks. i szansa opadów. */
@Composable
private fun RowHeroContent(ctx: WeatherContext) {
    val tile = ctx.tile
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            ConditionIcon(ctx, 64.dp)
            SingleLine(
                ctx.state.city,
                MaterialTheme.typography.titleMedium,
                tile.title,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End,
            )
        }
        Column {
            Text(ctx.texts.temperature, style = MaterialTheme.typography.displayLarge, color = tile.title, maxLines = 1)
            SingleLine(ctx.texts.condition, MaterialTheme.typography.bodyLarge, tile.title)
            val extra = ctx.state.staleLabel ?: ctx.texts.minMax
            if (extra != null) SingleLine(extra, MaterialTheme.typography.bodyMedium, tile.subtitle)
            ctx.texts.precipToday?.let { SingleLine(it, MaterialTheme.typography.bodyMedium, DomikTheme.colors.precipitation) }
        }
    }
}

// --- Stacked: sekcje jedna pod drugą ------------------------------------------------------------------------

@Composable
private fun StackedContent(ctx: WeatherContext) {
    val plan = ctx.plan
    val sectionGap = ctx.gap + plan.gapExtra.dp
    // Od góry: nagłówek, pasek godzin, szczegóły, pasek dni, ewentualnie nieaktualność – jedno pod drugim. Odstęp przed
    // sekcją to minimalny [WeatherContext.gap] plus [WeatherLayoutPlan.gapExtra]: wolna wysokość jest rozłożona równo
    // między przerwy i dolny margines (planer), więc nic nie przykleja się do nagłówka ani do dołu.
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Top) {
        if (plan.compact) SlimHeader(ctx) else StackedHeader(ctx)
        if (plan.hourlyCount > 0) {
            Spacer(Modifier.height(sectionGap))
            HourlyStrip(ctx, plan.hourlyCount)
        }
        if (plan.detailCount > 0) {
            Spacer(Modifier.height(sectionGap))
            DetailsBlock(ctx, plan.detailCount, plan.detailColumns)
        }
        if (plan.dailyCount > 0) {
            Spacer(Modifier.height(sectionGap))
            DailyStrip(ctx, plan.dailyCount)
        }
        // Układ kompaktowy pokazuje nieaktualność w nagłówku (zamiast min/maks.).
        if (!plan.compact) {
            ctx.state.staleLabel?.let { SingleLine(it, MaterialTheme.typography.bodySmall, ctx.tile.subtitle) }
        }
    }
}

/** Nagłówek w jednej linii: ikona i temperatura z lewej, z prawej miasto nad min/maks. (albo nieaktualnością). */
@Composable
private fun SlimHeader(ctx: WeatherContext) {
    val tile = ctx.tile
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ConditionIcon(ctx, 32.dp)
        Spacer(Modifier.width(8.dp))
        Text(ctx.texts.temperature, style = MaterialTheme.typography.headlineSmall, color = tile.title, maxLines = 1)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            SingleLine(ctx.state.city, MaterialTheme.typography.labelLarge, tile.title, textAlign = TextAlign.End)
            (ctx.state.staleLabel ?: ctx.texts.minMax)?.let {
                SingleLine(it, MaterialTheme.typography.labelSmall, tile.subtitle, textAlign = TextAlign.End)
            }
        }
    }
}

@Composable
private fun StackedHeader(ctx: WeatherContext) {
    val tile = ctx.tile
    val state = ctx.state
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ConditionIcon(ctx, 44.dp)
            Spacer(Modifier.width(10.dp))
            Text(ctx.texts.temperature, style = MaterialTheme.typography.displaySmall, color = tile.title, maxLines = 1)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                SingleLine(state.city, MaterialTheme.typography.titleMedium, tile.title, textAlign = TextAlign.End)
                ctx.texts.minMax?.let {
                    SingleLine(it, MaterialTheme.typography.bodyMedium, tile.subtitle, textAlign = TextAlign.End)
                }
            }
        }
        SingleLine(ctx.texts.condition, MaterialTheme.typography.bodyMedium, tile.subtitle)
    }
}

// --- Split: bieżąca pogoda z lewej, prognoza z prawej --------------------------------------------------------

@Composable
private fun SplitContent(ctx: WeatherContext) {
    val plan = ctx.plan
    val tile = ctx.tile
    val state = ctx.state
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(WeatherLayout.SPLIT_GAP.dp)) {
        Column(
            Modifier.width(plan.leftWidth.dp).fillMaxHeight(),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ConditionIcon(ctx, 40.dp)
                Spacer(Modifier.width(10.dp))
                Text(ctx.texts.temperature, style = MaterialTheme.typography.displaySmall, color = tile.title, maxLines = 1)
            }
            val precipColor = DomikTheme.colors.precipitation
            // Linie pod temperaturą w kolejności ważności; nieaktualność wypiera min/maks. (jak w wąskim kafelku).
            val lines = listOfNotNull(
                LeftLine(state.city, MaterialTheme.typography.titleMedium, tile.title),
                LeftLine(ctx.texts.condition, MaterialTheme.typography.bodyMedium, tile.subtitle),
                (state.staleLabel ?: ctx.texts.minMax)?.let { LeftLine(it, MaterialTheme.typography.bodyMedium, tile.subtitle) },
                ctx.texts.precipToday?.let { LeftLine(it, MaterialTheme.typography.bodyMedium, precipColor) },
            ).take(plan.leftLines)
            lines.forEach { SingleLine(it.text, it.style, it.color) }
            if (plan.detailCount > 0) {
                Spacer(Modifier.height(ctx.gap))
                DetailsBlock(ctx, plan.detailCount, 1)
            }
        }
        Column(
            Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.Center,
        ) {
            if (plan.hourlyCount > 0) HourlyStrip(ctx, plan.hourlyCount)
            if (plan.dailyCount > 0) {
                if (plan.hourlyCount > 0) Spacer(Modifier.height(ctx.gap))
                DailyStrip(ctx, plan.dailyCount)
            }
        }
    }
}

private class LeftLine(val text: String, val style: TextStyle, val color: Color)

// --- Części: pasek godzin, pasek dni, szczegóły --------------------------------------------------------------

/** Style pasków; w układzie kompaktowym drobniejsze (wysokości jak w [WeatherLayout] STRIP_HEIGHT_COMPACT…). */
private class StripStyle(val label: TextStyle, val value: TextStyle, val icon: Dp, val precipHeight: Dp)

@Composable
private fun stripStyle(compact: Boolean): StripStyle {
    val typography = MaterialTheme.typography
    return if (compact) {
        StripStyle(
            label = typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 13.sp),
            value = typography.labelLarge.copy(fontSize = 14.sp, lineHeight = 17.sp),
            icon = 20.dp,
            precipHeight = 13.dp,
        )
    } else {
        StripStyle(typography.labelSmall, typography.labelLarge, 24.dp, 16.dp)
    }
}

/**
 * Pasek najbliższych godzin: godzina, ikona, temperatura i (jeśli włączona) szansa opadów. Kolumny są równe i rozłożone
 * na całą szerokość – dokładnie tak samo jak w [DailyStrip] o tej samej liczbie kolumn, więc leżą jedna pod drugą.
 */
@Composable
private fun HourlyStrip(ctx: WeatherContext, count: Int) {
    val hours = WeatherLayout.pickHours(ctx.state.hourly, count)
    val style = stripStyle(ctx.plan.compact)
    Row(Modifier.fillMaxWidth()) {
        hours.forEach { hour -> HourItem(hour, ctx, style, Modifier.weight(1f)) }
    }
}

@Composable
private fun HourItem(hour: WeatherHourState, ctx: WeatherContext, style: StripStyle, modifier: Modifier) {
    val tile = ctx.tile
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(hour.label, style = style.label, color = tile.subtitle, maxLines = 1)
        Image(
            painter = painterResource(hour.condition.iconRes),
            contentDescription = null,
            colorFilter = ColorFilter.tint(tile.icon),
            modifier = Modifier.size(style.icon),
        )
        Text(formatWholeTemperature(hour.temperature), style = style.value, color = tile.title, maxLines = 1)
        if (ctx.showPrecip) PrecipLabel(hour.precipChance, style)
    }
}

/**
 * Pasek kolejnych dni (poziomy, jak pasek godzin): dzień tygodnia, ikona, maksimum (wyraźne), minimum (przygaszone)
 * i – jeśli włączona – szansa opadów. Kolumny są równe i rozłożone na całą szerokość.
 */
@Composable
private fun DailyStrip(ctx: WeatherContext, count: Int) {
    val style = stripStyle(ctx.plan.compact)
    Row(Modifier.fillMaxWidth()) {
        ctx.state.daily.take(count).forEach { day -> DayItem(day, ctx, style, Modifier.weight(1f)) }
    }
}

@Composable
private fun DayItem(day: WeatherDayState, ctx: WeatherContext, style: StripStyle, modifier: Modifier) {
    val tile = ctx.tile
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(day.label, style = style.label, color = tile.subtitle, maxLines = 1)
        Image(
            painter = painterResource(day.condition.iconRes),
            contentDescription = null,
            colorFilter = ColorFilter.tint(tile.icon),
            modifier = Modifier.size(style.icon),
        )
        Text(formatWholeTemperature(day.maxTemp), style = style.value, color = tile.title, maxLines = 1)
        Text(formatWholeTemperature(day.minTemp), style = style.label, color = tile.subtitle, maxLines = 1)
        if (ctx.showPrecip) PrecipLabel(day.precipChance, style)
    }
}

/** Szansa opadów pod pozycją paska; zawsze zajmuje wysokość linii, żeby pozycje bez opadów nie przesuwały reszty paska. */
@Composable
private fun PrecipLabel(chance: Int?, style: StripStyle) {
    Text(
        text = if (WeatherLayout.precipVisible(chance)) stringResource(R.string.weather_precip_percent, chance ?: 0) else "",
        style = style.label,
        color = DomikTheme.colors.precipitation,
        maxLines = 1,
        modifier = Modifier.height(style.precipHeight),
    )
}

/** Włączone szczegóły ([count] pierwszych linii) w [columns] kolumnach. */
@Composable
private fun DetailsBlock(ctx: WeatherContext, count: Int, columns: Int) {
    val lines = ctx.texts.details.take(count)
    val style = MaterialTheme.typography.bodyMedium
    Column(Modifier.fillMaxWidth()) {
        lines.chunked(columns).forEach { chunk ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                chunk.forEach { line -> SingleLine(line, style, ctx.tile.subtitle, Modifier.weight(1f)) }
                repeat(columns - chunk.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
