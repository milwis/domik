package app.domik.deimic.client

import app.domik.deimic.protocol.ControllerMessage
import app.domik.deimic.protocol.FrameDecoder
import app.domik.deimic.protocol.Request
import app.domik.deimic.protocol.toBytes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetAddress
import java.net.ServerSocket
import javax.net.ssl.SSLServerSocket
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

/** Real TLS over loopback against a local server with a freshly generated self-signed certificate. */
class TlsSocketTransportTest {
    private val certificate = SelfSignedCertificate.generate()
    private val server = certificate.serverContext().serverSocketFactory
        .createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket
    private val endpoint = ControllerEndpoint("127.0.0.1", server.localPort)

    /** Accepts one client: answers each received frame (ping) with a pong, until the client disconnects. */
    private fun startPongServer() = thread(isDaemon = true) {
        runCatching {
            server.accept().use { client ->
                val decoder = FrameDecoder()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = client.inputStream.read(buffer)
                    if (count < 0) break
                    decoder.append(buffer, 0, count).forEach { _ ->
                        client.outputStream.write(pong().encode().toByteArray())
                        client.outputStream.flush()
                    }
                }
            }
        }
    }

    @AfterEach
    fun tearDown() {
        server.close()
    }

    @Test
    fun handshakeWithPinnedCertificateAndExchangeFrames() = runBlocking {
        startPongServer()
        val transport = TlsSocketTransport(endpoint, TlsSocketTransport.Options(pinnedSha1 = certificate.sha1))
        try {
            transport.connect()
            val info = transport.info!!
            assertEquals(certificate.sha1, info.certificateSha1)
            assertTrue(info.protocol == "TLSv1.3" || info.protocol == "TLSv1.2", info.protocol)

            transport.write(Request.ping(FAKE_SERIAL).encode().toByteArray())
            val decoder = FrameDecoder()
            val frames = withTimeout(5.seconds) {
                var received = emptyList<app.domik.deimic.protocol.Frame>()
                while (received.isEmpty()) received = decoder.append(transport.read()!!.toBytes())
                received
            }
            assertEquals(ControllerMessage.Pong(0x1FFu), ControllerMessage.decode(frames.single()))
        } finally {
            transport.close()
        }
    }

    @Test
    fun differentCertificateIsCertificateMismatch() = runBlocking {
        startPongServer()
        val transport = TlsSocketTransport(endpoint) // pinned to the controller's fingerprint
        val error = assertThrows<ConnectionException> { runBlocking { transport.connect() } }
        assertEquals(ConnectionProblem.CERTIFICATE_MISMATCH, error.problem)
    }

    @Test
    fun closedPortIsConnectionRefused() = runBlocking {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val transport = TlsSocketTransport(ControllerEndpoint("127.0.0.1", port))
        val error = assertThrows<ConnectionException> { runBlocking { transport.connect() } }
        assertEquals(ConnectionProblem.CONNECTION_REFUSED, error.problem)
        assertTrue("closed while" !in error.message.orEmpty(), error.message)
    }

    @Test
    fun silentServerIsHandshakeTimeout() = runBlocking {
        // Plain TCP server that accepts but never answers the TLS ClientHello.
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { silent ->
            thread(isDaemon = true) { runCatching { silent.accept().use { Thread.sleep(5_000) } } }
            val transport = TlsSocketTransport(
                ControllerEndpoint("127.0.0.1", silent.localPort),
                TlsSocketTransport.Options(handshakeTimeout = 1.seconds),
            )
            val error = assertThrows<ConnectionException> { runBlocking { transport.connect() } }
            assertEquals(ConnectionProblem.TIMEOUT, error.problem)
        }
    }

    @Test
    fun connectionOverRealTlsBecomesReadyAndAnswersRequests() = runBlocking {
        startPongServer()
        val connection = Connection(
            endpoint, FAKE_SERIAL, this,
            transportFactory = { TlsSocketTransport(it, TlsSocketTransport.Options(pinnedSha1 = certificate.sha1)) },
        )
        try {
            connection.connect()
            withTimeout(10.seconds) { connection.state.first { it == ConnectionState.Ready } }
            val result = connection.request(Request.ping(FAKE_SERIAL), setOf(app.domik.deimic.protocol.CommandCode.PING))
            assertEquals(1, result.responses.size)
            assertEquals(certificate.sha1, connection.transportInfo.value?.certificateSha1)
        } finally {
            connection.disconnect()
        }
    }
}
