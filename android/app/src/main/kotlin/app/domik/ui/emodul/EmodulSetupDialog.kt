package app.domik.ui.emodul

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.domik.R
import app.domik.data.ProjectLinks
import app.domik.data.TileId
import app.domik.integrations.emodul.EmodulError
import app.domik.integrations.emodul.EmodulKey
import app.domik.integrations.emodul.EmodulKeyResult
import app.domik.integrations.emodul.EmodulModel
import app.domik.integrations.emodul.EmodulPreview
import app.domik.integrations.emodul.EmodulPreviewResult
import app.domik.integrations.emodul.EmodulSaveResult
import app.domik.integrations.emodul.EmodulState
import app.domik.ui.components.LinkedText
import app.domik.ui.dashboard.drawable
import app.domik.ui.dashboard.rememberEmodulUiTexts
import app.domik.ui.menu.KeepImmersiveInDialog
import app.domik.ui.theme.DomikTheme

/** Gdzie jest użytkownik w oknie integracji: kreator (krok 1 i 2) albo ustawienia istniejącej integracji. */
private sealed interface SetupScreen {
    data class Wizard(val changeKey: Boolean) : SetupScreen

    data object Edit : SetupScreen
}

/** Powód, dla którego kreator nie przeszedł dalej (komunikat w oknie; okno zostaje otwarte). */
private enum class SetupProblem(val messageRes: Int) {
    MALFORMED(R.string.emodul_setup_err_malformed),
    EXPIRED(R.string.emodul_setup_err_expired),
    REJECTED(R.string.emodul_setup_err_rejected),
    NETWORK(R.string.emodul_setup_err_network),
    BAD_RESPONSE(R.string.emodul_setup_err_bad_response),
    EMPTY(R.string.emodul_setup_err_empty),
    STORAGE(R.string.emodul_setup_err_storage),
}

private fun EmodulPreviewResult.problem(): SetupProblem? = when (this) {
    is EmodulPreviewResult.Ready -> null
    EmodulPreviewResult.NoticeRequired, EmodulPreviewResult.Malformed -> SetupProblem.MALFORMED
    EmodulPreviewResult.Expired -> SetupProblem.EXPIRED
    EmodulPreviewResult.Rejected -> SetupProblem.REJECTED
    is EmodulPreviewResult.Failed -> error.problem()
}

private fun EmodulError.problem(): SetupProblem = when (this) {
    EmodulError.BAD_RESPONSE, EmodulError.TOO_LARGE -> SetupProblem.BAD_RESPONSE
    EmodulError.NETWORK, EmodulError.RATE_LIMITED -> SetupProblem.NETWORK
}

/**
 * Okno integracji eModul (tylko odczyt, §6.4a): bez klucza – kreator „Dodaj integrację eModul” (krok 1: opis, komunikat
 * o prywatności i klucz; krok 2: lista danych z polami wyboru), ze skonfigurowaną integracją – „Ustawienia eModul”
 * (ta sama lista z bieżącym wyborem, klucz `…abcd`, „Zmień klucz”, „Usuń integrację”). W trybie demo kreator pokazuje
 * przykładowy kocioł (bez pola klucza i bez sieci). Klucz żyje wyłącznie w polu tekstowym (zwykły `remember`, nie
 * `rememberSaveable`) i w modelu do chwili „Zapisz”; „Anuluj” nie zostawia niczego.
 */
