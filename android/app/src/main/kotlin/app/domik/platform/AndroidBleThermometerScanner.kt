package app.domik.platform

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import app.domik.integrations.thermo.BleScanPolicy
import app.domik.integrations.thermo.ScanIntensity
import app.domik.integrations.thermo.ScanMode
import app.domik.integrations.thermo.ScannerStatus
import app.domik.integrations.thermo.ThermometerReading
import app.domik.integrations.thermo.ThermometerSource
import app.domik.thermo.AdvertisementParser
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Odbiornik reklam BLE termometrów Xiaomi z firmware pvvx (format Custom) lub ATC1441: pasywny skan z filtrem na dane
 * usługi 0x181A, dekodowanie przez [AdvertisementParser]. Niczego nie łączy (GATT) – nie potrzeba `BLUETOOTH_CONNECT`.
 *
 * Jedna korutyna sterująca ([runLoop]) pilnuje stanu: tryb, uprawnienia, Bluetooth/lokalizacja, limity systemu
 * ([BleScanPolicy]: starty skanu i planowy restart co 25 min). Budzą ją [setMode], [refresh] (np. po przyznaniu zgody),
 * odbiornik zmian Bluetooth/lokalizacji i błąd skanu. Nic nie dotyka Bluetooth, dopóki tryb jest [ScanMode.OFF].
 *
 * Maksymalnie jedna instancja na proces (limity skanu są na aplikację).
 */
