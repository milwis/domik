package app.domik.deimic.config

import app.domik.deimic.config.sqlite.SqliteValue
import java.io.File
import java.security.MessageDigest

/** Directory with the shared fixtures (`<repo>/testdata`), set by Gradle. */
val testDataDirectory: File = File(checkNotNull(System.getProperty("domik.testdata")) { "domik.testdata is not set" })

fun fixtureFile(name: String): File = File(testDataDirectory, name).also { check(it.isFile) { "missing fixture $name" } }

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

@Suppress("UNCHECKED_CAST")
fun Any?.asObject(): Map<String, Any?> = this as Map<String, Any?>

@Suppress("UNCHECKED_CAST")
fun Any?.asList(): List<Any?> = this as List<Any?>

fun Map<String, Any?>.obj(key: String): Map<String, Any?> = getValue(key).asObject()

fun Map<String, Any?>.list(key: String): List<Any?> = getValue(key).asList()

fun Map<String, Any?>.objects(key: String): List<Map<String, Any?>> = list(key).map { it.asObject() }

fun Map<String, Any?>.string(key: String): String = getValue(key) as String

fun Map<String, Any?>.long(key: String): Long = getValue(key) as Long

fun Map<String, Any?>.int(key: String): Int = long(key).toInt()

fun Map<String, Any?>.intOrNull(key: String): Int? = (getValue(key) as Long?)?.toInt()

/** The shared expected-results document produced by `tools/gen_config_db.py` from real SQLite. */
val expectedDocument: Map<String, Any?> by lazy {
    MiniJson.parse(fixtureFile("config_synthetic.expected.json").readText(Charsets.UTF_8)).asObject()
}

/**
 * Compares a value read by our reader with the generator's JSON encoding: integers as numbers, REAL as
 * `{"real": <big-endian IEEE-754 hex>}`, short text as a string, long text as `{"text": {len, sha256}}`,
 * blobs as `{"blob": {len, sha256}}`, NULL as null.
 */
fun describeMismatch(expected: Any?, actual: SqliteValue): String? = when {
    expected == null -> if (actual == SqliteValue.Null) null else "expected NULL, got $actual"
    expected is Long -> if (actual is SqliteValue.Integer && actual.value == expected) null else "expected $expected, got ${show(actual)}"
    expected is String ->
        if (actual is SqliteValue.Text && actual.value == expected) null else "expected text '$expected', got ${show(actual)}"
    expected is Map<*, *> && "real" in expected -> {
        val bits = java.lang.Long.parseUnsignedLong(expected["real"] as String, 16)
        if (actual is SqliteValue.Real && actual.value.toRawBits() == bits) null else "expected real $bits, got ${show(actual)}"
    }
    expected is Map<*, *> && "text" in expected -> {
        val info = expected["text"] as Map<*, *>
        val bytes = (actual as? SqliteValue.Text)?.value?.toByteArray(Charsets.UTF_8)
        if (bytes != null && bytes.size.toLong() == info["len"] && sha256Hex(bytes) == info["sha256"]) null
        else "long text differs (${show(actual)})"
    }
    expected is Map<*, *> && "blob" in expected -> {
        val info = expected["blob"] as Map<*, *>
        val bytes = (actual as? SqliteValue.Blob)?.bytes
        if (bytes != null && bytes.size.toLong() == info["len"] && sha256Hex(bytes) == info["sha256"]) null
        else "blob differs (${show(actual)})"
    }
    else -> "unsupported expectation $expected"
}

private fun show(value: SqliteValue): String = when (value) {
    SqliteValue.Null -> "NULL"
    is SqliteValue.Integer -> "integer ${value.value}"
    is SqliteValue.Real -> "real ${value.value}"
    is SqliteValue.Text -> "text of ${value.value.length} chars"
    is SqliteValue.Blob -> "blob of ${value.bytes.size} bytes"
}
