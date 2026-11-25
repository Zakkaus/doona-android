package dev.doona.android.designsystem.rv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import kotlin.math.roundToInt

/** A thin pill bar, #13's goal progress: [fraction] of it in the positive colour on the raised track. */
@Composable
internal fun RvProgress(fraction: Float, modifier: Modifier = Modifier) = RvProgress({ fraction }, modifier)

/**
 * [RvProgress] with its [fraction] read as the fill is measured rather than composed, so a fill that moves every frame
 * ([RvBusy]) measures the fill alone, in a track of a fixed size, and composes nothing.
 */
@Composable
internal fun RvProgress(fraction: () -> Float, modifier: Modifier = Modifier) =
    RvProgressBar(fraction, modifier, { ProgressBarRangeInfo(fraction().coerceIn(0f, 1f), 0f..1f) })

/** The bar of [RvProgress], telling a screen reader [info] (and [description], when it has one): what the bar stands for. */
@Composable
internal fun RvProgressBar(fraction: () -> Float, modifier: Modifier, info: () -> ProgressBarRangeInfo, description: String? = null) {
    Box(
        modifier.fillMaxWidth().height(RvSize.progress).testTag(RvPart.FRAME).semantics {
            progressBarRangeInfo = info()
            description?.let { contentDescription = it }
        }.clip(CircleShape).background(Rv.colors.track),
    ) {
        Box(Modifier.rvFillWidth(fraction).fillMaxHeight().clip(CircleShape).background(Rv.colors.positive))
    }
}

/**
 * `fillMaxWidth(fraction())` with the fraction read while measuring: the content gets the same width, and the node keeps
 * the whole width, so a new fraction never measures its parent again.
 */
private fun Modifier.rvFillWidth(fraction: () -> Float): Modifier = layout { measurable, constraints ->
    val width = (constraints.maxWidth * fraction().coerceIn(0f, 1f)).roundToInt().coerceIn(constraints.minWidth, constraints.maxWidth)
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.placeRelative(0, 0) }
}
