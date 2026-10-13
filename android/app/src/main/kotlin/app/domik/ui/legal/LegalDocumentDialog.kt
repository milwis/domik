package app.domik.ui.legal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.domik.R
import app.domik.data.LegalDocument
import app.domik.data.LegalMarkdown
import app.domik.data.MdBlock
import app.domik.data.MdInline
import app.domik.data.ProjectLinks
import app.domik.data.isHttpsUrl
import app.domik.data.plainText
import app.domik.ui.components.rememberLinkOpener
import app.domik.ui.menu.KeepImmersiveInDialog
import app.domik.ui.theme.DomikTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pełny tekst Regulaminu albo Polityki prywatności, czytany z `assets/legal/` (kopia dokumentów z `legal/`, patrz
 * [LegalDocument]) – działa bez internetu. Wersja językowa zależy od języka interfejsu (zasób `legal_language`).
 * Odnośniki https w treści otwierają się w przeglądarce, odnośniki do drugiego dokumentu – w tym samym oknie.
 * Na dole jest dodatkowy przycisk „Wersja online” (repozytorium projektu).
 */
@Composable
fun LegalDocumentDialog(document: LegalDocument, onDismiss: () -> Unit) {
    var current by rememberSaveable { mutableStateOf(document) }
    val title = stringResource(current.titleRes())
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
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f).semantics { heading() },
                        )
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.legal_close)) }
                    }
                    HorizontalDivider(color = colors.textSecondary.copy(alpha = 0.25f))
                    DocumentBody(current, onNavigate = { current = it }, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

private fun LegalDocument.titleRes(): Int = when (this) {
    LegalDocument.TERMS -> R.string.legal_title_terms
    LegalDocument.PRIVACY -> R.string.legal_title_privacy
}

private fun LegalDocument.onlineUrl(): String = when (this) {
    LegalDocument.TERMS -> ProjectLinks.TERMS
    LegalDocument.PRIVACY -> ProjectLinks.PRIVACY_POLICY
}

/** Wynik wczytania dokumentu z `assets/`: null = jeszcze trwa, pusta lista = błąd (brak pliku). */
@Composable
private fun DocumentBody(document: LegalDocument, onNavigate: (LegalDocument) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val language = stringResource(R.string.legal_language)
    val open = rememberLinkOpener()
    val blocks by produceState<List<MdBlock>?>(initialValue = null, document, language, context) {
        value = withContext(Dispatchers.IO) {
            try {
                val text = context.assets.open(document.assetPath(language)).bufferedReader(Charsets.UTF_8).use { it.readText() }
                LegalMarkdown.parse(text)
            } catch (_: java.io.IOException) {
                emptyList()
            }
        }
    }
    val parsed = blocks
    when {
        parsed == null -> Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
        }

        parsed.isEmpty() -> Column(modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.legal_load_failed), style = MaterialTheme.typography.bodyLarge)
            OnlineButton(document, open)
        }

        else -> LazyColumn(
            modifier = modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(parsed) { block -> Block(block, open, onNavigate) }
            item { OnlineButton(document, open) }
        }
    }
}

@Composable
private fun OnlineButton(document: LegalDocument, open: (String) -> Unit) {
    Column(Modifier.padding(top = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { open(document.onlineUrl()) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.legal_open_online))
        }
        Text(
            ProjectLinks.displayAddress(document.onlineUrl()),
            style = MaterialTheme.typography.bodySmall,
            color = DomikTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun Block(block: MdBlock, open: (String) -> Unit, onNavigate: (LegalDocument) -> Unit) {
    val colors = DomikTheme.colors
    when (block) {
        is MdBlock.Heading -> Text(
            text = inlineText(block.text, open, onNavigate),
            style = when (block.level) {
                1 -> MaterialTheme.typography.headlineSmall
                2 -> MaterialTheme.typography.titleLarge
                else -> MaterialTheme.typography.titleMedium
            },
            color = colors.textPrimary,
            modifier = Modifier.padding(top = if (block.level <= 2) 12.dp else 6.dp).semantics { heading() },
        )

        is MdBlock.Paragraph -> Text(
            inlineText(block.text, open, onNavigate),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textPrimary,
        )

        is MdBlock.ListItem -> Row(Modifier.padding(start = (block.depth * 20).dp)) {
            Text(
                block.marker,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
                modifier = Modifier.width(if (block.marker == "•") 18.dp else 28.dp),
            )
            Text(
                inlineText(block.text, open, onNavigate),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
        }

        is MdBlock.Quote -> Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            block.blocks.forEach { Block(it, open, onNavigate) }
        }

        is MdBlock.Table -> TableBlock(block, open, onNavigate)

        MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 6.dp), color = colors.textSecondary.copy(alpha = 0.3f))
    }
}

/** Tabela jako lista kart (na wąskim ekranie kolumny nie mieszczą się obok siebie): pierwsza komórka to tytuł, reszta „Kolumna: wartość”. */
@Composable
private fun TableBlock(table: MdBlock.Table, open: (String) -> Unit, onNavigate: (LegalDocument) -> Unit) {
    val colors = DomikTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in table.rows) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.cardOff.copy(alpha = 0.5f))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                row.firstOrNull()?.let {
                    Text(
                        inlineText(it, open, onNavigate),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.textPrimary,
                    )
                }
                for ((column, cell) in row.withIndex().drop(1)) {
                    val label = table.header.getOrNull(column)?.plainText().orEmpty()
                    Text(
                        buildAnnotatedString {
                            if (label.isNotEmpty()) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("$label: ") }
                            append(inlineText(cell, open, onNavigate))
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun inlineText(parts: List<MdInline>, open: (String) -> Unit, onNavigate: (LegalDocument) -> Unit): AnnotatedString {
    val colors = DomikTheme.colors
    val linkStyles = TextLinkStyles(
        SpanStyle(color = colors.accent, fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline),
    )
    val codeBackground = colors.textSecondary.copy(alpha = 0.15f)
    return remember(parts, linkStyles, open, onNavigate, codeBackground) {
        buildAnnotatedString {
            for (part in parts) {
                val style = SpanStyle(
                    fontWeight = if (part.bold) FontWeight.Bold else null,
                    fontStyle = if (part.italic) FontStyle.Italic else null,
                    fontFamily = if (part.code) FontFamily.Monospace else null,
                    background = if (part.code) codeBackground else Color.Unspecified,
                )
                val link = part.link
                val internal = link?.let { LegalDocument.linkedFrom(it) }
                withStyle(style) {
                    when {
                        link != null && isHttpsUrl(link) ->
                            withLink(LinkAnnotation.Url(link, linkStyles) { open(link) }) { append(part.text) }
                        link != null && internal != null ->
                            withLink(LinkAnnotation.Clickable("legal:${internal.name}", linkStyles) { onNavigate(internal) }) {
                                append(part.text)
                            }
                        else -> append(part.text)
                    }
                }
            }
        }
    }
}
