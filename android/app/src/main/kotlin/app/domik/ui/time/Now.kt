package app.domik.ui.time

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.delay

/** Ile milisekund zostało do początku następnej pełnej minuty (1..60 000) – do tyknięć zegara co minutę. */
fun millisUntilNextMinute(now: LocalDateTime): Long {
    val elapsedInMinute = now.second * 1000L + now.nano / 1_000_000L
    return (60_000L - elapsedInMinute).coerceIn(1L, 60_000L)
}

/**
 * Bieżący czas lokalny z dokładnością do minuty, odświeżany **równo na granicy minuty**
 * (12:30:00, 12:31:00…), a także natychmiast po zmianie czasu/daty/strefy oraz po włączeniu ekranu
 * (tablet na ścianie po uśpieniu). Dzięki temu komponenty bezstanowe dostają „teraz” jako zwykły parametr
 * i same nie czytają zegara – wystarczy podać wynik tej funkcji.
 *
 * @param enabled false = bez tykania (zwraca stałą chwilę z pierwszej kompozycji)
 * @param clock źródło czasu (do testów/podglądów)
 */
@Composable
fun rememberNow(
    enabled: Boolean = true,
    clock: () -> LocalDateTime = { LocalDateTime.now() },
): State<LocalDateTime> {
    val state: MutableState<LocalDateTime> = remember { mutableStateOf(clock().truncatedTo(ChronoUnit.MINUTES)) }
    val context = LocalContext.current
    val inPreview = LocalView.current.isInEditMode
    if (inPreview) return state

    DisposableEffect(context, enabled) {
        if (!enabled) return@DisposableEffect onDispose {}
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                state.value = clock().truncatedTo(ChronoUnit.MINUTES)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        while (true) {
            state.value = clock().truncatedTo(ChronoUnit.MINUTES)
            delay(millisUntilNextMinute(clock()))
        }
    }
    return state
}

/**
 * Strefa czasowa urządzenia, odświeżana po jej zmianie (np. podróż, ustawienie ręczne). Kafelek kalendarza
 * przelicza godziny wydarzeń na tę strefę, więc po zmianie musi wczytać dane od nowa.
 */
@Composable
fun rememberSystemZone(): State<ZoneId> {
    val state: MutableState<ZoneId> = remember { mutableStateOf(ZoneId.systemDefault()) }
    val context = LocalContext.current
    if (LocalView.current.isInEditMode) return state

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                state.value = ZoneId.systemDefault()
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_TIMEZONE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }
    return state
}
