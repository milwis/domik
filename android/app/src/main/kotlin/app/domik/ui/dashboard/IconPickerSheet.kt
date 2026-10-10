package app.domik.ui.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.domik.R
import app.domik.data.LightIcon
import app.domik.ui.theme.DomikShapes
import app.domik.ui.theme.DomikTheme

private val CELL_MIN_WIDTH = 96.dp
private val CELL_GAP = 12.dp
private val CELL_SHAPE = RoundedCornerShape(18.dp)

/**
 * Arkusz wyboru ikony kafelka: „Automatycznie” (ikona dobrana z bazy sterownika i nazwy)
 * oraz siatka wszystkich ikon, każda w kolorze akcentu (podgląd wypełnionej ikony włączonego kafelka). Bieżący
 * wybór jest zaznaczony ramką. Dotknięcie stosuje wybór i zamyka arkusz.
 *
 * @param name nazwa urządzenia (tytuł arkusza)
 * @param chosen ikona wybrana przez użytkownika; `null` = „Automatycznie”
 * @param automatic ikona, którą aplikacja dobiera sama (podpowiedź przy „Automatycznie”)
 * @param onChoose wybrana ikona albo `null` dla „Automatycznie”
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IconPickerSheet(
    name: String,
    chosen: LightIcon?,
    automatic: LightIcon,
    onChoose: (LightIcon?) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DomikTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    fun pick(icon: LightIcon?) {
        onChoose(icon)
        onDismiss()
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = DomikShapes.sheet,
        containerColor = colors.card,
        contentColor = colors.textPrimary,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(CELL_GAP),
        ) {
            Text(
                stringResource(R.string.icon_picker_title, name),
                style = MaterialTheme.typography.headlineSmall,
            )
            AutomaticRow(automatic = automatic, selected = chosen == null, onClick = { pick(null) })
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                // 12 ikon: 3, 4 albo 6 w rzędzie (pełne rzędy), zależnie od szerokości arkusza.
                val fit = ((maxWidth + CELL_GAP) / (CELL_MIN_WIDTH + CELL_GAP)).toInt()
                val columns = when {
                    fit >= 6 -> 6
                    fit >= 4 -> 4
                    else -> 3
                }
                Column(verticalArrangement = Arrangement.spacedBy(CELL_GAP)) {
                    LightIcon.entries.chunked(columns).forEach { rowIcons ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CELL_GAP)) {
                            rowIcons.forEach { icon ->
                                IconCell(icon, selected = icon == chosen, modifier = Modifier.weight(1f), onClick = { pick(icon) })
                            }
                            repeat(columns - rowIcons.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun cellBorder(selected: Boolean): BorderStroke {
    val colors = DomikTheme.colors
    return if (selected) BorderStroke(2.dp, colors.accent) else BorderStroke(1.dp, colors.cardBorder)
}

@Composable
private fun IconCell(icon: LightIcon, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = DomikTheme.colors
    val label = stringResource(icon.labelRes())
    val description = stringResource(if (selected) R.string.icon_selected_description else R.string.icon_description, label)
    Column(
        modifier = modifier
            .heightIn(min = 96.dp)
            .clip(CELL_SHAPE)
            .background(if (selected) colors.accent.copy(alpha = 0.12f) else colors.card)
            .border(cellBorder(selected), CELL_SHAPE)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.RadioButton
                this.selected = selected
            }
            .padding(horizontal = 6.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Image(
            painter = painterResource(icon.toDeviceIcon().filled),
            contentDescription = null,
            colorFilter = ColorFilter.tint(colors.accent),
            modifier = Modifier.size(40.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AutomaticRow(automatic: LightIcon, selected: Boolean, onClick: () -> Unit) {
    val colors = DomikTheme.colors
    val title = stringResource(R.string.icon_auto)
    val now = stringResource(R.string.icon_auto_now, stringResource(automatic.labelRes()))
    val description = stringResource(
        if (selected) R.string.icon_auto_selected_description else R.string.icon_auto_description,
        title,
        now,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(CELL_SHAPE)
            .background(if (selected) colors.accent.copy(alpha = 0.12f) else colors.card)
            .border(cellBorder(selected), CELL_SHAPE)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.RadioButton
                this.selected = selected
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(automatic.toDeviceIcon().filled),
            contentDescription = null,
            colorFilter = ColorFilter.tint(colors.accent),
            modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
            Text(now, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
        }
    }
}
