package dev.doona.android.designsystem.rv

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import dev.doona.android.R

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

    /** Between the primary button and the secondary pill under it: two controls stacked, as a list's search and order. */
    val actionGap = RvSpace.controlStack
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
    // A sheet taller than the window keeps clear of the status bar, as far from it as from the screen's bottom edge.
    val top = with(density) { (LocalFrameInsets.current ?: WindowInsets.systemBars).getTop(this).toDp() } + RvSheetSize.bottom
    val drags = rememberDraggableState { drag = (drag + it).coerceAtLeast(0f) }
    // What the scrim does is close the sheet, whatever the sheet is about: it must not read as its title (S2).
    val dismiss = stringResource(R.string.ui_dismiss)
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = progress() }
                .background(Rv.colors.sheetScrim)
                // A tap outside the card, or a screen reader's click on the scrim, dismisses the sheet; a keyboard or a
                // D-pad never stops here, so Tab starts on the sheet's own controls and back closes it. The
                // scrim is the one clickable of the library without the focus outline (q-a11y-focus): it takes no focus.
                .focusProperties { canFocus = false }
                .clickable(interactionSource = null, indication = null, onClickLabel = dismiss, role = Role.Button, onClick = onDismiss),
        )
        RvSheetPanel(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(start = RvSheetSize.inset, top = top, end = RvSheetSize.inset, bottom = RvSheetSize.bottom)
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

/**
 * The sheet's card: the card's fill, its rim in dark, the handle, and [content] on the page's edge line. Content taller
 * than the window scrolls under the fixed handle; content that fits does not scroll, so a drag on it moves the sheet.
 */
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
    Column(modifier.fillMaxWidth().clip(shape).background(colors.card).then(rim).padding(horizontal = RvSheetSize.edge)) {
        Spacer(Modifier.height(RvSheetSize.handleTop))
        Box(Modifier.align(Alignment.CenterHorizontally).size(RvSheetSize.handleWidth, RvSheetSize.handleHeight).clip(CircleShape).background(colors.track))
        // Scrolling only on overflow: an enabled scroll takes the drag even with nothing to scroll, which would leave a
        // sheet that fits no way to be dragged down. The last gap sits inside the scroll, whose clip would otherwise cut
        // the shadow the last button casts into it.
        val scroll = rememberScrollState()
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(scroll, enabled = scroll.maxValue > 0).padding(bottom = last), content = content)
    }
}

/**
 * A question before something that cannot be taken back or needs the user's yes: [title], an optional [body], an
 * optional [extra] (stat rows, a field), then [confirm] as the primary button and [dismiss] as the neutral secondary
 * pill under it, as a card's notice sets its action: no action of the sheet is a coloured text link (the owner,
 * 2026-11-23). [titleKeepWords] and [bodyKeepWords] false for a title or body that quotes a name or an address
 * ([RvText]'s `keepWords`: 刪除「香港 01」?). Back and a tap above the sheet run [onDismissRequest], by default
 * [onDismiss]; a question whose secondary pill means no, while closing it means not yet, passes its own.
 */
@Composable
internal fun RvConfirmSheet(
    visible: Boolean,
    title: String,
    body: String?,
    confirm: String,
    dismiss: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmEnabled: Boolean = true,
    titleKeepWords: Boolean = true,
    bodyKeepWords: Boolean = true,
    onDismissRequest: () -> Unit = onDismiss,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
    RvSheet(visible, onDismissRequest, title) {
        RvConfirmContent(title, body, confirm, dismiss, onConfirm, onDismiss, confirmEnabled, titleKeepWords, bodyKeepWords, extra)
    }
}

/** What [RvConfirmSheet] shows in its card. */
@Composable
internal fun ColumnScope.RvConfirmContent(
    title: String,
    body: String?,
    confirm: String,
    dismiss: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmEnabled: Boolean = true,
    titleKeepWords: Boolean = true,
    bodyKeepWords: Boolean = true,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
    SheetWords(title, body, titleKeepWords, bodyKeepWords)
    extra?.let {
        Spacer(Modifier.height(RvSpace.gutter))
        it()
    }
    Spacer(Modifier.height(RvSheetSize.bodyToActions))
    RvPrimaryButton(confirm, onConfirm, enabled = confirmEnabled)
    Spacer(Modifier.height(RvSheetSize.actionGap))
    RvSecondaryButton(dismiss, onDismiss)
}

/**
 * Something to read with one way out: [title], [body], then [done] as the primary button, which closes it as back or
 * the scrim does ([onDismiss]).
 */
@Composable
internal fun RvInfoSheet(visible: Boolean, title: String, body: String, done: String, onDismiss: () -> Unit) {
    RvSheet(visible, onDismiss, title) { RvInfoContent(title, body, done, onDismiss) }
}

/** What [RvInfoSheet] shows in its card: [RvConfirmContent]'s words and its primary button alone. */
@Composable
internal fun ColumnScope.RvInfoContent(title: String, body: String, done: String, onDone: () -> Unit) {
    SheetWords(title, body)
    Spacer(Modifier.height(RvSheetSize.bodyToActions))
    RvPrimaryButton(done, onDone)
}

/**
 * The title [RvSheetSize.titleTop] under the handle and the optional [body] under it, on the card-content line, each
 * holding its words unless it names something ([titleKeepWords], [bodyKeepWords]).
 */
@Composable
private fun SheetWords(title: String, body: String?, titleKeepWords: Boolean = true, bodyKeepWords: Boolean = true) {
    Spacer(Modifier.height(RvSheetSize.titleTop))
    // The words on the card-content line, as a card's rows set theirs.
    val text = Modifier.padding(horizontal = RvSpace.rowPadding)
    RvText(title, Rv.type.section, text, maxLines = TITLE_LINES, keepWords = titleKeepWords)
    body?.let {
        Spacer(Modifier.height(RvSheetSize.titleToBody))
        RvText(it, Rv.type.rowSub, text, color = Rv.colors.textSecondary, maxLines = BODY_LINES, keepWords = bodyKeepWords)
    }
}

private const val TITLE_LINES = 2
private const val BODY_LINES = 6
