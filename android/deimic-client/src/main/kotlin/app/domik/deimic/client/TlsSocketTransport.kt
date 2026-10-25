package app.domik.deimic.client

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.Provider
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * TLS over TCP using `javax.net.ssl` (works on the JVM and on Android).
 *
 * Server authentication is done **only** by certificate pinning ([PinnedCertificateTrustManager]): the
 * controller uses a self-signed certificate and is addressed by IP, so host name verification is intentionally
 * not enabled (no endpoint identification algorithm is set on the socket).
 *
 * Blocking socket calls run on [ioDispatcher]; [close] unblocks a pending connect / read.
 *
 * A failed [connect] throws a [ConnectionException] carrying [TlsDiagnostics] (provider, phase, error chain,
 * certificate fingerprints, protocols); a successful one exposes them in [info].
 */
class TlsSocketTransport(
    private val endpoint: ControllerEndpoint,
    private val options: Options = Options(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : FrameTransport {

    data class Options(
        val pinnedSha1: String = CertificatePinning.CONTROLLER_CERTIFICATE_SHA1,
        val connectTimeout: Duration = 5.seconds,
        val handshakeTimeout: Duration = 10.seconds,
        /** Enabled only if supported by the platform; the controller itself speaks TLS 1.2. */
        val protocols: List<String> = listOf("TLSv1.3", "TLSv1.2"),
        /**
         * Cipher suites that must be enabled when the platform supports them. The controller negotiates only
         * `TLS_RSA_WITH_AES_256_GCM_SHA384` (RSA key exchange), which a platform may leave out of its default
         * list. Only adds suites; nothing is removed and the certificate pin is unaffected.
         */
        val requiredCipherSuites: List<String> = CipherSuitePolicy.CONTROLLER_SUITES,
        val readBufferSize: Int = 16 * 1024,
        /**
         * TLS implementation to create the `SSLContext` with (`SSLContext.getInstance("TLS", provider)`); null = the
         * platform's default one. It is only passed to this transport and never installed process-wide, so other
         * TLS users in the same process are unaffected.
         */
        val provider: Provider? = null,
        /** Free-text remark about the provider choice, shown in [TlsDiagnostics] (e.g. why a fallback was used). */
        val providerNote: String? = null,
    )

    /** What is known about the connection attempt so far; read only to build [TlsDiagnostics]. */
    private class Attempt(val pinnedSha1: String) {
        @Volatile var provider: String = "unknown"
        @Volatile var phase: TlsPhase = TlsPhase.TCP_CONNECT
        @Volatile var trustManager: PinnedCertificateTrustManager? = null
        @Volatile var enabledProtocols: List<String> = emptyList()
        @Volatile var supportedProtocols: List<String> = emptyList()
        @Volatile var suiteSupported: Boolean? = null
        @Volatile var suiteEnabled: Boolean? = null
    }

    private val attempt = Attempt(runCatching { CertificatePinning.normalize(options.pinnedSha1) }.getOrDefault(options.pinnedSha1))

    private val lock = Any()
    private var rawSocket: Socket? = null
    private var tlsSocket: SSLSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    @Volatile
    private var closed = false

    @Volatile
    override var info: TransportInfo? = null
        private set

    override suspend fun connect(): Unit = withContext(ioDispatcher) {
        try {
            openBlocking()
        } catch (error: Throwable) {
            val closedByCaller = closed
            close()
            val diagnostics = diagnosticsOf(error)
            if (closedByCaller && error !is ConnectionException) {
                throw ConnectionException(
                    ConnectionErrors.classify(error), "connection closed while connecting", error, diagnostics,
                )
            }
            val wrapped = ConnectionErrors.wrap(error)
            if (wrapped.diagnostics != null) throw wrapped
            throw ConnectionException(wrapped.problem, wrapped.message.orEmpty(), wrapped.cause, diagnostics)
        }
    }

    private fun diagnosticsOf(error: Throwable?): TlsDiagnostics {
        val presented = attempt.trustManager?.lastPresentedSha1
        val pinMismatch = generateSequence(error) { it.cause?.takeIf { cause -> cause !== it } }
            .take(16).any { it is CertificatePinMismatchException }
        val phase = if (error != null && pinMismatch) TlsPhase.CERTIFICATE_CHECK else attempt.phase
        return TlsDiagnostics(
            provider = attempt.provider,
            providerNote = options.providerNote,
            phase = phase,
            errorChain = error?.let { TlsDiagnostics.errorChain(it) }.orEmpty(),
            presentedSha1 = presented,
            expectedSha1 = attempt.pinnedSha1,
            enabledProtocols = attempt.enabledProtocols,
            supportedProtocols = attempt.supportedProtocols,
            controllerSuiteSupported = attempt.suiteSupported,
            controllerSuiteEnabled = attempt.suiteEnabled,
        )
    }

    @Suppress("DEPRECATION") // Provider.getVersionStr() needs API 34 on Android; getVersion() exists everywhere.
    private fun describe(provider: Provider): String = "${provider.name} ${provider.version}"

    private fun openBlocking() {
        val trustManager = PinnedCertificateTrustManager(options.pinnedSha1)
        attempt.trustManager = trustManager
        attempt.provider = options.provider?.let(::describe) ?: "platform default"
        val context = (options.provider?.let { SSLContext.getInstance("TLS", it) } ?: SSLContext.getInstance("TLS"))
            .apply { init(null, arrayOf(trustManager), null) }
        attempt.provider = describe(context.provider)

        val plain = Socket()
        synchronized(lock) {
            if (closed) throw ConnectionException(ConnectionProblem.OTHER, "transport closed")
            rawSocket = plain
        }
        plain.tcpNoDelay = true
        plain.keepAlive = true
        plain.connect(InetSocketAddress(endpoint.host, endpoint.port), options.connectTimeout.inWholeMillisecondsInt())

        attempt.phase = TlsPhase.TLS_HANDSHAKE
        val socket = context.socketFactory.createSocket(plain, endpoint.host, endpoint.port, true) as SSLSocket
        synchronized(lock) {
            if (closed) throw ConnectionException(ConnectionProblem.OTHER, "transport closed")
            tlsSocket = socket
        }
        attempt.supportedProtocols = socket.supportedProtocols.toList()
        val protocols = options.protocols.filter { it in socket.supportedProtocols }
        if (protocols.isEmpty()) {
            throw ConnectionException(ConnectionProblem.TLS_FAILED, "none of ${options.protocols} is supported")
        }
        socket.enabledProtocols = protocols.toTypedArray()
        socket.enabledCipherSuites = CipherSuitePolicy.withRequired(
            socket.enabledCipherSuites, socket.supportedCipherSuites, options.requiredCipherSuites,
        )
        attempt.enabledProtocols = socket.enabledProtocols.toList()
        attempt.suiteSupported = CipherSuitePolicy.CONTROLLER_SUITE in socket.supportedCipherSuites
        attempt.suiteEnabled = CipherSuitePolicy.CONTROLLER_SUITE in socket.enabledCipherSuites
        socket.soTimeout = options.handshakeTimeout.inWholeMillisecondsInt()
        socket.startHandshake()
        attempt.phase = TlsPhase.ESTABLISHED
        // Liveness is supervised by the connection (keepalive + watchdog); reads block until data or close.
        socket.soTimeout = 0

        val session = socket.session
        val leaf = session.peerCertificates.firstOrNull()
            ?: throw ConnectionException(ConnectionProblem.TLS_FAILED, "no server certificate")
        info = TransportInfo(
            protocol = session.protocol,
            cipherSuite = session.cipherSuite,
            certificateSha1 = CertificatePinning.sha1Hex(leaf.encoded),
            diagnostics = diagnosticsOf(null).copy(
                negotiatedProtocol = session.protocol,
                negotiatedCipherSuite = session.cipherSuite,
                presentedSha1 = CertificatePinning.sha1Hex(leaf.encoded),
            ),
        )
        input = socket.inputStream
        output = socket.outputStream
    }

    override suspend fun read(): ByteArray? = withContext(ioDispatcher) {
        val stream = input ?: throw ConnectionException(ConnectionProblem.OTHER, "not connected")
        val buffer = ByteArray(options.readBufferSize)
        val count = try {
            stream.read(buffer)
        } catch (error: IOException) {
            if (closed) throw ConnectionException(ConnectionProblem.CONNECTION_LOST, "transport closed", error)
            throw ConnectionErrors.wrap(error)
        }
        if (count < 0) null else buffer.copyOf(count)
    }

    override suspend fun write(bytes: ByteArray): Unit = withContext(ioDispatcher) {
        val stream = output ?: throw ConnectionException(ConnectionProblem.OTHER, "not connected")
        try {
            stream.write(bytes)
            stream.flush()
        } catch (error: IOException) {
            if (closed) throw ConnectionException(ConnectionProblem.CONNECTION_LOST, "transport closed", error)
            throw ConnectionErrors.wrap(error)
        }
    }

    override fun close() {
        val (raw, tls) = synchronized(lock) {
            closed = true
            rawSocket to tlsSocket
        }
        // Close the TCP socket first: this unblocks a pending read / handshake immediately, without the TLS
        // layer waiting to send close_notify.
        runCatching { raw?.close() }
        runCatching { tls?.close() }
    }

    private fun Duration.inWholeMillisecondsInt(): Int = inWholeMilliseconds.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
}
