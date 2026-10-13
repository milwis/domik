package app.domik.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.domik.R
import app.domik.ui.theme.DomikColors
import app.domik.ui.theme.DomikSpacing
import app.domik.ui.theme.DomikTheme

/** Profil strefy ogrzewania (kolejność = kolejność przełączania ‹ ›). Etykiety: zasoby. */
enum class HeatingProfile(@StringRes val labelRes: Int) {
    Off(R.string.heating_profile_off),
    Eco(R.string.heating_profile_eco),
    Comfort(R.string.heating_profile_comfort),
    Max(R.string.heating_profile_max),
}

/**
 * Dane kafelka ogrzewania – wyłącznie do wyświetlenia (model UI, bez wiedzy o sterowniku).
 *
 * @property currentTemp temperatura zmierzona; `null` = brak odczytu
 * @property setpoint temperatura zadana; `null` = jeszcze nieznana (strefa nie odczytana)
 * @property canDecrease/[canIncrease] false na granicy zakresu (np. 5–30 °C) – przycisk wyszarzony
 * @property canSwitchOn strefa jest wyłączona, ale „+” ma ją włączyć (przy wyłączonej strefie reszta sterowania
 *   jest wyszarzona)
 * @property busy trwa zmiana profilu / włączanie strefy – sterowanie wyszarzone do potwierdzenia sterownika
 * @property profileSwitching false = bez strzałek ‹ › (zmiana profilu niedostępna), sama etykieta profilu
 * @property fromThermometer [currentTemp] pochodzi z termometru pokojowego (świeży odczyt), nie z czujnika Deimic –
 *   przy temperaturze pojawia się mała ikona termometru
 */
@Immutable
data class HeatingTileState(
    val name: String,
    val room: String,
    val currentTemp: Float?,
    val setpoint: Float?,
    val profile: HeatingProfile,
    val heating: Boolean,
    val canDecrease: Boolean = true,
    val canIncrease: Boolean = true,
    val canSwitchOn: Boolean = false,
    val busy: Boolean = false,
    val profileSwitching: Boolean = true,
    val fromThermometer: Boolean = false,
)

/**
 * Wariant rozmiaru: [Small] 1×1 i [Medium] 2×1 (tylko odczyt; sterowanie w oknie strefy) albo [Large] 2×2 – karta
 * ze sterowaniem (± i profil).
 */
enum class HeatingTileSize { Small, Medium, Large }

/**
 * Które kontrolki strefy są aktywne – jedna reguła dla dużego kafelka i okna strefy.
 *
 * @property increaseSwitchesOn „+” na wyłączonej strefie ją włącza (opis przycisku: „Włącz ogrzewanie”)
 */
@Immutable
data class HeatingControls(
    val canDecrease: Boolean,
    val canIncrease: Boolean,
    val increaseSwitchesOn: Boolean,
    val profileEnabled: Boolean,
)

/**
 * Aktywność kontrolek wg stanu strefy: przy wyłączonej strefie aktywny jest tylko „+” (włączenie), w trakcie zmiany
 * profilu / włączania ([HeatingTileState.busy]) nic; [online] = false (brak połączenia) wyłącza wszystko.
 */
fun HeatingTileState.controls(online: Boolean = true): HeatingControls {
    val off = profile == HeatingProfile.Off
    val ready = online && !busy
    val switchOn = ready && off && canSwitchOn
    return HeatingControls(
        canDecrease = ready && !off && canDecrease,
        canIncrease = (ready && !off && canIncrease) || switchOn,
        increaseSwitchesOn = switchOn,
        profileEnabled = ready,
    )
}

/**
 * Kafelek strefy ogrzewania.
 *
 *  - **S (1×1):** nazwa (jedna linia), pod nią duża temperatura aktualna, „→ zadana” i płomyk, gdy grzeje; bez
 *    przycisków (dotknięcie otwiera okno strefy).
 *  - **M (2×1):** ikona termometru, nazwa i pomieszczenie; po prawej duża temperatura aktualna („21,5°”)
 *    i pod nią „→ 22,0° · Komfort”; płomyk w kolorze `heating` obok temperatury, gdy grzeje (wtedy też ciepły
 *    odcień tła). Profil „wyłączone” – kafelek przygaszony jak wyłączone światło. Układ dopasowuje się do
 *    wysokości komórki ([TileDensity]) – wszystkie linie mieszczą się od 104 dp w górę.
 *  - **L (szeroka karta):** nagłówek (ikona, nazwa, pomieszczenie, płomyk), rząd `−` | temperatura aktualna
 *    + „→ zadana” | `+` oraz przełącznik profilu `‹ Komfort ›`.
 *
 * Bezstanowy: zmiany zgłasza callbackami. Przyciski ± mają [controlSize] (56 dp tablet / 48 dp telefon).
 * [onClick] (dotknięcie kafelka M albo L poza przyciskami) otwiera okno strefy; [onClickLabel] to jego opis dla
 * czytników ekranu.
 */
