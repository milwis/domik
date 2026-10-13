package app.domik.integrations.thermo

import app.domik.data.Features
import app.domik.data.HomeSession
import app.domik.demo.FakeController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlin.random.Random

/** Składanie [ThermoRuntime] dla sesji: prawdziwy sterownik (odbiornik BLE) albo tryb demo (termometry symulowane). */
object ThermoSessions {
    /** Termometr demo stoi w zimniejszym narożniku: pokazuje tyle mniej niż „prawdziwa” temperatura pokoju. */
    const val DEMO_CORNER_OFFSET_C = -1.5f

    /** Rozrzut pomiaru termometru demo (±połowa tej wartości). */
    const val DEMO_NOISE_C = 0.1f

    /** Termometry demo nadają co 5 s (prawdziwe pvvx: pomiar co ~10 s). */
    const val DEMO_INTERVAL_MILLIS = 5_000L

    /**
     * Uruchamia termometry dla [session]. Demo: termometr n ↔ n-ta strefa domu demo, temperatura = temperatura pokoju
     * z symulacji + [DEMO_CORNER_OFFSET_C] + szum; pliki tylko w pamięci; bez zapisanych ustawień demo ma funkcję
     * włączoną w trybie rzeczywistym (zapis do symulatora). Prawdziwy sterownik: [source] (odbiornik BLE; jego tryb
     * skanu ustawia UI) i pliki profilu z [settings].
     *
     * @param parent zakres sesji; runtime dostaje własny podrzędny, zamykany przez [ThermoRuntime.close]
     */
    fun create(
        session: HomeSession,
        settings: ThermoSettings,
        source: ThermometerSource?,
        clock: () -> Long,
        parent: CoroutineScope,
        timing: ThermoTiming? = null,
        writesFeatureEnabled: Boolean = Features.SENSOR_CALIBRATION_WRITES,
    ): ThermoRuntime {
        val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
        val controller = session.demoController
        val runtime = if (controller != null) {
            ThermoRuntime(
                scope = scope,
                home = session.repository.state,
                connection = session.repository.connectionState,
                port = session.calibration,
                source = demoThermometers(controller, scope, clock),
                files = ThermoFiles(null),
                settings = settings.profile(session.profileId, demoDefaults(controller)),
                hub = settings.hub,
                demo = true,
                clock = clock,
                timing = timing ?: ThermoTiming.DEMO,
                writesFeatureEnabled = writesFeatureEnabled,
            )
        } else {
            ThermoRuntime(
                scope = scope,
                home = session.repository.state,
                connection = session.repository.connectionState,
                port = session.calibration,
                source = source,
                // Pliki per sterownik (skrót numeru seryjnego), nie per profil: przetrwają zmianę adresu.
                files = settings.files(settings.controllerKey(session.controllerSerial), legacyProfileId = session.profileId),
                settings = settings.profile(session.profileId),
                hub = settings.hub,
                demo = false,
                clock = clock,
                timing = timing ?: ThermoTiming.DEFAULT,
                writesFeatureEnabled = writesFeatureEnabled,
            )
        }
        runtime.start()
        return runtime
    }

    /** Ustawienia demo, dopóki użytkownik nic nie zmienił: włączone, rzeczywiste (symulator), termometr n → strefa n. */
    fun demoDefaults(controller: FakeController): ThermoProfileSettings = ThermoProfileSettings(
        enabled = true,
        dryRun = false,
        assignments = controller.home.zones.mapIndexed { index, zone ->
            ThermoAssignment(DemoThermometers.macFor(index + 1), zone.module, zone.outputNr, null, "Demo ${index + 1}")
        },
    )

    private fun demoThermometers(controller: FakeController, scope: CoroutineScope, clock: () -> Long): DemoThermometers {
        val zones = controller.home.zones
        val noise = Random(DEMO_SEED)
        val byMac = zones.mapIndexed { index, zone -> DemoThermometers.macFor(index + 1) to zone }.toMap()
        return DemoThermometers(
            scope = scope,
            temperatureOf = { mac ->
                byMac[mac]?.let { zone ->
                    controller.roomTemperature(zone.module, zone.outputNr)
                        ?.plus(DEMO_CORNER_OFFSET_C + (noise.nextFloat() - 0.5f) * DEMO_NOISE_C)
                }
            },
            count = zones.size.coerceAtLeast(1),
            intervalMillis = DEMO_INTERVAL_MILLIS,
            clock = clock,
        )
    }

    private const val DEMO_SEED = 7
}
