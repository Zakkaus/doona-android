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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The crash files an export holds, which a clear after a pick in the share sheet removes: their [names] and the listing's [mark]. */
data class ExportedCrashes(val names: List<String>, val mark: Long)

/** Where the export is: from the tap to the share sheet's return. */
sealed interface ExportState {
    data object Idle : ExportState

    /** The zip is being written. */
    data object Building : ExportState

    /**
     * The zip at [file] is written; the activity shares it. [crashes] are cleared once the user picks an app for it, in
     * the sheet whose callback carries [token].
     */
    data class Ready(val file: File, val crashes: ExportedCrashes?, val token: String) : ExportState

    /** The share sheet for [file], of the export [token], is open. */
    data class Sharing(val file: File, val token: String) : ExportState

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
 * shares through the share sheet. Nothing is sent anywhere: the user picks where it goes. Each export has a token, kept
 * in [tokens] with the names and the mark of the core's listing before its sheet opens, which its sheet's callback
 * carries; once that sheet reports that the user picked an app ([picked]), those crash files are cleared, so a crash
 * written meanwhile stays, and a late pick in an earlier sheet clears only its own export's. A clear that does not
 * succeed is sent again at the next start ([sweep]). A sheet closed without a pick keeps them. Without the core's
 * listing (the core is unavailable) the zip takes the app's own, and nothing is cleared. An app crash file goes into the
 * zip only in the current format, its lines checked again ([exportedCrash]); `report.json` names only the files the zip
 * holds, and a pick clears only those. Process-wide, so a build in progress outlives the page that started it. Each zip
 * is written under a part name and then takes a name of its own; one the sheet took is deleted [SHARE_GRACE_MS] after the
 * sheet returns, which leaves the app picked time to read it, and one it did not take at once; [sweep] removes what an
 * earlier run left.
 */
class ReportExport(
    private val engine: Engine,
    private val crashes: CrashFiles,
    private val offer: CrashOffer,
    private val outDir: () -> File,
    private val tokens: ExportTokens,
    private val app: () -> AppInfo,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
) : Reports {
    private val state = MutableStateFlow<ExportState>(ExportState.Idle)

    /** Held while a zip is written and published, and while [sweep] removes parts: it never takes one being written. */
    private val zips = Any()

    /** One clear at a time, so a pick reported twice, or one that comes while a start sends the pending ones, clears once. */
    private val clearing = Mutex()
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
        return ready.takeIf { state.compareAndSet(ready, ExportState.Sharing(ready.file, ready.token)) }
    }

    /** The share sheet did not open because the activity ended first, as on rotation: the next one shares [ready]. */
    fun putBack(ready: ExportState.Ready) {
        state.compareAndSet(ExportState.Sharing(ready.file, ready.token), ready)
    }

    /**
     * The user picked an app in the share sheet of the export [token] (the sheet's callback, [isPick]), in this process
     * or an earlier one: clears the crash files that export holds, once, then calls [done]. A pick does not prove the app
     * sent the zip on, but it is the only answer the sheet gives; its activity result says nothing, and closing the sheet
     * clears nothing.
     */
    fun picked(token: String, done: () -> Unit = {}) {
        scope.launch {
            try {
                // Noted before waiting for a clear in progress: a process that ends meanwhile leaves it to the next start.
                val crashes = withContext(io) { tokens.picked(token) } ?: return@launch
                clearing.withLock {
                    // A pick reported twice: the first clear may have succeeded meanwhile, and forgotten the export.
                    if (withContext(io) { tokens.kept(token) }) clear(token, crashes)
                }
            } finally {
                done()
            }
        }
    }

    /** Sends the clear of the export [token]. The export is forgotten only once the clear succeeds; until then each start sends it again. */
    private suspend fun clear(token: String, crashes: ExportedCrashes) {
        if (engine.clearCrashes(crashes.names, crashes.mark).isSuccess) withContext(io) { tokens.forget(token) }
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
        scope.launch(io) {
            sharing.file.delete()
            tokens.forget(sharing.token)
        }
    }

    /**
     * Once per process, at its start: removes what an earlier run left, every zip it was writing and the zips older than
     * [SHARE_GRACE_MS], and sends again the clears picks asked for that did not succeed, idempotent as they are. It reads
     * the disk, so call it off the main thread.
     */
    fun sweep() {
        val before = clock() - SHARE_GRACE_MS
        synchronized(zips) {
            outDir().listFiles { file -> file.isPart() || (file.isZip() && file.lastModified() < before) }.orEmpty().forEach { it.delete() }
        }
        scope.launch {
            clearing.withLock {
                for ((token, crashes) in withContext(io) { tokens.pending() }) clear(token, crashes)
            }
        }
    }

    private fun File.isPart() = name.startsWith(PART_PREFIX) && name.endsWith(PART_SUFFIX)

    private fun File.isZip() = name.startsWith(ZIP_PREFIX) && name.endsWith(ZIP_SUFFIX)

    override fun dismissFailure() {
        state.compareAndSet(ExportState.Failed, ExportState.Idle)
    }

    override fun dismissCrash() = offer.dismiss()

    override fun crashShown() {
        scope.launch(io) { offer.shown() }
    }

    /** The zip, and the export's token saved before any sheet can open for it: a token that cannot be saved fails the export. */
    private fun build(core: Result<CoreDiagnostics>, engineView: EngineView): ExportState.Ready {
        val listing = core.getOrNull()
        val names = listing?.crashes?.map { it.name } ?: crashes.list().map { it.name }
        // Left out, with their names: a file gone since the listing, an empty one, which is no crash file, and an app file
        // of an earlier format.
        val known = KnownNames()
        val files = names.mapNotNull { name -> crashes.read(name)?.let { exportedCrash(it, known) }?.let { name to it } }
        val at = clock()
        val json = reportJson(at, app(), engineView, core, AppEvents.snapshot(), files.map { it.first })
        val zip = publishZip(STAMP.format(Instant.ofEpochMilli(at)), json, files)
        val token = UUID.randomUUID().toString()
        val cleared = listing?.let { l -> files.map { it.first }.takeIf { it.isNotEmpty() }?.let { ExportedCrashes(it, l.crashMark) } }
        try {
            cleared?.let { tokens.add(token, it) }
        } catch (e: IOException) {
            zip.delete()
            throw e
        }
        return ExportState.Ready(zip, cleared, token)
    }

    /**
     * Writes the zip under a part name of its own, then renames it `doona-report-<stamp>.zip`, or `-2`, `-3` and on when
     * an export in the same second has the name: no zip under that name is ever half written.
     */
    private fun publishZip(stamp: String, json: String, files: List<Pair<String, ByteArray>>): File = synchronized(zips) {
        val dir = outDir().apply { mkdirs() }
        val part = File.createTempFile(PART_PREFIX, PART_SUFFIX, dir)
        try {
            writeZip(part, json, files)
            val zip = generateSequence(1) { it + 1 }
                .map { n -> File(dir, ZIP_PREFIX + stamp + (if (n == 1) "" else "-$n") + ZIP_SUFFIX) }
                .first { !it.exists() }
            if (!part.renameTo(zip)) throw IOException("not renamed")
            zip
        } catch (e: IOException) {
            part.delete()
            throw e
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

        /** A zip being written: `.doona-report-<random>.part`. */
        private const val PART_PREFIX = ".doona-report-"
        private const val PART_SUFFIX = ".part"
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
