package app.domik.ui.menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.domik.R
import app.domik.data.AppInfo
import app.domik.data.AppMode
import app.domik.data.KioskModel
import app.domik.ui.theme.AppearanceSettings
import app.domik.ui.theme.DomikShapes
import app.domik.ui.theme.DomikTheme

/** Działania z menu dotyczące połączenia; każde zamyka arkusz. */
class MenuActions(
    val onConnectionSettings: () -> Unit,
    val onLeaveDemo: () -> Unit,
    val onSwitchToDemo: () -> Unit,
)

/**
 * Arkusz otwierany ikoną ☰: tryb pracy i ustawienia połączenia (przejście z trybu demo do własnego sterownika
 * i z powrotem), pogoda i kalendarze (tylko w trybie sterownika – [weather], [calendar]), wygląd i tryb tabletu
 * ściennego ([kiosk] – domyślnie z [LocalKioskModel]; bez niego sekcji nie ma) oraz „O aplikacji” (wersja, licencje,
 * dokumenty, odnośniki – zawsze na dole).
 * [focusWeather] ustawia kursor w polu wyszukiwania miejscowości (menu otwarte z karty „Wybierz miejscowość”).
 * [onWeatherOptions] otwiera okno „Pogoda – co pokazywać” (arkusz się zamyka); dostępne też w trybie demo.
 * [onThermo] otwiera pełnoekranowe okno „Termometry pokojowe” (arkusz się zamyka); dostępne też w trybie demo.
 * [thermoWarning] pokazuje przy tej pozycji ostrzeżenie, że na sterowniku mogła zostać kalibracja ustawiona przez Domik.
 * [emodul] – sekcja „Integracje” (eModul): dodanie integracji / ustawienia (arkusz się zamyka); dostępna też w trybie demo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuSheet(
    info: AppInfo,
    appearance: AppearanceSettings,
    onAppearanceChange: (AppearanceSettings) -> Unit,
    actions: MenuActions,
    onDismiss: () -> Unit,
    calendar: CalendarMenu? = null,
    kiosk: KioskModel? = LocalKioskModel.current,
    weather: WeatherMenu? = null,
    focusWeather: Boolean = false,
    onWeatherOptions: (() -> Unit)? = null,
    onThermo: (() -> Unit)? = null,
    thermoWarning: Boolean = false,
    emodul: EmodulMenu? = null,
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
        KeepImmersiveInDialog()
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            ConnectionSection(info, actions, onDismiss)
            val openOptions = onWeatherOptions?.let { open -> { onDismiss(); open() } }
            if (weather != null) {
                WeatherSection(weather, focusSearch = focusWeather, close = onDismiss, onOptions = openOptions)
            } else if (openOptions != null) {
                WeatherOptionsOnlySection(openOptions)
            }
            if (calendar != null) CalendarSection(calendar)
            if (onThermo != null) ThermoMenuSection(thermoWarning) { onDismiss(); onThermo() }
            if (emodul != null) EmodulMenuSection(emodul, close = onDismiss)
            AppearanceSection(appearance, onAppearanceChange)
            if (kiosk != null) KioskSection(kiosk)
            AboutSection()
        }
    }
}

@Composable
private fun ConnectionSection(info: AppInfo, actions: MenuActions, close: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.menu_connection), style = MaterialTheme.typography.headlineSmall)
        val label = when (info.mode) {
            AppMode.DEMO -> stringResource(R.string.menu_mode_demo)
            else -> stringResource(
                R.string.menu_mode_controller,
                info.controllerName.ifBlank { info.controllerHost.orEmpty() },
            )
        }
        Text(label, style = MaterialTheme.typography.bodyLarge, color = DomikTheme.colors.textSecondary)
        when (info.mode) {
            AppMode.DEMO -> {
                MenuButton(R.string.menu_connect_controller) { close(); actions.onConnectionSettings() }
                MenuButton(R.string.menu_leave_demo) { close(); actions.onLeaveDemo() }
            }
            else -> {
                MenuButton(R.string.menu_connection_settings) { close(); actions.onConnectionSettings() }
                MenuButton(R.string.menu_switch_to_demo) { close(); actions.onSwitchToDemo() }
            }
        }
    }
}

/** Pozycja „Termometry pokojowe”: opis i przycisk otwierający okno termometrów. */
@Composable
private fun ThermoMenuSection(warning: Boolean, onOpen: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.menu_thermo), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.menu_thermo_hint),
            style = MaterialTheme.typography.bodyLarge,
            color = DomikTheme.colors.textSecondary,
        )
        if (warning) {
            Text(
                stringResource(R.string.menu_thermo_warning),
                style = MaterialTheme.typography.bodyLarge,
                color = DomikTheme.colors.error,
            )
        }
        MenuButton(R.string.menu_thermo_open, onOpen)
    }
}

@Composable
private fun MenuButton(textRes: Int, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(textRes))
    }
}