@Composable
fun HeatingTile(
    state: HeatingTileState,
    size: HeatingTileSize,
    modifier: Modifier = Modifier,
    controlSize: Dp = 56.dp,
    onDecrease: () -> Unit = {},
    onIncrease: () -> Unit = {},
    onPreviousProfile: () -> Unit = {},
    onNextProfile: () -> Unit = {},
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
) {
    val colors = DomikTheme.colors
    val tileState = if (state.profile == HeatingProfile.Off) TileState.Off else TileState.Neutral
    val showHeating = state.heating && state.profile != HeatingProfile.Off
    val current = formatTemperature(state.currentTemp)
    val target = formatTemperature(state.setpoint)
    val profileLabel = stringResource(state.profile.labelRes)
    val summary = stringResource(
        R.string.heating_summary,
        state.name,
        state.room,
        current,
        target,
        profileLabel,
    ).let { if (showHeating) stringResource(R.string.heating_summary_heating, it) else it }
        .let { if (state.fromThermometer) stringResource(R.string.heating_summary_thermometer, it) else it }

    if (size == HeatingTileSize.Small) {
        BoxWithConstraints(modifier) {
            val density = TileDensity.forHeight(maxHeight)
            HeatingCard(
                tileState, showHeating, summary, Modifier.fillMaxSize(), PaddingValues(density.padding),
                onClick, onLongClick, onClickLabel, mergeDescendants = true,
            ) { tile ->
                SmallContent(state, tile, current, target, showHeating, density)
            }
        }
    } else if (size == HeatingTileSize.Medium) {
        // Wysokość komórki 2×1 waha się od ok. 104 do ok. 160 dp, więc gęstość układu zależy od niej (jak w ShutterTile).
        BoxWithConstraints(modifier) {
            val density = TileDensity.forHeight(maxHeight)
            val innerHeight = maxHeight - density.padding * 2
            val innerWidth = maxWidth - density.padding * 2
            HeatingCard(
                tileState, showHeating, summary, Modifier.fillMaxSize(), PaddingValues(density.padding),
                onClick, onLongClick, onClickLabel, mergeDescendants = true,
            ) { tile ->
                MediumContent(state, tile, current, target, profileLabel, showHeating, density, innerHeight, innerWidth)
            }
        }
    } else {
        HeatingCard(
            tileState, showHeating, null, modifier, PaddingValues(DomikSpacing.tilePadding),
            onClick, onLongClick, onClickLabel, mergeDescendants = false,
        ) { tile ->
            LargeContent(
                state = state,
                tile = tile,
                current = current,
                target = target,
                profileLabel = profileLabel,
                showHeating = showHeating,
                controlSize = controlSize,
                onDecrease = onDecrease,
                onIncrease = onIncrease,
                onPreviousProfile = onPreviousProfile,
                onNextProfile = onNextProfile,
            )
        }
    }
}

@Composable
private fun HeatingCard(
    tileState: TileState,
    showHeating: Boolean,
    contentDescription: String?,
    modifier: Modifier,
    contentPadding: PaddingValues,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    onClickLabel: String?,
    mergeDescendants: Boolean,
    content: @Composable BoxScope.(TileColors) -> Unit,
) {
    TileCard(
        state = tileState,
        modifier = modifier,
        tint = DomikTheme.colors.heating,
        tintAmount = if (showHeating) 1f else 0f,
        onClick = onClick,
        onLongClick = onLongClick,
        onClickLabel = onClickLabel,
        contentDescription = contentDescription,
        mergeDescendants = mergeDescendants,
        contentPadding = contentPadding,
        content = content,
    )
}

/**
 * Zawartość kafelka S (1×1): nazwa (1 linia) z płomykiem grzania po prawej u góry, na dole **po prawej** duża temperatura
 * aktualna (headlineMedium; w najmniejszych komórkach titleLarge, by „20,3°” z ikoną termometru się mieściło),
 * pod nią „→ zadana”. Pomieszczenie pomijamy (nie mieści się; widać je w oknie strefy).
 */
