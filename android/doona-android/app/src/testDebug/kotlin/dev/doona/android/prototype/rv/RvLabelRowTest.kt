package dev.doona.android.prototype.rv

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.TextUnit
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RV_NAV
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.RvTypeScale
import dev.doona.android.prototype.rv.settings.RvSettingsScreen
import dev.doona.android.prototype.rv.settings.rvSampleSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Controls in one row are one size: a row of labels takes the largest size at which every one of them fits, so none
 * shrinks alone. In English, Test latency and Disconnect do not fit their columns on 活動 at the full size, nor
 * Connections its tab, so those rows are smaller than the full size, all of their labels alike.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RV_EN)
class RvLabelRowTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun activitysActionsAndTabsAreEachOneSmallerSize() {
        compose.setContent { Themed { RvActivityScreen(rvSampleActivity(), onAction = {}, onConnect = {}) } }
        // 活動's outbound card repeats Mode; the action row is above it, the nav below everything.
        val actions = assertOneSize("活動's actions", listOf(R.string.rv_disconnect, R.string.act_mode, R.string.rv_test_latency, R.string.rv_more), top = true)
        assertTrue("活動's actions shrink together: $actions", actions.value < RvTypeScale.action.size)
        val tabs = assertOneSize("the nav's tabs", RV_NAV.map { it.label }, top = false)
        assertTrue("the nav's tabs shrink together: $tabs", tabs.value < RvTypeScale.navLabel.size)
    }

    @Test
    fun settingsTwoActionsAreOneSize() {
        compose.setContent { Themed { RvSettingsScreen(rvSampleSettings(), onChange = {}, onOpenTheme = {}) } }
        assertOneSize("設定's actions", listOf(R.string.rv_settings_export_diagnostics, R.string.rv_settings_check_update), top = true)
    }

    /** The one size [labels] are laid out at; the label taken where a text shows twice is the [top] or bottom one. */
    private fun assertOneSize(row: String, labels: List<Int>, top: Boolean): TextUnit {
        val sizes = labels.associate { rvText(it) to fontSize(it, top) }
        assertEquals("$row: $sizes", 1, sizes.values.toSet().size)
        return sizes.values.first()
    }

    /** The size the label [id] is laid out at, after any fit. */
    private fun fontSize(@StringRes id: Int, top: Boolean): TextUnit {
        val nodes = compose.onAllNodesWithText(rvText(id), useUnmergedTree = true).fetchSemanticsNodes()
        val node = if (top) nodes.minBy { it.positionInRoot.y } else nodes.maxBy { it.positionInRoot.y }
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single().layoutInput.style.fontSize
    }

    @Composable
    private fun Themed(content: @Composable () -> Unit) = RvTheme(seedColorScheme(Seed.Cyan.argb, false), false, content)
}
