package dev.doona.android.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.designsystem.rv.RvSheetLayout
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.testing.Seed
import dev.doona.android.testing.seedColorScheme
import dev.doona.android.testing.setDoonaContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A sheet that fits keeps its drag down to close; one taller than the window scrolls to its last row instead. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xxhdpi")
class RvSheetScrollTest {
    @get:Rule
    val compose = createComposeRule()

    private var dismissed = 0

    private fun sheet(rows: Int) =
        compose.setDoonaContent {
            RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) {
                RvSheetLayout({ 1f }, { dismissed++ }, "sheet") {
                    repeat(rows) { Box(Modifier.fillMaxWidth().height(ROW).testTag("row$it")) }
                }
            }
        }

    private fun dragDown(tag: String) = compose.onNodeWithTag(tag).performTouchInput { swipe(center, center + Offset(0f, height * 6f), DRAG_MS) }

    @Test
    fun aSheetThatFitsStillClosesOnADragDown() {
        sheet(rows = 2)
        dragDown("row0")
        compose.runOnIdle { assertEquals(1, dismissed) }
    }

    @Test
    fun aSheetTallerThanTheWindowScrollsToItsLastRow() {
        sheet(rows = 30)
        compose.onNodeWithTag("row29").assertIsNotDisplayed()
        compose.onNodeWithTag("row29").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("row0").assertIsNotDisplayed()
        compose.runOnIdle { assertEquals(0, dismissed) }
    }

    private companion object {
        val ROW = 56.dp
        const val DRAG_MS = 300L
    }
}
