package dev.doona.android.prototype.rv.config

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.prototype.rv.RV_FS115
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.RvDiffAt
import dev.doona.android.prototype.rv.Seed
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.rvScrollBy
import dev.doona.android.prototype.rv.rvShot
import dev.doona.android.prototype.rv.seedColorScheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * doona's templates on the configuration side (Round 2 item 2), samples only: choosing 依地區分流 for a new
 * configuration, with its region and service groups (`proto-rv-config-template-new-*`), and 依服務分流 applied over
 * 繞過中國大陸 through the diff (`proto-rv-config-template-diff-*`). Light and dark hold the whole page; fs115 and fs200
 * the first screen, and the new configuration's page scrolled to its end (`proto-rv-config-template-new-end-*`), where
 * the last row stands clear of the floating 新增設定檔.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvConfigTemplateShotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    @Config(qualifiers = WHOLE_NEW)
    fun newLight() = compose.rvShot("config-template-new", dark = false, before = { assertWhole() }) { New() }

    @Test
    @Config(qualifiers = WHOLE_NEW)
    fun newDark() = compose.rvShot("config-template-new", dark = true, before = { assertWhole() }) { New() }

    @Test
    fun newFs115() = compose.rvShot("config-template-new", dark = false, fontScale = RV_FS115) { New() }

    @Test
    fun newFs200() = compose.rvShot("config-template-new", dark = false, fontScale = RV_FS200) { New() }

    @Test
    fun newEndFs115() = compose.rvShot("config-template-new-end", dark = false, fontScale = RV_FS115, before = { rvScrollBy(TO_THE_END_DP) }) { New() }

    @Test
    fun newEndFs200() = compose.rvShot("config-template-new-end", dark = false, fontScale = RV_FS200, before = { rvScrollBy(TO_THE_END_DP) }) { New() }

    @Test
    @Config(qualifiers = WHOLE_DIFF)
    fun diffLight() = compose.rvShot("config-template-diff", dark = false, before = { assertWhole() }) { Diff() }

    @Test
    @Config(qualifiers = WHOLE_DIFF)
    fun diffDark() = compose.rvShot("config-template-diff", dark = true, before = { assertWhole() }) { Diff() }

    @Test
    fun diffFs115() = compose.rvShot("config-template-diff", dark = false, fontScale = RV_FS115) { Diff() }

    @Test
    fun diffFs200() = compose.rvShot("config-template-diff", dark = false, fontScale = RV_FS200) { Diff() }

    @Test
    fun choosingATemplateShowsItsGroups() {
        compose.setContent { Themed { Chooser(null, RvConfigTemplateData.bypass) } }
        compose.onNodeWithText(APPLE).assertDoesNotExist()
        compose.onNodeWithText(SERVICES).performScrollTo().performClick()
        compose.onNodeWithText(APPLE).performScrollTo()
        compose.onNodeWithText(CREATE).assertIsEnabled()
    }

    @Test
    fun theTemplateInUseCannotBeReviewed() {
        compose.setContent { Themed { Chooser(RvConfigTemplateData.bypass, RvConfigTemplateData.bypass) } }
        compose.onNodeWithText(REVIEW).assertIsNotEnabled()
        compose.onNodeWithText(SERVICES).performScrollTo().performClick()
        compose.onNodeWithText(REVIEW).assertIsEnabled()
    }

    @Test
    fun anOptionAloneCanBeReviewedAndTurningItBackCannot() {
        compose.setContent { Themed { Chooser(RvConfigTemplateData.bypass, RvConfigTemplateData.bypass) } }
        compose.onNodeWithText(REVIEW).assertIsNotEnabled()
        // Semantics clicks: scrolled into view, a row's foot may sit under the floating 檢視變更.
        compose.onNodeWithText("封鎖廣告").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText(REVIEW).assertIsEnabled()
        compose.onNodeWithText("封鎖廣告").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText(REVIEW).assertIsNotEnabled()
    }

    @Test
    fun optionsStartAsDoonasDefaults() {
        compose.setContent { Themed { Chooser(null, RvConfigTemplateData.regions) } }
        compose.onNodeWithText("封鎖廣告").performScrollTo().assertIsOff()
        compose.onNodeWithText("封鎖 QUIC").performScrollTo().assertIsOn()
        // A semantics click: scrolled into view, the row's foot sits under the floating 新增設定檔.
        compose.onNodeWithText("封鎖 QUIC").performScrollTo().assertIsOn().performSemanticsAction(SemanticsActions.OnClick).assertIsOff()
    }

    @Test
    fun aDiffLineOpensTheFileThere() {
        var opened: Pair<String, RvDiffAt>? = null
        compose.setContent { Themed { RvConfigTemplateApplyPage(RvConfigTemplateData.apply, onBack = {}, onOpen = { file, at -> opened = file to at }) } }
        compose.onNodeWithContentDescription("新增，第 54 行：  domain(geosite:gfw) -> proxy").performScrollTo().performClick()
        assertEquals(RvConfigData.ENTRY to RvDiffAt(54, base = false), opened)
    }

    @Composable
    private fun Themed(content: @Composable () -> Unit) = RvTheme(seedColorScheme(Seed.Cyan.argb, false), false, content)

    @Composable
    private fun Chooser(current: RvConfigTemplate?, first: RvConfigTemplate) {
        var selected by remember { mutableStateOf(first) }
        var options by remember { mutableStateOf(RvTemplateOptions()) }
        RvConfigTemplatePage(current, selected, options, { selected = it }, { options = it }, onBack = {})
    }

    @Composable
    private fun New() = RvConfigTemplatePage(null, RvConfigTemplateData.regions, RvTemplateOptions(), {}, {}, onBack = {})

    @Composable
    private fun Diff() = RvConfigTemplateApplyPage(RvConfigTemplateData.apply, onBack = {})

    private companion object {
        const val WHOLE_NEW = "+h2454dp"
        const val WHOLE_DIFF = "+h2454dp"

        /** Farther than the page at fs200 scrolls; the scroll stops at the end. */
        const val TO_THE_END_DP = 10_000f
        const val APPLE = "Apple"

        /** A title the page holds together with word joiners; its node reads the plain words. */
        const val SERVICES = "依服務分流"
        const val CREATE = "新增設定檔"
        const val REVIEW = "檢視變更"
    }
}
