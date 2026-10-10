package app.domik.ui.dashboard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.domik.R
import app.domik.data.HomeEvent
import app.domik.data.HomeRepository
import app.domik.data.TileId
import app.domik.deimic.client.ConnectionState
import app.domik.ui.components.DISABLED_ALPHA
import app.domik.ui.components.DeviceIcon
import app.domik.ui.components.DeviceIconGlow
import app.domik.ui.components.HeatingControls
import app.domik.ui.components.HeatingProfile
import app.domik.ui.components.HeatingTileState
import app.domik.ui.components.RoundControl
import app.domik.ui.components.controls
import app.domik.ui.components.dotColor
import app.domik.ui.components.formatTemperature
import app.domik.ui.theme.DomikShapes
import app.domik.ui.theme.DomikTheme
import app.domik.ui.theme.ThemeMode
import kotlinx.coroutines.delay

/*
 * Okno strefy ogrzewania: otwiera się po stuknięciu w kafelek strefy i pozwala zmienić
 * temperaturę zadaną (− / + co 0,5 °C) oraz profil (Wyłączone · Eko · Komfort · Maks.). Na tablecie to wyśrodkowana
 * karta, na wąskim ekranie (< 600 dp) arkusz od dołu.
 */

/** Jak długo komunikat o nieudanym poleceniu zostaje w oknie. */
private const val FAILURE_SHOWN_MS = 8_000L

private val CARD_MAX_WIDTH = 480.dp
private val DIALOG_MARGIN = 24.dp
private val CONTENT_PADDING = 24.dp

/** Ostatni znany stan strefy – nie jest stanem Compose (nie wywołuje rekompozycji), tylko pamięcią między nimi. */
private class LastKnownZone {
    var model: ZoneDialogModel? = null
}

/**
 * Okno strefy [zone]. Pokazuje stan na żywo z [repository] (także zmiany z innych źródeł: sterownik, drugi tablet)
 * i wysyła polecenia tymi samymi wywołaniami co duży kafelek ([HomeRepository.adjustSetpoint], [HomeRepository.setProfile]).
 *
 * Gdy strefa zniknie z konfiguracji albo padnie połączenie, okno zostaje z ostatnim znanym stanem, kontrolki są
 * nieaktywne, a komunikat mówi dlaczego. Zamykanie: przycisk „Zamknij”, dotknięcie poza oknem, „Wstecz”.
 */
@Composable
fun HeatingZoneDialog(repository: HomeRepository, zone: TileId.Zone, onDismiss: () -> Unit) {
    val home by repository.state.collectAsStateWithLifecycle()
    val connection by repository.connectionState.collectAsStateWithLifecycle()
    val model = remember(home, zone) { zoneDialogModel(home, zone) }
    val lastKnown = remember { LastKnownZone() }
    if (model != null) lastKnown.model = model

    // Błąd polecenia z tego okna: pasek z komunikatem (snackbar pulpitu jest pod oknem, więc go nie widać).
    var failure by remember { mutableStateOf<String?>(null) }
    val zoneName by rememberUpdatedState((model ?: lastKnown.model)?.tile?.name)
    val resources = LocalContext.current.resources
    LaunchedEffect(repository) {
        repository.events.collect { event ->
            if (event is HomeEvent.CommandFailed && event.subject == zoneName) failure = event.message(resources)
        }
    }
    LaunchedEffect(failure) {
        if (failure != null) {
            delay(FAILURE_SHOWN_MS)
            failure = null
        }
    }

    val ui = zoneDialogUi(model, lastKnown.model, online = connection == ConnectionState.Ready, failed = failure != null)
    val compact = LocalConfiguration.current.screenWidthDp < 600
    val panel: @Composable ColumnScope.() -> Unit = {
        HeatingZonePanel(
            ui = ui,
            failure = failure,
            controlSize = if (compact) 56.dp else 64.dp,
            chipHeight = if (compact) 52.dp else 56.dp,
            onDecrease = {
                failure = null
                repository.adjustSetpoint(zone.module, zone.outputNr, -1)
            },
            onIncrease = {
                failure = null
                repository.adjustSetpoint(zone.module, zone.outputNr, +1)
            },
            onSelectProfile = { profile ->
                failure = null
                repository.setProfile(zone.module, zone.outputNr, profile.toProtocolProfile())
            },
            onClose = onDismiss,
        )
    }
    val title = stringResource(R.string.heating_dialog_title, ui.tile?.name ?: stringResource(R.string.heating_dialog_default_name))
    if (compact) {
        ZoneSheet(title, onDismiss, panel)
    } else {
        ZoneCard(title, onDismiss, panel)
    }
}

