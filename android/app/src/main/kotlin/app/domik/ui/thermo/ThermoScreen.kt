package app.domik.ui.thermo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.domik.R
import app.domik.data.HomeRepository
import app.domik.deimic.client.commands.ZoneKey
import app.domik.deimic.config.HomeConfig
import app.domik.integrations.thermo.ThermoLogEntry
import app.domik.integrations.thermo.ThermoProfileSettings
import app.domik.integrations.thermo.ThermoUiState
import app.domik.integrations.thermo.ThermoZoneStatus
import app.domik.ui.menu.KeepImmersiveInDialog
import app.domik.ui.theme.DomikBackground
import app.domik.ui.theme.DomikTheme

private enum class ThermoTab(val titleRes: Int) {
    THERMOMETERS(R.string.thermo_tab_thermometers),
    CALIBRATION(R.string.thermo_tab_calibration),
    LOG(R.string.thermo_tab_log),
}

/**
 * Pełnoekranowe okno „Termometry pokojowe” (bez nowej trasy – jak okno strefy). Trzy karty: „Termometry” (odbiornik,
 * wykryte i przypisane), „Kalibracja” (przełączniki, próg, tabela stref) i „Dziennik” (decyzje i liczniki).
 *
 * Przy otwarciu prosi o szybkie wyszukiwanie ([ThermoDiscoveryRequest]); tryb skanu ustawia wyłącznie
 * [ThermoBackgroundScan], więc zamknięcie okna wraca do trybu tła albo wyłącza skan.
 */
