package app.domik.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.view.Window
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Tryb pełnoekranowy (immersive): ukrywa paski systemowe; przesunięcie od krawędzi pokazuje je na chwilę
 * nakładką (układ się nie przesuwa, bo przejściowe paski nie zmieniają wcięć). Bez trybu pełnoekranowego paski
 * są zwykłe. Trzeba wołać ponownie po powrocie z okien dialogowych, zgód systemowych i przy zmianie ostrości
 * okna – system potrafi wtedy pokazać paski.
 */
fun applySystemBars(window: Window, fullscreen: Boolean) {
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    if (fullscreen) {
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    } else {
        controller.show(WindowInsetsCompat.Type.systemBars())
    }
}

/**
 * Czy tablet jest podłączony do zasilania, na bieżąco. Odbiornik jest rejestrowany, gdy ktoś zbiera przepływ,
 * i wyrejestrowany po jego zakończeniu – zbierać w cyklu życia aktywności (`repeatOnLifecycle`). Pierwsza
 * wartość pochodzi z „lepkiego” komunikatu o baterii, więc nie trzeba czekać na zmianę.
 */
fun powerConnectedFlow(context: Context): Flow<Boolean> = callbackFlow {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> trySend(true)
                Intent.ACTION_POWER_DISCONNECTED -> trySend(false)
                Intent.ACTION_BATTERY_CHANGED -> trySend(intent.isPluggedIn())
            }
        }
    }
    val filter = IntentFilter().apply {
        addAction(Intent.ACTION_BATTERY_CHANGED)
        addAction(Intent.ACTION_POWER_CONNECTED)
        addAction(Intent.ACTION_POWER_DISCONNECTED)
    }
    val sticky = ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    trySend(sticky?.isPluggedIn() ?: false)
    awaitClose { context.unregisterReceiver(receiver) }
}.distinctUntilChanged()

/** Ładowarka, USB, ładowanie bezprzewodowe lub stacja dokująca. */
private fun Intent.isPluggedIn(): Boolean = getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
