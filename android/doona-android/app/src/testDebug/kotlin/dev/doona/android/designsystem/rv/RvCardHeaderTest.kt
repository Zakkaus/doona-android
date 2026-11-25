package dev.doona.android.designsystem.rv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.testing.Seed
import dev.doona.android.testing.seedColorScheme
import dev.doona.android.testing.setDoonaContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A card header's title keeps its width: the tag sits after it when there is room and moves under it when there is not. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvCardHeaderTest {
    @get:Rule
    val compose = createComposeRule()

    private fun header(widthDp: Int) = compose.setDoonaContent {
        RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) {
            Box(Modifier.width(widthDp.dp)) { RvCardHeader(TITLE, tag = TAG) }
        }
    }

    private fun node(text: String) = compose.onNode(hasText(text), useUnmergedTree = true).fetchSemanticsNode()

    private fun titleIsWhole() {
        val layout = mutableListOf<TextLayoutResult>().also { node(TITLE).config[SemanticsActions.GetTextLayoutResult].action?.invoke(it) }.single()
        assertFalse("$TITLE is cut", (0 until layout.lineCount).any(layout::isLineEllipsized) || layout.hasVisualOverflow)
    }

    @Test
    fun withRoomTheTagSitsAfterTheTitle() {
        header(WIDE)
        val title = node(TITLE).boundsInRoot
        val tag = node(TAG).boundsInRoot
        titleIsWhole()
        assertTrue("the tag is after the title", tag.left > title.right)
        assertEquals("on the same line", title.center.y, tag.center.y, 1f)
    }

    @Test
    fun withoutRoomTheTagMovesUnderTheTitle() {
        header(NARROW)
        val title = node(TITLE).boundsInRoot
        val tag = node(TAG).boundsInRoot
        titleIsWhole()
        assertTrue("the tag is under the title", tag.top >= title.bottom)
    }

    private companion object {
        const val TITLE = "已用流量"
        const val TAG = "全部訂閱"

        /** Room for both; and a width that holds the title but not the tag beside it (統計's 已用流量 column is about this). */
        const val WIDE = 360
        const val NARROW = 150
    }
}
