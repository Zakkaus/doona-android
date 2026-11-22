package dev.doona.android.app

import android.app.Application
import dev.doona.android.engine.Engine

/**
 * Process-wide objects, created once by [DoonaApplication] (manual dependency injection, ARCH D6). Activities,
 * ViewModel factories, the VPN service and the tile all take what they need from here.
 */
class AppContainer(val application: Application, createEngine: () -> Engine) {
    /** Created on first use, so a process started for the tile or the service builds it once. */
    val engine: Engine by lazy(createEngine)
}
