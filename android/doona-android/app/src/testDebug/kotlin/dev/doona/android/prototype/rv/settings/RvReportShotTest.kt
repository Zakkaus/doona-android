package dev.doona.android.prototype.rv.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RV_MENU
import dev.doona.android.designsystem.rv.RvMenuItem
import dev.doona.android.designsystem.rv.RvMenuPanel
import dev.doona.android.designsystem.rv.RvMenuSize
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.prototype.rv.RV_FS115
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Seed
import dev.doona.android.prototype.rv.rvAnimatorScale
import dev.doona.android.prototype.rv.rvShot
import dev.doona.android.prototype.rv.rvText
import dev.doona.android.prototype.rv.seedColorScheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 回報問題 (`proto-rv-report-*`): at rest, after a failed export (`-report-failed-*`), while it exports
 * (`-report-exporting-*`, at animator scale 0, so the busy bar rests half full), and with the level menu open
 * (`-report-menu-*`, its card drawn where the popup puts it, under the row's end); each light, dark, fs115 and fs200.
 * Then what the rows do: 日誌等級 opens the four levels and a pick sets the level, 匯出診斷報告 exports, an export under
 * way takes no tap, and the notice's 重試 exports again.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvReportShotTest {
    @get:Rule
    val compose = createComposeRule()

    @After
    fun restoreScale() = rvAnimatorScale(1f)

    @Test fun light() = shot("report", RvExport.Idle, dark = false)

    @Test fun dark() = shot("report", RvExport.Idle, dark = true)

    @Test fun fs115() = shot("report", RvExport.Idle, fontScale = RV_FS115)

    @Test fun fs200() = shot("report", RvExport.Idle, fontScale = RV_FS200)

    @Test fun failedLight() = shot("report-failed", RvExport.Failed, dark = false)

    @Test fun failedDark() = shot("report-failed", RvExport.Failed, dark = true)

    @Test fun failedFs115() = shot("report-failed", RvExport.Failed, fontScale = RV_FS115)

    @Test fun failedFs200() = shot("report-failed", RvExport.Failed, fontScale = RV_FS200)

    @Test fun exportingLight() = shot("report-exporting", RvExport.Exporting, dark = false)

    @Test fun exportingDark() = shot("report-exporting", RvExport.Exporting, dark = true)

    @Test fun exportingFs115() = shot("report-exporting", RvExport.Exporting, fontScale = RV_FS115)

    @Test fun exportingFs200() = shot("report-exporting", RvExport.Exporting, fontScale = RV_FS200)

    @Test fun menuLight() = menu(dark = false)

    @Test fun menuDark() = menu(dark = true)

    @Test fun menuFs115() = menu(fontScale = RV_FS115)

    @Test fun menuFs200() = menu(fontScale = RV_FS200)

    @Test
    fun theLevelRowOpensTheFourLevelsAndAPickSetsIt() {
        var picked: RvLogLevel? = null
        compose.setContent { Themed { RvReportScreen(RvLogLevel.Info, RvExport.Idle, onLevel = { picked = it }, onExport = {}, onBack = {}) } }
        compose.onAllNodesWithTag(RV_MENU).assertCountEquals(0)
        compose.onNodeWithText(rvText(R.string.rv_settings_log_level)).performClick()
        compose.onAllNodesWithTag(RV_MENU).assertCountEquals(1)
        compose.onNodeWithText(rvText(R.string.rv_log_debug)).performClick()
        assertEquals(RvLogLevel.Debug, picked)
        compose.onAllNodesWithTag(RV_MENU).assertCountEquals(0)
    }

    @Test
    fun theExportRowExportsAndTakesNoTapWhileItRuns() {
        val export = mutableStateOf(RvExport.Idle)
        var exports = 0
        compose.setContent { Themed { RvReportScreen(RvLogLevel.Info, export.value, onLevel = {}, onExport = { exports++ }, onBack = {}) } }
        compose.onNodeWithText(rvText(R.string.rv_report_export)).performClick()
        assertEquals(1, exports)
        export.value = RvExport.Exporting
        compose.waitForIdle()
        val row = compose.onNodeWithText(rvText(R.string.rv_report_export)).fetchSemanticsNode()
        assertFalse("an export under way takes no tap", row.config.contains(SemanticsActions.OnClick))
    }

    @Test
    fun aFailedExportIsToldInTheNoticeAndRetried() {
        var exports = 0
        compose.setContent { Themed { RvReportScreen(RvLogLevel.Info, RvExport.Failed, onLevel = {}, onExport = { exports++ }, onBack = {}) } }
        compose.onNodeWithText(rvText(R.string.rv_report_failed)).assertExists()
        compose.onNodeWithText(rvText(R.string.rv_retry)).performClick()
        assertEquals(1, exports)
    }

    private fun shot(name: String, export: RvExport, dark: Boolean = false, fontScale: Float = 1f) {
        if (export == RvExport.Exporting) rvAnimatorScale(0f)
        compose.rvShot(name, dark, fontScale = fontScale) { Page(export) }
    }

    /**
     * The page with the level menu's card where [RvMenu][dev.doona.android.prototype.rv.RvMenu] puts its popup: its end
     * on the row's end (the card's, on the gutter) and its top [RvMenuSize.anchorGap] under the row. A popup is a window
     * of its own, which a capture of the page's root leaves out, so the card is drawn in the page's window instead.
     */
    private fun menu(dark: Boolean = false, fontScale: Float = 1f) {
        val top = mutableStateOf<Dp?>(null)
        compose.rvShot("report-menu", dark, before = { top.value = levelRowBottom() + RvMenuSize.anchorGap }, fontScale = fontScale) {
            Box(Modifier.fillMaxSize()) {
                Page(RvExport.Idle)
                top.value?.let { RvMenuPanel(levels(), onPick = {}, Modifier.align(Alignment.TopEnd).padding(top = it, end = RvSpace.gutter)) }
            }
        }
    }

    /** Where the 日誌等級 row ends, under the page's top. */
    private fun ComposeContentTestRule.levelRowBottom(): Dp {
        val row = onNodeWithText(rvText(R.string.rv_settings_log_level)).fetchSemanticsNode()
        return (row.boundsInRoot.bottom / density.density).dp
    }

    @Composable
    private fun levels() = RvLogLevel.entries.map { RvMenuItem(stringResource(it.label), it.icon) {} }

    @Composable
    private fun Page(export: RvExport) = RvReportScreen(RvLogLevel.Info, export, onLevel = {}, onExport = {}, onBack = {})

    @Composable
    private fun Themed(content: @Composable () -> Unit) = RvTheme(seedColorScheme(Seed.Cyan.argb, false), false, content)
}
