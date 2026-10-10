package app.domik.deimic.config

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * A stored copy of the configuration database together with the version token (the checksum from command 0x1C8)
 * it was downloaded under. The token is only compared for equality – it is not verified against the content.
 */
class CachedConfig(val checksum: UShort, val database: ByteArray)

/**
 * Persistent store of downloaded databases, one entry per controller profile id. Methods are blocking (file
 * access): call them from an I/O dispatcher. An entry that is missing, unreadable or inconsistent reads as null.
 */
interface ConfigCache {
    fun read(profileId: String): CachedConfig?

    /** Replaces the entry of [profileId]. @throws IOException it could not be written (the previous entry stays intact). */
    fun write(profileId: String, entry: CachedConfig)

    fun remove(profileId: String)
}

/** Cache held in memory – for tests and for processes that do not need persistence. */
class InMemoryConfigCache : ConfigCache {
    private val entries = ConcurrentHashMap<String, CachedConfig>()

    override fun read(profileId: String): CachedConfig? = entries[profileId]

    override fun write(profileId: String, entry: CachedConfig) {
        entries[profileId] = entry
    }

    override fun remove(profileId: String) {
        entries.remove(profileId)
    }
}

/**
 * Cache in a directory, per profile: `<id>.meta` (the commit record: checksum, size and SHA-256 of the database)
 * and `<id>.<sha256>.db3` (the database itself). A new entry is written as follows: database file first, then the
 * metadata – both through a temporary file that is renamed into place – and finally the files of older versions
 * are deleted. The rename of the metadata is the commit point, so a crash or a write error at any moment leaves
 * either the previous entry or the new one, never a mixture. An entry whose files are missing, of the wrong size
 * or fail the SHA-256 check reads as "no entry".
 *
 * Profile ids are encoded into safe file names (lowercase letters, digits, `_` and `-` stay; everything else,
 * including uppercase letters, becomes `%XX`; ids that would give very long names are replaced by a hash).
 */
class FileConfigCache(private val directory: File) : ConfigCache {
    override fun read(profileId: String): CachedConfig? {
        val metaFile = metaFile(profileId)
        return try {
            if (!metaFile.isFile) return null
            val meta = metaFile.readLines(Charsets.US_ASCII).mapNotNull { line ->
                line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it) to line.substring(it + 1) }
            }.toMap()
            val checksum = meta["crc"]?.toIntOrNull(16)?.takeIf { it in 0..0xFFFF } ?: return null
            val size = meta["size"]?.toLongOrNull() ?: return null
            val digest = meta["sha256"]?.takeIf { SHA256_HEX.matches(it) } ?: return null
            val databaseFile = databaseFile(profileId, digest)
            if (!databaseFile.isFile || databaseFile.length() != size) return null
            val bytes = databaseFile.readBytes()
            if (sha256(bytes) != digest) return null
            CachedConfig(checksum.toUShort(), bytes)
        } catch (error: IOException) {
            null
        }
    }

    override fun write(profileId: String, entry: CachedConfig) {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("cannot create the cache directory")
        }
        val digest = sha256(entry.database)
        val databaseFile = databaseFile(profileId, digest)
        if (!databaseFile.isFile || databaseFile.length() != entry.database.size.toLong()) {
            replaceAtomically(databaseFile) { it.write(entry.database) }
        }
        val meta = "crc=${entry.checksum.toString(16)}\nsize=${entry.database.size}\nsha256=$digest\n"
        replaceAtomically(metaFile(profileId)) { it.write(meta.toByteArray(Charsets.US_ASCII)) }
        deleteOtherVersions(profileId, keep = databaseFile)
    }

    override fun remove(profileId: String) {
        metaFile(profileId).delete()
        deleteOtherVersions(profileId, keep = null)
    }

    private fun base(profileId: String): String = encode(profileId)

    private fun metaFile(profileId: String): File = File(directory, base(profileId) + META_EXTENSION)

    private fun databaseFile(profileId: String, digest: String): File =
        File(directory, base(profileId) + "." + digest + DATABASE_EXTENSION)

    /** Deletes `<id>.<sha256>.db3` files other than [keep] (older versions, leftovers of an interrupted write). */
    private fun deleteOtherVersions(profileId: String, keep: File?) {
        val prefix = base(profileId) + "."
        directory.listFiles()?.forEach { file ->
            if (file != keep && file.name.startsWith(prefix) && file.name.endsWith(DATABASE_EXTENSION)) file.delete()
        }
    }

    private fun replaceAtomically(target: File, write: (FileOutputStream) -> Unit) {
        val temporary = File.createTempFile("cache-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { stream ->
                write(stream)
                stream.fd.sync()
            }
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (error: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    private companion object {
        const val DATABASE_EXTENSION = ".db3"
        const val META_EXTENSION = ".meta"
        const val MAX_NAME_LENGTH = 100
        val SHA256_HEX = Regex("[0-9a-f]{64}")

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /**
         * File name for a profile id: `a-z`, `0-9`, `_` and `-` stay, every other UTF-8 byte becomes `%XX` (so the
         * result has no dots, slashes or uppercase letters). Overlong results are replaced by `h-<sha256>`.
         */
        fun encode(profileId: String): String {
            require(profileId.isNotEmpty()) { "profile id must not be empty" }
            val builder = StringBuilder()
            val bytes = profileId.toByteArray(Charsets.UTF_8)
            for (byte in bytes) {
                val char = (byte.toInt() and 0xFF).toChar()
                if (char in 'a'..'z' || char in '0'..'9' || char == '_' || char == '-') {
                    builder.append(char)
                } else {
                    builder.append('%').append("%02X".format(byte))
                }
            }
            return if (builder.length <= MAX_NAME_LENGTH) builder.toString() else "h-" + sha256(bytes)
        }
    }
}
