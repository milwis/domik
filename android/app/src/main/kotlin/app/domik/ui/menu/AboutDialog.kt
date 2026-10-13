package app.domik.ui.menu

import android.content.Context
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.domik.R
import app.domik.data.AppVersion
import app.domik.data.LicenseKind
import app.domik.data.OpenSourceComponent
import app.domik.data.OpenSourceItem
import app.domik.data.OpenSourceLicenses
import app.domik.data.LegalDocument
import app.domik.data.ProjectLinks
import app.domik.data.reflowNoticeText
import app.domik.ui.components.rememberLinkOpener
import app.domik.ui.legal.LegalDocumentDialog
import app.domik.ui.theme.DomikShapes
import app.domik.ui.theme.DomikTheme
import java.io.IOException

/**
 * Okno „O aplikacji” (otwierane z menu): nazwa i wersja, charakter projektu (nieoficjalny, edukacyjny), prywatność,
 * kod źródłowy i licencja MIT, regulamin i polityka prywatności, licencje składników open source (z pełnym tekstem
 * Apache-2.0 z pliku res/raw/notice_material_icons.txt) oraz kontakt. Odnośniki otwierają się w przeglądarce;
 * wszystkie adresy pochodzą z [ProjectLinks].
 */
@Composable
fun AboutDialog(version: AppVersion?, onDismiss: () -> Unit) {
    val open = rememberLinkOpener()
    var document by rememberSaveable { mutableStateOf<LegalDocument?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.about_title)) },
        text = {
            KeepImmersiveInDialog()
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Header(version)
                UnofficialBlock()
                Group(R.string.about_privacy_title) { Body(stringResource(R.string.about_privacy)) }
                Group(R.string.about_code_title) {
                    Body(stringResource(R.string.about_mit))
                    LinkRow(stringResource(R.string.about_link_source), ProjectLinks.REPOSITORY, open)
                    LinkRow(stringResource(R.string.about_link_license), ProjectLinks.LICENSE_MIT, open)
                    LinkRow(stringResource(R.string.about_link_releases), ProjectLinks.RELEASES, open)
                }
                Group(R.string.about_documents_title) {
                    // Pełne teksty są w aplikacji (offline); wersje online (repozytorium) – jako dodatek poniżej.
                    DocumentRow(stringResource(R.string.about_link_terms)) { document = LegalDocument.TERMS }
                    DocumentRow(stringResource(R.string.about_link_privacy)) { document = LegalDocument.PRIVACY }
                    LinkRow(stringResource(R.string.about_link_terms_online), ProjectLinks.TERMS, open)
                    LinkRow(stringResource(R.string.about_link_privacy_online), ProjectLinks.PRIVACY_POLICY, open)
                }
                OpenSourceGroup(open)
                Group(R.string.about_contact_title) {
                    Body(stringResource(R.string.about_contact_hint))
                    LinkRow(stringResource(R.string.about_link_issues), ProjectLinks.ISSUES, open)
                    LinkRow(stringResource(R.string.about_link_security), ProjectLinks.SECURITY_ADVISORY, open)
                }
                Text(
                    text = stringResource(R.string.about_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = DomikTheme.colors.textSecondary,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.about_close)) } },
    )
    document?.let { LegalDocumentDialog(it, onDismiss = { document = null }) }
}

@Composable
private fun Header(version: AppVersion?) {
    val colors = DomikTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            color = colors.textPrimary,
        )
        if (version != null) {
            Text(
                text = stringResource(R.string.about_version, version.name, version.code),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.textSecondary,
            )
        }
        Text(
            text = stringResource(R.string.app_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.textSecondary,
        )
    }
}

/** Wyróżniony blok: projekt nieoficjalny, edukacyjny, na własną odpowiedzialność; znaki towarowe. */
@Composable
private fun UnofficialBlock() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DomikShapes.tile)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.about_unofficial),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Text(
            text = stringResource(R.string.about_trademark),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun Group(@StringRes title: Int, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.titleMedium,
            color = DomikTheme.colors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        content()
    }
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = DomikTheme.colors.textSecondary)
}

/** Wiersz-odnośnik: tytuł i adres (widoczny, żeby dało się go przepisać, gdyby przeglądarka nie otworzyła się). */
@Composable
private fun LinkRow(title: String, url: String, open: (String) -> Unit) {
    InfoRow(title = title, details = listOf(ProjectLinks.displayAddress(url)), url = url, open = open)
}

/** Wiersz otwierający pełny tekst dokumentu w aplikacji (offline), z podpisem „działa bez internetu”. */
@Composable
private fun DocumentRow(title: String, onClick: () -> Unit) {
    InfoRow(
        title = title,
        details = listOf(stringResource(R.string.about_document_offline)),
        url = null,
        open = {},
        onClick = onClick,
        clickLabel = stringResource(R.string.about_open_document),
    )
}

