package dev.doona.android.prototype.rv

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.util.lerp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.max

// Revolut's More popup and its long-press menu (revolut-UX.md §5, §9): a raised card that scales and fades in from where
// it was asked for, with no scrim; a long press shows the row's press first and opens the menu at the finger.

/**
 * One entry of a menu: its glyph and label, and what it does. A [destructive] entry keeps its label in ink and draws only
 * its glyph in the error hue, as Revolut's destructive row (measured §13.2); a [separated] entry starts a group of its
 * own under a hairline, as the More menu sets its last row apart (revolut-UX.md §5).
 */
@Immutable
internal class RvMenuItem(
    val label: String,
    @DrawableRes val icon: Int,
    val destructive: Boolean = false,
    val separated: Boolean = false,
    val onClick: () -> Unit,
)

/** The menu's geometry: Revolut's More card (20 dp round, rows with a leading glyph), held clear of the screen's edges. */
internal object RvMenuSize {
    val minWidth = 200.dp
    val maxWidth = 280.dp

    /** An anchored menu opens this far under its anchor, its end on the anchor's end. */
    val anchorGap = 8.dp

    /** The menu keeps the gutter from the window's edges, and room round it for its shadow inside the popup's window. */
    val margin = RvSpace.gutter
    val shadowRoom = RvChrome.navShadow
}

/**
 * The menu's motion, measured (revolut-UX.md §5, §9): open in 267 ms, close in 233 ms, a long press after 500 ms. The
 * scale it grows from is Material's menu's. These are rv-final's `RvMotion.MENU_MS`, `LONG_PRESS_MS` and `Decelerate`;
 * fold them into `RvMotion` at the merge.
 */
internal object RvMenuMotion {
    const val MENU_MS = 267
    const val CLOSE_MS = 233
    const val LONG_PRESS_MS = 500L
    const val FROM_SCALE = 0.8f
    val Decelerate: Easing = CubicBezierEasing(0f, 0f, 0f, 1f)
    val Accelerate: Easing = CubicBezierEasing(0.3f, 0f, 1f, 1f)
}

/**
 * The menu as a popup over the screen while [expanded]: at [at] (a point in the anchor, where a finger pressed) or, with
 * none, under the anchor with its end on the anchor's end; it flips above when there is no room under. It scales and
 * fades in from that point and out again; a tap outside or back calls [onDismiss], and picking an item dismisses it and
 * runs the item. The anchor is the layout this is called in.
 */
@Composable
internal fun RvMenu(expanded: Boolean, onDismiss: () -> Unit, items: List<RvMenuItem>, at: Offset? = null) {
    val shown = remember { MutableTransitionState(false) }
    shown.targetState = expanded
    if (!shown.currentState && !shown.targetState) return
    val density = LocalDensity.current
    var origin by remember { mutableStateOf(TransformOrigin.Center) }
    val position = remember(at, density) {
        with(density) { MenuPosition(at?.round(), RvMenuSize.anchorGap.roundToPx(), RvMenuSize.margin.roundToPx(), RvMenuSize.shadowRoom.roundToPx()) { origin = it } }
    }
    Popup(popupPositionProvider = position, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        val progress by rememberTransition(shown, label = "menu").animateFloat(
            transitionSpec = { if (targetState) tween(RvMenuMotion.MENU_MS, easing = RvMenuMotion.Decelerate) else tween(RvMenuMotion.CLOSE_MS, easing = RvMenuMotion.Accelerate) },
            label = "open",
        ) { if (it) 1f else 0f }
        RvMenuPanel(
            items,
            onPick = {
                onDismiss()
                it.onClick()
            },
            Modifier.padding(RvMenuSize.shadowRoom).graphicsLayer {
                val scale = lerp(RvMenuMotion.FROM_SCALE, 1f, progress)
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
 * shadow in light, 20 dp round as every card, as wide as its longest label within [RvMenuSize]; a 58 dp row per item
 * with a 24 dp glyph and its label, as Revolut's settings rows (measured §7).
 */
@Composable
internal fun RvMenuPanel(items: List<RvMenuItem>, onPick: (RvMenuItem) -> Unit, modifier: Modifier = Modifier) {
    val colors = Rv.colors
    val shape = RoundedCornerShape(RvSize.cardRadius)
    val rim = colors.raisedRim?.let { Modifier.border(RvChrome.ring, it, shape) } ?: Modifier
    Column(
        modifier
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
                Box(Modifier.padding(vertical = RvSpace.tight, horizontal = RvSpace.rowPadding).fillMaxWidth().height(RvChrome.ring).background(colors.grid))
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = RvSize.rowOneLine)
                    .rvPress(RectangleShape, Role.Button) { onPick(item) }
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
 * element that is pressed. A tap runs [onClick]; a press held [RvMenuMotion.LONG_PRESS_MS] (or the user's longer delay)
 * shows the press, ticks, and opens [items] at the finger. A screen reader gets each item as a custom action, so no
 * item needs the gesture.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RvLongPressMenu(items: List<RvMenuItem>, onClick: (() -> Unit)?, modifier: Modifier = Modifier, content: @Composable (Modifier) -> Unit) {
    var touch by remember { mutableStateOf(Offset.Zero) }
    var open by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val base = LocalViewConfiguration.current
    val config = remember(base) { LongPressConfiguration(base) }
    val actions = items.map { item ->
        CustomAccessibilityAction(item.label) {
            item.onClick()
            true
        }
    }
    val stateLayer = Rv.colors.stateLayer
    Box(modifier) {
        CompositionLocalProvider(LocalViewConfiguration provides config) {
            content(
                Modifier
                    .pointerInput(Unit) { awaitEachGesture { touch = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).position } }
                    .clip(RectangleShape)
                    .combinedClickable(
                        interactionSource = null,
                        indication = ripple(color = stateLayer),
                        role = Role.Button,
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            open = true
                        },
                        onClick = onClick ?: {},
                    )
                    .semantics { customActions = actions },
            )
        }
        RvMenu(open, { open = false }, items, at = touch)
    }
}

/** The platform's configuration with a long press no shorter than Revolut's (revolut-UX.md §9: about 500 ms). */
private class LongPressConfiguration(private val base: ViewConfiguration) : ViewConfiguration by base {
    override val longPressTimeoutMillis: Long get() = max(base.longPressTimeoutMillis, RvMenuMotion.LONG_PRESS_MS)
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
