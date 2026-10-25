package app.domik.deimic.client

/** How far a TLS connection attempt got. */
enum class TlsPhase(val label: String) {
    /** Opening the TCP connection (nothing TLS-related has happened yet). */
    TCP_CONNECT("TCP connect"),

    /** TCP is open; the TLS handshake (protocol / cipher negotiation) did not complete. */
    TLS_HANDSHAKE("TLS handshake"),

    /** The server presented a certificate that is not the pinned one. */
    CERTIFICATE_CHECK("certificate check"),

    /** The handshake completed and the certificate matched the pin. */
    ESTABLISHED("established"),
}

/**
 * Technical facts about one TLS connection attempt, for a bug report: which TLS implementation was used, how far
 * the attempt got, why it failed, what certificate the peer presented and which protocols / cipher suite the
 * platform offers. Formatting is in [format] / [formatBrief] only. Contains nothing secret (never the serial
 * number) – only platform and certificate fingerprint data – so a user can paste it to a forum.
 */
data class TlsDiagnostics(
    /** TLS provider that created the `SSLContext`, e.g. "Conscrypt 2.8" or "AndroidOpenSSL 1.0". */
    val provider: String,
    /** Extra remark about the provider choice, e.g. why the bundled one was not used. */
    val providerNote: String? = null,
    val phase: TlsPhase,
    /** The exception and its causes, outermost first ("class: message"); empty on success. */
    val errorChain: List<String> = emptyList(),
    /** SHA-1 of the certificate the server presented, if it got that far. */
    val presentedSha1: String? = null,
    val expectedSha1: String,
    val enabledProtocols: List<String> = emptyList(),
    val supportedProtocols: List<String> = emptyList(),
    /** Whether [CipherSuitePolicy.CONTROLLER_SUITE] is supported / enabled on the socket; null before the socket exists. */
    val controllerSuiteSupported: Boolean? = null,
    val controllerSuiteEnabled: Boolean? = null,
    /** Negotiated protocol and cipher suite (success only). */
    val negotiatedProtocol: String? = null,
    val negotiatedCipherSuite: String? = null,
) {
    /** Multi-line report. */
    fun format(): String = buildString {
        append("TLS provider: ").append(provider)
        providerNote?.let { append(" (").append(it).append(')') }
        append("\nPhase: ").append(phase.label)
        errorChain.forEachIndexed { index, line ->
            append(if (index == 0) "\nError: " else "\n  caused by: ").append(line)
        }
        append("\nCertificate SHA-1: ")
        when {
            presentedSha1 == null -> append("none presented")
            else -> append(presentedSha1).append(if (presentedSha1 == expectedSha1) " (matches the pin)" else " (DIFFERS from the pin)")
        }
        append("\nExpected SHA-1:    ").append(expectedSha1)
        if (enabledProtocols.isNotEmpty() || supportedProtocols.isNotEmpty()) {
            append("\nProtocols: enabled ").append(enabledProtocols).append(", supported ").append(supportedProtocols)
        }
        if (controllerSuiteSupported != null) {
            append("\nCipher suite ").append(CipherSuitePolicy.CONTROLLER_SUITE)
                .append(": supported ").append(yesNo(controllerSuiteSupported))
                .append(", enabled ").append(yesNo(controllerSuiteEnabled == true))
        }
        if (negotiatedProtocol != null) {
            append("\nNegotiated: ").append(negotiatedProtocol).append(' ').append(negotiatedCipherSuite.orEmpty())
        }
    }

    /** One-line summary for a successful connection. */
    fun formatBrief(): String = buildString {
        append(negotiatedProtocol.orEmpty()).append(' ').append(negotiatedCipherSuite.orEmpty())
        append(" | provider ").append(provider)
        providerNote?.let { append(" (").append(it).append(')') }
        presentedSha1?.let { append(" | SHA-1 ").append(it) }
    }.trim()

    companion object {
        private const val MAX_CHAIN = 5
        private const val MAX_MESSAGE = 240

        /** "class: message" for [error] and up to four causes (outermost first); long messages are shortened. */
        fun errorChain(error: Throwable): List<String> =
            generateSequence(error) { current -> current.cause?.takeIf { it !== current } }
                .take(MAX_CHAIN)
                .map { describe(it) }
                .toList()

        private fun describe(error: Throwable): String {
            val message = error.message?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
            val shortened = if (message.length > MAX_MESSAGE) message.take(MAX_MESSAGE) + "…" else message
            return if (shortened.isEmpty()) error::class.java.name else "${error::class.java.name}: $shortened"
        }

        private fun yesNo(value: Boolean?) = if (value == true) "yes" else "no"
    }
}
