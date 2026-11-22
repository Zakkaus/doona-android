package dev.doona.android.app

import androidx.compose.ui.test.junit4.v2.createComposeRule
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

    @Test
    fun `the settings show the version the shell passes`() {
        // The package manager of this test app says 0.1; the shell's value is the one shown.
        settings("9.9.9")
        compose.onNodeWithText("9.9.9").assertExists()
        compose.onNodeWithText("0.1").assertDoesNotExist()
    }

    @Test
    fun `a shell that knows no version shows a dash`() {
        settings("")
        compose.onNodeWithText("—").assertExists()
    }
}