@Composable
private fun SmallContent(
    state: HeatingTileState,
    tile: TileColors,
    current: String,
    target: String,
    showHeating: Boolean,
    density: TileDensity,
) {
    val tight = density == TileDensity.Tight
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Text(
                text = state.name,
                style = density.nameStyle,
                color = tile.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (showHeating) {
                Spacer(Modifier.width(4.dp))
                Flame(if (tight) 20.dp else 24.dp)
            }
        }
        // Wartości zawsze po prawej (jak w M): temperatura i „→ zadana” wyrównane do końca.
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.fromThermometer) {
                    ThermometerMark(if (tight) 12.dp else 16.dp, tile.subtitle)
                    Spacer(Modifier.width(2.dp))
                }
                Text(
                    text = current,
                    style = if (tight) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
                    color = tile.title,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            Text(
                text = stringResource(R.string.heating_target, target),
                style = density.roomStyle,
                color = tile.subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
            )
        }
    }
}

/**
 * Zawartość kafelka M. Lewa kolumna: ikona u góry, nazwa (do 2 linii, jeśli się mieści) i pomieszczenie na dole.
 * Prawa kolumna przyklejona do dołu: temperatura aktualna (płomyk **obok niej**, nie nad nią – nie zabiera wysokości)
 * i pod nią „→ zadana · profil”. Cała prawa kolumna ma ≤ 64 dp wysokości, więc mieści się w każdej komórce siatki,
 * a prawa kolumna nie zabiera lewej więcej niż ok. 60% szerokości.
 *
 * @param innerHeight wnętrze kafelka (komórka bez paddingu) – do dobrania liczby linii nazwy
 * @param innerWidth jw., szerokość
 */
@Composable
private fun MediumContent(
    state: HeatingTileState,
    tile: TileColors,
    current: String,
    target: String,
    profileLabel: String,
    showHeating: Boolean,
    density: TileDensity,
    innerHeight: Dp,
    innerWidth: Dp,
) {
    val dim = state.profile == HeatingProfile.Off
    val tight = density == TileDensity.Tight
    val currentStyle = if (tight) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.displaySmall
    val targetStyle = if (tight) MaterialTheme.typography.bodySmall else density.roomStyle
    val nameLines = fitNameLines(innerHeight, density.icon, density.nameStyle, density.roomStyle.takeIf { state.room.isNotBlank() })
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            DeviceIconGlow(DeviceIcon.Thermostat, lit = !dim, tint = tile.icon, size = density.icon)
            TileNameAndRoom(state.name, state.room, tile, density, nameLines)
        }
        Spacer(Modifier.width(8.dp))
        Column(
            Modifier.fillMaxHeight().widthIn(max = innerWidth * 0.6f),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.Bottom,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showHeating) {
                    Flame(if (tight) 24.dp else 28.dp)
                    Spacer(Modifier.width(4.dp))
                }
                if (state.fromThermometer) {
                    ThermometerMark(if (tight) 16.dp else 20.dp, tile.subtitle)
                    Spacer(Modifier.width(2.dp))
                }
                Text(
                    text = current,
                    style = currentStyle,
                    color = tile.title,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            Text(
                text = stringResource(R.string.heating_target_profile, target, stringResource(state.profile.labelRes)),
                style = targetStyle,
                color = tile.subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LargeContent(
    state: HeatingTileState,
    tile: TileColors,
    current: String,
    target: String,
    profileLabel: String,
    showHeating: Boolean,
    controlSize: Dp,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    onPreviousProfile: () -> Unit,
    onNextProfile: () -> Unit,
) {
    val colors = DomikTheme.colors
    val dim = state.profile == HeatingProfile.Off
    val controls = state.controls()
    // Kafelek w siatce ma stałą wysokość (2×2): wolne miejsce dzielone równo między sekcje (min. 12 dp).
    Column(Modifier.fillMaxSize()) {
        // Nagłówek: ikona | nazwa + pomieszczenie | płomyk
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            DeviceIconGlow(DeviceIcon.Thermostat, lit = !dim, tint = tile.icon, size = 32.dp)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) { NameAndRoom(state.name, state.room, tile) }
            FlameOrSpace(showHeating)
        }
        Spacer(Modifier.weight(1f).heightIn(min = 12.dp))
        // Rząd: − | temperatura aktualna + zadana | +
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            RoundControl(
                iconRes = R.drawable.ic_remove,
                description = stringResource(R.string.heating_decrease),
                container = colors.cool,
                enabled = controls.canDecrease,
                size = controlSize,
                onClick = onDecrease,
            )
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.fromThermometer) {
                        ThermometerMark(14.dp, tile.subtitle)
                        Spacer(Modifier.width(2.dp))
                    }
                    Text(
                        text = current,
                        style = MaterialTheme.typography.displaySmall,
                        color = tile.title,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                Text(
                    text = stringResource(R.string.heating_target, target),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (dim) tile.subtitle else tile.title,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            RoundControl(
                iconRes = R.drawable.ic_add,
                description = stringResource(if (controls.increaseSwitchesOn) R.string.heating_switch_on else R.string.heating_increase),
                container = colors.heating,
                enabled = controls.canIncrease,
                size = controlSize,
                onClick = onIncrease,
            )
        }
        Spacer(Modifier.weight(1f).heightIn(min = 12.dp))
        ProfileSwitcher(
            profile = state.profile,
            label = profileLabel,
            chevrons = state.profileSwitching,
            enabled = controls.profileEnabled,
            onPrevious = onPreviousProfile,
            onNext = onNextProfile,
        )
    }
}

