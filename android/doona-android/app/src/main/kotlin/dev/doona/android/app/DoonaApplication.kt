package dev.doona.android.app

import android.app.Application
import androidx.work.Configuration
import dev.doona.android.data.apps.PackageInstalledApps
import dev.doona.android.engine.Engine
import dev.doona.android.engine.InstalledApps
import dev.doona.android.report.AppInfo
import dev.doona.android.report.CrashCapture
import dev.doona.android.vpn.ServiceEngine

open class DoonaApplication : Application(), Configuration.Provider {
    // WorkManager starts on first use from onCreate, also where no content provider runs (Robolectric).
    override val workManagerConfiguration: Configuration get() = Configuration.Builder().build()

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this, ::createEngine, ::createInstalledApps)
        // First, so a crash anywhere later in the process leaves its file for the report.
        CrashCapture.install(container.crashFiles, AppInfo.of(this).crashVersion)
        container.startFlowCapture()
        container.startSubscriptionRefresh()
        container.startGeoRefresh()
    }

    /** The service-backed engine; JVM tests run the app with a fake one instead. */
    protected open fun createEngine(): Engine = ServiceEngine.create(
        this,
        endDebugCapture = { container.logLevels.endCapture() },
        expireDebugCapture = { container.logLevels.expireIfDue() },
    )

    protected open fun createInstalledApps(): InstalledApps = PackageInstalledApps(this)
}
