package dev.doona.android.prototype.rv.nodes

import android.content.res.Configuration
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
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
import dev.doona.android.designsystem.theme.LocalFrameInsets
import dev.doona.android.prototype.rv.LocalRvUpdating
import dev.doona.android.prototype.rv.RV_NO_VALUE
import dev.doona.android.prototype.rv.Rv
import dev.doona.android.prototype.rv.RvBackDisc
import dev.doona.android.prototype.rv.RvBar
import dev.doona.android.prototype.rv.RvBusy
import dev.doona.android.prototype.rv.RvCard
import dev.doona.android.prototype.rv.RvChevron
import dev.doona.android.prototype.rv.RvChrome
import dev.doona.android.prototype.rv.RvFlag
import dev.doona.android.prototype.rv.RvGlyph
import dev.doona.android.prototype.rv.RvIconDisc
import dev.doona.android.prototype.rv.RvLargeTitle
import dev.doona.android.prototype.rv.RvLatencyChange
import dev.doona.android.prototype.rv.RvLongPressTiming
import dev.doona.android.prototype.rv.RvMenuAnchor
import dev.doona.android.prototype.rv.RvMotion
import dev.doona.android.prototype.rv.RvRow
import dev.doona.android.prototype.rv.RvSearchField
import dev.doona.android.prototype.rv.RvSize
import dev.doona.android.prototype.rv.RvSpace
import dev.doona.android.prototype.rv.RvText
import dev.doona.android.prototype.rv.RvTheme
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.data.RvNode
import dev.doona.android.prototype.rv.data.RvSubscription
import dev.doona.android.prototype.rv.rememberRvFormat
import dev.doona.android.prototype.rv.rememberRvGlass
import dev.doona.android.prototype.rv.rvGlassSource
import dev.doona.android.prototype.rv.rvNodeMenu
import dev.doona.android.prototype.rv.rvPageArrived
import dev.doona.android.prototype.rv.rvPress
import dev.doona.android.prototype.rv.rvShade
import dev.doona.android.prototype.rv.rvSharedFlag
import dev.doona.android.prototype.rv.rvSharedSearch
import dev.doona.android.prototype.rv.rvSubscriptionMenu
import dev.doona.android.prototype.rv.rvSystemScheme
import kotlinx.coroutines.flow.first

/** The picker's root, for tests. */
internal const val RV_PICKER = "rv-picker"

/**
 * The node picker 活動's pull-down opens (ARCH D30), built from the 節點 page's parts: the bar holds the back disc and
 * the search pill as a field; under the large title, one card per subscription, its 訂閱 row (usage and end date, which
 * opens and closes the group) over its nodes in 節點's rows, the [currentId] node's flag checked. A tap on a node picks it
 * ([onPick]: the caller switches and closes); a long press or the row's info mark opens its page ([onDetail]). Typing
 * filters by name, code, protocol or place; rows and groups that stop matching fold away and come back. [section] gives
 * the n-th section (0 the bar, 1 the title, then each group) its modifier, for P3's staggered drop; [stretch] moves the
 * list down by that many pixels while a pull at its top would close the picker. As the [searchPage] (brief rv-gestures
 * G7) its field is the search pill's, and the keyboard comes up once the page has arrived.
 */
@Composable
internal fun RvNodePicker(
    nodes: List<RvNode>,
    subscriptions: List<RvSubscription>,
    currentId: String,
    onPick: (String) -> Unit,
    onDetail: (String) -> Unit,
    onClose: () -> Unit,
    section: (Int) -> Modifier = { Modifier },
    stretch: () -> Float = { 0f },
    searchPage: Boolean = false,
) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val focus = remember { FocusRequester() }
    val arrived = rvPageArrived()
    // The search page (G7): the keyboard comes up once the page has arrived, and the list's end follows it as it rises.
    LaunchedEffect(searchPage) {
        if (!searchPage) return@LaunchedEffect
        snapshotFlow { arrived() }.first { it }
        // One frame later the field is placed and can take the focus.
        withFrameNanos {}
        focus.requestFocus()
    }
    val bottom = if (searchPage) insets.union(WindowInsets.ime) else insets
    val scroll = rememberScrollState()
    val glass = rememberRvGlass()
    var query by rememberSaveable { mutableStateOf("") }
    val labels = nodes.associate { it.id to stringResource(it.place.label) }
    val matching = nodes.filter { it.matches(query.trim(), labels.getValue(it.id)) }.mapTo(mutableSetOf()) { it.id }
    RvLongPressTiming {
        Box(Modifier.fillMaxSize().testTag(RV_PICKER)) {
            // A pull down at the top, which closes the picker, moves the list under the bar with the finger.
            Column(Modifier.fillMaxSize().rvGlassSource(glass).background(Rv.colors.page).verticalScroll(scroll).graphicsLayer { translationY = stretch() }) {
                Column(section(1)) {
                    RvLargeTitle(stringResource(R.string.rv_nodes_title), Modifier, insets)
                    RvText(stringResource(R.string.rv_main_node_hint), Rv.type.rowSub, Modifier.padding(start = RvSpace.gutter, end = RvSpace.gutter, bottom = RvSpace.subGap * 2), color = Rv.colors.textSecondary)
                }
                subscriptions.forEachIndexed { i, sub ->
                    val members = nodes.filter { it.subscription == sub.name }
                    Group(sub, members, matching, labels, currentId, searching = query.isNotBlank(), onPick, onDetail, section(2 + i))
                }
                Spacer(Modifier.windowInsetsBottomHeight(bottom))
            }
            RvBar(glass, insets, rvShade(scroll), section(0)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RvBackDisc(onClose)
                    Spacer(Modifier.width(RvChrome.controlGap - RvChrome.touchPad))
                    val field = Modifier.weight(1f).padding(end = RvSpace.gutter - RvChrome.touchPad)
                    RvSearchField(glass, query, { query = it }, if (searchPage) field.rvSharedSearch() else field, focus)
                }
            }
        }
    }
}