@Composable
private fun NameAndRoom(name: String, room: String, tile: TileColors) {
    Column {
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium,
            color = tile.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = room,
            style = MaterialTheme.typography.bodyMedium,
            color = tile.subtitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Płomyk w kolorze `heating`, gdy grzeje; inaczej puste miejsce tej samej wielkości (stabilny układ nagłówka L). */
@Composable
private fun FlameOrSpace(visible: Boolean) {
    if (visible) Flame(28.dp) else Spacer(Modifier.size(28.dp))
}

/** Mała ikona „odczyt z termometru pokojowego” obok temperatury aktualnej. */
@Composable
private fun ThermometerMark(size: Dp, tint: Color) {
    Image(
        painter = painterResource(R.drawable.ic_thermostat_outline),
        contentDescription = stringResource(R.string.heating_from_thermometer),
        colorFilter = ColorFilter.tint(tint),
        modifier = Modifier.size(size),
    )
}

@Composable
private fun Flame(size: Dp) {
    Image(
        painter = painterResource(R.drawable.ic_flame),
        contentDescription = stringResource(R.string.heating_is_heating),
        colorFilter = ColorFilter.tint(DomikTheme.colors.heating),
        modifier = Modifier.size(size),
    )
}

/** Okrągły przycisk ± (48/56 dp), delikatnie podbarwiony semantycznym kolorem ([container]). */
@Composable
internal fun RoundControl(
    iconRes: Int,
    description: String,
    container: Color,
    enabled: Boolean,
    size: Dp,
    onClick: () -> Unit,
) {
    val colors = DomikTheme.colors
    Box(
        modifier = Modifier
            .size(size)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clip(CircleShape)
            .background(lerp(colors.card, container, 0.20f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            colorFilter = ColorFilter.tint(colors.textPrimary),
            modifier = Modifier.size(28.dp),
        )
    }
}

/** `‹ Komfort ›` – przełączanie profilu (kółko w kolorze profilu przed etykietą). */
@Composable
private fun ProfileSwitcher(
    profile: HeatingProfile,
    label: String,
    chevrons: Boolean,
    enabled: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val colors = DomikTheme.colors
    val dotColor = profile.dotColor(colors)
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (chevrons) Arrangement.SpaceBetween else Arrangement.Center,
    ) {
        if (chevrons) {
            ChevronButton(R.drawable.ic_chevron_left, stringResource(R.string.heating_previous_profile), enabled, onPrevious)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = if (chevrons) Modifier else Modifier.height(48.dp),
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(dotColor))
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
        if (chevrons) {
            ChevronButton(R.drawable.ic_chevron_right, stringResource(R.string.heating_next_profile), enabled, onNext)
        }
    }
}

@Composable
private fun ChevronButton(iconRes: Int, description: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = DomikTheme.colors
    Box(
        modifier = Modifier
            .size(48.dp)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            colorFilter = ColorFilter.tint(colors.textSecondary),
            modifier = Modifier.size(28.dp),
        )
    }
}

internal const val DISABLED_ALPHA = 0.38f

/** Kolor kropki profilu: wyłączone – szary, eko – chłodny, komfort i maks. – ciepły (semantyczne, nie zależą od palety). */
internal fun HeatingProfile.dotColor(colors: DomikColors): Color = when (this) {
    HeatingProfile.Off -> colors.iconOff
    HeatingProfile.Eco -> colors.cool
    HeatingProfile.Comfort, HeatingProfile.Max -> colors.heating
}

/** Kolejny/poprzedni profil w kolejności [HeatingProfile] (zawijanie na końcach). */
fun HeatingProfile.next(): HeatingProfile = HeatingProfile.entries[(ordinal + 1) % HeatingProfile.entries.size]

fun HeatingProfile.previous(): HeatingProfile =
    HeatingProfile.entries[(ordinal + HeatingProfile.entries.size - 1) % HeatingProfile.entries.size]

