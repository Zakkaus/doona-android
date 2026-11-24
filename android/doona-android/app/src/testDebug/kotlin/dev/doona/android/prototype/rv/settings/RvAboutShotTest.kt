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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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

    @Test
    fun theTitleIsThePagesHeadingAndEachVersionIsARow() {
        compose.setContent { Themed { Page() } }
        compose.onNode(hasText(rvText(R.string.rv_about_title)) and isHeading()).assertExists()
        for ((label, value) in listOf(
            rvText(R.string.rv_settings_version) to rvSampleVersions.app,
            rvText(R.string.rv_about_core) to rvSampleVersions.core,
            rvText(R.string.rv_about_honk) to rvSampleVersions.honk,
        )) {
            compose.onNodeWithText(label).assertExists()
            // The app and the core share 0.1.0 in the sample.
            assertTrue(compose.onAllNodes(hasText(value)).fetchSemanticsNodes().isNotEmpty())
        }
    }

    @Test
    fun eachRowOpensItsRoute() {
        val opened = mutableListOf<String>()
        val routes = RvAboutRoutes(
            github = { opened += "github" },
            report = { opened += "report" },
            checkUpdate = { opened += "update" },
            licenses = { opened += "licenses" },
            privacy = { opened += "privacy" },
        )
        compose.setContent { Themed { RvAboutScreen(rvSampleVersions, onBack = {}, routes = routes) } }
        for (id in listOf(R.string.rv_about_github, R.string.rv_report_title, R.string.rv_settings_check_update, R.string.rv_settings_licenses, R.string.rv_about_privacy)) {
            compose.onNodeWithText(rvText(id)).performClick()
        }
        assertEquals(listOf("github", "report", "update", "licenses", "privacy"), opened)
    }

    @Test
    fun settingsRowOpensThePage() {
        var opened = 0
        compose.setContent { Themed { RvAboutEntry(rvSampleVersions.app, onOpen = { opened++ }) } }
        compose.onNodeWithText(rvText(R.string.rv_about_title)).performClick()
        assertEquals(1, opened)
    }

    @Composable
    private fun Page() = RvAboutScreen(rvSampleVersions, onBack = {})

    @Composable
    private fun Themed(content: @Composable () -> Unit) = RvTheme(seedColorScheme(Seed.Cyan.argb, false), false, content)
}
