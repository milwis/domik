package app.domik.deimic.client

import app.domik.deimic.protocol.ProtocolException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.EOFException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.Security
import javax.net.ssl.SSLHandshakeException

class ConnectionErrorsTest {
    private fun classify(error: Throwable) = ConnectionErrors.classify(error)

    @Test
    fun classification() {
        assertEquals(ConnectionProblem.HOST_UNREACHABLE, classify(UnknownHostException("x")))
        assertEquals(ConnectionProblem.HOST_UNREACHABLE, classify(NoRouteToHostException("No route to host")))
        assertEquals(ConnectionProblem.HOST_UNREACHABLE, classify(ConnectException("Network is unreachable")))
        assertEquals(ConnectionProblem.CONNECTION_REFUSED, classify(ConnectException("Connection refused")))
        // Android wording.
        assertEquals(
            ConnectionProblem.CONNECTION_REFUSED,
            classify(ConnectException("failed to connect to /192.0.2.10 (port 60110): connect failed: ECONNREFUSED (Connection refused)")),
        )
        assertEquals(ConnectionProblem.TIMEOUT, classify(SocketTimeoutException("connect timed out")))
        assertEquals(ConnectionProblem.TLS_FAILED, classify(SSLHandshakeException("no cipher suites in common")))
        assertEquals(ConnectionProblem.DECODER_ERROR, classify(ProtocolException.FrameTooShort(16)))
        assertEquals(ConnectionProblem.CONNECTION_LOST, classify(EOFException()))
        assertEquals(ConnectionProblem.OTHER, classify(IllegalStateException()))
    }

    @Test
    fun pinMismatchInsideHandshakeExceptionIsCertificateMismatch() {
        val handshake = SSLHandshakeException("certificate rejected").apply {
            initCause(CertificatePinMismatchException("00".repeat(20)))
        }
        assertEquals(ConnectionProblem.CERTIFICATE_MISMATCH, classify(handshake))
    }

    @Test
    fun wrapKeepsConnectionExceptions() {
        val original = ConnectionException(ConnectionProblem.NO_RESPONSE, "x")
        assertSame(original, ConnectionErrors.wrap(original))
        val wrapped = ConnectionErrors.wrap(SocketTimeoutException("slow"))
        assertEquals(ConnectionProblem.TIMEOUT, wrapped.problem)
    }

    @Test
    fun endpointValidation() {
        assertEquals("192.0.2.10:60110", ControllerEndpoint("192.0.2.10").toString())
        assertEquals("[fe80::1]:1", ControllerEndpoint("fe80::1", 1).toString())
        assertThrows<IllegalArgumentException> { ControllerEndpoint(" ") }
        assertThrows<IllegalArgumentException> { ControllerEndpoint("h", 70000) }
        assertEquals(ControllerEndpoint("h", 60110), ControllerProfile("id", "Dom", "h").endpoint)
    }

    @Test
    fun jvmCompatibilityRemovesOnlyRsaKeyExchangeEntry() {
        val property = "jdk.tls.disabledAlgorithms"
        val original = Security.getProperty(property)
        try {
            Security.setProperty(property, "SSLv3, TLS_RSA_*, RC4")
            assertEquals(true, JvmTlsCompatibility.allowRsaKeyExchange())
            assertEquals("SSLv3, RC4", Security.getProperty(property))
            assertEquals(false, JvmTlsCompatibility.allowRsaKeyExchange())
        } finally {
            Security.setProperty(property, original)
        }
    }
}
