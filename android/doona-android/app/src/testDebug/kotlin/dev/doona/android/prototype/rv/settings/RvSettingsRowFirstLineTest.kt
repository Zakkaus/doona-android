package dev.doona.android.prototype.rv.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvPart
import dev.doona.android.designsystem.rv.RvSettingsRow
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
 * A settings row's leading disc stands as Revolut's settings icon does: on the row's centre in a row of one line, its top
 * on the title's top in a row with a subtitle of one or more lines (Appearance and Language cards), and the trailing
 * control stays on the title's first line.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvSettingsRowFirstLineTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theDiscCentresOnARowOfOneLineAndTopsTheTitleOfALongerOne() {
        compose.setContent {
            RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) {
                Column {
                    ROWS.forEachIndexed { i, (title, subtitle) ->
                        RvSettingsRow(title, icon = R.drawable.rv_sym_settings, subtitle = subtitle) { Box(Modifier.size(RvSize.actionGlyph).testTag("$END$i")) }
                    }
                }
            }
        }
        val discs = compose.onAllNodesWithTag(RvPart.DISC, useUnmergedTree = true).fetchSemanticsNodes()
        val rows = compose.onAllNodesWithTag(RvPart.ROW, useUnmergedTree = true).fetchSemanticsNodes()
        ROWS.forEachIndexed { i, (title, subtitle) ->
            val text = compose.onNodeWithText(title, useUnmergedTree = true).fetchSemanticsNode()
            val lines = 1 + (subtitle?.let { layout(compose.onNodeWithText(it, useUnmergedTree = true).fetchSemanticsNode()).lineCount } ?: 0)
            assertEquals("$title: the row's lines", i + 1, lines)
            val disc = discs[i]
            val slack = SLACK * disc.layoutInfo.density.density
            if (subtitle == null) {
                val row = rows[i]
                assertEquals("$title: disc centre on the row's", centre(row), centre(disc), slack)
            } else {
                assertEquals("$title: disc top on the title's", text.positionInRoot.y, disc.positionInRoot.y, slack)
            }
            val first = layout(text)
            val line = text.positionInRoot.y + (first.getLineTop(0) + first.getLineBottom(0)) / 2
            val end = compose.onAllNodesWithTag("$END$i", useUnmergedTree = true).fetchSemanticsNodes().single()
            // The end centres in the first line's box (a one-line row's 26 dp at 1.0), 1.5 dp under the title line's centre.
            assertEquals("$title: end on the first line", line, centre(end), FIRST_LINE_SLACK * disc.layoutInfo.density.density)
        }
    }

    private fun centre(node: SemanticsNode) = node.positionInRoot.y + node.size.height / 2f

    private fun layout(node: SemanticsNode): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single()
    }

    private companion object {
        val ROWS = listOf(
            "語言" to null,
            "永久連線" to "開機後自動連線",
            "斷線時通知" to "在系統的 VPN 設定中開啟永久連線，手機開機後就會自動連線。",
            "依 App" to "在系統的 VPN 設定中開啟永久連線，手機開機後就會自動連線；封鎖未使用 VPN 的連線時，doona 無法連到任何節點，會改為回報失敗。",
        )
        const val END = "end"
        const val SLACK = 1f
        const val FIRST_LINE_SLACK = 1.5f
    }
}
