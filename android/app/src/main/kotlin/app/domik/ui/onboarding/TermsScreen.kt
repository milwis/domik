package app.domik.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.domik.R
import app.domik.data.ProjectLinks
import app.domik.data.TERMS_BODY_LINKS
import app.domik.ui.components.LinkedText
import app.domik.ui.components.ScreenColumn
import app.domik.ui.components.rememberLinkOpener
import app.domik.ui.theme.DomikShapes
import app.domik.ui.theme.DomikTheme

/**
 * Regulamin i polityka prywatności przy pierwszym uruchomieniu (i po każdej zmianie wersji dokumentów).
 * Tekst (klucze `terms_*` w strings.xml) jest streszczeniem Regulaminu (`legal/regulamin.md`); pełne dokumenty – w repozytorium
 * (`legal/`): adresy w tekście i przyciski „Otwórz pełny regulamin” / „Otwórz politykę prywatności” otwierają je
 * w przeglądarce (adresy w [ProjectLinks]).
 */
@Composable
fun TermsScreen(onAccept: () -> Unit) {
    val colors = DomikTheme.colors
    val open = rememberLinkOpener()
    ScreenColumn {
        Text(stringResource(R.string.terms_title), style = MaterialTheme.typography.headlineMedium, color = colors.textPrimary)
        Text(
            stringResource(R.string.terms_placeholder_notice),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
        )
        LinkedText(
            text = stringResource(R.string.terms_body),
            links = TERMS_BODY_LINKS,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.textPrimary,
            modifier = Modifier
                .fillMaxWidth()
                .clip(DomikShapes.tile)
                .background(colors.card)
                .padding(20.dp),
        )
        OutlinedButton(onClick = { open(ProjectLinks.TERMS) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.terms_open_terms))
        }
        OutlinedButton(onClick = { open(ProjectLinks.PRIVACY_POLICY) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.terms_open_privacy))
        }
        Button(onClick = onAccept, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.terms_accept))
        }
    }
}
