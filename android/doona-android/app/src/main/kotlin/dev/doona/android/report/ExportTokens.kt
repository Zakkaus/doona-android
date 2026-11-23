package dev.doona.android.report

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * The exports whose share sheets may still call back, by the token each sheet's callback carries ([reportShareIntent]),
 * with the crash files a pick in that sheet clears. Kept in [file], so a pick that starts a new process finds its export,
 * and a late pick in an earlier sheet clears that export's files, never a newer crash. Holds the newest [KEPT]. Reads and
 * writes the disk: call it off the main thread.
 */
class ExportTokens(private val file: () -> File) {
    /** Keeps [crashes] for the export [token]. */
    @Synchronized
    fun add(token: String, crashes: ExportedCrashes) {
        val records = load().filter { it.token != token } + Record(token, crashes.names, crashes.mark)
        save(records.takeLast(KEPT))
    }

    /** The crash files of the export [token], once: the record goes. Null for a token unknown, or gone. */
    @Synchronized
    fun take(token: String): ExportedCrashes? {
        val records = load()
        val record = records.find { it.token == token } ?: return null
        save(records - record)
        return ExportedCrashes(record.names, record.mark)
    }

    private fun load(): List<Record> = try {
        Json.decodeFromString(SERIALIZER, file().readText())
    } catch (_: IOException) {
        emptyList()
    } catch (_: IllegalArgumentException) {
        // A SerializationException: a file this version cannot read keeps no record.
        emptyList()
    }

    /** Written whole beside the file, then renamed onto it; a failed write keeps the records as they were. */
    private fun save(records: List<Record>) {
        val target = file()
        val part = File(target.parentFile, target.name + PART)
        try {
            part.writeText(Json.encodeToString(SERIALIZER, records))
            if (!part.renameTo(target)) part.delete()
        } catch (_: IOException) {
            part.delete()
        }
    }

    @Serializable
    private data class Record(val token: String, val names: List<String>, val mark: Long)

    private companion object {
        const val KEPT = 8
        const val PART = ".part"
        val SERIALIZER = ListSerializer(Record.serializer())
    }
}
