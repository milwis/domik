package app.domik.deimic.config

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

class ConfigCacheTest {
    @TempDir
    lateinit var directory: File

    private val database = fixtureFile("config_synthetic.db3").readBytes()

    private fun cache() = FileConfigCache(File(directory, "cache"))

    private fun files() = File(directory, "cache").list()!!.sorted()

    @Test
    fun fileCacheRoundTrip() {
        val cache = cache()
        assertNull(cache.read("profile-1"))
        cache.write("profile-1", CachedConfig(0x4d62u, database))
        val read = cache.read("profile-1")!!
        assertEquals(0x4d62u.toUShort(), read.checksum)
        assertArrayEquals(database, read.database)
        // a fresh instance (new process) sees the same
        assertArrayEquals(database, FileConfigCache(File(directory, "cache")).read("profile-1")!!.database)
    }

    @Test
    fun checksumExtremesRoundTrip() {
        val cache = cache()
        for (value in listOf<UShort>(0u, 1u, 0xFFFFu, 0x00FFu, 0xFF00u)) {
            cache.write("p", CachedConfig(value, database))
            assertEquals(value, cache.read("p")!!.checksum)
        }
    }

    @Test
    fun overwriteReplacesTheEntryAndLeavesNoOldFiles() {
        val cache = cache()
        cache.write("p", CachedConfig(1u, database))
        val other = database.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        cache.write("p", CachedConfig(2u, other))
        val read = cache.read("p")!!
        assertEquals(2u.toUShort(), read.checksum)
        assertArrayEquals(other, read.database)
        assertEquals(2, files().size, "one database and one metadata file: ${files()}")
        assertTrue(files().none { it.endsWith(".tmp") })
    }

    @Test
    fun writingTheSameContentAgainKeepsTheEntry() {
        val cache = cache()
        cache.write("p", CachedConfig(1u, database))
        cache.write("p", CachedConfig(1u, database))
        assertArrayEquals(database, cache.read("p")!!.database)
        assertEquals(2, files().size)
    }

    @Test
    fun removeDeletesTheEntry() {
        val cache = cache()
        cache.write("p", CachedConfig(1u, database))
        cache.write("q", CachedConfig(2u, database))
        cache.remove("p")
        assertNull(cache.read("p"))
        assertNotNull(cache.read("q"))
        assertEquals(2, files().size)
        cache.remove("never-existed")
    }

    @Test
    fun profilesAreIndependent() {
        val cache = cache()
        val other = database.copyOf().also { it[it.size - 1] = 9 }
        cache.write("a", CachedConfig(1u, database))
        cache.write("b", CachedConfig(2u, other))
        assertArrayEquals(database, cache.read("a")!!.database)
        assertArrayEquals(other, cache.read("b")!!.database)
    }

    @Test
    fun hostileProfileIdsStayInsideTheDirectory() {
        val cache = cache()
        val ids = listOf("../evil", "a/b", "a\\b", "..", ".", "CON", "Ala ma kota", "zażółć", "a%2Fb", "A", "a", "x".repeat(500), "id:with:colons", "\u0000nul")
        for (id in ids) cache.write(id, CachedConfig(1u, database))
        // every id has its own entry, all files are directly in the cache directory
        for (id in ids) assertNotNull(cache.read(id), id)
        assertEquals(ids.size * 2, files().size, files().toString())
        assertTrue(files().all { name -> name.all { it.isLetterOrDigit() || it in "%_-." } && name.length < 200 }, files().toString())
        assertTrue(directory.list()!!.toList() == listOf("cache"), "nothing was written outside the cache directory")
        assertThrows<IllegalArgumentException> { cache.read("") }
        assertThrows<IllegalArgumentException> { cache.write("", CachedConfig(1u, database)) }
    }

    @Test
    fun uppercaseAndLowercaseIdsDoNotCollideOnCaseInsensitiveFileSystems() {
        val names = listOf("Abc", "abc", "ABC").map { id ->
            cache().write(id, CachedConfig(1u, database))
            id
        }
        assertEquals(6, files().size)
        assertEquals(6, files().map { it.lowercase() }.toSet().size, "file names differ even ignoring case: ${files()}")
        assertEquals(3, names.size)
    }

    private fun entryFiles(): Pair<File, File> {
        val cacheDirectory = File(directory, "cache")
        val meta = cacheDirectory.listFiles()!!.single { it.name.endsWith(".meta") }
        val db = cacheDirectory.listFiles()!!.single { it.name.endsWith(".db3") }
        return meta to db
    }

    @Test
    fun damagedEntriesReadAsMissing() {
        val cache = cache()
        fun fresh() {
            cache.remove("p")
            cache.write("p", CachedConfig(0x1234u, database))
        }

        fresh()
        entryFiles().second.let { it.writeBytes(it.readBytes().also { bytes -> bytes[100] = (bytes[100] + 1).toByte() }) }
        assertNull(cache.read("p"), "flipped byte in the database")

        fresh()
        entryFiles().second.let { it.writeBytes(it.readBytes().copyOf(1000)) }
        assertNull(cache.read("p"), "truncated database")

        fresh()
        entryFiles().second.delete()
        assertNull(cache.read("p"), "database file missing")

        fresh()
        entryFiles().first.delete()
        assertNull(cache.read("p"), "metadata missing")

        for (broken in listOf("", "garbage", "crc=zz\nsize=1\nsha256=00", "crc=1234\nsize=abc\nsha256=" + "0".repeat(64),
            "crc=12345678\nsize=${database.size}\nsha256=" + "0".repeat(64), "crc=1234\nsize=${database.size}\n",
            "crc=1234\nsize=${database.size}\nsha256=../../etc/passwd")) {
            fresh()
            entryFiles().first.writeText(broken)
            assertNull(cache.read("p"), "metadata '$broken'")
        }

        fresh()
        assertNotNull(cache.read("p"), "a freshly written entry is readable again")
    }

    @Test
    fun unwritableDirectoryIsAnIoException() {
        val blocker = File(directory, "blocked").also { it.writeText("a file where the cache directory should be") }
        val cache = FileConfigCache(File(blocker, "cache"))
        assertThrows<IOException> { cache.write("p", CachedConfig(1u, database)) }
        assertNull(cache.read("p"))
    }

    @Test
    fun failedWriteKeepsThePreviousEntry() {
        val cache = cache()
        cache.write("p", CachedConfig(1u, database))
        val (meta, db) = entryFiles()
        // The new metadata can not be written: a directory is in the way of the rename target.
        val other = database.copyOf().also { it[it.size - 1] = 7 }
        val metaBackup = meta.readBytes()
        meta.delete()
        meta.mkdir()
        File(meta, "x").writeText("x")
        assertThrows<IOException> { cache.write("p", CachedConfig(2u, other)) }
        meta.listFiles()!!.forEach { it.delete() }
        meta.delete()
        meta.writeBytes(metaBackup)
        assertTrue(db.isFile, "the database of the previous entry was not deleted")
        val read = cache.read("p")!!
        assertEquals(1u.toUShort(), read.checksum)
        assertArrayEquals(database, read.database)
    }

    @Test
    fun inMemoryCache() {
        val cache = InMemoryConfigCache()
        assertNull(cache.read("p"))
        cache.write("p", CachedConfig(5u, database))
        assertEquals(5u.toUShort(), cache.read("p")!!.checksum)
        cache.remove("p")
        assertNull(cache.read("p"))
    }
}
