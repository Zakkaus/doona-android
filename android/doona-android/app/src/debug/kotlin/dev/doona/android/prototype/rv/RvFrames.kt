package dev.doona.android.prototype.rv

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import kotlin.math.roundToInt

/**
 * Frame times per gesture (brief rv-gestures rule 7), for a phone run: while a gesture is under way ([begin] to [end])
 * each frame's metrics are kept, and at its end one line goes to logcat (`adb logcat -s RvFrames`), p50 and p95 in ms:
 *
 * - `cpu`: the frame's production time, the work its threads did for it. The UI thread's input, animation, measure
 *   and layout, and draw (recording), plus the RenderThread's sync, command issue and swap. It leaves out the wait
 *   before the frame started and the UI thread's wait for the RenderThread, so a lower refresh rate does not pad it to
 *   a vsync interval. Command issue can include a wait for a free buffer when the GPU or the display lags.
 * - `gpu`: the GPU's time for the frame (API 31 and later; 0 before).
 * - `total`: from the frame's intended vsync to its completion, waits included. This is what the earlier runs logged:
 *   at 60 Hz its p50 sits near 16.7 ms whatever the frame cost.
 * - `hz`: the rate the gesture's frames came at, from the median interval between their vsyncs.
 *
 * [attach] listens to the window's frame metrics (on their own thread); without it, as in tests, nothing is kept and
 * nothing is logged. Each gesture has fixed buffers, so a frame allocates nothing.
 */
internal object RvFrames {
    private const val TAG = "RvFrames"
    private const val CAPACITY = 2_048
    private const val NS_PER_MS = 1_000_000.0
    private const val NS_PER_S = 1_000_000_000.0
    private const val P50 = 0.5
    private const val P95 = 0.95

    private class Run {
        val cpu = LongArray(CAPACITY)
        val gpu = LongArray(CAPACITY)
        val total = LongArray(CAPACITY)
        val vsync = LongArray(CAPACITY)
        var count = 0
        var open = false
    }

    private val runs = mutableMapOf<String, Run>()
    private var listening = false

    /** Starts keeping frames for [gesture] (again from nothing if it was already under way). */
    fun begin(gesture: String) = synchronized(this) {
        if (!listening) return@synchronized
        val run = runs.getOrPut(gesture) { Run() }
        run.count = 0
        run.open = true
    }

    /** Ends [gesture] and logs its frames. */
    fun end(gesture: String) {
        val kept = synchronized(this) {
            val run = runs[gesture]?.takeIf { it.open } ?: return
            run.open = false
            val n = run.count
            listOf(run.cpu.copyOf(n), run.gpu.copyOf(n), run.total.copyOf(n), run.vsync.copyOf(n))
        }
        val (cpu, gpu, total, vsync) = kept
        if (cpu.isEmpty()) return
        val rate = hz(vsync)
        listOf(cpu, gpu, total).forEach { it.sort() }
        Log.i(
            TAG,
            "%s frames=%d hz=%d cpu p50=%.1f p95=%.1f gpu p50=%.1f p95=%.1f total p50=%.1f p95=%.1f".format(
                gesture, cpu.size, rate, ms(cpu, P50), ms(cpu, P95), ms(gpu, P50), ms(gpu, P95), ms(total, P50), ms(total, P95),
            ),
        )
    }

    private fun ms(sorted: LongArray, at: Double): Double = sorted[((sorted.size - 1) * at).toInt()] / NS_PER_MS

    /** The rate of frames whose vsyncs are [vsyncs], in order: one over their median interval; 0 with one frame. */
    private fun hz(vsyncs: LongArray): Int {
        if (vsyncs.size < 2) return 0
        val gaps = LongArray(vsyncs.size - 1) { vsyncs[it + 1] - vsyncs[it] }.apply { sort() }
        val median = gaps[gaps.size / 2]
        return if (median > 0) (NS_PER_S / median).roundToInt() else 0
    }

    private fun frame(metrics: FrameMetrics) {
        val cpu = metrics.getMetric(FrameMetrics.INPUT_HANDLING_DURATION) +
            metrics.getMetric(FrameMetrics.ANIMATION_DURATION) +
            metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION) +
            metrics.getMetric(FrameMetrics.DRAW_DURATION) +
            metrics.getMetric(FrameMetrics.SYNC_DURATION) +
            metrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION) +
            metrics.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION)
        val gpu = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) metrics.getMetric(FrameMetrics.GPU_DURATION) else 0L
        val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
        val vsync = metrics.getMetric(FrameMetrics.VSYNC_TIMESTAMP)
        synchronized(this) {
            for (run in runs.values) {
                if (!run.open || run.count >= CAPACITY) continue
                run.cpu[run.count] = cpu
                run.gpu[run.count] = gpu
                run.total[run.count] = total
                run.vsync[run.count] = vsync
                run.count++
            }
        }
    }

    /** Listens to [window]'s frames for as long as it lives. */
    fun attach(window: Window) {
        val thread = HandlerThread(TAG).apply { start() }
        synchronized(this) { listening = true }
        window.addOnFrameMetricsAvailableListener({ _, metrics, _ -> frame(metrics) }, Handler(thread.looper))
    }
}
