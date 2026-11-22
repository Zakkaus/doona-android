package dev.doona.android.prototype.rv

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.FrameMetrics
import android.view.Window

/**
 * Frame times per gesture (brief rv-gestures rule 7), for a phone run: while a gesture is under way ([begin] to [end])
 * every frame's total time is kept, and at its end one line goes to logcat with the frame count, p50 and p95 in ms:
 * `adb logcat -s RvFrames`. [attach] listens to the window's frame metrics (on their own thread); without it, as in
 * tests, nothing is kept and nothing is logged. Each gesture has a fixed buffer, so a frame allocates nothing.
 */
internal object RvFrames {
    private const val TAG = "RvFrames"
    private const val CAPACITY = 2_048
    private const val NS_PER_MS = 1_000_000.0
    private const val P50 = 0.5
    private const val P95 = 0.95

    private class Run {
        val frames = LongArray(CAPACITY)
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
        val frames = synchronized(this) {
            val run = runs[gesture]?.takeIf { it.open } ?: return
            run.open = false
            run.frames.copyOf(run.count)
        }
        if (frames.isEmpty()) return
        frames.sort()
        Log.i(TAG, "%s frames=%d p50=%.1f ms p95=%.1f ms".format(gesture, frames.size, ms(frames, P50), ms(frames, P95)))
    }

    private fun ms(sorted: LongArray, at: Double): Double = sorted[((sorted.size - 1) * at).toInt()] / NS_PER_MS

    private fun frame(ns: Long) = synchronized(this) {
        for (run in runs.values) if (run.open && run.count < CAPACITY) run.frames[run.count++] = ns
    }

    /** Listens to [window]'s frames for as long as it lives. */
    fun attach(window: Window) {
        val thread = HandlerThread(TAG).apply { start() }
        synchronized(this) { listening = true }
        window.addOnFrameMetricsAvailableListener({ _, metrics, _ -> frame(metrics.getMetric(FrameMetrics.TOTAL_DURATION)) }, Handler(thread.looper))
    }
}
