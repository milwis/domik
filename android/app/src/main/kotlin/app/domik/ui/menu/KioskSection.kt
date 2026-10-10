package app.domik.ui.menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.domik.R
import app.domik.data.KioskModel
import app.domik.data.KioskNotice
import app.domik.data.KioskState
import app.domik.ui.theme.DomikTheme
import kotlinx.coroutines.delay

/**
 * Tryb tabletu ściennego dla menu i okien dialogowych. Dostarcza go aktywność; brak (np. w podglądach) = menu
 * nie pokazuje sekcji „Tablet ścienny”.
 */
val LocalKioskModel = staticCompositionLocalOf<KioskModel?> { null }

/**
 * Sekcja „Tablet ścienny” w menu: ekran zawsze włączony przy zasilaniu, pełny ekran, Domik jako ekran główny
 * (autostart po restarcie) i przypinanie aplikacji. Przełączniki od razu zmieniają ustawienia; zmiany stanu
 * systemu (wybrany ekran główny, przypięcie) widać po powrocie z ustawień, bo aktywność odświeża [KioskModel].
 */
@Composable
fun KioskSection(model: KioskModel) {
    val state by model.state.collectAsStateWithLifecycle()
    // Przypięcie i odpięcie system potwierdza chwilę po wywołaniu (a gestem odpinamy poza aplikacją), więc
    // dopóki sekcja jest na ekranie, stan platformy odczytujemy co chwilę – tani odczyt, StateFlow pomija powtórki.
    LaunchedEffect(model) {
        while (true) {
            delay(STATE_POLL_MILLIS)
            model.refresh()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.kiosk_title), style = MaterialTheme.typography.headlineSmall)

        SwitchRow(
            iconRes = R.drawable.ic_sun,
            title = stringResource(R.string.kiosk_keep_on),
            hint = stringResource(R.string.kiosk_keep_on_hint),
            checked = state.settings.keepScreenOnWhenPowered,
            onCheckedChange = model::setKeepScreenOnWhenPowered,
        )
        SwitchRow(
            iconRes = R.drawable.ic_fullscreen,
            title = stringResource(R.string.kiosk_fullscreen),
            hint = stringResource(R.string.kiosk_fullscreen_hint),
            checked = state.settings.fullscreen,
            onCheckedChange = model::setFullscreen,
        )
        SwitchRow(
            iconRes = R.drawable.ic_home,
            title = stringResource(R.string.kiosk_home),
            hint = stringResource(state.homeHintRes()),
            checked = state.homeAliasEnabled,
            onCheckedChange = model::setHomeLauncher,
        )
        if (state.homeAliasEnabled && !state.isDefaultHome) {
            OutlinedButton(onClick = model::openHomeSettings, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.kiosk_home_open_settings))
            }
        }

        Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PinButton(state, onPin = model::pin, onUnpin = model::unpin)
            Hint(stringResource(if (state.pinned) R.string.kiosk_unpin_hint else R.string.kiosk_pin_hint))
        }

        state.notice?.let { Notice(it) }
    }
}

private const val STATE_POLL_MILLIS = 500L

private fun KioskState.homeHintRes(): Int = when {
    !homeAliasEnabled -> R.string.kiosk_home_hint_off
    isDefaultHome -> R.string.kiosk_home_hint_active
    else -> R.string.kiosk_home_hint_choose
}

@Composable
private fun SwitchRow(
    iconRes: Int,
    title: String,
    hint: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = DomikTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(iconRes), contentDescription = null, tint = colors.accent, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.textPrimary)
            Hint(hint)
        }
        Spacer(Modifier.width(12.dp))
        // Dotyk obsługuje cały wiersz (toggleable) – przełącznik jest tylko wskaźnikiem stanu.
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun PinButton(state: KioskState, onPin: () -> Unit, onUnpin: () -> Unit) {
    OutlinedButton(onClick = if (state.pinned) onUnpin else onPin, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Icon(painterResource(R.drawable.ic_pin), contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(stringResource(if (state.pinned) R.string.kiosk_unpin else R.string.kiosk_pin))
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = DomikTheme.colors.textSecondary)
}

@Composable
private fun Notice(notice: KioskNotice) {
    Text(
        text = stringResource(
            when (notice) {
                KioskNotice.PINNING_DISABLED -> R.string.kiosk_notice_pinning_disabled
                KioskNotice.UNPIN_FAILED -> R.string.kiosk_notice_unpin_failed
                KioskNotice.HOME_SETTINGS_UNAVAILABLE -> R.string.kiosk_notice_home_settings
            },
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = DomikTheme.colors.error,
    )
}
