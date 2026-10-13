package app.domik.ui.thermo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.domik.R
import app.domik.ui.menu.KeepImmersiveInDialog
import app.domik.ui.theme.DomikTheme

/**
 * Sekcja „Termometr w pokoju” w oknie strefy: odczyt termometru, czujnik Deimic, różnica, kalibracje, status
 * i komunikaty, licznik fazy 1 oraz przyciski „Przywróć kalibrację instalatora” (z potwierdzeniem) i – po zmianie
 * kalibracji poza Domikiem – „Przyjmij nową kalibrację”, a po ręcznym przywróceniu – „Wznów korektę”. Bezstanowa poza oknem potwierdzenia.
 */
@Composable
fun ThermoZoneSectionView(
    section: ThermoZoneSection,
    onRestoreBaseline: () -> Unit,
    onAcknowledge: () -> Unit,
    onResume: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = DomikTheme.colors
    var confirmRestore by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ThermometerIcon(size = 22.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.thermo_zone_section_title),
                style = MaterialTheme.typography.labelLarge,
                color = colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            Text(section.title.resolve(), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
        }
        for (row in section.rows) ValueLine(row)
        Text(section.status.resolve(), style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
        for (notice in section.notices) ProblemText(notice.resolve())
        for (issue in section.issues) ProblemText(issue.resolve())
        section.idle?.let { idle -> HintText(stringResource(R.string.thermo_idle_prefix, idle.resolve())) }
        section.frequency?.let { line ->
            HintText(line.prefix.resolve() + " " + line.parts.map { it.resolve() }.joinToString(", "))
        }
        if (section.canAcknowledge) {
            OutlinedButton(onClick = onAcknowledge, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.thermo_acknowledge))
            }
        }
        if (section.canResume) {
            OutlinedButton(onClick = onResume, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.thermo_resume))
            }
        }
        if (section.canRestore) {
            OutlinedButton(onClick = { confirmRestore = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.thermo_restore))
            }
        }
    }
    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text(stringResource(R.string.thermo_restore_title)) },
            text = {
                KeepImmersiveInDialog()
                Text(stringResource(if (section.writesActive) R.string.thermo_restore_message_live else R.string.thermo_restore_message_dry))
            },
            confirmButton = {
                TextButton(onClick = { confirmRestore = false; onRestoreBaseline() }) {
                    Text(stringResource(R.string.thermo_restore_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestore = false }) { Text(stringResource(R.string.thermo_cancel)) }
            },
        )
    }
}
