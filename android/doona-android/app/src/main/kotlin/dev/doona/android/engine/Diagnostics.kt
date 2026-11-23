package dev.doona.android.engine

/** How severe a core event is; the core's ring keeps the events of its level and the more severe. */
enum class LogLevel { Error, Warn, Info, Debug }

/** A crash report the core wrote: its file [name] in the core's `crash/` directory and its size in [bytes]. */
data class CrashFile(val name: String, val bytes: Long)

/**
 * What [Engine.diagnostics] returned: the ring's [level], the crash files waiting, oldest first, and [json], the core's
 * whole reply as JSON text for the export's report.
 */
data class CoreDiagnostics(val level: LogLevel, val crashes: List<CrashFile>, val json: String)
