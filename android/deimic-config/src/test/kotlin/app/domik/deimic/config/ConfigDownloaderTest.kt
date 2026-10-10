@file:OptIn(ExperimentalCoroutinesApi::class)

package app.domik.deimic.config

import app.domik.deimic.client.Connection
import app.domik.deimic.client.ConnectionState
import app.domik.deimic.client.ControllerEndpoint
import app.domik.deimic.client.FrameTransportFactory
import app.domik.deimic.client.InMemoryTransport
import app.domik.deimic.client.NotConnectedException
import app.domik.deimic.client.RequestTimeoutException
import app.domik.deimic.protocol.CommandCode
import app.domik.deimic.protocol.Frame
import app.domik.deimic.protocol.FrameAddress
import app.domik.deimic.protocol.Request
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.seconds

class ConfigDownloaderTest {
    private val fixture = fixtureFile("config_synthetic.db3").readBytes()
    private val controller = FakeDatabaseController(fixture)

    /** Connection to the fake controller, already in the ready state. */
    private fun TestScope.readyConnection(): Connection {
        val transports = mutableListOf<InMemoryTransport>()
        val factory = FrameTransportFactory { _ ->
            InMemoryTransport(onFrame = { frame: Frame -> controller.handle(this, frame) }).also { transports += it }
        }
        val connection = Connection(ControllerEndpoint("192.0.2.10"), FAKE_SERIAL, backgroundScope, factory)
        connection.connect()
        runCurrent()
        assertEquals(ConnectionState.Ready, connection.state.value)
        return connection
    }

    @Test
    fun downloadsTheDatabaseWithTheFixedPath() = runTest {
        val connection = readyConnection()
        val downloaded = ConfigDownloader(connection).download()

        assertArrayEquals(fixture, downloaded.bytes)
        assertEquals("/logs/database-last.db3", downloaded.fileName)
        // The one and only command sent is the file download request for the constant path.
        assertEquals(listOf(CommandCode.FILE_DOWNLOAD), controller.received)
        val sent = controller.frames.single()
        assertEquals(Request.fileDownload(FAKE_SERIAL, "/logs/database-last.db3"), sent)
        assertEquals(FrameAddress.CONTROLLER, sent.address)
        assertEquals(64, sent.payload.size)
    }

    @Test
    fun largeFileInSmallChunks() = runTest {
        controller.database = fixtureFile("config_synthetic_4096.db3").readBytes()
        controller.chunkSize = 1500
        val downloaded = ConfigDownloader(readyConnection()).download()
        assertArrayEquals(controller.database, downloaded.bytes)
    }

    @Test
    fun noAnswerTimesOutAfterThirtySeconds() = runTest {
        controller.silent = setOf(CommandCode.FILE_DOWNLOAD)
        val connection = readyConnection()
        val start = testScheduler.currentTime
        val result = async { runCatching { ConfigDownloader(connection).download() } }
        testScheduler.advanceUntilIdle()
        val error = result.await().exceptionOrNull()
        assertTrue(error is RequestTimeoutException, "$error")
        assertEquals(30_000L, testScheduler.currentTime - start)
        assertEquals(ConnectionState.Ready, connection.state.value)
    }

    @Test
    fun timeoutCanBeShortened() = runTest {
        controller.silent = setOf(CommandCode.FILE_DOWNLOAD)
        val connection = readyConnection()
        val start = testScheduler.currentTime
        assertThrows<RequestTimeoutException> { ConfigDownloader(connection, timeout = 2.seconds).download() }
        assertEquals(2_000L, testScheduler.currentTime - start)
    }

    @Test
    fun notConnected() = runTest {
        val connection = Connection(ControllerEndpoint("192.0.2.10"), FAKE_SERIAL, backgroundScope, FrameTransportFactory { InMemoryTransport() })
        assertThrows<NotConnectedException> { ConfigDownloader(connection).download() }
    }

    @Test
    fun otherFileNameIsRejected() = runTest {
        controller.echoedName = "/logs/other.db3"
        val error = assertThrows<ConfigDownloadException> { ConfigDownloader(readyConnection()).download() }
        assertEquals(ConfigDownloadException.Reason.UNEXPECTED_FILE_NAME, error.reason)
    }

    @Test
    fun emptyNameIsRejected() = runTest {
        controller.echoedName = ""
        val error = assertThrows<ConfigDownloadException> { ConfigDownloader(readyConnection()).download() }
        assertEquals(ConfigDownloadException.Reason.UNEXPECTED_FILE_NAME, error.reason)
    }

    @Test
    fun emptyFileIsRejected() = runTest {
        controller.database = ByteArray(0)
        val error = assertThrows<ConfigDownloadException> { ConfigDownloader(readyConnection()).download() }
        assertEquals(ConfigDownloadException.Reason.EMPTY_FILE, error.reason)
    }

    @Test
    fun somethingThatIsNotSqliteIsRejected() = runTest {
        controller.database = "<html>not a database</html>".toByteArray()
        val error = assertThrows<ConfigDownloadException> { ConfigDownloader(readyConnection()).download() }
        assertEquals(ConfigDownloadException.Reason.NOT_A_DATABASE, error.reason)
        assertTrue(error.message!!.contains("not a SQLite"))
    }

    @Test
    fun truncatedMagicIsNotADatabase() = runTest {
        controller.database = fixture.copyOf(10)
        val error = assertThrows<ConfigDownloadException> { ConfigDownloader(readyConnection()).download() }
        assertEquals(ConfigDownloadException.Reason.NOT_A_DATABASE, error.reason)
    }

    @Test
    fun responseShorterThanTheNameFieldIsMalformed() = runTest {
        controller.rawDownloadPayload = ByteArray(10) { 0x41 }
        val error = assertThrows<ConfigDownloadException> { ConfigDownloader(readyConnection()).download() }
        assertEquals(ConfigDownloadException.Reason.MALFORMED_RESPONSE, error.reason)
    }
}
