package app.domik.ui.thermo

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import android.content.pm.PackageManager
import app.domik.DomikApplication
import app.domik.platform.AndroidBleThermometerScanner
import app.domik.platform.BlePermissions

/**
 * Zgody i ustawienia systemu potrzebne do odbioru termometrów (Bluetooth LE), w interfejsie. Pytamy dopiero po
 * stuknięciu przycisku ([requestScan], [requestForHub]) – nigdy przy starcie. Po trwałej odmowie ([scanBlocked])
 * przycisk prowadzi do ustawień aplikacji. Po każdej zmianie (wynik prośby, powrót z ustawień) odbiornik ocenia
 * warunki od nowa ([AndroidBleThermometerScanner.refresh]).
 */
@Stable
class ThermoBluetoothAccess internal constructor(
    val scanBlocked: Boolean,
    private val ask: (withNotifications: Boolean) -> Unit,
    private val start: (Intent) -> Unit,
    private val appSettings: () -> Unit,
) {
    /** Zgoda na wyszukiwanie urządzeń (Android 12+: „Urządzenia w pobliżu”; wcześniej lokalizacja). */
    fun requestScan() = if (scanBlocked) appSettings() else ask(false)

    /** Włączenie huba: zgoda na skan oraz (Android 13+) na powiadomienie usługi pierwszoplanowej. */
    fun requestForHub() = ask(true)

    fun openBluetoothSettings() = start(BlePermissions.bluetoothSettingsIntent())

    fun openLocationSettings() = start(BlePermissions.locationSettingsIntent())

    fun openAppSettings() = appSettings()

    /** Wykonuje [action] ([ScannerAction]) z karty stanu odbiornika. */
    fun perform(action: ScannerAction) = when (action) {
        ScannerAction.NONE -> Unit
        ScannerAction.REQUEST_PERMISSION -> requestScan()
        ScannerAction.OPEN_APP_SETTINGS -> openAppSettings()
        ScannerAction.ENABLE_BLUETOOTH -> openBluetoothSettings()
        ScannerAction.ENABLE_LOCATION -> openLocationSettings()
    }
}

@Composable
fun rememberThermoBluetoothAccess(): ThermoBluetoothAccess {
    val context = LocalContext.current
    var scanBlocked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val activity = context.findActivity()
        val denied = BlePermissions.scanPermissions().filter { result[it] == false }
        scanBlocked = activity != null && denied.isNotEmpty() && denied.all {
            isPermissionBlocked(granted = false, shouldShowRationale = ActivityCompat.shouldShowRequestPermissionRationale(activity, it))
        }
        context.bleScanner()?.refresh()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (BlePermissions.hasScanPermissions(context)) scanBlocked = false
        context.bleScanner()?.refresh()
    }
    return remember(scanBlocked, launcher, context) {
        ThermoBluetoothAccess(
            scanBlocked = scanBlocked,
            ask = { withNotifications ->
                val wanted = BlePermissions.scanPermissions() + if (withNotifications) BlePermissions.notificationPermissions() else emptyList()
                val missing = wanted.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
                if (missing.isNotEmpty()) launcher.launch(missing.toTypedArray()) else context.bleScanner()?.refresh()
            },
            start = { intent -> context.startSafely(intent) },
            appSettings = { context.startSafely(BlePermissions.appSettingsIntent(context)) },
        )
    }
}

/** Odbiornik BLE aplikacji (null w podglądach, testach i w trybie demo bez sprzętu). */
internal fun Context.bleScanner(): AndroidBleThermometerScanner? =
    (applicationContext as? DomikApplication)?.environment?.thermometerSource as? AndroidBleThermometerScanner

private fun Context.startSafely(intent: Intent) {
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        // Brak ekranu ustawień na nietypowym urządzeniu – nic więcej nie da się zrobić.
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
