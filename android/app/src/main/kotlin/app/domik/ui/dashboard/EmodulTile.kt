package app.domik.ui.dashboard

import android.content.res.Resources
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.domik.R
import app.domik.data.TileSize
import app.domik.ui.components.TileCard
import app.domik.ui.components.TileColors
import app.domik.ui.components.TileDensity
import app.domik.ui.components.TileState
import app.domik.ui.theme.DomikTheme
import java.time.ZoneId
import java.util.Locale

/*
 * Kafelki eModul (tylko odczyt): bez żadnych akcji i przycisków. Każdy kafelek zawsze pokazuje małą linię z czasem
 * danych sterownika; dane starsze niż 15 min przygaszają kafelek (pasek uwagi jest osobno, u góry pulpitu).
 */

private const val STALE_ALPHA = 0.55f
private val PROGRESS_HEIGHT = 4.dp

/**
 * Kafelek eModul w rozmiarze [size]: S 1×1 (ikona + tytuł, wartość, czas danych; pierwszy szczegół, jeśli się
 * mieści), M 2×1 (tytuł po lewej, wartość i dwa szczegóły po prawej, pasek postępu) albo L 2×2 (nagłówek, duża
 * wartość, lista szczegółów, pasek, uwaga i czas danych). Układ dopasowuje się do wysokości komórki ([TileDensity]).
 *
 * @param nowMillis „teraz” (z zegara minutowego) – od niego zależy podpis czasu danych i przygaszenie
 */
@Composable
fun EmodulTile(
    ui: EmodulTileUi,
    size: TileSize,
    nowMillis: Long,
    zone: ZoneId,
    texts: EmodulUiTexts,
    modifier: Modifier = Modifier,
) {
    val age = ui.dataTime?.let { EmodulDataAge.label(it, nowMillis, zone, texts) }
    val ageCompact = ui.dataTime?.let { EmodulDataAge.label(it, nowMillis, zone, texts, compact = true) }
    val ageSpoken = ui.dataTime?.let { EmodulDataAge.spoken(it, nowMillis, zone, texts) }
    val stale = ui.isStale(nowMillis)
    val binary = ui.icon in BINARY_ICONS
    BoxWithConstraints(modifier) {
        val density = TileDensity.forHeight(maxHeight)
        val innerHeight = maxHeight - density.padding * 2
        val innerWidth = maxWidth - density.padding * 2
        TileCard(
            state = if (binary) (if (ui.active) TileState.On else TileState.Off) else TileState.Neutral,
            modifier = Modifier.fillMaxSize().alpha(if (stale) STALE_ALPHA else 1f),
            tint = DomikTheme.colors.heating,
            tintAmount = if (!binary && ui.active) 1f else 0f,
            contentDescription = ui.description,
            stateDescription = ageSpoken,
            contentPadding = PaddingValues(density.padding),
        ) { tile ->
            when {
                size.cols < 2 -> SmallContent(ui, tile, age, ageCompact, stale, density, innerHeight, innerWidth)
                size.rows < 2 -> MediumContent(ui, tile, age, stale, density, innerWidth)
                else -> LargeContent(ui, tile, age, stale, density, innerHeight, innerWidth)
            }
        }
    }
}

