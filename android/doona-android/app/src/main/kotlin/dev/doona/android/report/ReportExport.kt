package dev.doona.android.report

import dev.doona.android.engine.CoreDiagnostics
import dev.doona.android.engine.Engine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The crash files an export holds, which a clear after the share removes: their [names] and the listing's [mark]. */
data class ExportedCrashes(val names: List<String>, val mark: Long)

/** Where the export is: from the tap to the share sheet's return. */
sealed interface ExportState {
    data object Idle : ExportState

    /** The zip is being written. */
    data object Building : ExportState

    /** The zip at [file] is written; the activity shares it. [crashes] are cleared once the share sheet returns. */
    data class Ready(val file: File, val crashes: ExportedCrashes?) : ExportState

    /** The share sheet is open. */
    data class Sharing(val crashes: ExportedCrashes?) : ExportState

    /** The zip could not be written, or no share sheet opened. Shown until dismissed. */
    data object Failed : ExportState
}

/** What the screens get of the export and the crash offer; [ReportExport] in the app, a fixed one in previews. */
interface Reports {
    val export: StateFlow<ExportState>

    /** True while a crash waits to be reported ([CrashOffer]). */
    val crashOffered: StateFlow<Boolean>

    /** Builds the report; the activity shares it when it is [ExportState.Ready]. Also takes down the crash offer. */
    fun startExport()

    fun dismissFailure()

    fun dismissCrash()
}

/**
 * The diagnostic report export: a zip in [outDir] of `report.json` ([reportJson]) and the crash files, which the activity
 * shares through the share sheet. Nothing is sent anywhere: the user picks where it goes. Once the sheet returns, the
 * crash files the zip holds are cleared by the names and the mark of the core's listing, so a crash written meanwhile
 * stays. Without the core's listing (the core is unavailable) the zip takes the app's own, and nothing is cleared.
 * Process-wide, so a build in progress outlives the page that started it; only the newest zip is kept.
 */
class ReportExport(
    private val engine: Engine,
    private val crashes: CrashFiles,
    private val offer: CrashOffer,
    private val outDir: File,
    private val app: () -> AppInfo,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
) : Reports {
    private val state = MutableStateFlow<ExportState>(ExportState.Idle)
    override val export: StateFlow<ExportState> = state.asStateFlow()
    override val crashOffered: StateFlow<Boolean> get() = offer.offered

    override fun startExport() {
        offer.dismiss()
        if (!state.compareAndSet(ExportState.Idle, ExportState.Building) && !state.compareAndSet(ExportState.Failed, ExportState.Building)) return
        scope.launch {
            val core = engine.diagnostics()
            val engineView = EngineView(engine.status.value, engine.settings.value, engine.catalog.value)
            state.value = try {
                withContext(io) { build(core, engineView) }
            } catch (_: IOException) {
                ExportState.Failed
            }
        }
    }

    /** The zip to share, for the activity, once: the state moves on to [ExportState.Sharing]. */
    fun take(): ExportState.Ready? {
        val ready = state.value as? ExportState.Ready ?: return null
        return ready.takeIf { state.compareAndSet(it, ExportState.Sharing(it.crashes)) }
    }

    /** The share sheet returned, whatever the user did there: its result does not say. Clears what the zip holds. */
    fun shared() {
        val sharing = state.value as? ExportState.Sharing ?: return
        if (!state.compareAndSet(sharing, ExportState.Idle)) return
        sharing.crashes?.let { scope.launch { engine.clearCrashes(it.names, it.mark) } }
    }

    /** No share sheet opened: nothing was shared, so nothing is cleared. */
    fun shareFailed() {
        val sharing = state.value as? ExportState.Sharing ?: return
        state.compareAndSet(sharing, ExportState.Failed)
    }

    override fun dismissFailure() {
        state.compareAndSet(ExportState.Failed, ExportState.Idle)
    }

    override fun dismissCrash() = offer.dismiss()

    private fun build(core: Result<CoreDiagnostics>, engineView: EngineView): ExportState.Ready {
        val listing = core.getOrNull()
        val names = listing?.crashes?.map { it.name } ?: crashes.list().map { it.name }
        // A file gone since the listing is left out, and so is its name.
        val files = names.mapNotNull { name -> crashes.read(name)?.let { name to it } }
        val at = clock()
        val json = reportJson(at, app(), engineView, core, AppEvents.snapshot(), files.map { it.first })
        outDir.mkdirs()
        outDir.listFiles().orEmpty().forEach { it.delete() }
        val zip = File(outDir, "doona-report-${STAMP.format(Instant.ofEpochMilli(at))}.zip")
        writeZip(zip, json, files)
        val cleared = listing?.let { l -> files.map { it.first }.takeIf { it.isNotEmpty() }?.let { ExportedCrashes(it, l.crashMark) } }
        return ExportState.Ready(zip, cleared)
    }

    companion object {
        const val REPORT_ENTRY = "report.json"
        const val CRASH_DIR = "crash/"
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault())

        /** The zip: [REPORT_ENTRY], then each crash file under [CRASH_DIR]. */
        internal fun writeZip(target: File, report: String, crashes: List<Pair<String, ByteArray>>) {
            ZipOutputStream(FileOutputStream(target).buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(REPORT_ENTRY))
                zip.write(report.toByteArray())
                zip.closeEntry()
                for ((name, bytes) in crashes) {
                    zip.putNextEntry(ZipEntry(CRASH_DIR + name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }
    }
}
