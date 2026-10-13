package app.domik.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.domik.R
import app.domik.ui.components.DeviceIcon
import app.domik.ui.components.DeviceIconGlow
import app.domik.ui.components.ShutterControl
import app.domik.ui.components.ShutterControlButton
import app.domik.ui.components.ShutterDisplay
import app.domik.ui.components.ShutterTile
import app.domik.ui.theme.DomikTheme

private val BUTTON_HEIGHT = 56.dp

/**
 * Kafelek rolety na pulpicie. W wariancie 1×1 ([ShutterTileModel.size]) nie ma miejsca na przyciski ↑ ■ ↓, więc
 * dotknięcie kafelka otwiera [ShutterDialog]; w 2×1 i 2×2 przyciski są na samym kafelku. W trybie edycji pulpitu
 * dotknięcie nic nie robi (okno zamyka się, gdyby edycja zaczęła się przy otwartym). Otwarte okno przeżywa obrót ekranu.
 */
@Composable
fun ShutterTileHost(
    item: ShutterTileModel,
    editing: Boolean,
    onControl: (ShutterControl) -> Unit,
    modifier: Modifier = Modifier,
) {
    val compact = item.size.cols < 2
    var open by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(editing) { if (editing) open = false }

    ShutterTile(
        name = item.name,
        room = item.room,
        display = item.display,
        pending = item.pending,
        onControl = onControl,
        modifier = modifier,
        compact = compact,
        onClick = if (editing) null else ({ open = true }),
        onClickLabel = stringResource(R.string.shutter_open_controls, item.name),
    )
    if (compact && open && !editing) {
        ShutterDialog(
            name = item.name,
            room = item.room,
            display = item.display,
            pending = item.pending,
            onControl = onControl,
            onDismiss = { open = false },
        )
    }
}

/**
 * Okno sterowania roletą (dla kafelka 1×1): nazwa, pomieszczenie, stan i trzy przyciski ↑ ■ ↓ po co najmniej 56 dp
 * wysokości, pod spodem „Zamknij”. Bezstanowe – stan na żywo przekazuje wywołujący. Okno zostaje otwarte po
 * poleceniu (roleta jest pilotem jednoprzyciskowym: zmiana kierunku to dwa dotknięcia).
 */
@Composable
fun ShutterDialog(
    name: String,
    room: String,
    display: ShutterDisplay,
    pending: ShutterControl?,
    onControl: (ShutterControl) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DomikTheme.colors
    val shape = MaterialTheme.shapes.extraLarge
    val title = stringResource(R.string.shutter_dialog_title, name)
    val stateText = stringResource(display.labelRes)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.card)
                .then(if (colors.isDark) Modifier.border(1.dp, colors.cardBorder, shape) else Modifier)
                .semantics { paneTitle = title }
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                DeviceIconGlow(DeviceIcon.Shutter, lit = display.isMoving, tint = colors.accent, size = 36.dp)
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.headlineSmall,
                        color = colors.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (room.isNotBlank()) {
                        Text(
                            text = room,
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Text(
                text = stateText.replaceFirstChar { it.titlecase() },
                style = MaterialTheme.typography.titleLarge,
                color = if (display.isMoving) colors.textPrimary else colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShutterControl.entries.forEach { control ->
                    ShutterControlButton(
                        control = control,
                        description = stringResource(control.descriptionRes, name),
                        active = display == control.movingDisplay,
                        pending = pending == control,
                        enabled = pending == null,
                        height = BUTTON_HEIGHT,
                        modifier = Modifier.weight(1f),
                        onClick = { onControl(control) },
                    )
                }
            }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text(stringResource(R.string.shutter_dialog_close), style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}
