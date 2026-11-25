package dev.doona.android.feature.stats

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.RV_GHOST_UP
import dev.doona.android.designsystem.rv.RV_NO_VALUE
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvFigure
import dev.doona.android.designsystem.rv.RvFilterChip
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvPageHeader
import dev.doona.android.designsystem.rv.RvPart
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvStatsSpace
import dev.doona.android.designsystem.rv.figure
import dev.doona.android.designsystem.rv.rememberRvFormat
import dev.doona.android.feature.model.RvDay
import java.text.NumberFormat

/**
 * The 統計 page in the language of Revolut's Analytics page: the back disc and the large title, the subscription
 * and range chips, then the cards: 已用流量 and the paged tiles (the app-side daily history), 訂閱流量 (the
 * subscriptions' quota), 節點分布 (node names and flags), 延遲分布 (latency results), 分流比例 and the tools. Where
 * each figure comes from is noted on [RvStatsState], not on the page (N-14). With a Score group ([RvStatsState.score]), a
 * 綜合評分 card follows 延遲分布. The chips open the subscription filter ([onSubscriptions]) and the range ([onRange]); the
 * tools test every node, update every subscription and open the export.
 */
@Composable
internal fun RvStatsScreen(
    state: RvStatsState,
    onBack: () -> Unit,
    onSubscriptions: () -> Unit,
    onRange: () -> Unit,
    onTestAll: () -> Unit,
    onUpdateAll: () -> Unit,
    onExport: () -> Unit,
    empty: RvStatsEmptyLook = RvStatsEmptyLook.Grid,
) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_stats_title)
    RvScrollPage(title, insets, onBack = onBack) { titleBlock ->
        RvPageHeader(title, titleBlock, insets, control = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RvFilterChip(stringResource(R.string.rv_stats_all_subscriptions), onSubscriptions)
                Spacer(Modifier.weight(1f))
                RvFilterChip(stringResource(state.range.label), onRange)
            }
        })
        RvGutter { RvSpentCard(state) }
        Spacer(Modifier.height(RvSpace.cardGap))
        DayTiles(state.today, empty)
        Spacer(Modifier.height(RvSpace.cardGap))
        RvGutter {
            RvQuotaCard(state.subscriptions, state.nowEpochMs)
            RvNodeMapCard(state)
            RvLatencyCard(state)
            state.score?.let { RvScoreStatsCard(it) }
            RvSplitCard(state.split)
            RvToolsCard(tools(state, onTestAll, onUpdateAll, onExport))
        }
    }
}

/**
 * 下載, 上傳 and 連線次數 as Revolut's paged half tiles: two side by side, as far apart as 活動's pair, and the third's edge
 * at the screen's edge, which says the row scrolls. All three come from the day's usage: bytes each way and flows opened.
 */
@Composable
private fun DayTiles(day: RvDay, empty: RvStatsEmptyLook) {
    val locale = LocalConfiguration.current.locales[0]
    val format = rememberRvFormat()
    val count = day.connections?.sum()?.let { NumberFormat.getIntegerInstance(locale).format(it) }
    val tiles = listOf(
        RvDayTile(stringResource(R.string.ui_download), format.bytes(day.downTotal).figure(), day.down, Rv.colors.down.line),
        RvDayTile(stringResource(R.string.ui_upload), format.bytes(day.upTotal).figure(), day.up, Rv.colors.up.line, ghost = RV_GHOST_UP),
        RvDayTile(
            stringResource(R.string.rv_stats_connections),
            RvFigure(count ?: RV_NO_VALUE, ""),
            day.connections?.map { it.toLong() },
            Rv.colors.textSecondary,
        ),
    )
    val pager = rememberPagerState { tiles.size }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // From the gutter, two tiles RvSpace.tileGap apart, then the third's first RvStatsSpace.tilePeek before the edge.
        val width = (maxWidth - RvSpace.gutter - RvSpace.tileGap * 2 - RvStatsSpace.tilePeek) / 2
        HorizontalPager(
            pager,
            Modifier.testTag(RvPart.BLOCK),
            contentPadding = PaddingValues(horizontal = RvSpace.gutter),
            pageSize = PageSize.Fixed(width),
            pageSpacing = RvSpace.tileGap,
            verticalAlignment = Alignment.Top,
        ) { page -> RvDayTileCard(tiles[page], empty = empty) }
    }
}

/** The tools card's rows: test every node, refresh every subscription, export what a bug report needs. */
@Composable
private fun tools(state: RvStatsState, onTestAll: () -> Unit, onUpdateAll: () -> Unit, onExport: () -> Unit): List<RvStatsTool> {
    val format = rememberRvFormat()
    val nodes = pluralStringResource(R.plurals.rv_stats_node_count, state.nodes.size, state.nodes.size)
    val subscriptions = pluralStringResource(R.plurals.rv_stats_subscriptions, state.subscriptions.size, state.subscriptions.size)
    val refreshed = state.subscriptions.map { it.updatedAtEpochMs }.filter { it > 0L }.maxOrNull()?.let {
        stringResource(R.string.rv_updated, format.time(it))
    }
    return listOf(
        RvStatsTool(R.drawable.rv_sym_speed, stringResource(R.string.rv_stats_test_all), nodes, onTestAll),
        RvStatsTool(R.drawable.rv_stats_ic_sync, stringResource(R.string.rv_stats_update_all), listOfNotNull(subscriptions, refreshed).joinToString(" · "), onUpdateAll),
        RvStatsTool(R.drawable.rv_sym_ios_share, stringResource(R.string.rv_stats_export), stringResource(R.string.rv_stats_export_detail), onExport),
    )
}
