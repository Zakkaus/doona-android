package dev.doona.android.app

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import dev.doona.android.engine.Engine
import dev.doona.android.engine.InstalledApps
import dev.doona.android.feature.localnetwork.LocalNetworkAccess
import dev.doona.android.feature.localnetwork.LocalNetworkEngine
import dev.doona.android.feature.subscriptions.SubscriptionImports
import dev.doona.android.report.AppInfo
import dev.doona.android.report.CrashFiles
import dev.doona.android.report.CrashOffer
import dev.doona.android.report.ExportTokens
import dev.doona.android.report.ReportExport
import dev.doona.android.report.reportDir
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide objects, created once by [DoonaApplication] (manual dependency injection, ARCH D6). Activities,
 * ViewModel factories, the VPN service and the tile all take what they need from here.
 */
class AppContainer(val application: Application, createEngine: () -> Engine, createInstalledApps: () -> InstalledApps) {
    /** Created on first use, so a process started for the tile or the service builds it once. */
    val engine: Engine by lazy(createEngine)

    /** The apps per-app routing can name; read on demand, so nothing queries the device until that page opens. */
    val installedApps: InstalledApps by lazy(createInstalledApps)

    /** Outlives every screen: imports and local network prompts that a page started go on when it is left. */
    private val uiScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }

    /** The local network permission, asked for only when something on the local network failed (ARCH D26). */
    val localNetwork: LocalNetworkAccess by lazy { LocalNetworkAccess(::localNetworkGranted) }

    /**
     * The engine the screens use: [engine], asking for the local network permission where an action failed for want
     * of it. The service and the tile use [engine] itself.
     */
    val uiEngine: Engine by lazy { LocalNetworkEngine(engine, localNetwork, uiScope).also { it.watch() } }

    /** The subscription import in flight, above every screen that can start one: a slow fetch survives leaving the page. */
    val imports: SubscriptionImports by lazy { SubscriptionImports(uiEngine, uiScope) }

    /**
     * The crash files of the core and the app: the core's `crash/` directory under the data directory it is given. The
     * directories are found on first use, off the main thread: asking for one makes it.
     */
    val crashFiles: CrashFiles by lazy { CrashFiles({ File(application.noBackupFilesDir, "crash") }) }

    /** The report offered after a crash, once per crash; the number it last offered is kept beside the crash files. */
    val crashOffer: CrashOffer by lazy { CrashOffer(crashFiles) { File(application.noBackupFilesDir, "crash-offered") } }

    /**
     * The diagnostic report export, above every screen: a zip being written survives leaving the page. The exports a
     * share sheet may still call back for are kept beside the crash files, so a pick reaches them after a restart.
     */
    val reports: ReportExport by lazy {
        val tokens = ExportTokens { File(application.noBackupFilesDir, "report-exports.json") }
        ReportExport(engine, crashFiles, crashOffer, { reportDir(application) }, tokens, { AppInfo.of(application) }, uiScope, Dispatchers.IO)
    }

    private val launched = AtomicBoolean(false)

    /**
     * Once per process: looks for a crash to offer the report for, removes what earlier runs left, parts of crash files
     * and old report zips, and sends again the clears of picks that did not succeed. Not again for an activity created
     * later, whose share sheet may hold a zip. Reads the disk on another thread.
     */
    fun checkCrashes() {
        if (!launched.compareAndSet(false, true)) return
        uiScope.launch(Dispatchers.IO) {
            crashOffer.check()
            crashFiles.sweep()
            reports.sweep()
        }
    }

    /** Below Android 17 no permission guards the local network. */
    private fun localNetworkGranted(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN ||
        application.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
}
