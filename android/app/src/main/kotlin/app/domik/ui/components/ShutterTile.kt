package app.domik.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.domik.R
import app.domik.ui.theme.DomikShapes
import app.domik.ui.theme.DomikSpacing
import app.domik.ui.theme.DomikTheme

/**
 * Co roleta robi, tak jak widzi to użytkownik (podpis kafelka). Sterownik z firmware 2017 nie zna położenia rolety,
 * więc „otwarta” i „zamknięta” znaczą tylko, że roleta sama zgłosiła dojechanie do górnego / dolnego końca.
 */
enum class ShutterDisplay(@StringRes val labelRes: Int) {
    /** Nikt jeszcze nie ruszył rolety od uruchomienia aplikacji (albo połączenie zerwało się w czasie ruchu). */
    Unknown(R.string.shutter_state_unknown),
    MovingUp(R.string.shutter_state_moving_up),
    MovingDown(R.string.shutter_state_moving_down),
    Stopped(R.string.shutter_state_stopped),

    /** Dojechała na górę. */
    Open(R.string.shutter_state_open),

    /** Dojechała na dół. */
    Closed(R.string.shutter_state_closed),
    ;

    val isMoving: Boolean get() = this == MovingUp || this == MovingDown
}

/**
 * Przycisk kafelka rolety: ↑ w górę, ■ stop, ↓ w dół. [movingDisplay] to stan, w którym przycisk jest „aktywny”
 * (roleta jedzie w tym kierunku); stop nie ma takiego stanu.
 */
enum class ShutterControl(@StringRes val descriptionRes: Int, val movingDisplay: ShutterDisplay?) {
    Up(R.string.shutter_up, ShutterDisplay.MovingUp),
    Stop(R.string.shutter_stop, null),
    Down(R.string.shutter_down, ShutterDisplay.MovingDown),
}

/**
 * Jak gęsto układamy kafelek rolety – zależnie od jego wysokości (siatka daje 2×1 od ok. 104 do ok. 160 dp, 2×2 od ok. 224 dp),
 * tak żeby trzy przyciski ↑ ■ ↓ miały zawsze co najmniej `controlHeight` i się mieściły. Podpis stanu jest zawsze
 * w drugiej linii nagłówka („pomieszczenie · stan”) – po prawej zabierałby miejsce nazwie; w [Large] ma osobną, dużą linię.
 *
 * @property padding wewnętrzny padding kafelka
 * @property largeHeader nagłówek z większą ikoną i większymi czcionkami
 * @property stateOnRoomLine podpis stanu dopisany do pomieszczenia (false = [Large], gdzie jest osobno)
 */
private enum class ShutterDensity(val padding: Dp, val largeHeader: Boolean, val stateOnRoomLine: Boolean) {
    /** 2×2 (od ok. 200 dp): nagłówek, duży podpis stanu i wysokie przyciski. */
    Large(DomikSpacing.tilePadding, largeHeader = true, stateOnRoomLine = false),

    /** 2×1 z zapasem (od ok. 140 dp): zwykły padding i czcionki kafelka. */
    Regular(DomikSpacing.tilePadding, largeHeader = true, stateOnRoomLine = true),

    /** 2×1 w typowej komórce (od ok. 116 dp): węższy padding i mniejsze czcionki. */
    Compact(DomikSpacing.tilePaddingCompact, largeHeader = false, stateOnRoomLine = true),

    /** 2×1 w najmniejszej komórce siatki (ok. 104–116 dp): padding 8 dp, reszta jak [Compact]. */
    Tight(8.dp, largeHeader = false, stateOnRoomLine = true),
    ;

    companion object {
        fun forHeight(height: Dp): ShutterDensity = when {
            height >= LARGE_MIN_HEIGHT -> Large
            height >= REGULAR_MIN_HEIGHT -> Regular
            height >= COMPACT_MIN_HEIGHT -> Compact
            else -> Tight
        }
    }
}

private val LARGE_MIN_HEIGHT = 200.dp
private val REGULAR_MIN_HEIGHT = 140.dp
private val COMPACT_MIN_HEIGHT = 116.dp

/** Wysokość przycisków w kafelku 2×2 (w pozostałych – [ShutterTile] `controlHeight`). */
private val LARGE_CONTROL_HEIGHT = 72.dp

