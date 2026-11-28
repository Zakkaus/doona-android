package dev.doona.android.app

import dev.doona.android.engine.Engine
import dev.doona.android.engine.InstalledApps
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.engine.fake.FakeInstalledApps
import dev.doona.android.honk.NativeApi
import dev.doona.android.honk.SettingsHonk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Runs the app on [FakeEngine] and a honk that also answers 設定's reads and writes, so activity tests never load the native core. */
class FakeEngineApplication : DoonaTestApplication() {
    override val io: CoroutineDispatcher get() = Dispatchers.Unconfined

    override fun createEngine(): Engine = FakeEngine()

    override fun createInstalledApps(): InstalledApps = FakeInstalledApps()

    override fun createNativeApi(): NativeApi = SettingsHonk.running(others = super.createNativeApi())
}