/** Kafelek eModul bez danych ([message]: „Ładowanie…”, „Klucz eModul odrzucony”, „Brak danych eModul”). */
@Composable
fun EmodulPlaceholderTile(title: String, message: String, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val density = TileDensity.forHeight(maxHeight)
        TileCard(
            state = TileState.Neutral,
            modifier = Modifier.fillMaxSize().alpha(STALE_ALPHA),
            contentDescription = "$title, $message",
            contentPadding = PaddingValues(density.padding),
        ) { tile ->
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EmodulGlyph(EmodulIcon.GENERIC, 20.dp, tile.icon)
                    Spacer(Modifier.width(6.dp))
                    Text(title, style = density.nameStyle, color = tile.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(message, style = density.bodyStyle, color = tile.subtitle, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * S 1×1: tytuł (do 2 linii, w najciaśniejszych komórkach mniejszy) z ikoną 20/24 dp, jeśli komórka jest dość szeroka, wartość (headlineMedium, zmniejszana
 * do szerokości), pierwszy szczegół (tylko gdy [innerHeight] pozwala) i linia czasu danych. Budżet wysokości w najmniejszej
 * komórce (ok. 79 dp wnętrza): 2 × 14 + 34 + 16 = 78 dp – tytuł „Temperatura zewnętrzna” nie jest ucinany, a wartość też nie.
 */
@Composable
private fun SmallContent(ui: EmodulTileUi, tile: TileColors, age: String?, ageCompact: String?, stale: Boolean, density: TileDensity, innerHeight: Dp, innerWidth: Dp) {
    val tight = density == TileDensity.Tight
    val titleStyle = if (tight) density.nameStyle.copy(fontSize = 12.sp, lineHeight = 14.sp) else density.nameStyle
    val valueStyle = MaterialTheme.typography.headlineMedium
    val detailStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp)
    val ageStyle = MaterialTheme.typography.labelSmall
    val icon = if (tight) 20.dp else 24.dp
    // Wąska komórka: ikona zabrałaby tytułowi ok. 1/3 szerokości („Tempera…”), więc jest pomijana – tytuł dostaje całą szerokość.
    val showIcon = innerWidth >= SMALL_ICON_MIN_WIDTH
    val header = with(LocalDensity.current) { maxOf(if (showIcon) icon else 0.dp, titleStyle.lineHeight.toDp() * 2) }
    val needed = with(LocalDensity.current) { header + valueStyle.lineHeight.toDp() + detailStyle.lineHeight.toDp() + ageStyle.lineHeight.toDp() }
    val detail = ui.details.firstOrNull()?.takeIf { innerHeight >= needed }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            if (showIcon) {
                EmodulGlyph(ui.icon, icon, tile.icon)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = ui.title,
                style = titleStyle,
                color = tile.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Wartości zawsze po prawej (jak w M), czas odczytu na dole po lewej; w wąskiej komórce sama godzina.
        Column(Modifier.fillMaxWidth()) {
            FitText(ui.primary ?: DASH, valueStyle, tile.title, Modifier.fillMaxWidth(), alignment = Alignment.CenterEnd)
            if (detail != null) {
                Text(
                    text = "${detail.first} ${detail.second}",
                    style = detailStyle,
                    color = tile.subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            AgeLine(if (innerWidth < SMALL_AGE_FULL_MIN_WIDTH) ageCompact else age, tile, stale)
        }
    }
}

/**
 * M 2×1: u góry ikona z tytułem na całej szerokości (i nazwą modułu, gdy modułów jest kilka); niżej czas danych po lewej
 * i wartość (displaySmall, w najmniejszych komórkach headlineMedium) z maks. dwoma szczegółami w jednym wierszu po prawej;
 * na dole pasek postępu, jeśli kafelek go ma.
 */
@Composable
private fun MediumContent(ui: EmodulTileUi, tile: TileColors, age: String?, stale: Boolean, density: TileDensity, innerWidth: Dp) {
    val tight = density == TileDensity.Tight
    val valueStyle = if (tight) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.displaySmall
    val detailStyle = density.roomStyle
    val summary = ui.details.take(2).joinToString(" · ") { "${it.first} ${it.second}" }
    Column(Modifier.fillMaxSize()) {
        // Nagłówek (ikona + tytuł) ma całą szerokość kafelka – wartość jest w niższym wierszu, więc nie zabiera tytułowi miejsca.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            EmodulGlyph(ui.icon, density.icon - 4.dp, tile.icon)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = ui.title,
                    style = density.nameStyle,
                    color = tile.title,
                    maxLines = if (tight) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (ui.multiModule && ui.module.isNotBlank()) {
                    Text(ui.module, style = density.roomStyle, color = tile.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f)) { AgeLine(age, tile, stale) }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.widthIn(max = innerWidth * 0.7f), horizontalAlignment = Alignment.End) {
                FitText(ui.primary ?: DASH, valueStyle, tile.title)
                if (summary.isNotBlank()) {
                    Text(
                        text = summary,
                        style = detailStyle,
                        color = tile.subtitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
        ui.progress?.let {
            Spacer(Modifier.height(6.dp))
            ProgressBar(it, tile)
        }
    }
}

/**
 * L 2×2: nagłówek (ikona, tytuł, nazwa modułu), duża wartość, lista szczegółów (tyle wierszy, ile się mieści),
 * pasek postępu, uwaga (np. „wg krzywej grzewczej”) i na dole czas danych.
 */
@Composable
private fun LargeContent(ui: EmodulTileUi, tile: TileColors, age: String?, stale: Boolean, density: TileDensity, innerHeight: Dp, innerWidth: Dp) {
    val valueStyle = if (density == TileDensity.Tight) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.displaySmall
    val rowStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp)
    val noteStyle = MaterialTheme.typography.labelMedium
    val ageStyle = MaterialTheme.typography.labelSmall
    val rows = with(LocalDensity.current) {
        val fixed = HEADER_HEIGHT + valueStyle.lineHeight.toDp() + ageStyle.lineHeight.toDp() + SECTION_GAP * 2 +
            (if (ui.progress != null) PROGRESS_HEIGHT + SECTION_GAP else 0.dp) +
            (if (ui.note != null) noteStyle.lineHeight.toDp() else 0.dp)
        ((innerHeight - fixed) / rowStyle.lineHeight.toDp()).toInt().coerceIn(0, MAX_ROWS)
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(HEADER_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
            EmodulGlyph(ui.icon, 32.dp, tile.icon)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(ui.title, style = density.nameStyle, color = tile.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (ui.multiModule && ui.module.isNotBlank()) {
                    Text(ui.module, style = density.roomStyle, color = tile.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(SECTION_GAP))
        FitText(ui.primary ?: DASH, valueStyle, tile.title, Modifier.width(innerWidth))
        ui.primaryLabel?.let { Text(it, style = noteStyle, color = tile.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        Spacer(Modifier.height(SECTION_GAP).weight(1f, fill = false))
        ui.details.take(rows).forEach { (label, value) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, style = rowStyle, color = tile.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(8.dp))
                Text(value, style = rowStyle, color = tile.title, maxLines = 1, softWrap = false)
            }
        }
        Spacer(Modifier.weight(1f))
        ui.progress?.let {
            ProgressBar(it, tile)
            Spacer(Modifier.height(SECTION_GAP))
        }
        ui.note?.let { Text(it, style = noteStyle, color = tile.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        AgeLine(age, tile, stale)
    }
}

/** Linia „Odczyt 14:32” (czas sterownika) pod wartością – zawsze obecna; nieaktualne dane ([stale]) w kolorze ostrzegawczym. */
@Composable
private fun AgeLine(age: String?, tile: TileColors, stale: Boolean) {
    if (age == null) return
    Text(
        text = age,
        style = MaterialTheme.typography.labelSmall,
        color = if (stale) DomikTheme.colors.warning else tile.subtitle,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Pasek postępu 4 dp (otwarcie zaworu, zasobnik paliwa). */
@Composable
private fun ProgressBar(progress: Float, tile: TileColors) {
    val shape = RoundedCornerShape(percent = 50)
    Box(Modifier.fillMaxWidth().height(PROGRESS_HEIGHT).clip(shape).background(tile.subtitle.copy(alpha = 0.25f))) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().clip(shape).background(DomikTheme.colors.accent))
    }
}

/** Tekst w jednej linii, zmniejszany (nie poniżej [minSize]), aż zmieści się w szerokości – bez ucinania wartości. */
@Composable
private fun FitText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    minSize: TextUnit = 14.sp,
    alignment: Alignment = Alignment.CenterStart,
) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier.height(with(LocalDensity.current) { style.lineHeight.toDp() }), contentAlignment = alignment) {
        val widthPx = constraints.maxWidth
        val fitted = remember(text, style, widthPx, minSize) {
            var size = style.fontSize.value
            while (size > minSize.value) {
                val width = measurer.measure(
                    text, style.copy(fontSize = size.sp, lineHeight = TextUnit.Unspecified), softWrap = false, maxLines = 1,
                    constraints = Constraints(),
                ).size.width
                if (width <= widthPx) break
                size -= 1f
            }
            size.sp
        }
        Text(
            text = text,
            style = style.copy(fontSize = fitted, lineHeight = TextUnit.Unspecified),
            color = color,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EmodulGlyph(icon: EmodulIcon, size: Dp, tint: Color) {
    Image(
        painter = painterResource(icon.drawable()),
        contentDescription = null,
        colorFilter = ColorFilter.tint(tint),
        modifier = Modifier.size(size),
    )
}

@DrawableRes
internal fun EmodulIcon.drawable(): Int = when (this) {
    EmodulIcon.THERMOMETER -> R.drawable.ic_thermostat
    EmodulIcon.BOILER -> R.drawable.ic_emodul_boiler
    EmodulIcon.WATER -> R.drawable.ic_emodul_water
    EmodulIcon.VALVE -> R.drawable.ic_emodul_valve
    EmodulIcon.FAN -> R.drawable.ic_fan
    EmodulIcon.PUMP -> R.drawable.ic_pump
    EmodulIcon.RELAY -> R.drawable.ic_switch
    EmodulIcon.FUEL -> R.drawable.ic_emodul_fuel
    EmodulIcon.FLAME -> R.drawable.ic_flame
    EmodulIcon.INFO -> R.drawable.ic_emodul_info
    EmodulIcon.CLOCK -> R.drawable.ic_emodul_clock
    EmodulIcon.GENERIC -> R.drawable.ic_emodul_generic
}

/** Ikony kafelków włącz/wyłącz: pracujący wygląda jak włączone światło (gradient), nieczynny – jak wyłączone. */
private val BINARY_ICONS = setOf(EmodulIcon.RELAY, EmodulIcon.PUMP, EmodulIcon.FAN, EmodulIcon.FLAME)

private const val DASH = "—"

/** Od tej szerokości wnętrza kafelka S obok tytułu jest ikona (węższy kafelek: tytuł dostaje całą szerokość). */
private val SMALL_ICON_MIN_WIDTH = 140.dp
/** Od tej szerokości wnętrza kafelka S mieści się pełny podpis „Odczyt wczoraj 14:32”; węższy dostaje samą godzinę. */
private val SMALL_AGE_FULL_MIN_WIDTH = 130.dp
private val HEADER_HEIGHT = 32.dp
private val SECTION_GAP = 6.dp
private const val MAX_ROWS = 4

/** Teksty kafelków eModul z zasobów (`strings.xml`) – etykieta → zasób. */
class ResourcesEmodulUiTexts(private val resources: Resources) : EmodulUiTexts {
    override fun text(label: EmodulLabel, vararg args: Any): String = resources.getString(label.resource(), *args)

    @StringRes
    private fun EmodulLabel.resource(): Int = when (this) {
        EmodulLabel.SETPOINT -> R.string.emodul_label_setpoint
        EmodulLabel.RETURN -> R.string.emodul_label_return
        EmodulLabel.OPENING -> R.string.emodul_label_opening
        EmodulLabel.PUMP -> R.string.emodul_label_pump
        EmodulLabel.ON -> R.string.emodul_label_on
        EmodulLabel.OFF -> R.string.emodul_label_off
        EmodulLabel.RUNNING -> R.string.emodul_label_running
        EmodulLabel.STOPPED -> R.string.emodul_label_stopped
        EmodulLabel.ACTIVE -> R.string.emodul_label_active
        EmodulLabel.INACTIVE -> R.string.emodul_label_inactive
        EmodulLabel.GEAR -> R.string.emodul_label_gear
        EmodulLabel.BATTERY -> R.string.emodul_label_battery
        EmodulLabel.SIGNAL -> R.string.emodul_label_signal
        EmodulLabel.HUMIDITY -> R.string.emodul_label_humidity
        EmodulLabel.RUN_TIME -> R.string.emodul_label_run_time
        EmodulLabel.DATE -> R.string.emodul_label_date
        EmodulLabel.VERSION -> R.string.emodul_label_version
        EmodulLabel.TYPE -> R.string.emodul_label_type
        EmodulLabel.VALUE -> R.string.emodul_label_value
        EmodulLabel.BY_CURVE -> R.string.emodul_label_by_curve
        EmodulLabel.ZONE_N -> R.string.emodul_label_zone_n
        EmodulLabel.YESTERDAY -> R.string.emodul_age_yesterday
        EmodulLabel.READING -> R.string.emodul_age_reading
        EmodulLabel.SPOKEN_AT -> R.string.emodul_age_spoken_at
        EmodulLabel.SPOKEN_DATE -> R.string.emodul_age_spoken_date
    }
}

/** Teksty kafelków eModul i język liczb dla bieżącej konfiguracji (język urządzenia). */
@Composable
fun rememberEmodulUiTexts(): Pair<EmodulUiTexts, Locale> {
    val configuration = LocalConfiguration.current
    val resources = LocalResources.current
    return remember(resources, configuration) {
        ResourcesEmodulUiTexts(resources) to configuration.locales[0]
    }
}
