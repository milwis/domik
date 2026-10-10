package app.domik.deimic.client

import app.domik.deimic.protocol.Ack
import app.domik.deimic.protocol.CommandCode
import app.domik.deimic.protocol.ControllerMessage
import app.domik.deimic.protocol.Frame
import app.domik.deimic.protocol.FrameDecoder
import app.domik.deimic.protocol.ProtocolException
import app.domik.deimic.protocol.Request
import app.domik.deimic.protocol.SerialNumber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/** Outcome of [Connection.request]. */
data class RequestResult(
    /** Received frames with one of the expected commands, in arrival order (at least one). */
    val responses: List<Frame>,
    /** True when the controller's acknowledgement (0x1E0 echo of the request) was received. */
    val acknowledged: Boolean,
)

/**
 * Connection to one controller: keeps a single transport open, sends keepalive pings, detects a dead link
 * and reconnects with exponential backoff.
 *
 * All work runs in coroutines of [scope] (cancelling the scope stops the connection). Time is only measured
 * with `delay` / timeouts, so tests can drive it with virtual time.
 *
 * - [state] – lifecycle for the UI.
 * - [frames] / [messages] – every frame received from the controller (raw / decoded), including responses
 *   to requests and spontaneous events. Collectors must keep up: a full buffer suspends the reader.
 *   A frame whose payload length field exceeds its payload area is not a connection problem: it is skipped,
 *   shows up only in [messages] as [ControllerMessage.Malformed] and the connection keeps running. A broken
 *   frame length header ([ConnectionProblem.DECODER_ERROR]) drops the connection like any other failure,
 *   followed by the normal reconnect with backoff.
 * - [send] – fire and forget; [request] – send and wait for a response (plus acknowledgement).
 */
