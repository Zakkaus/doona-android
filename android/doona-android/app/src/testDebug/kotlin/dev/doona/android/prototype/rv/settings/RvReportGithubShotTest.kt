package dev.doona.android.prototype.rv.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.doona.android.R
import dev.doona.android.prototype.rv.RV_FS115
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.RvTheme
import dev.doona.android.prototype.rv.Seed
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.rvAnimatorScale
import dev.doona.android.prototype.rv.rvShot
import dev.doona.android.prototype.rv.rvText
import dev.doona.android.prototype.rv.seedColorScheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 回報問題 for GitHub, each state light, dark, fs115 and fs200 in one frame as tall as the page: ready
 * (`proto-rv-settings-report-github-*`), while the report is written (`-report-github-building-*`, at animator scale 0,
 * so the busy bar rests half full) and after it failed (`-report-github-failed-*`). Then what the switches and the
 * actions do, and that the actions wait for a report written with the switches as they stand.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvReportGithubShotTest {
    @get:Rule
    val compose = createComposeRule()

    @After
    fun restoreScale() = rvAnimatorScale(1f)

    @Test
    @Config(qualifiers = READY)
    fun light() = shot("report-github", READY_BUILD, dark = false)

    @Test
    @Config(qualifiers = READY)
    fun dark() = shot("report-github", READY_BUILD, dark = true)

    @Test
    @Config(qualifiers = READY_FS115)
    fun fs115() = shot("report-github", READY_BUILD, fontScale = RV_FS115)

    @Test
    @Config(qualifiers = READY_FS200)
    fun fs200() = shot("report-github", READY_BUILD, fontScale = RV_FS200)

    @Test
    @Config(qualifiers = BUILDING)
    fun buildingLight() = shot("report-github-building", RvReportBuild.Building, dark = false)

    @Test
    @Config(qualifiers = BUILDING)
    fun buildingDark() = shot("report-github-building", RvReportBuild.Building, dark = true)

    @Test
    @Config(qualifiers = BUILDING_FS115)
    fun buildingFs115() = shot("report-github-building", RvReportBuild.Building, fontScale = RV_FS115)

    @Test
    @Config(qualifiers = BUILDING_FS200)
    fun buildingFs200() = shot("report-github-building", RvReportBuild.Building, fontScale = RV_FS200)

    @Test
    @Config(qualifiers = FAILED)
    fun failedLight() = shot("report-github-failed", RvReportBuild.Failed, dark = false)

    @Test
    @Config(qualifiers = FAILED)
    fun failedDark() = shot("report-github-failed", RvReportBuild.Failed, dark = true)

    @Test
    @Config(qualifiers = FAILED_FS115)
    fun failedFs115() = shot("report-github-failed", RvReportBuild.Failed, fontScale = RV_FS115)

    @Test
    @Config(qualifiers = FAILED_FS200)
    fun failedFs200() = shot("report-github-failed", RvReportBuild.Failed, fontScale = RV_FS200)

    @Test
    @Config(qualifiers = READY)
    fun eachSwitchTurnsItsRedactionAndTheActionsRun() {
        val redaction = mutableStateOf(RvRedaction())
        val ran = mutableListOf<String>()
        val actions = RvReportActions(github = { ran += "github" }, share = { ran += "share" })
        compose.setContent { Themed { RvReportGithubScreen(RvReportBuild.Ready(redaction.value), rvSampleReport, redaction.value, { redaction.value = it }, onBack = {}, actions = actions) } }
        compose.onNodeWithText(rvText(R.string.rv_redact_apps)).performClick()
        compose.onNodeWithText(rvText(R.string.rv_redact_nodes)).performClick()
        assertEquals(RvRedaction(nodes = false, apps = true), redaction.value)
        compose.onNodeWithText(rvText(R.string.rv_report_github)).performClick()
        compose.onNodeWithText(rvText(R.string.rv_report_share)).performClick()
        assertEquals(listOf("github", "share"), ran)
    }

    /** A report written before a switch turned hides other things: it is not shared until it is written again. */
    @Test
    @Config(qualifiers = READY)
    fun aSwitchHoldsTheActionsUntilTheReportIsWrittenAgain() {
        val redaction = mutableStateOf(RvRedaction())
        val build = mutableStateOf<RvReportBuild>(RvReportBuild.Ready(RvRedaction()))
        val ran = mutableListOf<String>()
        val actions = RvReportActions(github = { ran += "github" }, share = { ran += "share" })
        compose.setContent { Themed { RvReportGithubScreen(build.value, rvSampleReport, redaction.value, { redaction.value = it }, onBack = {}, actions = actions) } }
        compose.onNodeWithText(rvText(R.string.rv_redact_apps)).performClick()
        compose.onNodeWithText(rvText(R.string.rv_report_building)).assertExists()
        compose.onNodeWithText(rvText(R.string.rv_report_github)).performClick()
        compose.onNodeWithText(rvText(R.string.rv_report_share)).performClick()
        assertEquals(emptyList<String>(), ran)
        build.value = RvReportBuild.Ready(redaction.value)
        compose.onNodeWithText(rvText(R.string.rv_report_building)).assertDoesNotExist()
        compose.onNodeWithText(rvText(R.string.rv_report_github)).performClick()
        compose.onNodeWithText(rvText(R.string.rv_report_share)).performClick()
        assertEquals(listOf("github", "share"), ran)
    }

    @Test
    @Config(qualifiers = BUILDING)
    fun theActionsWaitForTheReport() {
        val build = mutableStateOf<RvReportBuild>(RvReportBuild.Building)
        val ran = mutableListOf<String>()
        val actions = RvReportActions(github = { ran += "github" }, share = { ran += "share" })
        compose.setContent { Themed { RvReportGithubScreen(build.value, rvSampleReport, RvRedaction(), {}, onBack = {}, actions = actions) } }
        for (state in listOf(RvReportBuild.Building, RvReportBuild.Failed)) {
            build.value = state
            for (id in listOf(R.string.rv_report_github, R.string.rv_report_share)) {
                compose.onNode(hasText(rvText(id)) and IS_BUTTON).assertIsNotEnabled().performClick()
            }
        }
        assertEquals(emptyList<String>(), ran)
    }

    @Test
    @Config(qualifiers = FAILED)
    fun aFailedBuildIsToldAndRetried() {
        var retries = 0
        compose.setContent {
            Themed { RvReportGithubScreen(RvReportBuild.Failed, rvSampleReport, RvRedaction(), {}, onBack = {}, actions = RvReportActions(retry = { retries++ })) }
        }
        compose.onNodeWithText(rvText(R.string.rv_report_failed)).assertExists()
        compose.onNodeWithText(rvText(R.string.rv_retry)).performClick()
        assertEquals(1, retries)
    }

    private fun shot(name: String, build: RvReportBuild, dark: Boolean = false, fontScale: Float = 1f) {
        if (build == RvReportBuild.Building) rvAnimatorScale(0f)
        compose.rvShot("settings-$name", dark, before = { assertWhole() }, fontScale = fontScale) { Page(build) }
    }

    @Composable
    private fun Page(build: RvReportBuild) = RvReportGithubScreen(build, rvSampleReport, RvRedaction(), onRedaction = {}, onBack = {})

    @Composable
    private fun Themed(content: @Composable () -> Unit) = RvTheme(seedColorScheme(Seed.Cyan.argb, false), false, content)

    private companion object {
        val READY_BUILD = RvReportBuild.Ready(RvRedaction())
        val IS_BUTTON = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

        /** As tall as the page in each state and font size (to the next whole dp), so its end sits above the actions as on the phone. */
        const val READY = "+h1132dp"
        const val READY_FS115 = "+h1139dp"
        const val READY_FS200 = "+h1719dp"
        const val BUILDING = "+h1206dp"
        const val BUILDING_FS115 = "+h1213dp"
        const val BUILDING_FS200 = "+h1850dp"
        const val FAILED = "+h1282dp"
        const val FAILED_FS115 = "+h1294dp"
        const val FAILED_FS200 = "+h1944dp"
    }
}