@Composable
fun EmodulSetupDialog(emodul: EmodulModel, onDismiss: () -> Unit) {
    val state by emodul.state.collectAsStateWithLifecycle()
    val demo = emodul.isDemo
    var screen by remember {
        mutableStateOf<SetupScreen>(if (state is EmodulState.NotConfigured) SetupScreen.Wizard(false) else SetupScreen.Edit)
    }
    val title = stringResource(
        when (val current = screen) {
            is SetupScreen.Wizard -> if (current.changeKey) R.string.emodul_setup_title_key else R.string.emodul_setup_title_add
            SetupScreen.Edit -> R.string.emodul_setup_title_edit
        },
    )
    SetupFrame(title, onDismiss) {
        when (val current = screen) {
            is SetupScreen.Wizard -> WizardContent(
                emodul = emodul,
                demo = demo,
                onCancel = { if (current.changeKey) screen = SetupScreen.Edit else onDismiss() },
                onDone = onDismiss,
            )
            SetupScreen.Edit -> EditContent(emodul, state, demo, onChangeKey = { screen = SetupScreen.Wizard(true) }, onDone = onDismiss)
        }
    }
}

/** Pełny ekran na telefonie, okno z ograniczoną szerokością na tablecie. */
@Composable
private fun SetupFrame(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val colors = DomikTheme.colors
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        KeepImmersiveInDialog()
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
            val large = maxWidth >= 600.dp && maxHeight >= 520.dp
            Surface(
                modifier = (if (large) Modifier.widthIn(max = 720.dp).fillMaxHeight(0.92f) else Modifier.fillMaxSize())
                    .semantics { paneTitle = title },
                shape = if (large) RoundedCornerShape(28.dp) else RectangleShape,
                color = colors.card,
                contentColor = colors.textPrimary,
            ) {
                Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                    Spacer(Modifier.size(12.dp))
                    content()
                }
            }
        }
    }
}

// Kreator

@Composable
private fun ColumnScope.WizardContent(emodul: EmodulModel, demo: Boolean, onCancel: () -> Unit, onDone: () -> Unit) {
    var preview by remember { mutableStateOf<EmodulPreview?>(null) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<SetupProblem?>(null) }
    val current = preview
    if (current == null) {
        KeyStep(
            demo = demo,
            busy = busy,
            problem = problem,
            onCancel = onCancel,
            onNext = { input ->
                problem = null
                busy = true
                // „Dalej” oznacza zgodę z komunikatem o prywatności (§6.3) – dopiero teraz wolno wysłać klucz.
                if (!demo) emodul.acceptNotice()
                emodul.preview(input) { result ->
                    busy = false
                    when (result) {
                        is EmodulPreviewResult.Ready ->
                            if (result.preview.modules.isEmpty()) problem = SetupProblem.EMPTY else preview = result.preview
                        else -> problem = result.problem()
                    }
                }
            },
        )
    } else {
        val (texts, locale) = rememberEmodulUiTexts()
        val sections = remember(current, texts, locale) { EmodulSetupRows.sections(current.modules, locale, texts) }
        // Jedyne źródło prawdy: to, co pokazują pola wyboru, i to, co idzie do zapisu, pochodzi z tego stanu.
        var pick by remember(current) { mutableStateOf(EmodulPickState.initial(current.modules)) }
        val checked = pick.effective(sections)
        PickList(
            intro = R.string.emodul_pick_intro,
            sections = sections,
            checked = checked,
            onToggle = { id, on -> pick = pick.set(id, on) },
            onAll = { pick = pick.selectAll(sections) },
            onNone = { pick = pick.selectNone() },
            modifier = Modifier.weight(1f),
        )
        ProblemText(problem)
        Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            if (checked.isEmpty()) NoneHint()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                ActionButtons(
                    busy = busy,
                    saveEnabled = checked.isNotEmpty(),
                    onCancel = onCancel,
                    onSave = {
                        problem = null
                        busy = true
                        emodul.commit(current, pick.effective(sections)) { result ->
                            busy = false
                            when (result) {
                                is EmodulSaveResult.Saved, EmodulSaveResult.Demo -> onDone()
                                EmodulSaveResult.StorageFailed -> problem = SetupProblem.STORAGE
                                EmodulSaveResult.Rejected -> problem = SetupProblem.REJECTED
                                is EmodulSaveResult.Failed -> problem = result.error.problem()
                                else -> problem = SetupProblem.NETWORK
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.KeyStep(demo: Boolean, busy: Boolean, problem: SetupProblem?, onCancel: () -> Unit, onNext: (String) -> Unit) {
    val colors = DomikTheme.colors
    // Zwykły remember (nie rememberSaveable): klucz nie trafia do stanu instancji.
    var key by remember { mutableStateOf("") }
    // Wysłanie dopiero na jawne „Dalej” (albo „Gotowe” na klawiaturze) i tylko dla klucza poprawnego lokalnie; zły
    // kształt pokazuje komunikat lokalny (model nie robi wtedy żadnego żądania), a wpisany tekst zostaje do poprawy.
    fun submit() {
        val input = key
        if (!demo && EmodulKey.parse(input) is EmodulKeyResult.Valid) key = ""
        onNext(input)
    }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.emodul_setup_intro), style = MaterialTheme.typography.bodyLarge)
        if (demo) {
            Text(stringResource(R.string.emodul_setup_demo), style = MaterialTheme.typography.bodyLarge, color = colors.textSecondary)
        } else {
            LinkedText(
                text = stringResource(R.string.emodul_setup_privacy),
                links = listOf(ProjectLinks.PRIVACY_POLICY),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
            )
            Text(stringResource(R.string.emodul_setup_instruction), style = MaterialTheme.typography.bodyLarge)
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text(stringResource(R.string.emodul_setup_key_label)) },
                singleLine = true,
                enabled = !busy,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = { if (!busy && EmodulKey.parse(key) is EmodulKeyResult.Valid) submit() },
                ),
                isError = problem != null,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (busy) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.emodul_setup_connecting), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
            }
        }
        ProblemText(problem)
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.emodul_setup_cancel)) }
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = ::submit,
            enabled = !busy && (demo || key.isNotBlank()),
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.emodul_setup_next)) }
    }
}

