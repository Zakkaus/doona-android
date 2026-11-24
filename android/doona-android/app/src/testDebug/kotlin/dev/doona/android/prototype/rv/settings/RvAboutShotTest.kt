package dev.doona.android.prototype.rv.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.doona.android.R
import dev.doona.android.prototype.rv.RV_FS115
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Rv
import dev.doona.android.prototype.rv.RvTheme
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
 * 關於, the two layouts for the owner, each light, dark, fs115 and fs200: A, 設定's 關於 group with GitHub 專案 added
 * (`proto-rv-settings-about-a-*`), and B, 設定's one 關於 doona row (`-settings-about-b-*`) and the page it opens
 * (`-settings-about-*`); the groups are drawn as 設定 draws its last group, on the page under the status bar. Then what
 * their rows and actions open.
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
    @Config(qualifiers = GROUP_A)
    fun groupALight() = compose.rvShot("settings-about-a", dark = false) { GroupA() }

    @Test
    @Config(qualifiers = GROUP_A)
    fun groupADark() = compose.rvShot("settings-about-a", dark = true) { GroupA() }

    @Test
    @Config(qualifiers = GROUP_A_FS115)
    fun groupAFs115() = compose.rvShot("settings-about-a", dark = false, fontScale = RV_FS115) { GroupA() }

    @Test
    @Config(qualifiers = GROUP_A_FS200)
    fun groupAFs200() = compose.rvShot("settings-about-a", dark = false, fontScale = RV_FS200) { GroupA() }

    @Test
    @Config(qualifiers = GROUP_B)
    fun groupBLight() = compose.rvShot("settings-about-b", dark = false) { GroupB() }

    @Test
    @Config(qualifiers = GROUP_B)
    fun groupBDark() = compose.rvShot("settings-about-b", dark = true) { GroupB() }

    @Test
    @Config(qualifiers = GROUP_B_FS115)
    fun groupBFs115() = compose.rvShot("settings-about-b", dark = false, fontScale = RV_FS115) { GroupB() }

    @Test
    @Config(qualifiers = GROUP_B_FS200)
    fun groupBFs200() = compose.rvShot("settings-about-b", dark = false, fontScale = RV_FS200) { GroupB() }

    @Test
    fun theNameIsThePagesHeadingAndTheVersionsSitUnderIt() {
        compose.setContent { Themed { Page() } }
        compose.onNode(hasText(rvText(R.string.app_name)) and isHeading()).assertExists()
        compose.onNodeWithText(versionLine()).assertExists()
    }

    @Test
    fun eachActionAndRowOpensItsRoute() {
        val opened = mutableListOf<String>()
        val routes = RvAboutRoutes(
            github = { opened += "github" },
            report = { opened += "report" },
            checkUpdate = { opened += "update" },
            licenses = { opened += "licenses" },
            privacy = { opened += "privacy" },
        )
        compose.setContent { Themed { RvAboutScreen(rvSampleVersions, onBack = {}, routes = routes) } }
        for (id in listOf(R.string.rv_about_github_action, R.string.rv_about_report_action, R.string.rv_settings_check_update, R.string.rv_settings_licenses, R.string.rv_about_privacy)) {
            compose.onNodeWithText(rvText(id)).performClick()
        }
        assertEquals(listOf("github", "report", "update", "licenses", "privacy"), opened)
    }

    @Test
    fun groupAOpensTheLicencesAndTheRepository() {
        val opened = mutableListOf<String>()
        compose.setContent { Themed { RvAboutGroup(VERSION, onLicenses = { opened += "licenses" }, onGithub = { opened += "github" }) } }
        compose.onNodeWithText(rvText(R.string.rv_settings_licenses)).performClick()
        compose.onNodeWithText(rvText(R.string.rv_about_github)).performClick()
        assertEquals(listOf("licenses", "github"), opened)
    }

    @Test
    fun groupBsRowOpensThePage() {
        var opened = 0
        compose.setContent { Themed { RvAboutEntry(VERSION, onOpen = { opened++ }) } }
        compose.onNodeWithText(rvText(R.string.rv_about_title)).performClick()
        assertEquals(1, opened)
    }

    private fun versionLine() =
        RuntimeEnvironment.getApplication().getString(R.string.rv_about_versions, rvSampleVersions.app, rvSampleVersions.core, rvSampleVersions.honk)

    @Composable
    private fun Page() = RvAboutScreen(rvSampleVersions, onBack = {})

    @Composable
    private fun GroupA() = Strip { RvAboutGroup(VERSION, onLicenses = {}, onGithub = {}) }

    @Composable
    private fun GroupB() = Strip { RvAboutEntry(VERSION, onOpen = {}) }

    /** 設定's last group on the page's colour, on its margin, under the frame's status bar. */
    @Composable
    private fun Strip(content: @Composable () -> Unit) {
        Column(Modifier.fillMaxSize().background(Rv.colors.page).padding(top = TOP).padding(horizontal = RvSettingsSpace.margin)) { content() }
    }

    @Composable
    private fun Themed(content: @Composable () -> Unit) = RvTheme(seedColorScheme(Seed.Cyan.argb, false), false, content)

    private companion object {
        /** 設定's 版本 row value. */
        const val VERSION = "0.1.0"

        /** The frame's status bar; the section title keeps its own 24 dp above it. */
        val TOP = 24.dp

        /** As tall as each strip at each font size, so its group ends a gutter above the frame's foot. */
        const val GROUP_A = "+h340dp"
        const val GROUP_A_FS115 = "+h360dp"
        const val GROUP_A_FS200 = "+h480dp"
        const val GROUP_B = "+h220dp"
        const val GROUP_B_FS115 = "+h240dp"
        const val GROUP_B_FS200 = "+h320dp"
    }
}
