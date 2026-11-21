package dev.tunbench.preview.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModel
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.LineCartesianLayerModel
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.common.Fill

// Vico chart. Line width and interpolation are Vico's; colours come from the tokens.

/**
 * The live throughput as an area from zero under its line, on a fixed 0–[maxY] Gbit/s range so the
 * height is the level. No axes, so no guidelines or labels. Vico's cubic interpolator draws curves
 * between samples.
 */
@Composable
fun ThroughputArea(points: List<Double>, maxY: Double, lineColor: Color, fillColor: Color, modifier: Modifier = Modifier) {
    val model = remember(points) { CartesianChartModel(LineCartesianLayerModel.build { series(points) }) }
    val line = LineCartesianLayer.rememberLine(
        fill = LineCartesianLayer.LineFill.single(Fill(lineColor)),
        areaFill = LineCartesianLayer.AreaFill.single(Fill(fillColor)),
        interpolator = LineCartesianLayer.Interpolator.cubic(),
    )
    val chart = rememberCartesianChart(
        rememberLineCartesianLayer(
            lineProvider = LineCartesianLayer.LineProvider.series(line),
            rangeProvider = remember(maxY) { CartesianLayerRangeProvider.fixed(minY = 0.0, maxY = maxY) },
        ),
    )
    CartesianChartHost(
        chart = chart,
        model = model,
        modifier = modifier,
        scrollState = rememberVicoScrollState(scrollEnabled = false),
    )
}
