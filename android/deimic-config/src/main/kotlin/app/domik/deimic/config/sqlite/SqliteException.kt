package app.domik.deimic.config.sqlite

/** Base class of all errors of the SQLite reader. */
open class SqliteException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The bytes are not a database this reader can handle: wrong magic, truncated file, unsupported feature
 * (text encoding other than UTF-8, WITHOUT ROWID tables, …) or a corrupt structure (bad page number, cycle,
 * payload larger than the file, …). Never thrown for a programming error of the caller.
 */
class SqliteFormatException(message: String) : SqliteException(message)

/** Invalid use of the API: unknown table or column, or a typed getter applied to a value of another type. */
class SqliteQueryException(message: String) : SqliteException(message)
