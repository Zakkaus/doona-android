package dev.doona.android.app

import android.app.Application
import dev.doona.android.engine.Engine
import dev.doona.android.vpn.ServiceEngine

open class DoonaApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this, ::createEngine)
    }

    /** The service-backed engine; JVM tests run the app with a fake one instead. */
    protected open fun createEngine(): Engine = ServiceEngine.create(this)
}
