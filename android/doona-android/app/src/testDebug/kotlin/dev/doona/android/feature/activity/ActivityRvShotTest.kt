package dev.doona.android.feature.activity

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.doona.android.designsystem.rv.RvPlace
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.Status
import dev.doona.android.engine.Traffic
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.engine.fake.rvSampleEngine
import dev.doona.android.prototype.rv.RV_EN
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.rvAnimatorScale
import dev.doona.android.prototype.rv.rvContent
import dev.doona.android.prototype.rv.rvRowRules
import dev.doona.android.testing.drawFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneOffset

/**
 * 活動 as the app draws it: [rvSampleEngine] through [ActivityViewModel] to the page ([ActivityPage]), at the sample's
 * 10:24 UTC. Goldens `app-rv-activity-*`, beside the prototype's `proto-rv-activity-*`: connected in light, dark, fs200
 * and English, and each notice the engine can raise, starting and stopping in light. A notice's engine is disconnected,
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

    @Test
    fun noNodeLight() = shot("activity-no-node") {
        stop(Status.Stopped)
        groups.value = groups.value.map { group -> group.copy(members = group.members.map { if (it.isGroup) it else it.copy(latencyMs = null, failed = true) }) }
    }

    @Test
    fun emptyLight() = shot("activity-empty") {
        stop(Status.Stopped)
        subscriptions.value = emptyList()
    }

    /** The engine at [status], with no traffic. */
    private fun FakeEngine.stop(status: Status) {
        this.status.value = status
        traffic.value = Traffic()
    }

    private fun shot(screen: String, dark: Boolean = false, fontScale: Float = 1f, crash: Boolean = false, change: FakeEngine.() -> Unit = {}) {
        val engine = rvSampleEngine().apply(change)
        // The busy bar rests half full at animator scale 0, as in the prototype's starting and stopping goldens.
        if (engine.status.value == Status.Starting || engine.status.value == Status.Stopping) rvAnimatorScale(0f)
        val model = ActivityViewModel(engine, MutableStateFlow(crash), Dispatchers.Unconfined, { RvData.nowEpochMs }, { ZoneOffset.UTC }, { RvPlace.Other })
        compose.rvContent(dark, fontScale) { ActivityPage(model, crashOffer = null, onConnect = {}, routes = activityTestRoutes()) }
        compose.drawFully()
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
        val SHOTS: String = System.getProperty("doona.shots") ?: "src/test/screenshots"
    }
}
