package app.domik.deimic.config

import app.domik.deimic.client.Connection
import app.domik.deimic.client.ControllerProfile
import app.domik.deimic.protocol.CommandCode
import app.domik.deimic.protocol.ConfigChecksum
import app.domik.deimic.protocol.ProtocolException
import app.domik.deimic.protocol.Request
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

enum class ConfigSource {
    /** Parsed from the stored copy; only the version token was asked from the controller. */
    CACHE,

    /** Downloaded from the controller just now. */
    CONTROLLER,
}

/**
 * Result of [ConfigStore.load].
 *
 * @property checksum the version token (command 0x1C8) the configuration belongs to.
 * @property cacheWriteFailed a fresh download could not be stored (the configuration itself is fine).
 */
data class LoadedConfig(
    val config: HomeConfig,
    val checksum: ConfigChecksum,
    val source: ConfigSource,
    val cacheWriteFailed: Boolean = false,
) {
    val fromCache: Boolean get() = source == ConfigSource.CACHE
}

/**
 * Provides the [HomeConfig] of one controller, downloading the database only when it changed.
 *
 * [load]: ask the controller for the configuration version token (0x1C8); when the cache holds a copy with the same
 * token for this profile, parse that copy; otherwise download the database (0x212), parse it and – only if parsing
 * succeeded – store it. The token is a version marker that is only compared for equality.
 *
 * Reads only (0x1C8 and 0x212). Calls are serialized. Cache access and parsing run on [ioDispatcher].
 */
class ConfigStore(
    private val connection: Connection,
    private val profile: ControllerProfile,
    private val cache: ConfigCache,
    private val parser: HomeConfigParser = HomeConfigParser(),
    private val downloader: ConfigDownloader = ConfigDownloader(connection),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Mutex()

    /**
     * @throws ConfigParseException the downloaded database is unusable (nothing is cached).
     * @throws ConfigDownloadException the controller's answer is not the expected file.
     * @throws app.domik.deimic.client.ConnectionException no answer in time or connection lost.
     * @throws app.domik.deimic.client.NotConnectedException the connection is not ready.
     */
    suspend fun load(): LoadedConfig = lock.withLock {
        val checksum = requestChecksum()
        val stored = withContext(ioDispatcher) { cache.read(profile.id) }
        if (stored != null && stored.checksum == checksum.value) {
            val config = withContext(ioDispatcher) {
                try {
                    parser.parse(stored.database)
                } catch (error: ConfigParseException) {
                    null // damaged copy: fall through to a fresh download
                }
            }
            if (config != null) return@withLock LoadedConfig(config, checksum, ConfigSource.CACHE)
        }

        val downloaded = downloader.download()
        val config = withContext(ioDispatcher) { parser.parse(downloaded.bytes) }
        val written = withContext(ioDispatcher) {
            try {
                cache.write(profile.id, CachedConfig(checksum.value, downloaded.bytes))
                true
            } catch (error: IOException) {
                false
            }
        }
        LoadedConfig(config, checksum, ConfigSource.CONTROLLER, cacheWriteFailed = !written)
    }

    /**
     * The stored copy, without contacting the controller (for example while offline). Null when there is no usable
     * copy. The copy may be outdated; its [LoadedConfig.checksum] tells which version it is.
     */
    suspend fun loadCached(): LoadedConfig? = lock.withLock {
        withContext(ioDispatcher) {
            val stored = cache.read(profile.id) ?: return@withContext null
            try {
                LoadedConfig(parser.parse(stored.database), ConfigChecksum(stored.checksum), ConfigSource.CACHE)
            } catch (error: ConfigParseException) {
                null
            }
        }
    }

    /** Forgets the stored copy; the next [load] downloads again. */
    suspend fun invalidate() {
        lock.withLock { withContext(ioDispatcher) { cache.remove(profile.id) } }
    }

    private suspend fun requestChecksum(): ConfigChecksum {
        val result = connection.request(Request.configChecksum(connection.serial), setOf(CommandCode.CONFIG_CHECKSUM))
        return try {
            ConfigChecksum.parse(result.responses.first())
        } catch (error: ProtocolException) {
            throw ConfigDownloadException(
                ConfigDownloadException.Reason.MALFORMED_RESPONSE, "unusable configuration checksum response: ${error.message}", error,
            )
        }
    }
}
