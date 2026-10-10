package app.domik.deimic.client

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * Minimal self-signed X.509 certificate built with a small DER encoder and JDK crypto only
 * (no keytool, no internal JDK classes, no third-party libraries) – for local TLS tests.
 */
class SelfSignedCertificate private constructor(val keyPair: KeyPair, val certificate: X509Certificate) {
    val sha1: String get() = CertificatePinning.sha1Hex(certificate.encoded)

    /** Server-side TLS context presenting this certificate. */
    fun serverContext(): SSLContext {
        val password = CharArray(0)
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("server", keyPair.private, password, arrayOf(certificate))
        }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore, password) }.keyManagers
        return SSLContext.getInstance("TLS").apply { init(keyManagers, null, null) }
    }

    companion object {
        fun generate(commonName: String = "test"): SelfSignedCertificate {
            val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val algorithm = sequence(oid(1, 2, 840, 113549, 1, 1, 11), byteArrayOf(0x05, 0x00)) // sha256WithRSA
            val name = sequence(set(sequence(oid(2, 5, 4, 3), tlv(0x0C, commonName.toByteArray()))))
            val tbs = sequence(
                tlv(0xA0, integer(BigInteger.valueOf(2))), // v3
                integer(BigInteger.valueOf(System.nanoTime() and 0x7FFFFFFF)),
                algorithm,
                name,
                sequence(utcTime("250101000000Z"), utcTime("491231235959Z")),
                name,
                keyPair.public.encoded, // SubjectPublicKeyInfo, already DER
            )
            val signature = Signature.getInstance("SHA256withRSA").run {
                initSign(keyPair.private)
                update(tbs)
                sign()
            }
            val der = sequence(tbs, algorithm, tlv(0x03, byteArrayOf(0) + signature))
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(der.inputStream()) as X509Certificate
            return SelfSignedCertificate(keyPair, certificate)
        }

        private fun tlv(tag: Int, value: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(tag)
            val length = value.size
            when {
                length < 0x80 -> out.write(length)
                length < 0x100 -> { out.write(0x81); out.write(length) }
                else -> { out.write(0x82); out.write(length shr 8); out.write(length and 0xFF) }
            }
            out.write(value)
            return out.toByteArray()
        }

        private fun sequence(vararg items: ByteArray) = tlv(0x30, items.fold(ByteArray(0)) { acc, it -> acc + it })

        private fun set(vararg items: ByteArray) = tlv(0x31, items.fold(ByteArray(0)) { acc, it -> acc + it })

        private fun integer(value: BigInteger) = tlv(0x02, value.toByteArray())

        private fun utcTime(text: String) = tlv(0x17, text.toByteArray())

        private fun oid(vararg arcs: Int): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(arcs[0] * 40 + arcs[1])
            for (arc in arcs.drop(2)) {
                val groups = mutableListOf<Int>()
                var rest = arc
                do {
                    groups += rest and 0x7F
                    rest = rest ushr 7
                } while (rest != 0)
                groups.reversed().forEachIndexed { index, group ->
                    out.write(if (index < groups.size - 1) group or 0x80 else group)
                }
            }
            return tlv(0x06, out.toByteArray())
        }
    }
}
