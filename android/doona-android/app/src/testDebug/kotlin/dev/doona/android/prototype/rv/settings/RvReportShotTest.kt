package dev.doona.android.prototype.rv.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvControlSize
import dev.doona.android.designsystem.rv.RvPart
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.feature.report.RvRedaction
import dev.doona.android.feature.report.RvReportActions
import dev.doona.android.feature.report.RvReportBuild
import dev.doona.android.feature.report.RvReportScreen
import dev.doona.android.prototype.rv.RV_FS115
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Seed
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.rvAnimatorScale
import dev.doona.android.prototype.rv.rvContent
import dev.doona.android.prototype.rv.rvShot
import dev.doona.android.prototype.rv.rvText
import dev.doona.android.prototype.rv.seedColorScheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 回報問題, each state light, dark, fs115 and fs200 in one frame as tall as the page: ready (`proto-rv-report-*`), while
 * the report is written (`-report-building-*`, at animator scale 0, so the busy bar rests half full) and after it failed
 * (`-report-failed-*`). Then what the switches start as and do, what the actions do, and that the actions wait for a
 * report written with the switches as they stand.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvReportShotTest {
    @get:Rule
    val compose = createComposeRule()

    @After
    fun restoreScale() = rvAnimatorScale(1f)

    @Test
    @Config(qualifiers = READY)
    fun light() = shot("report", READY_BUILD, dark = false)

    @Test
    @Config(qualifiers = READY)
    fun dark() = shot("report", READY_BUILD, dark = true)

    @Test
    @Config(qualifiers = READY_FS115)
    fun fs115() = shot("report", READY_BUILD, fontScale = RV_FS115)

    @Test
    @Config(qualifiers = READY_FS200)
    fun fs200() = shot("report", READY_BUILD, fontScale = RV_FS200)

    @Test
    @Config(qualifiers = BUILDING)
    fun buildingLight() = shot("report-building", RvReportBuild.Building, dark = false)

    @Test
    @Config(qualifiers = BUILDING)
    fun buildingDark() = shot("report-building", RvReportBuild.Building, dark = true)

    @Test
    @Config(qualifiers = BUILDING_FS115)
    fun buildingFs115() = shot("report-building", RvReportBuild.Building, fontScale = RV_FS115)

    @Test
    @Config(qualifiers = BUILDING_FS200)
    fun buildingFs200() = shot("report-building", RvReportBuild.Building, fontScale = RV_FS200)

    @Test
    @Config(qualifiers = FAILED)
    fun failedLight() = shot("report-failed", RvReportBuild.Failed, dark = false)

    @Test
    @Config(qualifiers = FAILED)
    fun failedDark() = shot("report-failed", RvReportBuild.Failed, dark = true)

    @Test
    @Config(qualifiers = FAILED_FS115)
    fun failedFs115() = shot("report-failed", RvReportBuild.Failed, fontScale = RV_FS115)

    @Test
    @Config(qualifiers = FAILED_FS200)
    fun failedFs200() = shot("report-failed", RvReportBuild.Failed, fontScale = RV_FS200)

    @Test
    @Config(qualifiers = READY)
    fun eachSwitchTurnsItsRedactionAndTheActionsRun() {
        val redaction = mutableStateOf(RvRedaction())
        val ran = mutableListOf<String>()
        val actions = rvReportActions(github = { ran += "github" }, share = { ran += "share" })
        compose.setContent { Themed { RvReportScreen(RvReportBuild.Ready(redaction.value), rvSampleReport, redaction.value, { redaction.value = it }, onBack = {}, actions = actions) } }
        compose.onNodeWithText(rvText(R.string.rv_redact_nodes)).assertIsNotEnabled()
        for (id in listOf(R.string.rv_redact_ip, R.string.rv_redact_domains, R.string.rv_redact_apps)) {
            compose.onNodeWithText(rvText(id)).performSemanticsAction(SemanticsActions.OnClick)
            compose.waitForIdle()
        }
        assertEquals(RvRedaction(nodes = true, ip = false, domains = false, apps = true), redaction.value)
        compose.onNodeWithText(rvText(R.string.rv_report_github)).performClick()
        compose.onNodeWithText(rvText(R.string.rv_report_share)).performClick()
        assertEquals(listOf("github", "share"), ran)
    }

    /** The pair is one part: both pills as tall and as wide. */
    @Test
    @Config(qualifiers = READY)
    fun thePairIsOneSize() {
        compose.setContent { Themed { Page(READY_BUILD) } }
        val github = compose.onNodeWithText(rvText(R.string.rv_report_github)).getUnclippedBoundsInRoot()
        val share = compose.onNodeWithText(rvText(R.string.rv_report_share)).getUnclippedBoundsInRoot()
        assertEquals(RvControlSize.primary.value, github.height.value, 0.5f)
        assertEquals(github.height.value, share.height.value, 0.5f)
        assertEquals(github.width.value, share.width.value, 0.5f)
    }

    /**
     * Four switches, each on where it hides its part: node addresses, my IPs and the visited domains start hidden, the app
     * names shown (the owner, owner-fix2 item 11). Under them one paragraph: that hiding more makes the problem harder to
     * find, then what a report never holds.
     */
    @Test
    @Config(qualifiers = READY)
    fun threeThingsStartHiddenAndAppNamesShown() {
        compose.setContent { Themed { Page(READY_BUILD) } }
        compose.onAllNodes(isToggleable()).assertCountEquals(4)
        for (id in listOf(R.string.rv_redact_nodes, R.string.rv_redact_ip, R.string.rv_redact_domains)) {
            compose.onNodeWithText(rvText(id)).assertIsOn()
        }
        compose.onNodeWithText(rvText(R.string.rv_redact_apps)).assertIsOff()
        val apps = compose.onNodeWithText(rvText(R.string.rv_redact_apps)).getUnclippedBoundsInRoot()
        val footer = compose.onNodeWithText(rvText(R.string.rv_redact_footer)).getUnclippedBoundsInRoot()
        assertTrue("the footnote stands under the switches", footer.top > apps.bottom)
    }

    /**
     * The footnote spans the cards' text column and is laid out that wide, so its first line runs to the column's end
     * (owner-fix3 item 29, then q-s1125c-footgap 19:05: 「右邊也內縮到 32 dp」, the end in by the card's text inset as
     * the start; it ran to the card's edge before).
     */
    @Test
    @Config(qualifiers = READY)
    fun theFootnoteRunsToTheCardsTextColumnEnd() = footnoteSpansTheCards(1f)

    @Test
    @Config(qualifiers = READY_FS200)
    fun theFootnoteRunsToTheCardsTextColumnEndAtFs200() = footnoteSpansTheCards(RV_FS200)

    private fun footnoteSpansTheCards(fontScale: Float) {
        compose.rvContent(dark = false, fontScale = fontScale) { Page(READY_BUILD) }
        val card = compose.onAllNodesWithTag(RvPart.CARD).fetchSemanticsNodes().last().boundsInRoot
        val node = compose.onNodeWithTag(RvPart.FOOTER).fetchSemanticsNode()
        val box = node.boundsInRoot
        assertEquals("starts at the cards' text inset", card.left + RvSpace.rowPadding.value * compose.density.density, box.left, EDGE_PX)
        assertEquals("ends at the cards' text inset from their right edge", card.right - RvSpace.rowPadding.value * compose.density.density, box.right, EDGE_PX)
        val layouts = mutableListOf<TextLayoutResult>().also { node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(it) }
        val layout = layouts.single()
        assertEquals("laid out as wide as its box", box.width, layout.layoutInput.constraints.maxWidth.toFloat(), EDGE_PX)
        assertTrue("one paragraph that wraps", layout.lineCount >= 2)
    }

    /** What a report never holds is never cut: on a narrow English screen at 2.0 the paragraph grows past three lines. */
    @Test
    @Config(qualifiers = "en-w320dp-h2600dp-notnight-xhdpi")
    fun thePrivacyFootnoteGrowsRatherThanBeingCut() {
        compose.rvContent(dark = false, fontScale = RV_FS200) { Page(READY_BUILD) }
        val node = compose.onNodeWithTag(RvPart.FOOTER).fetchSemanticsNode()
        val layout = mutableListOf<TextLayoutResult>().also { node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(it) }.single()
        assertTrue("wraps past three lines: ${layout.lineCount}", layout.lineCount > 3)
        assertFalse("cut", layout.hasVisualOverflow)
    }

    /** A report written before a switch turned hides other things: it is not shared until it is written again. */
    @Test
    @Config(qualifiers = READY)
    fun aSwitchHoldsTheActionsUntilTheReportIsWrittenAgain() {
        val redaction = mutableStateOf(RvRedaction())
        val build = mutableStateOf<RvReportBuild>(RvReportBuild.Ready(RvRedaction()))
        val ran = mutableListOf<String>()
        val actions = rvReportActions(github = { ran += "github" }, share = { ran += "share" })
        compose.setContent { Themed { RvReportScreen(build.value, rvSampleReport, redaction.value, { redaction.value = it }, onBack = {}, actions = actions) } }
        compose.onNodeWithText(rvText(R.string.rv_redact_ip)).performClick()
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
        val actions = rvReportActions(github = { ran += "github" }, share = { ran += "share" })
        compose.setContent { Themed { RvReportScreen(build.value, rvSampleReport, RvRedaction(), {}, onBack = {}, actions = actions) } }
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
            Themed { RvReportScreen(RvReportBuild.Failed, rvSampleReport, RvRedaction(), {}, onBack = {}, actions = rvReportActions(retry = { retries++ })) }
        }
        compose.onNodeWithText(rvText(R.string.rv_report_failed)).assertExists()
        compose.onNodeWithText(rvText(R.string.rv_retry)).performClick()
        assertEquals(1, retries)
    }

    private fun shot(name: String, build: RvReportBuild, dark: Boolean = false, fontScale: Float = 1f) {
        if (build == RvReportBuild.Building) rvAnimatorScale(0f)
        compose.rvShot(name, dark, before = { assertWhole() }, fontScale = fontScale) { Page(build) }
    }

    @Composable
    private fun Page(build: RvReportBuild) = RvReportScreen(build, rvSampleReport, RvRedaction(), onRedaction = {}, onBack = {}, actions = rvReportActions())

    @Composable
    private fun Themed(content: @Composable () -> Unit) = RvTheme(seedColorScheme(Seed.Cyan.argb, false), false, content)

    private companion object {
        val READY_BUILD = RvReportBuild.Ready(RvRedaction())

        /** Half a dp at xhdpi: bounds rounded to the pixel. */
        const val EDGE_PX = 1f
        val IS_BUTTON = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

        /** As tall as the page in each state and font size (to the next whole dp), so its end sits above the actions as on the phone. */
        const val READY = "+h1177dp"
        const val READY_FS115 = "+h1213dp"
        const val READY_FS200 = "+h1820dp"
        const val BUILDING = "+h1259dp"
        const val BUILDING_FS115 = "+h1295dp"
        const val BUILDING_FS200 = "+h1959dp"
        const val FAILED = "+h1335dp"
        const val FAILED_FS115 = "+h1376dp"
        const val FAILED_FS200 = "+h2053dp"
    }
}
