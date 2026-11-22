package dev.doona.android.app

import dev.doona.android.engine.Engine
import dev.doona.android.engine.fake.FakeEngine

/** Runs the app on [FakeEngine], so activity tests never load the native core. */
class FakeEngineApplication : DoonaApplication() {
    override fun createEngine(): Engine = FakeEngine()
}
