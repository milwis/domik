package app.domik.ui.dashboard

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.domik.R
import app.domik.integrations.weather.WeatherOption
import app.domik.integrations.weather.WeatherOptions
import app.domik.ui.menu.KeepImmersiveInDialog
import app.domik.ui.theme.DomikTheme

/**
 * Okno „Pogoda – co pokazywać”: pola wyboru elementów kafelka pogody ([WeatherOption]). Zmiana działa od razu
 * ([onChange] dostaje nowy wybór) – kafelek za oknem przebudowuje się na bieżąco, więc widać efekt; „Gotowe”
 * tylko zamyka okno. „Przywróć domyślne” ustawia [WeatherOptions.DEFAULT]. Otwierane z trybu edycji pulpitu
 * (dotknięcie kafelka pogody) i z menu (sekcja „Pogoda”).
 */
@Composable
fun WeatherOptionsDialog(
    options: WeatherOptions,
    onChange: (WeatherOptions) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DomikTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.weather_options_title)) },
        text = {
            KeepImmersiveInDialog()
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.weather_options_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                )
                Spacer(Modifier.height(12.dp))
                GroupTitle(R.string.weather_options_group_forecast)
                for (option in FORECAST_OPTIONS) OptionRow(option, options, onChange)
                Spacer(Modifier.height(8.dp))
                GroupTitle(R.string.weather_options_group_details)
                for (option in WeatherOptions.DETAIL_OPTIONS) OptionRow(option, options, onChange)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.weather_options_done)) } },
        dismissButton = {
            TextButton(
                onClick = { onChange(WeatherOptions.DEFAULT) },
                enabled = options != WeatherOptions.DEFAULT,
            ) { Text(stringResource(R.string.weather_options_reset)) }
        },
    )
}

private val FORECAST_OPTIONS = listOf(WeatherOption.Hourly, WeatherOption.Daily, WeatherOption.PrecipitationChance)

@StringRes
internal fun WeatherOption.labelRes(): Int = when (this) {
    WeatherOption.Hourly -> R.string.weather_option_hourly
    WeatherOption.Daily -> R.string.weather_option_daily
    WeatherOption.PrecipitationChance -> R.string.weather_option_precip_chance
    WeatherOption.PrecipitationSum -> R.string.weather_option_precip_sum
    WeatherOption.FeelsLike -> R.string.weather_option_feels_like
    WeatherOption.Wind -> R.string.weather_option_wind
    WeatherOption.Humidity -> R.string.weather_option_humidity
    WeatherOption.UvIndex -> R.string.weather_option_uv
    WeatherOption.Sun -> R.string.weather_option_sun
}

@Composable
private fun GroupTitle(@StringRes title: Int) {
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = DomikTheme.colors.textPrimary,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

/** Wiersz z polem wyboru; cały wiersz (min. 48 dp) jest celem dotyku i jednym elementem dostępności. */
@Composable
private fun OptionRow(option: WeatherOption, options: WeatherOptions, onChange: (WeatherOptions) -> Unit) {
    val checked = option in options
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onChange(options.with(option, it)) }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(12.dp))
        Text(stringResource(option.labelRes()), style = MaterialTheme.typography.bodyLarge, color = DomikTheme.colors.textPrimary)
    }
}
