package app.domik.deimic.config

/** Failure while obtaining or interpreting the controller's configuration database. */
sealed class ConfigException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The database could not be turned into a [HomeConfig]: it is not a readable SQLite file, or a required table or
 * column is missing. Messages are technical (for logs and diagnostics) and never contain device names.
 */
class ConfigParseException(message: String, cause: Throwable? = null) : ConfigException(message, cause)

/** The controller's answer to the file download request (0x212) is not the expected database. */
class ConfigDownloadException(val reason: Reason, message: String, cause: Throwable? = null) :
    ConfigException(message, cause) {
    enum class Reason {
        /** The response payload is shorter than the 64-byte file name or otherwise unparsable. */
        MALFORMED_RESPONSE,

        /** The controller echoed a different file name than the one requested. */
        UNEXPECTED_FILE_NAME,

        /** The response carries no file content. */
        EMPTY_FILE,

        /** The content does not start with the SQLite header string. */
        NOT_A_DATABASE,
    }
}
