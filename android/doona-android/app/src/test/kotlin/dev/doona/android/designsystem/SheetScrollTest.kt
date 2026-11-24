package dev.doona.android.designsystem

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.designsystem.components.LayoutRole
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.testing.FrameInsets
import dev.doona.android.testing.SeedTheme
import dev.doona.android.testing.layoutTree
import dev.doona.android.testing.screenCases
import dev.doona.android.testing.sheetScroll
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A window short enough that every screen scrolls, so the end of the sheet can be measured. */
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
                SeedTheme { screenCases().first { it.name == "group" }.content() }
            }
        }
        compose.onNode(sheetScroll()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        compose.waitForIdle()
        val sheet = compose.layoutTree().descendants().first { it.role == LayoutRole.Sheet }
        assertEquals("space below the last block at the end of the scroll, in dp", 40f, sheet.bottom - sheet.content.last().bottom, 0.5f)
    }
}
