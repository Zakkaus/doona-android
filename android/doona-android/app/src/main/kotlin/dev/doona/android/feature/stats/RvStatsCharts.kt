package dev.doona.android.feature.stats

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import dev.doona.android.designsystem.rv.RvChartGrid
import dev.doona.android.designsystem.rv.RvChartSize
import dev.doona.android.designsystem.rv.RvEmptyChartLine
import dev.doona.android.designsystem.rv.RvPillInk
import dev.doona.android.designsystem.rv.RvUsageSize
import dev.doona.android.designsystem.rv.latencyColor
import dev.doona.android.designsystem.rv.pillName
import dev.doona.android.designsystem.rv.rvChartMarker
import dev.doona.android.designsystem.rv.rvLatencyAxisTop
import dev.doona.android.designsystem.rv.rvMarkerChip
import dev.doona.android.designsystem.rv.rvPillSize
import dev.doona.android.designsystem.rv.rvValuePill
import dev.doona.android.designsystem.rv.scripted
import kotlin.math.abs

/**
 * An empty chart (Addendum A): the grid of [slots] hours or days alone with a short [line] on it; never bars that were
 * not measured.
 */
@Composable
internal fun RvEmptyChart(line: String, modifier: Modifier = Modifier, slots: Int = HOURS) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        RvChartGrid(slots, gridEvery(slots), Modifier.matchParentSize())
        RvEmptyChartLine(line)
    }
}

/** The day's hours on the x axis of the empty grid and the hour bars, as 今天 draws them. */
internal const val HOURS = 24
