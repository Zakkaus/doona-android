package dev.doona.android.prototype.rv.nodes

import android.content.res.Configuration
import android.os.Trace
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntSize
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.LocalRvShown
import dev.doona.android.designsystem.rv.LocalRvUpdating
import dev.doona.android.designsystem.rv.RV_NO_VALUE
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvBackDisc
import dev.doona.android.designsystem.rv.RvBannerSlot
import dev.doona.android.designsystem.rv.RvBar
import dev.doona.android.designsystem.rv.RvBusy
import dev.doona.android.designsystem.rv.RvCaption
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvChevron
import dev.doona.android.designsystem.rv.RvChrome
import dev.doona.android.designsystem.rv.RvConfirmSheet
import dev.doona.android.designsystem.rv.RvFlag
import dev.doona.android.designsystem.rv.RvFrames
import dev.doona.android.designsystem.rv.RvGlyph
import dev.doona.android.designsystem.rv.RvIconDisc
import dev.doona.android.designsystem.rv.RvLatencyChange
import dev.doona.android.designsystem.rv.RvLongPressTiming
import dev.doona.android.designsystem.rv.RvMenuAnchor
import dev.doona.android.designsystem.rv.RvMotion
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvPageHeader
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvRowEnd
import dev.doona.android.designsystem.rv.RvSearchField
import dev.doona.android.designsystem.rv.RvSize
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvText
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rememberRvFormat
import dev.doona.android.designsystem.rv.rememberRvGlass
import dev.doona.android.designsystem.rv.rvGlassSource
import dev.doona.android.designsystem.rv.rvPress
import dev.doona.android.designsystem.rv.rvShade
import dev.doona.android.designsystem.rv.rvSharedSearch
import dev.doona.android.designsystem.rv.rvSystemScheme
import dev.doona.android.prototype.rv.LocalRvRowActions
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.data.RvNode
import dev.doona.android.prototype.rv.data.RvSubscription
import dev.doona.android.prototype.rv.rvNodeMenu
import dev.doona.android.prototype.rv.rvPageArrived
import dev.doona.android.prototype.rv.rvSharedFlag
import dev.doona.android.prototype.rv.rvSubscriptionMenu
import kotlinx.coroutines.flow.first

/**
 * One subscription: its 訂閱 row, then its nodes. The row opens and closes the group (always open while searching); the
 * group folds away when none of its nodes match. The pieces of the node picker and of a policy group's page, which lists
 * its nodes the same way: the group starts open unless [initiallyOpen] is false.
 */
