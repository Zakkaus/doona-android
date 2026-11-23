package dev.doona.android.feature.report

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.app.ActivityKey
import dev.doona.android.app.DoonaApp
import dev.doona.android.app.SettingsKey
import dev.doona.android.designsystem.components.DoonaErrorBanner
import dev.doona.android.engine.LogLevel
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.engine.fake.FakeInstalledApps
import dev.doona.android.report.fake.FakeReports
import dev.doona.android.testing.SeedTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 設定 › 回報問題 › 匯出診斷報告, the log level row, and the crash banner's offer. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "zh-rTW")
class ReportScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `回報問題 in the settings opens the page, whose button exports`() {
        val reports = FakeReports()
        compose.setContent {
            SeedTheme { DoonaApp(FakeEngine(), FakeInstalledApps(), onConnect = {}, start = listOf(ActivityKey, SettingsKey), reports = reports) }
        }
        compose.onNodeWithText("回報問題").performClick()
        compose.onNodeWithText("匯出診斷報告").performClick()
        assertEquals(1, reports.exports)
    }

    @Test
    fun `the log level row sets the core's level`() {
        val engine = FakeEngine()
        compose.setContent {
            SeedTheme { DoonaApp(engine, FakeInstalledApps(), onConnect = {}, start = listOf(ActivityKey, SettingsKey), reports = FakeReports()) }
        }
        compose.onNodeWithText("回報問題").performClick()
        compose.onNodeWithText("一般").assertIsSelected()
        compose.onNodeWithText("除錯").performClick()
        compose.waitForIdle()
        assertEquals(LogLevel.Debug, engine.logLevel)
        compose.onNodeWithText("除錯").assertIsSelected()
    }

    @Test
    fun `the crash banner offers the export and goes to the page that runs it`() {
        val reports = FakeReports(crashOffered = true)
        compose.setContent {
            SeedTheme { DoonaApp(FakeEngine(), FakeInstalledApps(), onConnect = {}, reports = reports) }
        }
        compose.onNodeWithText("doona 先前發生當機").assertExists()
        compose.onNodeWithText("匯出報告").performClick()
        assertEquals(1, reports.exports)
        compose.onNodeWithText("匯出診斷報告").assertExists()
        compose.onNodeWithText("doona 先前發生當機").assertDoesNotExist()
    }

    @Test
    fun `the banner's action is not a dismissal, and a tap elsewhere is`() {
        var acted = 0
        var dismissed = 0
        compose.setContent {
            SeedTheme { DoonaErrorBanner("doona 先前發生當機", "關閉", onDismiss = { dismissed++ }, actionLabel = "匯出報告", onAction = { acted++ }) }
        }
        compose.onNodeWithText("匯出報告").performClick()
        assertEquals(1, acted)
        assertEquals(0, dismissed)
        compose.onNodeWithText("doona 先前發生當機").performClick()
        assertEquals(1, dismissed)
        assertEquals(1, acted)
    }

    @Test
    fun `a dismissed crash banner stays down`() {
        val reports = FakeReports(crashOffered = true)
        compose.setContent {
            SeedTheme { DoonaApp(FakeEngine(), FakeInstalledApps(), onConnect = {}, reports = reports) }
        }
        compose.onNodeWithText("doona 先前發生當機").performClick()
        compose.waitForIdle()
        assertFalse(reports.crashOffered.value)
        compose.onNodeWithText("doona 先前發生當機").assertDoesNotExist()
        assertEquals(0, reports.exports)
    }

    @Test
    fun `the crash banner shows on the first-run page too, and says it was shown once`() {
        val reports = FakeReports(crashOffered = true)
        compose.setContent {
            SeedTheme { DoonaApp(FakeEngine(subscriptions = emptyList()), FakeInstalledApps(), onConnect = {}, reports = reports) }
        }
        compose.onNodeWithText("doona 先前發生當機").assertExists()
        compose.waitForIdle()
        assertEquals(1, reports.crashesShown)
    }

    @Test
    fun `a crash offer that is not up is not marked shown`() {
        val reports = FakeReports(crashOffered = false)
        compose.setContent {
            SeedTheme { DoonaApp(FakeEngine(), FakeInstalledApps(), onConnect = {}, reports = reports) }
        }
        compose.waitForIdle()
        assertEquals(0, reports.crashesShown)
    }
}
