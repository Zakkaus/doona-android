package dev.doona.android.report

import dev.doona.android.data.settings.LogLevelController
import dev.doona.android.engine.CoreDiagnostics
import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The crash files an export holds, which a clear after a pick in the share sheet removes: their [names] and the listing's [mark]. */
data class ExportedCrashes(val names: List<String>, val mark: Long)

/**
 * A report written and ready to share: the zip at [file], the export's [token], which its share sheet's callback
 * carries, and the crash files a pick in that sheet clears ([crashes]; none without the core's listing).
 */
data class ReportZip(val file: File, val token: String, val crashes: ExportedCrashes?, val issueLink: String?)

/**
 * The diagnostic report export: a zip in [outDir] of `report.json` ([reportJson]) and the crash files, which a share
 * sheet sends ([ReportExporter]). Nothing is sent anywhere: the user picks where it goes. Each export has a token, kept
 * in [tokens] with the names and the mark of the core's listing before its sheet opens, which its sheet's callback
 * carries; once that sheet reports that the user picked an app ([picked]), those crash files are cleared, so a crash
 * written meanwhile stays, and a late pick in an earlier sheet clears only its own export's. A clear that does not
 * succeed is sent again at the next start ([sweep]). A sheet closed without a pick keeps them. Without the core's
 * listing (the core is unavailable) the zip takes the app's own, and nothing is cleared. An app crash file goes into the
 * zip only in the current format, its lines checked again; `report.json` names only the files the zip
 * holds, and a pick clears only those. Each zip is written under a part name and then takes a name of its own; one a
 * sheet took is deleted [SHARE_GRACE_MS] after the sheet returns ([returned]), which leaves the app picked time to read
 * it, and one no sheet took at once ([notOpened]); [sweep] removes what an earlier run left. Android-free; the share
 * sheet is [ReportExporter]'s.
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
    private val logLevels: LogLevelController,
    private val ownAddresses: () -> List<InetAddress> = { emptyList() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Held while a zip is written and published, and while [sweep] removes parts: it never takes one being written. */
    private val zips = Any()

    /** One clear at a time, so a pick reported twice, or one that comes while a start sends the pending ones, clears once. */
    private val clearing = Mutex()

    /** True while a crash waits to be reported ([CrashOffer]). */
    val crashOffered: StateFlow<Boolean> get() = offer.offered

    /**
     * Writes the report and keeps its token, before any sheet can open for it: null when the zip or the token could not
     * be written. Takes the crash offer down. The expiry check and the post-write reset never hold up the export.
     */
    suspend fun build(options: RedactionOptions = RedactionOptions(), failure: ReportFailure? = null): ReportZip? {
        offer.dismiss()
        logLevels.expireIfDue()
        val core = engine.diagnostics()
        val events = AppEvents.snapshot()
        val catalog = engine.catalog.value
        val members = catalog?.nodeMembers().orEmpty()
        val endpoints = if (options.showNodeEndpoints && members.isNotEmpty()) {
            engine.nodes(members.take(MAX_LISTED_NODES).map { it.id }).getOrNull().orEmpty().associateBy { it.id }
        } else {
            emptyMap()
        }
        val engineView = EngineView(engine.status.value, engine.settings.value, catalog, members, endpoints)
        val code = (core.exceptionOrNull() as? EngineException)?.code?.name
        return try {
            withContext(io) {
                val addresses = if (options.showOwnAddresses) ownAddresses() else emptyList()
                write(core, engineView, events, addresses, failure).also {
                    logLevels.endCapture()
                    AppEvents.action("export", "write", if (core.isSuccess) "ok" else "partial", code ?: "ok")
                }
            }
        } catch (_: IOException) {
            AppEvents.action("export", "write", "failed", "IOException")
            null
        }
    }

    /** The share sheet for [zip] returned, whatever the user did there. The zip goes after [SHARE_GRACE_MS]. */
    fun returned(zip: ReportZip) {
        scope.launch(io) {
            delay(SHARE_GRACE_MS)
            zip.file.delete()
        }
    }

    /** No share sheet opened for [zip]: nothing was shared, so nothing is cleared, and the zip goes. */
    fun notOpened(zip: ReportZip) {
        scope.launch(io) {
            zip.file.delete()
            tokens.forget(zip.token)
        }
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

    fun dismissCrash() = offer.dismiss()

    /** The crash offer reached the screen, so it is not made again for the same crash ([CrashOffer.shown]). */
    fun crashShown() {
        scope.launch(io) { offer.shown() }
    }

    /** The zip, and the export's token saved before any sheet can open for it: a token that cannot be saved fails the export. */
    private fun write(
        core: Result<CoreDiagnostics>,
        engineView: EngineView,
        events: List<AppEvent>,
        addresses: List<InetAddress>,
        failure: ReportFailure?,
    ): ReportZip {
        val scrubber = ReportScrubber()
        val listing = core.getOrNull()
        val names = listing?.crashes?.map { it.name } ?: crashes.list().map { it.name }
        // Left out, with their names: a file gone since the listing, an empty one, which is no crash file, and an app file
        // of an earlier format.
        val known = KnownNames()
        val files = names.mapNotNull { name -> crashes.read(name)?.let { scrubber.crash(it, known) }?.let { name to it } }
        val at = clock()
        val held = files.map { it.first }
        val appInfo = app()
        val diagnostics = scrubber.diagnostics(core, held)
        val json = reportJson(at, appInfo, engineView, diagnostics, events, held, addresses, scrubber)
        val issueLink = failure?.let {
            val coreVersion = ((diagnostics["core"] as? JsonObject)?.get("version") as? JsonPrimitive)?.content
            GitHubIssueLink.build(it, appInfo, coreVersion, events, scrubber)
        }
        val zip = publishZip(STAMP.format(Instant.ofEpochMilli(at)), json, files)
        val token = UUID.randomUUID().toString()
        val cleared = listing?.let { l -> held.takeIf { it.isNotEmpty() }?.let { ExportedCrashes(it, l.crashMark) } }
        try {
            cleared?.let { tokens.add(token, it) }
        } catch (e: IOException) {
            zip.delete()
            throw e
        }
        return ReportZip(zip, token, cleared, issueLink)
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
