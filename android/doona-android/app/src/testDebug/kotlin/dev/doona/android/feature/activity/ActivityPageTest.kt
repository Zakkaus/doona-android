package dev.doona.android.feature.activity

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.doona.android.R
import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.Mode
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
import java.util.concurrent.atomic.AtomicInteger

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
        val model = ActivityViewModel(engine, MutableStateFlow(false), Dispatchers.Unconfined, { RvData.nowEpochMs }, { ZoneOffset.UTC })
        compose.rvContent(dark = false) { ActivityPage(model, crash = null, onConnect = { connects++ }, routes = activityTestRoutes(opened)) }
    }

    @Test
    fun eachFailedStartLeadsWhereItsCauseIsMendedAndOnlyARetryConnects() {
        val engine = rvSampleEngine()
        show(engine)
        val cases = listOf(
            Status.Failed(FailureReason.NoAllowedApps) to (R.string.rv_edit_app_list to "perApp"),
            Status.Failed(FailureReason.StartFailed, ErrorCode.NoNodes) to (R.string.rv_add_subscription to "addSubscription"),
            Status.Failed(FailureReason.IncompatibleCore) to (R.string.rv_settings_check_update to "checkUpdate"),
            Status.Failed(FailureReason.LockdownEnabled) to (R.string.rv_open_vpn_settings to "vpnSettings"),
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
        val model = ActivityViewModel(counted, MutableStateFlow(false), Dispatchers.Unconfined, { RvData.nowEpochMs }, { ZoneOffset.UTC })
        compose.rvContent(dark = false) { ActivityPage(model, crash = null, onConnect = {}, routes = activityTestRoutes(opened)) }
        model.onAction(ActivityAction.TestLatency)
        compose.waitForIdle()
        compose.onNodeWithText(rvText(R.string.rv_failed_test_latency)).assertExists()
        compose.onNodeWithText(rvText(R.string.rv_retry)).performClick()
        assertEquals(2, tests)
    }

    @Test
    fun theCrashOfferIsMarkedShownOnlyOnceItsNoticeIsOnScreenAndTheReportTakesItDown() {
        var failing = true
        val engine = object : Engine by rvSampleEngine() {
            override suspend fun testLatency(groupId: String?): Result<Unit> = if (failing) Result.failure(EngineException(ErrorCode.Busy)) else Result.success(Unit)
        }
        val model = ActivityViewModel(engine, MutableStateFlow(true), Dispatchers.Unconfined, { RvData.nowEpochMs }, { ZoneOffset.UTC })
        val shown = AtomicInteger()
        val dismissed = AtomicInteger()
        // The failure stands before the page's first frame, so its banner holds the notice's place from the start.
        model.onAction(ActivityAction.TestLatency)
        compose.rvContent(dark = false) {
            // On Unconfined the disk hop runs inside the page's effect, so once the page is idle any shown() it would make is made.
            ActivityPage(model, ActivityCrashOffer({ shown.incrementAndGet() }, { dismissed.incrementAndGet() }, Dispatchers.Unconfined), onConnect = {}, routes = activityTestRoutes(opened))
        }
        compose.waitForIdle()
        compose.onNodeWithText(rvText(R.string.rv_failed_test_latency)).assertExists()
        compose.onNodeWithText(rvText(R.string.rv_crash_export)).assertDoesNotExist()
        assertEquals("shown while a failure banner stands", 0, shown.get())

        failing = false
        compose.onNodeWithText(rvText(R.string.rv_retry)).performClick()
        compose.onNodeWithText(rvText(R.string.rv_crash_export)).assertExists()
        compose.waitUntil(TIMEOUT_MS) { shown.get() > 0 }
        // Further frames (the sample's traffic) do not mark it again.
        compose.mainClock.advanceTimeBy(TIMEOUT_MS)
        compose.waitForIdle()
        assertEquals(1, shown.get())
        assertEquals(0, dismissed.get())

        compose.onNodeWithText(rvText(R.string.rv_crash_export)).performClick()
        assertEquals(1, dismissed.get())
        assertEquals(listOf("exportReport"), opened)
        assertEquals(1, shown.get())
    }

    /** 匯出報告 opens the report before anything takes the offer down; a route that opens nothing leaves it up. */
    @Test
    fun exportOpensTheReportFirstAndTheOfferStaysUpUntilTheReportHoldsIt() {
        val model = ActivityViewModel(rvSampleEngine(), MutableStateFlow(true), Dispatchers.Unconfined, { RvData.nowEpochMs }, { ZoneOffset.UTC })
        val order = mutableListOf<String>()
        var handOff: (() -> Unit)? = null
        val export: (() -> Unit) -> Unit = { received ->
            order += "open"
            handOff = received
        }
        val routes = activityTestRoutes(opened, exportReport = export)
        compose.rvContent(dark = false) { ActivityPage(model, ActivityCrashOffer({}, { order += "dismiss" }, Dispatchers.Unconfined), onConnect = {}, routes = routes) }
        compose.onNodeWithText(rvText(R.string.rv_crash_export)).performClick()
        assertEquals("opened, not yet held: the offer stands", listOf("open"), order)
        checkNotNull(handOff)()
        assertEquals(listOf("open", "dismiss"), order)
    }

    /** The 出站模式 action, tapped three times, goes 規則, 全域, 直連 and back to 規則. */
    @Test
    fun theModeActionCyclesThroughAllThreeModes() {
        val engine = rvSampleEngine()
        show(engine)
        val action = hasText(rvText(R.string.rv_action_mode)) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
        val modes = mutableListOf(engine.settings.value.mode)
        repeat(3) {
            compose.onAllNodes(action).onFirst().performClick()
            compose.waitForIdle()
            modes += engine.settings.value.mode
        }
        assertEquals(listOf(Mode.Rule, Mode.Global, Mode.Direct, Mode.Rule), modes)
    }

    private companion object {
        const val TIMEOUT_MS = 3_000L
    }
}

/** Routes that lead nowhere, each counted in [opened] by its name. */
internal fun activityTestRoutes(
    opened: MutableList<String> = mutableListOf(),
    exportReport: (() -> Unit) -> Unit = { received ->
        opened += "exportReport"
        received()
    },
): ActivityRoutes {
    fun to(name: String): () -> Unit = { opened += name }
    return ActivityRoutes(
        traffic = to("traffic"),
        stats = to("stats"),
        search = to("search"),
        nodes = to("nodes"),
        node = { opened += "node:$it" },
        tab = { opened += "tab" },
        mode = to("mode"),
        rule = { opened += "rule:$it" },
        rules = to("rules"),
        subscriptions = to("subscriptions"),
        more = to("more"),
        addSubscription = to("addSubscription"),
        perApp = to("perApp"),
        checkUpdate = to("checkUpdate"),
        exportReport = exportReport,
        networkSettings = to("networkSettings"),
        notifySettings = to("notifySettings"),
        vpnSettings = to("vpnSettings"),
    )
}
