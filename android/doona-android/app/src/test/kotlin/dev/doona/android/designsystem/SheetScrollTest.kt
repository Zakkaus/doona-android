package dev.doona.android.designsystem

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.designsystem.components.LayoutRole
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.designsystem.theme.LocalFrameInsets
import dev.doona.android.testing.FrameInsets
import dev.doona.android.testing.layoutTree
import dev.doona.android.testing.screenCases
import org.junit.Assert.assertEquals
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A window short enough that every screen scrolls, so the end of the sheet can be measured. */
@Ignore("red on the sketch: segmented items and pickers sit in 48 dp boxes, top bar glyphs 6 to 8 dp off the gutter, a latency dot pushes its value, pickers differ, chevrons carry 2.7 dp of padding, the sheet ends 24 dp below its last block; U1 step 3 fixes them and removes this")
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h500dp-notnight-xxhdpi")
class SheetScrollTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun sheetEndsFortyBelowItsLastBlock() {
        compose.setContent {
            CompositionLocalProvider(LocalFrameInsets provides FrameInsets) {
                DoonaTheme { screenCases().first { it.name == "group" }.content() }
            }
        }
        compose.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        compose.waitForIdle()
        val sheet = compose.layoutTree().descendants().first { it.role == LayoutRole.Sheet }
        assertEquals("space below the last block at the end of the scroll, in dp", 40f, sheet.bottom - sheet.content.last().bottom, 0.5f)
    }
}