class AndroidBleThermometerScanner(
    context: Context,
    private val monotonicClock: () -> Long = SystemClock::elapsedRealtime,
    private val wallClock: () -> Long = System::currentTimeMillis,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : ThermometerSource {
    private val appContext = context.applicationContext
    private val policy = BleScanPolicy()

    private val _status = MutableStateFlow(ScannerStatus.OFF)
    override val status: StateFlow<ScannerStatus> = _status.asStateFlow()

    private val _readings = MutableSharedFlow<ThermometerReading>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val readings: SharedFlow<ThermometerReading> = _readings.asSharedFlow()

    @Volatile
    private var requestedMode = ScanMode.OFF
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** Kod błędu z [ScanCallback.onScanFailed] (wątek główny) do przetworzenia w pętli; 0 = brak. */
    private val failureCode = AtomicInteger(0)

    // Stan poniżej dotyka wyłącznie korutyna sterująca.
    private var scanner: BluetoothLeScanner? = null
    private var activeCallback: ScanCallback? = null
    private var activeIntensity = ScanIntensity.NONE
    private var receiverRegistered = false

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            wake.trySend(Unit)
        }
    }

    init {
        scope.launch { runLoop() }
    }

    override fun setMode(mode: ScanMode) {
        requestedMode = mode
        wake.trySend(Unit)
    }

    /** Ponowna ocena warunków (np. po przyznaniu zgody w oknie systemowym albo powrocie z ustawień). */
    fun refresh() {
        wake.trySend(Unit)
    }

    private suspend fun runLoop() {
        while (true) {
            val waitMillis = step()
            if (waitMillis == null) wake.receive() else withTimeoutOrNull(waitMillis) { wake.receive() }
        }
    }

    /** Jedna ocena stanu; zwraca, jak długo czekać do następnej (null = do sygnału budzącego). */
    private fun step(): Long? {
        val now = monotonicClock()
        if (requestedMode == ScanMode.OFF) {
            stopScan()
            setReceiverRegistered(false)
            _status.value = ScannerStatus.OFF
            return null
        }
        setReceiverRegistered(true)

        val failure = failureCode.getAndSet(0)
        if (failure != 0) {
            Log.w(TAG, "Scan failed, code $failure")
            stopScan()
            if (failure == ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED) {
                _status.value = ScannerStatus.UNSUPPORTED
                return null
            }
            _status.value = ScannerStatus.ERROR
        }

        BlePermissions.blocker(appContext)?.let { blocker ->
            stopScan()
            _status.value = blocker
            return null
        }

        val intensity = policy.intensityFor(requestedMode)
        if (activeCallback != null && (intensity != activeIntensity || policy.isRestartDue(now))) stopScan()
        if (activeCallback == null) {
            val delay = policy.millisUntilStartAllowed(now)
            if (delay > 0) return delay
            if (!startScan(intensity, now)) return policy.millisUntilStartAllowed(now).coerceAtLeast(RETRY_MILLIS)
        }
        return policy.millisUntilRestart(now)
    }

    private fun startScan(intensity: ScanIntensity, nowMillis: Long): Boolean {
        val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val leScanner = manager?.adapter?.takeIf(BluetoothAdapter::isEnabled)?.bluetoothLeScanner
        if (leScanner == null) {
            _status.value = ScannerStatus.BLUETOOTH_OFF
            return false
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = handleResult(result)

            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handleResult)

            override fun onScanFailed(errorCode: Int) {
                failureCode.set(errorCode)
                wake.trySend(Unit)
            }
        }
        return try {
            leScanner.startScan(listOf(thermometerFilter()), settingsFor(intensity), callback)
            policy.recordStart(nowMillis)
            scanner = leScanner
            activeCallback = callback
            activeIntensity = intensity
            _status.value = ScannerStatus.SCANNING
            Log.d(TAG, "Scan started ($intensity)")
            true
        } catch (e: SecurityException) {
            _status.value = ScannerStatus.NO_PERMISSION
            false
        } catch (e: IllegalStateException) {
            Log.w(TAG, "startScan failed", e)
            _status.value = ScannerStatus.ERROR
            false
        }
    }

    private fun stopScan() {
        val callback = activeCallback ?: return
        try {
            scanner?.stopScan(callback)
        } catch (e: SecurityException) {
            Log.w(TAG, "stopScan: missing permission")
        } catch (e: IllegalStateException) {
            Log.w(TAG, "stopScan failed", e)
        }
        scanner = null
        activeCallback = null
        activeIntensity = ScanIntensity.NONE
        policy.recordStop()
    }

    private fun handleResult(result: ScanResult) {
        val record = result.scanRecord ?: return
        val payload = record.getServiceData(SERVICE_UUID) ?: return
        val reading = AdvertisementParser.parse(payload) ?: return
        _readings.tryEmit(
            ThermometerReading(
                mac = reading.mac,
                // deviceName z reklamy – bez BLUETOOTH_CONNECT (BluetoothDevice.getName() by tego wymagało).
                name = record.deviceName,
                rssi = result.rssi,
                tempC = reading.tempC,
                humidity = reading.humidity,
                batteryPercent = reading.batteryPercent,
                batteryMv = reading.batteryMv,
                counter = reading.counter,
                flags = reading.flags,
                receivedAtMillis = wallClock(),
            ),
        )
        Log.v(TAG, "Advertisement from ${AdvertisementParser.maskMac(reading.mac)} rssi ${result.rssi}")
    }

    private fun setReceiverRegistered(registered: Boolean) {
        if (registered == receiverRegistered) return
        if (registered) {
            val filter = IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(LocationManager.MODE_CHANGED_ACTION)
            }
            ContextCompat.registerReceiver(appContext, stateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.unregisterReceiver(stateReceiver)
        }
        receiverRegistered = registered
    }

    private companion object {
        const val TAG = "DomikBle"

        /** Pauza przed ponowną próbą po nieudanym starcie (poza limitem częstości z [BleScanPolicy]). */
        const val RETRY_MILLIS = 10_000L

        /** Environmental Sensing (0x181A) – pod tym UUID nadają dane pvvx Custom i ATC1441. */
        val SERVICE_UUID: ParcelUuid = ParcelUuid(UUID.fromString("0000181A-0000-1000-8000-00805F9B34FB"))

        /**
         * Filtr na obecność danych usługi 0x181A (pusty wzorzec + pusta maska = „dowolna zawartość”); dzięki niemu
         * system odfiltrowuje obce reklamy już w kontrolerze Bluetooth.
         */
        fun thermometerFilter(): ScanFilter =
            ScanFilter.Builder().setServiceData(SERVICE_UUID, ByteArray(0), ByteArray(0)).build()

        fun settingsFor(intensity: ScanIntensity): ScanSettings = ScanSettings.Builder()
            .setScanMode(
                if (intensity == ScanIntensity.LOW_LATENCY) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_LOW_POWER,
            )
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setReportDelay(0)
            .setLegacy(true)
            .build()
    }
}
