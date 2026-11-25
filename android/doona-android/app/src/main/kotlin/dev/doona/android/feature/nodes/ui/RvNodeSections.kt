package dev.doona.android.feature.nodes.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalRvUpdating
import dev.doona.android.designsystem.rv.RV_NO_VALUE
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvConfirmSheet
import dev.doona.android.designsystem.rv.RvFlag
import dev.doona.android.designsystem.rv.RvFoldRow
import dev.doona.android.designsystem.rv.RvGlyph
import dev.doona.android.designsystem.rv.RvLatencyChange
import dev.doona.android.designsystem.rv.RvMenuAnchor
import dev.doona.android.designsystem.rv.RvMotion
import dev.doona.android.designsystem.rv.RvNodesSize
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvRowEnd
import dev.doona.android.designsystem.rv.RvRowPart
import dev.doona.android.designsystem.rv.RvSize
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.SEPARATOR
import dev.doona.android.designsystem.rv.rememberRvFormat
import dev.doona.android.designsystem.rv.rvPress
import dev.doona.android.designsystem.rv.rvSharedFlag
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.RvSubscription
import kotlinx.coroutines.flow.first

/**
 * One subscription: its 訂閱 row, then its nodes. The row opens and closes the group (always open while searching); the
 * group folds away when none of its nodes match. The pieces of the node picker and of a policy group's page, which lists
 * its nodes the same way: the group starts open unless [initiallyOpen] is false. A page that marks its rows its own way
 * (a name's emoji drawn as our icons, the network a node is chosen on) draws them with [row] in place of [RvNodePickRow].
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
    nowEpochMs: Long,
    modifier: Modifier,
    initiallyOpen: Boolean = true,
    row: (@Composable (node: RvNode, label: String, current: Boolean, order: Int) -> Unit)? = null,
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
                RvSubscriptionFold(sub, open || searching, enabled = !searching, nowEpochMs) { open = !open }
                AnimatedVisibility(open || searching, enter = expandVertically(size), exit = shrinkVertically(size)) {
                    Column {
                        members.forEachIndexed { order, node ->
                            // The group's 訂閱 row is its first row.
                            if (1 + order >= limit) return@forEachIndexed
                            key(node.id) {
                                AnimatedVisibility(node.id in matching, enter = fadeIn(fold) + expandVertically(size), exit = fadeOut(fold) + shrinkVertically(size)) {
                                    val label = labels.getValue(node.id)
                                    if (row != null) row(node, label, node.id == currentId, order) else RvNodePickRow(node, label, node.id == currentId, order, onPick, onDetail)
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
internal fun RvSubscriptionFold(sub: RvSubscription, open: Boolean, enabled: Boolean, nowEpochMs: Long, onToggle: () -> Unit) {
    val format = rememberRvFormat()
    val total = format.bytes(sub.totalBytes)
    val updating = sub.id in LocalRvUpdating.current
    var deleting by remember { mutableStateOf(false) }
    RvMenuAnchor(rvSubscriptionMenu(sub) { deleting = true }) { menu, actions ->
        RvFoldRow(
            R.drawable.rv_sym_rss_feed,
            sub.name,
            stringResource(R.string.rv_subscription_usage, format.usedOf(sub.usedBytes, total), total.toString(), sub.expiresAtEpochMs?.let { format.expiry(it, nowEpochMs) } ?: RV_NO_VALUE),
            format.bytes(sub.leftBytes).toString(),
            open,
            enabled,
            onToggle,
            actions,
            menu,
            busy = updating,
        )
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
 * menu (設為主節點, 測試延遲, 詳情, 複製分享連結), or its page where the sample offers no menus. A page may show the node
 * under another [title] (a name without its emoji), put [lead] before the line under it (the network a node is chosen on,
 * its Score mark) and draw its leading disc itself with [mark], told whether the node is chosen.
 */
@Composable
internal fun RvNodePickRow(
    node: RvNode,
    label: String,
    current: Boolean,
    order: Int,
    onPick: (String) -> Unit,
    onDetail: (String) -> Unit,
    title: String = node.name,
    lead: String? = null,
    mark: (@Composable (current: Boolean) -> Unit)? = null,
) {
    val inUse = stringResource(R.string.rv_nodes_in_use)
    val details = stringResource(R.string.rv_nodes_details)
    // Each row is a layer of its own: a row composed after the others records itself, not the whole card again.
    RvMenuAnchor(rvNodeMenu(node, current, onPick, onDetail), Modifier.graphicsLayer {}) { menu, actions ->
        RvRow(
            { if (mark != null) mark(current) else RvFlag(node.place.flag, RvNodesSize.rowFlag, Modifier.rvSharedFlag(node.id), current = current) },
            title,
            listOfNotNull(lead, node.protocol, label).joinToString(SEPARATOR),
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
                .testTag(RvRowPart.TARGET)
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
