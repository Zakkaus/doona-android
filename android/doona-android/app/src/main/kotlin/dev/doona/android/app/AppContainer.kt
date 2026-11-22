package dev.doona.android.app

import android.app.Application
import dev.doona.android.engine.Engine
import dev.doona.android.engine.fake.FakeEngine

/**
 * Process-wide objects, created once by [DoonaApplication] (manual dependency injection, ARCH D6). Activities,
 * ViewModel factories, the VPN service and the tile all take what they need from here.
 */
class AppContainer(val application: Application) {
    /** Replaced by the service-backed engine when the VPN lane lands. */
    val engine: Engine by lazy { FakeEngine() }
}