/** Telefon / wąskie okno: arkusz od dołu (28 dp górne rogi). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ZoneSheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val colors = DomikTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = DomikShapes.sheet,
        containerColor = colors.card,
        contentColor = colors.textPrimary,
    ) {
        Column(
            modifier = Modifier
                .semantics { paneTitle = title }
                .verticalScroll(rememberScrollState())
                .padding(horizontal = CONTENT_PADDING)
                .padding(bottom = 16.dp)
                .navigationBarsPadding(),
            content = content,
        )
    }
}

/**
 * Tablet / szerokie okno: wyśrodkowana karta (do [CARD_MAX_WIDTH]) na przyciemnionym tle. Dotknięcie poza kartą
 * zamyka okno; samo okno systemowe jest na cały ekran, więc dotknięcia łapie przezroczysta warstwa pod kartą.
 */
@Composable
private fun ZoneCard(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val colors = DomikTheme.colors
    val shape = MaterialTheme.shapes.extraLarge
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) }
                .safeDrawingPadding()
                .padding(DIALOG_MARGIN),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = CARD_MAX_WIDTH)
                    .fillMaxWidth()
                    .clip(shape)
                    .background(colors.card)
                    .then(if (colors.isDark) Modifier.border(1.dp, colors.cardBorder, shape) else Modifier)
                    // Dotknięcia karty nie dochodzą do warstwy pod nią (nie zamykają okna).
                    .pointerInput(Unit) { detectTapGestures { } }
                    .semantics { paneTitle = title }
                    .verticalScroll(rememberScrollState())
                    .padding(CONTENT_PADDING),
                content = content,
            )
        }
    }
}

/**
 * Zawartość okna strefy – bezstanowa (stan i callbacki z [HeatingZoneDialog]): nagłówek, temperatura aktualna,
 * zadana z − / +, profil, komunikat i „Zamknij”.
 */
