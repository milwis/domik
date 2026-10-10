package app.domik.ui.menu

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.domik.R
import app.domik.ui.components.DeviceIcon
import app.domik.ui.components.LightTile
import app.domik.ui.theme.AccentPalette
import app.domik.ui.theme.AppearanceSettings
import app.domik.ui.theme.DomikTheme
import app.domik.ui.theme.ThemeMode
import app.domik.ui.theme.labelRes
import java.time.LocalTime
import java.util.Locale

/**
 * Sekcja „Wygląd” w menu: tryb jasny/ciemny/wg godzin, kolor akcentu i podgląd dwóch kafelków
 * (włączony/wyłączony) w bieżącym motywie. Zmiany od razu trafiają do [onAppearanceChange].
 */
@Composable
fun AppearanceSection(
    appearance: AppearanceSettings,
    onAppearanceChange: (AppearanceSettings) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(stringResource(R.string.appearance_title), style = MaterialTheme.typography.headlineSmall)

        Section(stringResource(R.string.appearance_theme)) {
            ThemeModePicker(appearance.mode) { onAppearanceChange(appearance.copy(mode = it)) }
            val mode = appearance.mode
            if (mode is ThemeMode.Scheduled) {
                TimeStepper(stringResource(R.string.appearance_dark_from), mode.darkFrom) {
                    onAppearanceChange(appearance.copy(mode = mode.copy(darkFrom = it)))
                }
                TimeStepper(stringResource(R.string.appearance_dark_until), mode.darkUntil) {
                    onAppearanceChange(appearance.copy(mode = mode.copy(darkUntil = it)))
                }
            }
        }

        Section(stringResource(R.string.appearance_color)) {
            PalettePicker(appearance.palette) { onAppearanceChange(appearance.copy(palette = it)) }
        }

        Section(stringResource(R.string.appearance_preview)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                repeat(2) { index ->
                    LightTile(
                        name = stringResource(R.string.appearance_preview_name),
                        room = stringResource(R.string.appearance_preview_room),
                        on = index == 0,
                        onToggle = {},
                        icon = DeviceIcon.Bulb,
                        modifier = Modifier.width(150.dp).aspectRatio(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = DomikTheme.colors.textSecondary,
        )
        content()
    }
}

private enum class ModeKind(val labelRes: Int) {
    System(R.string.theme_mode_system),
    Light(R.string.theme_mode_light),
    Dark(R.string.theme_mode_dark),
    Scheduled(R.string.theme_mode_scheduled),
}

private fun ThemeMode.kind() = when (this) {
    ThemeMode.System -> ModeKind.System
    ThemeMode.Light -> ModeKind.Light
    ThemeMode.Dark -> ModeKind.Dark
    is ThemeMode.Scheduled -> ModeKind.Scheduled
}

@Composable
private fun ThemeModePicker(mode: ThemeMode, onChange: (ThemeMode) -> Unit) {
    val kinds = ModeKind.entries
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        kinds.forEachIndexed { index, kind ->
            SegmentedButton(
                selected = mode.kind() == kind,
                onClick = {
                    if (mode.kind() != kind) {
                        onChange(
                            when (kind) {
                                ModeKind.System -> ThemeMode.System
                                ModeKind.Light -> ThemeMode.Light
                                ModeKind.Dark -> ThemeMode.Dark
                                ModeKind.Scheduled -> ThemeMode.Scheduled()
                            },
                        )
                    }
                },
                shape = SegmentedButtonDefaults.itemShape(index, kinds.size),
                icon = {},
                label = {
                    Text(
                        text = stringResource(kind.labelRes),
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
            )
        }
    }
}

/** Zmiana godziny o 30 min przyciskami −/+ (zastępuje wybierak godziny w podglądzie). */
@Composable
private fun TimeStepper(label: String, time: LocalTime, onChange: (LocalTime) -> Unit) {
    val colors = DomikTheme.colors
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = { onChange(time.minusMinutes(30)) }) {
            Icon(
                painterResource(R.drawable.ic_remove),
                contentDescription = stringResource(R.string.appearance_time_earlier),
                tint = colors.textPrimary,
            )
        }
        Text(
            text = String.format(Locale.ROOT, "%02d:%02d", time.hour, time.minute),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(64.dp),
        )
        IconButton(onClick = { onChange(time.plusMinutes(30)) }) {
            Icon(
                painterResource(R.drawable.ic_add),
                contentDescription = stringResource(R.string.appearance_time_later),
                tint = colors.textPrimary,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PalettePicker(selected: AccentPalette, onSelect: (AccentPalette) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AccentPalette.availableOn(Build.VERSION.SDK_INT).forEach { palette ->
            Swatch(palette, selected = palette == selected, onClick = { onSelect(palette) })
        }
    }
}

/** Kółko z kolorem akcentu palety w bieżącym motywie; wybrane – z obwódką i znacznikiem. */
@Composable
private fun Swatch(palette: AccentPalette, selected: Boolean, onClick: () -> Unit) {
    val colors = DomikTheme.colors
    val context = LocalContext.current
    val fill: Color = when (val variant = palette.variant(colors.isDark)) {
        null -> if (Build.VERSION.SDK_INT >= AccentPalette.DYNAMIC_MIN_SDK) {
            (if (colors.isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)).primary
        } else {
            colors.accent
        }
        else -> Color(variant.accent)
    }
    val name = stringResource(palette.labelRes)
    val selectedText = stringResource(R.string.appearance_selected)
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .then(
                if (selected) {
                    Modifier.border(3.dp, SolidColor(colors.textPrimary), CircleShape)
                } else {
                    Modifier
                },
            )
            .padding(if (selected) 5.dp else 0.dp)
            .clip(CircleShape)
            .background(fill)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics {
                contentDescription = if (selected) "$name, $selectedText" else name
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Image(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = null,
                colorFilter = ColorFilter.tint(contrastOn(fill)),
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

private fun contrastOn(background: Color): Color =
    if (background.luminance() > 0.4f) Color(0xFF10141B) else Color.White
