package dev.doona.android.prototype.rv.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.prototype.rv.RV_FS115
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Seed
import dev.doona.android.prototype.rv.rvShot
import dev.doona.android.prototype.rv.rvText
import dev.doona.android.prototype.rv.seedColorScheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 關於 doona, the page 設定's 關於 row opens, light, dark, fs115 and fs200 (`proto-rv-settings-about-*`); 設定's row
 * itself is in 設定's goldens. Then that the page names its versions and what each row and 設定's row open.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvAboutShotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test fun pageLight() = compose.rvShot("settings-about", dark = false) { Page() }

    @Test fun pageDark() = compose.rvShot("settings-about", dark = true) { Page() }

    @Test fun pageFs115() = compose.rvShot("settings-about", dark = false, fontScale = RV_FS115) { Page() }

    @Test fun pageFs200() = compose.rvShot("settings-about", dark = false, fontScale = RV_FS200) { Page() }

    /** Two versions, doona's and honk's, the second saying what honk is; no 核心 row, since the core is the app's version. */
    @Test
    fun theTitleIsThePagesHeadingAndEachVersionIsARow() {
        compose.setContent { Themed { Page() } }
        compose.onNode(hasText(rvText(R.string.rv_about_title)) and isHeading()).assertExists()
        compose.onNodeWithText(rvText(R.string.app_name)).assertExists()
        compose.onNodeWithText(rvSampleVersions.app).assertExists()
        compose.onNodeWithText(rvText(R.string.rv_about_honk)).assertExists()
        val honk = RuntimeEnvironment.getApplication().getString(R.string.rv_about_honk_sub, rvSampleVersions.honk)
        compose.onNodeWithText(honk).assertExists()
        compose.onNodeWithText(rvText(R.string.rv_settings_version)).assertDoesNotExist()
    }

    @Test
    fun eachRowOpensItsRoute() {
        val opened = mutableListOf<String>()
        val routes = RvAboutRoutes(
            github = { opened += "github" },
            report = { opened += "report" },
            licenses = { opened += "licenses" },
            privacy = { opened += "privacy" },
        )
        compose.setContent { Themed { RvAboutScreen(rvSampleVersions, onBack = {}, routes = routes) } }
        for (id in listOf(R.string.rv_about_github, R.string.rv_report_title, R.string.rv_settings_licenses, R.string.rv_about_privacy)) {
            compose.onNodeWithText(rvText(id)).performClick()
        }
        // 檢查更新 is on 設定 now (owner-fix2 item 18).
        compose.onNodeWithText(rvText(R.string.rv_settings_check_update)).assertDoesNotExist()
        assertEquals(listOf("github", "report", "licenses", "privacy"), opened)
    }

    /** 設定's 關於 card: one row, 關於 doona, which opens the page; 檢查更新 is in 更新 (owner-fix3 item 27). */
    @Test
    fun settingsRowOpensThePage() {
        var opened = 0
        compose.setContent { Themed { RvAboutEntry(rvSampleVersions.app, onOpen = { opened++ }) } }
        compose.onNodeWithText(rvText(R.string.rv_about_title)).performClick()
        compose.onNodeWithText(rvText(R.string.rv_settings_check_update)).assertDoesNotExist()
        assertEquals(1, opened)
    }

    @Composable
    private fun Page() = RvAboutScreen(rvSampleVersions, onBack = {})

    @Composable
    private fun Themed(content: @Composable () -> Unit) = RvTheme(seedColorScheme(Seed.Cyan.argb, false), false, content)
}
