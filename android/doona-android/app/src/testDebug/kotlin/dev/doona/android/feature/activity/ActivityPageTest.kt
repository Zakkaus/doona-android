package dev.doona.android.feature.activity

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvPlace
import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.Status
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.engine.fake.rvSampleEngine
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.rvContent
import dev.doona.android.prototype.rv.rvText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneOffset

/** [ActivityPage]'s banners: a notice's action leads where its cause is mended, a failed action's 重試 runs it again. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class ActivityPageTest {
    @get:Rule
    val compose = createComposeRule()

    private val opened = mutableListOf<String>()
    private var connects = 0

    private fun show(engine: FakeEngine) {
        val model = ActivityViewModel(engine, MutableStateFlow(false), Dispatchers.Unconfined, { RvData.nowEpochMs }, { ZoneOffset.UTC }, { RvPlace.Other })
        compose.rvContent(dark = false) { ActivityPage(model, crashOffer = null, onConnect = { connects++ }, routes = activityTestRoutes(opened)) }
    }

    @Test
    fun eachFailedStartLeadsWhereItsCauseIsMendedAndOnlyARetryConnects() {
        val engine = rvSampleEngine()
        show(engine)
        val cases = listOf(
            Status.Failed(FailureReason.NoAllowedApps) to (R.string.perapp_title to "perApp"),
            Status.Failed(FailureReason.StartFailed, ErrorCode.NoNodes) to (R.string.rv_add_subscription to "addSubscription"),
            Status.Failed(FailureReason.IncompatibleCore) to (R.string.rv_settings_check_update to "checkUpdate"),
            Status.Failed(FailureReason.LockdownEnabled) to (R.string.rv_lan_open_settings to "vpnSettings"),
        )
        cases.forEach { (status, want) ->
            opened.clear()
            engine.status.value = status
            compose.waitForIdle()
            compose.onNodeWithText(rvText(want.first)).performClick()
            assertEquals(status.toString(), listOf(want.second), opened)
        }
        assertEquals(0, connects)
        engine.status.value = Status.Failed(FailureReason.StartFailed)
        compose.waitForIdle()
        compose.onNodeWithText(rvText(R.string.rv_retry)).performClick()
        assertEquals(1, connects)
    }

    @Test
    fun aFailedLatencyTestShowsItsBannerAndItsRetryTestsAgain() {
        var tests = 0
        val counted = object : Engine by rvSampleEngine() {
            override suspend fun testLatency(groupId: String?): Result<Unit> {
                tests++
                return Result.failure(EngineException(ErrorCode.Busy))
            }
        }
        val model = ActivityViewModel(counted, MutableStateFlow(false), Dispatchers.Unconfined, { RvData.nowEpochMs }, { ZoneOffset.UTC }, { RvPlace.Other })
        compose.rvContent(dark = false) { ActivityPage(model, crashOffer = null, onConnect = {}, routes = activityTestRoutes(opened)) }
        model.onAction(ActivityAction.TestLatency)
        compose.waitForIdle()
        compose.onNodeWithText(rvText(R.string.rv_failed_test_latency)).assertExists()
        compose.onNodeWithText(rvText(R.string.rv_retry)).performClick()
        assertEquals(2, tests)
    }
}

/** Routes that lead nowhere, each counted in [opened] by its name. */
internal fun activityTestRoutes(opened: MutableList<String> = mutableListOf()): ActivityRoutes {
    fun to(name: String): () -> Unit = { opened += name }
    return ActivityRoutes(
        traffic = to("traffic"),
        stats = to("stats"),
        search = to("search"),
        nodes = to("nodes"),
        node = to("node"),
        tab = { opened += "tab" },
        mode = to("mode"),
        rule = { opened += "rule:$it" },
        rules = to("rules"),
        subscriptions = to("subscriptions"),
        more = to("more"),
        addSubscription = to("addSubscription"),
        perApp = to("perApp"),
        checkUpdate = to("checkUpdate"),
        exportReport = to("exportReport"),
        networkSettings = to("networkSettings"),
        notifySettings = to("notifySettings"),
        vpnSettings = to("vpnSettings"),
    )
}
