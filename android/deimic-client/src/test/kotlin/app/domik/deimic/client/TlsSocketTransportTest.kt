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
import java.security.Security
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

    /** Plain-text (non-TLS) peer: accepts, sends an HTTP-looking reply and closes. */
    private inline fun <T> withPlainServer(block: (ControllerEndpoint) -> T): T =
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { plain ->
            thread(isDaemon = true) {
                runCatching {
                    plain.accept().use { it.outputStream.write("HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray()) }
                }
            }
            block(ControllerEndpoint("127.0.0.1", plain.localPort))
        }

    @Test
    fun explicitProviderIsUsedAndReportedInDiagnostics() = runBlocking {
        startPongServer()
        val sunJsse = Security.getProvider("SunJSSE")
        val transport = TlsSocketTransport(
            endpoint,
            TlsSocketTransport.Options(pinnedSha1 = certificate.sha1, provider = sunJsse, providerNote = "test note"),
        )
        try {
            transport.connect()
            val diagnostics = transport.info!!.diagnostics!!
            assertTrue(diagnostics.provider.startsWith("SunJSSE "), diagnostics.provider)
            assertEquals("test note", diagnostics.providerNote)
            assertEquals(TlsPhase.ESTABLISHED, diagnostics.phase)
            assertEquals(certificate.sha1, diagnostics.presentedSha1)
            assertEquals(transport.info!!.protocol, diagnostics.negotiatedProtocol)
            assertEquals(transport.info!!.cipherSuite, diagnostics.negotiatedCipherSuite)
            assertTrue(diagnostics.enabledProtocols.isNotEmpty())
            // Recent JDKs disable TLS_RSA_* (see JvmTlsCompatibility), so only the presence of the answer is stable.
            assertTrue(diagnostics.controllerSuiteSupported != null && diagnostics.controllerSuiteEnabled != null)
            assertTrue(diagnostics.formatBrief().contains("provider SunJSSE"), diagnostics.formatBrief())
        } finally {
            transport.close()
        }
    }

    @Test
    fun unavailableProviderAlgorithmFailsClearly() = runBlocking {
        // A provider that does not offer SSLContext "TLS" must not silently fall back to another one.
        val empty = object : java.security.Provider("Empty", "1.0", "no services") {}
        val transport = TlsSocketTransport(endpoint, TlsSocketTransport.Options(provider = empty))
        val error = assertThrows<ConnectionException> { runBlocking { transport.connect() } }
        assertTrue(error.diagnostics!!.errorChain.first().contains("NoSuchAlgorithmException"), error.diagnostics.toString())
    }

    @Test
    fun pinMismatchDiagnosticsShowBothFingerprints() = runBlocking {
        startPongServer()
        val transport = TlsSocketTransport(endpoint) // pinned to the controller's fingerprint
        val error = assertThrows<ConnectionException> { runBlocking { transport.connect() } }
        val diagnostics = error.diagnostics!!
        assertEquals(TlsPhase.CERTIFICATE_CHECK, diagnostics.phase)
        assertEquals(certificate.sha1, diagnostics.presentedSha1)
        assertEquals(CertificatePinning.CONTROLLER_CERTIFICATE_SHA1, diagnostics.expectedSha1)
        assertTrue(diagnostics.errorChain.any { it.contains("CertificatePinMismatchException") }, diagnostics.errorChain.toString())
        val text = diagnostics.format()
        assertTrue(text.contains("Certificate SHA-1: ${certificate.sha1} (DIFFERS from the pin)"), text)
        assertTrue(text.contains("Expected SHA-1:    ${CertificatePinning.CONTROLLER_CERTIFICATE_SHA1}"), text)
        assertTrue(text.contains("Phase: certificate check"), text)
    }

    @Test
    fun nonTlsPeerFailsInTheHandshakePhase() = runBlocking {
        withPlainServer { plainEndpoint ->
            val transport = TlsSocketTransport(plainEndpoint, TlsSocketTransport.Options(pinnedSha1 = certificate.sha1))
            val error = assertThrows<ConnectionException> { runBlocking { transport.connect() } }
            assertEquals(ConnectionProblem.TLS_FAILED, error.problem)
            val diagnostics = error.diagnostics!!
            assertEquals(TlsPhase.TLS_HANDSHAKE, diagnostics.phase)
            assertEquals(null, diagnostics.presentedSha1)
            assertTrue(diagnostics.errorChain.first().contains("SSLException"), diagnostics.errorChain.toString())
            assertTrue(diagnostics.enabledProtocols.isNotEmpty())
            assertTrue(diagnostics.format().contains("Certificate SHA-1: none presented"))
        }
    }

    @Test
    fun refusedConnectionFailsInTheTcpPhase() = runBlocking {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val transport = TlsSocketTransport(ControllerEndpoint("127.0.0.1", port))
        val error = assertThrows<ConnectionException> { runBlocking { transport.connect() } }
        val diagnostics = error.diagnostics!!
        assertEquals(TlsPhase.TCP_CONNECT, diagnostics.phase)
        assertTrue(diagnostics.enabledProtocols.isEmpty())
        assertEquals(null, diagnostics.controllerSuiteSupported)
    }

    @Test
    fun connectionTesterReportsTlsDiagnosticsInTheStepDetail() = runBlocking {
        withPlainServer { plainEndpoint ->
            val report = ConnectionTester(FrameTransportFactory { TlsSocketTransport(it) })
                .run(plainEndpoint, FAKE_SERIAL)
            val tls = report.failure!!
            assertEquals(ConnectionTestStep.TLS, tls.step)
            assertEquals(ConnectionProblem.TLS_FAILED, tls.problem)
            assertTrue(tls.detail!!.contains("TLS provider: "), tls.detail)
            assertTrue(tls.detail!!.contains("Phase: TLS handshake"), tls.detail)

            val text = report.formatDiagnostics(listOf("Android 8.0 (SDK 26), Test Tablet"))
            assertTrue(text.startsWith("Android 8.0 (SDK 26), Test Tablet\nTLS: FAILED (TLS_FAILED)\n  TLS provider: "), text)
            assertTrue(text.contains("PING: SKIPPED"), text)
        }
    }

    @Test
    fun successfulConnectionTestStepDetailIsBrief() = runBlocking {
        startPongServer()
        val report = ConnectionTester(
            FrameTransportFactory { TlsSocketTransport(it, TlsSocketTransport.Options(pinnedSha1 = certificate.sha1)) },
            stepTimeout = 2.seconds,
        ).run(endpoint, FAKE_SERIAL)
        // The pong server answers the ping, but not the firmware request, so the test stops later – the TLS step passed.
        val tls = report.steps.first()
        assertEquals(StepStatus.PASSED, tls.status)
        assertTrue(tls.detail!!.contains("| provider "), tls.detail)
        assertTrue(tls.detail!!.contains("| SHA-1 ${certificate.sha1}"), tls.detail)
    }
}
