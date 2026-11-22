package dev.doona.android.designsystem

import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.designsystem.components.DoonaConfirmDialog
import dev.doona.android.designsystem.components.DoonaConfirmKind
import dev.doona.android.designsystem.components.DoonaConfirmPanel
import dev.doona.android.designsystem.theme.DoonaColors
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.designsystem.tokens.Dawn
import dev.doona.android.testing.setDoonaContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/** What the confirmation dialog does and shows: both buttons answer, the title is a heading, and a removal is in the destructive fill. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class DialogTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theButtonsAnswerAndTheTitleIsAHeading() {
        var confirmed = 0
        var dismissed = 0
        compose.setDoonaContent {
            DoonaConfirmDialog("Remove it?", "Its nodes go too.", "Yes, remove", "Keep it", onConfirm = { confirmed++ }, onDismiss = { dismissed++ }, kind = DoonaConfirmKind.Destructive)
        }
        compose.onNodeWithText("Remove it?").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithText("Its nodes go too.").assertExists()
        compose.onNodeWithText("Yes, remove").performClick()
        assertEquals(1, confirmed)
        assertEquals(0, dismissed)
        compose.onNodeWithText("Keep it").performClick()
        assertEquals(1, dismissed)
        assertEquals("a dismiss is not a confirm", 1, confirmed)
    }

    private fun dialog(onConfirm: () -> Unit = {}, onDismiss: () -> Unit = {}) = compose.setDoonaContent {
        DoonaConfirmDialog("Remove it?", "Its nodes go too.", "Yes, remove", "Keep it", onConfirm = onConfirm, onDismiss = onDismiss, kind = DoonaConfirmKind.Destructive)
    }

    @Test
    fun backDismissesOnceAndDoesNotConfirm() {
        var confirmed = 0
        var dismissed = 0
        dialog(onConfirm = { confirmed++ }, onDismiss = { dismissed++ })
        Espresso.pressBack()
        compose.waitForIdle()
        assertEquals(1, dismissed)
        assertEquals(0, confirmed)
    }

    /**
     * A tap as the dialog's window receives it: a press and a release at [point] of its corner.
     * The compose test's own taps stay inside the content and never reach the window's touch handling, so the events
     * are dispatched to the dialog itself.
     */
    private fun tapWindowAt(point: Float) {
        val dialog = ShadowDialog.getLatestDialog()
        compose.runOnUiThread {
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
                val event = MotionEvent.obtain(0L, 0L, action, point, point, 0)
                dialog.onTouchEvent(event)
                event.recycle()
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun aTapOutsideThePanelDismissesOnceAndDoesNotConfirm() {
        var confirmed = 0
        var dismissed = 0
        dialog(onConfirm = { confirmed++ }, onDismiss = { dismissed++ })
        tapWindowAt(OUTSIDE)
        assertEquals(1, dismissed)
        assertEquals(0, confirmed)
    }

    @Test
    fun aTapInsideThePanelDoesNotDismiss() {
        var dismissed = 0
        dialog(onDismiss = { dismissed++ })
        compose.onNodeWithText("Its nodes go too.").performClick()
        tapWindowAt(INSIDE)
        assertEquals(0, dismissed)
    }

    private fun panelOf(confirm: String, dismiss: String) = compose.setDoonaContent {
        Box(Modifier.width(PANEL)) { DoonaConfirmPanel("Remove it?", "Its nodes go too.", confirm, dismiss, {}, {}, DoonaConfirmKind.Destructive) }
    }

    private fun bounds(label: String) = compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot

    /** Both buttons fit: they sit side by side, a gap apart, the confirm one at the end of the panel's inner edge. */
    @Test
    fun theButtonsSitSideBySideAtTheEndWhenBothFit() {
        panelOf("Remove", "Keep it")
        val density = compose.density.density
        val inner = (PANEL.value - DoonaTheme.spacing.dialogInset.value) * density
        val dismiss = bounds("Keep it")
        val confirm = bounds("Remove")
        assertEquals("on one line", dismiss.center.y, confirm.center.y, 1f)
        assertEquals("a gap apart", DoonaTheme.spacing.item.value * density, confirm.left - dismiss.right, 1f)
        assertEquals("the confirm button ends at the inner edge", inner, confirm.right, 1f)
    }

    /** A label too long for both to fit: the buttons stack at the full width, the confirm one last, and its label wraps whole. */
    @Test
    fun longLabelsStackTheButtonsAtFullWidthAndNoLabelIsCut() {
        val confirm = "Remove the subscription and every group that uses only its nodes"
        panelOf(confirm, "Keep it")
        val density = compose.density.density
        val dismissBox = bounds("Keep it")
        val confirmBox = bounds(confirm)
        assertTrue("the confirm button is below the dismiss button", confirmBox.top >= dismissBox.bottom)
        assertEquals("a block apart", DoonaTheme.spacing.block.value * density, confirmBox.top - dismissBox.bottom, 1f)
        val width = (PANEL.value - 2 * DoonaTheme.spacing.dialogInset.value) * density
        assertEquals("the dismiss button is as wide as the panel's content", width, dismissBox.width, 1f)
        assertEquals("and so is the confirm button", width, confirmBox.width, 1f)
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(confirm, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        assertEquals("the label wraps onto two lines", 2, results.single().lineCount)
        assertFalse("and is not cut", results.single().hasVisualOverflow)
    }

    private fun hex(c: Color) = "#%08X".format(c.toArgb())

    @Test
    fun aRemovalIsFilledDestructiveAndAChoiceIsFilledAccent() {
        val c = DoonaColors(Dawn)
        compose.setDoonaContent {
            Column(Modifier.fillMaxSize().background(DoonaTheme.colors.window)) {
                DoonaConfirmPanel("A", "a", "Choose", "No", {}, {}, DoonaConfirmKind.Default)
                DoonaConfirmPanel("B", "b", "Remove", "No", {}, {}, DoonaConfirmKind.Destructive)
            }
        }
        val image = compose.onRoot().captureToImage().toPixelMap()
        val density = compose.density.density
        fun fill(label: String): Color {
            val button = compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
            return image[(button.left + FILL_INSET * density).toInt(), button.center.y.toInt()]
        }
        assertEquals(hex(c.accent), hex(fill("Choose")))
        assertEquals(hex(c.destructive), hex(fill("Remove")))
    }

    private companion object {
        /** A phone's panel: wide enough for two short buttons and too narrow for a long label beside another. */
        val PANEL = 320.dp

        /** Inside the content, which starts at the window's corner. */
        const val INSIDE = 10f

        /** Left of and above the content, which starts at the window's corner. */
        const val OUTSIDE = -10f

        /** Inside a primary button's round end, where the fill is. */
        const val FILL_INSET = 6f
    }
}
