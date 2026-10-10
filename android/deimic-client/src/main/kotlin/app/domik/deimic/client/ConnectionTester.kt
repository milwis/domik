package app.domik.deimic.client

import app.domik.deimic.protocol.CommandCode
import app.domik.deimic.protocol.ConfigChecksum
import app.domik.deimic.protocol.ControllerMessage
import app.domik.deimic.protocol.FirmwareVersion
import app.domik.deimic.protocol.Frame
import app.domik.deimic.protocol.Request
import app.domik.deimic.protocol.SerialNumber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Steps of the connection test, in execution order. */
enum class ConnectionTestStep {
    /** TCP + TLS handshake including the certificate pin check. */
    TLS,

    /** Ping (0x1CE) – the first command carrying the serial number. */
    PING,

    /** Firmware version (0x1D6). */
    FIRMWARE_VERSION,

    /** Configuration database checksum (0x1C8). */
    CONFIG_CHECKSUM,
}

enum class StepStatus { PASSED, FAILED, SKIPPED }

/**
 * Result of one step. [detail] is technical text for diagnostics (TLS version and cipher, firmware text,
 * checksum hex, error message) – not a UI string. For a failed [ConnectionTestStep.PING] step,
 * [acknowledged] tells whether the controller at least acknowledged the request.
 */
data class StepResult(
    val step: ConnectionTestStep,
    val status: StepStatus,
    val problem: ConnectionProblem? = null,
    val detail: String? = null,
    val acknowledged: Boolean? = null,
)

data class ConnectionTestReport(
    val steps: List<StepResult>,
    val transportInfo: TransportInfo? = null,
    val firmwareVersion: String? = null,
    val configChecksum: ConfigChecksum? = null,
) {
    val succeeded: Boolean get() = steps.all { it.status == StepStatus.PASSED }

    /** The first failed step, if any. */
    val failure: StepResult? get() = steps.firstOrNull { it.status == StepStatus.FAILED }
}

/**
 * "Test connection" for onboarding / settings: TLS → ping → firmware version → configuration checksum.
 * Stops at the first failed step (the remaining steps are [StepStatus.SKIPPED]). Never retries and never
 * sends anything that changes the controller's state.
 */
class ConnectionTester(
    private val transportFactory: FrameTransportFactory = FrameTransportFactory { TlsSocketTransport(it) },
    private val config: ConnectionConfig = ConnectionConfig(),
    private val stepTimeout: Duration = 5.seconds,
) {
    suspend fun run(endpoint: ControllerEndpoint, serial: SerialNumber): ConnectionTestReport = coroutineScope {
        val connection = Connection(
            endpoint = endpoint,
            serial = serial,
            scope = this,
            transportFactory = transportFactory,
            config = config.copy(maxReconnectAttempts = 0),
        )
        val results = mutableListOf<StepResult>()
        var firmware: String? = null
        var checksum: ConfigChecksum? = null
        try {
            connection.connect()
            when (val reached = connection.state.first { it == ConnectionState.Ready || it.isTerminal() }) {
                ConnectionState.Ready -> {
                    val info = connection.transportInfo.value
                    results += StepResult(
                        ConnectionTestStep.TLS, StepStatus.PASSED,
                        detail = info?.let { "${it.protocol} ${it.cipherSuite}" },
                    )
                }
                else -> results += StepResult(
                    ConnectionTestStep.TLS, StepStatus.FAILED,
                    problem = reached.problemOrOther(), detail = (reached as? ConnectionState.Failed)?.detail,
                )
            }

            if (results.last().status == StepStatus.PASSED) {
                results += step(ConnectionTestStep.PING) {
                    connection.request(Request.ping(serial), setOf(CommandCode.PING), stepTimeout)
                    null
                }
            }
            if (results.last().status == StepStatus.PASSED) {
                results += step(ConnectionTestStep.FIRMWARE_VERSION) {
                    val frame = connection.request(
                        Request.firmwareVersion(serial), setOf(CommandCode.FIRMWARE_VERSION), stepTimeout,
                    ).responses.first()
                    parse<FirmwareVersion>(frame).text.also { firmware = it }
                }
            }
            if (results.last().status == StepStatus.PASSED) {
                results += step(ConnectionTestStep.CONFIG_CHECKSUM) {
                    val frame = connection.request(
                        Request.configChecksum(serial), setOf(CommandCode.CONFIG_CHECKSUM), stepTimeout,
                    ).responses.first()
                    parse<ConfigChecksum>(frame).also { checksum = it }.hexString
                }
            }
            val done = results.map { it.step }.toSet()
            ConnectionTestStep.entries.filter { it !in done }.forEach { results += StepResult(it, StepStatus.SKIPPED) }
            ConnectionTestReport(results, connection.transportInfo.value, firmware, checksum)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun step(step: ConnectionTestStep, block: suspend () -> String?): StepResult =
        try {
            StepResult(step, StepStatus.PASSED, detail = block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (timeout: RequestTimeoutException) {
            StepResult(step, StepStatus.FAILED, timeout.problem, timeout.message, timeout.acknowledged)
        } catch (error: Throwable) {
            StepResult(step, StepStatus.FAILED, ConnectionErrors.classify(error), error.message)
        }

    private inline fun <reified T : ControllerMessage> parse(frame: Frame): T =
        when (val message = ControllerMessage.decode(frame)) {
            is T -> message
            is ControllerMessage.Malformed -> throw ConnectionException(
                ConnectionProblem.OTHER, "unexpected response: ${message.error.message}", message.error,
            )
            else -> throw ConnectionException(ConnectionProblem.OTHER, "unexpected response ${frame.command}")
        }

    private fun ConnectionState.isTerminal(): Boolean =
        this is ConnectionState.Failed || this is ConnectionState.WaitingToReconnect

    private fun ConnectionState.problemOrOther(): ConnectionProblem = when (this) {
        is ConnectionState.Failed -> problem
        is ConnectionState.WaitingToReconnect -> problem
        else -> ConnectionProblem.OTHER
    }
}
