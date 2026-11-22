package dev.doona.android.app

import android.app.Application
import dev.doona.android.data.apps.PackageInstalledApps
import dev.doona.android.engine.Engine
import dev.doona.android.engine.InstalledApps
import dev.doona.android.vpn.ServiceEngine

open class DoonaApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this, ::createEngine, ::createInstalledApps)
    }

    /** The service-backed engine; JVM tests run the app with a fake one instead. */
    protected open fun createEngine(): Engine = ServiceEngine.create(this)

    protected open fun createInstalledApps(): InstalledApps = PackageInstalledApps(this)
}
