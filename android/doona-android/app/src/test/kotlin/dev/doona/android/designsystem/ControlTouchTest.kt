package dev.doona.android.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.designsystem.components.DoonaSegmented
import dev.doona.android.designsystem.theme.DoonaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val DENSITY = 3f

/** The segmented control's 36 dp track is hit over 48 dp: a tap just above or below an item still selects it. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-notnight-xxhdpi")
class ControlTouchTest {
    @get:Rule
    val compose = createComposeRule()

    private var selected by mutableIntStateOf(0)

    private fun tapOutsideTrack(above: Boolean) {
        compose.setContent {
            DoonaTheme {
                Column(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.height(100.dp))
                    DoonaSegmented(listOf("A", "B", "C"), selected, { selected = it })
                    Spacer(Modifier.height(100.dp))
                }
            }
        }
        val item = compose.onAllNodes(isSelectable()).fetchSemanticsNodes()[1].boundsInRoot
        val reach = 5 * DENSITY
        val y = if (above) item.top - reach else item.bottom + reach
        compose.onRoot().performTouchInput { click(Offset(item.center.x, y)) }
        compose.waitForIdle()
    }

    @Test
    fun tapAboveTheTrackSelects() {
        tapOutsideTrack(above = true)
        assertEquals(1, selected)
    }

    @Test
    fun tapBelowTheTrackSelects() {
        tapOutsideTrack(above = false)
        assertEquals(1, selected)
    }
}
