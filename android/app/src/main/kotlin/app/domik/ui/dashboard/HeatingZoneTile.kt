package app.domik.ui.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import app.domik.R
import app.domik.data.HomeRepository
import app.domik.ui.components.HeatingTile
import app.domik.ui.components.HeatingTileSize
import app.domik.ui.thermo.ThermoActions

/**
 * Kafelek strefy na pulpicie razem z jej oknem ([HeatingZoneDialog]): dotknięcie kafelka S albo M, a w L – obszaru poza
 * przyciskami − / + i przełącznikiem profilu, otwiera okno. W trybie edycji pulpitu dotknięcie nic nie robi
 * (okno zamyka się, gdyby edycja zaczęła się przy otwartym). Otwarte okno przeżywa obrót ekranu.
 */
@Composable
fun HeatingZoneTile(
    item: ZoneTileModel,
    repository: HomeRepository,
    editing: Boolean,
    controlSize: Dp,
    modifier: Modifier = Modifier,
    thermo: ThermoActions? = null,
) {
    val id = item.id
    var open by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(editing) { if (editing) open = false }

    HeatingTile(
        state = item.state,
        size = when {
            item.size.cols < 2 -> HeatingTileSize.Small
            item.size.rows < 2 -> HeatingTileSize.Medium
            else -> HeatingTileSize.Large
        },
        modifier = modifier,
        controlSize = controlSize,
        onDecrease = { repository.adjustSetpoint(id.module, id.outputNr, -1) },
        onIncrease = { repository.adjustSetpoint(id.module, id.outputNr, +1) },
        onPreviousProfile = { repository.stepProfile(id.module, id.outputNr, forward = false) },
        onNextProfile = { repository.stepProfile(id.module, id.outputNr, forward = true) },
        onClick = if (editing) null else ({ open = true }),
        onClickLabel = stringResource(R.string.heating_open_settings),
    )
    if (open && !editing) {
        HeatingZoneDialog(repository = repository, zone = id, onDismiss = { open = false }, thermo = thermo)
    }
}
