package dev.doona.android.prototype.rv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import dev.doona.android.R
import dev.doona.android.engine.Status
import dev.doona.android.prototype.rv.nodes.RvStatusChip

/**
 * How 活動 shows a connect or a disconnect under way, for the owner to compare (brief pv-c1); starting and stopping
 * share each look, with their own words. [Caption] is today's: the caption alone says so. Each other look takes one
 * earlier style:
 * - [Chip] (v1): the caption becomes a status chip in the fair tone, the old app's status light for 連線中
 *   (`DoonaStatusLight(Tone.Warn)`) and O1's status dot, as the library's [RvStatusChip];
 * - [Bar] (v2): the busy bar stands where the rates stand, as wide as the figure, the old app's loading page (its progress
 *   ring where the content comes) in the library's own busy part, [RvBusy];
 * - [Tint] (v3): the dot map takes the fair tone, rv-hero-variants V4's hero tinted by the connection state, on the
 *   page's one tinted area ([RvHeader]).
 */
internal enum class RvBusyLook { Caption, Chip, Bar, Tint }

/** Connecting or disconnecting: under way, with no known end. */
internal val Status.busy: Boolean get() = this == Status.Starting || this == Status.Stopping

/** The look [status] takes: [look] while it is [busy], else the caption's, as before. */
internal fun RvBusyLook.of(status: Status): RvBusyLook = if (status.busy) this else RvBusyLook.Caption

/** v1: [caption] as a status chip in the fair tone, centred on the caption's line and spilling over its ends, so nothing moves. */
@Composable
internal fun RvBusyChip(caption: String) {
    Box(contentAlignment = Alignment.Center) {
        RvText(caption, Rv.type.caption, Modifier.rvHeld())
        Box(Modifier.matchParentSize().wrapContentSize(unbounded = true), contentAlignment = Alignment.Center) {
            RvStatusChip(R.drawable.rv_sym_power, caption, Rv.colors.fair)
        }
    }
}

/** v2: the busy bar across the room [figure] takes, centred on it; the figure holds its place unseen, so nothing moves. */
@Composable
internal fun RvBusyFigure(figure: @Composable () -> Unit) {
    Box(contentAlignment = Alignment.Center) {
        Box(Modifier.rvHeld()) { figure() }
        RvBusy(Modifier.matchParentSize().wrapContentHeight())
    }
}

/** Keeps the element's place, unseen and unread: a figure that means nothing while the tunnel connects. */
internal fun Modifier.rvHeld(): Modifier = alpha(0f).clearAndSetSemantics {}
