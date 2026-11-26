package dev.doona.android.feature.activity

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.Mode
import dev.doona.android.engine.Status
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.engine.fake.rvSampleEngine
import dev.doona.android.prototype.rv.RV_EN
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.RvActivityScreenSample
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.rvAnimatorScale
import dev.doona.android.prototype.rv.rvContent
import dev.doona.android.prototype.rv.rvRowRules
import dev.doona.android.prototype.rv.rvSampleActivity
import dev.doona.android.prototype.rv.rvShot
import dev.doona.android.testing.drawFully
import dev.doona.android.vpn.ActivityHonkFixtures
import dev.doona.android.vpn.ActivityHonkFixtures.allUnavailable
import dev.doona.android.vpn.ActivityHonkFixtures.empty
import dev.doona.android.vpn.ActivityHonkFixtures.idle
import dev.doona.android.vpn.ActivityHonkFixtures.refusingProbes
import dev.doona.android.vpn.FakeHonk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneOffset

/**
 * 活動 as the app draws it: the RV sample as honk serves it ([ActivityHonkFixtures.sample]), with [rvSampleEngine]'s
 * tunnel and mode, through [ActivityViewModel] to the page ([ActivityPage]), at the sample's
 * 10:24 UTC. Goldens `app-rv-activity-*`, beside the prototype's `proto-rv-activity-*`: connected in light, dark, fs200
 * and English, idle, Global mode and loading in light and dark, and each notice the engine can raise, starting and
 * stopping in light. A notice's engine is disconnected,
 * with no traffic, as the prototype's notices are.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class ActivityRvShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @After
    fun restoreScale() = rvAnimatorScale(1f)

    @Test fun activityLight() = shot("activity")

    @Test fun activityDark() = shot("activity", dark = true)

    @Test fun activityFs200() = shot("activity", fontScale = RV_FS200)

    @Test
    @Config(qualifiers = RV_EN)
    fun activityEn() = shot("activity-en")

    @Test
    @Config(qualifiers = FULL_PAGE)
    fun activityFullLight() = shot("activity-full", whole = true)

    @Test
    @Config(qualifiers = FULL_PAGE)
    fun activityFullDark() = shot("activity-full", dark = true, whole = true)

    @Test
    @Config(qualifiers = FULL_PAGE_FS200)
    fun activityFullFs200() = shot("activity-full", fontScale = RV_FS200, whole = true)

    @Test
    @Config(qualifiers = FULL_PAGE_EN)
    fun activityFullEn() = shot("activity-full-en", whole = true)

    /** The prototype's whole page in English, which `proto-rv-activity-full-*` lacks: the approved side of the en panel. */
    @Test
    @Config(qualifiers = FULL_PAGE_EN)
    fun prototypeFullEn() = compose.rvShot("activity-full-en", dark = false, before = { assertWhole() }) {
        RvActivityScreenSample(rvSampleActivity(), onAction = {}, onConnect = {})
    }

    @Test fun idleLight() = shot("activity-idle") { stop(Status.Stopped) }

    @Test fun idleDark() = shot("activity-idle", dark = true) { stop(Status.Stopped) }

    @Test fun globalLight() = shot("activity-global") { runBlocking { setMode(Mode.Global) } }

    @Test fun globalDark() = shot("activity-global", dark = true) { runBlocking { setMode(Mode.Global) } }

    /** Before the engine is ready the page waits under its skeleton, disconnected as `proto-rv-activity-loading-*` is. */
    @Test fun loadingLight() = shot("activity-loading") { loading() }

    @Test fun loadingDark() = shot("activity-loading", dark = true) { loading() }

    @Test fun startingLight() = shot("activity-starting") { stop(Status.Starting) }

    @Test fun stoppingLight() = shot("activity-stopping") { stop(Status.Stopping) }

    @Test fun failedStartLight() = shot("activity-failed-start") { stop(Status.Failed(FailureReason.StartFailed)) }

    @Test fun failedNoAppsLight() = shot("activity-failed-no-apps") { stop(Status.Failed(FailureReason.NoAllowedApps)) }

    @Test fun failedIncompatibleLight() = shot("activity-failed-incompatible") { stop(Status.Failed(FailureReason.IncompatibleCore)) }

    @Test fun failedNoNodesLight() = shot("activity-failed-no-nodes") { stop(Status.Failed(FailureReason.StartFailed, ErrorCode.NoNodes)) }

    @Test fun coreStoppedLight() = shot("activity-core-stopped") { stop(Status.Failed(FailureReason.CoreStopped)) }

    @Test fun vpnTakenLight() = shot("activity-vpn-taken") { stop(Status.Failed(FailureReason.Revoked)) }

    @Test fun lockdownLight() = shot("activity-lockdown") { stop(Status.Failed(FailureReason.LockdownEnabled)) }

    @Test fun crashLight() = shot("activity-crash", crash = true) { stop(Status.Stopped) }

    @Test fun offlineLight() = shot("activity-offline", signals = ActivitySignals(online = false)) { stop(Status.Stopped) }

    @Test fun consentDeniedLight() = shot("activity-consent-denied", signals = ActivitySignals(vpnRefused = true)) { stop(Status.Stopped) }

    @Test fun notifyDeniedLight() = shot("activity-notify-denied", signals = ActivitySignals(notifyRefused = true)) { stop(Status.Stopped) }

    /** A latency test the engine refused: its banner (無法測試延遲, 重試) stands where the notice would. */
    @Test fun failedTestLight() = shot("activity-failed-test", failTest = true)

    @Test fun noNodeLight() = shot("activity-no-node", honk = { allUnavailable() }) { stop(Status.Stopped) }

    @Test fun emptyLight() = shot("activity-empty", honk = { empty() }) { stop(Status.Stopped) }

    /** The engine before it is ready, disconnected. */
    private fun FakeEngine.loading() {
        stop(Status.Stopped)
        ready.value = false
    }

    /** The tunnel at [status]; not running, honk has no traffic ([ActivityHonkFixtures.idle]). */
    private fun FakeEngine.stop(status: Status) {
        this.status.value = status
    }

    private fun shot(
        screen: String,
        dark: Boolean = false,
        fontScale: Float = 1f,
        crash: Boolean = false,
        whole: Boolean = false,
        failTest: Boolean = false,
        signals: ActivitySignals = ActivitySignals(),
        honk: FakeHonk.() -> Unit = {},
        change: FakeEngine.() -> Unit = {},
    ) {
        val engine = rvSampleEngine().apply(change)
        val api = ActivityHonkFixtures.sample().apply(honk)
        if (engine.status.value !is Status.Running) api.idle()
        if (failTest) api.refusingProbes()
        // The busy bar rests half full at animator scale 0, as in the prototype's starting and stopping goldens.
        if (engine.status.value == Status.Starting || engine.status.value == Status.Stopping) rvAnimatorScale(0f)
        val refusals = ConnectRefusals().apply {
            vpn.value = signals.vpnRefused
            notifications.value = signals.notifyRefused
        }
        val model = ActivityViewModel(engine, api, MutableStateFlow(crash), Dispatchers.Unconfined, { RvData.nowEpochMs }, { ZoneOffset.UTC }, flowOf(signals.online), refusals, io = Dispatchers.Unconfined)
        model.load()
        if (failTest) model.onAction(ActivityAction.TestLatency)
        compose.rvContent(dark, fontScale) { ActivityPage(model, crash = null, onConnect = {}, routes = activityTestRoutes()) }
        compose.drawFully()
        if (whole) compose.assertWhole()
        val variant = when {
            fontScale != 1f -> "fs${(fontScale * PERCENT).toInt()}"
            dark -> "dark"
            else -> "light"
        }
        val name = "app-rv-$screen-$variant"
        compose.rvRowRules(name)
        compose.onRoot().captureToImage().asAndroidBitmap().captureRoboImage("$SHOTS/$name.png")
    }

    private companion object {
        const val PERCENT = 100

        /** Screens as tall as the whole 活動 page, as the prototype's `activity-full` captures use. */
        const val FULL_PAGE = "+h1951dp"
        const val FULL_PAGE_FS200 = "+h3118dp"

        /** [RV_EN] as tall as the English page, whose longer words wrap to more lines than the Chinese ones. */
        const val FULL_PAGE_EN = "en-w412dp-h2049dp-notnight-xhdpi"
        val SHOTS: String = System.getProperty("doona.shots") ?: "src/test/screenshots"
    }
}
