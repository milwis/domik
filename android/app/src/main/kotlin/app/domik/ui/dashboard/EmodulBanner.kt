package app.domik.ui.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.domik.R
import app.domik.integrations.emodul.EmodulAttention
import app.domik.ui.theme.DomikTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm")
private val DAY_MONTH_HOUR_MINUTE = DateTimeFormatter.ofPattern("d.MM HH:mm")

/** „od” w pasku uwagi: godzina `HH:mm`, a gdy moment nie jest z dzisiaj – z datą (`12.10 14:32`). */
internal fun bannerSince(since: Instant, nowMillis: Long, zone: ZoneId): String {
    val local = since.atZone(zone)
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    return (if (local.toLocalDate() == today) HOUR_MINUTE else DAY_MONTH_HOUR_MINUTE).format(local)
}

/**
 * Pasek uwagi eModul na górze pulpitu (jeden dla wszystkich modułów): kolor ostrzegawczy i ikona. Znika, gdy
 * [attention] to [EmodulAttention.None] (np. po świeżych danych). Teksty: brak komunikacji z modułem pieca (chmura
 * odpowiada, sterownik milczy), brak połączenia z serwerem eModul (tablet bez internetu) albo odrzucony klucz.
 */
@Composable
fun EmodulBanner(attention: EmodulAttention, nowMillis: Long, zone: ZoneId, modifier: Modifier = Modifier) {
    val colors = DomikTheme.colors
    // Przy znikaniu pasek animuje się z ostatnim tekstem, więc pamiętamy ostatnią wartość inną niż None.
    val shown = remember { arrayOf<EmodulAttention>(EmodulAttention.None) }
    if (attention != EmodulAttention.None) shown[0] = attention
    val message = when (val current = shown[0]) {
        EmodulAttention.None -> ""
        EmodulAttention.KeyRejected -> stringResource(R.string.emodul_banner_key_rejected)
        is EmodulAttention.ControllerSilent -> current.since
            ?.let { stringResource(R.string.emodul_banner_controller, bannerSince(it, nowMillis, zone)) }
            ?: stringResource(R.string.emodul_banner_controller_unknown)
        is EmodulAttention.CloudUnreachable -> current.since
            ?.let { stringResource(R.string.emodul_banner_cloud, bannerSince(it, nowMillis, zone)) }
            ?: stringResource(R.string.emodul_banner_cloud_unknown)
    }
    AnimatedVisibility(
        visible = attention != EmodulAttention.None,
        modifier = modifier,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.warning)
                .padding(horizontal = 20.dp, vertical = 10.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_warning),
                contentDescription = null,
                colorFilter = ColorFilter.tint(colors.onWarning),
                modifier = Modifier.size(20.dp),
            )
            Text(text = message, style = MaterialTheme.typography.labelLarge, color = colors.onWarning)
        }
    }
}
