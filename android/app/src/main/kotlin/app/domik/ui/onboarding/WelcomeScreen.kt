package app.domik.ui.onboarding

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.domik.R
import app.domik.ui.components.ScreenColumn
import app.domik.ui.theme.DomikTheme

/** Wybór po akceptacji regulaminu: własny sterownik albo tryb demo. */
@Composable
fun WelcomeScreen(onConnectController: () -> Unit, onTryDemo: () -> Unit) {
    val colors = DomikTheme.colors
    ScreenColumn(centerVertically = true) {
        Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.headlineLarge, color = colors.textPrimary)
        Text(stringResource(R.string.app_subtitle), style = MaterialTheme.typography.bodyLarge, color = colors.textSecondary)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onConnectController, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.welcome_connect))
        }
        Text(stringResource(R.string.welcome_connect_hint), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onTryDemo, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.welcome_demo))
        }
        Text(stringResource(R.string.welcome_demo_hint), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
    }
}
