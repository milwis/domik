package app.domik.deimic.client

import java.security.Security

/**
 * Compatibility switch for desktop / server JVMs (not needed on Android).
 *
 * The controller negotiates a TLS 1.2 cipher suite with RSA key exchange (`TLS_RSA_WITH_AES_256_GCM_SHA384`).
 * Recent JDKs list `TLS_RSA_*` in the `jdk.tls.disabledAlgorithms` security property, so a plain JVM
 * client cannot complete the handshake. The connection stays authenticated by certificate pinning; what is
 * lost compared with modern suites is forward secrecy, which the controller does not offer anyway.
 */
object JvmTlsCompatibility {
    private const val DISABLED_ALGORITHMS = "jdk.tls.disabledAlgorithms"
    private const val RSA_KEY_EXCHANGE = "TLS_RSA_*"

    /**
     * Removes `TLS_RSA_*` from the process-wide disabled TLS algorithms. Must be called before the first TLS
     * connection of the process (the JDK reads the property once). Returns true if the property was changed.
     */
    fun allowRsaKeyExchange(): Boolean {
        val current = Security.getProperty(DISABLED_ALGORITHMS) ?: return false
        val entries = current.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val kept = entries.filterNot { it.equals(RSA_KEY_EXCHANGE, ignoreCase = true) }
        if (kept.size == entries.size) return false
        Security.setProperty(DISABLED_ALGORITHMS, kept.joinToString(", "))
        return true
    }
}
