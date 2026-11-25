package dev.doona.android.app

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.navigation3.runtime.NavKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.R
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.Status
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.engine.fake.FakeInstalledApps
import dev.doona.android.testing.SeedTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationTest {
    @get:Rule
    val compose = createComposeRule()

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
        restoration.setContent { SeedTheme { DoonaApp(engine, FakeInstalledApps(), onConnect = {}) } }
        compose.onNodeWithText("Policies").performClick()
        compose.onNodeWithText("Proxy").performClick()

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("Proxy").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Manual 1").assertExists()
    }

    @Test
    fun `the per-app row opens its page and back returns to the settings`() {
        compose.setContent { SeedTheme { DoonaApp(FakeEngine(), FakeInstalledApps(), onConnect = {}, start = listOf(ActivityKey, SettingsKey)) } }
        compose.onNodeWithText("Per-app").performClick()
        compose.onNodeWithText("Save").assertExists()

        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Version").assertExists()
        compose.onNodeWithText("Save").assertDoesNotExist()
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
    fun `a second back while the page leaves does not pop the tab underneath`() {
        compose.setContent { SeedTheme { DoonaApp(FakeEngine(FakeEngine.SAMPLE_GROUPS), FakeInstalledApps(), onConnect = {}, start = GROUP) } }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.mainClock.autoAdvance = true

        compose.onNodeWithText("Manual 1").assertExists()
    }

    @Test
    fun `a group page leaves when its group is removed`() {
        val engine = FakeEngine(FakeEngine.SAMPLE_GROUPS)
        compose.setContent { SeedTheme { DoonaApp(engine, FakeInstalledApps(), onConnect = {}, start = GROUP) } }
        compose.onNodeWithText("Proxy").assertExists()

        engine.groups.value = engine.groups.value.filter { it.id != "g-Proxy" }

        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
        compose.onNodeWithText("Manual 0").assertExists()
    }

    @Test
    fun `a restored group page waits for the engine before it leaves`() {
        val engine = FakeEngine(groups = emptyList()).apply { ready.value = false }
        compose.setContent { SeedTheme { DoonaApp(engine, FakeInstalledApps(), onConnect = {}, start = GROUP) } }
        compose.waitForIdle()

        engine.groups.value = FakeEngine.SAMPLE_GROUPS
        engine.ready.value = true

        compose.onNodeWithText("Proxy").assertExists()
        compose.onNodeWithContentDescription("Back").assertExists()
    }
}

private val GROUP = listOf<NavKey>(ActivityKey, PoliciesKey, GroupKey("g-Proxy"))