@Composable
internal fun RvNodeSection(
    sub: RvSubscription,
    members: List<RvNode>,
    matching: Set<String>,
    labels: Map<String, String>,
    currentId: String,
    searching: Boolean,
    onPick: (String) -> Unit,
    onDetail: (String) -> Unit,
    rows: RvRowsComposed,
    modifier: Modifier,
    initiallyOpen: Boolean = true,
) {
    // Read here, so that as more rows are composed this group composes again, not the picker.
    val limit = rows.composed() - rows.first
    if (limit <= 0) return
    val motion = Rv.motion
    var open by rememberSaveable(sub.id) { mutableStateOf(initiallyOpen) }
    val fold = motion.tween<Float>(RvMotion.MOVE_MS)
    val size = motion.tween<IntSize>(RvMotion.MOVE_MS)
    AnimatedVisibility(members.any { it.id in matching }, modifier, enter = fadeIn(fold) + expandVertically(size), exit = fadeOut(fold) + shrinkVertically(size)) {
        Column(Modifier.padding(horizontal = RvSpace.gutter)) {
            RvCard {
                RvSubscriptionFold(sub, open || searching, enabled = !searching) { open = !open }
                AnimatedVisibility(open || searching, enter = expandVertically(size), exit = shrinkVertically(size)) {
                    Column {
                        members.forEachIndexed { order, node ->
                            // The group's 訂閱 row is its first row.
                            if (1 + order >= limit) return@forEachIndexed
                            key(node.id) {
                                AnimatedVisibility(node.id in matching, enter = fadeIn(fold) + expandVertically(size), exit = fadeOut(fold) + shrinkVertically(size)) {
                                    RvNodePickRow(node, labels.getValue(node.id), node.id == currentId, order, onPick, onDetail)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(RvSpace.cardGap))
        }
    }
}

/**
 * 活動's 訂閱 row for [sub] (use, quota, end date, what is left), with a chevron that turns down while [open]; a long
 * press opens its menu (更新, 刪除, which asks first in [RvConfirmSheet]), and an update in progress shows a busy bar
 * where what is left would be.
 */
@Composable
internal fun RvSubscriptionFold(sub: RvSubscription, open: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val format = rememberRvFormat()
    val total = format.bytes(sub.totalBytes)
    val turn by animateFloatAsState(if (open) QUARTER_TURN else 0f, Rv.motion.tween(RvMotion.MOVE_MS), label = "chevron")
    val toggle = Modifier.semantics {
        if (!enabled) return@semantics
        if (open) {
            collapse {
                onToggle()
                true
            }
        } else {
            expand {
                onToggle()
                true
            }
        }
    }
    val updating = sub.id in LocalRvUpdating.current
    var deleting by remember { mutableStateOf(false) }
    RvMenuAnchor(rvSubscriptionMenu(sub) { deleting = true }) { menu, actions ->
        RvRow(
            { RvIconDisc(R.drawable.rv_sym_rss_feed) },
            sub.name,
            stringResource(R.string.rv_subscription_usage, format.bytesIn(sub.usedBytes, total), total.toString(), sub.expiresAtEpochMs?.let { format.expiry(it, RvData.nowEpochMs) } ?: RV_NO_VALUE),
            toggle.then(actions),
            onClick = onToggle.takeIf { enabled },
            onLongClick = menu,
            titleKeepWords = false,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Crossfade(updating, animationSpec = Rv.motion.tween(RvMotion.FADE_MS), label = "update") { busy ->
                    if (busy) RvBusy(Modifier.width(RvSize.busy)) else RvText(format.bytes(sub.leftBytes).toString(), Rv.type.rowValue, align = TextAlign.End)
                }
                Spacer(Modifier.width(RvSpace.trailingGap))
                RvChevron(Modifier.rotate(turn))
            }
        }
    }
    val delete = LocalRvRowActions.current.deleteSubscription
    RvConfirmSheet(
        visible = deleting,
        title = stringResource(R.string.rv_sub_delete_title, sub.name),
        body = pluralStringResource(R.plurals.rv_sub_delete_body, sub.nodes, sub.nodes),
        confirm = stringResource(R.string.rv_menu_delete),
        dismiss = stringResource(R.string.rv_cancel),
        onConfirm = {
            deleting = false
            delete?.invoke(sub.id)
        },
        onDismiss = { deleting = false },
        titleKeepWords = false,
    )
}

/**
 * 節點's row with an info mark at its end: a tap picks the node, the mark opens its page, and a long press opens its
 * menu (設為主節點, 測試延遲, 詳情, 複製分享連結), or its page where the sample offers no menus.
 */
@Composable
internal fun RvNodePickRow(node: RvNode, label: String, current: Boolean, order: Int, onPick: (String) -> Unit, onDetail: (String) -> Unit) {
    val inUse = stringResource(R.string.rv_nodes_in_use)
    val details = stringResource(R.string.rv_nodes_details)
    // Each row is a layer of its own: a row composed after the others records itself, not the whole card again.
    RvMenuAnchor(rvNodeMenu(node, current, onPick, onDetail), Modifier.graphicsLayer {}) { menu, actions ->
        RvRow(
            { RvFlag(node.place.flag, RvNodesSize.rowFlag, Modifier.rvSharedFlag(node.id), current = current) },
            node.name,
            node.protocol + SEPARATOR + label,
            (if (current) Modifier.semantics { stateDescription = inUse } else Modifier).then(actions),
            onClick = { onPick(node.id) },
            onLongClick = menu ?: { onDetail(node.id) },
            titleKeepWords = false,
        ) { RvNodeTrailing(node, order, details, onDetail) }
    }
}

/** The row's latency and change, then the info mark that opens the node's page, on the latency's line ([RvRowEnd]). */
@Composable
internal fun RvNodeTrailing(node: RvNode, order: Int, details: String, onDetail: (String) -> Unit) {
    RvRowEnd({ RvLatencyChange(node.latencyMs, node.changeMs, order = order, node = node.id) }) {
        // The mark's 48 dp target reaches into the row's padding, so the glyph ends where every trailing ink ends.
        Box(
            Modifier
                .offset(x = (RvSize.touch - RvSize.rowGlyph) / 2)
                .size(RvSize.touch)
                .rvPress(CircleShape, Role.Button, { onDetail(node.id) })
                .semantics { contentDescription = details },
            contentAlignment = Alignment.Center,
        ) { RvGlyph(R.drawable.rv_sym_info, RvSize.rowGlyph, Rv.colors.control) }
    }
}

/** Whether [query] (trimmed) is in the node's name, short code, protocol or place [label], ignoring case. */
internal fun RvNode.matches(query: String, label: String): Boolean =
    query.isEmpty() || listOf(name, short, protocol, label).any { it.contains(query, ignoreCase = true) }

/** The picker's rows a group starts at ([first], its 訂閱 row) and how many of them are [composed] so far. */
internal class RvRowsComposed(val first: Int, val composed: () -> Int)

private const val QUARTER_TURN = 90f