@Composable
fun ThermoScreen(actions: ThermoActions, repository: HomeRepository, onDismiss: () -> Unit) {
    val state by actions.state.collectAsStateWithLifecycle()
    val home by repository.state.collectAsStateWithLifecycle()
    val config = home.config
    val now by rememberWallClock()
    val access = rememberThermoBluetoothAccess()
    var tab by rememberSaveable { mutableStateOf(ThermoTab.THERMOMETERS) }

    val discovery = LocalThermoDiscovery.current
    DisposableEffect(discovery) {
        discovery?.open?.value = true
        onDispose { discovery?.open?.value = false }
    }

    val colors = DomikTheme.colors
    val title = stringResource(R.string.thermo_title)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        KeepImmersiveInDialog()
        DomikBackground(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalContentColor provides colors.textPrimary) {
                Column(Modifier.fillMaxSize().safeDrawingPadding().semantics { paneTitle = title }) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ThermometerIcon(size = 28.dp)
                        Spacer(Modifier.padding(start = 12.dp))
                        Text(
                            text = title,
                            style = MaterialTheme.typography.headlineSmall,
                            color = colors.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.thermo_close))
                        }
                    }
                    PrimaryTabRow(
                        selectedTabIndex = tab.ordinal,
                        containerColor = Color.Transparent,
                        contentColor = colors.accent,
                    ) {
                        ThermoTab.entries.forEach { entry ->
                            Tab(
                                selected = entry == tab,
                                onClick = { tab = entry },
                                modifier = Modifier.heightIn(min = 52.dp),
                                text = { Text(stringResource(entry.titleRes)) },
                            )
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        Column(
                            modifier = Modifier
                                .widthIn(max = 760.dp)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            LeaveWarningCard(state, actions)
                            when (tab) {
                                ThermoTab.THERMOMETERS -> ThermometersTab(state, config, actions, access, now)
                                ThermoTab.CALIBRATION -> CalibrationTab(state, actions, access)
                                ThermoTab.LOG -> LogTab(state, actions)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Karta „Termometry”

@Composable
private fun ThermometersTab(
    state: ThermoUiState,
    config: HomeConfig?,
    actions: ThermoActions,
    access: ThermoBluetoothAccess,
    nowMillis: Long,
) {
    var assigning by rememberSaveable { mutableStateOf<String?>(null) }

    if (state.demo) DemoBanner()
    ScannerCard(state, access)

    SectionTitle(stringResource(R.string.thermo_discovered_title))
    val rows = discoveredRows(state, zoneNames = { zoneName(config, state, it) }, nowMillis = nowMillis)
    if (rows.isEmpty()) {
        HintText(stringResource(R.string.thermo_discovered_empty))
    }
    for (row in rows) {
        ThermoCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = row.title.ifBlank { stringResource(R.string.thermo_thermometer_default) },
                        style = MaterialTheme.typography.titleMedium,
                        color = DomikTheme.colors.textPrimary,
                    )
                    HintText(row.mac)
                }
            }
            ValueFlow(row.values)
            if (row.assignedZoneName != null) {
                HintText(stringResource(R.string.thermo_assigned_to, row.assignedZoneName))
            }
            OutlinedButton(
                onClick = { assigning = row.mac },
                enabled = config != null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.thermo_assign))
            }
        }
    }

    SectionTitle(stringResource(R.string.thermo_assigned_title))
    if (state.zones.isEmpty()) {
        HintText(stringResource(R.string.thermo_assigned_empty))
    }
    for (zone in state.zones) {
        val choices = inputChoices(config, zone)
        ThermoCard {
            Text(zone.name, style = MaterialTheme.typography.titleMedium, color = DomikTheme.colors.textPrimary)
            zone.roomName?.takeIf { it.isNotBlank() && !it.equals(zone.name, ignoreCase = true) }?.let { HintText(it) }
            HintText(thermometerTitle(zone.thermometer).resolve() + " · " + zone.thermometer.mac)
            ValueFlow(assignedValues(zone.thermometer, state.updatedAt, nowMillis))
            Text(zoneStatusText(zone, state).resolve(), style = MaterialTheme.typography.titleSmall, color = DomikTheme.colors.textPrimary)
            for (notice in sectionNotices(zone)) ProblemText(notice)
            for (issue in zoneIssueTexts(zone)) ProblemText(issue.resolve())
            if (zone.status == ThermoZoneStatus.NEEDS_INPUT || choices.size > 1) {
                HintText(stringResource(R.string.thermo_choose_input))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.selectableGroup()) {
                    for (input in choices) {
                        FilterChip(
                            selected = input == zone.inputNr,
                            onClick = {
                                actions.assign(zone.thermometer.mac, zone.zone.module, zone.zone.outputNr, input, zone.thermometer.label)
                            },
                            label = { Text(inputLabel(input).resolve()) },
                        )
                    }
                }
            }
            OutlinedButton(
                onClick = { actions.unassign(zone.zone) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.thermo_unassign))
            }
        }
    }

    val mac = assigning
    if (mac != null) {
        AssignDialog(
            mac = mac,
            choices = zoneChoices(config, state),
            onAssign = { zone, label ->
                actions.assign(mac, zone.module, zone.outputNr, null, label)
                assigning = null
            },
            onDismiss = { assigning = null },
        )
    }
}

@Composable
private fun sectionNotices(zone: app.domik.integrations.thermo.ThermoZoneUi): List<String> =
    zone.notices.sortedBy { it.ordinal }.map { stringResource(it.textRes()) }

@Composable
private fun DemoBanner() {
    ThermoCard {
        Text(stringResource(R.string.thermo_demo_banner_title), style = MaterialTheme.typography.titleSmall, color = DomikTheme.colors.accent)
        HintText(stringResource(R.string.thermo_demo_banner))
    }
}

@Composable
private fun ScannerCard(state: ThermoUiState, access: ThermoBluetoothAccess) {
    val status = scannerStatusUi(state, permissionBlocked = access.scanBlocked)
    ThermoCard {
        Text(stringResource(R.string.thermo_scanner_title), style = MaterialTheme.typography.titleMedium, color = DomikTheme.colors.textPrimary)
        if (status.problem) ProblemText(status.text.resolve()) else HintText(status.text.resolve())
        if (status.action != ScannerAction.NONE) {
            Button(onClick = { access.perform(status.action) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(status.action.labelRes))
            }
        }
    }
}

