package dev.doona.android.designsystem.rv

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.FrameRateCategory
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.round
import androidx.compose.ui.util.lerp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.flow.first
import kotlin.math.max

// Revolut's More popup and its long-press menu (revolut-UX.md §5, §9): a raised card that scales and fades in from where
// it was asked for, with no scrim; a long press shows the row's press first and opens the menu.

/** A menu's card, for tests. */
internal const val RV_MENU = "rv-menu"

/**
 * One entry of a menu: its glyph and label, and what it does. A [destructive] entry keeps its label in ink and draws only
 * its glyph in the error hue, as Revolut's destructive row (measured §13.2); a [separated] entry starts a group of its
 * own under a hairline, as the More menu sets its last row apart (revolut-UX.md §5). An entry that needs a question
 * first asks it in [RvConfirmSheet] from its [onClick]. Not [enabled] (an action the core cannot do yet), the entry
 * stays in its place at [RvAlpha.DISABLED], takes no press and is no screen-reader action.
 */
@Immutable
internal class RvMenuItem(
    val label: String,
    @DrawableRes val icon: Int,
    val destructive: Boolean = false,
    val separated: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * The menu as a popup over the screen while [expanded]: at [at] (a point in the anchor, where a finger pressed) or, with
 * none, under the anchor with its end on the anchor's end; it flips above when there is no room under. It scales from
 * [RvMotion.MENU_FROM] and fades in from that point in [RvMotion.MENU_MS] and out in [RvMotion.MENU_OUT_MS]; a tap
 * outside or back calls [onDismiss], and picking an item dismisses it and runs the item. The anchor is the layout this
 * is called in. Closed and settled, it composes nothing: every row of a list may carry one.
 */
@Composable
internal fun RvMenu(expanded: Boolean, onDismiss: () -> Unit, items: List<RvMenuItem>, at: Offset? = null) {
    val shown = remember { MutableTransitionState(false) }
    shown.targetState = expanded
    if (!shown.currentState && !shown.targetState) return
    LaunchedEffect(expanded) {
        val gesture = if (expanded) "menu-open" else "menu-close"
        RvFrames.begin(gesture)
        // A close that has settled leaves composition with this effect: it ends there.
        try {
            snapshotFlow { shown.isIdle }.first { it }
        } finally {
            RvFrames.end(gesture)
        }
    }
    val motion = Rv.motion
    val density = LocalDensity.current
    var origin by remember { mutableStateOf(TransformOrigin.Center) }
    val position = remember(at, density) {
        with(density) { MenuPosition(at?.round(), RvMenuSize.anchorGap.roundToPx(), RvMenuSize.margin.roundToPx(), RvMenuSize.shadowRoom.roundToPx()) { origin = it } }
    }
    Popup(popupPositionProvider = position, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        val progress by rememberTransition(shown, label = "menu").animateFloat(
            transitionSpec = { if (targetState) motion.tween(RvMotion.MENU_MS, RvMotion.Decelerate) else motion.tween(RvMotion.MENU_OUT_MS, RvMotion.Accelerate) },
            label = "open",
        ) { if (it) 1f else 0f }
        RvMenuPanel(
            items,
            onPick = {
                onDismiss()
                it.onClick()
            },
            Modifier
                // The menu is a window of its own, outside the sample's vote: it asks the high rate as it scales.
                .preferredFrameRate(FrameRateCategory.High)
                .padding(RvMenuSize.shadowRoom)
                .graphicsLayer {
                    val scale = lerp(RvMotion.MENU_FROM, 1f, progress)
                    scaleX = scale
                    scaleY = scale
                    alpha = progress
                    transformOrigin = origin
                },
        )
    }
}

/**
 * The menu's card: the raised fill (white in light, a lighter container with its rim in dark) over the capsule's soft
 * shadow in light, 20 dp round as every card, as wide as its longest label within [RvMenuSize]; a [RvMenuSize.row] row
 * per item with a 24 dp glyph and its label, as Revolut's long-press popup (revolut-ux3 §1).
 */
@Composable
internal fun RvMenuPanel(items: List<RvMenuItem>, onPick: (RvMenuItem) -> Unit, modifier: Modifier = Modifier) {
    val colors = Rv.colors
    val shape = RoundedCornerShape(Rv.cardRadius)
    val rim = colors.raisedRim?.let { Modifier.border(RvChrome.ring, it, shape) } ?: Modifier
    Column(
        modifier
            .testTag(RV_MENU)
            .widthIn(RvMenuSize.minWidth, RvMenuSize.maxWidth)
            .width(IntrinsicSize.Max)
            .rvSoftShadow(shape, RvChrome.navShadow, RvChrome.navShadowDrop, RvChrome.NAV_SHADOW_ALPHA)
            .clip(shape)
            .background(colors.raised)
            .then(rim)
            .padding(vertical = RvSpace.tight),
    ) {
        items.forEach { item ->
            if (item.separated) {
                // Only the line's own 1 dp: Revolut's last row starts 2 px (0.8 dp) under the row above (p1-more-sheet).
                Box(Modifier.padding(horizontal = RvSpace.rowPadding).fillMaxWidth().height(RvChrome.ring).background(colors.grid))
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .testTag(RvRowPart.MENU_ROW)
                    .heightIn(min = RvMenuSize.row)
                    .then(if (item.enabled) Modifier.rvRowPress(Role.Button, { onPick(item) }) else Modifier.alpha(RvAlpha.DISABLED))
                    .padding(horizontal = RvSpace.rowPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RvGlyph(item.icon, RvSize.actionGlyph, if (item.destructive) colors.error else colors.text)
                Spacer(Modifier.width(RvSpace.rowGap))
                RvText(item.label, Rv.type.rowTitle)
            }
        }
    }
}

/**
 * A row (or card) with a long-press menu, as the gestures brief has it (G3): [content] gets the modifier to put on the
 * element that is pressed. A tap runs [onClick] or opens the menu if no primary action is supplied. A press held [RvMotion.LONG_PRESS_MS] (or the user's longer delay)
 * shows the press, ticks, and opens [items] at the finger. A screen reader gets each item as a custom action, so no
 * item needs the gesture.
 */
@Composable
internal fun RvLongPressMenu(items: List<RvMenuItem>, onClick: (() -> Unit)?, modifier: Modifier = Modifier, content: @Composable (Modifier) -> Unit) {
    var touch by remember { mutableStateOf(Offset.Zero) }
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        RvLongPressTiming {
            content(
                Modifier
                    .pointerInput(Unit) { awaitEachGesture { touch = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).position } }
                    // combinedClickable ticks on the long press.
                    .rvRowPress(Role.Button, onClick ?: { open = true }, onLongClick = { open = true })
                    .rvMenuActions(items),
            )
        }
        RvMenu(open, { open = false }, items, at = touch)
    }
}

