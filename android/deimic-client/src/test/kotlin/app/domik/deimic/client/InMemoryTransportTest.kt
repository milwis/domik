package app.domik.deimic.client

import app.domik.deimic.protocol.Frame
import app.domik.deimic.protocol.Request
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InMemoryTransportTest {
    @Test
    fun recordsSentFramesByDefault() = runTest {
        val seen = mutableListOf<Frame>()
        val transport = InMemoryTransport(onFrame = { seen += it })
        transport.connect()
        transport.write(Request.ping(FAKE_SERIAL).encode().toByteArray())
        assertEquals(listOf(Request.ping(FAKE_SERIAL)), transport.sentFrames)
        assertEquals(transport.sentFrames, seen)
    }

    @Test
    fun withoutRecordingSentFramesStayEmptyButThePeerStillSeesThem() = runTest {
        val seen = mutableListOf<Frame>()
        val transport = InMemoryTransport(recordSentFrames = false, onFrame = { seen += it })
        transport.connect()
        repeat(3) { transport.write(Request.ping(FAKE_SERIAL).encode().toByteArray()) }
        assertTrue(transport.sentFrames.isEmpty())
        assertEquals(3, seen.size)
    }
}
