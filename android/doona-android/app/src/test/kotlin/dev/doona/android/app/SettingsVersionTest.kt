package dev.doona.android.app

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.engine.fake.FakeInstalledApps
import dev.doona.android.testing.SeedTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsVersionTest {
    @get:Rule
    val compose = createComposeRule()

    private fun settings(version: String) = compose.setContent {
        SeedTheme {
            DoonaApp(FakeEngine(), FakeInstalledApps(), onConnect = {}, start = listOf(ActivityKey, SettingsKey), version = version)
        }
    }

    /**
     * 設定 waits for the app's settings file, which DataStore reads off the main thread where the test's idling does not
     * see it, so each check first waits for the page.
     */
    private fun waitForCount(text: String, count: Int) =
        compose.waitUntil(PAGE_TIMEOUT_MS) { compose.onAllNodesWithText(text).fetchSemanticsNodes().size == count }

    @Test
    fun `the settings show the version the shell passes`() {
        // The package manager of this test app says 0.1; the shell's value is the one shown.
        settings("9.9.9")
        // 檢查更新 and 關於 both name it (the approved page).
        waitForCount("9.9.9", 2)
        compose.onAllNodesWithText("9.9.9").assertCountEquals(2)
        compose.onNodeWithText("0.1").assertDoesNotExist()
    }

    @Test
    fun `a shell that knows no version shows a dash`() {
        settings("")
        waitForCount("—", 2)
        compose.onAllNodesWithText("—").assertCountEquals(2)
    }

    private companion object {
        const val PAGE_TIMEOUT_MS = 5_000L
    }
}
