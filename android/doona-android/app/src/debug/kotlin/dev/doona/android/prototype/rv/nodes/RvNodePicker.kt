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

/** The picker's root, for tests. */
internal const val RV_PICKER = "rv-picker"

/**
 * The node picker 活動's pull-down opens (ARCH D30), built from the 節點 page's parts: the bar holds the back disc and the
 * search pill as a field; under the large title and its caption ([RvPageHeader]), one card per subscription, its 訂閱 row
 * (usage and end date, which opens and closes the group) over its nodes in 節點's rows, the [currentId] node's flag
 * checked. A tap on a node picks it ([onPick]: the caller switches and closes); a long press or the row's info mark
 * opens its page ([onDetail]). Typing filters by name, code, protocol or place; rows and groups that stop matching fold
 * away and come back. [section] gives the n-th section (0 the bar, 1 the title, then each group) its modifier, for P3's
 * staggered drop; [stretch] moves the list down by that many pixels while a pull at its top would close the picker. As
 * the [searchPage] (brief rv-gestures G7) its field is the search pill's, and the keyboard comes up once the page has
 * arrived. The large title is [title], 節點 unless the caller opened the picker from a card that names it (規則's 主節點).
 * A [banner] (a failed action: a node switch) stands under the title and its caption, in [RvBannerSlot], and drops with
 * them.
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
    title: String = stringResource(R.string.rv_nodes_title),
    banner: RvNotice? = null,
) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val focus = remember { FocusRequester() }
    val arrived = rvPageArrived()
    // The search page (G7): the keyboard comes up once the page has arrived, and the list's end follows it as it rises.
    LaunchedEffect(searchPage) {
        if (!searchPage) return@LaunchedEffect
        RvFrames.begin(SEARCH_GESTURE)
        snapshotFlow { arrived() }.first { it }
        RvFrames.end(SEARCH_GESTURE)
        // One frame later the field is placed and can take the focus.
        withFrameNanos {}
        Trace.beginSection(TRACE_FOCUS)
        focus.requestFocus()
        Trace.endSection()
    }
    val bottom = if (searchPage) insets.union(WindowInsets.ime) else insets
    val scroll = rememberScrollState()
    val glass = rememberRvGlass()
    var query by rememberSaveable { mutableStateOf("") }
    val labels = nodes.associate { it.id to stringResource(it.place.label) }
    val matching = nodes.filter { it.matches(query.trim(), labels.getValue(it.id)) }.mapTo(mutableSetOf()) { it.id }
    val composed = rememberRowsComposed(subscriptions.size + nodes.size)
    RvLongPressTiming {
        Box(Modifier.fillMaxSize().testTag(RV_PICKER)) {
            // A pull down at the top, which closes the picker, moves the list under the bar with the finger.
            Column(Modifier.fillMaxSize().rvGlassSource(glass).background(Rv.colors.page).verticalScroll(scroll).graphicsLayer { translationY = stretch() }) {
                Column(section(1)) {
                    RvPageHeader(title, Modifier, insets, caption = { RvCaption(stringResource(R.string.rv_main_node_hint)) })
                    RvBannerSlot(banner)
                }
                var first = 0
                subscriptions.forEachIndexed { i, sub ->
                    val members = nodes.filter { it.subscription == sub.name }
                    val rows = RvRowsComposed(first, composed)
                    RvNodeSection(sub, members, matching, labels, currentId, searching = query.isNotBlank(), onPick, onDetail, rows, section(2 + i))
                    first += 1 + members.size
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
 * How many of the picker's [total] rows (訂閱 rows and node rows, in order) are composed: shown, at first those that
 * fill the window, then [ROWS_PER_FRAME] more each frame until all are; composed ahead and hidden ([LocalRvShown]), from
 * none, a row a frame, filling the window at once if it is shown before they are in. On the phone, composing all 50 rows
 * in the page's first frame took 52 ms of the UI thread in the profile build (23 ms to compose, 10 to measure, 7 to
 * draw); the rows below the window come in over the next frames, out of sight.
 */
@Composable
private fun rememberRowsComposed(total: Int): () -> Int {
    val window = LocalWindowInfo.current.containerSize.height
    val row = with(LocalDensity.current) { RvSize.rowTwoLine.roundToPx() }
    val fill = window / row.coerceAtLeast(1) + 1
    val shown = LocalRvShown.current
    // Not observed here: whether it is shown decides where the count starts, not when this composes.
    val composed = remember { mutableIntStateOf(if (Snapshot.withoutReadObservation { shown() }) fill else 0) }
    LaunchedEffect(total) {
        while (composed.intValue < total) {
            withFrameNanos {}
            composed.intValue = (if (shown()) maxOf(composed.intValue, fill) else composed.intValue) + ROWS_PER_FRAME
        }
    }
    return { if (shown()) maxOf(composed.intValue, fill) else composed.intValue }
}

/**
 * A row is about 3 ms to compose, apply and measure on the phone (profile build, 120 Hz): one a frame keeps a frame
 * within its budget while the page or the sheet moves; four made each of those frames 13 to 20 ms.
 */
private const val ROWS_PER_FRAME = 1
private const val SEARCH_GESTURE = "search"
private const val TRACE_FOCUS = "RvSearchFocus"

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvNodePickerPreview() {
    val dark = isSystemInDarkTheme()
    RvTheme(rvSystemScheme(dark), dark) { RvNodePicker(RvData.nodes, RvData.subscriptions, RvData.CURRENT_ID, onPick = {}, onDetail = {}, onClose = {}) }
}
