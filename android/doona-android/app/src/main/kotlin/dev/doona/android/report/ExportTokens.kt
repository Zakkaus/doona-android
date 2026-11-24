package dev.doona.android.report

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The exports whose share sheets may still call back, by the token each sheet's callback carries ([reportShareIntent]),
 * with the crash files a pick in that sheet clears. Kept in [file], so a pick that starts a new process finds its export,
 * and a late pick in an earlier sheet clears that export's files, never a newer crash. A record stays until its clear
 * succeeds, or until no sheet opened for it: a pick is noted first, so a clear that failed, or a process that ended
 * before the clear returned, leaves it [pending] for the next start; a pick that cannot be noted clears nothing until a
 * later save notes it. None is dropped to make room. A save that fails is tried once more. Reads and writes the disk:
 * call it off the main thread.
 */
class ExportTokens(private val file: () -> File) {
    /** Whether a file this version cannot decode was logged: once is enough. */
    private val undecodableLogged = AtomicBoolean(false)

    /** The picks whose note failed ([picked]), by token: each save writes them picked, and the first that succeeds empties it. */
    private val unsaved = HashSet<String>()

    /**
     * Keeps [crashes] for the export [token]; an [IOException] when it cannot, the file cannot be read or decoded or two
     * saves failed, and then no sheet may open for it.
     */
    @Synchronized
    @Throws(IOException::class)
    fun add(token: String, crashes: ExportedCrashes) {
        save(load().filter { it.token != token } + Record(token, crashes.names, crashes.mark))
    }

    /**
     * The crash files of the export [token], once the pick is noted on disk, so that the next start sends the clear
     * again until it succeeds ([forget]). Null for a token unknown or forgotten, and for a pick that could not be noted,
     * the records unread or two saves failed: that is logged ([PICK_UNSAVED]) and nothing is cleared, as a clear that
     * failed would never be sent again. The pick is kept in memory and written with the next save that succeeds; the
     * start after it sends the clear.
     */
    @Synchronized
    fun picked(token: String): ExportedCrashes? {
        val records = try {
            load()
        } catch (_: IOException) {
            return notNoted(token)
        }
        val record = records.find { it.token == token } ?: return null
        if (!record.picked) {
            unsaved += token
            try {
                save(records)
            } catch (_: IOException) {
                return notNoted(token)
            }
        }
        return ExportedCrashes(record.names, record.mark)
    }

    /** A pick not on disk: kept for the next save ([unsaved]) and logged; null, so nothing is cleared. */
    private fun notNoted(token: String): ExportedCrashes? {
        unsaved += token
        AppEvents.record(AppEventLevel.Warn, PICK_UNSAVED)
        return null
    }

    /**
     * Whether the export [token] is still kept: false once its clear succeeded and it was forgotten. One that cannot be
     * read counts as kept, as a clear sent again removes nothing more.
     */
    @Synchronized
    fun kept(token: String): Boolean = try {
        load().any { it.token == token }
    } catch (_: IOException) {
        true
    }

    /** The exports picked whose clear has not yet succeeded, by token. */
    @Synchronized
    fun pending(): List<Pair<String, ExportedCrashes>> = try {
        load().filter { it.picked }.map { it.token to ExportedCrashes(it.names, it.mark) }
    } catch (_: IOException) {
        // The next start reads them again.
        emptyList()
    }

    /**
     * Drops the export [token]: its clear succeeded, or no sheet opened for it. One that cannot be dropped stays, and a
     * clear sent again removes nothing more: every name it holds is gone or will never come back.
     */
    @Synchronized
    fun forget(token: String) {
        unsaved -= token
        try {
            val records = load()
            if (records.any { it.token == token }) save(records.filter { it.token != token })
        } catch (_: IOException) {
            // Kept; harmless.
        }
    }

    /**
     * The records: none when the file was never written. A file that cannot be read, or holds what this version cannot
     * decode, is an [IOException] and stays as it is: read as none, the next save would drop every record it holds. An
     * undecodable one is logged ([TOKENS_UNDECODABLE]).
     */
    private fun load(): List<Record> {
        val text = try {
            Files.readAllBytes(file().toPath()).decodeToString()
        } catch (_: NoSuchFileException) {
            return emptyList()
        }
        return try {
            Json.decodeFromString(SERIALIZER, text)
        } catch (e: IllegalArgumentException) {
            // A SerializationException: another version's records, or a damaged file.
            if (undecodableLogged.compareAndSet(false, true)) AppEvents.record(AppEventLevel.Warn, TOKENS_UNDECODABLE)
            throw IOException(TOKENS_UNDECODABLE, e)
        }
    }

    /**
     * [write], with the picks not yet saved ([unsaved]) marked, and once more when it fails: a save that fails twice is an
     * [IOException], and keeps them for the next.
     */
    private fun save(records: List<Record>) {
        val noted = records.map { if (it.token in unsaved) it.copy(picked = true) else it }
        try {
            write(noted)
        } catch (_: IOException) {
            write(noted)
        }
        unsaved.clear()
    }

    /** Written whole beside the file and synced, then renamed onto it; a failed write keeps the records as they were. */
    private fun write(records: List<Record>) {
        val target = file()
        val part = File(target.parentFile, target.name + PART)
        try {
            FileOutputStream(part).use {
                it.write(Json.encodeToString(SERIALIZER, records).toByteArray())
                it.fd.sync()
            }
            if (!part.renameTo(target)) throw IOException("not renamed")
        } catch (e: IOException) {
            part.delete()
            throw e
        }
    }

    /** One export: its token, the crash files a pick clears, and whether the user picked an app in its sheet. */
    @Serializable
    private data class Record(val token: String, val names: List<String>, val mark: Long, val picked: Boolean = false)

    internal companion object {
        /** The event logged for a tokens file this version cannot decode. */
        const val TOKENS_UNDECODABLE = "report tokens: undecodable"

        /** The event logged for a pick that could not be noted, so nothing was cleared. */
        const val PICK_UNSAVED = "report tokens: pick not saved"
        private const val PART = ".part"
        private val SERIALIZER = ListSerializer(Record.serializer())
    }
}
