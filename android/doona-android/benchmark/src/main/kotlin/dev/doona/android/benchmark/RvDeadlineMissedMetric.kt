package dev.doona.android.benchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.Metric
import androidx.benchmark.macro.TraceMetric
import androidx.benchmark.traceprocessor.TraceProcessor

/**
 * Perfetto's frame timeline for the measured app, as the perf rounds counted it by hand (plans/perf5/an.py):
 * `appDeadlineMissed`, the frames whose jank type includes App Deadline Missed (the app's own frame finished past the
 * deadline the timeline gave it; a frame that only queued early and waited, Buffer Stuffing, is not one), and
 * `timelineFrames`, every frame of the app's layer in the trace.
 */
@OptIn(ExperimentalMetricApi::class)
class RvDeadlineMissedMetric : TraceMetric() {
    override fun getMeasurements(captureInfo: Metric.CaptureInfo, traceSession: TraceProcessor.Session): List<Metric.Measurement> {
        val counts = runCatching {
            traceSession.query(
                """
            select count(*) as frames, sum(jank_type like '%App Deadline Missed%') as missed
            from actual_frame_timeline_slice
            where layer_name like '%${captureInfo.targetPackageName}%'
                """.trimIndent(),
            ).single()
        }.getOrNull() ?: return emptyList()
        return listOf(
            Metric.Measurement("appDeadlineMissed", (counts.nullableLong("missed") ?: 0L).toDouble()),
            Metric.Measurement("timelineFrames", counts.long("frames").toDouble()),
        )
    }
}