/** Wybór strefy (i opcjonalnej etykiety) dla wykrytego termometru. */
@Composable
private fun AssignDialog(
    mac: String,
    choices: List<ZoneChoice>,
    onAssign: (ZoneKey, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by rememberSaveable(mac) { mutableStateOf<Pair<Int, Int>?>(null) }
    var label by rememberSaveable(mac) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.thermo_assign_title)) },
        text = {
            KeepImmersiveInDialog()
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (choices.isEmpty()) HintText(stringResource(R.string.thermo_assign_no_zones))
                Column(Modifier.selectableGroup()) {
                    for (choice in choices) {
                        val key = choice.key.module to choice.key.outputNr
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .selectable(selected = selected == key, role = Role.RadioButton, onClick = { selected = key }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected == key, onClick = null)
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(choice.name, style = MaterialTheme.typography.bodyLarge)
                                val sub = listOfNotNull(
                                    choice.room.takeIf { it.isNotBlank() },
                                    stringResource(R.string.thermo_assign_has_thermometer).takeIf { choice.hasThermometer },
                                ).joinToString(" · ")
                                if (sub.isNotEmpty()) HintText(sub)
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it.take(MAX_LABEL_LENGTH) },
                    label = { Text(stringResource(R.string.thermo_assign_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected != null,
                onClick = { selected?.let { (module, output) -> onAssign(ZoneKey(module, output), label) } },
            ) { Text(stringResource(R.string.thermo_assign_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.thermo_cancel)) } },
    )
}

private const val MAX_LABEL_LENGTH = 40

// ---------------------------------------------------------------------------------------------------------------
// Karta „Kalibracja”

@Composable
private fun CalibrationTab(state: ThermoUiState, actions: ThermoActions, access: ThermoBluetoothAccess) {
    var confirmLive by rememberSaveable { mutableStateOf(false) }

    if (state.demo) DemoBanner()
    ThermoCard {
        ThermoSwitchRow(
            title = stringResource(R.string.thermo_switch_enabled),
            hint = stringResource(R.string.thermo_switch_enabled_hint),
            checked = state.enabled,
            onCheckedChange = actions.setEnabled,
        )
        ThermoSwitchRow(
            title = stringResource(R.string.thermo_switch_hub),
            hint = stringResource(R.string.thermo_switch_hub_hint),
            checked = state.hub,
            onCheckedChange = { on ->
                actions.setHub(on)
                if (on) access.requestForHub()
            },
        )
        ThermoSwitchRow(
            title = stringResource(R.string.thermo_switch_dry_run),
            hint = stringResource(if (state.writesBlockedByFlag) R.string.thermo_writes_blocked else R.string.thermo_switch_dry_run_hint),
            checked = state.dryRun,
            enabled = !state.writesBlockedByFlag,
            onCheckedChange = { dryRun -> if (dryRun) actions.setDryRun(true) else confirmLive = true },
        )
    }

    ThermoCard {
        Text(stringResource(R.string.thermo_threshold_title), style = MaterialTheme.typography.titleMedium, color = DomikTheme.colors.textPrimary)
        HintText(stringResource(R.string.thermo_threshold_hint))
        Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (threshold in ThermoProfileSettings.THRESHOLDS) {
                FilterChip(
                    selected = threshold == state.threshold,
                    onClick = { actions.setThreshold(threshold) },
                    label = { Text(thresholdLabel(threshold)) },
                )
            }
        }
    }

    ThermoCard {
        Text(stringResource(R.string.thermo_calibration_title), style = MaterialTheme.typography.titleMedium, color = DomikTheme.colors.textPrimary)
        val read = state.lastCalibrationReadAt
        HintText(
            if (read == null) stringResource(R.string.thermo_last_read_never) else stringResource(R.string.thermo_last_read, formatDateTime(read)),
        )
        OutlinedButton(onClick = actions.refreshCalibrations, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.thermo_read_calibration))
        }
    }

    if (state.zones.isEmpty()) {
        HintText(stringResource(R.string.thermo_assigned_empty))
    }
    for (row in calibrationRows(state)) {
        ThermoCard {
            Text(row.zoneName, style = MaterialTheme.typography.titleMedium, color = DomikTheme.colors.textPrimary)
            row.room?.let { HintText(it) }
            HintText(row.input.resolve())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OffsetCell(R.string.thermo_row_calibration_baseline, row.baseline, Modifier.weight(1f))
                OffsetCell(R.string.thermo_row_calibration_now, row.current, Modifier.weight(1f))
                OffsetCell(R.string.thermo_row_calibration_planned, row.planned, Modifier.weight(1f))
            }
            Text(row.status.resolve(), style = MaterialTheme.typography.titleSmall, color = DomikTheme.colors.textPrimary)
            for (notice in row.notices) ProblemText(notice.resolve())
            for (issue in row.issues) ProblemText(issue.resolve())
            row.idle?.let { HintText(stringResource(R.string.thermo_idle_prefix, it.resolve())) }
            if (row.canResume) {
                OutlinedButton(
                    onClick = { actions.resumeCalibration(row.zone) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.thermo_resume))
                }
            }
        }
    }

    if (confirmLive) {
        AlertDialog(
            onDismissRequest = { confirmLive = false },
            title = { Text(stringResource(R.string.thermo_live_title)) },
            text = {
                KeepImmersiveInDialog()
                Text(stringResource(R.string.thermo_live_message), modifier = Modifier.verticalScroll(rememberScrollState()))
            },
            confirmButton = {
                TextButton(onClick = { confirmLive = false; actions.setDryRun(false) }) {
                    Text(stringResource(R.string.thermo_live_confirm), color = DomikTheme.colors.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmLive = false }) { Text(stringResource(R.string.thermo_cancel)) } },
        )
    }
}

