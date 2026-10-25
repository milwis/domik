package app.domik.deimic.client

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Lifecycle of a [Connection]. */
sealed interface ConnectionState {
    /** Not connected and not trying ([Connection.connect] not called, or [Connection.disconnect] called). */
    data object Idle : ConnectionState

    /** Opening TCP + TLS. [attempt] = 0 for the first connection, n for the n-th reconnect attempt. */
    data class Connecting(val attempt: Int) : ConnectionState

    /** Connected: requests can be sent, incoming frames are delivered. */
    data object Ready : ConnectionState

    /** Connection lost or attempt failed because of [problem]; the next attempt ([attempt]) starts after [delay]. */
    data class WaitingToReconnect(
        val attempt: Int,
        val delay: Duration,
        val problem: ConnectionProblem,
    ) : ConnectionState

    /** Gave up (attempts exhausted or a permanent problem such as a certificate mismatch). */
    data class Failed(
        val problem: ConnectionProblem,
        val detail: String? = null,
        /** TLS facts of the failed attempt, if it failed while connecting. */
        val diagnostics: TlsDiagnostics? = null,
    ) : ConnectionState
}

/** Timing and retry policy of a [Connection]. Defaults follow the controller protocol. */
data class ConnectionConfig(
    /** Ping (0x1CE, address 0) interval while connected. */
    val keepAliveInterval: Duration = 5.seconds,
    /** Reconnect when no bytes at all arrive for this long. */
    val livenessTimeout: Duration = 15.seconds,
    /** Upper bound for opening TCP + TLS (the transport closes earlier on its own timeouts). */
    val connectTimeout: Duration = 15.seconds,
    /** Default time to wait for a response in [Connection.request]. */
    val requestTimeout: Duration = 5.seconds,
    /** After a response, how long to keep waiting for the acknowledgement (0x1E0) if it has not arrived yet. */
    val ackGracePeriod: Duration = 500.milliseconds,
    val reconnectBaseDelay: Duration = 2.seconds,
    val reconnectMaxDelay: Duration = 60.seconds,
    /** Reconnect attempts after a failure before giving up ([ConnectionState.Failed]); 0 = never retry. */
    val maxReconnectAttempts: Int = 20,
) {
    init {
        require(keepAliveInterval.isPositive() && livenessTimeout.isPositive() && connectTimeout.isPositive())
        require(maxReconnectAttempts >= 0)
    }

    /** Delay before reconnect attempt [attempt] (1-based): `min(2^(attempt-1) × base, max)` → 2, 4, 8 … 60 s. */
    fun reconnectDelay(attempt: Int): Duration {
        require(attempt >= 1)
        val exponent = (attempt - 1).coerceAtMost(30)
        val delay = reconnectBaseDelay * (1L shl exponent).toDouble()
        return if (delay > reconnectMaxDelay) reconnectMaxDelay else delay
    }
}
