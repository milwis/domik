package app.domik.deimic.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.net.ssl.SSLHandshakeException

class TlsDiagnosticsTest {
    private val pin = CertificatePinning.CONTROLLER_CERTIFICATE_SHA1

    @Test
    fun errorChainIsOutermostFirstAndLimited() {
        var error: Throwable = IllegalStateException("root")
        repeat(8) { level -> error = RuntimeException("level $level", error) }

        val chain = TlsDiagnostics.errorChain(error)
        assertEquals(5, chain.size)
        assertEquals("java.lang.RuntimeException: level 7", chain.first())
    }

    @Test
    fun errorChainShortensLongMessagesAndHandlesEmptyOnes() {
        val chain = TlsDiagnostics.errorChain(RuntimeException("x".repeat(1000), IllegalStateException()))
        assertTrue(chain[0].length < 300, chain[0].length.toString())
        assertTrue(chain[0].endsWith("…"))
        assertEquals("java.lang.IllegalStateException", chain[1])
    }

    @Test
    fun formatListsEverythingNeededToDiagnoseAHandshakeFailure() {
        val cause = SSLHandshakeException("Handshake failed")
        val text = TlsDiagnostics(
            provider = "AndroidOpenSSL 1.0",
            providerNote = "bundled provider unavailable: UnsatisfiedLinkError",
            phase = TlsPhase.TLS_HANDSHAKE,
            errorChain = TlsDiagnostics.errorChain(RuntimeException("outer", cause)),
            presentedSha1 = null,
            expectedSha1 = pin,
            enabledProtocols = listOf("TLSv1.2"),
            supportedProtocols = listOf("TLSv1", "TLSv1.1", "TLSv1.2"),
            controllerSuiteSupported = true,
            controllerSuiteEnabled = false,
        ).format()

        assertEquals(
            """
            TLS provider: AndroidOpenSSL 1.0 (bundled provider unavailable: UnsatisfiedLinkError)
            Phase: TLS handshake
            Error: java.lang.RuntimeException: outer
              caused by: javax.net.ssl.SSLHandshakeException: Handshake failed
            Certificate SHA-1: none presented
            Expected SHA-1:    $pin
            Protocols: enabled [TLSv1.2], supported [TLSv1, TLSv1.1, TLSv1.2]
            Cipher suite TLS_RSA_WITH_AES_256_GCM_SHA384: supported yes, enabled no
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun formatMarksMatchingAndDifferingFingerprints() {
        val base = TlsDiagnostics(provider = "p", phase = TlsPhase.ESTABLISHED, expectedSha1 = pin)
        assertTrue(base.copy(presentedSha1 = pin).format().contains("(matches the pin)"))
        assertTrue(base.copy(presentedSha1 = "0".repeat(40)).format().contains("(DIFFERS from the pin)"))
    }

    @Test
    fun briefSummaryOfASuccessfulConnection() {
        val diagnostics = TlsDiagnostics(
            provider = "Conscrypt 2.8",
            providerNote = "bundled",
            phase = TlsPhase.ESTABLISHED,
            presentedSha1 = pin,
            expectedSha1 = pin,
            negotiatedProtocol = "TLSv1.2",
            negotiatedCipherSuite = "TLS_RSA_WITH_AES_256_GCM_SHA384",
        )
        assertEquals(
            "TLSv1.2 TLS_RSA_WITH_AES_256_GCM_SHA384 | provider Conscrypt 2.8 (bundled) | SHA-1 $pin",
            diagnostics.formatBrief(),
        )
        assertFalse(diagnostics.format().contains("Error:"))
        assertTrue(diagnostics.format().contains("Negotiated: TLSv1.2 TLS_RSA_WITH_AES_256_GCM_SHA384"))
    }
}
