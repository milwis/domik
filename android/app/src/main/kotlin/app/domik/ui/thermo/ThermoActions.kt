package app.domik.ui.thermo

import androidx.compose.runtime.compositionLocalOf
import app.domik.data.AppModel
import app.domik.deimic.client.commands.ZoneKey
import app.domik.integrations.thermo.ScanMode
import app.domik.integrations.thermo.ThermoLogEntry
import app.domik.integrations.thermo.ThermoUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Wszystko, czego interfejs termometrów potrzebuje od [AppModel]: stan i akcje. Osobny typ, żeby okno strefy,
 * ekran termometrów i sterownik skanowania nie zależały od całego modelu (i dało się je podglądać z lambdami).
 */
class ThermoActions(
    val state: StateFlow<ThermoUiState>,
    val assign: (mac: String, module: Int, outputNr: Int, inputNr: Int?, label: String) -> Boolean,
    val unassign: (ZoneKey) -> Unit,
    val setEnabled: (Boolean) -> Unit,
    /** Zwraca false, gdy tryb rzeczywisty jest zablokowany flagą funkcji. */
    val setDryRun: (Boolean) -> Boolean,
    val setThreshold: (Float) -> Boolean,
    val setHub: (Boolean) -> Unit,
    val setScanMode: (ScanMode) -> Unit,
    val refreshCalibrations: () -> Unit,
    val restoreBaseline: (ZoneKey) -> Unit,
    val acknowledgeChange: (ZoneKey) -> Unit,
    /** Wznawia korektę strefy wstrzymaną po ręcznym przywróceniu kalibracji instalatora. */
    val resumeCalibration: (ZoneKey) -> Unit = {},
    /** „Rozumiem” przy ostrzeżeniu o kalibracji, która mogła zostać na sterowniku. */
    val dismissLeaveWarning: () -> Unit = {},
    /** Dziennik decyzji (najstarsze pierwsze); czyta pliki – wołać poza wątkiem UI. */
    val log: () -> List<ThermoLogEntry> = { emptyList() },
)

fun AppModel.thermoActions(): ThermoActions = ThermoActions(
    state = thermo,
    assign = { mac, module, outputNr, inputNr, label -> assignThermometer(mac, module, outputNr, inputNr, label) },
    unassign = ::unassignThermometer,
    setEnabled = ::setThermoEnabled,
    setDryRun = ::setThermoDryRun,
    setThreshold = ::setThermoThreshold,
    setHub = ::setThermoHub,
    setScanMode = ::setThermoScanMode,
    refreshCalibrations = ::refreshCalibrations,
    restoreBaseline = ::restoreCalibrationBaseline,
    acknowledgeChange = ::acknowledgeCalibrationChange,
    resumeCalibration = ::resumeCalibration,
    dismissLeaveWarning = ::dismissCalibrationLeaveWarning,
    log = ::thermoLog,
)

/**
 * Prośba „ekran termometrów jest otwarty” → szybkie wyszukiwanie ([ScanMode.DISCOVERY]). Ekran ustawia flagę przy
 * otwarciu i zdejmuje przy zamknięciu; decyzję o trybie skanu podejmuje jedno miejsce ([ThermoBackgroundScan]).
 */
class ThermoDiscoveryRequest {
    val open = MutableStateFlow(false)
}

/** Dostarczane przez `AppRoot`; null w podglądach i testach (ekran działa bez wymuszania skanu). */
val LocalThermoDiscovery = compositionLocalOf<ThermoDiscoveryRequest?> { null }
