package app.domik.deimic.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.security.cert.CertificateException
import java.security.cert.X509Certificate

class CertificatePinningTest {
    private val certificate = SelfSignedCertificate.generate()

    @Test
    fun sha1OfKnownBytes() {
        // FIPS 180 test vector: SHA-1("abc").
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", CertificatePinning.sha1Hex("abc".toByteArray()))
    }

    @Test
    fun normalizeAcceptsColonSeparatedUppercase() {
        val colon = CertificatePinning.CONTROLLER_CERTIFICATE_SHA1.chunked(2).joinToString(":").uppercase()
        assertEquals(CertificatePinning.CONTROLLER_CERTIFICATE_SHA1, CertificatePinning.normalize(colon))
        assertThrows<IllegalArgumentException> { CertificatePinning.normalize("abc") }
        assertThrows<IllegalArgumentException> { CertificatePinning.normalize("z".repeat(40)) }
    }

    @Test
    fun matchesComparesDerFingerprint() {
        assertTrue(CertificatePinning.matches("abc".toByteArray(), "A9:99:3E:36:47:06:81:6A:BA:3E:25:71:78:50:C2:6C:9C:D0:D8:9D"))
        assertFalse(CertificatePinning.matches("abd".toByteArray(), "a9993e364706816aba3e25717850c26c9cd0d89d"))
    }

    @Test
    fun trustManagerAcceptsPinnedCertificate() {
        val manager = PinnedCertificateTrustManager(certificate.sha1)
        assertDoesNotThrow { manager.checkServerTrusted(arrayOf(certificate.certificate), "RSA") }
        assertEquals(certificate.sha1, manager.lastPresentedSha1)
    }

    @Test
    fun trustManagerRejectsOtherCertificate() {
        val manager = PinnedCertificateTrustManager() // controller fingerprint
        val error = assertThrows<CertificatePinMismatchException> {
            manager.checkServerTrusted(arrayOf(certificate.certificate), "RSA")
        }
        assertEquals(certificate.sha1, error.actualSha1)
    }

    @Test
    fun onlyTheLeafCertificateCounts() {
        val other = SelfSignedCertificate.generate("other")
        val manager = PinnedCertificateTrustManager(certificate.sha1)
        assertThrows<CertificatePinMismatchException> {
            manager.checkServerTrusted(arrayOf(other.certificate, certificate.certificate), "RSA")
        }
    }

    @Test
    fun trustManagerRejectsEmptyChainAndClients() {
        val manager = PinnedCertificateTrustManager(certificate.sha1)
        assertThrows<CertificateException> { manager.checkServerTrusted(arrayOf<X509Certificate>(), "RSA") }
        assertThrows<CertificateException> { manager.checkServerTrusted(null, "RSA") }
        assertThrows<CertificateException> { manager.checkClientTrusted(arrayOf(certificate.certificate), "RSA") }
        assertEquals(0, manager.acceptedIssuers.size)
    }
}
