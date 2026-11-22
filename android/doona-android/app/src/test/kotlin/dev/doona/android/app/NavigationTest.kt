package dev.doona.android.app

import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.engine.fake.FakeEngine
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
    fun `the back stack survives saving and restoring`() {
        val restoration = StateRestorationTester(compose)
        val engine = FakeEngine()
        restoration.setContent { DoonaTheme { DoonaApp(engine, onConnect = {}) } }
        compose.onNodeWithText("Policies").performClick()
        compose.onNodeWithText("Proxy").performClick()

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("Proxy").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Manual 1").assertExists()
    }
}
