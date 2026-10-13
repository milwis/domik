package app.domik.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.domik.R

/**
 * Usługa pierwszoplanowa trzymająca proces przy życiu, żeby odbiór reklam termometrów (BLE) trwał przy wygaszonym
 * ekranie. Sama niczego nie skanuje – skanuje [AndroidBleThermometerScanner] z `AppEnvironment`; usługa tylko
 * zapewnia, że system nie uśpi procesu (typ `connectedDevice`, widoczne powiadomienie).
 *
 * Start/stop: [start] / [stop]. **Nie jest uruchamiana automatycznie** – podłączy ją integracja po włączeniu ustawienia
 * „To urządzenie kalibruje”.
 *
 * UWAGA (ograniczenie): sesja ze sterownikiem żyje dziś w ViewModelu (zakres aktywności). Pełna praca 24/7 (odczyty
 * termometrów → korekta kalibracji na sterowniku przy wygaszonym ekranie) wymaga przeniesienia sesji do zakresu
 * `Application` – to osobny refaktor; sama usługa tego nie załatwia.
 *
 * Android 14+: `startForeground` z typem `connectedDevice` wymaga przyznanego `BLUETOOTH_SCAN` – bez niego usługa
 * kończy się od razu zamiast rzucać wyjątkiem.
 */
class ThermoService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !BlePermissions.hasScanPermissions(this)) {
            Log.w(TAG, "Missing scan permission, not starting foreground service")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        ensureChannel()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "startForeground refused", e)
            stopSelf(startId)
            return START_NOT_STICKY
        } catch (e: IllegalStateException) {
            // m.in. ForegroundServiceStartNotAllowedException (start z tła na Androidzie 12+).
            Log.w(TAG, "startForeground not allowed", e)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.thermo_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification(): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launch?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_thermostat)
            .setContentTitle(getString(R.string.thermo_notification_text))
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentIntent)
            .build()
    }

    companion object {
        private const val TAG = "DomikThermo"
        private const val CHANNEL_ID = "thermo_monitor"
        private const val NOTIFICATION_ID = 1001

        /** Uruchamia usługę. Zwraca false, gdy system odmówił startu (np. aplikacja w tle na Androidzie 12+). */
        fun start(context: Context): Boolean = try {
            // Sprawdzenie z góry: usługa uruchomiona przez startForegroundService musi wywołać startForeground
            // (inaczej system zgłasza błąd), więc nie startujemy jej bez zgody wymaganej na Androidzie 14+.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !BlePermissions.hasScanPermissions(context)) {
                false
            } else {
                ContextCompat.startForegroundService(context, Intent(context, ThermoService::class.java))
                true
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Cannot start service now", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot start service", e)
            false
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ThermoService::class.java))
        }
    }
}
