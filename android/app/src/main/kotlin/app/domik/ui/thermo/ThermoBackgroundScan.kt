package app.domik.ui.thermo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.domik.platform.BlePermissions
import app.domik.platform.ThermoService

/**
 * **Jedyne miejsce**, które ustawia tryb skanowania termometrów i uruchamia / zatrzymuje usługę pierwszoplanową.
 * Decyzja ([thermoScanPlan]): hub + funkcja włączona (w demo zawsze) → tło; otwarty ekran termometrów → wyszukiwanie;
 * inaczej wyłączone. Efekty zależą od stanu, więc po restarcie sesji (nowy runtime startuje z trybem OFF) tryb jest
 * ustawiany od nowa. Usługa startuje tylko z przyznaną zgodą na skan i nie w trybie demo; po powrocie z ustawień
 * systemu (nowa zgoda) wniosek jest oceniany ponownie.
 */
@Composable
fun ThermoBackgroundScan(actions: ThermoActions, discovery: ThermoDiscoveryRequest) {
    val context = LocalContext.current
    val state by actions.state.collectAsStateWithLifecycle()
    val discoveryOpen by discovery.open.collectAsStateWithLifecycle()
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumes++ }

    val plan = thermoScanPlan(state, discoveryOpen)
    LaunchedEffect(plan.mode, state.active, state.scanMode) {
        if (state.active && state.scanMode != plan.mode) actions.setScanMode(plan.mode)
    }
    LaunchedEffect(plan.service, resumes) {
        if (plan.service && BlePermissions.hasScanPermissions(context)) {
            ThermoService.start(context)
        } else {
            ThermoService.stop(context)
        }
    }
}
