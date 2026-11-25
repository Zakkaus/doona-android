package dev.doona.android.prototype.rv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.doona.android.R
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvCaption
import dev.doona.android.designsystem.rv.RvChrome
import dev.doona.android.designsystem.rv.RvPart
import dev.doona.android.designsystem.rv.RvRangeRow
import dev.doona.android.designsystem.rv.RvRowPart
import dev.doona.android.designsystem.rv.RvSegmented
import dev.doona.android.designsystem.rv.rvPress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A focused clickable draws a 2 dp primary outline for a keyboard or a switch and never for a touch (owner, 2026-11-25, q-a11y-focus). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RV_EN)
class RvFocusTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var input: InputModeManager
    private var ring = Color.Unspecified
    private var density = 1f

    /** The colour 1 dp inside the top edge of the pressable box tagged [tag], in its middle: the outline's row. */
    private fun edge(tag: String): Color {
        val pixels = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
        return pixels[pixels.width / 2, (density * RING_PROBE_DP).toInt()]
    }

    private fun pressable() = compose.rvContent(dark = false) {
        input = LocalInputModeManager.current
        ring = Rv.colors.positive
        density = LocalDensity.current.density
        Column {
            Box(Modifier.size(120.dp, 56.dp).testTag("a").rvPress(RoundedCornerShape(12.dp), Role.Button, onClick = {}))
        }
    }

    @Test
    fun aKeyboardFocusedClickableDrawsThePrimaryOutlineAndATouchDoesNot() {
        pressable()
        val plain = edge("a")
        assertNotEquals("the page is not the outline's colour", ring, plain)
        // A tap focuses nothing and draws no outline.
        compose.runOnIdle { input.requestInputMode(InputMode.Touch) }
        compose.onNodeWithTag("a").performClick()
        compose.waitForIdle()
        assertEquals("a tap draws no outline", plain, edge("a"))
        // A key focuses it: the primary outline, 2 dp wide, inside the shape.
        compose.runOnIdle { input.requestInputMode(InputMode.Keyboard) }
        compose.onNodeWithTag("a").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        assertEquals(ring, edge("a"))
        assertEquals(2f, RvChrome.focusRing.value, 0f)
    }

    private companion object {
        /** A point on the 2 dp outline, 1 dp inside the top edge. */
        const val RING_PROBE_DP = 1f
    }
}