/**
 * Wiersz z tytułem i wierszami szczegółów; z [url] (otwierany w przeglądarce) albo [onClick] całość jest klikalna
 * (min. 56 dp, jeden element dostępności) i kończy się strzałką.
 */
@Composable
private fun InfoRow(
    title: String,
    details: List<String>,
    url: String?,
    open: (String) -> Unit,
    onClick: (() -> Unit)? = null,
    clickLabel: String = stringResource(R.string.about_open_in_browser),
) {
    val colors = DomikTheme.colors
    val action: (() -> Unit)? = if (url != null) ({ open(url) }) else onClick
    val clickable = if (action == null) {
        Modifier
    } else {
        Modifier.clickable(onClickLabel = clickLabel, role = Role.Button, onClick = action)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(clickable)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.textPrimary)
            for (line in details) {
                Text(line, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
            }
        }
        if (action != null) {
            Spacer(Modifier.width(12.dp))
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = colors.accent, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun OpenSourceGroup(open: (String) -> Unit) {
    Group(R.string.about_oss_title) {
        Body(stringResource(R.string.about_oss_intro))
        for (item in OpenSourceLicenses.items) {
            OpenSourceRow(item, open)
        }
        Text(
            text = stringResource(R.string.about_apache_title),
            style = MaterialTheme.typography.titleSmall,
            color = DomikTheme.colors.textPrimary,
            modifier = Modifier.padding(top = 8.dp).semantics { heading() },
        )
        Body(stringResource(R.string.about_apache_summary))
        LinkRow(stringResource(R.string.about_link_apache), ProjectLinks.LICENSE_APACHE_2, open)
        if (OpenSourceLicenses.items.any { it.license == LicenseKind.CcBy4 }) {
            LinkRow(stringResource(R.string.about_link_cc_by), ProjectLinks.LICENSE_CC_BY_4, open)
        }
    }
}

@Composable
private fun OpenSourceRow(item: OpenSourceItem, open: (String) -> Unit) {
    val authorRes = item.component.authorRes()
    val license = stringResource(item.license.nameRes())
    val meta = if (authorRes == null) {
        stringResource(R.string.about_oss_meta_license_only, license)
    } else {
        stringResource(R.string.about_oss_meta, stringResource(authorRes), license)
    }
    val details = buildList {
        add(meta)
        item.link?.let { add(ProjectLinks.displayAddress(it)) }
    }
    Column {
        InfoRow(title = stringResource(item.component.nameRes()), details = details, url = item.link, open = open)
        if (item.hasNoticeText) NoticeText()
    }
}

/** Przycisk rozwijający informację o licencji dołączoną do aplikacji (res/raw/notice_material_icons.txt). */
@Composable
private fun NoticeText() {
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) {
        Text(stringResource(if (expanded) R.string.about_notice_hide else R.string.about_notice_show))
    }
    if (expanded) {
        val text = remember(context) { readNoticeText(context) }
        Text(
            text = text ?: stringResource(R.string.about_notice_unavailable),
            style = MaterialTheme.typography.bodySmall,
            color = DomikTheme.colors.textSecondary,
        )
    }
}

/** Treść pliku z informacją o licencji ułożona do czytania ([reflowNoticeText]); null, gdy nie da się go odczytać. */
private fun readNoticeText(context: Context): String? = try {
    context.resources.openRawResource(R.raw.notice_material_icons).bufferedReader(Charsets.UTF_8).use { reflowNoticeText(it.readText()) }
} catch (_: IOException) {
    null
} catch (_: Resources.NotFoundException) {
    null
}

@StringRes
private fun OpenSourceComponent.nameRes(): Int = when (this) {
    OpenSourceComponent.Kotlin -> R.string.oss_name_kotlin
    OpenSourceComponent.KotlinxCoroutines -> R.string.oss_name_coroutines
    OpenSourceComponent.AndroidX -> R.string.oss_name_androidx
    OpenSourceComponent.JetpackCompose -> R.string.oss_name_compose
    OpenSourceComponent.Material3 -> R.string.oss_name_material3
    OpenSourceComponent.MaterialIcons -> R.string.oss_name_icons
    OpenSourceComponent.OpenMeteo -> R.string.menu_weather_attribution
}

/** Autor składnika (null: dla danych pogodowych podpis zawiera już nazwę dostawcy). */
@StringRes
private fun OpenSourceComponent.authorRes(): Int? = when (this) {
    OpenSourceComponent.Kotlin, OpenSourceComponent.KotlinxCoroutines -> R.string.oss_author_jetbrains
    OpenSourceComponent.AndroidX,
    OpenSourceComponent.JetpackCompose,
    OpenSourceComponent.Material3,
    OpenSourceComponent.MaterialIcons,
    -> R.string.oss_author_google
    OpenSourceComponent.OpenMeteo -> null
}

@StringRes
private fun LicenseKind.nameRes(): Int = when (this) {
    LicenseKind.Apache2 -> R.string.license_name_apache2
    LicenseKind.CcBy4 -> R.string.license_name_cc_by_4
}
