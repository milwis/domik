package app.domik.automation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.time.Instant

/**
 * Persistence of the timers' **absolute** switch-off times, so that a restart of the app or the tablet (or a long
 * connection outage) does not make the engine forget when a light was due to go off.
 *
 * [AutoOffEngine] calls [save] after every change of its timers (writes are coalesced: only the latest state is
 * saved when changes come faster than [save] returns) and [load] once when it starts. Failures are ignored by the
 * engine – a deadline that could not be saved only means counting from observation after a restart.
 */
interface DeadlineStore {
    suspend fun load(): Map<OutputAddress, Instant>

    suspend fun save(deadlines: Map<OutputAddress, Instant>)

    /** Stores nothing. */
    object None : DeadlineStore {
        override suspend fun load(): Map<OutputAddress, Instant> = emptyMap()

        override suspend fun save(deadlines: Map<OutputAddress, Instant>) = Unit
    }
}

/** Keeps the deadlines in memory only (tests, short-lived services). */
class InMemoryDeadlineStore(initial: Map<OutputAddress, Instant> = emptyMap()) : DeadlineStore {
    @Volatile
    var deadlines: Map<OutputAddress, Instant> = initial.toMap()
        private set

    override suspend fun load(): Map<OutputAddress, Instant> = deadlines

    override suspend fun save(deadlines: Map<OutputAddress, Instant>) {
        this.deadlines = deadlines.toMap()
    }
}

/**
 * Stores the deadlines as JSON in [file] (the app's private storage, or a service's data directory). The file is
 * replaced atomically, so a crash in the middle of a write leaves the previous version. A missing, unreadable or
 * damaged file reads as "no deadlines".
 */
class FileDeadlineStore(private val file: File) : DeadlineStore {
    override suspend fun load(): Map<OutputAddress, Instant> =
        withContext(Dispatchers.IO) {
            try {
                if (file.isFile) DeadlinesJson.decode(file.readText(Charsets.UTF_8)) else emptyMap()
            } catch (_: java.io.IOException) {
                emptyMap()
            }
        }

    override suspend fun save(deadlines: Map<OutputAddress, Instant>) {
        withContext(Dispatchers.IO) {
            val directory = file.absoluteFile.parentFile
            directory.mkdirs()
            val temporary = File(directory, file.name + ".tmp")
            temporary.writeText(DeadlinesJson.encode(deadlines), Charsets.UTF_8)
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
