package app.domik.ui.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.domik.R
import app.domik.integrations.calendar.CalendarInfo
import app.domik.integrations.calendar.CalendarSelection
import app.domik.integrations.calendar.CalendarState
import app.domik.ui.dashboard.CalendarAccess
import app.domik.ui.theme.DomikTheme

/** Co menu potrzebuje, by pokazać sekcję „Kalendarz” (null w trybie demo – tam kalendarz jest przykładowy). */
class CalendarMenu(
    val state: CalendarState,
    val selection: CalendarSelection,
    val access: CalendarAccess,
    val onVisibleChange: (calendarId: Long, visible: Boolean) -> Unit,
)

/**
 * Sekcja „Kalendarz” w menu: lista kalendarzy z urządzenia (kolor, nazwa, konto) z przełącznikami – które z nich
 * zasilają kafelek. Bez zgody na odczyt kalendarza pokazuje krótki tekst i przycisk (prośba o zgodę albo
 * ustawienia aplikacji po trwałej odmowie).
 */
@Composable
fun CalendarSection(menu: CalendarMenu) {
    val colors = DomikTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.menu_calendar), style = MaterialTheme.typography.headlineSmall)
        val state = menu.state
        when {
            !menu.access.granted || state is CalendarState.NoAccess -> {
                SectionText(stringResource(R.string.menu_calendar_no_access))
                OutlinedButton(onClick = menu.access::requestOrOpenSettings, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(
                            if (menu.access.blocked) R.string.calendar_open_settings else R.string.calendar_show_mine,
                        ),
                    )
                }
            }
            state is CalendarState.Ready && state.calendars.isEmpty() ->
                SectionText(stringResource(R.string.menu_calendar_empty))
            state is CalendarState.Ready -> {
                SectionText(stringResource(R.string.menu_calendar_hint))
                Column {
                    state.calendars.forEach { calendar ->
                        CalendarRow(
                            calendar = calendar,
                            checked = menu.selection.isVisible(calendar.id),
                            fallbackColor = colors.accent,
                            onCheckedChange = { menu.onVisibleChange(calendar.id, it) },
                        )
                    }
                }
            }
            state is CalendarState.Unavailable -> SectionText(stringResource(R.string.menu_calendar_unavailable))
            else -> SectionText(stringResource(R.string.calendar_loading))
        }
    }
}

@Composable
private fun SectionText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = DomikTheme.colors.textSecondary)
}

@Composable
private fun CalendarRow(
    calendar: CalendarInfo,
    checked: Boolean,
    fallbackColor: Color,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = DomikTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(calendar.color?.let { Color(it or OPAQUE) } ?: fallbackColor),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = calendar.displayName.ifBlank { calendar.accountName },
                style = MaterialTheme.typography.bodyLarge,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (calendar.accountName.isNotBlank() && calendar.accountName != calendar.displayName) {
                Text(
                    text = calendar.accountName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        // Dotyk obsługuje cały wiersz (toggleable) – przełącznik jest tylko wskaźnikiem stanu.
        Switch(checked = checked, onCheckedChange = null)
    }
}

private const val OPAQUE = 0xFF000000.toInt()