// Ustawienia istniejącej integracji

@Composable
private fun ColumnScope.EditContent(
    emodul: EmodulModel,
    state: EmodulState,
    demo: Boolean,
    onChangeKey: () -> Unit,
    onDone: () -> Unit,
) {
    val colors = DomikTheme.colors
    val (texts, locale) = rememberEmodulUiTexts()
    // Świeży odczyt przy otwarciu: znikłe kafelki wypadają z listy, nowe pojawiają się odznaczone i oznaczone „nowe”.
    LaunchedEffect(emodul) { emodul.refreshNow() }
    val modules = (state as? EmodulState.Ready)?.modules.orEmpty().filter { it.enabled && it.snapshot != null }
    val sections = remember(modules, texts, locale) { EmodulSetupRows.sections(modules, locale, texts) }
    // Szkic wyboru: inicjowany raz na moduł (kolejne odświeżenia nie cofają tego, co użytkownik już zmienił).
    var pick by remember { mutableStateOf(EmodulPickState()) }
    LaunchedEffect(modules) { pick = pick.merge(modules) }
    val checked = pick.effective(sections)
    var confirmRemove by remember { mutableStateOf(false) }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (demo) stringResource(R.string.emodul_edit_demo) else stringResource(R.string.emodul_edit_key, emodul.maskedKey().orEmpty()),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        if (!demo) {
            TextButton(onClick = onChangeKey, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.emodul_edit_change_key)) }
        }
    }
    if (sections.isEmpty()) {
        val message = when (state) {
            EmodulState.KeyRejected -> R.string.emodul_edit_rejected
            is EmodulState.Failed -> R.string.emodul_edit_unavailable
            else -> R.string.emodul_edit_loading
        }
        Column(Modifier.weight(1f).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(message),
                style = MaterialTheme.typography.bodyLarge,
                color = if (state == EmodulState.KeyRejected) colors.error else colors.textSecondary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (state == EmodulState.KeyRejected || state is EmodulState.Failed) {
                TextButton(onClick = emodul::refreshNow, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.emodul_edit_retry)) }
            }
        }
    } else {
        PickList(
            intro = R.string.emodul_pick_intro_edit,
            sections = sections,
            checked = checked,
            onToggle = { id, on -> pick = pick.set(id, on) },
            onAll = { pick = pick.selectAll(sections) },
            onNone = { pick = pick.selectNone() },
            modifier = Modifier.weight(1f),
        )
        if (checked.isEmpty()) NoneHint()
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { confirmRemove = true }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.emodul_edit_remove), color = colors.error)
        }
        Spacer(Modifier.weight(1f))
        ActionButtons(
            busy = false,
            saveEnabled = sections.isNotEmpty() && checked.isNotEmpty(),
            onCancel = onDone,
            onSave = {
                emodul.setSelection(checked)
                onDone()
            },
        )
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.emodul_edit_remove_title)) },
            text = { Text(stringResource(R.string.emodul_edit_remove_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    emodul.removeKey()
                    onDone()
                }) { Text(stringResource(R.string.emodul_edit_remove_confirm), color = colors.error) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.emodul_setup_cancel)) } },
        )
    }
}

