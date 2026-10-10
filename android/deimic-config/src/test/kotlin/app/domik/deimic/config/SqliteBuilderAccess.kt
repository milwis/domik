package app.domik.deimic.config

import app.domik.deimic.config.sqlite.SqliteBuilder

/** Bridge to the internal SQLite builder of the `sqlite` test package. */
internal object SqliteBuilderAccess {
    /** A valid SQLite file with one table that is not a Deimic configuration. */
    fun emptyDatabase(): ByteArray =
        SqliteBuilder.singleTable("CREATE TABLE unrelated (v)", listOf(SqliteBuilder.record("x")))
}
