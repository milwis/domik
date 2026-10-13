package app.domik.ui

import androidx.compose.animation.Crossfade
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.domik.data.AppModel
import app.domik.data.Route
import app.domik.ui.dashboard.DashboardRoute
import app.domik.ui.onboarding.TermsScreen
import app.domik.ui.onboarding.WelcomeScreen
import app.domik.ui.settings.ConnectionSettingsScreen
import app.domik.ui.thermo.LocalThermoDiscovery
import app.domik.ui.thermo.ThermoBackgroundScan
import app.domik.ui.thermo.ThermoDiscoveryRequest
import app.domik.ui.thermo.thermoActions

/** Korzeń interfejsu: wybiera ekran według [AppModel.route] (bez biblioteki nawigacji – stan wystarcza). */
@Composable
fun AppRoot(model: AppModel) {
    val route by model.route.collectAsStateWithLifecycle()
    // Skanowanie termometrów pokojowych: jedno miejsce decyzji o trybie skanu i usłudze w tle (bez względu na ekran).
    val thermoActions = remember(model) { model.thermoActions() }
    val thermoDiscovery = remember { ThermoDiscoveryRequest() }
    ThermoBackgroundScan(thermoActions, thermoDiscovery)
    CompositionLocalProvider(LocalThermoDiscovery provides thermoDiscovery) {
        Crossfade(targetState = route, label = "route") { current ->
            when (current) {
                Route.TERMS -> TermsScreen(onAccept = model::acceptTerms)
                Route.WELCOME -> WelcomeScreen(
                    onConnectController = model::openConnectionSettings,
                    onTryDemo = model::chooseDemo,
                )
                Route.CONNECTION_SETTINGS -> ConnectionSettingsScreen(model)
                Route.DASHBOARD -> DashboardRoute(model)
            }
        }
    }
}
