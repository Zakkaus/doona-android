package dev.doona.android.app

import android.app.Application
import dev.doona.android.engine.Engine
import dev.doona.android.engine.InstalledApps
import dev.doona.android.feature.subscriptions.SubscriptionImports
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-wide objects, created once by [DoonaApplication] (manual dependency injection, ARCH D6). Activities,
 * ViewModel factories, the VPN service and the tile all take what they need from here.
 */
class AppContainer(val application: Application, createEngine: () -> Engine, createInstalledApps: () -> InstalledApps) {
    /** Created on first use, so a process started for the tile or the service builds it once. */
    val engine: Engine by lazy(createEngine)

    /** The apps per-app routing can name; read on demand, so nothing queries the device until that page opens. */
    val installedApps: InstalledApps by lazy(createInstalledApps)

    /** The subscription import in flight, above every screen that can start one: a slow fetch survives leaving the page. */
    val imports: SubscriptionImports by lazy { SubscriptionImports(engine, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)) }
}
