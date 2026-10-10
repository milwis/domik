package app.domik.deimic.client

/**
 * Which TLS cipher suites to enable for the controller's endpoint. Pure list arithmetic, so it can be tested
 * without a TLS stack.
 */
object CipherSuitePolicy {
    /**
     * The suite the 1st-generation controller negotiates (verified on a live controller): RSA key exchange,
     * AES-256-GCM. No forward secrecy, but the endpoint is a local self-signed device that is authenticated by
     * certificate pinning, not by the cipher suite.
     */
    const val CONTROLLER_SUITE: String = "TLS_RSA_WITH_AES_256_GCM_SHA384"

    val CONTROLLER_SUITES: List<String> = listOf(CONTROLLER_SUITE)

    /**
     * [enabled] plus every suite of [required] that the platform [supported] but did not enable. Keeps the order of
     * [enabled] (the platform's preference) and appends the missing ones; never removes or reorders anything, and
     * never adds a suite the platform does not support (which would make `setEnabledCipherSuites` throw).
     */
    fun withRequired(enabled: Array<String>, supported: Array<String>, required: List<String>): Array<String> {
        val missing = required.filter { it in supported && it !in enabled }.distinct()
        return if (missing.isEmpty()) enabled else enabled + missing
    }
}