@Composable
internal fun HeatingZonePanel(
    ui: ZoneDialogUi,
    failure: String?,
    controlSize: Dp,
    chipHeight: Dp,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    onSelectProfile: (HeatingProfile) -> Unit,
    onClose: () -> Unit,
) {
    val tile = ui.tile
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        ZoneHeader(tile)
        CurrentTemperature(tile)
        SetpointControls(tile, ui.controls, controlSize, onDecrease, onIncrease)
        if (tile != null) {
            ProfileSection(tile, ui.controls, chipHeight, onSelectProfile)
        }
        StatusLine(ui.status, failure)
        Button(
            onClick = onClose,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) {
            Text(stringResource(R.string.heating_dialog_close), style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun ZoneHeader(tile: HeatingTileState?) {
    val colors = DomikTheme.colors
    val off = tile?.profile == HeatingProfile.Off
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        DeviceIconGlow(DeviceIcon.Thermostat, lit = !off, tint = if (off) colors.iconOff else colors.accent, size = 36.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = tile?.name ?: stringResource(R.string.heating_dialog_default_name),
                style = MaterialTheme.typography.headlineSmall,
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!tile?.room.isNullOrBlank()) {
                Text(
                    text = tile?.room.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (tile != null && tile.heating && !off) {
            Spacer(Modifier.width(12.dp))
            Row(
                modifier = Modifier.height(36.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_flame),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(colors.heating),
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.heating_is_heating),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun CurrentTemperature(tile: HeatingTileState?) {
    val colors = DomikTheme.colors
    val current = formatTemperature(tile?.currentTemp)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.heating_dialog_current),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = current,
            style = MaterialTheme.typography.headlineMedium,
            color = colors.textPrimary,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** Duża temperatura zadana z − / + po bokach; zmiana jest ogłaszana czytnikom ekranu (strefa „live”). */
@Composable
private fun SetpointControls(
    tile: HeatingTileState?,
    controls: HeatingControls,
    controlSize: Dp,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    val colors = DomikTheme.colors
    val off = tile?.profile == HeatingProfile.Off
    val target = formatTemperature(tile?.setpoint)
    val label = stringResource(R.string.heating_dialog_target)
    val description = if (tile?.setpoint == null) label else stringResource(R.string.heating_dialog_target_description, target)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(lerp(colors.card, colors.textSecondary, 0.07f))
            .padding(horizontal = 12.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        RoundControl(
            iconRes = R.drawable.ic_remove,
            description = stringResource(R.string.heating_decrease),
            container = colors.cool,
            enabled = controls.canDecrease,
            size = controlSize,
            onClick = onDecrease,
        )
        Column(Modifier.weight(1f).padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
            Text(
                text = target,
                style = MaterialTheme.typography.displayLarge,
                color = if (off) colors.textSecondary else colors.textPrimary,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = description
                },
            )
        }
        RoundControl(
            iconRes = R.drawable.ic_add,
            description = stringResource(if (controls.increaseSwitchesOn) R.string.heating_switch_on else R.string.heating_increase),
            container = colors.heating,
            enabled = controls.canIncrease,
            size = controlSize,
            onClick = onIncrease,
        )
    }
}

/** Wybór profilu: cztery przyciski ([ProfileChipsLayout]); przy wyłączonym przełączaniu sama etykieta. */
@Composable
private fun ProfileSection(
    tile: HeatingTileState,
    controls: HeatingControls,
    chipHeight: Dp,
    onSelect: (HeatingProfile) -> Unit,
) {
    val colors = DomikTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.heating_dialog_profile),
            style = MaterialTheme.typography.labelLarge,
            color = colors.textSecondary,
        )
        if (!tile.profileSwitching) {
            Row(Modifier.height(chipHeight), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(tile.profile.dotColor(colors)))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(tile.profile.labelRes), style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
            }
            return@Column
        }
        ProfileChipsLayout(Modifier.fillMaxWidth().selectableGroup(), gap = 8.dp) {
            HeatingProfile.entries.forEach { profile ->
                ProfileChip(
                    profile = profile,
                    selected = profile == tile.profile,
                    enabled = controls.profileEnabled,
                    height = chipHeight,
                    onClick = { if (profile != tile.profile) onSelect(profile) },
                )
            }
        }
    }
}

/**
 * Przyciski profilu o równej szerokości: wszystkie w jednym rzędzie, jeśli każda etykieta mieści się w swojej
 * komórce, inaczej 2×2, a w ostateczności jeden pod drugim. O układzie decyduje faktyczna szerokość etykiet
 * (język, skala czcionki, szerokość okna), więc nic nie jest obcinane.
 */
@Composable
private fun ProfileChipsLayout(modifier: Modifier = Modifier, gap: Dp, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val gapPx = gap.roundToPx()
        val count = measurables.size
        val widest = measurables.maxOfOrNull { it.maxIntrinsicWidth(0) } ?: 0
        val perRow = listOf(count, (count + 1) / 2, 1).firstOrNull { k ->
            k > 0 && k * widest + (k - 1) * gapPx <= constraints.maxWidth
        } ?: 1
        val rows = (count + perRow - 1) / perRow
        val itemWidth = ((constraints.maxWidth - (perRow - 1) * gapPx) / perRow).coerceAtLeast(0)
        val placeables = measurables.map { it.measure(Constraints(minWidth = itemWidth, maxWidth = itemWidth)) }
        val rowHeight = placeables.maxOfOrNull { it.height } ?: 0
        layout(constraints.maxWidth, rows * rowHeight + (rows - 1).coerceAtLeast(0) * gapPx) {
            placeables.forEachIndexed { index, placeable ->
                placeable.placeRelative(
                    x = (index % perRow) * (itemWidth + gapPx),
                    y = (index / perRow) * (rowHeight + gapPx),
                )
            }
        }
    }
}

/**
 * Przycisk profilu (kapsuła, min. 52 dp): wybrany ma wypełnienie palety i obrys w kolorze akcentu (nie tylko kolor –
 * także obrys i stan „wybrane” dla czytników ekranu), kropka w kolorze profilu jak na dużym kafelku.
 */
@Composable
private fun ProfileChip(
    profile: HeatingProfile,
    selected: Boolean,
    enabled: Boolean,
    height: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DomikTheme.colors
    val background = if (selected) colors.tileOnEnd else lerp(colors.card, colors.textSecondary, 0.10f)
    val textColor = if (selected) colors.textOnTile else colors.textPrimary
    Row(
        modifier = modifier
            .height(height)
            .alpha(if (enabled || selected) 1f else DISABLED_ALPHA)
            .clip(DomikShapes.chip)
            .background(background)
            .then(if (selected) Modifier.border(2.dp, colors.accent, DomikShapes.chip) else Modifier)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(profile.dotColor(colors)))
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(profile.labelRes),
            style = MaterialTheme.typography.titleSmall,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Jedna linia komunikatu o stałej minimalnej wysokości (okno nie skacze, gdy komunikat się pojawia): zapisywanie,
 * błąd polecenia, brak połączenia, brak odczytu albo zniknięcie strefy. Zmiany są ogłaszane czytnikom ekranu.
 */
@Composable
private fun StatusLine(status: ZoneDialogStatus, failure: String?) {
    val colors = DomikTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        contentAlignment = Alignment.CenterStart,
    ) {
        when (status) {
            ZoneDialogStatus.NONE -> Unit
            ZoneDialogStatus.SAVING -> StatusRow(stringResource(R.string.heating_dialog_saving), colors.textSecondary, progress = true)
            ZoneDialogStatus.ERROR -> StatusRow(failure.orEmpty(), colors.error)
            ZoneDialogStatus.OFFLINE -> StatusRow(stringResource(R.string.heating_dialog_offline), colors.error, icon = R.drawable.ic_wifi_off)
            ZoneDialogStatus.NO_READING -> StatusRow(stringResource(R.string.heating_dialog_no_reading), colors.textSecondary, progress = true)
            ZoneDialogStatus.GONE -> StatusRow(stringResource(R.string.heating_dialog_gone), colors.error)
        }
    }
}

@Composable
private fun StatusRow(
    text: String,
    color: Color,
    progress: Boolean = false,
    icon: Int? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when {
            progress -> CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = DomikTheme.colors.accent,
            )
            icon != null -> Image(
                painter = painterResource(icon),
                contentDescription = null,
                colorFilter = ColorFilter.tint(color),
                modifier = Modifier.size(20.dp),
            )
        }
        if (progress || icon != null) Spacer(Modifier.width(10.dp))
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

// Podglądy: karta okna w obu motywach (dane fikcyjne).

private fun previewUi(profile: HeatingProfile = HeatingProfile.Comfort, status: ZoneDialogStatus = ZoneDialogStatus.NONE): ZoneDialogUi {
    val tile = HeatingTileState(
        name = "Salon",
        room = "Parter",
        currentTemp = 21.5f,
        setpoint = 22.0f,
        profile = profile,
        heating = profile != HeatingProfile.Off,
    )
    return ZoneDialogUi(tile, status, tile.controls(online = status.interactive))
}

@Composable
private fun PanelPreview(mode: ThemeMode, ui: ZoneDialogUi, failure: String? = null) {
    DomikTheme(mode = mode) {
        Box(Modifier.background(DomikTheme.colors.card).padding(CONTENT_PADDING)) {
            HeatingZonePanel(ui, failure, 64.dp, 56.dp, {}, {}, {}, {})
        }
    }
}

@Preview(name = "Okno strefy – jasny", widthDp = 480, heightDp = 640)
@Composable
private fun ZonePanelLightPreview() = PanelPreview(ThemeMode.Light, previewUi())

@Preview(name = "Okno strefy – ciemny, brak połączenia", widthDp = 480, heightDp = 640)
@Composable
private fun ZonePanelDarkOfflinePreview() = PanelPreview(ThemeMode.Dark, previewUi(status = ZoneDialogStatus.OFFLINE))

@Preview(name = "Okno strefy – wąski, wyłączone", widthDp = 360, heightDp = 640)
@Composable
private fun ZonePanelNarrowPreview() = PanelPreview(ThemeMode.Light, previewUi(HeatingProfile.Off))
