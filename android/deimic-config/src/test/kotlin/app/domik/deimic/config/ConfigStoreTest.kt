@file:OptIn(ExperimentalCoroutinesApi::class)

package app.domik.deimic.config

import app.domik.deimic.client.Connection
import app.domik.deimic.client.ConnectionState
import app.domik.deimic.client.ControllerEndpoint
import app.domik.deimic.client.ControllerProfile
import app.domik.deimic.client.FrameTransportFactory
import app.domik.deimic.client.InMemoryTransport
import app.domik.deimic.client.RequestTimeoutException
import app.domik.deimic.protocol.Bytes
import app.domik.deimic.protocol.CommandCode
import app.domik.deimic.protocol.Frame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

class ConfigStoreTest {
    private val fixture = fixtureFile("config_synthetic.db3").readBytes()
    private val controller = FakeDatabaseController(fixture, checksum = 0x4d62)
    private val profile = ControllerProfile("profile-1", "Dom", "192.0.2.10")
    private val parser = HomeConfigParser()
    private val expected = parser.parse(fixture)

    @TempDir
    lateinit var directory: File

    private fun TestScope.readyConnection(): Connection {
        val factory = FrameTransportFactory { _ -> InMemoryTransport(onFrame = { frame: Frame -> controller.handle(this, frame) }) }
        val connection = Connection(ControllerEndpoint(profile.host), FAKE_SERIAL, backgroundScope, factory)
        connection.connect()
        runCurrent()
        assertEquals(ConnectionState.Ready, connection.state.value)
        return connection
    }

    private fun TestScope.store(
        cache: ConfigCache = InMemoryConfigCache(),
        profile: ControllerProfile = this@ConfigStoreTest.profile,
        connection: Connection = readyConnection(),
    ) = ConfigStore(connection, profile, cache, parser, ConfigDownloader(connection), StandardTestDispatcher(testScheduler))

    @Test
    fun firstLoadDownloadsParsesAndCaches() = runTest {
        val cache = InMemoryConfigCache()
        val loaded = store(cache).load()

        assertEquals(expected, loaded.config)
        assertEquals(ConfigSource.CONTROLLER, loaded.source)
        assertFalse(loaded.fromCache)
        assertFalse(loaded.cacheWriteFailed)
        assertEquals(0x4d62u.toUShort(), loaded.checksum.value)
        assertEquals(listOf(CommandCode.CONFIG_CHECKSUM, CommandCode.FILE_DOWNLOAD), controller.received)
        val cached = cache.read(profile.id)!!
        assertEquals(0x4d62u.toUShort(), cached.checksum)
        assertArrayEquals(fixture, cached.database)
    }

    @Test
    fun sameChecksumLoadsFromCacheWithoutDownloading() = runTest {
        val cache = InMemoryConfigCache()
        val store = store(cache)
        store.load()
        controller.received.clear()

        val loaded = store.load()
        assertTrue(loaded.fromCache)
        assertEquals(ConfigSource.CACHE, loaded.source)
        assertEquals(expected, loaded.config)
        assertEquals(listOf(CommandCode.CONFIG_CHECKSUM), controller.received, "only the version token is requested")
    }

    @Test
    fun cacheSurvivesANewStoreAndANewConnection() = runTest {
        val cache = FileConfigCache(File(directory, "cache"))
        store(cache).load()
        controller.received.clear()

        val loaded = store(FileConfigCache(File(directory, "cache"))).load()
        assertTrue(loaded.fromCache)
        assertEquals(expected, loaded.config)
        assertEquals(0, controller.downloads)
    }

    @Test
    fun changedChecksumDownloadsAgainAndReplacesTheCache() = runTest {
        val cache = InMemoryConfigCache()
        val store = store(cache)
        store.load()

        controller.checksum = 0x0001
        controller.database = fixtureFile("config_synthetic_4096.db3").readBytes()
        controller.received.clear()
        val loaded = store.load()
        assertEquals(ConfigSource.CONTROLLER, loaded.source)
        assertEquals(1u.toUShort(), loaded.checksum.value)
        assertEquals(1, controller.downloads)
        assertEquals(1u.toUShort(), cache.read(profile.id)!!.checksum)
        assertArrayEquals(controller.database, cache.read(profile.id)!!.database)
    }

    @Test
    fun unparsableDownloadIsNotCachedAndKeepsTheOldEntry() = runTest {
        val cache = InMemoryConfigCache()
        val store = store(cache)
        store.load()

        // A valid SQLite file without the configuration tables
        controller.checksum = 0x0002
        controller.database = SqliteBuilderAccess.emptyDatabase()
        assertThrows<ConfigParseException> { store.load() }
        val kept = cache.read(profile.id)!!
        assertEquals(0x4d62u.toUShort(), kept.checksum, "the previous working copy is still there")
        assertArrayEquals(fixture, kept.database)
    }

    @Test
    fun unparsableFirstDownloadLeavesTheCacheEmpty() = runTest {
        val cache = InMemoryConfigCache()
        controller.database = SqliteBuilderAccess.emptyDatabase()
        assertThrows<ConfigParseException> { store(cache).load() }
        assertNull(cache.read(profile.id))
    }

