package app.domik.data

import app.domik.demo.DemoNetwork
import app.domik.demo.DemoSimulation
import app.domik.demo.FakeController
import app.domik.deimic.client.Connection
import app.domik.deimic.client.ConnectionConfig
import app.domik.deimic.client.ControllerProfile
import app.domik.deimic.client.FrameTransportFactory
import app.domik.deimic.client.commands.DeviceCommands
import app.domik.deimic.config.ConfigCache
import app.domik.deimic.config.ConfigStore
import app.domik.deimic.protocol.SerialNumber
import app.domik.integrations.thermo.CalibrationPort
import app.domik.integrations.thermo.DeviceCommandsCalibrationPort
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Jedna „sesja” pracy z domem: [repository] z połączeniem (prawdziwym albo symulowanym) i wszystkim, co do niego
 * należy. Zamknięcie sesji ([close]) zrywa połączenie i kończy jej korutyny.
 *
 * @property profileId identyfikator profilu (klucz kopii konfiguracji i układu pulpitu)
 * @property demoController symulowany sterownik (tylko w trybie demo) – do ręcznego „poruszenia domem” w testach
 * @property calibration odczyt i zapis kalibracji wejść analogowych tego samego połączenia (dla modułu termometrów)
 * @property controllerSerial numer seryjny sterownika sesji – wyłącznie do wyliczenia klucza plików termometrów
 *   (`ThermoSettings.controllerKey`); nie logować, nie pokazywać
 */
class HomeSession internal constructor(
    val profileId: String,
    val repository: HomeRepository,
    val demoController: FakeController?,
    val calibration: CalibrationPort,
    private val scope: CoroutineScope,
    internal val controllerSerial: SerialNumber,
) {
    val isDemo: Boolean get() = demoController != null

    suspend fun close() {
        try {
            repository.stop()
        } finally {
            scope.cancel()
        }
    }
}

/** Składanie sesji: tryb demo (symulowany sterownik w pamięci) i prawdziwy sterownik w sieci LAN. */
object Sessions {
    /**
     * Sesja demo: [FakeController] mówi tym samym protokołem co sterownik, a wszystko powyżej (połączenie, pobranie
     * i zapis konfiguracji w [cache], polecenia) działa bez zmian. Nic nie łączy się z siecią.
     *
     * @param scope zakres nadrzędny; sesja tworzy własny podrzędny, więc [HomeSession.close] nie rusza reszty
     */
    fun demo(
        scope: CoroutineScope,
        cache: ConfigCache,
        network: DemoNetwork = DemoNetwork(),
        simulation: DemoSimulation = DemoSimulation(),
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        settings: HomeRepositoryConfig = HomeRepositoryConfig(),
        connectionConfig: ConnectionConfig = ConnectionConfig(),
    ): HomeSession {
        val sessionScope = childScope(scope)
        val controller = FakeController(sessionScope, network = network, simulation = simulation)
        val connection = Connection(
            controller.endpoint, controller.serial, sessionScope, controller.transportFactory, connectionConfig,
        )
        val commands = DeviceCommands(connection, experimentalProfileSwitching = Features.PROFILE_SWITCHING)
        val repository = HomeRepository(
            sessionScope,
            connection,
            commands,
            ConfigStore(connection, controller.profile, cache, ioDispatcher = ioDispatcher),
            settings,
        )
        return HomeSession(
            controller.profile.id, repository, controller, DeviceCommandsCalibrationPort(commands), sessionScope, controller.serial,
        )
    }

    /**
     * Sesja z prawdziwym sterownikiem pod adresem z [profile] (TLS z przypięciem certyfikatu).
     *
     * @param transportFactory inny transport niż TLS po gnieździe – wyłącznie dla testów
     */
    fun controller(
        scope: CoroutineScope,
        profile: ControllerProfile,
        serial: SerialNumber,
        cache: ConfigCache,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        settings: HomeRepositoryConfig = HomeRepositoryConfig(),
        connectionConfig: ConnectionConfig = ConnectionConfig(),
        transportFactory: FrameTransportFactory? = null,
    ): HomeSession {
        val sessionScope = childScope(scope)
        val connection = if (transportFactory == null) {
            Connection(profile.endpoint, serial, sessionScope, config = connectionConfig)
        } else {
            Connection(profile.endpoint, serial, sessionScope, transportFactory, connectionConfig)
        }
        val commands = DeviceCommands(connection, experimentalProfileSwitching = Features.PROFILE_SWITCHING)
        val repository = HomeRepository(
            sessionScope,
            connection,
            commands,
            ConfigStore(connection, profile, cache, ioDispatcher = ioDispatcher),
            settings,
        )
        return HomeSession(profile.id, repository, null, DeviceCommandsCalibrationPort(commands), sessionScope, serial)
    }

    private fun childScope(parent: CoroutineScope): CoroutineScope =
        CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
}
