package app.domik.deimic.client

import app.domik.deimic.protocol.CommandCode
import app.domik.deimic.protocol.ProtocolException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Classification of connection failures, meant to be mapped to user-facing messages by the UI. */
enum class ConnectionProblem {
    /** Unknown host name, no route to the address or network unreachable (wrong IP, different Wi-Fi). */
    HOST_UNREACHABLE,

    /** The address answers, but nothing accepts connections on the port. */
    CONNECTION_REFUSED,

    /** Connecting or the TLS handshake took too long. */
    TIMEOUT,

    /** TLS handshake failed for a reason other than the certificate pin (protocol / cipher mismatch, …). */
    TLS_FAILED,

    /** The device presented a different certificate – probably not a 1st-generation controller. */
    CERTIFICATE_MISMATCH,

    /**
     * Connected, but the controller does not answer (no frames, or no response to a request). Probably a wrong
     * serial number – how the controller reacts to a wrong serial number has not been verified.
     */
    NO_RESPONSE,

    /**
     * The byte stream could not be split into frames (broken length header, sync lost). The connection is dropped
     * and re-established with backoff; this is not permanent. A frame with a bad payload length only is not
     * this problem (see [Connection]).
     */
    DECODER_ERROR,

    /** An established connection was closed by the controller or broke. */
    CONNECTION_LOST,

    OTHER,
}

/**
 * Connection-level failure with its [problem] classification. Messages never contain the serial number.
 * [diagnostics] is set when the failure happened while opening a TLS connection.
 */
open class ConnectionException(
    val problem: ConnectionProblem,
    message: String,
    cause: Throwable? = null,
    val diagnostics: TlsDiagnostics? = null,
) : IOException(message, cause)

/** No response with one of the expected commands arrived within the request timeout. */
class RequestTimeoutException(val command: CommandCode, val acknowledged: Boolean) :
    ConnectionException(
        ConnectionProblem.NO_RESPONSE,
        "no response to $command" + if (acknowledged) " (request was acknowledged)" else " (no acknowledgement)",
    )

/** [Connection.send] / [Connection.request] called while the connection is not ready. */
class NotConnectedException(state: ConnectionState) : IOException("not connected (state: $state)")

/** Maps low-level exceptions to a [ConnectionProblem]. */
object ConnectionErrors {
    fun classify(error: Throwable): ConnectionProblem {
        if (error is ConnectionException) return error.problem
        if (error.causes().any { it is CertificatePinMismatchException }) return ConnectionProblem.CERTIFICATE_MISMATCH
        return when (error) {
            is UnknownHostException, is NoRouteToHostException, is PortUnreachableException ->
                ConnectionProblem.HOST_UNREACHABLE
            is ConnectException ->
                if (error.message.orEmpty().contains("refused", ignoreCase = true)) {
                    ConnectionProblem.CONNECTION_REFUSED
                } else {
                    ConnectionProblem.HOST_UNREACHABLE
                }
            // SocketTimeoutException is an InterruptedIOException.
            is InterruptedIOException -> ConnectionProblem.TIMEOUT
            is SSLException -> ConnectionProblem.TLS_FAILED
            is ProtocolException -> ConnectionProblem.DECODER_ERROR
            is IOException -> ConnectionProblem.CONNECTION_LOST
            else -> ConnectionProblem.OTHER
        }
    }

    /** Wraps [error] into a [ConnectionException] (returned unchanged if it already is one). */
    fun wrap(error: Throwable): ConnectionException =
        error as? ConnectionException
            ?: ConnectionException(classify(error), error.message ?: error::class.java.simpleName, error)

    private fun Throwable.causes(): Sequence<Throwable> =
        generateSequence(this) { current -> current.cause?.takeIf { it !== current } }.take(16)
}
