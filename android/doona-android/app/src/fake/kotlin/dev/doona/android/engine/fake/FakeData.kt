package dev.doona.android.engine.fake

import dev.doona.android.engine.NodeInfo
import dev.doona.android.engine.RuleRow
import dev.doona.android.engine.RuleSource
import dev.doona.android.engine.ScoreState
import dev.doona.android.engine.TodayTraffic
import dev.doona.android.engine.TrafficSeries
import dev.doona.android.engine.Usage

internal data class FakeData(
    val nodes: List<NodeInfo>,
    val usage: Usage = FakeRuleMode.USAGE,
    val today: TodayTraffic = TodayTraffic(usage.today.up.sum(), usage.today.down.sum()),
    val source: RuleSource = FakeRuleMode.SOURCE,
    val rows: List<RuleRow> = FakeRuleMode.ROWS,
    val configLines: List<String> = FakeRuleMode.LINES,
    val series: SampleSeries? = null,
    val scoreState: ScoreState,
)

internal class SampleSeries(private val endEpochMs: Long, private val up: List<Long>, private val down: List<Long>) {
    fun window(seconds: Int): TrafficSeries {
        val step = (seconds + MAX_POINTS - 1) / MAX_POINTS
        val count = (seconds + step - 1) / step
        val (upPoints, upPeak) = up.points(count, step)
        val (downPoints, downPeak) = down.points(count, step)
        return TrafficSeries(step.toLong(), endEpochMs, upPoints, downPoints, upPeak, downPeak)
    }

    private fun List<Long>.points(count: Int, step: Int): Pair<List<Long>, Long> {
        var index = size - count * step
        var peak = 0L
        val points = List(count) {
            var total = 0L
            repeat(step) {
                val rate = getOrElse(index++) { 0L }
                total += rate
                peak = maxOf(peak, rate)
            }
            total / step
        }
        return points to peak
    }

    private companion object {
        const val MAX_POINTS = 300
    }
}
