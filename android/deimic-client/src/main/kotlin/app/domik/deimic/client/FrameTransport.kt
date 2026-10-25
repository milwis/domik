package app.domik.deimic.client

/**
 * Byte-stream connection to a controller (TLS socket in production, in-memory peer in tests).
 *
 * A transport is used for a single connection: [connect] once, then [read] / [write], then [close].
 * [close] may be called from any thread at any time and must make a pending [connect] / [read] fail promptly.
 */
interface FrameTransport {
    /** Details of the established connection (TLS version etc.); null before [connect] or when not applicable. */
    val info: TransportInfo?

    /** Opens the connection. Throws [ConnectionException] (with a classified problem) on failure. */
    suspend fun connect()

    /** Next chunk of received bytes (never empty), or null when the peer closed the connection. */
    suspend fun read(): ByteArray?

    suspend fun write(bytes: ByteArray)

    fun close()
}

/** Creates a fresh transport for each connection attempt. */
fun interface FrameTransportFactory {
    fun create(endpoint: ControllerEndpoint): FrameTransport
}

/** Details of an established connection, for diagnostics. */
data class TransportInfo(
    /** e.g. "TLSv1.2". */
    val protocol: String,
    /** e.g. "TLS_RSA_WITH_AES_256_GCM_SHA384". */
    val cipherSuite: String,
    /** SHA-1 of the server certificate (lowercase hex) – equal to the pinned fingerprint once connected. */
    val certificateSha1: String,
    /** Provider, protocols etc. of the TLS stack that made the connection; null for non-TLS transports. */
    val diagnostics: TlsDiagnostics? = null,
)
