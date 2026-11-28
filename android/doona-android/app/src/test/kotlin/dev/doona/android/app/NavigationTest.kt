package dev.doona.android.app

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.navigation3.runtime.NavKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.R
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.Mode
import dev.doona.android.engine.Status
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.engine.fake.FakeInstalledApps
import dev.doona.android.engine.fake.FakeRuleMode
import dev.doona.android.engine.fake.rvSampleEngine
import dev.doona.android.report.CrashFiles
import dev.doona.android.report.CrashHandler
import dev.doona.android.report.CrashOffer
import dev.doona.android.testing.SeedTheme
import dev.doona.android.vpn.ActivityHonkFixtures.servingSample
import dev.doona.android.vpn.FakeHonk
import dev.doona.android.vpn.HonkFixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    /** Waits for [text]: the pages read the engine off the main thread, which waitForIdle does not wait for. */
    private fun appears(text: String) = compose.waitUntil(READ_MS) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    /** The search page is up once its field holds the focus, which the page gives it a frame after it is placed. */
    private fun searchOpens() = compose.waitUntil(READ_MS) { compose.onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().isNotEmpty() }

    private fun string(id: Int) = ApplicationProvider.getApplicationContext<Context>().getString(id)

    @Test
    fun `switching tabs keeps 活動 at the bottom`() {
        val stack = mutableListOf<NavKey>(ActivityKey, PoliciesKey, GroupKey("g-Proxy"))
        assertEquals(Tab.Policies, currentTab(stack))
        stack.selectTab(Tab.Settings)
        assertEquals(listOf(ActivityKey, SettingsKey), stack)
        stack.selectTab(Tab.Activity)
        assertEquals(listOf<NavKey>(ActivityKey), stack)
    }

    @Test
    fun `pop leaves only the top key and never the root`() {
        val stack = GROUP.toMutableList()
        stack.pop(GroupKey("g-Proxy"))
        stack.pop(GroupKey("g-Proxy"))
        assertEquals(listOf(ActivityKey, PoliciesKey), stack)
        stack.pop(PoliciesKey)
        stack.pop(ActivityKey)
        assertEquals(listOf<NavKey>(ActivityKey), stack)
    }

    @Test
    fun `the back stack survives saving and restoring`() {
        val restoration = StateRestorationTester(compose)
        val engine = FakeEngine(FakeEngine.SAMPLE_GROUPS)
        restoration.setContent { SeedTheme { DoonaApp(engine, FakeInstalledApps(), onConnect = {}, start = listOf(ActivityKey, PoliciesKey, GroupKey("g-Auto"))) } }
        appears(TEST_GROUP)

        restoration.emulateSavedInstanceStateRestore()

        appears(TEST_GROUP)
        compose.onNodeWithContentDescription("Back").performClick()
        appears(POLICIES)
    }

    @Test
    fun `the per-app row opens its page and back returns to the settings`() {
        compose.setContent { SeedTheme { DoonaApp(FakeEngine(), FakeInstalledApps(), onConnect = {}, start = listOf(ActivityKey, SettingsKey)) } }
        compose.onNodeWithText("Per-app").performScrollTo().performClick()
        compose.onNodeWithText("Save").assertExists()

        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Per-app").assertExists()
        compose.onNodeWithText("Save").assertDoesNotExist()
    }

    @Test
    fun `生效設定 on 設定 opens the effective config`() {
        compose.setContent { SeedTheme { DoonaApp(FakeEngine(FakeEngine.SAMPLE_GROUPS), FakeInstalledApps(), onConnect = {}, start = listOf(ActivityKey, SettingsKey)) } }
        compose.onNodeWithText(string(R.string.rv_effective_open)).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        appears(string(R.string.rv_effective_title))
    }

    @Test
    fun `自動更新 on a subscription's page opens 更新間隔`() {
        compose.setContent { SeedTheme { DoonaApp(FakeEngine(FakeEngine.SAMPLE_GROUPS), FakeInstalledApps(), onConnect = {}, start = listOf(ActivityKey, SubscriptionsKey, SubDetailKey("s1"))) } }
        appears(string(R.string.rv_sub_auto_update))
        compose.onNode(hasClickAction() and hasText(string(R.string.rv_sub_auto_update))).performSemanticsAction(SemanticsActions.OnClick)
        appears(string(R.string.rv_settings_update_interval))
    }

    @Test
    fun `匯出診斷 on 統計 opens 回報問題`() {
        compose.setContent { SeedTheme { DoonaApp(FakeEngine(FakeEngine.SAMPLE_GROUPS), FakeInstalledApps(), onConnect = {}, start = listOf(ActivityKey, StatsKey)) } }
        compose.onNode(hasClickAction() and hasText(string(R.string.rv_stats_export))).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        appears(string(R.string.rv_report_title))
    }

    @Test
    fun `匯出報告 on 活動's crash notice opens 回報問題`() {
        val crashes = CrashFiles(tmp.root.resolve("crash"))
        CrashHandler(crashes, "v", { _, _ -> }) { true }.uncaughtException(Thread.currentThread(), IllegalStateException())
        val offer = CrashOffer(crashes, tmp.root.resolve("crash-offered")).also { it.check() }
        compose.setContent { SeedTheme { DoonaApp(FakeEngine(FakeEngine.SAMPLE_GROUPS), FakeInstalledApps(), onConnect = {}, crashOffer = offer) } }
        appears(string(R.string.rv_crash_export))
        compose.onNode(hasClickAction() and hasText(string(R.string.rv_crash_export))).performSemanticsAction(SemanticsActions.OnClick)
        appears(string(R.string.rv_report_title))
    }

    @Test
    fun `統計 on 活動 opens the statistics page and back returns to 活動`() {
        val res = ApplicationProvider.getApplicationContext<Context>().resources
        compose.setContent { SeedTheme { DoonaApp(FakeEngine(FakeEngine.SAMPLE_GROUPS), FakeInstalledApps(), onConnect = {}) } }
        compose.onNodeWithContentDescription(res.getString(R.string.rv_stats)).performClick()
        compose.onNodeWithText(res.getString(R.string.rv_stats_title)).assertExists()

        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText(res.getString(R.string.rv_stats_title)).assertDoesNotExist()
        compose.onNodeWithContentDescription(res.getString(R.string.rv_stats)).assertExists()
    }

    @Test
    fun `全部規則 on 活動 opens the rules page and back returns to 活動`() {
        val res = ApplicationProvider.getApplicationContext<Context>().resources
        // 活動 counts the rules honk runs: the app's honk serves the RV sample's.
        val rules = Json.parseToJsonElement(HonkFixtures.body("rules/rv-sample")).jsonObject.getValue("rules").jsonArray.size
        val seeAll = res.getQuantityString(R.plurals.rv_see_all_rules, rules, rules)
        compose.setContent { SeedTheme { DoonaApp(FakeEngine(FakeEngine.SAMPLE_GROUPS, mode = Mode.Rule), FakeInstalledApps(), onConnect = {}) } }
        appears(seeAll)
        compose.onNode(hasClickAction() and hasText(seeAll)).performSemanticsAction(SemanticsActions.OnClick)
        appears(res.getString(R.string.rv_policy_rules))

        compose.onNodeWithContentDescription("Back").performClick()
        appears(seeAll)
        compose.onNodeWithText(res.getString(R.string.rv_policy_rules)).assertDoesNotExist()
    }

    @Test
    fun `新增訂閱 on a start without nodes opens the add page`() {
        val res = ApplicationProvider.getApplicationContext<Context>().resources
        val engine = FakeEngine(FakeEngine.SAMPLE_GROUPS).apply { status.value = Status.Failed(FailureReason.StartFailed, ErrorCode.NoNodes) }
        compose.setContent { SeedTheme { DoonaApp(engine, FakeInstalledApps(), onConnect = {}) } }
        compose.onNode(hasClickAction() and hasText(res.getString(R.string.rv_add_subscription))).performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText(res.getString(R.string.rv_add_import)).assertExists()
    }

    @Test
    fun `a traffic tile on 活動 opens 流量詳情`() {
        val download = ApplicationProvider.getApplicationContext<Context>().getString(R.string.ui_download)
        compose.setContent { SeedTheme { DoonaApp(FakeEngine(FakeEngine.SAMPLE_GROUPS), FakeInstalledApps(), onConnect = {}) } }
        compose.onNode(hasClickAction() and hasText(download, substring = true)).performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("This connection").assertExists()
    }

    @Test
    fun `流量詳情 opens on its key and back returns to 活動`() {
        compose.setContent { SeedTheme { DoonaApp(FakeEngine(), FakeInstalledApps(), onConnect = {}, start = listOf(ActivityKey, TrafficKey)) } }
        compose.onNodeWithText("This connection").assertExists()

        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("This connection").assertDoesNotExist()
    }

    @Test
    fun `搜尋節點 on 活動 opens the node search`() {
        compose.setContent { SeedTheme { DoonaApp(FakeEngine(FakeEngine.SAMPLE_GROUPS), FakeInstalledApps(), onConnect = {}) } }
        compose.onNode(hasClickAction() and hasText(string(R.string.rv_search_nodes))).performSemanticsAction(SemanticsActions.OnClick)
        searchOpens()
    }

    @Test
    fun `最快節點 on 活動 opens 節點`() {
        compose.setContent { SeedTheme { DoonaApp(FakeEngine(FakeEngine.SAMPLE_GROUPS), FakeInstalledApps(), onConnect = {}) } }
        compose.onNode(hasClickAction() and hasText(string(R.string.rv_fastest_nodes))).performSemanticsAction(SemanticsActions.OnClick)
        appears(string(R.string.rv_nodes_spread))
    }

    @Test
    fun `the main node on 活動 opens the node's page`() {
        // The contract's rules fall back to block, which names no main node: serve the sample's profile.
        (ApplicationProvider.getApplicationContext<DoonaApplication>().container.nativeApi as FakeHonk).servingSample()
        compose.setContent { SeedTheme { DoonaApp(rvSampleEngine(), FakeInstalledApps(), onConnect = {}) } }
        appears(string(R.string.rv_main_node))
        compose.onNode(hasClickAction() and hasText(string(R.string.rv_main_node))).performSemanticsAction(SemanticsActions.OnClick)
        appears(string(R.string.rv_nodes_details))
    }

    @Test
    fun `主節點 on 策略 opens the main node's picker`() {
        compose.setContent { SeedTheme { DoonaApp(FakeEngine(FakeEngine.SAMPLE_GROUPS, mode = Mode.Rule), FakeInstalledApps(), onConnect = {}, start = listOf(ActivityKey, PoliciesKey)) } }
        appears(string(R.string.rv_main_node))
        compose.onNode(hasClickAction() and hasText(string(R.string.rv_main_node))).performSemanticsAction(SemanticsActions.OnClick)
        appears(string(R.string.rv_main_node_hint))
        compose.onNodeWithContentDescription("Back").assertExists()
    }

    @Test
    fun `搜尋節點 on 策略 opens the node search`() {
        compose.setContent { SeedTheme { DoonaApp(FakeEngine(FakeEngine.SAMPLE_GROUPS), FakeInstalledApps(), onConnect = {}, start = listOf(ActivityKey, PoliciesKey)) } }
        appears(POLICIES)
        compose.onNode(hasClickAction() and hasText(string(R.string.rv_search_nodes))).performSemanticsAction(SemanticsActions.OnClick)
        searchOpens()
    }

    @Test
    fun `a second back while the page leaves does not pop the tab underneath`() {
        compose.setContent { SeedTheme { DoonaApp(FakeEngine(FakeEngine.SAMPLE_GROUPS), FakeInstalledApps(), onConnect = {}, start = GROUP) } }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.mainClock.autoAdvance = true

        appears(POLICIES)
    }

    @Test
    fun `a group page leaves when honk has no such group`() {
        // Removed from the configuration, as on another device: honk answers the page's read with 404.
        val honk = ApplicationProvider.getApplicationContext<DoonaApplication>().container.nativeApi as FakeHonk
        val groups = Json.parseToJsonElement(HonkFixtures.body("groups/rv-sample")).jsonArray.filterNot { it.jsonObject.getValue("id").jsonPrimitive.content == "g-Auto" }
        honk.on("GET", "/api/v1/groups", 200, groups.toString())
        honk.on("GET", "/api/v1/groups/g-Auto", 404, HonkFixtures.body("errors/group-not-found"))
        compose.setContent { SeedTheme { DoonaApp(FakeEngine(FakeEngine.SAMPLE_GROUPS), FakeInstalledApps(), onConnect = {}, start = listOf(ActivityKey, PoliciesKey, GroupKey("g-Auto"))) } }

        appears(POLICIES)
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
    }

    @Test
    fun `a restored group page waits for the engine before it leaves`() {
        val engine = FakeEngine(groups = emptyList()).apply { ready.value = false }
        compose.setContent { SeedTheme { DoonaApp(engine, FakeInstalledApps(), onConnect = {}, start = GROUP) } }
        compose.waitForIdle()

        engine.groups.value = FakeEngine.SAMPLE_GROUPS
        engine.ready.value = true

        appears(TEST_GROUP)
        compose.onNodeWithContentDescription("Back").assertExists()
    }
}

private val GROUP = listOf<NavKey>(ActivityKey, PoliciesKey, GroupKey("g-Proxy"))

/** The group page's button, there once the group is read. */
private const val TEST_GROUP = "Test this group"

/** 策略's line under its mode switch, in the fixture's mode (全域), there once the profile is read. */
private const val POLICIES = "All traffic goes through the main node"

private const val READ_MS = 5_000L
