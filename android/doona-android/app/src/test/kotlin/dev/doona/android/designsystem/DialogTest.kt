package dev.doona.android.designsystem

import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.designsystem.components.DoonaConfirmDialog
import dev.doona.android.designsystem.components.DoonaConfirmKind
import dev.doona.android.designsystem.components.DoonaConfirmPanel
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.testing.Variant
import dev.doona.android.testing.seedColors
import dev.doona.android.testing.setDoonaContent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
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

    /** The short-window test sets the font scale of the configuration, which Robolectric does not reset with the qualifiers. */
    @After
    fun resetFontScale() = RuntimeEnvironment.setFontScale(1f)

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
     * A tap as the dialog's window receives it: a press and a release at [x], [y] of the window. The compose test's own
     * taps stay inside the content and never reach the window's touch handling, so the events are dispatched to the
     * dialog itself, at coordinates read off the rendered layout.
     */
    private fun tapWindowAt(x: Float, y: Float) {
        val dialog = ShadowDialog.getLatestDialog()
        compose.runOnUiThread {
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
                val event = MotionEvent.obtain(0L, 0L, action, x, y, 0)
                dialog.onTouchEvent(event)
                event.recycle()
            }
        }
        compose.waitForIdle()
    }

    private val card get() = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle)).fetchSemanticsNode().boundsInWindow

    private val windowWidth get() = ShadowDialog.getLatestDialog().window!!.decorView.width.toFloat()

    /**
     * The gutter beside the card is the underlay, so a tap on it is a tap outside the dialog: it dismisses once and does
     * not confirm. The points lie in the window and outside the card, which the test checks, so it is not the
     * coordinates outside the window that dismiss.
     */
    @Test
    fun aTapOnTheUnderlayBesideTheCardDismissesOnceAndDoesNotConfirm() {
        var confirmed = 0
        var dismissed = 0
        dialog(onConfirm = { confirmed++ }, onDismiss = { dismissed++ })
        val card = card
        val gutter = DoonaTheme.spacing.gutter.value * compose.density.density
        assertEquals("the card keeps a gutter at the start", gutter, card.left, 1f)
        assertEquals("and at the end", windowWidth - gutter, card.right, 1f)
        val beside = listOf(card.left / 2, (card.right + windowWidth) / 2)
        beside.forEach { assertTrue("$it is in the window and not on the card", it in 0f..windowWidth && it !in card.left..card.right) }
        tapWindowAt(beside[0], card.center.y)
        assertEquals("a tap beside the card at the start", 1, dismissed)
        tapWindowAt(beside[1], card.center.y)
        assertEquals("and one beside it at the end", 2, dismissed)
        assertEquals(0, confirmed)
    }

    /**
     * The card's own padding is the card: a tap on it, and one on its text, is a tap on the dialog and leaves it open.
     * The control is the same tap a few pixels further out, past the card's edge, which does dismiss: the edge of the
     * card is where the dialog ends, so the test cannot pass by tapping where nothing would ever dismiss.
     */
    @Test
    fun aTapOnTheCardsOwnPaddingDoesNotDismiss() {
        var dismissed = 0
        dialog(onDismiss = { dismissed++ })
        val card = card
        val inset = DoonaTheme.spacing.dialogInset.value * compose.density.density
        val onPadding = Offset(card.left + EDGE, card.center.y)
        val corner = Offset(card.left + inset / 2, card.top + inset / 2)
        listOf("Remove it?", "Its nodes go too.", "Keep it", "Yes, remove").forEach {
            val node = compose.onNodeWithText(it).fetchSemanticsNode().boundsInWindow
            assertFalse("the padding is clear of $it", onPadding in node || corner in node)
        }
        assertTrue("the points are on the card", listOf(onPadding, corner).all { it.x in card.left..card.right && it.y in card.top..card.bottom })
        tapWindowAt(card.left - EDGE, card.center.y)
        assertEquals("control: a tap just past the card's edge dismisses", 1, dismissed)
        tapWindowAt(onPadding.x, onPadding.y)
        tapWindowAt(corner.x, corner.y)
        assertEquals("a tap on the card's padding does not", 1, dismissed)
        compose.onNodeWithText("Its nodes go too.").performClick()
        tapWindowAt(card.center.x, card.center.y)
        assertEquals("nor does one in the middle of the card", 1, dismissed)
    }

    private fun lines(label: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    /**
     * The real modal in the shortest window at the largest font: the card stays inside the window with the gutter above
     * and below, scrolls to reach the buttons, and every label wraps whole, none ending in an ellipsis.
     */
    @Test
    fun aShortWindowAtTheLargestFontScrollsTheCardAndWrapsEveryLabelWhole() {
        val title = "Remove the subscription and every group that uses only its nodes?"
        val body = "新增訂閱後節點會出現在這裡；移除後，這份訂閱的全部節點、依賴這些節點的群組與測得的延遲都會一併刪除，且無法復原。"
        val confirm = "Remove the subscription and every group that uses only its nodes, with the measured delay of each of them"
        // The dialog is a window of its own whose density is the configuration's, so the font scale is set on that, not on a local.
        RuntimeEnvironment.setQualifiers(ShortWindow.qualifiers)
        RuntimeEnvironment.setFontScale(ShortWindow.fontScale)
        compose.setDoonaContent {
            DoonaConfirmDialog(title, body, confirm, "Keep it", onConfirm = {}, onDismiss = {}, kind = DoonaConfirmKind.Destructive)
        }
        val density = compose.density.density
        val window = SHORT_HEIGHT_DP * density
        val gutter = DoonaTheme.spacing.gutter.value * density
        val inset = DoonaTheme.spacing.dialogInset.value * density
        val card = card
        assertTrue("the card is %.0f px high in a window of %.0f, leaving the gutter above and below".format(card.height, window), card.height <= window - 2 * gutter + 1f)
        compose.onNode(hasScrollAction()).assertExists()
        val first = compose.onNodeWithText(title).fetchSemanticsNode().boundsInWindow
        assertEquals("the title starts at the card's padding", card.top + inset, first.top, 1f)
        val below = compose.onNodeWithText(confirm).fetchSemanticsNode().boundsInWindow
        assertTrue("the content ends at %.0f, past the card's end at %.0f".format(below.bottom + inset, card.bottom), below.bottom + inset > card.bottom + 1f)
        compose.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        compose.waitForIdle()
        val dismiss = compose.onNodeWithText("Keep it").fetchSemanticsNode().boundsInWindow
        val last = compose.onNodeWithText(confirm).fetchSemanticsNode().boundsInWindow
        assertTrue("the dismiss button is reached", dismiss.top >= card.top && dismiss.bottom <= card.bottom)
        assertEquals("the end of the card is the padding below the confirm button", card.bottom - inset, last.bottom, 1f)
        assertTrue("the confirm button is under the dismiss button", last.top >= dismiss.bottom)
        listOf("Keep it", confirm).forEach { label ->
            val result = lines(label)
            assertEquals("$label shows to its last character", label.length, result.getLineEnd(result.lineCount - 1))
            assertTrue("$label has no ellipsis", (0 until result.lineCount).none { result.isLineEllipsized(it) })
            assertFalse("$label is not cut by its height", result.didOverflowHeight)
        }
        assertTrue("the confirm label needs more than the two lines a button would otherwise give it: ${lines(confirm).lineCount}", lines(confirm).lineCount > 2)
    }

    /** Focus reaches the dismiss button first and the confirm button after it, which is the order a screen reader and a keyboard read them in. */
    @Test
    fun focusReachesTheDismissButtonBeforeTheConfirmButton() {
        lateinit var focus: FocusManager
        lateinit var input: InputModeManager
        compose.setDoonaContent {
            focus = LocalFocusManager.current
            input = LocalInputModeManager.current
            DoonaConfirmPanel("Remove it?", "Its nodes go too.", "Yes, remove", "Keep it", {}, {}, DoonaConfirmKind.Destructive)
        }
        val order = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().map { node -> node.config[SemanticsProperties.Text].joinToString { it.text } }
        assertEquals("the buttons are read dismiss first", listOf("Keep it", "Yes, remove"), order)
        // A button takes focus when a keyboard is in use, and not while the screen is touched; the first focus is the first in the order.
        compose.runOnIdle { input.requestInputMode(InputMode.Keyboard) }
        compose.onNode(isFocused()).assert(hasText("Keep it"))
        compose.runOnIdle { focus.moveFocus(FocusDirection.Next) }
        compose.onNode(isFocused()).assert(hasText("Yes, remove"))
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

    /** A label too long for both to fit: the buttons stack at the full width, the confirm one last, and its label wraps whole, to as many lines as it needs. */
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
        assertTrue("the label wraps onto more than one line", results.single().lineCount > 1)
        assertEquals("and shows to its last character", confirm.length, results.single().getLineEnd(results.single().lineCount - 1))
        assertTrue("and ends in no ellipsis", (0 until results.single().lineCount).none { results.single().isLineEllipsized(it) })
    }

    private fun hex(c: Color) = "#%08X".format(c.toArgb())

    @Test
    fun aRemovalIsFilledDestructiveAndAChoiceIsFilledAccent() {
        val c = seedColors(false)
        compose.setDoonaContent {
            Column(Modifier.fillMaxSize().background(DoonaTheme.colors.page)) {
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
        assertEquals(hex(c.positive), hex(fill("Choose")))
        assertEquals(hex(c.destructive), hex(fill("Remove")))
    }

    private companion object {
        /** A phone's panel: wide enough for two short buttons and too narrow for a long label beside another. */
        val PANEL = 320.dp

        /** A few pixels: how far either side of the card's edge a tap is aimed. */
        const val EDGE = 4f

        /** A phone turned on its side at the largest font: 915 × 412 dp, the shortest window the dialog meets. */
        val ShortWindow = Variant("landscape-fs200", "zh-rTW-w915dp-h412dp-notnight-xhdpi", 2f)
        const val SHORT_HEIGHT_DP = 412f

        /** Inside a primary button's round end, where the fill is. */
        const val FILL_INSET = 6f
    }
}
