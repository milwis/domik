package app.domik.ui.dashboard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.domik.R
import app.domik.ui.theme.DomikTheme

/**
 * Potwierdzenie przełączenia wyjścia alarmowego (dotknięcie kafelka – i przy włączaniu, i przy wyłączaniu):
 * „Na pewno? To wyjście steruje alarmem.” i nazwa wyjścia. Do potwierdzenia nic nie jest wysyłane do sterownika.
 */
@Composable
fun AlarmConfirmDialog(request: ConfirmRequest, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = DomikTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.alarm_confirm_title)) },
        text = {
            Column {
                Text(stringResource(R.string.alarm_confirm_message), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(12.dp))
                Text(request.name, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(if (request.turnOn) R.string.alarm_confirm_on else R.string.alarm_confirm_off))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.edit_cancel)) } },
    )
}
