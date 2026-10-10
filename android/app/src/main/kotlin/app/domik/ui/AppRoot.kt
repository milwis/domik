package app.domik.ui

import androidx.compose.animation.Crossfade
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.domik.data.AppModel
import app.domik.data.Route
import app.domik.ui.dashboard.DashboardRoute
import app.domik.ui.onboarding.TermsScreen
import app.domik.ui.onboarding.WelcomeScreen
import app.domik.ui.settings.ConnectionSettingsScreen

/** Korzeń interfejsu: wybiera ekran według [AppModel.route] (bez biblioteki nawigacji – stan wystarcza). */
@Composable
fun AppRoot(model: AppModel) {
    val route by model.route.collectAsStateWithLifecycle()
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
