package dev.doona.android.prototype.rv.samples

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.LocalRvTitleGap
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvCardHeader
import dev.doona.android.designsystem.rv.RvFlag
import dev.doona.android.designsystem.rv.RvPlace
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvSize
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvSwitchRow
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.nodes.RvNodePickRow

/**
 * The title-to-subtitle gap, side by side: one column for each of [gaps], each a card with the same three rows (a
 * settings switch row with a two-line subtitle, a list row with a flag and a subtitle, a node row), their titles and
 * subtitles [LocalRvTitleGap] apart. LAYOUT-RULES Table 0 sets the gap to 0, the line boxes touching, as in Revolut; this
 * sheet is the owner's comparison, the library keeps 0. Each column is headed with its gap in dp.
 */
@Composable
internal fun RvTitleGapSheet(gaps: List<Dp>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().background(Rv.colors.page).verticalScroll(rememberScrollState())) {
        gaps.forEach { gap ->
            Column(Modifier.weight(1f).padding(horizontal = RvSpace.gutter)) {
                Spacer(Modifier.windowInsetsTopHeight(LocalFrameInsets.current ?: WindowInsets.systemBars))
                CompositionLocalProvider(LocalRvTitleGap provides gap) { GapColumn(gap) }
            }
        }
    }
}

@Composable
private fun GapColumn(gap: Dp) {
    val node = RvData.nodes.first()
    RvCard {
        RvCardHeader("${gap.value.toInt()} dp")
        RvSwitchRow(
            R.drawable.rv_sym_speed,
            sampleText("效能模式", "Performance mode"),
            true,
            sampleText("使用更多工作執行緒換取速度，耗電較多。下次開啟 App 時生效。", "Uses more worker threads for speed and more battery. Takes effect the next time the app starts."),
            onChange = {},
        )
        RvRow(
            { RvFlag(RvPlace.JP.flag, RvSize.rowDisc) },
            stringResource(RvPlace.JP.label),
            sampleText("TCP/UDP · 12 個節點", "TCP/UDP · 12 nodes"),
            onClick = {},
        )
        RvNodePickRow(node, stringResource(node.place.label), current = false, order = 0, onPick = {}, onDetail = {})
    }
}
