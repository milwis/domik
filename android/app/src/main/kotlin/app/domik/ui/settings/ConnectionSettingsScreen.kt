package app.domik.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.domik.R
import app.domik.data.AppModel
import app.domik.data.ConnectionFormState
import app.domik.data.ConnectionTestOutcome
import app.domik.data.ConnectionTestState
import app.domik.ui.components.ScreenColumn
import app.domik.ui.theme.DomikTheme

/** Formularz połączenia ze sterownikiem: adres, port, numer seryjny, test połączenia i zapis. */
@Composable
fun ConnectionSettingsScreen(model: AppModel) {
    val form by model.form.collectAsStateWithLifecycle()
    ConnectionSettingsContent(
        form = form,
        onName = model::editName,
        onHost = model::editHost,
        onPort = model::editPort,
        onSerial = model::editSerial,
        onTest = model::testConnection,
        onSave = model::saveConnection,
        onCancel = model::cancelConnectionSettings,
        onForget = model::forgetController,
    )
}

@Composable
internal fun ConnectionSettingsContent(
    form: ConnectionFormState,
    onName: (String) -> Unit,
    onHost: (String) -> Unit,
    onPort: (String) -> Unit,
    onSerial: (String) -> Unit,
    onTest: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onForget: () -> Unit,
) {
    val colors = DomikTheme.colors
    var confirmForget by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = form.canCancel, onBack = onCancel)

    ScreenColumn {
        Text(stringResource(R.string.connection_title), style = MaterialTheme.typography.headlineMedium, color = colors.textPrimary)
        Text(stringResource(R.string.connection_intro), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)

        OutlinedTextField(
            value = form.name,
            onValueChange = onName,
            label = { Text(stringResource(R.string.connection_name)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.host,
            onValueChange = onHost,
            label = { Text(stringResource(R.string.connection_host)) },
            placeholder = { Text(stringResource(R.string.connection_host_hint)) },
            supportingText = {
                Text(stringResource(form.hostError?.messageRes() ?: R.string.connection_host_supporting))
            },
            isError = form.hostError != null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.port,
            onValueChange = onPort,
            label = { Text(stringResource(R.string.connection_port)) },
            placeholder = { Text(stringResource(R.string.connection_port_hint)) },
            supportingText = {
                Text(stringResource(form.portError?.messageRes() ?: R.string.connection_port_supporting))
            },
            isError = form.portError != null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.serial,
            onValueChange = onSerial,
            label = { Text(stringResource(R.string.connection_serial)) },
            placeholder = if (form.serialStored) {
                { Text(stringResource(R.string.connection_serial_stored)) }
            } else {
                null
            },
            supportingText = {
                Text(stringResource(form.serialError?.messageRes() ?: R.string.connection_serial_supporting))
            },
            isError = form.serialError != null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        if (form.storageFailed) {
            Text(
                stringResource(R.string.connection_storage_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.error,
            )
        }

        OutlinedButton(
            onClick = onTest,
            enabled = form.test != ConnectionTestState.Running,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.connection_test))
        }
        TestResult(form.test)

        Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.connection_save))
        }
        if (form.canCancel) {
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.connection_cancel))
            }
        }
        if (form.canForget) {
            TextButton(onClick = { confirmForget = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.connection_forget), color = colors.error)
            }
        }
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text(stringResource(R.string.connection_forget_title)) },
            text = { Text(stringResource(R.string.connection_forget_message)) },
            confirmButton = {
                TextButton(onClick = { confirmForget = false; onForget() }) {
                    Text(stringResource(R.string.connection_forget_confirm), color = colors.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmForget = false }) {
                    Text(stringResource(R.string.connection_cancel))
                }
            },
        )
    }
}

/** Wynik testu: kręcące się kółko, potwierdzenie z wersją albo powód niepowodzenia. */
@Composable
private fun TestResult(test: ConnectionTestState) {
    val colors = DomikTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (test) {
            ConnectionTestState.Idle -> Unit
            ConnectionTestState.Running -> {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.connection_testing), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
            }
            is ConnectionTestState.Done -> when (val outcome = test.outcome) {
                is ConnectionTestOutcome.Success -> Text(
                    text = outcome.firmware?.let { stringResource(R.string.connection_test_ok_version, it) }
                        ?: stringResource(R.string.connection_test_ok),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.accent,
                )
                is ConnectionTestOutcome.Failure -> Text(
                    text = stringResource(outcome.reason.messageRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.error,
                )
            }
        }
    }
}
