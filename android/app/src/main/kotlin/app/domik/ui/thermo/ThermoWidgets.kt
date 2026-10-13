package app.domik.ui.thermo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.domik.ui.components.DISABLED_ALPHA
import app.domik.ui.theme.DomikTheme
import kotlinx.coroutines.delay

/** Wspólne klocki ekranu termometrów i sekcji w oknie strefy. */

@Composable
fun ResText.resolve(): String = stringResource(res, *args.toTypedArray())

/** Czas ścienny odświeżany co [periodMillis] – żeby „5 min temu” nie stało w miejscu między odczytami. */
@Composable
fun rememberWallClock(periodMillis: Long = 15_000L): State<Long> {
    val state = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMillis) {
        while (true) {
            delay(periodMillis)
            state.longValue = System.currentTimeMillis()
        }
    }
    return state
}

/** Karta sekcji: tło `card`, w ciemnym motywie z obrysem (jak karta okna strefy). */
@Composable
fun ThermoCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = DomikTheme.colors
    val shape = MaterialTheme.shapes.large
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.card)
            .then(if (colors.isDark) Modifier.border(1.dp, colors.cardBorder, shape) else Modifier)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, color = DomikTheme.colors.textPrimary)
}

@Composable
fun HintText(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = DomikTheme.colors.textSecondary, modifier = modifier)
}

@Composable
fun ProblemText(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = DomikTheme.colors.error, modifier = modifier)
}

/** Wiersz „etykieta ........ wartość” (okno strefy, tabele). */
@Composable
fun ValueLine(value: ThermoValue) {
    val colors = DomikTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(value.label),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value.value.resolve(),
            style = MaterialTheme.typography.titleSmall,
            color = if (value.warning) colors.error else colors.textPrimary,
        )
    }
}

/** Wartości w rzędzie z zawijaniem: mała etykieta nad wartością (karty na liście termometrów). */
@Composable
fun ValueFlow(values: List<ThermoValue>) {
    val colors = DomikTheme.colors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (value in values) {
            Column {
                Text(stringResource(value.label), style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                Text(
                    value.value.resolve(),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (value.warning) colors.error else colors.textPrimary,
                )
            }
        }
    }
}

/** Przełącznik z opisem; dotyk obsługuje cały wiersz (jak w sekcji „Tablet ścienny”). */
@Composable
fun ThermoSwitchRow(
    title: String,
    hint: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val colors = DomikTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.textPrimary)
            if (hint != null) HintText(hint)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** Mała ikona termometru (kafelek, nagłówki). */
@Composable
fun ThermometerIcon(modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 20.dp, tint: androidx.compose.ui.graphics.Color = DomikTheme.colors.accent) {
    Icon(
        painter = painterResource(app.domik.R.drawable.ic_thermostat_outline),
        contentDescription = null,
        tint = tint,
        modifier = modifier.size(size),
    )
}
