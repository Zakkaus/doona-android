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
}

/**
 * A modal sheet while [visible]: its own window, so a screen reader stays inside it, with the scrim drawn here (the
 * platform's dim is off). It rises in [RvMotion.MENU_MS] and falls in [RvMotion.MENU_OUT_MS] (the menu's measured
 * timings; Revolut's sheet was not timed). Back, a tap on the scrim, or a drag down past [RvMotion.SHEET_DISMISS_SHARE]
 * or let go faster than [RvMotion.sheetFling] calls [onDismiss].
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
 * on the scrim calls [onDismiss]. The card follows a drag down and the scrim lightens with it, as far as the card has
 * gone of its height; let go, it is dismissed or springs back ([RvSpring.Default]) from the finger's speed. [RvSheet]
 * draws it in its window; a golden draws it over the page at 1.
 */
@Composable
internal fun RvSheetLayout(progress: () -> Float, onDismiss: () -> Unit, label: String, content: @Composable ColumnScope.() -> Unit) {
    val density = LocalDensity.current
    var drag by remember { mutableFloatStateOf(0f) }
    var height by remember { mutableFloatStateOf(0f) }
    val motion = Rv.motion
    val fling = with(density) { RvMotion.sheetFling.toPx() }
    val bottom = with(density) { RvSheetSize.bottom.toPx() }
    val drags = rememberDraggableState { drag = (drag + it).coerceAtLeast(0f) }
    // What the scrim does is close the sheet, whatever the sheet is about: it must not read as its title (S2).
    val dismiss = stringResource(R.string.ui_dismiss)
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = progress() * (1f - if (height > 0f) (drag / height).coerceAtMost(1f) else 0f) }
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
                .padding(start = RvSheetSize.inset, end = RvSheetSize.inset, bottom = RvSheetSize.bottom)
                .graphicsLayer {
                    height = size.height
                    translationY = drag + (1f - progress()) * (size.height + bottom)
                }
                .draggable(
                    drags,
                    Orientation.Vertical,
                    onDragStopped = { velocity ->
                        if (rvSheetAway(drag, height, velocity, fling)) {
                            onDismiss()
                        } else {
                            // The card goes no higher than its place: a flick up lands it there, never past.
                            animate(drag, 0f, rvSheetReturn(drag, velocity), motion.spatial(RvSpring.Default, RvMotion.REST_PX)) { value, _ -> drag = value.coerceAtLeast(0f) }
                        }
                    },
                )
                .semantics { paneTitle = label },
            content,
        )
    }
}

/**
 * Whether a sheet let go [drag] px below its place, [height] px tall, at [velocity] px a second (down positive), is
 * dismissed: flung down faster than [fling], or past [RvMotion.SHEET_DISMISS_SHARE] of its height unless flung back up.
 */
internal fun rvSheetAway(drag: Float, height: Float, velocity: Float, fling: Float): Boolean =
    velocity > fling || (drag > height * RvMotion.SHEET_DISMISS_SHARE && velocity > -fling)

/** The speed a sheet let go [drag] px below its place springs back with: the finger's, unless it is at its place already. */
internal fun rvSheetReturn(drag: Float, velocity: Float): Float = if (drag > 0f) velocity else 0f

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
 * A question before something that cannot be taken back or needs the user's yes: [title], an optional [body], an
 * optional [extra] (stat rows, a field), then [confirm] as the primary button and [dismiss] as the neutral secondary
 * pill under it, as a card's notice sets its action: no action of the sheet is a coloured text link (the owner,
 * 2026-11-23). [titleKeepWords] and [bodyKeepWords] false for a title or body that quotes a name or an address
 * ([RvText]'s `keepWords`: 刪除「香港 01」?). Back and a tap above the sheet run [onDismissRequest], by default
 * [onDismiss]; a question whose secondary pill means no, while closing it means not yet, passes its own. While it
 * closes it keeps the words it last showed open: what it asked about may already be gone (a deleted subscription).
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
    val words = remember { ShownWords(title, body) }
    if (visible) {
        words.title = title
        words.body = body
    }
    RvSheet(visible, onDismissRequest, words.title) {
        RvConfirmContent(words.title, words.body, confirm, dismiss, onConfirm, onDismiss, confirmEnabled, titleKeepWords, bodyKeepWords, extra)
    }
}

/** The [title] and [body] an [RvConfirmSheet] last showed open; plain fields, as the sheet's own composition sets them. */
private class ShownWords(var title: String, var body: String?)

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
