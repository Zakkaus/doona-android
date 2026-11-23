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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.designsystem.components.DoonaSegmented
import dev.doona.android.testing.SeedTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val DENSITY = 3f

// The segmented control's target and its track (DoonaTheme.size.touch, DoonaTheme.chart.segment).
private const val TOUCH_DP = 48f
private const val TRACK_DP = 36f

/**
 * The segmented control's 36 dp track is hit over 48 dp: a tap just above or below the track still selects its item. A
 * tap on the item already selected selects nothing.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-notnight-xxhdpi")
class ControlTouchTest {
    @get:Rule
    val compose = createComposeRule()

    private var selected by mutableIntStateOf(0)

    private fun tapOutsideTrack(above: Boolean) {
        compose.setContent {
            SeedTheme {
                Column(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.height(100.dp))
                    DoonaSegmented(listOf("A", "B", "C"), selected, { selected = it })
                    Spacer(Modifier.height(100.dp))
                }
            }
        }
        val item = compose.onAllNodes(isSelectable()).fetchSemanticsNodes()[1].boundsInRoot
        // The item is the 48 dp target; its 36 dp track sits in the middle of it.
        val inset = (TOUCH_DP - TRACK_DP) / 2 * DENSITY
        val reach = 5 * DENSITY
        val y = if (above) item.top + inset - reach else item.bottom - inset + reach
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

    @Test
    fun aTapOnTheSelectedItemSelectsNothing() {
        val taps = mutableListOf<Int>()
        compose.setContent { SeedTheme { DoonaSegmented(listOf("A", "B"), 0, { taps += it }) } }
        val items = compose.onAllNodes(isSelectable())
        items[0].performClick()
        items[1].performClick()
        compose.waitForIdle()
        assertEquals(listOf(1), taps)
    }
}