// Wspólne: lista z polami wyboru i przyciski

/** Lista danych do wyboru, pogrupowana per moduł, z „Zaznacz wszystkie / Odznacz wszystkie” na górze. */
@Composable
private fun PickList(
    @StringRes intro: Int,
    sections: List<EmodulSetupSection>,
    checked: Set<TileId.EmodulBase>,
    onToggle: (TileId.EmodulBase, Boolean) -> Unit,
    onAll: () -> Unit,
    onNone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DomikTheme.colors
    Column(modifier) {
        Text(stringResource(intro), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onAll, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.emodul_pick_all))
            }
            TextButton(onClick = onNone, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.emodul_pick_none))
            }
        }
        HorizontalDivider(color = colors.cardBorder)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 8.dp)) {
            for (section in sections) {
                item(key = "module-${section.slot}") {
                    Column(Modifier.padding(top = 16.dp, bottom = 4.dp).semantics { heading() }) {
                        Text(
                            section.name.ifBlank { stringResource(R.string.emodul_pick_module_n, section.slot) },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (section.version.isNotBlank()) {
                            Text(section.version, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                        }
                    }
                }
                items(section.rows, key = { it.id.code }) { row ->
                    PickRow(row, row.id in checked) { on -> onToggle(row.id, on) }
                }
            }
        }
    }
}

/** Wiersz z polem wyboru: cały wiersz (min. 56 dp) jest celem dotyku i jednym elementem dostępności. */
@Composable
private fun PickRow(row: EmodulSetupRow, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = DomikTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(12.dp))
        Image(
            painter = painterResource(row.icon.drawable()),
            contentDescription = null,
            colorFilter = ColorFilter.tint(colors.textSecondary),
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.title.ifBlank { stringResource(R.string.emodul_tile_n, row.id.number) },
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (row.isNew) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.emodul_pick_new),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.accent,
                    )
                }
            }
            row.value?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary) }
        }
    }
}

@Composable
private fun ActionButtons(busy: Boolean, saveEnabled: Boolean, onCancel: () -> Unit, onSave: () -> Unit) {
    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    TextButton(onClick = onCancel, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(stringResource(R.string.emodul_setup_cancel))
    }
    Spacer(Modifier.width(8.dp))
    Button(onClick = onSave, enabled = saveEnabled && !busy, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(stringResource(R.string.emodul_pick_save))
    }
}

@Composable
private fun NoneHint() {
    Text(
        stringResource(R.string.emodul_pick_none_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = DomikTheme.colors.textSecondary,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun ProblemText(problem: SetupProblem?) {
    if (problem == null) return
    Text(
        stringResource(problem.messageRes),
        style = MaterialTheme.typography.bodyMedium,
        color = DomikTheme.colors.error,
        modifier = Modifier.padding(top = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
    )
}
