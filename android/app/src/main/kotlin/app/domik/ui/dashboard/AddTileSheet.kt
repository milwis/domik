package app.domik.ui.dashboard

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import app.domik.R
import app.domik.data.TileId
import app.domik.ui.theme.DomikShapes
import app.domik.ui.theme.DomikTheme

/**
 * Arkusz „Dodaj”: ukryte kafelki pogrupowane na Ogrzewanie, Światła, Rolety, Alarm (wyjścia alarmowe)
 * i Inne (pogoda, kalendarz).
 * Dotknięcie wiersza dodaje kafelek na koniec pulpitu i zostawia arkusz otwarty, by dodać kolejne.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTileSheet(
    hidden: List<HiddenTileModel>,
    onAdd: (TileId) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DomikTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = DomikShapes.sheet,
        containerColor = colors.card,
        contentColor = colors.textPrimary,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.add_title), style = MaterialTheme.typography.headlineSmall)
            if (hidden.isEmpty()) {
                Text(
                    stringResource(R.string.add_nothing),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
            TileGroup.entries.forEach { group ->
                val items = hidden.filter { it.group == group }
                if (items.isNotEmpty()) {
                    Text(
                        stringResource(group.titleRes()),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    items.forEach { item -> AddRow(item, onClick = { onAdd(item.id) }) }
                }
            }
        }
    }
}

@Composable
private fun AddRow(item: HiddenTileModel, onClick: () -> Unit) {
    val colors = DomikTheme.colors
    val name = item.displayName()
    val description = stringResource(R.string.add_tile_description, name, item.room)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = description }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, color = colors.textPrimary)
            if (item.room.isNotBlank()) {
                Text(item.room, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
            }
        }
        Spacer(Modifier.width(12.dp))
        Image(
            painter = painterResource(R.drawable.ic_add),
            contentDescription = null,
            colorFilter = ColorFilter.tint(colors.accent),
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun HiddenTileModel.displayName(): String = when (id) {
    TileId.Weather -> stringResource(R.string.tile_weather)
    TileId.Agenda -> stringResource(R.string.tile_agenda)
    is TileId.Emodul -> name.ifBlank { stringResource(R.string.emodul_tile_n, id.tile) }
    is TileId.EmodulZone -> name.ifBlank { stringResource(R.string.emodul_tile_n, id.zoneId) }
    else -> name
}

private fun TileGroup.titleRes(): Int = when (this) {
    TileGroup.HEATING -> R.string.add_group_heating
    TileGroup.EMODUL -> R.string.add_group_emodul
    TileGroup.LIGHTS -> R.string.add_group_lights
    TileGroup.SHUTTERS -> R.string.add_group_shutters
    TileGroup.OTHER -> R.string.add_group_other
    TileGroup.ALARM -> R.string.add_group_alarm
}
