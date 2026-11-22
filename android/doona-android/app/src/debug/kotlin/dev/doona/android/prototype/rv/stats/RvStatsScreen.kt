package dev.doona.android.prototype.rv.stats

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.R
import dev.doona.android.designsystem.theme.LocalFrameInsets
import dev.doona.android.feature.NOT_WIRED
import dev.doona.android.prototype.rv.RV_NO_VALUE
import dev.doona.android.prototype.rv.Rv
import dev.doona.android.prototype.rv.RvFigure
import dev.doona.android.prototype.rv.RvFilterChip
import dev.doona.android.prototype.rv.RvGutter
import dev.doona.android.prototype.rv.RvLargeTitle
import dev.doona.android.prototype.rv.RvScrollPage
import dev.doona.android.prototype.rv.RvSpace
import dev.doona.android.prototype.rv.RvTheme
import dev.doona.android.prototype.rv.data.RvDay
import dev.doona.android.prototype.rv.figure
import dev.doona.android.prototype.rv.rememberRvFormat
import dev.doona.android.prototype.rv.rvSystemScheme
import java.text.NumberFormat

/**
 * The 統計 page in the language of Revolut's Analytics page: the back disc and the large title, the subscription
 * and range chips, then the cards. Each card names its v0.1 source at its foot, and a card whose data v0.1 lacks carries
 * a `v0.2` tag: 已用流量 and the paged tiles (the app-side daily history; 連線次數 is v0.2), 訂閱流量 (the subscriptions'
 * quota), 節點分布 (node names and flags), 延遲分布 (latency results), 分流比例 (v0.2) and the tools. The chips and the
 * tools are not wired in the sample. [initialTile] is the paged tile the row starts on.
 */
@Composable
internal fun RvStatsScreen(state: RvStatsState, onBack: () -> Unit, initialTile: Int = 0) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_stats_title)
    RvScrollPage(title, insets, onBack = onBack) { titleBlock ->
        RvLargeTitle(title, titleBlock, insets)
        Row(Modifier.fillMaxWidth().padding(horizontal = RvSpace.gutter), verticalAlignment = Alignment.CenterVertically) {
            RvFilterChip(stringResource(R.string.rv_stats_all_subscriptions), NOT_WIRED)
            Spacer(Modifier.weight(1f))
            RvFilterChip(stringResource(state.range.label), NOT_WIRED)
        }
        Spacer(Modifier.height(RvStatsSpace.chipsGap))
        RvGutter { RvSpentCard(state) }
        Spacer(Modifier.height(RvSpace.cardGap))
        DayTiles(state.today, initialTile)
        Spacer(Modifier.height(RvSpace.cardGap))
        RvGutter {
            RvQuotaCard(state.subscriptions, state.nowEpochMs)
            RvNodeMapCard(state)
            RvLatencyCard(state)
            RvSplitCard(state.split)
            RvToolsCard(tools(state), onTool = { NOT_WIRED() })
        }
        Spacer(Modifier.height(RvSpace.cardGap))
        Spacer(Modifier.windowInsetsBottomHeight(insets))
    }
}

/**
 * 下載, 上傳 and 連線次數 as Revolut's paged half tiles: two side by side, the third peeking at the edge, dots under them.
 * Download and upload come from the daily history; a count of opened connections needs the core, so that tile is v0.2.
 */
@Composable
private fun DayTiles(day: RvDay, initialTile: Int) {
    val locale = LocalConfiguration.current.locales[0]
    val format = rememberRvFormat()
    val source = stringResource(R.string.rv_stats_source, stringResource(R.string.rv_stats_source_traffic))
    val count = day.connections?.sum()?.let { NumberFormat.getIntegerInstance(locale).format(it) }
    val tiles = listOf(
        RvDayTile(stringResource(R.string.ui_download), format.bytes(day.downTotal).figure(), source, day.down, Rv.colors.down.line, v02 = false),
        RvDayTile(stringResource(R.string.ui_upload), format.bytes(day.upTotal).figure(), source, day.up, Rv.colors.up.line, v02 = false),
        RvDayTile(
            stringResource(R.string.rv_stats_connections),
            RvFigure(count ?: RV_NO_VALUE, ""),
            stringResource(R.string.rv_stats_needs, stringResource(R.string.rv_stats_needs_connections)),
            day.connections?.map { it.toLong() },
            Rv.colors.textSecondary,
            v02 = true,
        ),
    )
    val pager = rememberPagerState(initialTile) { tiles.size }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = (maxWidth - RvSpace.gutter * 2 - RvStatsSpace.tileGap) / 2
        Column {
            HorizontalPager(
                pager,
                contentPadding = PaddingValues(horizontal = RvSpace.gutter),
                pageSize = PageSize.Fixed(width),
                pageSpacing = RvStatsSpace.tileGap,
                verticalAlignment = Alignment.Top,
            ) { page -> RvDayTileCard(tiles[page]) }
            Spacer(Modifier.height(RvStatsSpace.dotsGap))
            RvStatsDots(tiles.size, pager.currentPage)
        }
    }
}

/** The tools card's rows: test every node, refresh every subscription, export what a bug report needs. */
@Composable
private fun tools(state: RvStatsState): List<RvStatsTool> {
    val format = rememberRvFormat()
    val nodes = pluralStringResource(R.plurals.rv_stats_node_count, state.nodes.size, state.nodes.size)
    val subscriptions = pluralStringResource(R.plurals.rv_stats_subscriptions, state.subscriptions.size, state.subscriptions.size)
    val refreshed = state.subscriptions.mapNotNull { it.updatedAtEpochMs }.maxOrNull()?.let {
        stringResource(R.string.rv_updated, format.time(it))
    }
    return listOf(
        RvStatsTool(R.drawable.rv_sym_speed, stringResource(R.string.rv_stats_test_all), nodes),
        RvStatsTool(R.drawable.rv_stats_ic_sync, stringResource(R.string.rv_stats_update_all), listOfNotNull(subscriptions, refreshed).joinToString(" · ")),
        RvStatsTool(R.drawable.rv_stats_ic_bug_report, stringResource(R.string.rv_stats_export), stringResource(R.string.rv_stats_export_detail)),
    )
}

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 2200)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 2200, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvStatsScreenPreview() {
    val dark = isSystemInDarkTheme()
    RvTheme(rvSystemScheme(dark), dark) { RvStatsScreen(rvStatsSample(), onBack = {}) }
}

@Preview(name = "Quiet", locale = "zh-rTW", widthDp = 412, heightDp = 2200)
@Composable
private fun RvStatsQuietPreview() {
    RvTheme(rvSystemScheme(false), false) { RvStatsScreen(rvStatsQuiet(), onBack = {}) }
}
