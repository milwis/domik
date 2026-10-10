package app.domik.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import app.domik.ui.theme.DomikShapes
import app.domik.ui.theme.DomikSpacing
import app.domik.ui.theme.DomikTheme
import app.domik.ui.theme.DomikTokens

/**
 * Stan kafelka: stan pokazuje cały kafelek, bez przycisku zasilania.
 *  - [On]: wyjście włączone – wypełnienie gradientem `tileOn`, ikona w kolorze akcentu, tekst `textOnTile`;
 *  - [Off]: wyłączone – przygaszona, lekko przezroczysta karta, szara ikona;
 *  - [Pending]: oczekiwanie na potwierdzenie sterownika – kafelek ma już docelowy wygląd
 *    (włączony albo wyłączony, wg `pendingOn`), ale jasność delikatnie pulsuje;
 *  - [Neutral]: kafelek bez stanu on/off (ogrzewanie, pogoda, kalendarz) – zwykła karta.
 */
enum class TileState { On, Off, Pending, Neutral }

/** Kolory treści kafelka w bieżącym stanie (animowane) – przekazywane do zawartości [TileCard]. */
@Immutable
class TileColors(val icon: Color, val title: Color, val subtitle: Color)

private const val STATE_ANIMATION_MS = 200
private const val PULSE_PERIOD_MS = 850
private const val PULSE_DEPTH = 0.30f
private const val TINT_ALPHA = 0.10f

/**
 * Baza wszystkich kafelków: tło, kształt 24 dp, cień (jasny motyw) albo obrys (ciemny), animacja stanu
 * ~200 ms w jednym miejscu. Kolory wyłącznie z tokenów [DomikTheme.colors].
 *
 * @param pendingOn wygląd docelowy dla [TileState.Pending] (true = włączony)
 * @param tint opcjonalny odcień tła (np. ciepły przy grzaniu) o sile [tintAmount] 0..1
 * @param onClick dotknięcie całego kafelka (null = kafelek nieinteraktywny)
 * @param onLongClick przytrzymanie (arkusz szczegółów – później)
 * @param onClickLabel opis akcji dotknięcia dla czytników ekranu („Ustaw temperaturę”); null = domyślny
 * @param role rola dla czytników ekranu (np. [Role.Switch] dla światła)
 * @param toggled dla przełączników: stan do czytników ekranu (ToggleableState)
 * @param stateDescription opis stanu dla czytników ekranu („włączone”)
 * @param mergeDescendants scal zawartość w jeden element dostępności (false, gdy w środku są osobne przyciski)
 * @param content zawartość; dostaje kolory ikony/tytułu/podtytułu dla bieżącego stanu
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TileCard(
    state: TileState,
    modifier: Modifier = Modifier,
    pendingOn: Boolean = true,
    tint: Color = Color.Transparent,
    tintAmount: Float = 0f,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    role: Role? = null,
    toggled: Boolean? = null,
    stateDescription: String? = null,
    contentDescription: String? = null,
    mergeDescendants: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(DomikSpacing.tilePadding),
    content: @Composable BoxScope.(TileColors) -> Unit,
) {
    val colors = DomikTheme.colors
    val shape = DomikShapes.tile

    val lit = state == TileState.On || (state == TileState.Pending && pendingOn)
    val neutral = state == TileState.Neutral
    val pending = state == TileState.Pending

    // --- animowane parametry stanu (czytane w fazie rysowania, bez rekompozycji) ---
    val spec = tween<Float>(STATE_ANIMATION_MS)
    val litAlpha = animateFloatAsState(if (lit) 1f else 0f, spec, label = "litAlpha")
    val tintAlpha = animateFloatAsState(tintAmount.coerceIn(0f, 1f), spec, label = "tintAlpha")
    val base = animateColorAsState(
        if (neutral) colors.card else colors.cardOff,
        tween(STATE_ANIMATION_MS),
        label = "base",
    )
    val elevation by animateDpAsState(
        targetValue = if (colors.isDark || (!neutral && !lit)) 0.dp else TILE_ELEVATION,
        animationSpec = tween(STATE_ANIMATION_MS),
        label = "elevation",
    )

    // Pulsowanie jasności tylko w stanie Pending; poza nim brak animacji nieskończonej.
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(pending) {
        if (pending) {
            pulse.animateTo(
                1f,
                infiniteRepeatable(tween(PULSE_PERIOD_MS, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            )
        } else {
            pulse.animateTo(0f, tween(STATE_ANIMATION_MS))
        }
    }

    val tileBrush = remember(colors.tileOnStart, colors.tileOnEnd) { colors.tileOn }
    val shadowColor = colors.shadow.copy(alpha = SHADOW_ALPHA)

    val tileColors = TileColors(
        icon = animateColorAsState(
            if (lit || neutral) colors.accent else colors.iconOff,
            tween(STATE_ANIMATION_MS),
            label = "icon",
        ).value,
        title = animateColorAsState(
            when {
                lit -> colors.textOnTile
                neutral -> colors.textPrimary
                else -> colors.textTileOff
            },
            tween(STATE_ANIMATION_MS),
            label = "title",
        ).value,
        subtitle = animateColorAsState(
            when {
                lit -> colors.textOnTile.copy(alpha = DomikTokens.SUBTITLE_ON_TILE_ALPHA)
                else -> colors.textSecondary
            },
            tween(STATE_ANIMATION_MS),
            label = "subtitle",
        ).value,
    )

    val interaction =
        if (onClick != null || onLongClick != null) {
            Modifier.combinedClickable(onClick = onClick ?: {}, onLongClick = onLongClick, onClickLabel = onClickLabel, role = role)
        } else {
            Modifier
        }

    Box(
        modifier = modifier
            .graphicsLayer {
                shadowElevation = elevation.toPx()
                this.shape = shape
                clip = false
                ambientShadowColor = shadowColor
                spotShadowColor = shadowColor
            }
            .clip(shape)
            .drawBehind {
                val brightness = 1f - PULSE_DEPTH * pulse.value
                drawRect(base.value, alpha = brightness)
                if (litAlpha.value > 0f) {
                    drawRect(tileBrush, alpha = litAlpha.value * brightness)
                }
                if (tintAlpha.value > 0f) {
                    drawRect(tint.copy(alpha = TINT_ALPHA * tintAlpha.value))
                }
            }
            .then(if (colors.isDark) Modifier.border(BORDER_WIDTH, colors.cardBorder, shape) else Modifier)
            .then(interaction)
            .semantics(mergeDescendants = mergeDescendants) {
                contentDescription?.let { this.contentDescription = it }
                stateDescription?.let { this.stateDescription = it }
                toggled?.let { toggleableState = ToggleableState(it) }
            }
            .padding(contentPadding),
        content = { content(tileColors) },
    )
}

private val TILE_ELEVATION = 3.dp
private val BORDER_WIDTH = 1.dp
private const val SHADOW_ALPHA = 0.28f
