package app.domik.deimic.client

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
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
    )

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
            if (closedByCaller && error !is ConnectionException) {
                throw ConnectionException(ConnectionErrors.classify(error), "connection closed while connecting", error)
            }
            throw ConnectionErrors.wrap(error)
        }
    }

    private fun openBlocking() {
        val trustManager = PinnedCertificateTrustManager(options.pinnedSha1)
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }

        val plain = Socket()
        synchronized(lock) {
            if (closed) throw ConnectionException(ConnectionProblem.OTHER, "transport closed")
            rawSocket = plain
        }
        plain.tcpNoDelay = true
        plain.keepAlive = true
        plain.connect(InetSocketAddress(endpoint.host, endpoint.port), options.connectTimeout.inWholeMillisecondsInt())

        val socket = context.socketFactory.createSocket(plain, endpoint.host, endpoint.port, true) as SSLSocket
        synchronized(lock) {
            if (closed) throw ConnectionException(ConnectionProblem.OTHER, "transport closed")
            tlsSocket = socket
        }
        val protocols = options.protocols.filter { it in socket.supportedProtocols }
        if (protocols.isEmpty()) {
            throw ConnectionException(ConnectionProblem.TLS_FAILED, "none of ${options.protocols} is supported")
        }
        socket.enabledProtocols = protocols.toTypedArray()
        socket.enabledCipherSuites = CipherSuitePolicy.withRequired(
            socket.enabledCipherSuites, socket.supportedCipherSuites, options.requiredCipherSuites,
        )
        socket.soTimeout = options.handshakeTimeout.inWholeMillisecondsInt()
        socket.startHandshake()
        // Liveness is supervised by the connection (keepalive + watchdog); reads block until data or close.
        socket.soTimeout = 0

        val session = socket.session
        val leaf = session.peerCertificates.firstOrNull()
            ?: throw ConnectionException(ConnectionProblem.TLS_FAILED, "no server certificate")
        info = TransportInfo(
            protocol = session.protocol,
            cipherSuite = session.cipherSuite,
            certificateSha1 = CertificatePinning.sha1Hex(leaf.encoded),
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
