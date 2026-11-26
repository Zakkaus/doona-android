package dev.doona.android.feature.stats

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import dev.doona.android.designsystem.rv.RV_NO_VALUE
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvChipPick
import dev.doona.android.designsystem.rv.RvFigure
import dev.doona.android.designsystem.rv.RvFilterChipRow
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvMenuItem
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvPageHeader
import dev.doona.android.designsystem.rv.RvPart
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvStatsDots
import dev.doona.android.designsystem.rv.RvStatsSpace
import dev.doona.android.designsystem.rv.SEPARATOR
import dev.doona.android.designsystem.rv.figure
import dev.doona.android.designsystem.rv.rememberRvFormat
import java.text.NumberFormat

/**
 * The 統計 page in the language of Revolut's Analytics page: the back disc and the large title, the subscription
 * and range chips, then the cards: 已用流量 and the paged tiles (the daily history, only where it is kept:
 * [RvStatsState.daily]), 訂閱流量 (the subscriptions' quota), 節點分布 (node names and flags), 延遲分布 (latency
 * results), 分流比例 and the tools. Where
 * each figure comes from is noted on [RvStatsState], not on the page (N-14). With a Score group ([RvStatsState.score]), a
 * 綜合評分 card follows 延遲分布. The chips open menus of the subscriptions ([onSubscription], null for all) and of the
 * ranges ([onRange]); the tools test every node, update every subscription and open the export. A [banner] (a tool that
 * failed) heads the cards. In 本週 and 本月, 連線次數 and 分流比例 keep today's figures under a 今天 tag, since past days keep
 * neither (owner, 2026-11-26 01:03, q-stats-rangegap A).
 */
@Composable
internal fun RvStatsScreen(
    state: RvStatsState,
    onBack: () -> Unit,
    onSubscription: (String?) -> Unit,
    onRange: (RvStatsRange) -> Unit,
    onTestAll: () -> Unit,
    onUpdateAll: () -> Unit,
    onExport: () -> Unit,
    banner: RvNotice? = null,
) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_stats_title)
    RvScrollPage(title, insets, onBack = onBack) { titleBlock ->
        RvPageHeader(title, titleBlock, insets, control = { Chips(state, onSubscription, onRange) })
        if (state.daily) {
            RvGutter(banner = banner) { RvSpentCard(state) }
            Spacer(Modifier.height(RvSpace.cardGap))
            DayTiles(state)
            Spacer(Modifier.height(RvSpace.cardGap))
        }
        RvGutter(banner = banner.takeUnless { state.daily }) {
            RvQuotaCard(state.shownSubscriptions, state.nowEpochMs)
            RvNodeMapCard(state)
            RvLatencyCard(state)
            state.score?.let { RvScoreStatsCard(it) }
            RvSplitCard(state.split, splitScope(state))
            RvToolsCard(tools(state, onTestAll, onUpdateAll, onExport))
        }
    }
}

/**
 * The two chips under the title, the library's filter row: the subscription at the start (its name once picked) and the
 * range at the end, each opening a menu of its choices, as the 節點 and group pages' chips do.
 */
@Composable
private fun Chips(state: RvStatsState, onSubscription: (String?) -> Unit, onRange: (RvStatsRange) -> Unit) {
    val all = stringResource(R.string.rv_stats_all_subscriptions)
    val subscriptions = listOf(RvMenuItem(all, R.drawable.rv_sym_rss_feed) { onSubscription(null) }) +
        state.subscriptions.map { sub -> RvMenuItem(sub.name, R.drawable.rv_sym_rss_feed) { onSubscription(sub.id) } }
    val ranges = RvStatsRange.entries.map { range -> RvMenuItem(stringResource(range.label), R.drawable.rv_sym_schedule) { onRange(range) } }
    val picked = state.subscriptions.firstOrNull { it.id == state.subscriptionId }?.name ?: all
    RvFilterChipRow(RvChipPick(picked, items = subscriptions), RvChipPick(stringResource(state.range.label), items = ranges))
}

/**
 * 下載, 上傳 and 連線次數 as Revolut's paged half tiles: two side by side, as far apart as 活動's pair, and the third's edge
 * at the screen's edge, which says the row scrolls, and the pager's dots under them (owner, 2026-11-24 01:33). 下載 and
 * 上傳 are the range's bytes each way, by the hour today and by the day in 本週 and 本月; 連線次數 is the flows opened
 * today, which past days do not keep, so in a longer range its tag says 今天.
 */
