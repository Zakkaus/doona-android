package dev.doona.android.report

import dev.doona.android.engine.CoreDiagnostics
import dev.doona.android.engine.Engine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
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
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The crash files an export holds, which a clear after a pick in the share sheet removes: their [names] and the listing's [mark]. */
data class ExportedCrashes(val names: List<String>, val mark: Long)

/** Where the export is: from the tap to the share sheet's return. */
sealed interface ExportState {
    data object Idle : ExportState

    /** The zip is being written. */
    data object Building : ExportState

    /** The zip at [file] is written; the activity shares it. [crashes] are cleared once the user picks an app for it. */
    data class Ready(val file: File, val crashes: ExportedCrashes?) : ExportState

    /** The share sheet for [file] is open. */
    data class Sharing(val file: File) : ExportState

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

    /** The crash offer reached the screen, so it is not made again for the same crash ([CrashOffer.shown]). */
    fun crashShown()
}

/**
 * The diagnostic report export: a zip in [outDir] of `report.json` ([reportJson]) and the crash files, which the activity
 * shares through the share sheet. Nothing is sent anywhere: the user picks where it goes. Once the sheet reports that the
 * user picked an app ([picked]), the crash files the zip holds are cleared by the names and the mark of the core's
 * listing, so a crash written meanwhile stays; a sheet closed without a pick keeps them. Without the core's listing (the
 * core is unavailable) the zip takes the app's own, and nothing is cleared. Process-wide, so a build in progress outlives
 * the page that started it. Each zip has a name of its own; one the sheet took is deleted [SHARE_GRACE_MS] after the
 * sheet returns, which leaves the app picked time to read it, and one it did not take at once; [sweep] removes what an
 * earlier run left.
 */
class ReportExport(
    private val engine: Engine,
    private val crashes: CrashFiles,
    private val offer: CrashOffer,
    private val outDir: () -> File,
    private val app: () -> AppInfo,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
) : Reports {
    private val state = MutableStateFlow<ExportState>(ExportState.Idle)

    /** What a pick in the open share sheet clears; taken once. */
    private val onPick = AtomicReference<ExportedCrashes?>()
    override val export: StateFlow<ExportState> = state.asStateFlow()
    override val crashOffered: StateFlow<Boolean> get() = offer.offered

    override fun startExport() {
        offer.dismiss()
        if (!state.compareAndSet(ExportState.Idle, ExportState.Building) && !state.compareAndSet(ExportState.Failed, ExportState.Building)) return
        // The new zip holds the crashes a late pick in an earlier sheet would clear.
        onPick.set(null)
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
        if (!state.compareAndSet(ready, ExportState.Sharing(ready.file))) return null
        onPick.set(ready.crashes)
        return ready
    }

    /** The share sheet did not open because the activity ended first, as on rotation: the next one shares [ready]. */
    fun putBack(ready: ExportState.Ready) {
        if (state.compareAndSet(ExportState.Sharing(ready.file), ready)) onPick.set(null)
    }

    /**
     * The user picked an app in the share sheet (the sheet's callback, `Intent.createChooser` with an `IntentSender`):
     * clears the crash files the zip holds, once. A pick does not prove the app sent the zip on, but it is the only
     * answer the sheet gives; its activity result says nothing, and closing the sheet clears nothing.
     */
    fun picked() {
        onPick.getAndSet(null)?.let { scope.launch { engine.clearCrashes(it.names, it.mark) } }
    }

    /** The share sheet returned, whatever the user did there. The zip goes after [SHARE_GRACE_MS]. */
    fun shared() {
        val sharing = state.value as? ExportState.Sharing ?: return
        if (!state.compareAndSet(sharing, ExportState.Idle)) return
        scope.launch(io) {
            delay(SHARE_GRACE_MS)
            sharing.file.delete()
        }
    }

    /** No share sheet opened: nothing was shared, so nothing is cleared, and the zip goes. */
    fun shareFailed() {
        val sharing = state.value as? ExportState.Sharing ?: return
        if (!state.compareAndSet(sharing, ExportState.Failed)) return
        onPick.set(null)
        scope.launch(io) { sharing.file.delete() }
    }

    /** Removes the zips an earlier run left, those older than [SHARE_GRACE_MS]; it reads the disk, so call it off the main thread. */
    fun sweep() {
        val before = clock() - SHARE_GRACE_MS
        outDir().listFiles { file -> file.name.startsWith(ZIP_PREFIX) && file.name.endsWith(ZIP_SUFFIX) }
            .orEmpty()
            .filter { it.lastModified() < before }
            .forEach { it.delete() }
    }

    override fun dismissFailure() {
        state.compareAndSet(ExportState.Failed, ExportState.Idle)
    }

    override fun dismissCrash() = offer.dismiss()

    override fun crashShown() {
        scope.launch(io) { offer.shown() }
    }

    private fun build(core: Result<CoreDiagnostics>, engineView: EngineView): ExportState.Ready {
        val listing = core.getOrNull()
        val names = listing?.crashes?.map { it.name } ?: crashes.list().map { it.name }
        // A file gone since the listing is left out, and so is its name; so is one still empty, a name a writer claimed
        // and has not filled yet, which a clear by that name would remove with the crash it is about to hold.
        val files = names.mapNotNull { name -> crashes.read(name)?.takeIf { it.isNotEmpty() }?.let { name to it } }
        val at = clock()
        val json = reportJson(at, app(), engineView, core, AppEvents.snapshot(), files.map { it.first })
        val zip = newZip(STAMP.format(Instant.ofEpochMilli(at)))
        try {
            writeZip(zip, json, files)
        } catch (e: IOException) {
            zip.delete()
            throw e
        }
        val cleared = listing?.let { l -> files.map { it.first }.takeIf { it.isNotEmpty() }?.let { ExportedCrashes(it, l.crashMark) } }
        return ExportState.Ready(zip, cleared)
    }

    /** A new, empty file `doona-report-<stamp>.zip`, or `-2`, `-3` and on when an export in the same second has the name. */
    private fun newZip(stamp: String): File {
        val dir = outDir().apply { mkdirs() }
        var n = 1
        while (true) {
            val zip = File(dir, ZIP_PREFIX + stamp + (if (n == 1) "" else "-$n") + ZIP_SUFFIX)
            if (zip.createNewFile()) return zip
            n++
        }
    }

    companion object {
        const val REPORT_ENTRY = "report.json"
        const val CRASH_DIR = "crash/"

        /**
         * How long a shared zip is kept after the share sheet returns: the app picked may read it only when it sends,
         * as a mail draft does. A zip that outlives the process is removed by the next run's [sweep].
         */
        const val SHARE_GRACE_MS = 10 * 60_000L
        private const val ZIP_PREFIX = "doona-report-"
        private const val ZIP_SUFFIX = ".zip"
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
