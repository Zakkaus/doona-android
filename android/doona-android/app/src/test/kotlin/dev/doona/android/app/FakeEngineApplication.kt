package dev.doona.android.app

import dev.doona.android.engine.Engine
import dev.doona.android.engine.InstalledApps
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.engine.fake.FakeInstalledApps

/** Runs the app on [FakeEngine], so activity tests never load the native core. */
class FakeEngineApplication : DoonaTestApplication() {
    override fun createEngine(): Engine = FakeEngine()

    override fun createInstalledApps(): InstalledApps = FakeInstalledApps()
}
