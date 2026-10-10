package app.domik.deimic.client

import java.net.Socket
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager

/**
 * Certificate pinning for the controller's TLS endpoint.
 *
 * 1st-generation controllers present a self-signed certificate and are addressed by IP, so a CA chain or host
 * name cannot be verified; the only meaningful check is that the leaf certificate is exactly the expected one
 * (SHA-1 of its DER encoding). The pinned value is the certificate observed on 1st-generation controllers; it has
 * not been compared across other units, so a different certificate shows up as a connection error.
 */
object CertificatePinning {
    /** SHA-1 fingerprint of the certificate a controller presents on the TLS connection (1st-generation controllers use the same certificate). */
    const val CONTROLLER_CERTIFICATE_SHA1: String = "dfe8829178c687ba6286c42154cb181533f5453a"

    /** SHA-1 of [der] as 40 lowercase hex digits. */
    fun sha1Hex(der: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(der).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /** Accepts "DF:E8:…", "dfe8 …" etc.; returns 40 lowercase hex digits. */
    fun normalize(fingerprint: String): String {
        val hex = fingerprint.filter { it.isLetterOrDigit() }.lowercase()
        require(hex.length == 40 && hex.all { it in '0'..'9' || it in 'a'..'f' }) { "not a SHA-1 fingerprint" }
        return hex
    }

    /** True when the SHA-1 of [der] equals [pinnedSha1] (constant-time comparison). */
    fun matches(der: ByteArray, pinnedSha1: String): Boolean =
        MessageDigest.isEqual(sha1Hex(der).toByteArray(), normalize(pinnedSha1).toByteArray())
}

/** The server certificate is not the pinned one. [actualSha1] is the fingerprint that was presented. */
class CertificatePinMismatchException(val actualSha1: String) :
    CertificateException("server certificate does not match the pinned fingerprint (got $actualSha1)")

/**
 * Trust manager that trusts a server only if its leaf certificate has the pinned SHA-1 fingerprint, and
 * otherwise throws [CertificatePinMismatchException]. It never "trusts everything".
 *
 * Chain building, validity dates and host name are deliberately not checked (self-signed certificate,
 * IP address). It extends [X509ExtendedTrustManager] so the JDK does not wrap it with extra checks
 * (endpoint identification, certificate algorithm constraints) that do not apply to a pinned certificate.
 */
class PinnedCertificateTrustManager(pinnedSha1: String = CertificatePinning.CONTROLLER_CERTIFICATE_SHA1) :
    X509ExtendedTrustManager() {
    private val pinned = CertificatePinning.normalize(pinnedSha1)

    /** Fingerprint of the last certificate presented by a server (whether it matched or not). */
    @Volatile
    var lastPresentedSha1: String? = null
        private set

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = verify(chain)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) =
        verify(chain)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) =
        verify(chain)

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = rejectClient()

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) =
        rejectClient()

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) =
        rejectClient()

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private fun verify(chain: Array<out X509Certificate>?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("server sent no certificate")
        val actual = CertificatePinning.sha1Hex(leaf.encoded)
        lastPresentedSha1 = actual
        if (!MessageDigest.isEqual(actual.toByteArray(), pinned.toByteArray())) {
            throw CertificatePinMismatchException(actual)
        }
    }

    private fun rejectClient(): Nothing = throw CertificateException("client certificates are not accepted")
}
