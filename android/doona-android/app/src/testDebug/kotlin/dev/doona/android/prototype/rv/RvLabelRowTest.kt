package dev.doona.android.prototype.rv

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.TextUnit
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RV_NAV
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.RvTypeScale
import dev.doona.android.prototype.rv.settings.RvSettingsScreen
import dev.doona.android.prototype.rv.settings.RvUpdate
import dev.doona.android.prototype.rv.settings.rvSampleSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Controls in one row are one size: a row of labels takes the largest size at which every one of them fits, so none
 * shrinks alone. In English, Disconnect does not fit its column on 活動 at the full size, nor Connections its tab, so
 * those rows are smaller than the full size, all of their labels alike. Outbound mode, Test latency (活動) and Check for
 * updates (設定) are drawn as Mode, Latency and Updates, so that they do not shrink their rows further (owner-1125b item 3);
 * the button is still named, and the row and menus still say, the full wording.
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
        // 活動's outbound card repeats Outbound mode; the action row is above it, the nav below everything.
        val actions = assertOneSize("活動's actions", listOf(R.string.rv_disconnect, R.string.rv_action_mode, R.string.rv_action_latency, R.string.rv_more), top = true)
        // Disconnect is the longest left: the row measured 11.25 sp with the full wordings, 14.75 sp with Mode, 15.75 sp with Latency.
        assertEquals("活動's actions: $actions", 15.75f, actions.value, 0.01f)
        assertTrue("活動's actions shrink together: $actions", actions.value < RvTypeScale.action.size)
        val tabs = assertOneSize("the nav's tabs", RV_NAV.map { it.label }, top = false)
        assertTrue("the nav's tabs shrink together: $tabs", tabs.value < RvTypeScale.navLabel.size)
    }

    /** Export and Updates keep the full size (9.75 sp with Check for updates beside Export). */
    @Test
    fun settingsTwoActionsAreOneSizeAtTheFullSize() {
        compose.setContent { Themed { RvSettingsScreen(rvSampleSettings(), onChange = {}, onOpenTheme = {}) } }
        val actions = assertOneSize("設定's actions", listOf(R.string.rv_settings_export_diagnostics, R.string.rv_settings_check_update_short), top = true)
        assertEquals("設定's actions: $actions", RvTypeScale.action.size.toFloat(), actions.value, 0.01f)
    }

    /** With nothing newer the action says Up to date, as before, and the row keeps the full size too. */
    @Test
    fun settingsLatestStateKeepsItsWordAtTheFullSize() {
        compose.setContent { Themed { RvSettingsScreen(rvSampleSettings(), onChange = {}, onOpenTheme = {}, update = RvUpdate.Latest) } }
        val actions = assertOneSize("設定's actions", listOf(R.string.rv_settings_export_diagnostics, R.string.rv_update_latest), top = true)
        assertEquals("設定's actions: $actions", RvTypeScale.action.size.toFloat(), actions.value, 0.01f)
        compose.onAllNodes(hasText(rvText(R.string.rv_settings_check_update_short)) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).assertCountIs(0, "Updates is the idle word only")
        assertNull("Up to date names itself", button(R.string.rv_update_latest).config.getOrNull(SemanticsProperties.ContentDescription))
    }

    /** A screen reader finds the full wording on each shortened action, and the row and the card still say it. */
    @Test
    fun theShortenedActionsAreNamedByTheirFullWording() {
        compose.setContent { Themed { RvActivityScreen(rvSampleActivity(), onAction = {}, onConnect = {}) } }
        assertEquals(listOf(rvText(R.string.act_mode)), button(R.string.rv_action_mode).config[SemanticsProperties.ContentDescription])
        assertEquals(listOf(rvText(R.string.rv_test_latency)), button(R.string.rv_action_latency).config[SemanticsProperties.ContentDescription])
        assertNull("Disconnect names itself", button(R.string.rv_disconnect).config.getOrNull(SemanticsProperties.ContentDescription))
        // Only the card's row title is left with the full words; no action draws them.
        compose.onAllNodesWithText(rvText(R.string.act_mode)).assertCountIs(1, "Outbound mode, once: the card's row")
        compose.onAllNodesWithText(rvText(R.string.rv_test_latency)).assertCountIs(0, "Test latency is not drawn on 活動")
    }

    @Test
    fun theUpdateActionIsNamedByCheckForUpdates() {
        compose.setContent { Themed { RvSettingsScreen(rvSampleSettings(), onChange = {}, onOpenTheme = {}) } }
        assertEquals(listOf(rvText(R.string.rv_settings_check_update)), button(R.string.rv_settings_check_update_short).config[SemanticsProperties.ContentDescription])
        compose.onAllNodesWithText(rvText(R.string.rv_settings_check_update)).assertCountIs(1, "Check for updates, once: 關於's row")
    }

    /** The button drawn with the text [id], the topmost one where a row below repeats it. */
    private fun button(@StringRes id: Int) =
        compose.onAllNodes(hasText(rvText(id)) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).onFirst().fetchSemanticsNode()

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