/**
 * A row that opens its menu under its end ([RvMenu] without a point): [content] draws the row, given the long press that
 * opens the menu (for [RvRow]'s `onLongClick`) and a modifier that offers every item to TalkBack as a custom action. No
 * [items], no menu. [modifier] goes on the box round the row, the menu's anchor.
 */
@Composable
internal fun RvMenuAnchor(items: List<RvMenuItem>, modifier: Modifier = Modifier, content: @Composable (open: (() -> Unit)?, actions: Modifier) -> Unit) {
    if (items.isEmpty()) {
        Box(modifier) { content(null, Modifier) }
        return
    }
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        content({ open = true }, Modifier.rvMenuActions(items))
        RvMenu(open, { open = false }, items)
    }
}

/** Every one of [items] as a TalkBack custom action. */
private fun Modifier.rvMenuActions(items: List<RvMenuItem>): Modifier = semantics {
    customActions = items.filter { it.enabled }.map { item ->
        CustomAccessibilityAction(item.label) {
            item.onClick()
            true
        }
    }
}

/**
 * Places the menu's popup: its card's top start at [at] in the anchor, or its top end [gap] under the anchor's bottom end;
 * above that point when it would cross the window's bottom [margin]; within the margins either way. The popup's window
 * holds [room] round the card for its shadow. [onOrigin] gets the point the card grows from, in the card's own fractions.
 */
private class MenuPosition(
    private val at: IntOffset?,
    private val gap: Int,
    private val margin: Int,
    private val room: Int,
    private val onOrigin: (TransformOrigin) -> Unit,
) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val width = popupContentSize.width - 2 * room
        val height = popupContentSize.height - 2 * room
        val ltr = layoutDirection == LayoutDirection.Ltr
        val growX = at?.let { anchorBounds.left + it.x } ?: if (ltr) anchorBounds.right else anchorBounds.left
        val growY = at?.let { anchorBounds.top + it.y } ?: (anchorBounds.bottom + gap)
        val startX = if ((at != null) == ltr) growX else growX - width
        val x = startX.coerceIn(margin, max(margin, windowSize.width - margin - width))
        val below = growY + height <= windowSize.height - margin
        val top = if (below) growY else (at?.let { growY } ?: (anchorBounds.top - gap)) - height
        val y = top.coerceIn(margin, max(margin, windowSize.height - margin - height))
        onOrigin(TransformOrigin(fraction(growX - x, width), fraction((if (below) growY else top + height) - y, height)))
        return IntOffset(x - room, y - room)
    }

    private fun fraction(part: Int, whole: Int) = if (whole <= 0) 0.5f else (part.toFloat() / whole).coerceIn(0f, 1f)
}