class Connection(
    val endpoint: ControllerEndpoint,
    /** Serial number written into every frame. Its `toString()` is masked. */
    val serial: SerialNumber,
    private val scope: CoroutineScope,
    private val transportFactory: FrameTransportFactory = FrameTransportFactory { TlsSocketTransport(it) },
    val config: ConnectionConfig = ConnectionConfig(),
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _frames = MutableSharedFlow<Frame>(extraBufferCapacity = BUFFER_CAPACITY)
    val frames: SharedFlow<Frame> = _frames.asSharedFlow()

    private val _messages = MutableSharedFlow<ControllerMessage>(extraBufferCapacity = BUFFER_CAPACITY)
    val messages: SharedFlow<ControllerMessage> = _messages.asSharedFlow()

    private val _transportInfo = MutableStateFlow<TransportInfo?>(null)

    /** Details of the current (or last) connection, e.g. TLS version and cipher suite. */
    val transportInfo: StateFlow<TransportInfo?> = _transportInfo.asStateFlow()

    private val lock = Any()
    private var loopJob: Job? = null

    @Volatile
    private var currentTransport: FrameTransport? = null

    @Volatile
    private var session: Session? = null

    /** Starts connecting (and reconnecting) in the background. No-op while already running. */
    fun connect() {
        synchronized(lock) {
            if (loopJob?.isActive == true) return
            _state.value = ConnectionState.Connecting(0)
            loopJob = scope.launch { runLoop() }
        }
    }

    /** Closes the connection and stops reconnecting; [state] becomes [ConnectionState.Idle]. */
    suspend fun disconnect() {
        val job = synchronized(lock) { loopJob.also { loopJob = null } }
        currentTransport?.close()
        job?.cancelAndJoin()
        _state.value = ConnectionState.Idle
    }

    /** Sends [frame] without waiting for anything. Throws [NotConnectedException] unless [state] is Ready. */
    suspend fun send(frame: Frame) {
        require(frame.serial == serial) { "frame was built for a different serial number" }
        val current = session ?: throw NotConnectedException(state.value)
        current.write(frame.encode().toByteArray())
    }

    /**
     * Sends [frame] and waits for the first received frame whose command is in [expecting] and which passes
     * [accept]. Then it keeps collecting further matching frames for [collectWindow] (e.g. one temperature
     * report per sensor), and waits up to [ConnectionConfig.ackGracePeriod] for the acknowledgement if it has
     * not arrived yet. A missing acknowledgement is not an error.
     *
     * Frames that do not match are still delivered to [frames] / [messages] as usual.
     *
     * @throws RequestTimeoutException no matching frame within [timeout].
     * @throws ConnectionException the connection was lost while waiting.
     * @throws NotConnectedException not connected.
     */
    suspend fun request(
        frame: Frame,
        expecting: Set<CommandCode>,
        timeout: Duration = config.requestTimeout,
        collectWindow: Duration = Duration.ZERO,
        accept: (Frame) -> Boolean = { true },
    ): RequestResult = coroutineScope {
        require(expecting.isNotEmpty()) { "expecting must not be empty" }
        require(CommandCode.ACK !in expecting) { "the acknowledgement is awaited automatically" }
        val inbox = Channel<Frame>(Channel.UNLIMITED)
        // Subscribe before sending so that a fast response cannot be missed.
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { frames.collect { inbox.send(it) } }
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            state.first { it != ConnectionState.Ready }
            inbox.close(ConnectionException(ConnectionProblem.CONNECTION_LOST, "connection lost during request"))
        }
        try {
            send(frame)
            val responses = mutableListOf<Frame>()
            var acknowledged = false
            fun consume(received: Frame) {
                if (received.command == CommandCode.ACK) {
                    if (!acknowledged) {
                        acknowledged = (ControllerMessage.decode(received) as? Ack)?.acknowledges(frame) == true
                    }
                } else if (received.command in expecting && accept(received)) {
                    responses += received
                }
            }
            withTimeoutOrNull(timeout) {
                while (responses.isEmpty()) consume(inbox.receive())
            } ?: throw RequestTimeoutException(frame.command, acknowledged)
            if (collectWindow.isPositive()) {
                withTimeoutOrNull(collectWindow) {
                    while (true) consume(inbox.receive())
                }
            }
            if (!acknowledged) {
                withTimeoutOrNull(config.ackGracePeriod) {
                    while (!acknowledged) consume(inbox.receive())
                }
            }
            RequestResult(responses.toList(), acknowledged)
        } finally {
            collector.cancel()
            watcher.cancel()
        }
    }

    private suspend fun runLoop() {
        var failures = 0
        while (true) {
            val transport = transportFactory.create(endpoint)
            currentTransport = transport
            _state.value = ConnectionState.Connecting(failures)
            val end = runSession(transport)
            if (end.reachedReady) failures = 0
            val permanent = end.error.problem == ConnectionProblem.CERTIFICATE_MISMATCH
            if (permanent || failures >= config.maxReconnectAttempts) {
                _state.value = ConnectionState.Failed(end.error.problem, end.error.message)
                return
            }
            failures++
            val wait = config.reconnectDelay(failures)
            _state.value = ConnectionState.WaitingToReconnect(failures, wait, end.error.problem)
            delay(wait)
        }
    }

    private class SessionEnd(val error: ConnectionException, val reachedReady: Boolean)

    /** Runs one connection until it fails; always closes [transport]. */
    private suspend fun runSession(transport: FrameTransport): SessionEnd {
        try {
            openWithTimeout(transport)
        } catch (cancelled: CancellationException) {
            transport.close()
            throw cancelled
        } catch (error: Throwable) {
            transport.close()
            return SessionEnd(ConnectionErrors.wrap(error), reachedReady = false)
        }

        val failure = CompletableDeferred<ConnectionException>()
        fun fail(error: Throwable) {
            failure.complete(ConnectionErrors.wrap(error))
        }

        val sessionJob = SupervisorJob(currentCoroutineContext().job)
        val sessionScope = CoroutineScope(currentCoroutineContext() + sessionJob)
        val activity = Channel<Unit>(Channel.CONFLATED)
        val current = Session(transport, ::fail)
        val decoder = FrameDecoder()

        fun launchGuarded(block: suspend () -> Unit) = sessionScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                fail(error)
            }
        }

        try {
            _transportInfo.value = transport.info
            session = current
            _state.value = ConnectionState.Ready

            launchGuarded {
                while (true) {
                    val chunk = transport.read()
                        ?: throw ConnectionException(ConnectionProblem.CONNECTION_LOST, "connection closed by the controller")
                    activity.trySend(Unit)
                    val received = try {
                        decoder.decode(chunk)
                    } catch (error: ProtocolException) {
                        // Broken length header = lost sync: drop the connection (reconnect with backoff).
                        throw ConnectionException(ConnectionProblem.DECODER_ERROR, "invalid data: ${error.message}", error)
                    }
                    for (frame in received.frames) {
                        _frames.emit(frame)
                        _messages.emit(ControllerMessage.decode(frame))
                    }
                    // Frame with an inconsistent payload length: skipped, framing intact, connection continues.
                    for (malformed in received.malformed) _messages.emit(malformed)
                }
            }
            launchGuarded {
                while (true) {
                    delay(config.keepAliveInterval)
                    current.write(Request.ping(serial).encode().toByteArray())
                }
            }
            launchGuarded {
                while (true) {
                    withTimeoutOrNull(config.livenessTimeout) { activity.receive() }
                        ?: throw ConnectionException(
                            ConnectionProblem.NO_RESPONSE,
                            "no data from the controller for ${config.livenessTimeout}",
                        )
                }
            }
            return SessionEnd(failure.await(), reachedReady = true)
        } finally {
            session = null
            transport.close()
            withContext(NonCancellable) { sessionJob.cancelAndJoin() }
        }
    }

    private suspend fun openWithTimeout(transport: FrameTransport) = coroutineScope {
        var timedOut = false
        val guard = launch {
            delay(config.connectTimeout)
            timedOut = true
            transport.close()
        }
        try {
            transport.connect()
        } catch (error: Throwable) {
            if (timedOut && error !is CancellationException) {
                throw ConnectionException(ConnectionProblem.TIMEOUT, "connecting took longer than ${config.connectTimeout}", error)
            }
            throw error
        } finally {
            guard.cancel()
        }
    }

    /** Write side of an open connection; writes are serialized. */
    private class Session(private val transport: FrameTransport, private val onFailure: (Throwable) -> Unit) {
        private val writeLock = Mutex()

        suspend fun write(bytes: ByteArray) {
            writeLock.withLock {
                try {
                    transport.write(bytes)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    val wrapped = ConnectionErrors.wrap(error)
                    onFailure(wrapped)
                    throw wrapped
                }
            }
        }
    }

    private companion object {
        const val BUFFER_CAPACITY = 1024
    }
}
