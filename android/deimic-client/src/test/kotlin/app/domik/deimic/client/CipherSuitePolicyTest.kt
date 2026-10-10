package app.domik.deimic.client

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class CipherSuitePolicyTest {
    private val suite = CipherSuitePolicy.CONTROLLER_SUITE
    private val required = CipherSuitePolicy.CONTROLLER_SUITES

    @Test
    fun anAlreadyEnabledSuiteChangesNothing() {
        val enabled = arrayOf("TLS_AES_128_GCM_SHA256", suite)
        assertSame(enabled, CipherSuitePolicy.withRequired(enabled, enabled + "OTHER", required))
    }

    @Test
    fun aSupportedButDisabledSuiteIsAppendedAndTheOrderOfTheRestIsKept() {
        val enabled = arrayOf("TLS_AES_128_GCM_SHA256", "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256")
        val supported = enabled + suite + "TLS_RSA_WITH_AES_128_CBC_SHA"
        assertArrayEquals(
            arrayOf("TLS_AES_128_GCM_SHA256", "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256", suite),
            CipherSuitePolicy.withRequired(enabled, supported, required),
        )
    }

    @Test
    fun aSuiteTheTlsStackDoesNotSupportIsNeverAdded() {
        val enabled = arrayOf("TLS_AES_128_GCM_SHA256")
        assertSame(enabled, CipherSuitePolicy.withRequired(enabled, enabled, required))
    }

    @Test
    fun duplicatesInTheRequestAreAddedOnce() {
        val result = CipherSuitePolicy.withRequired(emptyArray(), arrayOf(suite), listOf(suite, suite))
        assertArrayEquals(arrayOf(suite), result)
    }
}