/**
 * One subscription: its 訂閱 row, then its nodes. The row opens and closes the group (always open while searching); the
 * group folds away when none of its nodes match.
 */
@Composable
private fun Group(
    sub: RvSubscription,
    members: List<RvNode>,
    matching: Set<String>,
    labels: Map<String, String>,
    currentId: String,
    searching: Boolean,
    onPick: (String) -> Unit,
    onDetail: (String) -> Unit,
    modifier: Modifier,
) {
    val motion = Rv.motion
    var open by rememberSaveable(sub.id) { mutableStateOf(true) }
    val fold = motion.tween<Float>(RvMotion.MOVE_MS)
    val size = motion.tween<IntSize>(RvMotion.MOVE_MS)
    AnimatedVisibility(members.any { it.id in matching }, modifier, enter = fadeIn(fold) + expandVertically(size), exit = fadeOut(fold) + shrinkVertically(size)) {
        Column(Modifier.padding(horizontal = RvSpace.gutter)) {
            RvCard {
                SubscriptionRow(sub, open || searching, enabled = !searching) { open = !open }
                AnimatedVisibility(open || searching, enter = expandVertically(size), exit = shrinkVertically(size)) {
                    Column {
                        members.forEachIndexed { order, node ->
                            key(node.id) {
                                AnimatedVisibility(node.id in matching, enter = fadeIn(fold) + expandVertically(size), exit = fadeOut(fold) + shrinkVertically(size)) {
                                    NodeRow(node, labels.getValue(node.id), node.id == currentId, order, onPick, onDetail)
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
 * press opens its menu (更新, 刪除), and an update in progress shows a busy bar where what is left would be.
 */
@Composable
private fun SubscriptionRow(sub: RvSubscription, open: Boolean, enabled: Boolean, onToggle: () -> Unit) {
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
    RvMenuAnchor(rvSubscriptionMenu(sub)) { menu, actions ->
        RvRow(
            { RvIconDisc(R.drawable.rv_sym_rss_feed) },
            sub.name,
            stringResource(R.string.rv_subscription_usage, format.bytesIn(sub.usedBytes, total), total.toString(), sub.expiresAtEpochMs?.let { format.expiry(it, RvData.nowEpochMs) } ?: RV_NO_VALUE),
            toggle.then(actions),
            onClick = onToggle.takeIf { enabled },
            onLongClick = menu,
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
}

/**
 * 節點's row with an info mark at its end: a tap picks the node, the mark opens its page, and a long press opens its
 * menu (設為主節點, 測試延遲, 詳情, 複製分享連結), or its page where the sample offers no menus.
 */
@Composable
private fun NodeRow(node: RvNode, label: String, current: Boolean, order: Int, onPick: (String) -> Unit, onDetail: (String) -> Unit) {
    val inUse = stringResource(R.string.rv_nodes_in_use)
    val details = stringResource(R.string.rv_nodes_details)
    RvMenuAnchor(rvNodeMenu(node, current, onPick, onDetail)) { menu, actions ->
        RvRow(
            { RvFlag(node.place.name, RvNodesSize.rowFlag, Modifier.rvSharedFlag(node.id), current = current) },
            node.name,
            node.protocol + SEPARATOR + label,
            (if (current) Modifier.semantics { stateDescription = inUse } else Modifier).then(actions),
            onClick = { onPick(node.id) },
            onLongClick = menu ?: { onDetail(node.id) },
        ) { NodeTrailing(node, order, details, onDetail) }
    }
}

/** The row's latency and change, then the info mark that opens the node's page. */
@Composable
private fun NodeTrailing(node: RvNode, order: Int, details: String, onDetail: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RvLatencyChange(node.latencyMs, node.changeMs, order, node.id)
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
private fun RvNode.matches(query: String, label: String): Boolean =
    query.isEmpty() || listOf(name, short, protocol, label).any { it.contains(query, ignoreCase = true) }

private const val QUARTER_TURN = 90f

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvNodePickerPreview() {
    val dark = isSystemInDarkTheme()
    RvTheme(rvSystemScheme(dark), dark) { RvNodePicker(RvData.nodes, RvData.subscriptions, RvData.CURRENT_ID, onPick = {}, onDetail = {}, onClose = {}) }
}