@Composable
private fun DayTiles(state: RvStatsState) {
    val locale = LocalConfiguration.current.locales[0]
    val format = rememberRvFormat()
    val period = state.period
    val bytes = period?.days ?: state.today
    val slots = period?.slots ?: HOURS
    val quiet = stringResource(state.range.quiet)
    val flows = state.today.connections
    val count = flows?.sum()?.let { NumberFormat.getIntegerInstance(locale).format(it) }
    val tiles = listOf(
        RvDayTile(stringResource(R.string.ui_download), format.bytes(bytes.downTotal).figure(), bytes.down, Rv.colors.down.line, slots = slots, quiet = quiet),
        RvDayTile(stringResource(R.string.ui_upload), format.bytes(bytes.upTotal).figure(), bytes.up, Rv.colors.up.line, slots = slots, quiet = quiet),
        RvDayTile(
            stringResource(R.string.rv_stats_connections),
            RvFigure(count ?: RV_NO_VALUE, ""),
            flows?.map { it.toLong() },
            Rv.colors.textSecondary,
            quiet = stringResource(R.string.rv_stats_nothing_today),
            tag = stringResource(R.string.rv_stats_today).takeIf { period != null && flows != null },
        ),
    )
    val pager = rememberPagerState { tiles.size }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // From the gutter, two tiles RvSpace.tileGap apart, then the third's first RvStatsSpace.tilePeek before the edge.
        val width = (maxWidth - RvSpace.gutter - RvSpace.tileGap * 2 - RvStatsSpace.tilePeek) / 2
        Column(Modifier.testTag(RvPart.BLOCK)) {
            HorizontalPager(
                pager,
                contentPadding = PaddingValues(horizontal = RvSpace.gutter),
                pageSize = PageSize.Fixed(width),
                pageSpacing = RvSpace.tileGap,
                verticalAlignment = Alignment.Top,
            ) { page -> RvDayTileCard(tiles[page]) }
            Spacer(Modifier.height(RvStatsSpace.dotsGap))
            RvStatsDots(tiles.size, pager.currentPage)
        }
    }
}

/**
 * What 分流比例 counts when it differs from the chips: 本次啟動 where no usage by the day is kept (honk counts since it
 * started), 今天 in a longer range (past days keep no route targets), and all subscriptions while one is picked (bytes
 * are not counted by subscription); empty when it follows them.
 */
@Composable
private fun splitScope(state: RvStatsState): List<String> = listOfNotNull(
    stringResource(R.string.rv_stats_since_start).takeIf { !state.daily },
    stringResource(R.string.rv_stats_today).takeIf { state.daily && state.period != null },
    stringResource(R.string.rv_stats_all_subscriptions).takeIf { state.subscriptionId != null },
)

/** The tools card's rows: test every node, refresh every subscription, export what a bug report needs. */
@Composable
private fun tools(state: RvStatsState, onTestAll: () -> Unit, onUpdateAll: () -> Unit, onExport: () -> Unit): List<RvStatsTool> {
    val format = rememberRvFormat()
    val nodes = pluralStringResource(R.plurals.rv_stats_node_count, state.nodes.size, state.nodes.size)
    val subscriptions = pluralStringResource(R.plurals.rv_stats_subscriptions, state.subscriptions.size, state.subscriptions.size)
    val refreshed = state.subscriptions.mapNotNull { it.updatedAtEpochMs }.maxOrNull()?.let {
        stringResource(R.string.rv_updated, format.time(it))
    }
    return listOf(
        RvStatsTool(R.drawable.rv_sym_speed, stringResource(R.string.rv_stats_test_all), nodes, onTestAll),
        RvStatsTool(R.drawable.rv_stats_ic_sync, stringResource(R.string.rv_stats_update_all), listOfNotNull(subscriptions, refreshed).joinToString(SEPARATOR), onUpdateAll),
        RvStatsTool(R.drawable.rv_sym_ios_share, stringResource(R.string.rv_stats_export), stringResource(R.string.rv_stats_export_detail), onExport),
    )
}
