package dev.doona.android.prototype.rv.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvPart
import dev.doona.android.designsystem.rv.RvSize
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.prototype.rv.Seed
import dev.doona.android.prototype.rv.seedColorScheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A settings row whose description runs to three lines keeps both ends on the title's first line, as Revolut's settings
 * rows do: the leading disc's centre is the trailing control's, on the title's first line.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvSettingsRowFirstLineTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun bothEndsCentreOnTheFirstLine() {
        compose.setContent {
            RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) {
                RvSettingsRow(TITLE, icon = R.drawable.rv_sym_settings, subtitle = DESCRIPTION) { Box(Modifier.size(RvSize.actionGlyph).testTag(END)) }
            }
        }
        val subtitle = compose.onNodeWithText(DESCRIPTION, useUnmergedTree = true).fetchSemanticsNode()
        assertEquals("the description wraps to three lines", 3, layout(subtitle).lineCount)
        val title = compose.onNodeWithText(TITLE, useUnmergedTree = true).fetchSemanticsNode()
        val first = layout(title)
        val line = title.positionInRoot.y + (first.getLineTop(0) + first.getLineBottom(0)) / 2
        val disc = compose.onNodeWithTag(RvPart.DISC, useUnmergedTree = true).fetchSemanticsNode()
        val end = compose.onNodeWithTag(END, useUnmergedTree = true).fetchSemanticsNode()
        val discCentre = disc.positionInRoot.y + disc.size.height / 2f
        val endCentre = end.positionInRoot.y + end.size.height / 2f
        assertEquals("disc and end share one centre", endCentre, discCentre, 0.5f)
        // Both centre in the first line's box, a one-line row's 26 dp at 1.0 (rvOneLine where the title's line is
        // taller): 1.5 dp under the centre of the title's own 23 dp line, as the end has sat since it moved there.
        assertEquals("disc centre on the first line", line, discCentre, FIRST_LINE_SLACK * disc.layoutInfo.density.density)
    }

    private fun layout(node: androidx.compose.ui.semantics.SemanticsNode): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single()
    }

    private companion object {
        const val TITLE = "永久連線"
        const val DESCRIPTION = "在系統的 VPN 設定中開啟永久連線，手機開機後就會自動連線；封鎖未使用 VPN 的連線時，doona 無法連到任何節點，會改為回報失敗。"
        const val END = "end"
        const val FIRST_LINE_SLACK = 1.5f
    }
}
