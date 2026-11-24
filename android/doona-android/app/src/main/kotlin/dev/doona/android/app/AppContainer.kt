package dev.doona.android.app

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteException
import android.os.Build
import android.os.SystemClock
import androidx.work.WorkManager
import dev.doona.android.data.network.networkState
import dev.doona.android.data.network.ownAddresses
import dev.doona.android.data.settings.AppSettings
import dev.doona.android.data.settings.LogLevelController
import dev.doona.android.engine.Engine
import dev.doona.android.engine.InstalledApps
import dev.doona.android.feature.localnetwork.LocalNetworkAccess
import dev.doona.android.feature.localnetwork.LocalNetworkEngine
import dev.doona.android.feature.subscriptions.SubscriptionImports
import dev.doona.android.report.AppInfo
import dev.doona.android.report.AppReportExporter
import dev.doona.android.report.CrashFiles
import dev.doona.android.report.CrashOffer
import dev.doona.android.report.ExportTokens
import dev.doona.android.report.ReportExport
import dev.doona.android.report.ReportExporter
import dev.doona.android.report.reportDir
import dev.doona.android.vpn.VpnLog
import dev.doona.android.work.ForegroundRefresh
import dev.doona.android.work.GeoRefresh
import dev.doona.android.work.SubscriptionRefresh
import dev.doona.android.work.scheduleGeoRefresh
import dev.doona.android.work.scheduleGeoRefreshOnConnect
import dev.doona.android.work.scheduleSubscriptionRefresh
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
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

    val logLevels: LogLevelController by lazy { LogLevelController({ engine.setLogLevel(it) }, uiScope, SystemClock::elapsedRealtime) }

    val networkState by lazy { networkState(application, uiScope) }

    internal val subscriptionRefresh by lazy { SubscriptionRefresh(AppSettings.get(application), { engine }) }

    internal fun startSubscriptionRefresh() {
        application.registerActivityLifecycleCallbacks(ForegroundRefresh { uiScope.launch { subscriptionRefresh.run() } })
        uiScope.launch { subscriptionRefresh.watchConnections() }
        uiScope.launch {
            AppSettings.get(application).subscriptionUpdate
                .catch {
                    if (it !is IOException) throw it
                    VpnLog.warn("subscription-refresh: settings read failed (${it.javaClass.simpleName})")
                }.collect {
                    try {
                        scheduleSubscriptionRefresh(WorkManager.getInstance(application), it)
                    } catch (e: SQLiteException) {
                        // A scheduler database error must not disable the foreground and connection checks.
                        VpnLog.warn("subscription-refresh: schedule write failed (${e.javaClass.simpleName})")
                    }
                }
        }
    }

    internal val geoRefresh by lazy { GeoRefresh(AppSettings.get(application)) { engine } }

    internal fun startGeoRefresh() {
        uiScope.launch {
            geoRefresh.watchConnections { scheduleGeo { scheduleGeoRefreshOnConnect(it) } }
        }
        uiScope.launch {
            AppSettings.get(application).geoUpdateInterval
                .catch {
                    if (it !is IOException) throw it
                    VpnLog.warn("geo-refresh: settings read failed (${it.javaClass.simpleName})")
                }.collect { setting ->
                    scheduleGeo { scheduleGeoRefresh(it, setting) }
                }
        }
    }

    private suspend fun scheduleGeo(schedule: suspend (WorkManager) -> Unit) {
        try {
            schedule(WorkManager.getInstance(application))
        } catch (e: SQLiteException) {
            VpnLog.warn("geo-refresh: schedule write failed (${e.javaClass.simpleName})")
        }
    }

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
     * The diagnostic report export, process-wide. The exports a share sheet may still call back for are kept beside the
     * crash files, so a pick reaches them after a restart.
     */
    val reports: ReportExport by lazy {
        val tokens = ExportTokens { File(application.noBackupFilesDir, "report-exports.json") }
        ReportExport(
            engine,
            crashFiles,
            crashOffer,
            { reportDir(application) },
            tokens,
            { AppInfo.of(application) },
            uiScope,
            Dispatchers.IO,
            logLevels,
            ownAddresses = { ownAddresses(application) },
        )
    }

    /** The diagnostic report for a page to export and share, and the crash offer; no UI of its own. */
    val reportExporter: ReportExporter by lazy { AppReportExporter(application, reports, Dispatchers.IO) }

    private val launched = AtomicBoolean(false)

    /**
     * Looks for a crash to offer the report for, until a look in this process could read the crash files: one that could
     * not leaves it to the next call. Once per process, also removes what earlier runs left, parts of crash files and old
     * report zips, and sends again the clears of picks that did not succeed; not again for an activity created later,
     * whose share sheet may hold a zip. Reads the disk on another thread, the job returned.
     */
    fun checkCrashes(): Job {
        val first = launched.compareAndSet(false, true)
        return uiScope.launch(Dispatchers.IO) {
            crashOffer.check()
            if (first) {
                crashFiles.sweep()
                reports.sweep()
            }
        }
    }

    /** Below Android 17 no permission guards the local network. */
    private fun localNetworkGranted(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN ||
        application.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
}
