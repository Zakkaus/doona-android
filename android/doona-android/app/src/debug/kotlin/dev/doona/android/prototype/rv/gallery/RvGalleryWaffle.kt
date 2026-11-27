package dev.doona.android.prototype.rv.gallery

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvCardHeader
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvWaffle
import dev.doona.android.designsystem.rv.RvWaffleShare

/**
 * The library's waffle (`proto-rv-waffle`) as DNS 統計's 回應結果 draws it: a hundred cells over four outcomes, one of
 * them under a cell's worth yet still drawn as one, with the key under the grid; first with the counts at the key's
 * end, as the web's legend gives them, then the key alone in a line, for a card whose rows give the figures.
 */
@Composable
internal fun RvGalleryWaffle() {
    val hues = listOf(Rv.colors.positive, Rv.colors.up.line, Rv.colors.fair, Rv.colors.error)
    Column {
        RvCard {
            RvCardHeader(TITLE)
            RvWaffle(
                WAFFLE_LABELS.indices.map { i -> RvWaffleShare(WAFFLE_LABELS[i], hues[i], WAFFLE_COUNTS[i], "${WAFFLE_COUNTS[i]} 筆") },
                SPOKEN,
                Modifier.padding(start = RvSpace.rowPadding, end = RvSpace.rowPadding, bottom = RvSpace.rowPadding),
            )
        }
        RvCard(Modifier.padding(top = RvSpace.cardGap)) {
            RvWaffle(
                WAFFLE_LABELS.indices.map { i -> RvWaffleShare(WAFFLE_LABELS[i], hues[i], WAFFLE_COUNTS[i]) },
                SPOKEN,
                Modifier.padding(RvSpace.rowPadding),
            )
        }
    }
}

private const val TITLE = "回應結果"
internal val WAFFLE_LABELS = listOf("快取命中", "上游回答", "查無此名", "失敗")

/** 412 lookups: a third from the cache, most of the rest answered, and one denied name, a quarter of a cell. */
internal val WAFFLE_COUNTS = listOf(143, 249, 1, 19)
internal const val SPOKEN = "回應結果：快取命中 35%，上游回答 60%，查無此名 <1%，失敗 5%"
