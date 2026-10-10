package app.domik.deimic.client

import app.domik.deimic.protocol.Frame
import app.domik.deimic.protocol.FrameDecoder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Transport with a scriptable in-process peer – for tests, demo mode and previews.
 *
 * Frames written by the client are decoded and passed to [onFrame]; the peer answers with [deliver]. Bytes can
 * be delivered in arbitrary chunks (split or merged frames). [onConnect] may suspend or throw
 * [ConnectionException] to simulate connection failures.
 *
 * [onFrame] runs synchronously inside [write]; it must not block (hand slow work over to a coroutine).
 * Every frame written by the client is kept in [sentFrames] unless [recordSentFrames] is false – long-lived users
 * such as demo mode switch it off, otherwise the keepalive pings would pile up for as long as the connection lives.
 */
class InMemoryTransport(
    private val onConnect: suspend () -> Unit = {},
    override val info: TransportInfo? = null,
    private val recordSentFrames: Boolean = true,
    private val onFrame: InMemoryTransport.(Frame) -> Unit = {},
) : FrameTransport {
    private val lock = Any()
    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    private val peerDecoder = FrameDecoder()
    private val written = mutableListOf<Frame>()
    private val closedSignal = CompletableDeferred<Unit>()

    @Volatile
    var isConnected: Boolean = false
        private set

    @Volatile
    var isClosed: Boolean = false
        private set

    /** Frames written by the client so far (always empty when [recordSentFrames] is false). */
    val sentFrames: List<Frame> get() = synchronized(lock) { written.toList() }

    override suspend fun connect() {
        if (isClosed) throw ConnectionException(ConnectionProblem.OTHER, "transport closed")
        // Like a socket: close() aborts a pending connect.
        coroutineScope {
            val abort = launch {
                closedSignal.await()
                throw ConnectionException(ConnectionProblem.OTHER, "transport closed while connecting")
            }
            try {
                onConnect()
            } finally {
                abort.cancel()
            }
        }
        if (isClosed) throw ConnectionException(ConnectionProblem.OTHER, "transport closed")
        isConnected = true
    }

    override suspend fun read(): ByteArray? {
        val result = incoming.receiveCatching()
        result.getOrNull()?.let { return it }
        val cause = result.exceptionOrNull() ?: return null // closed by the peer = end of stream
        throw cause
    }

    override suspend fun write(bytes: ByteArray) {
        if (isClosed || !isConnected) {
            throw ConnectionException(ConnectionProblem.CONNECTION_LOST, "transport not open")
        }
        val frames = synchronized(lock) {
            peerDecoder.append(bytes).also { if (recordSentFrames) written += it }
        }
        frames.forEach { onFrame(it) }
    }

    override fun close() {
        isClosed = true
        closedSignal.complete(Unit)
        incoming.close(ConnectionException(ConnectionProblem.CONNECTION_LOST, "transport closed"))
    }

    /** Peer → client: raw bytes (may contain partial or several frames). Empty arrays are ignored. */
    fun deliver(bytes: ByteArray) {
        if (bytes.isNotEmpty()) incoming.trySend(bytes)
    }

    /** Peer → client: the given frames in one chunk. */
    fun deliver(vararg frames: Frame) {
        deliver(frames.fold(ByteArray(0)) { acc, frame -> acc + frame.encode().toByteArray() })
    }

    /** The peer closes the connection (client sees end of stream). */
    fun closeFromPeer() {
        incoming.close()
    }
}
