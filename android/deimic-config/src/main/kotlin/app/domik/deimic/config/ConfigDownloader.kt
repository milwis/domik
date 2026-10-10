package app.domik.deimic.config

import app.domik.deimic.client.Connection
import app.domik.deimic.config.sqlite.SqliteDatabase
import app.domik.deimic.protocol.CommandCode
import app.domik.deimic.protocol.FileDownloadResponse
import app.domik.deimic.protocol.ProtocolException
import app.domik.deimic.protocol.Request
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The database file as received from the controller. */
class DownloadedConfig(
    /** File name echoed by the controller (equal to [ConfigDownloader.DATABASE_PATH]). */
    val fileName: String,
    /** The SQLite file; do not modify. */
    val bytes: ByteArray,
)

/**
 * Downloads the configuration database from the controller: request 0x212 with the fixed path
 * [DATABASE_PATH] (64 bytes, NUL-padded), response 0x212 `[64-byte file name][file content]`. This is a read; the
 * path is a constant and can not be changed from outside.
 *
 * Checks the echoed file name, that the content is not empty and that it starts with the SQLite header string.
 * Whether the content is a usable configuration is decided later by [HomeConfigParser].
 */
class ConfigDownloader(
    private val connection: Connection,
    /** Time to wait for the (large, ~75 kB) response. */
    private val timeout: Duration = 30.seconds,
) {
    /**
     * @throws ConfigDownloadException the response is not the expected file.
     * @throws app.domik.deimic.client.ConnectionException no response in time or connection lost.
     * @throws app.domik.deimic.client.NotConnectedException the connection is not ready.
     */
    suspend fun download(): DownloadedConfig {
        val request = Request.fileDownload(connection.serial, DATABASE_PATH)
        val result = connection.request(request, expecting = setOf(CommandCode.FILE_DOWNLOAD), timeout = timeout)
        val response = try {
            FileDownloadResponse.parse(result.responses.first())
        } catch (error: ProtocolException) {
            throw ConfigDownloadException(
                ConfigDownloadException.Reason.MALFORMED_RESPONSE, "unusable file download response: ${error.message}", error,
            )
        }
        if (response.name != DATABASE_PATH) {
            throw ConfigDownloadException(
                ConfigDownloadException.Reason.UNEXPECTED_FILE_NAME,
                "the controller returned a file named '${response.name}' instead of the requested one",
            )
        }
        if (response.content.isEmpty()) {
            throw ConfigDownloadException(ConfigDownloadException.Reason.EMPTY_FILE, "the controller returned an empty file")
        }
        val bytes = response.content.toByteArray()
        if (!SqliteDatabase.hasMagic(bytes)) {
            throw ConfigDownloadException(
                ConfigDownloadException.Reason.NOT_A_DATABASE, "the downloaded file (${bytes.size} B) is not a SQLite database",
            )
        }
        return DownloadedConfig(response.name, bytes)
    }

    companion object {
        /** The only file this class downloads. */
        const val DATABASE_PATH: String = "/logs/database-last.db3"
    }
}
