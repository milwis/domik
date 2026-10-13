package app.domik.ui.menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.domik.R
import app.domik.integrations.emodul.EmodulSeeds
import app.domik.integrations.emodul.EmodulState
import app.domik.ui.theme.DomikTheme

/** Stan integracji eModul w menu (jeden wiersz): co pokazać nad przyciskiem. */
sealed interface EmodulMenuStatus {
    /** Brak integracji (klucza) – przycisk „Dodaj integrację eModul”. */
    data object NotConfigured : EmodulMenuStatus

    /** Klucz jest, dane jeszcze się wczytują. */
    data object Loading : EmodulMenuStatus

    /** eModul odrzucił klucz. */
    data object KeyRejected : EmodulMenuStatus

    /** Klucz jest, ale nie ma żadnych danych (brak połączenia). */
    data object Unavailable : EmodulMenuStatus

    /** Gotowe: [count] danych (kafelków i stref) wybranych do pokazania na pulpicie. */
    data class Ready(val count: Int) : EmodulMenuStatus

    companion object {
        fun of(state: EmodulState): EmodulMenuStatus = when (state) {
            EmodulState.NotConfigured -> NotConfigured
            EmodulState.Loading -> Loading
            EmodulState.KeyRejected -> KeyRejected
            is EmodulState.Failed -> Unavailable
            is EmodulState.Ready -> Ready(state.modules.filter { it.enabled }.sumOf { EmodulSeeds.of(it).size })
        }
    }
}

/**
 * Sekcja „Integracje” w menu (ekran ☰): eModul – bez klucza przycisk „Dodaj integrację eModul”, ze skonfigurowaną
 * integracją wiersz stanu i „Ustawienia eModul”. W trybie demo przycisk otwiera kreator z przykładowym kotłem.
 */
class EmodulMenu(val status: EmodulMenuStatus, val demo: Boolean, val onOpen: () -> Unit)

@Composable
internal fun EmodulMenuSection(menu: EmodulMenu, close: () -> Unit) {
    val colors = DomikTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.menu_integrations), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.menu_emodul_name), style = MaterialTheme.typography.titleMedium)
        val status = menu.status
        val line = when (status) {
            EmodulMenuStatus.NotConfigured ->
                stringResource(if (menu.demo) R.string.menu_emodul_hint_demo else R.string.menu_emodul_hint)
            EmodulMenuStatus.Loading -> stringResource(R.string.menu_emodul_loading)
            EmodulMenuStatus.KeyRejected -> stringResource(R.string.menu_emodul_key_rejected)
            EmodulMenuStatus.Unavailable -> stringResource(R.string.menu_emodul_unavailable)
            is EmodulMenuStatus.Ready -> stringResource(R.string.menu_emodul_ready, status.count)
        }
        Text(
            line,
            style = MaterialTheme.typography.bodyLarge,
            color = if (status == EmodulMenuStatus.KeyRejected) colors.error else colors.textSecondary,
        )
        OutlinedButton(onClick = { close(); menu.onOpen() }, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(
                    if (status == EmodulMenuStatus.NotConfigured) R.string.menu_emodul_add else R.string.menu_emodul_settings,
                ),
            )
        }
    }
}