@Composable
private fun OffsetCell(labelRes: Int, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(stringResource(labelRes), style = MaterialTheme.typography.labelMedium, color = DomikTheme.colors.textSecondary)
        Text(value, style = MaterialTheme.typography.titleSmall, color = DomikTheme.colors.textPrimary)
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Karta „Dziennik”

@Composable
private fun LogTab(state: ThermoUiState, actions: ThermoActions) {
    if (state.demo) DemoBanner()
    HintText(stringResource(R.string.thermo_log_hint))
    val zones = logZones(state)
    if (zones.isEmpty()) {
        HintText(stringResource(R.string.thermo_assigned_empty))
    }
    for (zone in zones) {
        ThermoCard {
            Text(zone.zoneName, style = MaterialTheme.typography.titleMedium, color = DomikTheme.colors.textPrimary)
            zone.room?.let { HintText(it) }
            Text(zone.status.resolve(), style = MaterialTheme.typography.titleSmall, color = DomikTheme.colors.textPrimary)

            Text(stringResource(R.string.thermo_log_last_decision), style = MaterialTheme.typography.labelLarge, color = DomikTheme.colors.textSecondary)
            val decision = zone.decision
            if (decision == null) {
                HintText(stringResource(R.string.thermo_log_no_decision))
            } else {
                Text(
                    text = listOfNotNull(decision.whenText, decision.kind.resolve(), decision.result?.resolve()).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = DomikTheme.colors.textPrimary,
                )
                decision.reason?.let { HintText(it.resolve()) }
                decision.values?.let { HintText(it.resolve()) }
            }
            zone.idle?.let { HintText(stringResource(R.string.thermo_idle_prefix, it.resolve())) }

            Text(
                stringResource(if (zone.live) R.string.thermo_log_counters_live else R.string.thermo_log_counters_dry),
                style = MaterialTheme.typography.labelLarge,
                color = DomikTheme.colors.textSecondary,
            )
            for (row in zone.frequency) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = row.threshold + if (row.primary) " " + stringResource(R.string.thermo_log_primary) else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = DomikTheme.colors.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(R.string.thermo_log_counts, row.last24h, row.total, row.restores),
                        style = MaterialTheme.typography.bodyMedium,
                        color = DomikTheme.colors.textSecondary,
                    )
                }
            }
            zone.sinceText?.let { HintText(stringResource(R.string.thermo_log_since, it)) }
        }
    }
    FullLog(state, actions)
}

