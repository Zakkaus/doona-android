package dev.doona.android.prototype.rv

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import dev.doona.android.designsystem.theme.LocalFrameInsets

/**
 * Revolut's modal sheet (measured §2): 8 dp in from the screen's sides and 10 dp above its bottom edge, about 41 dp round,
 * a 47 x 5 dp handle, over a scrim of black at 80 %. Inside, the title 20 dp under the handle, the body 8 dp under it,
 * and the actions 24 dp under the body; at least 8 dp under the last action, more over a taller system bar. Its parts
 * sit on the lines every page uses: buttons and fields on the page's edge line, 16 dp from the screen ([edge] in from
 * the sheet's side), so the primary button is a page's 380 dp; the title and body on the card-content line, 32 dp in.
 */
internal object RvSheetSize {
    val inset = 8.dp
    val bottom = 10.dp
    val radius = 41.dp
    val handleWidth = 47.dp
    val handleHeight = 5.dp
    val handleTop = 8.dp
    val titleTop = 20.dp
    val titleToBody = 8.dp
    val bodyToActions = RvSpace.chartGap
    val lastPad = 8.dp

    /** From the sheet's side to the page's edge line: the gutter less the sheet's own inset. */
    val edge = RvSpace.gutter - inset

    /** A drag dismisses the sheet past this share of its height, or when it lets go faster than [fling] a second. */
    const val DISMISS_SHARE = 0.33f
    val fling = 1_000.dp
}

/**
 * A modal sheet while [visible]: its own window, so a screen reader stays inside it, with the scrim drawn here (the
 * platform's dim is off). It rises in [RvMotion.MENU_MS] and falls in [RvMotion.MENU_OUT_MS] (the menu's measured
 * timings; Revolut's sheet was not timed). Back, a tap on the scrim, or a drag down past a third calls [onDismiss].
 * [label] names the pane for a screen reader.
 */
@Composable
internal fun RvSheet(visible: Boolean, onDismiss: () -> Unit, label: String, content: @Composable ColumnScope.() -> Unit) {
    val shown = remember { MutableTransitionState(false) }
    shown.targetState = visible
    if (!shown.currentState && !shown.targetState) return
    val motion = Rv.motion
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0f) }
        val progress by rememberTransition(shown, label = "sheet").animateFloat(
            transitionSpec = { if (targetState) motion.tween(RvMotion.MENU_MS, RvMotion.Decelerate) else motion.tween(RvMotion.MENU_OUT_MS, RvMotion.Accelerate) },
            label = "open",
        ) { if (it) 1f else 0f }
        RvSheetLayout({ progress }, onDismiss, label, content)
    }
}

/**
 * The sheet's scrim and its card at the bottom, [progress] of the way in (0 under the screen's edge, 1 in place); a tap
 * on the scrim calls [onDismiss], and the card follows a drag down. [RvSheet] draws it in its window; a golden draws it
 * over the page at 1.
 */
@Composable
internal fun RvSheetLayout(progress: () -> Float, onDismiss: () -> Unit, label: String, content: @Composable ColumnScope.() -> Unit) {
    val density = LocalDensity.current
    var drag by remember { mutableFloatStateOf(0f) }
    var height by remember { mutableFloatStateOf(0f) }
    val fling = with(density) { RvSheetSize.fling.toPx() }
    val bottom = with(density) { RvSheetSize.bottom.toPx() }
    val drags = rememberDraggableState { drag = (drag + it).coerceAtLeast(0f) }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = progress() }
                .background(Rv.colors.scrim)
                .clickable(interactionSource = null, indication = null, onClickLabel = label, onClick = onDismiss),
        )
        RvSheetPanel(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(start = RvSheetSize.inset, end = RvSheetSize.inset, bottom = RvSheetSize.bottom)
                .graphicsLayer {
                    height = size.height
                    translationY = drag + (1f - progress()) * (size.height + bottom)
                }
                .draggable(
                    drags,
                    Orientation.Vertical,
                    onDragStopped = { velocity ->
                        if (drag > height * RvSheetSize.DISMISS_SHARE || velocity > fling) onDismiss() else animate(drag, 0f) { value, _ -> drag = value }
                    },
                )
                .semantics { paneTitle = label },
            content,
        )
    }
}

/** The sheet's card: the card's fill, its rim in dark, the handle, and [content] on the page's edge line. */
@Composable
internal fun RvSheetPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = Rv.colors
    val shape = RoundedCornerShape(RvSheetSize.radius)
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val density = LocalDensity.current
    // The last action keeps 8 dp to the card's edge, and clears a system bar taller than the sheet's own 10 dp.
    val bar = with(density) { insets.getBottom(this).toDp() }
    val last = maxOf(RvSheetSize.lastPad, bar - RvSheetSize.bottom)
    val rim = colors.raisedRim?.let { Modifier.border(RvChrome.ring, it, shape) } ?: Modifier
    Column(modifier.fillMaxWidth().clip(shape).background(colors.card).then(rim).padding(start = RvSheetSize.edge, end = RvSheetSize.edge, bottom = last)) {
        Spacer(Modifier.height(RvSheetSize.handleTop))
        Box(Modifier.align(Alignment.CenterHorizontally).size(RvSheetSize.handleWidth, RvSheetSize.handleHeight).clip(CircleShape).background(colors.track))
        content()
    }
}

/**
 * A question before something that cannot be taken back or needs the user's yes: [title], [body], an optional [extra]
 * (stat rows, a field), then [confirm] as the primary button and [dismiss] as the accent text action under it.
 */
@Composable
internal fun RvConfirmSheet(
    visible: Boolean,
    title: String,
    body: String,
    confirm: String,
    dismiss: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmEnabled: Boolean = true,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
    RvSheet(visible, onDismiss, title) { RvConfirmContent(title, body, confirm, dismiss, onConfirm, onDismiss, confirmEnabled, extra) }
}

/** What [RvConfirmSheet] shows in its card. */
@Composable
internal fun ColumnScope.RvConfirmContent(
    title: String,
    body: String,
    confirm: String,
    dismiss: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmEnabled: Boolean = true,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Spacer(Modifier.height(RvSheetSize.titleTop))
    // The words on the card-content line, as a card's rows set theirs.
    val text = Modifier.padding(horizontal = RvSpace.rowPadding)
    RvText(title, Rv.type.section, text, maxLines = TITLE_LINES)
    Spacer(Modifier.height(RvSheetSize.titleToBody))
    RvText(body, Rv.type.rowSub, text, color = Rv.colors.textSecondary, maxLines = BODY_LINES)
    extra?.let {
        Spacer(Modifier.height(RvSpace.gutter))
        it()
    }
    Spacer(Modifier.height(RvSheetSize.bodyToActions))
    RvPrimaryButton(confirm, onConfirm, enabled = confirmEnabled)
    Spacer(Modifier.height(RvSpace.tight))
    RvTextAction(dismiss, onDismiss)
}

/**
 * A text action beside a primary button, in the form of Revolut's 'See all' row (measured §7): [text] centred, 19 sp bold
 * in the accent, in a 56 dp row; its press is a pill, as it is not a card's foot.
 */
@Composable
internal fun RvTextAction(text: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(RvSize.seeAll).rvPress(CircleShape, Role.Button, onClick),
        contentAlignment = Alignment.Center,
    ) { RvText(text, Rv.type.seeAll, color = Rv.colors.positive, align = TextAlign.Center) }
}

private const val TITLE_LINES = 2
private const val BODY_LINES = 6
