package app.domik.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.domik.R
import app.domik.ui.theme.DomikTheme

/**
 * Kafelek światła / wyjścia S (1×1): ikona w lewym górnym rogu, na dole nazwa i pomieszczenie – i nic więcej.
 * **Bez przycisku zasilania** – dotknięcie całego kafelka przełącza stan, a stan widać po
 * kolorze: włączony wypełnia się gradientem palety, wyłączony jest przygaszony.
 *
 * Układ dopasowuje się do wysokości komórki ([TileDensity]): nazwa może zająć **dwie linie** (pomieszczenie – jedną),
 * a ikona, padding i czcionki maleją w najmniejszych komórkach siatki (od 104 dp), tak by nic nie nachodziło na siebie.
 *
 * Bezstanowy – o stanie decyduje wywołujący ([on], [pending]).
 *
 * @param pending trwa oczekiwanie na potwierdzenie sterownika (kafelek ma już docelowy wygląd, jasność pulsuje)
 * @param onToggle dotknięcie całego kafelka
 */
@Composable
fun LightTile(
    name: String,
    room: String,
    on: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    icon: DeviceIcon = DeviceIcon.Bulb,
    pending: Boolean = false,
) {
    val stateText = stringResource(
        when {
            pending && on -> R.string.state_turning_on
            pending -> R.string.state_turning_off
            on -> R.string.state_on
            else -> R.string.state_off
        },
    )
    BoxWithConstraints(modifier) {
        val density = TileDensity.forHeight(maxHeight)
        val inner = maxHeight - density.padding * 2
        TileCard(
            state = if (pending) TileState.Pending else if (on) TileState.On else TileState.Off,
            pendingOn = on,
            modifier = Modifier.fillMaxSize(),
            onClick = onToggle,
            role = Role.Switch,
            toggled = on,
            stateDescription = stateText,
            contentPadding = PaddingValues(density.padding),
        ) { tile ->
            val lines = fitNameLines(inner, fixed = density.icon, density.nameStyle, room.takeIf { it.isNotBlank() }?.let { density.roomStyle })
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                DeviceIconGlow(icon = icon, lit = on, tint = tile.icon, size = density.icon)
                TileNameAndRoom(name, room, tile, density, lines)
            }
        }
    }
}

/**
 * Ikona urządzenia (36 dp): wypełniona dla włączonego, konturowa dla wyłączonego (płynne przejście),
 * z miękką poświatą w kolorze akcentu, gdy [lit].
 */
@Composable
internal fun DeviceIconGlow(
    icon: DeviceIcon,
    lit: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
) {
    val glow = animateFloatAsState(if (lit && icon.hasGlow) 1f else 0f, tween(300), label = "glow")
    val accent = DomikTheme.colors.accent
    Box(
        modifier = modifier
            .size(size)
            .drawBehind {
                val alpha = glow.value
                if (alpha > 0f) {
                    val radius = this.size.minDimension * 1.15f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(accent.copy(alpha = 0.40f * alpha), Color.Transparent),
                            center = center,
                            radius = radius,
                        ),
                        radius = radius,
                        center = Offset(center.x, center.y),
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(targetState = lit, animationSpec = tween(200), label = "icon") { filled ->
            Image(
                painter = painterResource(if (filled) icon.filled else icon.outline),
                contentDescription = null, // dekoracyjna – tekst kafelka niesie znaczenie
                colorFilter = ColorFilter.tint(tint),
                modifier = Modifier.size(size),
            )
        }
    }
}