/**
 * Kafelek rolety: szeroka karta 2×1 (albo 2×2) – ikona rolety, nazwa i pomieszczenie, podpis stanu
 * („jedzie w górę”, „zatrzymana”…), pod spodem trzy przyciski **↑ ■ ↓** (każdy co najmniej [controlHeight] wysokości).
 * Układ dopasowuje się do wysokości kafelka ([ShutterDensity]). **Bez paska położenia** – sterownik go nie zna.
 * Podczas ruchu przycisk kierunku ruchu jest wypełniony kolorem akcentu, a ikona rolety jest wypełniona.
 *
 * Uwaga: roleta działa jak pilot jednoprzyciskowy – dowolny kierunek wciśnięty w czasie ruchu ją zatrzymuje, więc
 * zmiana kierunku to dwa dotknięcia. Kafelek nic nie zgaduje: pokazuje wyłącznie to, co zgłosił sterownik.
 *
 * Bezstanowy – o stanie decyduje wywołujący ([display], [pending]).
 *
 * @param pending przycisk, którego polecenie czeka na potwierdzenie sterownika (pokazuje wskaźnik; sterowanie wyszarzone)
 */
@Composable
fun ShutterTile(
    name: String,
    room: String,
    display: ShutterDisplay,
    onControl: (ShutterControl) -> Unit,
    modifier: Modifier = Modifier,
    pending: ShutterControl? = null,
    controlHeight: Dp = 48.dp,
) {
    BoxWithConstraints(modifier) {
        val density = ShutterDensity.forHeight(maxHeight)
        val buttonHeight = if (density == ShutterDensity.Large) maxOf(controlHeight, LARGE_CONTROL_HEIGHT) else controlHeight
        val stateText = stringResource(display.labelRes)
        val headerDescription = listOf(name, room, stateText).filter { it.isNotBlank() }.joinToString(", ")
        TileCard(
            state = TileState.Neutral,
            modifier = Modifier.fillMaxSize(),
            mergeDescendants = false,
            contentPadding = PaddingValues(density.padding),
        ) { tile ->
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = headerDescription }) {
                    ShutterHeader(name, room, display, stateText, density, tile)
                    if (density == ShutterDensity.Large) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stateText.replaceFirstChar { it.titlecase() }, // osobna linia – zdanie od wielkiej litery
                            style = MaterialTheme.typography.headlineSmall,
                            color = if (display.isMoving) DomikTheme.colors.textPrimary else tile.subtitle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShutterControl.entries.forEach { control ->
                        ShutterControlButton(
                            control = control,
                            description = stringResource(control.descriptionRes, name),
                            active = display == control.movingDisplay,
                            pending = pending == control,
                            enabled = pending == null,
                            height = buttonHeight,
                            modifier = Modifier.weight(1f),
                            onClick = { onControl(control) },
                        )
                    }
                }
            }
        }
    }
}

/** Nagłówek: ikona rolety, nazwa i „pomieszczenie · stan” (stan wyróżniony, gdy roleta jedzie). */
@Composable
private fun ShutterHeader(
    name: String,
    room: String,
    display: ShutterDisplay,
    stateText: String,
    density: ShutterDensity,
    tile: TileColors,
) {
    val colors = DomikTheme.colors
    val stateColor = if (display.isMoving) colors.textPrimary else tile.subtitle
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        DeviceIconGlow(
            DeviceIcon.Shutter,
            lit = display.isMoving,
            tint = tile.icon,
            size = if (density.largeHeader) 32.dp else 24.dp,
        )
        Spacer(Modifier.width(if (density.largeHeader) 12.dp else 8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                style = if (density.largeHeader) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                color = tile.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (density.stateOnRoomLine) {
                    buildAnnotatedString {
                        if (room.isNotBlank()) append("$room · ")
                        withStyle(SpanStyle(color = stateColor)) { append(stateText) }
                    }
                } else {
                    AnnotatedString(room)
                },
                style = if (density.largeHeader) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                color = tile.subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Przycisk w kształcie pigułki; [active] = wypełniony kolorem akcentu, [pending] = wskaźnik zamiast ikony. */
@Composable
private fun ShutterControlButton(
    control: ShutterControl,
    description: String,
    active: Boolean,
    pending: Boolean,
    enabled: Boolean,
    height: Dp,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val colors = DomikTheme.colors
    val iconColor = if (active) colors.onAccent else colors.textPrimary
    Box(
        modifier = modifier
            .height(height)
            .alpha(if (enabled || pending) 1f else DISABLED_ALPHA)
            .clip(DomikShapes.chip)
            .background(if (active) colors.accent else lerp(colors.card, colors.textSecondary, IDLE_BUTTON_MIX))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = description
                selected = active
            },
        contentAlignment = Alignment.Center,
    ) {
        if (pending) {
            CircularProgressIndicator(color = iconColor, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
        } else {
            Image(
                painter = painterResource(
                    when (control) {
                        ShutterControl.Up -> R.drawable.ic_arrow_up
                        ShutterControl.Stop -> R.drawable.ic_stop
                        ShutterControl.Down -> R.drawable.ic_arrow_down
                    },
                ),
                contentDescription = null,
                colorFilter = ColorFilter.tint(iconColor),
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

private const val IDLE_BUTTON_MIX = 0.14f
