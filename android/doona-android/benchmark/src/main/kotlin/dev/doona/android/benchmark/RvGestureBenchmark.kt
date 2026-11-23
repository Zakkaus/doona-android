package dev.doona.android.benchmark

import android.content.ComponentName
import android.content.Intent
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The RV prototype's gestures, measured as the perf rounds did by hand (plans/perf5/one.sh), [ITERATIONS] times each on
 * the app's profile build, its baseline profile compiled in: the process killed, the prototype started and left
 * [SETTLE_MS], then a trace of [LEAD_MS] at rest, the gesture, and [TAIL_MS] after it. Each gesture reports
 * [RvDeadlineMissedMetric]'s App Deadline Missed count and [FrameTimingMetric]'s frame times.
 *
 * The gestures are the same `input` commands, in the pixels of the 1080 x 2410 phone the perf rounds used: [pull] pulls
 * 活動 down past the threshold, [reveal] further, [close] backs out of the open picker, [search] taps the search pill,
 * [node] taps the main node's row.
 *
 * All of them: `./gradlew -Pdoona.skipCore=true :benchmark:connectedProfileAndroidTest`; one of them: add
 * `-Pandroid.testInstrumentationRunnerArguments.class=dev.doona.android.benchmark.RvGestureBenchmark#pull`. Results
 * (min, median, max of each metric per gesture) and each iteration's Perfetto trace land in
 * `benchmark/build/outputs/connected_android_test_additional_output/profileAndroidTest/connected/<device>/`.
 */
@RunWith(AndroidJUnit4::class)
class RvGestureBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun pull() = measure { shell(PULL) }

    @Test
    fun reveal() = measure { shell(REVEAL) }

    @Test
    fun close() = measure(
        setup = {
            shell(REVEAL)
            Thread.sleep(OPEN_MS)
        },
    ) { shell(BACK) }

    @Test
    fun search() = measure { shell(SEARCH) }

    @Test
    fun node() = measure { shell(NODE) }

    @OptIn(ExperimentalMetricApi::class)
    private fun measure(setup: MacrobenchmarkScope.() -> Unit = {}, gesture: MacrobenchmarkScope.() -> Unit) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(RvDeadlineMissedMetric(), FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
        iterations = ITERATIONS,
        setupBlock = {
            killProcess()
            startActivityAndWait(Intent().setComponent(ComponentName(PACKAGE, ACTIVITY)))
            Thread.sleep(SETTLE_MS)
            setup()
        },
    ) {
        Thread.sleep(LEAD_MS)
        gesture()
        Thread.sleep(TAIL_MS)
    }

    /** Runs an `input` command on the phone; it returns once the gesture is over. */
    private fun MacrobenchmarkScope.shell(command: String) {
        device.executeShellCommand(command)
    }

    private companion object {
        const val PACKAGE = "dev.doona.android"
        const val ACTIVITY = "dev.doona.android.prototype.rv.RvPreviewActivity"
        const val ITERATIONS = 10
        const val SETTLE_MS = 3_000L
        const val LEAD_MS = 1_500L
        const val TAIL_MS = 3_500L
        const val OPEN_MS = 2_500L
        const val PULL = "input swipe 540 500 540 900 600"
        const val REVEAL = "input swipe 540 500 540 1400 600"
        const val BACK = "input keyevent BACK"
        const val SEARCH = "input tap 540 260"
        const val NODE = "input tap 540 1650"
    }
}
