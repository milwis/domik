package app.domik.platform

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import app.domik.integrations.thermo.ScannerStatus

/**
 * Uprawnienia i stan systemu potrzebne do skanowania reklam BLE termometrów.
 *  - Android ≤ 11 (API ≤ 30): `ACCESS_FINE_LOCATION` **oraz włączona lokalizacja systemowa** (inaczej skan milczy),
 *  - Android 12+ (API ≥ 31): `BLUETOOTH_SCAN` (grupa „Urządzenia w pobliżu”), bez lokalizacji.
 * Prośby o zgodę (okno systemowe) wysyła ekran ustawień; tu tylko lista, sprawdzanie i intencje do ustawień systemu.
 */
// Stałe uprawnień są wstawiane w czasie kompilacji (InlinedApi), a wybór po wersji robimy sami przez sdkInt.
@SuppressLint("InlinedApi")
object BlePermissions {
    /** Uprawnienia runtime wymagane do skanu na danej wersji Androida. */
    fun scanPermissions(sdkInt: Int = Build.VERSION.SDK_INT): List<String> =
        if (sdkInt >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /** Czy na tej wersji Androida skan wymaga włączonej lokalizacji systemowej. */
    fun needsLocationEnabled(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt < Build.VERSION_CODES.S

    /** Zgoda na powiadomienia (Android 13+) dla usługi pierwszoplanowej; bez niej usługa działa, ale bez widocznego wpisu. */
    fun notificationPermissions(sdkInt: Int = Build.VERSION.SDK_INT): List<String> =
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()

    /** Brakujące uprawnienia skanu (puste = wszystko przyznane). */
    fun missingScanPermissions(context: Context): List<String> =
        scanPermissions().filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }

    fun hasScanPermissions(context: Context): Boolean = missingScanPermissions(context).isEmpty()

    fun isLeSupported(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) && adapter(context) != null

    fun isBluetoothEnabled(context: Context): Boolean = adapter(context)?.isEnabled == true

    fun isLocationEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return LocationManagerCompat.isLocationEnabled(manager)
    }

    /**
     * Pierwsza przeszkoda w skanowaniu jako [ScannerStatus] (kolejność: sprzęt, zgoda, Bluetooth, lokalizacja)
     * albo null, gdy można skanować.
     */
    fun blocker(context: Context): ScannerStatus? = when {
        !isLeSupported(context) -> ScannerStatus.UNSUPPORTED
        !hasScanPermissions(context) -> ScannerStatus.NO_PERMISSION
        !isBluetoothEnabled(context) -> ScannerStatus.BLUETOOTH_OFF
        needsLocationEnabled() && !isLocationEnabled(context) -> ScannerStatus.LOCATION_OFF
        else -> null
    }

    /** Ustawienia Bluetooth (włączenie radia). */
    fun bluetoothSettingsIntent(): Intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)

    /** Ustawienia lokalizacji (Android ≤ 11). */
    fun locationSettingsIntent(): Intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)

    /** Ekran uprawnień Domika w ustawieniach systemu (gdy użytkownik odmówił na stałe). */
    fun appSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

    private fun adapter(context: Context): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
}