/** Pełny dziennik decyzji (ostatnie [LOG_LIMIT] wpisów, najnowsze na górze); plik czytany poza wątkiem UI. */
@Composable
private fun FullLog(state: ThermoUiState, actions: ThermoActions) {
    // Odświeżenie przy każdej nowej decyzji albo zmianie liczników (zapis do dziennika zmienia jedno z nich).
    val version = state.zones.map { zone -> zone.lastDecision to zone.frequency.sumOf { it.calibrationsTotal + it.restoresTotal } }
    val entries by produceState(emptyList<ThermoLogEntry>(), version) {
        value = withContext(Dispatchers.IO) { actions.log() }
    }
    val rows = remember(entries, state.zones) { logEntryRows(entries, state) }
    SectionTitle(stringResource(R.string.thermo_log_full_title))
    if (rows.isEmpty()) {
        HintText(stringResource(R.string.thermo_log_no_decision))
        return
    }
    HintText(stringResource(R.string.thermo_log_full_hint, rows.size))
    ThermoCard {
        rows.forEachIndexed { index, row ->
            if (index > 0) HorizontalDivider(color = DomikTheme.colors.cardBorder)
            LogEntryView(row)
        }
    }
}

@Composable
private fun LogEntryView(row: LogEntryRow) {
    val colors = DomikTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = listOf(row.whenText, row.zoneName.resolve(), row.input.resolve()).joinToString(" · "),
            style = MaterialTheme.typography.labelLarge,
            color = colors.textSecondary,
        )
        Text(row.kind.resolve(), style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary)
        val facts = listOfNotNull(
            row.threshold?.let { stringResource(R.string.thermo_log_threshold, it) },
            row.difference?.let { stringResource(R.string.thermo_log_difference, it) },
            row.change?.resolve(),
        )
        if (facts.isNotEmpty()) HintText(facts.joinToString(" · "))
        val outcome = listOfNotNull(row.mode?.resolve(), row.result?.resolve())
        if (outcome.isNotEmpty()) HintText(outcome.joinToString(" · "))
        row.reason?.let { HintText(it.resolve()) }
        row.detail?.let { ProblemText(it.resolve()) }
    }
}

/**
 * Ostrzeżenie, że na sterowniku mogła zostać kalibracja ustawiona przez Domik. „Rozumiem” zeruje trwałą flagę
 * (poprzedni sterownik) i ukrywa listę wejść do czasu, aż się zmieni (wejścia z bieżącej sesji pojawią się znów po
 * ponownym połączeniu, jeśli nadal nic ich nie nadzoruje).
 */
@Composable
private fun LeaveWarningCard(state: ThermoUiState, actions: ThermoActions) {
    var dismissedInputs by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    val full = leaveWarning(state) ?: return
    val warning = if (full.inputs == dismissedInputs) full.copy(inputs = emptyList()) else full
    if (warning.inputs.isEmpty() && !warning.previousController) return
    ThermoCard {
        Text(stringResource(R.string.thermo_leave_warning_title), style = MaterialTheme.typography.titleSmall, color = DomikTheme.colors.error)
        for (text in warning.texts()) ProblemText(text.resolve())
        HintText(stringResource(R.string.thermo_leave_warning_action))
        OutlinedButton(
            onClick = {
                dismissedInputs = full.inputs
                actions.dismissLeaveWarning()
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.thermo_leave_warning_dismiss))
        }
    }
}
