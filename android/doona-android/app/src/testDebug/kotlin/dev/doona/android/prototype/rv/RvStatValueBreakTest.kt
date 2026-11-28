package dev.doona.android.prototype.rv

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import dev.doona.android.designsystem.rv.RvSettingsCard
import dev.doona.android.designsystem.rv.RvSettingsColumn
import dev.doona.android.designsystem.rv.RvStatRow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A stat row's value that wraps breaks between its parts, never inside one: a target triple at 200 % breaks after a
 * hyphen (`aarch64-linux-` over `android`), not inside `android` (diag 總覽, 2026-11-27).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvStatValueBreakTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aTargetTripleBreaksAtAHyphen() {
        compose.rvContent(dark = false, fontScale = RV_FS200) {
            RvSettingsColumn { RvSettingsCard { RvStatRow("目標平台", TRIPLE) } }
        }
        compose.waitForIdle()
        val layout = layout()
        assertTrue("$TRIPLE fits one line here, so the test shows nothing", layout.lineCount > 1)
        val text = layout.layoutInput.text.text
        for (line in 0 until layout.lineCount - 1) {
            val end = layout.getLineEnd(line)
            val before = text.substring(0, end).lastOrNull { it.isVisible() }
            val after = text.substring(end).firstOrNull { it.isVisible() }
            assertTrue("line ${line + 1} ends inside a word: ${text.substring(0, end)} | ${text.substring(end)}", before == null || after == null || !(before.isLetterOrDigit() && after.isLetterOrDigit()))
        }
    }

    private fun layout(): TextLayoutResult {
        val node = compose.onNode(hasText(TRIPLE), useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single()
    }

    private fun Char.isVisible() = this != '​' && this != '⁠'

    private companion object {
        const val TRIPLE = "aarch64-linux-android"
    }
}
