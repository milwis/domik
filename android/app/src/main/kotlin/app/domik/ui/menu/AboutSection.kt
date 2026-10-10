package app.domik.ui.menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.domik.R
import app.domik.platform.readAppVersion
import app.domik.ui.theme.DomikTheme

/**
 * Sekcja „O aplikacji” na dole menu: nazwa, wersja (odczytana z systemu) i przycisk otwierający okno z licencjami,
 * dokumentami i odnośnikami ([AboutDialog]). Okno otwiera się nad menu – po jego zamknięciu wracamy do menu.
 */
@Composable
fun AboutSection() {
    val context = LocalContext.current
    val version = remember(context) { readAppVersion(context) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    val colors = DomikTheme.colors

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.about_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            text = stringResource(R.string.app_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.textSecondary,
        )
        if (version != null) {
            Text(
                text = stringResource(R.string.about_version, version.name, version.code),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
            )
        }
        OutlinedButton(onClick = { showAbout = true }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.about_open))
        }
    }

    if (showAbout) AboutDialog(version = version, onDismiss = { showAbout = false })
}
