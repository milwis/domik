package app.domik.ui.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.domik.integrations.emodul.EmodulModel
import kotlinx.coroutines.delay

/**
 * Pętla odświeżania eModul – jedna na pulpit. Działa tylko, gdy ekran jest widoczny (STARTED) i [active] (na
 * pulpicie jest widoczny kafelek eModul albo otwarte są ustawienia eModul); w tle nic nie jest wysyłane. Każde
 * wejście w STARTED zaczyna od [EmodulModel.refreshDue] z `resumed = true` (dane starsze niż 30 s od razu),
 * potem czeka tyle, ile zwróci model (polityka odstępów jest w [EmodulModel]).
 */
@Composable
fun EmodulRefreshEffect(emodul: EmodulModel, active: Boolean) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(emodul, active, lifecycleOwner) {
        if (!active) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var resumed = true
            while (true) {
                val wait = emodul.refreshDue(resumed)
                resumed = false
                delay(wait)
            }
        }
    }
}
