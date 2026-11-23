package dev.doona.android.report

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException

/**
 * The exports whose share sheets may still call back, by the token each sheet's callback carries ([reportShareIntent]),
 * with the crash files a pick in that sheet clears. Kept in [file], so a pick that starts a new process finds its export,
 * and a late pick in an earlier sheet clears that export's files, never a newer crash. A record stays until its clear
 * succeeds, or until no sheet opened for it: a pick is noted first, so a clear that failed, or a process that ended
 * before the clear returned, leaves it [pending] for the next start. None is dropped to make room. Reads and writes the
 * disk: call it off the main thread.
 */
class ExportTokens(private val file: () -> File) {
    /** Keeps [crashes] for the export [token]; an [IOException] when it cannot, and then no sheet may open for it. */
    @Synchronized
    @Throws(IOException::class)
    fun add(token: String, crashes: ExportedCrashes) {
        save(load().filter { it.token != token } + Record(token, crashes.names, crashes.mark))
    }

    /**
     * The crash files of the export [token], after noting the pick, so that the next start sends the clear again until
     * it succeeds ([forget]). Null for a token unknown or forgotten. A pick that cannot be noted still answers: the clear
     * is sent, only not again.
     */
    @Synchronized
    fun picked(token: String): ExportedCrashes? {
        val records = try {
            load()
        } catch (_: IOException) {
            return null
        }
        val record = records.find { it.token == token } ?: return null
        if (!record.picked) {
            try {
                save(records.map { if (it.token == token) it.copy(picked = true) else it })
            } catch (_: IOException) {
                // The clear is still sent; it is only not sent again should it fail.
            }
        }
        return ExportedCrashes(record.names, record.mark)
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
        try {
            val records = load()
            if (records.any { it.token == token }) save(records.filter { it.token != token })
        } catch (_: IOException) {
            // Kept; harmless.
        }
    }

    /**
     * The records: none when the file was never written, or holds what this version cannot read. A file that cannot be
     * read is an [IOException]: read as none, the next save would drop every record.
     */
    private fun load(): List<Record> {
        val text = try {
            Files.readAllBytes(file().toPath()).decodeToString()
        } catch (_: NoSuchFileException) {
            return emptyList()
        }
        return try {
            Json.decodeFromString(SERIALIZER, text)
        } catch (_: IllegalArgumentException) {
            // A SerializationException.
            emptyList()
        }
    }

    /** Written whole beside the file and synced, then renamed onto it; a failed write keeps the records as they were. */
    private fun save(records: List<Record>) {
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

    private companion object {
        const val PART = ".part"
        val SERIALIZER = ListSerializer(Record.serializer())
    }
}
