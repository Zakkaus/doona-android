package dev.doona.android.prototype.rv

import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.doona.android.R
import dev.doona.android.designsystem.theme.LocalFrameInsets
import kotlinx.coroutines.flow.first

/** A long-press menu's card, for tests. */
internal const val RV_MENU = "rv-menu"

/** One action of a long-press menu: its glyph and label, the question it asks first if any ([confirm]), what it does. */
@Immutable
internal class RvMenuItem(@DrawableRes val icon: Int, val label: String, val confirm: String? = null, val onClick: () -> Unit)

/** Whether a row's menu is open, and the item whose question it shows instead of the list ([asking]). */
@Stable
internal class RvMenuState {
    var open by mutableStateOf(false)
    var asking by mutableStateOf<RvMenuItem?>(null)

    fun show(question: RvMenuItem? = null) {
        asking = question
        open = true
    }

    fun close() {
        open = false
    }
}

/**
 * A row with a long-press menu (brief rv-gestures G3): [content] draws the row, given the long press that opens the menu
 * and a modifier that offers every item to TalkBack as a custom action (an item that asks first opens the menu on its
 * question). No [items], no menu. [modifier] goes on the box round the row, the menu's anchor.
 */
@Composable
internal fun RvMenuAnchor(items: List<RvMenuItem>, modifier: Modifier = Modifier, content: @Composable (open: (() -> Unit)?, actions: Modifier) -> Unit) {
    val state = remember { RvMenuState() }
    if (items.isEmpty()) {
        Box(modifier) { content(null, Modifier) }
        return
    }
    val actions = Modifier.semantics {
        customActions = items.map { item ->
            CustomAccessibilityAction(item.label) {
                if (item.confirm != null) state.show(item) else item.onClick()
                true
            }
        }
    }
    Box(modifier) {
        content({ state.show() }, actions)
        RvMenu(state, items)
    }
}

/**
 * The menu, as Revolut's More popup built from the RV set's parts: a card of one-line rows ([RvRow], each with its
 * [RvIconDisc]) on the raised fill, under the floating nav's shadow in light and inside the raised rim in dark, its end
 * on the row's end, below the row or above it when there is no room. It scales from [RvMotion.MENU_FROM] and fades in from that corner in [RvMotion.MENU_MS] and goes
 * in [RvMotion.MENU_OUT_MS]; a tap outside or back closes it. An item that asks first turns the card into its question
 * over that item and 取消.
 */
@Composable
private fun RvMenu(state: RvMenuState, items: List<RvMenuItem>) {
    val motion = Rv.motion
    val shown = remember { MutableTransitionState(false) }
    shown.targetState = state.open
    LaunchedEffect(state.open) {
        val gesture = if (state.open) "menu-open" else "menu-close"
        RvFrames.begin(gesture)
        snapshotFlow { shown.isIdle }.first { it }
        RvFrames.end(gesture)
    }
    if (!shown.currentState && !shown.targetState) return
    val density = LocalDensity.current
    val endSide = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1f else 0f
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val position = remember(density, insets) {
        with(density) { MenuPosition(RvSpace.tight.roundToPx(), RvSpace.rowPadding.roundToPx(), insets.getTop(this), insets.getBottom(this)) }
    }
    Popup(position, onDismissRequest = state::close, properties = PopupProperties(focusable = true)) {
        val transition = rememberTransition(shown, label = "menu")
        val p by transition.animateFloat({ motion.tween(if (targetState) RvMotion.MENU_MS else RvMotion.MENU_OUT_MS) }, label = "in") { if (it) 1f else 0f }
        val shape = RoundedCornerShape(RvSize.cardRadius)
        val colors = Rv.colors
        // In dark the shadow is not drawn (as under every raised control): the raised fill and its rim set it apart.
        val rim = colors.raisedRim?.let { Modifier.border(RvChrome.ring, it, shape) } ?: Modifier
        Column(
            Modifier
                .testTag(RV_MENU)
                .graphicsLayer {
                    val scale = RvMotion.MENU_FROM + (1f - RvMotion.MENU_FROM) * p
                    scaleX = scale
                    scaleY = scale
                    alpha = p
                    transformOrigin = TransformOrigin(endSide, if (position.below) 0f else 1f)
                }
                .width(RvSize.menuWidth)
                .rvSoftShadow(shape, RvChrome.navShadow, RvChrome.navShadowDrop, RvChrome.NAV_SHADOW_ALPHA)
                .clip(shape)
                .background(colors.raised)
                .then(rim)
                .animateContentSize(motion.tween(RvMotion.MOVE_MS)),
        ) {
            Crossfade(state.asking, animationSpec = motion.tween(RvMotion.FADE_MS), label = "question") { asking ->
                if (asking == null) MenuList(state, items) else Question(state, asking)
            }
        }
    }
}

@Composable
private fun MenuList(state: RvMenuState, items: List<RvMenuItem>) {
    Column {
        items.forEach { item ->
            MenuRow(item) {
                if (item.confirm != null) {
                    state.asking = item
                } else {
                    state.close()
                    item.onClick()
                }
            }
        }
    }
}

/** An item's question, the item to go ahead and 取消, which closes the menu. */
@Composable
private fun Question(state: RvMenuState, item: RvMenuItem) {
    Column {
        RvText(
            item.confirm.orEmpty(),
            Rv.type.rowTitle,
            Modifier.padding(start = RvSpace.rowPadding, end = RvSpace.rowPadding, top = RvSpace.rowPadding, bottom = RvSpace.tight),
        )
        MenuRow(item) {
            state.close()
            item.onClick()
        }
        MenuRow(RvMenuItem(R.drawable.rv_sym_arrow_back, stringResource(R.string.rv_menu_cancel)) {}, state::close)
    }
}

@Composable
private fun MenuRow(item: RvMenuItem, onClick: () -> Unit) {
    RvRow({ RvIconDisc(item.icon) }, item.label, null, onClick = onClick)
}

/**
 * Places the menu's end on the anchor row's end less [inset] (where its trailing content ends), [gap] under the row, or
 * [gap] over it when it would run into the bottom inset; [below] says which, for the corner it grows from.
 */
private class MenuPosition(private val gap: Int, private val inset: Int, private val top: Int, private val bottom: Int) : PopupPositionProvider {
    var below by mutableStateOf(true)
        private set

    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val end = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.right - inset - popupContentSize.width else anchorBounds.left + inset
        val x = end.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val under = anchorBounds.bottom + gap
        below = under + popupContentSize.height <= windowSize.height - bottom
        val y = if (below) under else (anchorBounds.top - gap - popupContentSize.height).coerceAtLeast(top)
        return IntOffset(x, y)
    }
}