    @Test
    fun damagedCopyWithMatchingChecksumIsReplaced() = runTest {
        val cache = InMemoryConfigCache()
        cache.write(profile.id, CachedConfig(0x4d62u, ByteArray(5000) { 7 }))
        val loaded = store(cache).load()
        assertEquals(ConfigSource.CONTROLLER, loaded.source)
        assertEquals(expected, loaded.config)
        assertArrayEquals(fixture, cache.read(profile.id)!!.database)
    }

    @Test
    fun cacheWriteFailureIsReportedButTheConfigIsReturned() = runTest {
        val failing = object : ConfigCache {
            override fun read(profileId: String): CachedConfig? = null
            override fun write(profileId: String, entry: CachedConfig) = throw IOException("disk full")
            override fun remove(profileId: String) = Unit
        }
        val loaded = store(failing).load()
        assertEquals(expected, loaded.config)
        assertTrue(loaded.cacheWriteFailed)
        assertEquals(ConfigSource.CONTROLLER, loaded.source)
    }

    @Test
    fun profilesHaveSeparateCacheEntries() = runTest {
        val cache = InMemoryConfigCache()
        store(cache, profile).load()
        controller.received.clear()
        val other = ControllerProfile("profile-2", "Dacza", "192.0.2.11")
        val loaded = store(cache, other).load()
        assertEquals(ConfigSource.CONTROLLER, loaded.source, "another profile does not use the first profile's copy")
        assertEquals(1, controller.downloads)
        assertNotNull(cache.read("profile-1"))
        assertNotNull(cache.read("profile-2"))
    }

    @Test
    fun loadCachedWorksWithoutTheController() = runTest {
        val cache = InMemoryConfigCache()
        val store = store(cache)
        assertNull(store.loadCached())
        store.load()
        controller.received.clear()

        val cached = store.loadCached()!!
        assertEquals(expected, cached.config)
        assertTrue(cached.fromCache)
        assertEquals(0x4d62u.toUShort(), cached.checksum.value)
        assertTrue(controller.received.isEmpty(), "nothing was sent")
    }

    @Test
    fun loadCachedIgnoresDamagedCopies() = runTest {
        val cache = InMemoryConfigCache()
        cache.write(profile.id, CachedConfig(1u, ByteArray(100)))
        assertNull(store(cache).loadCached())
    }

    @Test
    fun invalidateForcesANewDownload() = runTest {
        val cache = InMemoryConfigCache()
        val store = store(cache)
        store.load()
        store.invalidate()
        assertNull(cache.read(profile.id))
        controller.received.clear()
        assertEquals(ConfigSource.CONTROLLER, store.load().source)
        assertEquals(1, controller.downloads)
    }

    @Test
    fun silentControllerTimesOutAndKeepsTheCache() = runTest {
        val cache = InMemoryConfigCache()
        val store = store(cache)
        store.load()

        controller.checksum = 5
        controller.silent = setOf(CommandCode.FILE_DOWNLOAD)
        assertThrows<RequestTimeoutException> { store.load() }
        assertEquals(0x4d62u.toUShort(), cache.read(profile.id)!!.checksum)

        controller.silent = setOf(CommandCode.CONFIG_CHECKSUM)
        assertThrows<RequestTimeoutException> { store.load() }
        // after the failures the store works again
        controller.silent = emptySet()
        assertEquals(ConfigSource.CONTROLLER, store.load().source)
    }

    @Test
    fun malformedChecksumResponseIsADownloadException() = runTest {
        val garbage = FrameTransportFactory { _ ->
            InMemoryTransport(onFrame = { frame: Frame ->
                if (frame.command == CommandCode.CONFIG_CHECKSUM) {
                    deliver(Frame(FAKE_SERIAL, 0u, CommandCode.CONFIG_CHECKSUM, payload = Bytes.of(1)))
                } else {
                    controller.handle(this, frame)
                }
            })
        }
        val broken = Connection(ControllerEndpoint(profile.host), FAKE_SERIAL, backgroundScope, garbage)
        broken.connect()
        runCurrent()
        val error = assertThrows<ConfigDownloadException> { store(connection = broken).load() }
        assertEquals(ConfigDownloadException.Reason.MALFORMED_RESPONSE, error.reason)
        assertEquals(0, controller.downloads)
    }

    @Test
    fun concurrentLoadsDownloadOnlyOnce() = runTest {
        val store = store()
        val results = listOf(async { store.load() }, async { store.load() }, async { store.load() }).awaitAll()
        assertEquals(1, controller.downloads)
        assertEquals(1, results.count { it.source == ConfigSource.CONTROLLER })
        assertEquals(2, results.count { it.fromCache })
        assertTrue(results.all { it.config == expected })
    }

    @Test
    fun onlyReadCommandsAreEverSent() = runTest {
        val store = store(InMemoryConfigCache())
        store.load()
        controller.checksum = 9
        store.load()
        store.loadCached()
        store.invalidate()
        store.load()
        val allowed = setOf(CommandCode.CONFIG_CHECKSUM, CommandCode.FILE_DOWNLOAD)
        assertTrue(controller.received.all { it in allowed }, controller.received.toString())
        // the download path is the constant database file – nothing else is ever requested
        assertTrue(controller.frames.filter { it.command == CommandCode.FILE_DOWNLOAD }.all { frame ->
            frame.payload.toByteArray().takeWhile { it != 0.toByte() }.toByteArray().decodeToString() == ConfigDownloader.DATABASE_PATH
        })
    }
}
