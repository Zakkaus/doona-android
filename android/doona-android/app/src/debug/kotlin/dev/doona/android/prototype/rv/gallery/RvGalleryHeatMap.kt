package dev.doona.android.prototype.rv.gallery

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvCardHeader
import dev.doona.android.designsystem.rv.RvHeatMap
import dev.doona.android.designsystem.rv.RvHeatRow
import dev.doona.android.designsystem.rv.RvSpace

/**
 * The library's heat map (`proto-rv-heat-map`) as the log page's 日誌時間分布 draws it: a row per level from the most
 * severe, 24 columns of five minutes from 10:00, each row's hue in every one of its five tones and the empty track, and
 * the first, middle and last times under the cells.
 */
@Composable
internal fun RvGalleryHeatMap() {
    val hues = listOf(Rv.colors.error, Rv.colors.fair, Rv.colors.positive, Rv.colors.textSecondary)
    RvCard {
        RvCardHeader(TITLE)
        RvHeatMap(
            HEAT_LEVELS.indices.map { i -> RvHeatRow(HEAT_LEVELS[i], hues[i], HEAT_COUNTS[i], "${HEAT_LEVELS[i]}：${HEAT_COUNTS[i].sum()} 筆") },
            HEAT_TIMES,
            Modifier.padding(start = RvSpace.rowPadding, end = RvSpace.rowPadding, bottom = RvSpace.rowPadding),
        )
    }
}

private const val TITLE = "日誌時間分布"
internal val HEAT_LEVELS = listOf("錯誤", "警告", "資訊", "除錯")

/** Errors in two bursts, warnings around them, info all along with a peak, debug only while it was on. */
internal val HEAT_COUNTS = listOf(
    listOf(0, 0, 0, 1, 3, 5, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 4, 1, 0, 0, 0, 0, 0),
    listOf(1, 0, 0, 2, 4, 3, 1, 0, 0, 1, 0, 0, 0, 0, 0, 1, 2, 3, 2, 0, 0, 0, 1, 0),
    listOf(12, 9, 14, 20, 31, 40, 22, 15, 11, 9, 8, 10, 12, 9, 8, 11, 18, 27, 16, 10, 9, 8, 7, 9),
    listOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 30, 52, 61, 44, 18, 0, 0, 0, 0, 0, 0, 0, 0, 0),
)
private val HEAT_TIMES = List(24) { "%02d:%02d".format(10 + it * 5 / 60, it * 5 % 60) }
